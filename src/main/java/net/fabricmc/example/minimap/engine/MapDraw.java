package net.fabricmc.example.minimap.engine;

import net.minecraft.src.Tessellator;
import org.lwjgl.opengl.GL11;

/**
 * Draws the explored map from region textures into an arbitrary convex screen
 * shape (minimap circle/square, or the fullscreen map rectangle), with free
 * scrolling, zoom and rotation. Per frame this costs one textured polygon per
 * visible region; no pixels are touched on the CPU.
 */
public final class MapDraw {
	private static final int FULL_RES_REGION_LIMIT = 48;
	private static final int MAX_SHAPE_VERTICES = PolyClip.MAX_VERTICES - 8;

	private static final PolyClip CLIP = new PolyClip();
	private static final double[] worldX = new double[MAX_SHAPE_VERTICES];
	private static final double[] worldZ = new double[MAX_SHAPE_VERTICES];

	private MapDraw() {
	}

	/**
	 * Expects: texturing enabled, blending enabled, depth test / culling / alpha
	 * test disabled. Leaves the blend function set to premultiplied alpha
	 * (GL_ONE, GL_ONE_MINUS_SRC_ALPHA) and an arbitrary texture bound.
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
		boolean overview = realScale < 0.5 || regionCount > FULL_RES_REGION_LIMIT;
		// Crisp texels when they are large or land exactly on the pixel grid, smooth otherwise.
		double nearest = Math.rint(realScale);
		boolean pixelAligned = rotation == 0.0 && nearest >= 1.0 && Math.abs(realScale - nearest) < 0.001;
		int magFilter = (realScale >= 1.99 || pixelAligned) ? GL11.GL_NEAREST : GL11.GL_LINEAR;
		// Mipmaps only when actually shrinking; at 1:1 and above sample the full-resolution level directly.
		int minFilter = realScale >= 0.999 ? magFilter
				: (MapStorage.mipmapsSupported() ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);

		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glColor4f(alpha, alpha, alpha, alpha);
		Tessellator t = Tessellator.instance;
		boolean complete = true;

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

				GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
				if (fullRes) {
					GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter);
					GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
				}

				double[] px = CLIP.outX, pz = CLIP.outZ;
				t.startDrawing(GL11.GL_TRIANGLE_FAN);
				for (int i = 0; i < m; i++) {
					double dx = px[i] - centerX, dz = pz[i] - centerZ;
					t.addVertexWithUV(originX + (cos * dx - sin * dz) * scale, originY + (sin * dx + cos * dz) * scale,
							0.0, (px[i] - ox) / 512.0, (pz[i] - oz) / 512.0);
				}
				t.draw();
			}
		}
		return complete;
	}
}
