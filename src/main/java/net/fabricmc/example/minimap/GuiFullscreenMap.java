package net.fabricmc.example.minimap;

import net.fabricmc.example.minimap.engine.CaveLayers;
import net.fabricmc.example.minimap.engine.MapDraw;
import net.fabricmc.example.minimap.engine.MapEngine;
import net.fabricmc.example.minimap.engine.MapStorage;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.FontRenderer;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.Minecraft;
import net.minecraft.src.ScaledResolution;
import net.minecraft.src.Tessellator;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Fullscreen world map.
 *
 * Drag to pan, mouse wheel to zoom towards the cursor, WASD/arrow keys to pan,
 * C to follow the player again, double-click to drop a waypoint, right-click
 * for a context menu. Terrain is drawn from GPU region textures, so panning and
 * zooming never re-sample anything.
 */
public class GuiFullscreenMap extends GuiScreen {
	private static final int TOP_BAR_HEIGHT = 26;
	private static final int BOTTOM_BAR_HEIGHT = 18;
	private static final String[] DIM_NAMES = { "Overworld", "Nether", "End" };
	private static final int[] DIM_IDS = { 0, -1, 1 };
	private static final int BTN_DIM_FIRST = 10;
	private final FlatButton[] dimTabs = new FlatButton[3];
	private static final double PAN_SPEED = 480.0; // GUI pixels per second
	private static final int HOVER_RADIUS = 8;

	private static final int ACTION_NEW_WAYPOINT = 0;
	private static final int ACTION_TELEPORT_HERE = 1;
	private static final int ACTION_CENTER = 2;
	private static final int ACTION_EDIT = 3;
	private static final int ACTION_DELETE = 4;
	private static final int ACTION_TELEPORT_WAYPOINT = 5;
	private static final int ACTION_TOGGLE = 6;

	// View
	private double centerX, centerZ;
	/** The centre actually drawn this frame: centerX/Z snapped to the physical pixel grid. */
	private double shownX, shownZ;
	/** Zoom levels in physical pixels per block: whole numbers above 1, powers of two below, all crisp. */
	private static final double[] ZOOM_LEVELS = { 1.0 / 64, 1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1, 2, 3, 4,
			6, 8, 12, 16, 24, 32, 48, 64 };
	private double scale, targetScale;
	private boolean initialized;
	private boolean follow = true;
	private boolean zoomAnchored;
	private double anchorWorldX, anchorWorldZ, anchorScreenX, anchorScreenY;
	private long lastFrameNanos;
	private int guiScale = 1;

	// Dragging
	private boolean dragging;
	private boolean dragMoved;
	private double dragStartMouseX, dragStartMouseY, dragStartCenterX, dragStartCenterZ;

	// Double click
	private long lastClickTime;
	private int lastClickX, lastClickY;

	// Dimension being viewed
	private int viewDimension = Integer.MIN_VALUE;
	private int playerDimension = Integer.MIN_VALUE;
	private MapStorage auxStorage;
	private List<Waypoint> crossDimWaypoints;

	// Context menu
	private boolean menuOpen;
	private int menuX, menuY, menuW;
	private int menuWorldX, menuWorldZ;
	private Waypoint menuWaypoint;
	private final List<String> menuLabels = new ArrayList<String>();
	private final List<Integer> menuActions = new ArrayList<Integer>();

	private final double[] rectX = new double[4];
	private final double[] rectY = new double[4];

	// Cave layers (current dimension only). SURFACE means the normal map.
	private static final int SURFACE = Integer.MIN_VALUE;
	private int viewLayer = SURFACE;
	/** Show whichever layer the player is in, as they move (until a layer is picked by hand). */
	private boolean layerFollowsPlayer = true;
	private boolean layerPanelVisible;
	private int panelX, panelY, panelW, panelH;
	private static final int PANEL_ARROW_W = 14;

	// Waypoint tile letters, queued while the tiles themselves are batched.
	private final List<int[]> letterSpots = new ArrayList<int[]>();
	private final List<String> letterTexts = new ArrayList<String>();

	@SuppressWarnings("unchecked")
	@Override
	public void initGui() {
		Minecraft mc = Minecraft.getMinecraft();
		guiScale = Math.max(1, new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight).getScaleFactor());

		if (!initialized && mc.thePlayer != null) {
			centerX = shownX = mc.thePlayer.posX;
			centerZ = shownZ = mc.thePlayer.posZ;
			scale = targetScale = clampScale(ZOOM_LEVELS[nearestZoomLevel(MapConfig.instance.fullscreenScale)] / guiScale);
			initialized = true;
		}
		if (mc.theWorld != null) {
			int dim = mc.theWorld.provider.dimensionId;
			if (viewDimension == Integer.MIN_VALUE)
				viewDimension = dim;
			if (playerDimension == Integer.MIN_VALUE)
				playerDimension = dim;
		}

		// Toolbar: actions on the left, dimension tabs in the middle, close on the right.
		boolean narrow = this.width < 420;
		int btnH = 18, btnY = 4;
		int wpW = narrow ? 60 : 72, setW = narrow ? 54 : 62, closeW = narrow ? 22 : 54;
		this.buttonList.add(new FlatButton(0, 4, btnY, wpW, btnH, "Waypoints"));
		this.buttonList.add(new FlatButton(1, 4 + wpW + 4, btnY, setW, btnH, "Settings"));
		this.buttonList.add(new FlatButton(2, this.width - closeW - 4, btnY, closeW, btnH, narrow ? "X" : "Close"));
		int tabsW = 0;
		for (String name : DIM_NAMES)
			tabsW += this.fontRenderer.getStringWidth(name) + 14 + 2;
		int tabX = Math.max(4 + wpW + 4 + setW + 8, (this.width - tabsW) / 2);
		for (int i = 0; i < DIM_NAMES.length; i++) {
			int w = this.fontRenderer.getStringWidth(DIM_NAMES[i]) + 14;
			dimTabs[i] = new FlatButton(BTN_DIM_FIRST + i, tabX, btnY, w, btnH, DIM_NAMES[i]).style(MapUi.STYLE_TAB);
			this.buttonList.add(dimTabs[i]);
			tabX += w + 2;
		}

		dragging = false;
		menuOpen = false;
		lastFrameNanos = 0;
	}

	@Override
	public void onGuiClosed() {
		MapConfig.instance.fullscreenScale = targetScale > 0 ? targetScale : 1.0;
		MapConfig.instance.saveConfig();
		closeAuxStorage();
	}

	private void closeAuxStorage() {
		if (auxStorage != null) {
			auxStorage.close();
			auxStorage = null;
		}
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (!button.enabled)
			return;
		if (button.id >= BTN_DIM_FIRST && button.id < BTN_DIM_FIRST + DIM_IDS.length) {
			switchDimension(DIM_IDS[button.id - BTN_DIM_FIRST]);
			return;
		}
		switch (button.id) {
			case 0:
				this.mc.displayGuiScreen(new GuiWaypoints(this));
				break;
			case 1:
				this.mc.displayGuiScreen(new GuiMapSettings(this));
				break;
			case 2:
				this.mc.displayGuiScreen(null);
				break;
		}
	}

	// ------------------------------------------------------------------- state

	private boolean viewingPlayerDimension() {
		return viewDimension == playerDimension;
	}

	private MapStorage storage() {
		if (viewingPlayerDimension()) {
			CaveLayers layers = MapEngine.INSTANCE.caveLayers();
			if (viewLayer != SURFACE && layers != null) {
				MapStorage layer = layers.layer(viewLayer);
				if (layer != null)
					return layer;
			}
			return MapEngine.INSTANCE.storage();
		}
		if (auxStorage == null)
			auxStorage = MapEngine.INSTANCE.openDimension(viewDimension);
		return auxStorage;
	}

	private List<Waypoint> waypoints() {
		return crossDimWaypoints != null ? crossDimWaypoints : WaypointManager.instance.getWaypoints();
	}

	private void saveWaypoints() {
		if (crossDimWaypoints != null)
			WaypointManager.instance.saveWaypointsForDim(viewDimension, crossDimWaypoints);
		else
			WaypointManager.instance.save();
	}

	private void switchDimension(int dim) {
		if (dim == viewDimension)
			return;
		closeAuxStorage();
		menuOpen = false;
		zoomAnchored = false;

		// Keep looking at the equivalent place when hopping between Overworld and Nether.
		if (viewDimension == 0 && dim == -1) {
			centerX /= 8.0;
			centerZ /= 8.0;
		} else if (viewDimension == -1 && dim == 0) {
			centerX *= 8.0;
			centerZ *= 8.0;
		}

		viewDimension = dim;
		viewLayer = SURFACE;
		layerFollowsPlayer = dim == playerDimension;
		if (dim != playerDimension) {
			crossDimWaypoints = WaypointManager.instance.loadWaypointsForDim(dim);
			follow = false;
		} else {
			crossDimWaypoints = null;
		}
	}

	private int spawnX() {
		if (viewDimension == 1)
			return 100;
		return viewDimension == -1 ? SpawnTracker.instance.getNetherSpawnX() : SpawnTracker.instance.getSpawnX();
	}

	private int spawnZ() {
		if (viewDimension == 1)
			return 0;
		return viewDimension == -1 ? SpawnTracker.instance.getNetherSpawnZ() : SpawnTracker.instance.getSpawnZ();
	}

	private int mapHeight() {
		return this.height - TOP_BAR_HEIGHT - BOTTOM_BAR_HEIGHT;
	}

	private double midX() {
		return this.width / 2.0;
	}

	private double midY() {
		return TOP_BAR_HEIGHT + mapHeight() / 2.0;
	}

	private boolean inMapArea(double x, double y) {
		return y >= TOP_BAR_HEIGHT && y < this.height - BOTTOM_BAR_HEIGHT && x >= 0 && x < this.width;
	}

	private double toWorldX(double screenX) {
		return shownX + (screenX - midX()) / scale;
	}

	private double toWorldZ(double screenY) {
		return shownZ + (screenY - midY()) / scale;
	}

	private double toScreenX(double worldX) {
		return midX() + (worldX - shownX) * scale;
	}

	private double toScreenY(double worldZ) {
		return midY() + (worldZ - shownZ) * scale;
	}

	private static double clampScale(double guiPixelsPerBlock) {
		return Math.max(MapConfig.MIN_FULLSCREEN_SCALE, Math.min(MapConfig.MAX_FULLSCREEN_SCALE, guiPixelsPerBlock));
	}

	/** Index of the zoom level closest to a scale given in GUI pixels per block. */
	private int nearestZoomLevel(double guiPixelsPerBlock) {
		double real = Math.log(Math.max(1.0e-6, guiPixelsPerBlock * guiScale));
		int best = 0;
		for (int i = 1; i < ZOOM_LEVELS.length; i++) {
			if (Math.abs(Math.log(ZOOM_LEVELS[i]) - real) < Math.abs(Math.log(ZOOM_LEVELS[best]) - real))
				best = i;
		}
		return best;
	}

	private static boolean keyDown(int key) {
		return Keyboard.isCreated() && Keyboard.isKeyDown(key);
	}

	/** Advances pan/zoom animation by the real time since the last frame. */
	private void updateView(double mouseX, double mouseY, EntityPlayer player, float partialTicks) {
		long nowNanos = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0.0 : Math.min(0.1, (nowNanos - lastFrameNanos) / 1.0e9);
		lastFrameNanos = nowNanos;

		if (!menuOpen) {
			double pan = PAN_SPEED * dt / scale;
			double dx = 0, dz = 0;
			if (keyDown(Keyboard.KEY_LEFT) || keyDown(Keyboard.KEY_A))
				dx -= pan;
			if (keyDown(Keyboard.KEY_RIGHT) || keyDown(Keyboard.KEY_D))
				dx += pan;
			if (keyDown(Keyboard.KEY_UP) || keyDown(Keyboard.KEY_W))
				dz -= pan;
			if (keyDown(Keyboard.KEY_DOWN) || keyDown(Keyboard.KEY_S))
				dz += pan;
			if (dx != 0 || dz != 0) {
				centerX += dx;
				centerZ += dz;
				follow = false;
				zoomAnchored = false;
			}
		}

		if (dragging) {
			if (!Mouse.isCreated() || !Mouse.isButtonDown(0)) {
				dragging = false;
			} else {
				double ddx = mouseX - dragStartMouseX, ddy = mouseY - dragStartMouseY;
				if (!dragMoved && (Math.abs(ddx) > 2.0 || Math.abs(ddy) > 2.0))
					dragMoved = true;
				if (dragMoved) {
					centerX = dragStartCenterX - ddx / scale;
					centerZ = dragStartCenterZ - ddy / scale;
					follow = false;
					zoomAnchored = false;
				}
			}
		}

		if (scale != targetScale) {
			double ratio = targetScale / scale;
			double next;
			if (dt <= 0.0)
				next = scale; // first frame: nothing to animate yet
			else if (Math.abs(Math.log(ratio)) < 0.004)
				next = targetScale;
			else
				next = scale * Math.pow(ratio, 1.0 - Math.exp(-dt * 18.0));
			if (zoomAnchored && !follow) {
				centerX = anchorWorldX - (anchorScreenX - midX()) / next;
				centerZ = anchorWorldZ - (anchorScreenY - midY()) / next;
			}
			if (dragging) {
				// Keep the grabbed point under the cursor while zooming mid-drag.
				dragStartCenterX = centerX + (mouseX - dragStartMouseX) / next;
				dragStartCenterZ = centerZ + (mouseY - dragStartMouseY) / next;
			}
			scale = next;
		}

		if (follow && viewingPlayerDimension()) {
			centerX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
			centerZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
		}
		shownX = MapDraw.snap(centerX, midX(), scale, guiScale);
		shownZ = MapDraw.snap(centerZ, midY(), scale, guiScale);
	}

	// -------------------------------------------------------------------- draw

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.theWorld == null || mc.thePlayer == null) {
			this.mc.displayGuiScreen(null);
			return;
		}
		EntityPlayer player = mc.thePlayer;

		// The player changed dimension while the map was open: snap back to where they are.
		int dimNow = mc.theWorld.provider.dimensionId;
		if (dimNow != playerDimension) {
			playerDimension = dimNow;
			closeAuxStorage();
			viewDimension = dimNow;
			crossDimWaypoints = null;
			follow = true;
			menuOpen = false;
			viewLayer = SURFACE;
			layerFollowsPlayer = true;
		}
		updateLayer(player);

		// Sub-pixel mouse position, so dragging is as smooth as the display allows.
		double mx = Mouse.getX() * (double) this.width / mc.displayWidth;
		double my = this.height - Mouse.getY() * (double) this.height / mc.displayHeight;

		updateView(mx, my, player, partialTicks);
		for (int i = 0; i < dimTabs.length; i++)
			if (dimTabs[i] != null)
				dimTabs[i].selected = DIM_IDS[i] == viewDimension;

		MapStorage storage = storage();
		if (storage != null) {
			storage.beginFrame();
			if (storage == auxStorage)
				storage.tick();
		}

		int mapTop = TOP_BAR_HEIGHT;
		int mapBottom = this.height - BOTTOM_BAR_HEIGHT;
		int mapH = mapBottom - mapTop;
		double midX = midX(), midY = midY();
		FontRenderer fr = this.fontRenderer;
		Tessellator t = Tessellator.instance;

		Waypoint hovered = null;
		boolean hoveredSpawn = false;
		boolean complete = true;

		int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT
				| GL11.GL_SCISSOR_BIT | GL11.GL_LIGHTING_BIT);
		try {
			GL11.glDisable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_CULL_FACE);
			GL11.glDisable(GL11.GL_ALPHA_TEST);
			GL11.glDisable(GL11.GL_LIGHTING);
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			GL11.glLineWidth(1.0f);
			GL11.glShadeModel(GL11.GL_SMOOTH);
			GL11.glDisable(GL11.GL_TEXTURE_2D);

			fill(t, 0, 0, this.width, this.height, 0.08f, 0.08f, 0.10f, 1.0f);

			// Clip everything map-related to the area between the two bars.
			int scissorY = (int) Math.round((double) BOTTOM_BAR_HEIGHT * mc.displayHeight / this.height);
			int scissorH = (int) Math.round((double) mapH * mc.displayHeight / this.height);
			GL11.glEnable(GL11.GL_SCISSOR_TEST);
			GL11.glScissor(0, scissorY, mc.displayWidth, Math.max(0, scissorH));

			// ---- Terrain
			double halfW = this.width / 2.0, halfH = mapH / 2.0;
			rectX[0] = -halfW;
			rectY[0] = -halfH;
			rectX[1] = halfW;
			rectY[1] = -halfH;
			rectX[2] = halfW;
			rectY[2] = halfH;
			rectX[3] = -halfW;
			rectY[3] = halfH;
			GL11.glEnable(GL11.GL_TEXTURE_2D);
			MapDraw.sharpScaling = MapConfig.instance.sharpScaling;
			complete = MapDraw.draw(storage, shownX, shownZ, scale, 0.0, midX, midY, rectX, rectY, 4, 1.0f,
					guiScale, true);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			GL11.glDisable(GL11.GL_TEXTURE_2D);

			if (MapConfig.instance.showChunkGrid)
				drawChunkGrid(t, mapTop, mapBottom);

			// ---- Markers
			boolean mouseOnMap = inMapArea(mx, my) && !menuOpen;
			double bestDist = HOVER_RADIUS * HOVER_RADIUS;
			letterSpots.clear();
			letterTexts.clear();
			t.startDrawing(GL11.GL_TRIANGLES);
			if (MapConfig.instance.showSpawnWaypoint && SpawnTracker.instance.hasSpawn()) {
				double sx = toScreenX(spawnX() + 0.5), sy = toScreenY(spawnZ() + 0.5);
				if (onScreen(sx, sy)) {
					addWaypointIcon(t, sx, sy, 0x0066CC, "Spawn");
					double d = (mx - sx) * (mx - sx) + (my - sy) * (my - sy);
					if (mouseOnMap && d < bestDist) {
						bestDist = d;
						hoveredSpawn = true;
					}
				}
			}
			if (MapConfig.instance.showWaypoints) {
				List<Waypoint> list = waypoints();
				for (int i = 0, n = list.size(); i < n; i++) {
					Waypoint wp = list.get(i);
					if (!wp.enabled)
						continue;
					double sx = toScreenX(wp.x + 0.5), sy = toScreenY(wp.z + 0.5);
					if (!onScreen(sx, sy))
						continue;
					addWaypointIcon(t, sx, sy, wp.color, wp.name);
					double d = (mx - sx) * (mx - sx) + (my - sy) * (my - sy);
					if (mouseOnMap && d < bestDist) {
						bestDist = d;
						hovered = wp;
						hoveredSpawn = false;
					}
				}
			}
			if (viewingPlayerDimension()) {
				double ppx = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
				double ppz = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
				float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
				double sx = toScreenX(ppx), sy = toScreenY(ppz);
				if (onScreen(sx, sy))
					MapMarkers.playerArrow(t, sx, sy, Math.toRadians(yaw + 90.0), 8.0);
			}
			t.draw();
			if (fr != null && !letterSpots.isEmpty()) {
				GL11.glEnable(GL11.GL_TEXTURE_2D);
				for (int i = 0; i < letterSpots.size(); i++) {
					int[] spot = letterSpots.get(i);
					String letter = letterTexts.get(i);
					fr.drawString(letter, MapMarkers.tileLetterX(spot[0], fr.getStringWidth(letter)), spot[1] - 3,
							spot[2]);
				}
				GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
				GL11.glDisable(GL11.GL_TEXTURE_2D);
			}
			GL11.glDisable(GL11.GL_SCISSOR_TEST);

			// ---- Bars
			MapUi.bar(0, 0, this.width, TOP_BAR_HEIGHT, true);
			MapUi.bar(0, mapBottom, this.width, this.height, false);
			GL11.glDisable(GL11.GL_TEXTURE_2D);

			// Cave layer panel (shapes; its text is drawn with the other text below)
			if (fr != null)
				drawLayerPanelShapes(t, fr, mx, my);

			// ---- Text
			GL11.glEnable(GL11.GL_TEXTURE_2D);
			GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
			if (fr != null) {
				if (MapConfig.instance.showCompass) {
					drawStringOutlined(fr, "N", this.width / 2 - fr.getStringWidth("N") / 2, mapTop + 4);
					drawStringOutlined(fr, "S", this.width / 2 - fr.getStringWidth("S") / 2, mapBottom - 12);
					drawStringOutlined(fr, "W", 4, mapTop + mapH / 2 - 4);
					drawStringOutlined(fr, "E", this.width - fr.getStringWidth("E") - 4, mapTop + mapH / 2 - 4);
				}

				drawScaleBar(fr, mapBottom);
				if (layerPanelVisible)
					drawLayerPanelText(fr);
				drawBottomBar(fr, storage, player, mx, my, mapBottom, complete);

				if (!menuOpen) {
					if (hovered != null) {
						drawLabel(fr, hovered.name, hovered.x, hovered.y, hovered.z, hovered.color);
					} else if (hoveredSpawn) {
						drawLabel(fr, "Spawn Point", spawnX(), viewDimension == 1 ? 49 : SpawnTracker.instance.getSpawnY(),
								spawnZ(), 0x3F7FE0);
					}
				} else {
					drawMenu(fr, mx, my);
				}
			}
			GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
		} finally {
			GL11.glPopAttrib();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
		}

		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	private void addWaypointIcon(Tessellator t, double sx, double sy, int rgb, String name) {
		int x = (int) Math.round(sx), y = (int) Math.round(sy);
		if (MapConfig.instance.waypointIconStyle == MapConfig.WAYPOINT_LETTERS) {
			MapMarkers.waypointTile(t, x, y, rgb);
			letterSpots.add(new int[] { x, y, MapMarkers.tileLetterColor(rgb) });
			letterTexts.add(MapMarkers.tileLetter(name));
		} else {
			MapMarkers.waypoint(t, x, y, 3, rgb);
		}
	}

	// ------------------------------------------------------------- cave layers

	/** Surface first, then every known cave layer from the highest down. */
	private int[] layerChoices() {
		CaveLayers layers = MapEngine.INSTANCE.caveLayers();
		int[] known = layers != null ? layers.knownLayers() : new int[0];
		Integer mine = MapEngine.INSTANCE.currentCaveLayer(this.mc.thePlayer);
		boolean addMine = mine != null;
		for (int k : known)
			if (mine != null && k == mine)
				addMine = false;
		int[] out = new int[1 + known.length + (addMine ? 1 : 0)];
		out[0] = SURFACE;
		int n = 1;
		int[] all = known;
		if (addMine) {
			all = java.util.Arrays.copyOf(known, known.length + 1);
			all[known.length] = mine;
			java.util.Arrays.sort(all);
		}
		for (int i = all.length - 1; i >= 0; i--)
			out[n++] = all[i];
		return out;
	}

	private void updateLayer(EntityPlayer player) {
		layerPanelVisible = false;
		if (!viewingPlayerDimension() || MapEngine.INSTANCE.caveLayers() == null)
			return;
		if (layerFollowsPlayer) {
			Integer mine = MapEngine.INSTANCE.currentCaveLayer(player);
			viewLayer = mine != null ? mine : SURFACE;
		}
		layerPanelVisible = viewLayer != SURFACE || layerChoices().length > 1;
	}

	/** -1 = towards the surface, 1 = deeper. */
	private void stepLayer(int direction) {
		if (!viewingPlayerDimension() || MapEngine.INSTANCE.caveLayers() == null)
			return;
		int[] choices = layerChoices();
		int index = 0;
		for (int i = 0; i < choices.length; i++)
			if (choices[i] == viewLayer)
				index = i;
		int next = Math.max(0, Math.min(choices.length - 1, index + direction));
		if (choices[next] != viewLayer) {
			viewLayer = choices[next];
			layerFollowsPlayer = false;
			menuOpen = false;
		}
	}

	private String layerLabel() {
		if (viewLayer == SURFACE)
			return "Surface";
		int bottom = CaveLayers.bottomOf(viewLayer);
		return "Caves  y " + bottom + " to " + (bottom + CaveLayers.LAYER_HEIGHT - 1);
	}

	private boolean inPanel(double x, double y) {
		return x >= panelX && x < panelX + panelW && y >= panelY && y < panelY + panelH;
	}

	private void drawLayerPanelShapes(Tessellator t, FontRenderer fr, double mx, double my) {
		if (!layerPanelVisible)
			return;
		panelW = PANEL_ARROW_W * 2 + fr.getStringWidth("Caves  y 240 to 255") + 10;
		panelH = 16;
		panelX = this.width - panelW - 6;
		panelY = TOP_BAR_HEIGHT + 6;
		int[] choices = layerChoices();
		boolean canUp = choices[0] != viewLayer;
		boolean canDown = choices[choices.length - 1] != viewLayer;

		MapUi.panel(panelX, panelY, panelX + panelW, panelY + panelH);
		boolean hover = inPanel(mx, my) && !menuOpen;
		if (hover) {
			int x0 = mx < panelX + PANEL_ARROW_W ? panelX
					: (mx >= panelX + panelW - PANEL_ARROW_W ? panelX + panelW - PANEL_ARROW_W : panelX + PANEL_ARROW_W);
			int x1 = x0 == panelX + PANEL_ARROW_W ? panelX + panelW - PANEL_ARROW_W : x0 + PANEL_ARROW_W;
			MapUi.rect(x0 + 1, panelY + 1, x1 - 1, panelY + panelH - 1, 0x902F64C8);
		}
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		// Up (towards the surface) on the left, down (deeper) on the right.
		t.startDrawing(GL11.GL_TRIANGLES);
		float upC = canUp ? 0.95f : 0.35f, downC = canDown ? 0.95f : 0.35f;
		double cy = panelY + panelH / 2.0;
		double ux = panelX + PANEL_ARROW_W / 2.0;
		t.setColorRGBA_F(upC, upC, upC, 1.0f);
		t.addVertex(ux, cy - 3, 0.0);
		t.addVertex(ux - 4, cy + 2.5, 0.0);
		t.addVertex(ux + 4, cy + 2.5, 0.0);
		double dx = panelX + panelW - PANEL_ARROW_W / 2.0;
		t.setColorRGBA_F(downC, downC, downC, 1.0f);
		t.addVertex(dx - 4, cy - 2.5, 0.0);
		t.addVertex(dx, cy + 3, 0.0);
		t.addVertex(dx + 4, cy - 2.5, 0.0);
		t.draw();
	}

	private void drawLayerPanelText(FontRenderer fr) {
		String label = layerLabel();
		Integer mine = MapEngine.INSTANCE.currentCaveLayer(this.mc.thePlayer);
		boolean playersLayer = viewLayer == SURFACE ? mine == null : (mine != null && mine == viewLayer);
		int color = playersLayer ? 0xFFE070 : 0xFFFFFF;
		int textW = fr.getStringWidth(label);
		fr.drawStringWithShadow(label, panelX + (panelW - textW) / 2, panelY + 3, color);
	}

	private boolean onScreen(double sx, double sy) {
		return sx >= -8 && sx <= this.width + 8 && sy >= TOP_BAR_HEIGHT - 8
				&& sy <= this.height - BOTTOM_BAR_HEIGHT + 8;
	}

	private void drawBottomBar(FontRenderer fr, MapStorage storage, EntityPlayer player, double mx, double my,
			int mapBottom, boolean complete) {
		int textY = mapBottom + 5;

		String left;
		if (layerPanelVisible && inPanel(mx, my)) {
			left = "PgUp/PgDn: layer \u00a78|\u00a7r click the name: follow your level";
		} else if (inMapArea(mx, my)) {
			int wx = (int) Math.floor(toWorldX(mx));
			int wz = (int) Math.floor(toWorldZ(my));
			int h = storage != null ? storage.dataAt(wx, wz) >>> 24 : 0;
			left = "Cursor: x: " + wx + ", z: " + wz + (h > 0 ? ", y: " + h : "");
		} else {
			left = "Right-click the map for options";
		}
		fr.drawStringWithShadow(left, 6, textY, 0xCCCCCC);

		String right = "Player: x: " + (int) Math.floor(player.posX) + ", y: "
				+ (int) Math.floor(player.boundingBox.minY) + ", z: " + (int) Math.floor(player.posZ);
		int rightX = this.width - fr.getStringWidth(right) - 6;
		fr.drawStringWithShadow(right, rightX, textY, 0xCCCCCC);

		String mid = "Zoom: " + formatScale(targetScale);
		if (!complete)
			mid += "  \u00a78loading...";
		else if (!follow && viewingPlayerDimension())
			mid += "  \u00a78[C] follow";
		int midW = fr.getStringWidth(mid);
		int midX = this.width / 2 - midW / 2;
		// Only when it fits between the two side texts.
		if (midX > 6 + fr.getStringWidth(left) + 8 && midX + midW < rightX - 8)
			fr.drawStringWithShadow(mid, midX, textY, 0xAAAAFF);
	}

	private static String formatScale(double s) {
		double v = s >= 1.0 ? s : 1.0 / s;
		double rounded = Math.round(v * 10.0) / 10.0;
		String num = rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
		return s >= 1.0 ? num + "x" : "1/" + num + "x";
	}

	private void drawChunkGrid(Tessellator t, int mapTop, int mapBottom) {
		double step = 16.0 * scale;
		if (step < 5.0)
			return;
		t.startDrawing(GL11.GL_LINES);
		t.setColorRGBA_F(1.0f, 1.0f, 1.0f, 0.14f);
		int k0 = (int) Math.ceil(toWorldX(0) / 16.0), k1 = (int) Math.floor(toWorldX(this.width) / 16.0);
		for (int k = k0; k <= k1; k++) {
			double sx = toScreenX(k * 16.0);
			t.addVertex(sx, mapTop, 0.0);
			t.addVertex(sx, mapBottom, 0.0);
		}
		int j0 = (int) Math.ceil(toWorldZ(mapTop) / 16.0), j1 = (int) Math.floor(toWorldZ(mapBottom) / 16.0);
		for (int j = j0; j <= j1; j++) {
			double sy = toScreenY(j * 16.0);
			t.addVertex(0, sy, 0.0);
			t.addVertex(this.width, sy, 0.0);
		}
		t.draw();
	}

	/** Card above a hovered marker: tile, name, coordinates and distance. Leaves texturing enabled. */
	private void drawLabel(FontRenderer fr, String name, int x, int y, int z, int rgb) {
		String coords = "x " + x + "   y " + y + "   z " + z;
		if (viewingPlayerDimension() && this.mc.thePlayer != null) {
			double dx = x + 0.5 - this.mc.thePlayer.posX, dz = z + 0.5 - this.mc.thePlayer.posZ;
			coords += "   " + MapUi.distance(Math.sqrt(dx * dx + dz * dz));
		}
		int w = Math.max(fr.getStringWidth(name) + 16, fr.getStringWidth(coords)) + 12;
		int h = 26;
		int sx = (int) Math.round(toScreenX(x + 0.5)), sy = (int) Math.round(toScreenY(z + 0.5));
		int left = Math.max(4, Math.min(sx - w / 2, this.width - w - 4));
		int top = sy - h - 10;
		if (top < TOP_BAR_HEIGHT + 4)
			top = sy + 10;
		MapUi.panel(left, top, left + w, top + h);
		MapUi.tile(fr, left + 6, top + 5, 9, rgb, MapMarkers.tileLetter(name));
		fr.drawStringWithShadow(name, left + 19, top + 5, 0xFFFFFF);
		fr.drawStringWithShadow(coords, left + 6, top + 16, MapUi.TEXT_DIM);
	}

	/** Distance ruler in the bottom-left corner of the map. */
	private void drawScaleBar(FontRenderer fr, int mapBottom) {
		int[] nice = { 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000 };
		int blocks = nice[0];
		for (int n : nice)
			if (n * scale <= 90)
				blocks = n;
		int length = (int) Math.round(blocks * scale);
		if (length < 12)
			return;
		int x0 = 8, y0 = mapBottom - 9;
		MapUi.rect(x0 - 1, y0 - 4, x0 + length + 1, y0 + 3, 0x60000000);
		MapUi.rect(x0, y0, x0 + length, y0 + 2, 0xFFEDEDED);
		MapUi.rect(x0, y0 - 3, x0 + 1, y0 + 2, 0xFFEDEDED);
		MapUi.rect(x0 + length - 1, y0 - 3, x0 + length, y0 + 2, 0xFFEDEDED);
		String label = blocks >= 1000 && blocks % 1000 == 0 ? (blocks / 1000) + "k blocks"
				: blocks + (blocks == 1 ? " block" : " blocks");
		fr.drawStringWithShadow(label, x0, y0 - 13, 0xFFFFFF);
	}

	private void fill(Tessellator t, double x0, double y0, double x1, double y1, float r, float g, float b, float a) {
		t.startDrawingQuads();
		t.setColorRGBA_F(r, g, b, a);
		t.addVertex(x0, y1, 0.0);
		t.addVertex(x1, y1, 0.0);
		t.addVertex(x1, y0, 0.0);
		t.addVertex(x0, y0, 0.0);
		t.draw();
	}

	private void drawStringOutlined(FontRenderer fr, String text, int x, int y) {
		fr.drawString(text, x + 1, y, 0x000000);
		fr.drawString(text, x - 1, y, 0x000000);
		fr.drawString(text, x, y + 1, 0x000000);
		fr.drawString(text, x, y - 1, 0x000000);
		fr.drawString(text, x, y, 0xFFFFFF);
	}

	// ------------------------------------------------------------ context menu

	private static final int MENU_ROW = 14;

	private void openMenu(int mouseX, int mouseY) {
		menuLabels.clear();
		menuActions.clear();
		menuWorldX = (int) Math.floor(toWorldX(mouseX));
		menuWorldZ = (int) Math.floor(toWorldZ(mouseY));
		menuWaypoint = waypointNear(mouseX, mouseY);
		boolean canTeleport = MapConfig.instance.showTeleportButton;

		if (menuWaypoint != null) {
			addMenuItem("Edit \"" + menuWaypoint.name + "\"", ACTION_EDIT);
			addMenuItem(menuWaypoint.enabled ? "Hide" : "Show", ACTION_TOGGLE);
			if (canTeleport)
				addMenuItem("Teleport to waypoint", ACTION_TELEPORT_WAYPOINT);
			addMenuItem("Delete", ACTION_DELETE);
		} else {
			addMenuItem("New waypoint here", ACTION_NEW_WAYPOINT);
			if (canTeleport)
				addMenuItem("Teleport here", ACTION_TELEPORT_HERE);
		}
		if (viewingPlayerDimension())
			addMenuItem("Center on player", ACTION_CENTER);

		menuW = 0;
		for (String label : menuLabels)
			menuW = Math.max(menuW, this.fontRenderer.getStringWidth(label));
		menuW += 16;
		int menuH = menuLabels.size() * MENU_ROW + 4;
		menuX = Math.max(2, Math.min(mouseX, this.width - menuW - 2));
		menuY = Math.max(TOP_BAR_HEIGHT + 2, Math.min(mouseY, this.height - BOTTOM_BAR_HEIGHT - menuH - 2));
		menuOpen = true;
		dragging = false;
	}

	private void addMenuItem(String label, int action) {
		menuLabels.add(label);
		menuActions.add(action);
	}

	private int menuIndexAt(double mx, double my) {
		if (!menuOpen || mx < menuX || mx >= menuX + menuW)
			return -1;
		int index = (int) Math.floor((my - menuY - 2) / MENU_ROW);
		return index >= 0 && index < menuLabels.size() && my >= menuY + 2 ? index : -1;
	}

	private void drawMenu(FontRenderer fr, double mx, double my) {
		int menuH = menuLabels.size() * MENU_ROW + 4;
		int hover = menuIndexAt(mx, my);
		MapUi.panel(menuX, menuY, menuX + menuW, menuY + menuH);
		if (hover >= 0)
			MapUi.rect(menuX + 2, menuY + 2 + hover * MENU_ROW, menuX + menuW - 2, menuY + 2 + (hover + 1) * MENU_ROW,
					menuActions.get(hover) == ACTION_DELETE ? 0xC05A2328 : 0xC02F64C8);
		for (int i = 0; i < menuLabels.size(); i++) {
			int color = menuActions.get(i) == ACTION_DELETE ? 0xFF9A92 : (i == hover ? 0xFFFFFF : 0xE6E7EC);
			fr.drawStringWithShadow(menuLabels.get(i), menuX + 8, menuY + 5 + i * MENU_ROW, color);
		}
	}

	private Waypoint waypointNear(double mx, double my) {
		if (!MapConfig.instance.showWaypoints)
			return null;
		Waypoint best = null;
		double bestDist = HOVER_RADIUS * HOVER_RADIUS;
		for (Waypoint wp : waypoints()) {
			if (!wp.enabled)
				continue;
			double dx = mx - toScreenX(wp.x + 0.5), dy = my - toScreenY(wp.z + 0.5);
			double d = dx * dx + dy * dy;
			if (d < bestDist) {
				bestDist = d;
				best = wp;
			}
		}
		return best;
	}

	private void runMenuAction(int action) {
		Waypoint wp = menuWaypoint;
		switch (action) {
			case ACTION_NEW_WAYPOINT:
				createWaypoint(menuWorldX, menuWorldZ);
				break;
			case ACTION_TELEPORT_HERE: {
				int y = surfaceY(menuWorldX, menuWorldZ);
				if (y < 0) {
					this.mc.thePlayer.addChatMessage("\u00a7c[Minimap] Can't teleport there: that spot is unexplored.");
				} else {
					teleport(menuWorldX, y, menuWorldZ, "Map");
				}
				break;
			}
			case ACTION_CENTER:
				follow = true;
				zoomAnchored = false;
				break;
			case ACTION_EDIT:
				if (wp != null)
					this.mc.displayGuiScreen(new GuiWaypointEdit(this, wp, false, viewDimension, crossDimWaypoints));
				break;
			case ACTION_TOGGLE:
				if (wp != null) {
					wp.enabled = !wp.enabled;
					saveWaypoints();
				}
				break;
			case ACTION_DELETE:
				if (wp != null) {
					waypoints().remove(wp);
					saveWaypoints();
				}
				break;
			case ACTION_TELEPORT_WAYPOINT:
				if (wp != null)
					teleport(wp.x, wp.y + 2, wp.z, wp.name);
				break;
		}
	}

	/** Y to stand on at a map position, or -1 when nothing is known about it. */
	private int surfaceY(int worldX, int worldZ) {
		MapStorage storage = storage();
		int h = storage != null ? storage.dataAt(worldX, worldZ) >>> 24 : 0;
		return h > 0 ? h + 1 : -1;
	}

	private void createWaypoint(int worldX, int worldZ) {
		int y = surfaceY(worldX, worldZ);
		List<Waypoint> list = waypoints();
		Waypoint wp = new Waypoint("Waypoint " + (list.size() + 1), worldX, y < 0 ? 64 : y, worldZ,
				nextColor(list.size()));
		list.add(wp); // GuiWaypointEdit removes it again if the user cancels
		this.mc.displayGuiScreen(new GuiWaypointEdit(this, wp, true, viewDimension, crossDimWaypoints));
	}

	private void teleport(int x, int y, int z, String name) {
		Teleport.to(this.mc, x, y, z, viewDimension, name);
	}

	private static int nextColor(int index) {
		int[] colors = { 0xFF5555, 0x55FF55, 0x5555FF, 0xFFFF55, 0xFF55FF, 0x55FFFF, 0xFF8800, 0xAA55FF };
		return colors[index % colors.length];
	}

	// ------------------------------------------------------------------- input

	private void zoom(int steps, double mouseX, double mouseY) {
		int index = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, nearestZoomLevel(targetScale) + steps));
		double next = clampScale(ZOOM_LEVELS[index] / guiScale);
		if (next == targetScale)
			return;
		targetScale = next;
		if (!follow && inMapArea(mouseX, mouseY)) {
			zoomAnchored = true;
			anchorScreenX = mouseX;
			anchorScreenY = mouseY;
			anchorWorldX = toWorldX(mouseX);
			anchorWorldZ = toWorldZ(mouseY);
		} else {
			zoomAnchored = false;
		}
		menuOpen = false;
	}

	@Override
	protected void keyTyped(char c, int keyCode) {
		if (keyCode == Keyboard.KEY_ESCAPE) {
			if (menuOpen) {
				menuOpen = false;
				return;
			}
			this.mc.displayGuiScreen(null);
			return;
		}
		if (keyCode == MapConfig.fullscreenMapKey.keyCode) {
			this.mc.displayGuiScreen(null);
			return;
		}
		if (keyCode == Keyboard.KEY_C) {
			if (viewingPlayerDimension()) {
				follow = true;
				zoomAnchored = false;
			}
		} else if (keyCode == Keyboard.KEY_PRIOR) {
			stepLayer(-1);
		} else if (keyCode == Keyboard.KEY_NEXT) {
			stepLayer(1);
		} else if (keyCode == Keyboard.KEY_EQUALS || keyCode == Keyboard.KEY_ADD) {
			zoom(1, midX(), midY());
		} else if (keyCode == Keyboard.KEY_MINUS || keyCode == Keyboard.KEY_SUBTRACT) {
			zoom(-1, midX(), midY());
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
		if (menuOpen) {
			int index = menuIndexAt(mouseX, mouseY);
			menuOpen = false;
			if (mouseButton == 0 && index >= 0)
				runMenuAction(menuActions.get(index));
			return;
		}

		super.mouseClicked(mouseX, mouseY, mouseButton);
		if (this.mc.currentScreen != this)
			return; // a button opened another screen

		if (mouseY < TOP_BAR_HEIGHT)
			return;
		if (!inMapArea(mouseX, mouseY))
			return;
		if (layerPanelVisible && inPanel(mouseX, mouseY)) {
			if (mouseButton == 0) {
				if (mouseX < panelX + PANEL_ARROW_W)
					stepLayer(-1);
				else if (mouseX >= panelX + panelW - PANEL_ARROW_W)
					stepLayer(1);
				else
					layerFollowsPlayer = true;
			}
			return;
		}

		if (mouseButton == 1) {
			openMenu(mouseX, mouseY);
			return;
		}
		if (mouseButton != 0)
			return;

		long now = System.currentTimeMillis();
		if (now - lastClickTime < 300 && Math.abs(mouseX - lastClickX) < 5 && Math.abs(mouseY - lastClickY) < 5) {
			lastClickTime = 0;
			createWaypoint((int) Math.floor(toWorldX(mouseX)), (int) Math.floor(toWorldZ(mouseY)));
			return;
		}
		lastClickTime = now;
		lastClickX = mouseX;
		lastClickY = mouseY;

		dragging = true;
		dragMoved = false;
		dragStartMouseX = Mouse.getX() * (double) this.width / this.mc.displayWidth;
		dragStartMouseY = this.height - Mouse.getY() * (double) this.height / this.mc.displayHeight;
		dragStartCenterX = centerX;
		dragStartCenterZ = centerZ;
	}

	@Override
	protected void mouseMovedOrUp(int mouseX, int mouseY, int mouseButton) {
		super.mouseMovedOrUp(mouseX, mouseY, mouseButton);
		if (mouseButton == 0)
			dragging = false;
	}

	@Override
	public void handleMouseInput() {
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if (wheel != 0) {
			double mx = Mouse.getEventX() * (double) this.width / this.mc.displayWidth;
			double my = this.height - Mouse.getEventY() * (double) this.height / this.mc.displayHeight;
			zoom(wheel > 0 ? 1 : -1, mx, my);
		}
	}

	@Override
	public boolean doesGuiPauseGame() {
		return MapConfig.instance.fullscreenMapPausesGame;
	}
}
