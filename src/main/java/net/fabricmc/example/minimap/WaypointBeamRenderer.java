package net.fabricmc.example.minimap;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.src.FontRenderer;
import net.minecraft.src.Minecraft;
import net.minecraft.src.RenderManager;
import net.minecraft.src.ResourceLocation;
import net.minecraft.src.Tessellator;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Waypoints in the world, JourneyMap style: a beacon beam in the waypoint's colour, and at the
 * waypoint a diamond with "Name (distance)" above it while you look towards it.
 *
 * The diamond and label are 2D: the camera of the world frame is remembered while the beams are
 * drawn, and the HUD projects each waypoint with it. That keeps them sharp and the same size at
 * any distance, and the right size at every GUI scale.
 */
public class WaypointBeamRenderer {
	/** Closer than this (horizontally) there is no beam; it fades in over the next few blocks. */
	private static final double BEAM_MIN_DISTANCE = 4.0;
	private static final double BEAM_FADE_DISTANCE = 12.0;
	/** Inner beam radius and outer glow half-width in blocks, as the game's beacon. */
	private static final double BEAM_RADIUS = 0.2, BEAM_GLOW = 0.3;
	/** Far beams are widened to stay at least this wide relative to their distance (2 to 3 pixels at 1080p). */
	private static final double BEAM_MIN_SCREEN_WIDTH = 0.0032;
	private static final ResourceLocation BEAM_TEXTURE = new ResourceLocation("textures/entity/beacon_beam.png");
	private static final int SPAWN_COLOR = 0x5599FF;
	/** Corner scratch for the beam sides (render thread only). */
	private static final double[] BEAM_X = new double[4], BEAM_Z = new double[4];
	/** Half the diagonal of the diamond marker, GUI pixels. */
	private static final double DIAMOND_RADIUS = 2.0;
	/** Labels show within about 15 degrees of where you look (as before). */
	private static final double FOCUS_COS = 0.966;
	/** Closer than this the label is hidden: you are there. */
	private static final double LABEL_MIN_DISTANCE = 3.0;

	private static final boolean IS_BETTER_GAME_SETTING_LOADED = FabricLoader.getInstance()
			.isModLoaded("better_game_setting");

	// Camera of the last world frame.
	private static final FloatBuffer MODELVIEW_BUF = BufferUtils.createFloatBuffer(16);
	private static final FloatBuffer PROJECTION_BUF = BufferUtils.createFloatBuffer(16);
	private static final IntBuffer VIEWPORT_BUF = BufferUtils.createIntBuffer(16);
	private static final double[] modelview = new double[16];
	private static final double[] projection = new double[16];
	private static final int[] viewport = new int[4];
	private static double camX, camY, camZ;
	private static boolean cameraValid;

	private static final class Label {
		String name;
		int color;
		double x, y, distance, focus;
	}

	private static final List<Label> labels = new ArrayList<Label>();
	private static final List<Label> pool = new ArrayList<Label>();
	private static final Comparator<Label> FAR_FIRST = new Comparator<Label>() {
		@Override
		public int compare(Label a, Label b) {
			return Double.compare(b.distance, a.distance);
		}
	};

	/** Called from the world render pass, before the hand: draws the beams and remembers the camera. */
	public static void renderAll(float partialTicks) {
		cameraValid = false;
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.theWorld == null || mc.thePlayer == null)
			return;
		if (!MapConfig.instance.showWaypoints)
			return;

		camX = RenderManager.renderPosX;
		camY = RenderManager.renderPosY;
		camZ = RenderManager.renderPosZ;
		captureCamera();

		// Beams are drawn up to just inside the render distance, pulled in along their direction if farther.
		double maxRender = 500.0;
		try {
			float farPlane = 256 >> mc.gameSettings.renderDistance;
			if (IS_BETTER_GAME_SETTING_LOADED) {
				farPlane = 32 * (float) Math.pow(2, (mc.gameSettings.renderDistance - 2) / 10.0);
			}
			farPlane = Math.max(32.0f, farPlane);
			maxRender = Math.min(500.0, farPlane * 0.95);
		} catch (Exception e) {
		}

		// Beacon-style beams, as JourneyMap draws them: the game's beacon beam texture tinted with the
		// waypoint colour, an inner beam that turns and scrolls upwards, and a faint glow around it.
		double time = mc.theWorld.getTotalWorldTime() % 8388471L + partialTicks;
		GL11.glPushMatrix();
		// Only the state touched below (pushing everything costs more on some drivers).
		GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_CURRENT_BIT
				| GL11.GL_LIGHTING_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_FOG_BIT);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_ALPHA_TEST); // no flickering at the faint edges
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_FOG);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
		GL11.glDepthMask(false);
		GL11.glShadeModel(GL11.GL_SMOOTH);
		boolean textured = false;
		try {
			mc.renderEngine.bindTexture(BEAM_TEXTURE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
			textured = true;
		} catch (Throwable ignored) {
		}
		if (textured)
			GL11.glEnable(GL11.GL_TEXTURE_2D);
		else
			GL11.glDisable(GL11.GL_TEXTURE_2D);
		try {
			if (MapConfig.instance.showSpawnWaypoint && SpawnTracker.instance.hasSpawn()) {
				int[] s = spawnPosition(mc);
				renderBeam(s[0], s[1], s[2], SPAWN_COLOR, maxRender, time);
			}
			List<Waypoint> waypoints = WaypointManager.instance.getWaypoints();
			for (int i = 0, n = waypoints.size(); i < n; i++) {
				Waypoint wp = waypoints.get(i);
				if (wp.enabled)
					renderBeam(wp.x, wp.y, wp.z, wp.color, maxRender, time);
			}
		} finally {
			GL11.glPopAttrib();
			GL11.glPopMatrix();
		}
	}

	private static int[] spawnPosition(Minecraft mc) {
		int dim = mc.theWorld.provider.dimensionId;
		if (dim == 1)
			return new int[] { 100, 49, 0 };
		if (dim == -1)
			return new int[] { SpawnTracker.instance.getNetherSpawnX(), SpawnTracker.instance.getSpawnY(),
					SpawnTracker.instance.getNetherSpawnZ() };
		return new int[] { SpawnTracker.instance.getSpawnX(), SpawnTracker.instance.getSpawnY(),
				SpawnTracker.instance.getSpawnZ() };
	}

	static void captureCamera() {
		MODELVIEW_BUF.clear();
		PROJECTION_BUF.clear();
		VIEWPORT_BUF.clear();
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW_BUF);
		GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION_BUF);
		GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT_BUF);
		for (int i = 0; i < 16; i++) {
			modelview[i] = MODELVIEW_BUF.get(i);
			projection[i] = PROJECTION_BUF.get(i);
		}
		for (int i = 0; i < 4; i++)
			viewport[i] = VIEWPORT_BUF.get(i);
		cameraValid = viewport[2] > 0 && viewport[3] > 0;
	}

	/**
	 * One beam, from the waypoint up to the top of the world. Beyond the render distance it is drawn
	 * at the edge of the view in the same direction; far away it gets wider, so it stays a few pixels
	 * wide on screen instead of thinning to a hairline. It fades out as you walk up to the waypoint.
	 */
	private static void renderBeam(int wx, int wy, int wz, int color, double maxRender, double time) {
		double dx = wx + 0.5 - camX;
		double dy = wy - camY;
		double dz = wz + 0.5 - camZ;
		double distH = Math.sqrt(dx * dx + dz * dz);
		if (distH < BEAM_MIN_DISTANCE)
			return;
		float fade = (float) Math.min(1.0, (distH - BEAM_MIN_DISTANCE) / BEAM_FADE_DISTANCE);
		double shown = distH;
		if (distH > maxRender) {
			double ratio = maxRender / distH;
			dx *= ratio;
			dy *= ratio;
			dz *= ratio;
			shown = maxRender;
		}
		double widen = Math.max(1.0, shown * BEAM_MIN_SCREEN_WIDTH / (2.0 * BEAM_RADIUS));
		double inner = BEAM_RADIUS * widen, outer = BEAM_GLOW * widen;
		double height = Math.max(64.0, 256.0 - wy);
		float r = (color >> 16 & 255) / 255.0F;
		float g = (color >> 8 & 255) / 255.0F;
		float b = (color & 255) / 255.0F;

		// Texture scrolls upwards over time; the inner beam turns slowly (as the game's beacon).
		double scroll = -time * 0.2 - Math.floor(-time * 0.1);
		double turn = time * 0.025;
		Tessellator t = Tessellator.instance;

		// Inner beam: four turning sides, added on top of the scene so it glows.
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
		t.startDrawingQuads();
		t.setColorRGBA_F(r, g, b, 0.55f * fade);
		double v0 = scroll - 1.0, v1 = v0 + height * (0.5 / BEAM_RADIUS) / widen;
		double[] cx = BEAM_X, cz = BEAM_Z;
		for (int i = 0; i < 4; i++) {
			double a = turn + Math.PI * (0.25 + 0.5 * i);
			cx[i] = dx + Math.cos(a) * inner;
			cz[i] = dz + Math.sin(a) * inner;
		}
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			t.addVertexWithUV(cx[i], dy + height, cz[i], 1.0, v1);
			t.addVertexWithUV(cx[i], dy, cz[i], 1.0, v0);
			t.addVertexWithUV(cx[j], dy, cz[j], 0.0, v0);
			t.addVertexWithUV(cx[j], dy + height, cz[j], 0.0, v1);
		}
		t.draw();

		// Outer glow: a faint square column.
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		t.startDrawingQuads();
		t.setColorRGBA_F(r, g, b, 0.2f * fade);
		double w0 = scroll - 1.0, w1 = w0 + height / widen;
		double[] ox = BEAM_X, oz = BEAM_Z;
		ox[0] = ox[3] = dx - outer;
		ox[1] = ox[2] = dx + outer;
		oz[0] = oz[1] = dz - outer;
		oz[2] = oz[3] = dz + outer;
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			t.addVertexWithUV(ox[i], dy + height, oz[i], 1.0, w1);
			t.addVertexWithUV(ox[i], dy, oz[i], 1.0, w0);
			t.addVertexWithUV(ox[j], dy, oz[j], 0.0, w0);
			t.addVertexWithUV(ox[j], dy + height, oz[j], 0.0, w1);
		}
		t.draw();
	}

	// ------------------------------------------------------------------ HUD labels

	/**
	 * Draws the waypoint labels on the HUD, from the camera remembered during this frame's world
	 * pass. Call from the in-game overlay (GUI coordinates set up).
	 */
	public static void renderHud(Minecraft mc, int guiWidth, int guiHeight, int guiScale) {
		if (!cameraValid)
			return;
		cameraValid = false; // one HUD pass per world frame
		MapConfig cfg = MapConfig.instance;
		FontRenderer fr = mc.fontRenderer;
		if (!cfg.showWaypoints || cfg.worldWaypointLabels == MapConfig.WORLD_LABELS_OFF || fr == null
				|| mc.theWorld == null)
			return;

		pool.addAll(labels);
		labels.clear();
		if (cfg.showSpawnWaypoint && SpawnTracker.instance.hasSpawn()) {
			int[] s = spawnPosition(mc);
			addLabel("Spawn", s[0], s[1], s[2], SPAWN_COLOR, guiScale, mc.displayHeight);
		}
		List<Waypoint> waypoints = WaypointManager.instance.getWaypoints();
		for (int i = 0, n = waypoints.size(); i < n; i++) {
			Waypoint wp = waypoints.get(i);
			if (wp.enabled)
				addLabel(wp.name, wp.x, wp.y, wp.z, wp.color, guiScale, mc.displayHeight);
		}
		if (labels.isEmpty())
			return;
		Collections.sort(labels, FAR_FIRST);

		boolean always = cfg.worldWaypointLabels == MapConfig.WORLD_LABELS_FULL;

		GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT);
		try {
			GL11.glDisable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_ALPHA_TEST);
			GL11.glDisable(GL11.GL_LIGHTING);
			GL11.glDisable(GL11.GL_CULL_FACE);
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			GL11.glShadeModel(GL11.GL_SMOOTH);
			Tessellator t = Tessellator.instance;
			double u = 1.0 / guiScale;
			for (Label l : labels) {
				// Farther labels first, so nearer ones are drawn over them. Snapped to physical pixels.
				double x = Math.rint(l.x * guiScale) / guiScale, y = Math.rint(l.y * guiScale) / guiScale;
				// The name and distance show while you look towards the waypoint (within about 15 degrees).
				String text = always || l.focus > FOCUS_COS
						? (l.name == null ? "" : l.name) + " (" + formatDistance(l.distance) + ")" : null;
				int textW = text != null ? fr.getStringWidth(text) : 0;

				GL11.glDisable(GL11.GL_TEXTURE_2D);
				t.startDrawing(GL11.GL_TRIANGLES);
				if (text != null)
					MapMarkers.rect(t, x - (textW - 1) / 2.0 - 3, y - 17, x + (textW - 1) / 2.0 + 3, y - 5, 0.06f, 0.06f,
							0.07f, 0.62f);
				diamondMarker(t, x, y, l.color, u);
				t.draw();

				if (text != null) {
					GL11.glEnable(GL11.GL_TEXTURE_2D);
					drawAt(fr, text, x - (textW - 1) / 2.0, y - 15, readable(l.color), true);
				}
			}
			GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
		} finally {
			GL11.glPopAttrib();
		}
	}

	/**
	 * The waypoint's diamond, small like JourneyMap's: all in its own colour, the fill a little
	 * lighter at the top, edged in a darker shade of the same colour. Edges are softened over one
	 * physical pixel, so the diagonals are smooth, not stepped.
	 */
	private static void diamondMarker(Tessellator t, double x, double y, int rgb, double pixel) {
		float r = (rgb >> 16 & 255) / 255.0f, g = (rgb >> 8 & 255) / 255.0f, b = (rgb & 255) / 255.0f;
		double edge = Math.max(pixel, 0.6) * Math.sqrt(2.0); // the edge, a pixel or so across
		float dark = 0.42f, lift = 0.18f;
		diamond(t, x, y, DIAMOND_RADIUS + edge, pixel, r * dark, g * dark, b * dark, r * dark, g * dark, b * dark,
				0.95f);
		diamond(t, x, y, DIAMOND_RADIUS, pixel, r + (1 - r) * lift, g + (1 - g) * lift, b + (1 - b) * lift, r * 0.9f,
				g * 0.9f, b * 0.9f, 1.0f);
	}

	/**
	 * A diamond (half diagonal {@code radius}) as triangles, the top vertex in the first colour and the
	 * bottom in the second, its edge fading out over {@code feather} (measured across the edge).
	 */
	private static void diamond(Tessellator t, double x, double y, double radius, double feather, float r0, float g0,
			float b0, float r1, float g1, float b1, float a) {
		double grow = feather * Math.sqrt(2.0) / 2.0;
		double in = radius - grow, out = radius + grow;
		double[] dx = { 0, 1, 0, -1 }, dy = { -1, 0, 1, 0 };
		float rm = (r0 + r1) / 2, gm = (g0 + g1) / 2, bm = (b0 + b1) / 2;
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			// Fill: centre and two corners.
			t.setColorRGBA_F(rm, gm, bm, a);
			t.addVertex(x, y, 0.0);
			cornerColor(t, i, r0, g0, b0, r1, g1, b1, rm, gm, bm, a);
			t.addVertex(x + dx[i] * in, y + dy[i] * in, 0.0);
			cornerColor(t, j, r0, g0, b0, r1, g1, b1, rm, gm, bm, a);
			t.addVertex(x + dx[j] * in, y + dy[j] * in, 0.0);
			// Soft edge: from the inner corners (opaque) to the outer ones (clear).
			double ix = x + dx[i] * in, iy = y + dy[i] * in, jx = x + dx[j] * in, jy = y + dy[j] * in;
			double oix = x + dx[i] * out, oiy = y + dy[i] * out, ojx = x + dx[j] * out, ojy = y + dy[j] * out;
			cornerColor(t, i, r0, g0, b0, r1, g1, b1, rm, gm, bm, a);
			t.addVertex(ix, iy, 0.0);
			cornerColor(t, i, r0, g0, b0, r1, g1, b1, rm, gm, bm, 0.0f);
			t.addVertex(oix, oiy, 0.0);
			cornerColor(t, j, r0, g0, b0, r1, g1, b1, rm, gm, bm, 0.0f);
			t.addVertex(ojx, ojy, 0.0);
			cornerColor(t, i, r0, g0, b0, r1, g1, b1, rm, gm, bm, a);
			t.addVertex(ix, iy, 0.0);
			cornerColor(t, j, r0, g0, b0, r1, g1, b1, rm, gm, bm, 0.0f);
			t.addVertex(ojx, ojy, 0.0);
			cornerColor(t, j, r0, g0, b0, r1, g1, b1, rm, gm, bm, a);
			t.addVertex(jx, jy, 0.0);
		}
	}

	/** Corner 0 is the top (first colour), 2 the bottom (second colour), the sides in between. */
	private static void cornerColor(Tessellator t, int corner, float r0, float g0, float b0, float r1, float g1,
			float b1, float rm, float gm, float bm, float a) {
		if (corner == 0)
			t.setColorRGBA_F(r0, g0, b0, a);
		else if (corner == 2)
			t.setColorRGBA_F(r1, g1, b1, a);
		else
			t.setColorRGBA_F(rm, gm, bm, a);
	}

	/** The waypoint colour for its label, lifted towards white when too dark to read on the dark backing. */
	static int readable(int rgb) {
		int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
		int luminance = (r * 299 + g * 587 + b * 114) / 1000;
		if (luminance >= 120)
			return rgb & 0xFFFFFF;
		int k = 120 - luminance; // 0..120: how much white to mix in, out of 200
		r += (255 - r) * k / 200;
		g += (255 - g) * k / 200;
		b += (255 - b) * k / 200;
		return r << 16 | g << 8 | b;
	}

	static void addLabel(String name, int wx, int wy, int wz, int color, int guiScale, int displayHeight) {
		// On the ground where the waypoint was set (waypoints store the feet position), at the foot of the beam.
		double rx = wx + 0.5 - camX, ry = wy + 0.2 - camY, rz = wz + 0.5 - camZ;
		double distance = Math.sqrt(rx * rx + ry * ry + rz * rz);
		if (distance < LABEL_MIN_DISTANCE)
			return;
		double[] m = modelview, p = projection;
		double ex = m[0] * rx + m[4] * ry + m[8] * rz + m[12];
		double ey = m[1] * rx + m[5] * ry + m[9] * rz + m[13];
		double ez = m[2] * rx + m[6] * ry + m[10] * rz + m[14];
		double ew = m[3] * rx + m[7] * ry + m[11] * rz + m[15];
		double cx = p[0] * ex + p[4] * ey + p[8] * ez + p[12] * ew;
		double cy = p[1] * ex + p[5] * ey + p[9] * ez + p[13] * ew;
		double cw = p[3] * ex + p[7] * ey + p[11] * ez + p[15] * ew;
		if (cw < 0.05)
			return; // behind the camera
		double nx = cx / cw, ny = cy / cw;
		if (nx < -1.2 || nx > 1.2 || ny < -1.2 || ny > 1.2)
			return; // well off screen
		double px = viewport[0] + (nx + 1.0) * 0.5 * viewport[2];
		double pyUp = viewport[1] + (ny + 1.0) * 0.5 * viewport[3];
		Label l = pool.isEmpty() ? new Label() : pool.remove(pool.size() - 1);
		l.name = name;
		l.color = color;
		l.distance = distance;
		// Cosine of the angle between the view direction (-z in eye space) and the waypoint.
		double eyeLen = Math.sqrt(ex * ex + ey * ey + ez * ez);
		l.focus = eyeLen > 1.0e-9 ? -ez / eyeLen : 1.0;
		l.x = px / guiScale;
		l.y = (displayHeight - pyUp) / guiScale;
		labels.add(l);
	}

	static String formatDistance(double blocks) {
		if (blocks < 1000.0)
			return (int) blocks + "m";
		double km = blocks / 1000.0;
		return (km < 10.0 ? String.format(java.util.Locale.ROOT, "%.1f", km) : String.valueOf((int) km)) + "km";
	}

	private static void drawAt(FontRenderer fr, String s, double x, double y, int color, boolean shadow) {
		GL11.glPushMatrix();
		GL11.glTranslated(x, y, 0.0);
		if (shadow)
			fr.drawStringWithShadow(s, 0, 0, color);
		else
			fr.drawString(s, 0, 0, color);
		GL11.glPopMatrix();
	}
}
