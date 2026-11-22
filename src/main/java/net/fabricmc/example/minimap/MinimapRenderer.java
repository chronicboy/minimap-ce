package net.fabricmc.example.minimap;

import net.fabricmc.example.minimap.engine.BlockColors;
import net.fabricmc.example.minimap.engine.MapDraw;
import net.fabricmc.example.minimap.engine.MapEngine;
import net.fabricmc.example.minimap.engine.SharpShader;
import net.minecraft.src.BiomeGenBase;
import net.minecraft.src.Entity;
import net.minecraft.src.EntityAnimal;
import net.minecraft.src.EntityBat;
import net.minecraft.src.EntityLivingBase;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntitySquid;
import net.minecraft.src.EntityVillager;
import net.minecraft.src.FontRenderer;
import net.minecraft.src.IMob;
import net.minecraft.src.Minecraft;
import net.minecraft.src.ResourceLocation;
import net.minecraft.src.Tessellator;
import net.minecraft.src.World;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * The on-screen minimap. Terrain comes straight from the engine's region
 * textures, so a frame costs a handful of draw calls regardless of map size,
 * zoom or how fast the player moves.
 */
public class MinimapRenderer {
	private static final int CIRCLE_SEGMENTS = 72;
	private static final int MAX_ENTITIES = 128;
	private static final double ENTITY_Y_RANGE = 20.0;

	// Shape outline relative to the map centre (GUI pixels).
	private final double[] shapeX = new double[CIRCLE_SEGMENTS];
	private final double[] shapeY = new double[CIRCLE_SEGMENTS];
	private int shapeN;
	private int shapeRadius = -1;
	private boolean shapeCircle;

	// View of the current frame.
	private double viewX, viewZ, viewScale, viewCos, viewSin, viewRotation;
	private double centerX, centerY;
	private int radius;
	/** Physical pixels per GUI pixel this frame. */
	private int pixelScale = 1;
	private boolean circle;

	// Marker parts that need a texture, queued while the flat shapes are batched.
	private static final int MAX_LETTERS = 96;
	private final double[] letterX = new double[MAX_LETTERS];
	private final double[] letterY = new double[MAX_LETTERS];
	private final int[] letterColor = new int[MAX_LETTERS];
	private final String[] letterText = new String[MAX_LETTERS];
	private int letterCount;
	private final ResourceLocation[] headTexture = new ResourceLocation[MAX_ENTITIES];
	private final EntityIcons.Head[] headIcon = new EntityIcons.Head[MAX_ENTITIES];
	private final double[] headX = new double[MAX_ENTITIES];
	private final double[] headY = new double[MAX_ENTITIES];
	private final float[] headShade = new float[MAX_ENTITIES];
	/** Height difference to the player: faces far above or below are drawn first, so mobs at your level are on top. */
	private final double[] headDepth = new double[MAX_ENTITIES];
	private final int[] headOrder = new int[MAX_ENTITIES];
	private int headCount;
	/** Faces this frame are drawn with the sharp shader at exact (sub-pixel) positions. */
	private boolean smoothIcons;

	// Movement detection for smooth scrolling.
	private double lastRawX = Double.NaN, lastRawZ = Double.NaN;
	private int stillFrames;
	/** Frames without movement before the map settles onto the pixel grid. */
	private static final int SETTLE_FRAMES = 3;
	private int iconGeneration = -1;

	// HUD text cache
	private String cachedClockStr = "";
	private long lastClockSecond = Long.MIN_VALUE;
	private String cachedCoordStr = "";
	private String cachedBiomeName = "";
	private String biomeText = "";
	private int lastCoordX = Integer.MIN_VALUE, lastCoordY = Integer.MIN_VALUE, lastCoordZ = Integer.MIN_VALUE;
	private BiomeGenBase lastBiome = null;

	/** The radar style for this frame: the default shows dots, and faces while Tab is held. */
	private static int radarStyle(Minecraft mc) {
		int style = MapConfig.instance.entityIconStyle;
		if (style != MapConfig.ENTITY_DOTS_FACES_ON_TAB)
			return style;
		boolean tab = mc.gameSettings != null && mc.gameSettings.keyBindPlayerList.pressed;
		return tab ? MapConfig.ENTITY_HEADS : MapConfig.ENTITY_DOTS;
	}

	/** Mobs far above or below are drawn darker, so the radar shows depth at a glance. */
	private static float heightShade(double dy) {
		double d = Math.abs(dy) - 3.0;
		return d <= 0 ? 1.0f : (float) Math.max(0.45, 1.0 - d * 0.035);
	}

	public void renderMinimap(Minecraft mc, World world, EntityPlayer player, int screenWidth, int screenHeight,
			int guiScale, float partialTicks) {
		MapConfig cfg = MapConfig.instance;
		if (world == null || player == null || !cfg.minimapEnabled)
			return;

		int mapSize = cfg.minimapSize;
		radius = mapSize / 2;
		circle = cfg.minimapShape == MapConfig.SHAPE_CIRCLE;

		// ---- Layout (unchanged from 3.x so existing setups look the same)
		// Without padding a minimal margin still keeps the compass letters and info text on screen.
		int marginX = cfg.useMinimapPadding ? 10 : 4;
		int marginY = cfg.useMinimapPadding ? 10 : 2;
		if (!cfg.useMinimapPadding && cfg.minimapPosition <= 1)
			marginY = 18; // room for the clock and the north label

		int mapX, mapY;
		switch (cfg.minimapPosition) {
			case 0:
				mapX = marginX;
				mapY = marginY;
				break;
			case 2:
				mapX = marginX;
				mapY = screenHeight - mapSize - marginY;
				break;
			case 3:
				mapX = screenWidth - mapSize - marginX;
				mapY = screenHeight - mapSize - marginY;
				break;
			case 1:
			default:
				mapX = screenWidth - mapSize - marginX;
				mapY = marginY;
		}
		if (cfg.useMinimapPadding) {
			if (cfg.minimapPosition <= 1)
				mapY += 20;
			else
				mapY -= 30; // room for coordinates/biome below a bottom-aligned map
		}
		centerX = mapX + radius;
		centerY = mapY + radius;

		// ---- View: interpolated so the map scrolls and turns smoothly at any frame rate
		viewX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
		viewZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
		float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
		viewScale = cfg.minimapScale();
		viewRotation = cfg.rotateMinimap ? Math.toRadians(180.0 - yaw) : 0.0;
		viewCos = Math.cos(viewRotation);
		viewSin = Math.sin(viewRotation);
		pixelScale = Math.max(1, guiScale);
		MapDraw.sharpScaling = cfg.sharpScaling;
		boolean shader = cfg.sharpScaling && SharpShader.available();

		// While you move, the map glides below the pixel (the sharp shader keeps every block solid and
		// only blends the one pixel on its edge), so terrain and markers move smoothly instead of in
		// whole-pixel jumps. Standing still, it settles exactly onto the pixel grid, the sharpest it can be.
		// Zoomed out (a pixel covers several blocks) it always stays on the grid: gliding would only blur.
		boolean moved = Math.abs(viewX - lastRawX) > 1.0e-4 || Math.abs(viewZ - lastRawZ) > 1.0e-4;
		lastRawX = viewX;
		lastRawZ = viewZ;
		stillFrames = moved ? 0 : Math.min(stillFrames + 1, SETTLE_FRAMES);
		boolean glide = shader && stillFrames < SETTLE_FRAMES && viewScale * pixelScale >= 0.999;
		boolean aligned = viewRotation == 0.0 && !glide;
		if (aligned) {
			// Land block edges exactly on screen pixels; markers use the same snapped view so nothing drifts.
			viewX = MapDraw.snap(viewX, centerX, viewScale, pixelScale);
			viewZ = MapDraw.snap(viewZ, centerY, viewScale, pixelScale);
		}
		smoothIcons = shader;
		buildShape();

		GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT
				| GL11.GL_LIGHTING_BIT);
		MapDraw.pixelAligned = aligned;
		try {
			drawMinimap(mc, world, player, mapX, mapY, mapSize, screenWidth, screenHeight, guiScale, partialTicks,
					yaw);
		} finally {
			// Always restore the GL state, even if something above failed.
			MapDraw.pixelAligned = true;
			GL11.glPopAttrib();
		}
	}

	private void drawMinimap(Minecraft mc, World world, EntityPlayer player, int mapX, int mapY, int mapSize,
			int screenWidth, int screenHeight, int guiScale, float partialTicks, float yaw) {
		MapConfig cfg = MapConfig.instance;
		float opacity = cfg.mapOpacity;
		Tessellator t = Tessellator.instance;

		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glLineWidth(1.0f);
		// The game leaves flat shading on after drawing the world. Gradients (the frame's soft
		// shadow) need smooth shading, otherwise every other triangle comes out solid: "teeth".
		GL11.glShadeModel(GL11.GL_SMOOTH);
		pixelScale = Math.max(1, guiScale);
		MapMarkers.pixel = 1.0 / pixelScale;

		// ---- Background: unexplored ground (rotated square maps reach 1.4x farther into their corners)
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		t.startDrawing(GL11.GL_TRIANGLE_FAN);
		t.setColorRGBA_F(0.085f, 0.09f, 0.105f, opacity);
		t.addVertex(centerX, centerY, 0.0);
		for (int i = 0; i <= shapeN; i++) {
			int j = i % shapeN;
			t.addVertex(centerX + shapeX[j], centerY + shapeY[j], 0.0);
		}
		t.draw();
		drawHatch(t, opacity);

		// ---- Terrain
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		MapDraw.draw(MapEngine.INSTANCE.minimapStorage(), viewX, viewZ, viewScale, viewRotation, centerX, centerY, shapeX,
				shapeY, shapeN, opacity, guiScale, true);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glDisable(GL11.GL_TEXTURE_2D);

		if (cfg.showChunkGrid)
			drawChunkGrid(t);

		// Inner shadow: the map looks set into the frame rather than pasted on.
		double r = radius;
		ring(t, (r - 7.0) / r, 1.0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.30f * opacity);
		drawFrame(t);

		// ---- Markers: one batch for all flat shapes (entities first, so waypoints and the player stay on top)
		letterCount = 0;
		headCount = 0;
		if (BlockColors.generation() != iconGeneration) {
			// Resource pack changed: skins may have a different shape now.
			iconGeneration = BlockColors.generation();
			EntityIcons.clearCache();
		}
		// ---- Markers, bottom to top: mob dots and arrows, mob faces, then waypoints and the player,
		// so a crowd of mobs never hides where you are or where you are going.
		t.startDrawing(GL11.GL_TRIANGLES);
		if (cfg.showEntities)
			addEntityMarkers(t, world, player, partialTicks, radarStyle(mc));
		t.draw();
		if (headCount > 0)
			drawHeads(mc, t);

		GL11.glDisable(GL11.GL_TEXTURE_2D);
		t.startDrawing(GL11.GL_TRIANGLES);
		if (cfg.showSpawnWaypoint && SpawnTracker.instance.hasSpawn())
			addSpawnMarker(t, world);
		if (cfg.showWaypoints)
			addWaypointMarkers(t);
		MapMarkers.playerArrow(t, centerX, centerY, Math.toRadians(yaw + 90.0) + viewRotation, 5.5);
		t.draw();

		// ---- Waypoint letters and the text around the map
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
		FontRenderer fr = mc.fontRenderer;
		if (fr != null) {
			for (int i = 0; i < letterCount; i++) {
				String letter = letterText[i];
				drawAt(fr, letter, MapMarkers.tileLetterLeft(letterX[i], fr.getStringWidth(letter)), letterY[i] - 3,
						letterColor[i]);
				letterText[i] = null;
			}
			GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
		}
		if (cfg.showCompass)
			drawCompass(t, fr);
		if (fr != null)
			drawInfoOverlay(fr, world, player, mapX, mapY, mapSize, screenWidth, screenHeight);
		GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
	}

	// ------------------------------------------------------------------ shape

	private void buildShape() {
		if (shapeRadius == radius && shapeCircle == circle)
			return;
		shapeRadius = radius;
		shapeCircle = circle;
		if (circle) {
			shapeN = CIRCLE_SEGMENTS;
			for (int i = 0; i < shapeN; i++) {
				double a = (i * 2.0 * Math.PI) / shapeN;
				shapeX[i] = Math.cos(a) * radius;
				shapeY[i] = Math.sin(a) * radius;
			}
		} else {
			shapeN = 4;
			shapeX[0] = -radius;
			shapeY[0] = -radius;
			shapeX[1] = radius;
			shapeY[1] = -radius;
			shapeX[2] = radius;
			shapeY[2] = radius;
			shapeX[3] = -radius;
			shapeY[3] = radius;
		}
	}

	/** True if a point (relative to the map centre) lies inside the shape shrunk by margin. */
	private boolean inside(double dx, double dy, double margin) {
		double limit = radius - margin;
		if (circle)
			return dx * dx + dy * dy <= limit * limit;
		return Math.abs(dx) <= limit && Math.abs(dy) <= limit;
	}

	/** Scale factor that moves a point onto the shape border shrunk by margin. */
	private double edgeFactor(double dx, double dy, double margin) {
		double limit = radius - margin;
		double d = circle ? Math.sqrt(dx * dx + dy * dy) : Math.max(Math.abs(dx), Math.abs(dy));
		return d < 1.0e-6 ? 0.0 : limit / d;
	}

	// World -> screen offset from the map centre.
	private double projectX(double worldX, double worldZ) {
		return (viewCos * (worldX - viewX) - viewSin * (worldZ - viewZ)) * viewScale;
	}

	private double projectY(double worldX, double worldZ) {
		return (viewSin * (worldX - viewX) + viewCos * (worldZ - viewZ)) * viewScale;
	}

	// ------------------------------------------------------------------ frame

	/** Width of the frame's metal band in GUI pixels: a whole number of physical pixels, so it stays crisp. */
	private double bandWidth() {
		return Math.max(2L, Math.round(1.5 * pixelScale)) / (double) pixelScale;
	}

	/**
	 * Frame around the map: a soft drop shadow, a crisp dark outline, a metal band lit from above and
	 * a fine dark line where it meets the map. Every edge sits on a physical pixel.
	 */
	private void drawFrame(Tessellator t) {
		double r = radius, u = 1.0 / pixelScale, band = bandWidth();
		double outline = r + band + u;
		ring(t, outline / r, (outline + 5.0) / r, 0.0f, 0.0f, 0.0f, 0.45f, 0.0f, 0.0f, 0.0f, 0.0f);
		ring(t, (r + band) / r, outline / r, 0.02f, 0.02f, 0.03f, 1.0f, 0.02f, 0.02f, 0.03f, 1.0f);
		litRing(t, 1.0, (r + band) / r, 0.66f, 0.69f, 0.76f, 0.27f, 0.29f, 0.34f);
		ring(t, (r - u) / r, 1.0, 0.03f, 0.03f, 0.04f, 0.85f, 0.03f, 0.03f, 0.04f, 0.85f);
	}

	/** A solid ring shaded from the top colour to the bottom colour, as if lit from above. */
	private void litRing(Tessellator t, double inner, double outer, float rt, float gt, float bt, float rb, float gb,
			float bb) {
		t.startDrawing(GL11.GL_TRIANGLE_STRIP);
		for (int i = 0; i <= shapeN; i++) {
			int j = i % shapeN;
			float f = (float) ((shapeY[j] / radius + 1.0) * 0.5);
			t.setColorRGBA_F(rt + (rb - rt) * f, gt + (gb - gt) * f, bt + (bb - bt) * f, 1.0f);
			t.addVertex(centerX + shapeX[j] * inner, centerY + shapeY[j] * inner, 0.0);
			t.addVertex(centerX + shapeX[j] * outer, centerY + shapeY[j] * outer, 0.0);
		}
		t.draw();
	}

	/** Faint diagonal hatching, so unexplored ground reads as "not mapped yet" instead of as a hole. */
	private void drawHatch(Tessellator t, float opacity) {
		double spacing = 4.0, s = Math.sqrt(0.5);
		int n = (int) Math.ceil(radius * 1.4143 / spacing);
		t.startDrawing(GL11.GL_LINES);
		t.setColorRGBA_F(1.0f, 1.0f, 1.0f, 0.045f * opacity);
		for (int k = -n; k <= n; k++) {
			double off = k * spacing;
			addClippedLine(t, off * s, -off * s, s, s);
		}
		t.draw();
	}

	private void ring(Tessellator t, double inner, double outer, float r0, float g0, float b0, float a0, float r1,
			float g1, float b1, float a1) {
		t.startDrawing(GL11.GL_TRIANGLE_STRIP);
		for (int i = 0; i <= shapeN; i++) {
			int j = i % shapeN;
			t.setColorRGBA_F(r0, g0, b0, a0);
			t.addVertex(centerX + shapeX[j] * inner, centerY + shapeY[j] * inner, 0.0);
			t.setColorRGBA_F(r1, g1, b1, a1);
			t.addVertex(centerX + shapeX[j] * outer, centerY + shapeY[j] * outer, 0.0);
		}
		t.draw();
	}

	// ------------------------------------------------------------------- grid

	private void drawChunkGrid(Tessellator t) {
		double step = 16.0 * viewScale;
		if (step < 4.0)
			return; // lines would be denser than the terrain they sit on

		// Blocks from the centre to the farthest visible point.
		double reach = (circle ? radius : radius * 1.4143) / viewScale;
		int k0 = (int) Math.floor((viewX - reach) / 16.0), k1 = (int) Math.ceil((viewX + reach) / 16.0);
		int j0 = (int) Math.floor((viewZ - reach) / 16.0), j1 = (int) Math.ceil((viewZ + reach) / 16.0);

		t.startDrawing(GL11.GL_LINES);
		t.setColorRGBA_F(1.0f, 1.0f, 1.0f, 0.16f);
		for (int k = k0; k <= k1; k++) {
			// World line x = 16k: perpendicular offset a along (cos, sin), direction (-sin, cos).
			double a = (k * 16.0 - viewX) * viewScale;
			addClippedLine(t, viewCos * a, viewSin * a, -viewSin, viewCos);
		}
		for (int j = j0; j <= j1; j++) {
			// World line z = 16j: offset b along (-sin, cos), direction (cos, sin).
			double b = (j * 16.0 - viewZ) * viewScale;
			addClippedLine(t, -viewSin * b, viewCos * b, viewCos, viewSin);
		}
		t.draw();
	}

	/** Adds the part of the line (ax,ay) + s * (dx,dy) that lies inside the map shape. (dx,dy) is a unit vector. */
	private void addClippedLine(Tessellator t, double ax, double ay, double dx, double dy) {
		double s0, s1;
		double limit = radius - 0.5;
		if (circle) {
			// Closest approach of the line to the centre.
			double along = ax * dx + ay * dy;
			double px = ax - along * dx, py = ay - along * dy;
			double distSq = px * px + py * py;
			if (distSq >= limit * limit)
				return;
			double half = Math.sqrt(limit * limit - distSq);
			s0 = -along - half;
			s1 = -along + half;
		} else {
			s0 = -1.0e9;
			s1 = 1.0e9;
			// Slab clipping against [-limit, limit] on both axes.
			if (Math.abs(dx) < 1.0e-9) {
				if (Math.abs(ax) > limit)
					return;
			} else {
				double ta = (-limit - ax) / dx, tb = (limit - ax) / dx;
				s0 = Math.max(s0, Math.min(ta, tb));
				s1 = Math.min(s1, Math.max(ta, tb));
			}
			if (Math.abs(dy) < 1.0e-9) {
				if (Math.abs(ay) > limit)
					return;
			} else {
				double ta = (-limit - ay) / dy, tb = (limit - ay) / dy;
				s0 = Math.max(s0, Math.min(ta, tb));
				s1 = Math.min(s1, Math.max(ta, tb));
			}
			if (s1 <= s0)
				return;
		}
		t.addVertex(centerX + ax + dx * s0, centerY + ay + dy * s0, 0.0);
		t.addVertex(centerX + ax + dx * s1, centerY + ay + dy * s1, 0.0);
	}

	// ---------------------------------------------------------------- markers

	private void addSpawnMarker(Tessellator t, World world) {
		int spawnX, spawnZ;
		int dim = world.provider.dimensionId;
		if (dim == 1) {
			spawnX = 100;
			spawnZ = 0;
		} else if (dim == -1) {
			spawnX = SpawnTracker.instance.getNetherSpawnX();
			spawnZ = SpawnTracker.instance.getNetherSpawnZ();
		} else {
			spawnX = SpawnTracker.instance.getSpawnX();
			spawnZ = SpawnTracker.instance.getSpawnZ();
		}
		addWaypointMarker(t, spawnX + 0.5, spawnZ + 0.5, 0x0066CC, "Spawn");
	}

	private void addWaypointMarkers(Tessellator t) {
		List<Waypoint> waypoints = WaypointManager.instance.getWaypoints();
		for (int i = 0, n = waypoints.size(); i < n; i++) {
			Waypoint wp = waypoints.get(i);
			if (wp.enabled)
				addWaypointMarker(t, wp.x + 0.5, wp.z + 0.5, wp.color, wp.name);
		}
	}

	/** Marker at its map position, or pinned to the border in its direction when out of range. */
	private void addWaypointMarker(Tessellator t, double worldX, double worldZ, int rgb, String name) {
		boolean tiles = MapConfig.instance.waypointIconStyle == MapConfig.WAYPOINT_LETTERS;
		double margin = tiles ? 6.0 : 4.0;
		double dx = projectX(worldX, worldZ);
		double dy = projectY(worldX, worldZ);
		if (!inside(dx, dy, margin)) {
			double f = edgeFactor(dx, dy, margin + 1.0);
			dx *= f;
			dy *= f;
		}
		// On the physical pixel grid: crisp tiles that still move in the smallest steps the screen has.
		double x = Math.rint((centerX + dx) * pixelScale) / pixelScale;
		double y = Math.rint((centerY + dy) * pixelScale) / pixelScale;
		if (!tiles) {
			MapMarkers.waypoint(t, x, y, 2, rgb);
			return;
		}
		MapMarkers.waypointTile(t, x, y, rgb);
		if (letterCount < MAX_LETTERS) {
			letterX[letterCount] = x;
			letterY[letterCount] = y;
			letterColor[letterCount] = MapMarkers.tileLetterColor(rgb);
			letterText[letterCount] = MapMarkers.tileLetter(name);
			letterCount++;
		}
	}

	private void addEntityMarkers(Tessellator t, World world, EntityPlayer player, float partialTicks, int style) {
		List<?> entities = world.loadedEntityList;
		double reach = (circle ? radius : radius * 1.4143) / viewScale;
		int count = 0;
		for (int i = 0, n = entities.size(); i < n && count < MAX_ENTITIES; i++) {
			Object obj = entities.get(i);
			if (!(obj instanceof EntityLivingBase))
				continue;
			EntityLivingBase e = (EntityLivingBase) obj;
			if (e == player || e.isDead)
				continue;
			if (e instanceof EntitySquid || e instanceof EntityBat)
				continue;
			if (Math.abs(e.posX - viewX) > reach || Math.abs(e.posZ - viewZ) > reach)
				continue;
			if (Math.abs(e.posY - player.posY) > ENTITY_Y_RANGE)
				continue;
			if (e.isInvisible())
				continue;
			boolean isPlayer = e instanceof EntityPlayer;
			if (isPlayer && e.isSneaking())
				continue;

			double ex = e.prevPosX + (e.posX - e.prevPosX) * partialTicks;
			double ez = e.prevPosZ + (e.posZ - e.prevPosZ) * partialTicks;
			double dx = projectX(ex, ez);
			double dy = projectY(ex, ez);
			if (!inside(dx, dy, style == MapConfig.ENTITY_HEADS ? 5.0 : 3.0))
				continue;
			double x = centerX + dx, y = centerY + dy;
			count++;

			// Hostile = crimson, passive = emerald, players = cyan, anything else = amber.
			float r = 1.0f, g = 0.80f, b = 0.15f;
			if (e instanceof IMob) {
				r = 0.96f;
				g = 0.22f;
				b = 0.22f;
			} else if (e instanceof EntityAnimal || e instanceof EntityVillager) {
				r = 0.22f;
				g = 0.90f;
				b = 0.35f;
			} else if (isPlayer) {
				r = 0.25f;
				g = 0.75f;
				b = 1.0f;
			}

			if (style == MapConfig.ENTITY_ARROWS) {
				float eyaw = e.prevRotationYaw + (e.rotationYaw - e.prevRotationYaw) * partialTicks;
				float shade = heightShade(e.posY - player.posY);
				MapMarkers.entityArrow(t, x, y, Math.toRadians(eyaw + 90.0) + viewRotation, r * shade, g * shade,
						b * shade);
				continue;
			}
			if (style == MapConfig.ENTITY_HEADS) {
				EntityIcons.Head head = EntityIcons.forEntity(e);
				ResourceLocation texture = head != null ? EntityIcons.textureOf(e) : null;
				if (texture != null) {
					// With the sharp shader the face sits exactly where the mob is and glides; without it the
					// corner is snapped to a physical pixel so every texel stays a crisp square.
					double w = EntityIcons.iconWidth(head), h = EntityIcons.iconHeight(head);
					double left = x - w / 2.0, top = y - h / 2.0;
					if (!smoothIcons) {
						left = Math.rint(left * pixelScale) / pixelScale;
						top = Math.rint(top * pixelScale) / pixelScale;
					}
					headTexture[headCount] = texture;
					headIcon[headCount] = head;
					headX[headCount] = left;
					headY[headCount] = top;
					headShade[headCount] = heightShade(e.posY - player.posY);
					headDepth[headCount] = Math.abs(e.posY - player.posY);
					headCount++;
					continue;
				}
			}
			float shade = heightShade(e.posY - player.posY);
			MapMarkers.dot(t, x, y, 1.8, r * shade, g * shade, b * shade);
		}
	}

	/** Faces, farthest from your level first, each with its own outline so overlapping faces stay apart. */
	private void drawHeads(Minecraft mc, Tessellator t) {
		for (int i = 0; i < headCount; i++)
			headOrder[i] = i;
		for (int i = 1; i < headCount; i++) {
			int k = headOrder[i], j = i - 1;
			while (j >= 0 && headDepth[headOrder[j]] < headDepth[k]) {
				headOrder[j + 1] = headOrder[j];
				j--;
			}
			headOrder[j + 1] = k;
		}
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		int previousProgram = smoothIcons ? SharpShader.begin() : 0;
		try {
			for (int n = 0; n < headCount; n++) {
				int i = headOrder[n];
				EntityIcons.drawHead(mc, t, headTexture[i], headIcon[i], headX[i], headY[i], headShade[i],
						1.0 / pixelScale, smoothIcons);
			}
		} finally {
			if (smoothIcons)
				SharpShader.end(previousProgram);
			for (int i = 0; i < headCount; i++) {
				headTexture[i] = null;
				headIcon[i] = null;
			}
		}
	}

	// ------------------------------------------------------------------- text

	private static final String[] COMPASS = { "N", "E", "S", "W" };
	private static final int BADGE_SEGMENTS = 24;
	private final double[] compassX = new double[4];
	private final double[] compassY = new double[4];

	/** How far inside the map edge the compass letters sit (their centre), in GUI pixels. */
	private static final double COMPASS_INSET = 6.0;
	private static final int[] OUTLINE_DX = { -1, 0, 1, -1, 1, -1, 0, 1 };
	private static final int[] OUTLINE_DY = { -1, -1, -1, 0, 0, 1, 1, 1 };

	/**
	 * Cardinal letters just inside the map edge, like Xaero's: plain white letters with a one-pixel
	 * dark outline, over a faint soft shadow so they stay readable on snow and sand.
	 */
	private void drawCompass(Tessellator t, FontRenderer fr) {
		double inset = Math.min(COMPASS_INSET, radius * 0.25);
		for (int i = 0; i < 4; i++) {
			// Screen direction of world north is R * (0, -1), east R * (1, 0), and so on clockwise.
			double a = i * Math.PI * 0.5;
			double wx = Math.sin(a), wz = -Math.cos(a);
			double dx = viewCos * wx - viewSin * wz, dy = viewSin * wx + viewCos * wz;
			double dist = radius - inset;
			if (!circle) {
				// Along the square's edge; turned 45 degrees the letter sits in the corner.
				double m = Math.max(Math.abs(dx), Math.abs(dy));
				if (m > 1.0e-6)
					dist /= m;
			}
			compassX[i] = centerX + dx * dist;
			compassY[i] = centerY + dy * dist;
		}

		GL11.glDisable(GL11.GL_TEXTURE_2D);
		t.startDrawing(GL11.GL_TRIANGLES);
		for (int i = 0; i < 4; i++)
			disc(t, compassX[i], compassY[i], 6.5, 0.0f, 0.0f, 0.0f, 0.42f, 0.0f);
		t.draw();
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		if (fr == null)
			return;
		double u = 1.0 / pixelScale;
		for (int i = 0; i < 4; i++) {
			String s = COMPASS[i];
			// Glyphs are 7 high and their advance is one more than their width; corner on a physical pixel.
			double left = Math.rint((compassX[i] - (fr.getStringWidth(s) - 1) * 0.5) * pixelScale) / pixelScale;
			double top = Math.rint((compassY[i] - 3.5) * pixelScale) / pixelScale;
			for (int k = 0; k < 8; k++)
				drawAt(fr, s, left + OUTLINE_DX[k] * u, top + OUTLINE_DY[k] * u, 0x0A0A0C);
			drawAt(fr, s, left, top, 0xFFFFFF);
		}
	}

	private static void drawAt(FontRenderer fr, String s, double x, double y, int color) {
		GL11.glPushMatrix();
		GL11.glTranslated(x, y, 0.0);
		fr.drawString(s, 0, 0, color);
		GL11.glPopMatrix();
	}

	/** A filled disc as triangles; the rim colour alpha can differ from the centre for a soft edge. */
	private static void disc(Tessellator t, double x, double y, double rad, float r, float g, float b, float centerAlpha,
			float rimAlpha) {
		double prevX = x + rad, prevY = y;
		for (int i = 1; i <= BADGE_SEGMENTS; i++) {
			double a = i * 2.0 * Math.PI / BADGE_SEGMENTS;
			double nx = x + Math.cos(a) * rad, ny = y + Math.sin(a) * rad;
			t.setColorRGBA_F(r, g, b, centerAlpha);
			t.addVertex(x, y, 0.0);
			t.setColorRGBA_F(r, g, b, rimAlpha);
			t.addVertex(nx, ny, 0.0);
			t.addVertex(prevX, prevY, 0.0);
			prevX = nx;
			prevY = ny;
		}
	}

	/**
	 * Days until the next gloom night. BTW's "hardcore of darkness" makes the new moon pitch black:
	 * World.computeOverworldSunBrightnessWithMoonPhases uses phase ((time - 12000) / 24000) % 8, which
	 * is 4 (no moonlight) on the evening and night of days 5, 13, 21, ... (day index 4 mod 8).
	 *
	 * @return 0 when tonight is a gloom night
	 */
	static int daysToGloom(long dayIndex) {
		return (int) Math.floorMod(4L - dayIndex, 8L);
	}

	/** Clock above the map, coordinates and biome below it (or above, for bottom positions). */
	private void drawInfoOverlay(FontRenderer fr, World world, EntityPlayer player, int mapX, int mapY, int mapSize,
			int screenWidth, int screenHeight) {
		MapConfig cfg = MapConfig.instance;
		boolean showClock = cfg.showClock;
		boolean showCoords = cfg.showCoordinates;
		boolean showBiome = cfg.showBiome;
		if (!showClock && !showCoords && !showBiome)
			return;

		// Text shrinks with small maps (128 = full size).
		float textScale = Math.max(0.5f, Math.min(1.0f, mapSize / 128.0f));
		float invScale = 1.0f / textScale;

		// Clock: day counter, days to the next gloom night, and minutes:seconds into the current day,
		// refreshed once per second.
		long time = world.getWorldTime();
		long second = Math.floorMod(time, 24000L) / 20L;
		if (second != lastClockSecond || cachedClockStr.isEmpty()) {
			lastClockSecond = second;
			long dayIndex = Math.floorDiv(time, 24000L);
			StringBuilder sb = new StringBuilder(32);
			sb.append(world.isDaytime() ? "Day " : "Night ").append(dayIndex + 1);
			if (world.provider.dimensionId == 0) {
				int until = daysToGloom(dayIndex);
				if (until == 0)
					sb.append(" \u00a7c(Gloom)\u00a7f");
				else
					sb.append(" \u00a77(").append(until).append(")\u00a7f");
			}
			sb.append(", ");
			long minutes = second / 60, seconds = second % 60;
			if (minutes < 10)
				sb.append('0');
			sb.append(minutes).append(':');
			if (seconds < 10)
				sb.append('0');
			sb.append(seconds);
			cachedClockStr = sb.toString();
		}

		int x = (int) Math.floor(player.posX);
		int y = (int) Math.floor(player.boundingBox.minY);
		int z = (int) Math.floor(player.posZ);
		if (x != lastCoordX || y != lastCoordY || z != lastCoordZ || cachedCoordStr.isEmpty()) {
			boolean moved = x != lastCoordX || z != lastCoordZ;
			lastCoordX = x;
			lastCoordY = y;
			lastCoordZ = z;
			cachedCoordStr = x + ", " + y + ", " + z;
			if (moved || lastBiome == null) {
				BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
				if (biome != lastBiome || cachedBiomeName.isEmpty()) {
					lastBiome = biome;
					cachedBiomeName = biome != null && biome.biomeName != null ? biome.biomeName : "Unknown";
				}
			}
		}

		int clockW = (int) (fr.getStringWidth(cachedClockStr) * textScale) + 8;
		int coordW = (int) (fr.getStringWidth(cachedCoordStr) * textScale) + 8;
		biomeText = MapEngine.INSTANCE.isCaveViewActive() ? cachedBiomeName + " (Caves)" : cachedBiomeName;
		int biomeW = (int) (fr.getStringWidth(biomeText) * textScale) + 8;
		int lineH = (int) (12 * textScale);
		int clockH = lineH;
		int maxInfoW = Math.max(showCoords ? coordW : 0, showBiome ? biomeW : 0);
		int infoLines = (showCoords ? 1 : 0) + (showBiome ? 1 : 0);
		int infoH = infoLines * lineH;

		int gap = cfg.useMinimapPadding ? 8 : 2;
		boolean isBottom = cfg.minimapPosition >= 2;
		int cx = mapX + mapSize / 2;

		GL11.glPushMatrix();
		try {
			GL11.glScalef(textScale, textScale, 1.0f);
			drawInfoText(fr, cfg, mapX, mapY, mapSize, screenWidth, screenHeight, textScale, invScale, clockW, coordW,
					biomeW, lineH, clockH, maxInfoW, infoLines, infoH, gap, isBottom, cx);
		} finally {
			GL11.glPopMatrix();
		}
	}

	private void drawInfoText(FontRenderer fr, MapConfig cfg, int mapX, int mapY, int mapSize, int screenWidth,
			int screenHeight, float textScale, float invScale, int clockW, int coordW, int biomeW, int lineH,
			int clockH, int maxInfoW, int infoLines, int infoH, int gap, boolean isBottom, int cx) {
		boolean showClock = cfg.showClock;
		boolean showCoords = cfg.showCoordinates;
		boolean showBiome = cfg.showBiome;

		if (showClock) {
			int clockY = isBottom ? (mapY - clockH - (infoH > 0 ? infoH + gap + 2 : gap)) : (mapY - clockH - gap);
			clockY = Math.max(2, clockY);
			int clockX = cx - clockW / 2;
			clockX = Math.max(2, Math.min(clockX, screenWidth - clockW - 2));
			fr.drawStringWithShadow(cachedClockStr, (int) ((clockX + 4) * invScale),
					(int) ((clockY + 2 * textScale) * invScale), 0xFFFFFF);
		}

		if (infoLines > 0) {
			int infoY = isBottom ? (mapY - infoH - gap) : (mapY + mapSize + gap);
			infoY = Math.min(infoY, screenHeight - infoH - 2);
			int infoX = cx - maxInfoW / 2;
			infoX = Math.max(2, Math.min(infoX, screenWidth - maxInfoW - 2));

			int textY = infoY + (int) (2 * textScale);
			if (showCoords) {
				int w = coordW - 8;
				fr.drawStringWithShadow(cachedCoordStr, (int) ((infoX + (maxInfoW - w) / 2) * invScale),
						(int) (textY * invScale), 0xFFFFFF);
				textY += lineH;
			}
			if (showBiome) {
				int w = biomeW - 8;
				fr.drawStringWithShadow(biomeText, (int) ((infoX + (maxInfoW - w) / 2) * invScale),
						(int) (textY * invScale), 0xAAAAAA);
			}
		}
	}
}
