package net.fabricmc.example.minimap;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.src.FontRenderer;
import net.minecraft.src.Minecraft;
import net.minecraft.src.RenderManager;
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
 * Waypoints in the world: a faint beam in the 3D scene, and on the HUD a crisp waypoint tile at
 * the waypoint's place with the distance under it, plus the name while you look towards it.
 *
 * The labels are 2D: the camera of the world frame is remembered while the beams are drawn, and
 * the HUD projects each waypoint with it. That keeps them pixel-sharp and the same size at any
 * distance, and the right size at every GUI scale.
 */
public class WaypointBeamRenderer {
	private static final float BEAM_MIN_DISTANCE = 10.0f;
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

		GL11.glPushMatrix();
		GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_ALPHA_TEST); // no flickering at the faint edges
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_FOG);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
		GL11.glDepthMask(false);

		if (MapConfig.instance.showSpawnWaypoint && SpawnTracker.instance.hasSpawn()) {
			int[] s = spawnPosition(mc);
			renderBeam(s[0], s[1], s[2], 0x5599FF, maxRender);
		}
		List<Waypoint> waypoints = WaypointManager.instance.getWaypoints();
		for (int i = 0, n = waypoints.size(); i < n; i++) {
			Waypoint wp = waypoints.get(i);
			if (wp.enabled)
				renderBeam(wp.x, wp.y, wp.z, wp.color, maxRender);
		}

		GL11.glPopAttrib();
		GL11.glPopMatrix();
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

	private static void renderBeam(int wx, int wy, int wz, int color, double maxRender) {
		double dx = wx + 0.5 - camX;
		double dy = wy + 0.5 - camY;
		double dz = wz + 0.5 - camZ;
		double distH = Math.sqrt(dx * dx + dz * dz);
		if (distH < BEAM_MIN_DISTANCE)
			return;
		if (distH > maxRender) {
			double ratio = maxRender / distH;
			dx *= ratio;
			dy *= ratio;
			dz *= ratio;
		}
		float r = (color >> 16 & 255) / 255.0F;
		float g = (color >> 8 & 255) / 255.0F;
		float b = (color & 255) / 255.0F;

		GL11.glPushMatrix();
		GL11.glTranslated(dx, dy, dz);
		// A hollow, very translucent shell, and an even fainter glow around it.
		GL11.glColor4f(r, g, b, 0.18f);
		drawBeamQuads(0.25f, 0, 256);
		GL11.glColor4f(r, g, b, 0.04f);
		drawBeamQuads(0.35f, 0, 256);
		GL11.glPopMatrix();
	}

	private static void drawBeamQuads(float half, float yMin, float yMax) {
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex3f(-half, yMin, -half);
		GL11.glVertex3f(half, yMin, -half);
		GL11.glVertex3f(half, yMax, -half);
		GL11.glVertex3f(-half, yMax, -half);

		GL11.glVertex3f(half, yMin, half);
		GL11.glVertex3f(-half, yMin, half);
		GL11.glVertex3f(-half, yMax, half);
		GL11.glVertex3f(half, yMax, half);

		GL11.glVertex3f(-half, yMin, half);
		GL11.glVertex3f(-half, yMin, -half);
		GL11.glVertex3f(-half, yMax, -half);
		GL11.glVertex3f(-half, yMax, half);

		GL11.glVertex3f(half, yMin, -half);
		GL11.glVertex3f(half, yMin, half);
		GL11.glVertex3f(half, yMax, half);
		GL11.glVertex3f(half, yMax, -half);
		GL11.glEnd();
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
			addLabel("Spawn", s[0], s[1], s[2], 0x0066CC, guiScale, mc.displayHeight);
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

		// The name shows for the waypoint nearest the crosshair, if it is close enough to it.
		Label focused = null;
		double midX = guiWidth / 2.0, midY = guiHeight / 2.0;
		double best = Math.max(24.0, guiHeight * 0.07);
		best *= best;
		for (Label l : labels) {
			double d = (l.x - midX) * (l.x - midX) + (l.y - midY) * (l.y - midY);
			if (d < best) {
				best = d;
				focused = l;
			}
		}
		boolean allNames = cfg.worldWaypointLabels == MapConfig.WORLD_LABELS_FULL;
		boolean letters = cfg.waypointIconStyle == MapConfig.WAYPOINT_LETTERS;

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
				boolean named = allNames || l == focused;
				String dist = formatDistance(l.distance);
				String name = named ? (l.name == null ? "" : l.name) : null;

				GL11.glDisable(GL11.GL_TEXTURE_2D);
				t.startDrawing(GL11.GL_TRIANGLES);
				int distW = fr.getStringWidth(dist);
				MapMarkers.rect(t, x - (distW + 1) / 2.0 - 1, y + 7, x + (distW + 1) / 2.0 + 1, y + 7 + 10, 0.05f, 0.05f,
						0.06f, 0.55f);
				if (name != null && !name.isEmpty()) {
					int nameW = fr.getStringWidth(name);
					MapMarkers.rect(t, x - (nameW + 1) / 2.0 - 2, y - 19, x + (nameW + 1) / 2.0 + 2, y - 8, 0.05f, 0.05f,
							0.06f, 0.70f);
				}
				if (letters)
					MapMarkers.waypointTile(t, x, y, l.color);
				else
					MapMarkers.waypoint(t, x + 0.5, y + 0.5, 3, l.color);
				t.draw();

				GL11.glEnable(GL11.GL_TEXTURE_2D);
				if (letters) {
					String letter = MapMarkers.tileLetter(l.name);
					drawAt(fr, letter, MapMarkers.tileLetterLeft(x, fr.getStringWidth(letter)), y - 3,
							MapMarkers.tileLetterColor(l.color), false);
				}
				drawAt(fr, dist, x - (distW - 1) / 2.0, y + 8 + u, 0xE6E6E6, true);
				if (name != null && !name.isEmpty())
					drawAt(fr, name, x - (fr.getStringWidth(name) - 1) / 2.0, y - 17, 0xFFFFFF, true);
			}
			GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
		} finally {
			GL11.glPopAttrib();
		}
	}

	static void addLabel(String name, int wx, int wy, int wz, int color, int guiScale, int displayHeight) {
		// Just above the block the waypoint was set on (waypoints store the feet position).
		double rx = wx + 0.5 - camX, ry = wy + 1.0 - camY, rz = wz + 0.5 - camZ;
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
