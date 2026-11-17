package net.fabricmc.example.minimap.engine;

import net.minecraft.src.Tessellator;
import org.lwjgl.opengl.GL11;

/**
 * Draws the explored map from region textures into an arbitrary convex screen
 * shape (minimap circle/square, or the fullscreen map rectangle), with free
 * scrolling, zoom and rotation. Per frame this costs one textured polygon per
 * visible region; no pixels are touched on the CPU.
 *
 * Sampling is chosen for sharpness:
 * <ul>
 * <li>blocks a whole number of screen pixels wide: plain nearest sampling (exact);</li>
 * <li>any other size, or rotated: {@link SharpShader} (solid blocks, smooth edges);</li>
 * <li>smaller than a pixel: the matching mipmap level, sampled nearest, so each
 * screen pixel is the clean average of the blocks it covers.</li>
 * </ul>
 * Callers that do not rotate should pass a view centre from {@link #snap} so that
 * block edges land exactly on screen pixels.
 */
public final class MapDraw {
	private static final int FULL_RES_REGION_LIMIT = 48;
	private static final int MAX_SHAPE_VERTICES = PolyClip.MAX_VERTICES - 8;
	private static final int LOD_BLOCKS_PER_TEXEL = RegionCodec.REGION_SIZE / RegionCodec.LOD_SIZE;

	/** Use {@link SharpShader} where it helps (set from the config each frame). */
	public static boolean sharpScaling = true;
	/** Zoomed out: true shows one real block per pixel (more contrast), false the average of the blocks it covers. */
	public static boolean pointSampleZoomedOut = false;

	private static final int MODE_NEAREST = 0;
	private static final int MODE_SHARP = 1;
	private static final int MODE_LINEAR = 2;
	private static final int MODE_MIP_NEAREST = 3;
	private static final int MODE_MIP_LINEAR = 4;

	private static final PolyClip CLIP = new PolyClip();
	private static final double[] worldX = new double[MAX_SHAPE_VERTICES];
	private static final double[] worldZ = new double[MAX_SHAPE_VERTICES];

	private MapDraw() {
	}

	/**
	 * Moves a view centre by less than half a screen pixel so that block edges
	 * fall exactly on the physical pixel grid. Use the result for everything
	 * drawn in the same frame (terrain and markers), so they stay together.
	 *
	 * @param center   world coordinate shown at {@code origin}
	 * @param origin   screen position in GUI pixels
	 * @param scale    GUI pixels per block
	 * @param guiScale physical pixels per GUI pixel
	 */
	public static double snap(double center, double origin, double scale, int guiScale) {
		double real = scale * guiScale;
		if (!(real > 0))
			return center;
		// Physical pixel position of world coordinate 0, rounded to a pixel edge.
		double zeroAt = Math.rint(origin * guiScale - center * real);
		return (origin * guiScale - zeroAt) / real;
	}

	private static int filterMode(double pixelsPerTexel, boolean rotated) {
		boolean shader = sharpScaling && SharpShader.available();
		if (pixelsPerTexel >= 0.999) {
			boolean whole = Math.abs(pixelsPerTexel - Math.rint(pixelsPerTexel)) < 0.001;
			if (whole && !rotated)
				return MODE_NEAREST;
			if (shader)
				return MODE_SHARP;
			return rotated && pixelsPerTexel < 1.99 ? MODE_LINEAR : MODE_NEAREST;
		}
		if (!MapStorage.mipmapsSupported())
			return rotated ? MODE_LINEAR : MODE_NEAREST;
		if (pointSampleZoomedOut && !rotated)
			return MODE_NEAREST;
		return rotated ? MODE_MIP_LINEAR : MODE_MIP_NEAREST;
	}

	private static void applyFilter(int mode) {
		int min, mag;
		switch (mode) {
			case MODE_SHARP:
			case MODE_LINEAR:
				min = GL11.GL_LINEAR;
				mag = GL11.GL_LINEAR;
				break;
			case MODE_MIP_NEAREST:
				min = GL11.GL_NEAREST_MIPMAP_NEAREST;
				mag = GL11.GL_NEAREST;
				break;
			case MODE_MIP_LINEAR:
				min = GL11.GL_LINEAR_MIPMAP_LINEAR;
				mag = GL11.GL_LINEAR;
				break;
			default:
				min = GL11.GL_NEAREST;
				mag = GL11.GL_NEAREST;
		}
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, min);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, mag);
	}

	/**
	 * Expects: texturing enabled, blending enabled, depth test / culling / alpha
	 * test disabled. Leaves the blend function set to premultiplied alpha
	 * (GL_ONE, GL_ONE_MINUS_SRC_ALPHA) and an arbitrary texture bound. The
	 * active shader program is restored.
	 *
	 * @param centerX,centerZ world position shown at the shape origin
	 * @param scale           GUI pixels per block
	 * @param rotation        radians; screen = R(rotation) * (world - center) * scale
	 * @param originX,originY screen position (GUI pixels) of the shape origin
	 * @param shapeX,shapeY   convex shape vertices relative to the origin, in GUI pixels
	 * @param guiScale        real pixels per GUI pixel
	 * @param loadRegions     true to load regions from disk as they come into view
	 * @return true when everything in view that exists was drawn at its final quality
	 */
	public static boolean draw(MapStorage storage, double centerX, double centerZ, double scale, double rotation,
			double originX, double originY, double[] shapeX, double[] shapeY, int n, float alpha, int guiScale,
			boolean loadRegions) {
		if (storage == null || n < 3 || n > MAX_SHAPE_VERTICES || scale <= 0)
			return true;

		double cos = Math.cos(rotation);
		double sin = Math.sin(rotation);
		double inv = 1.0 / scale;

		// Shape -> world space (inverse rotation), tracking its bounding box.
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			double sx = shapeX[i], sy = shapeY[i];
			double wx = centerX + (cos * sx + sin * sy) * inv;
			double wz = centerZ + (-sin * sx + cos * sy) * inv;
			worldX[i] = wx;
			worldZ[i] = wz;
			if (wx < minX)
				minX = wx;
			if (wx > maxX)
				maxX = wx;
			if (wz < minZ)
				minZ = wz;
			if (wz > maxZ)
				maxZ = wz;
		}

		int rx0 = (int) Math.floor(minX / 512.0), rx1 = (int) Math.floor(maxX / 512.0);
		int rz0 = (int) Math.floor(minZ / 512.0), rz1 = (int) Math.floor(maxZ / 512.0);
		long regionCount = (long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1);
		if (regionCount <= 0 || regionCount > 65536)
			return true;

		double realScale = scale * guiScale;
		boolean rotated = rotation != 0.0;
		boolean overview = realScale < 0.5 || regionCount > FULL_RES_REGION_LIMIT;
		int regionMode = filterMode(realScale, rotated);
		int lodMode = filterMode(realScale * LOD_BLOCKS_PER_TEXEL, rotated);

		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glColor4f(alpha, alpha, alpha, alpha);
		Tessellator t = Tessellator.instance;
		boolean complete = true;

		boolean shaderNeeded = regionMode == MODE_SHARP || lodMode == MODE_SHARP;
		int previousProgram = shaderNeeded ? SharpShader.begin() : 0;
		boolean shaderOn = shaderNeeded;
		try {
			for (int rz = rz0; rz <= rz1; rz++) {
				for (int rx = rx0; rx <= rx1; rx++) {
					if (!storage.exists(rx, rz))
						continue;

					int texture = -1;
					boolean fullRes = false;
					if (overview) {
						MapRegion r = storage.loaded(rx, rz);
						if (r != null) {
							texture = storage.texture(r);
							fullRes = texture != -1;
						}
						if (texture == -1)
							texture = storage.lodTexture(rx, rz);
					} else {
						MapRegion r = loadRegions ? storage.request(rx, rz, false) : storage.loaded(rx, rz);
						texture = storage.texture(r);
						fullRes = texture != -1;
						if (!fullRes) {
							// Still loading: show the overview in the meantime if it happens to be around.
							complete = false;
							texture = storage.cachedLodTexture(rx, rz);
						}
					}
					if (texture == -1) {
						complete = false;
						continue;
					}

					double ox = rx * 512.0, oz = rz * 512.0;
					int m = CLIP.clip(worldX, worldZ, n, ox, oz, ox + 512.0, oz + 512.0);
					if (m == 0)
						continue;

					int mode = fullRes ? regionMode : lodMode;
					GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
					applyFilter(mode);
					if (shaderNeeded) {
						boolean want = mode == MODE_SHARP;
						if (want != shaderOn) {
							SharpShader.use(want, previousProgram);
							shaderOn = want;
						}
						if (want)
							SharpShader.setTextureSize(fullRes ? RegionCodec.REGION_SIZE : RegionCodec.LOD_SIZE);
					}

					double[] px = CLIP.outX, pz = CLIP.outZ;
					t.startDrawing(GL11.GL_TRIANGLE_FAN);
					for (int i = 0; i < m; i++) {
						double dx = px[i] - centerX, dz = pz[i] - centerZ;
						t.addVertexWithUV(originX + (cos * dx - sin * dz) * scale,
								originY + (sin * dx + cos * dz) * scale, 0.0, (px[i] - ox) / 512.0, (pz[i] - oz) / 512.0);
					}
					t.draw();
				}
			}
		} finally {
			if (shaderNeeded)
				SharpShader.end(previousProgram);
		}
		return complete;
	}
}
