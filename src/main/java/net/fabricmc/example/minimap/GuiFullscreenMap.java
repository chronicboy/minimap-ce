package net.fabricmc.example.minimap;

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
	private static final int TAB_GAP = 16;
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

	// Waypoint tile letters, queued while the tiles themselves are batched.
	private final List<int[]> letterSpots = new ArrayList<int[]>();
	private final List<String> letterTexts = new ArrayList<String>();

	@SuppressWarnings("unchecked")
	@Override
	public void initGui() {
		Minecraft mc = Minecraft.getMinecraft();
		guiScale = Math.max(1, new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight).getScaleFactor());

		if (!initialized && mc.thePlayer != null) {
			centerX = mc.thePlayer.posX;
			centerZ = mc.thePlayer.posZ;
			scale = targetScale = MapConfig.instance.fullscreenScale;
			initialized = true;
		}
		if (mc.theWorld != null) {
			int dim = mc.theWorld.provider.dimensionId;
			if (viewDimension == Integer.MIN_VALUE)
				viewDimension = dim;
			if (playerDimension == Integer.MIN_VALUE)
				playerDimension = dim;
		}

		int btnW = 80, btnH = 20, gap = 4, btnY = 3;
		this.buttonList.add(new GuiButton(0, gap, btnY, btnW, btnH, "Waypoints"));
		this.buttonList.add(new GuiButton(1, gap + btnW + gap, btnY, btnW, btnH, "Settings"));
		this.buttonList.add(new GuiButton(2, this.width - btnW - gap, btnY, btnW, btnH, "Close"));

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
		if (viewingPlayerDimension())
			return MapEngine.INSTANCE.storage();
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
		return centerX + (screenX - midX()) / scale;
	}

	private double toWorldZ(double screenY) {
		return centerZ + (screenY - midY()) / scale;
	}

	private double toScreenX(double worldX) {
		return midX() + (worldX - centerX) * scale;
	}

	private double toScreenY(double worldZ) {
		return midY() + (worldZ - centerZ) * scale;
	}

	/** Advances pan/zoom animation by the real time since the last frame. */
	private void updateView(double mouseX, double mouseY, EntityPlayer player, float partialTicks) {
		long nowNanos = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0.0 : Math.min(0.1, (nowNanos - lastFrameNanos) / 1.0e9);
		lastFrameNanos = nowNanos;

		if (!menuOpen) {
			double pan = PAN_SPEED * dt / scale;
			double dx = 0, dz = 0;
			if (Keyboard.isKeyDown(Keyboard.KEY_LEFT) || Keyboard.isKeyDown(Keyboard.KEY_A))
				dx -= pan;
			if (Keyboard.isKeyDown(Keyboard.KEY_RIGHT) || Keyboard.isKeyDown(Keyboard.KEY_D))
				dx += pan;
			if (Keyboard.isKeyDown(Keyboard.KEY_UP) || Keyboard.isKeyDown(Keyboard.KEY_W))
				dz -= pan;
			if (Keyboard.isKeyDown(Keyboard.KEY_DOWN) || Keyboard.isKeyDown(Keyboard.KEY_S))
				dz += pan;
			if (dx != 0 || dz != 0) {
				centerX += dx;
				centerZ += dz;
				follow = false;
				zoomAnchored = false;
			}
		}

		if (dragging) {
			if (!Mouse.isButtonDown(0)) {
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
		}

		// Sub-pixel mouse position, so dragging is as smooth as the display allows.
		double mx = Mouse.getX() * (double) this.width / mc.displayWidth;
		double my = this.height - Mouse.getY() * (double) this.height / mc.displayHeight;

		updateView(mx, my, player, partialTicks);

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
			complete = MapDraw.draw(storage, centerX, centerZ, scale, 0.0, midX, midY, rectX, rectY, 4, 1.0f,
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
			fill(t, 0, 0, this.width, TOP_BAR_HEIGHT, 0.05f, 0.05f, 0.07f, 0.94f);
			fill(t, 0, TOP_BAR_HEIGHT - 1, this.width, TOP_BAR_HEIGHT, 0.30f, 0.30f, 0.35f, 0.8f);
			fill(t, 0, mapBottom, this.width, this.height, 0.05f, 0.05f, 0.07f, 0.94f);
			fill(t, 0, mapBottom, this.width, mapBottom + 1, 0.30f, 0.30f, 0.35f, 0.8f);

			// Active dimension underline
			if (fr != null) {
				int tabX = tabStartX(fr);
				int tabY = (TOP_BAR_HEIGHT - 8) / 2;
				for (int i = 0; i < DIM_NAMES.length; i++) {
					int tw = fr.getStringWidth(DIM_NAMES[i]);
					if (viewDimension == DIM_IDS[i])
						fill(t, tabX - 1, tabY + 10, tabX + tw + 1, tabY + 12, 0.4f, 0.6f, 1.0f, 1.0f);
					tabX += tw + TAB_GAP;
				}
			}

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

				int tabX = tabStartX(fr);
				int tabY = (TOP_BAR_HEIGHT - 8) / 2;
				for (int i = 0; i < DIM_NAMES.length; i++) {
					fr.drawStringWithShadow(DIM_NAMES[i], tabX, tabY, viewDimension == DIM_IDS[i] ? 0xFFFFFF : 0x888888);
					tabX += fr.getStringWidth(DIM_NAMES[i]) + TAB_GAP;
				}

				drawBottomBar(fr, storage, player, mx, my, mapBottom, complete);

				if (!menuOpen) {
					if (hovered != null) {
						drawLabel(t, fr, hovered.name + "  \u00a77" + hovered.x + ", " + hovered.y + ", " + hovered.z,
								toScreenX(hovered.x + 0.5), toScreenY(hovered.z + 0.5), hovered.color);
					} else if (hoveredSpawn) {
						drawLabel(t, fr, "Spawn  \u00a77" + spawnX() + ", " + spawnZ(), toScreenX(spawnX() + 0.5),
								toScreenY(spawnZ() + 0.5), 0x5599FF);
					}
				} else {
					drawMenu(t, fr, mx, my);
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

	private boolean onScreen(double sx, double sy) {
		return sx >= -8 && sx <= this.width + 8 && sy >= TOP_BAR_HEIGHT - 8
				&& sy <= this.height - BOTTOM_BAR_HEIGHT + 8;
	}

	private int tabStartX(FontRenderer fr) {
		int total = TAB_GAP * (DIM_NAMES.length - 1);
		for (String name : DIM_NAMES)
			total += fr.getStringWidth(name);
		return (this.width - total) / 2;
	}

	private void drawBottomBar(FontRenderer fr, MapStorage storage, EntityPlayer player, double mx, double my,
			int mapBottom, boolean complete) {
		int textY = mapBottom + 5;

		String left;
		if (inMapArea(mx, my)) {
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

	/** Small name tag above a marker. Leaves texturing enabled. */
	private void drawLabel(Tessellator t, FontRenderer fr, String text, double sx, double sy, int rgb) {
		int textW = fr.getStringWidth(text);
		int x = (int) Math.round(sx) - textW / 2;
		int y = (int) Math.round(sy) - 17;
		x = Math.max(3, Math.min(x, this.width - textW - 3));
		if (y < TOP_BAR_HEIGHT + 2)
			y = (int) Math.round(sy) + 8;
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		fill(t, x - 3, y - 2, x + textW + 3, y + 10, 0.0f, 0.0f, 0.0f, 0.72f);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		fr.drawStringWithShadow(text, x, y, rgb & 0xFFFFFF);
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

	private static final int MENU_ROW = 13;

	private void openMenu(int mouseX, int mouseY) {
		menuLabels.clear();
		menuActions.clear();
		menuWorldX = (int) Math.floor(toWorldX(mouseX));
		menuWorldZ = (int) Math.floor(toWorldZ(mouseY));
		menuWaypoint = waypointNear(mouseX, mouseY);
		boolean canTeleport = MapConfig.instance.showTeleportButton && viewingPlayerDimension();

		if (menuWaypoint != null) {
			addMenuItem("Edit \"" + menuWaypoint.name + "\"", ACTION_EDIT);
			addMenuItem(menuWaypoint.enabled ? "Hide" : "Show", ACTION_TOGGLE);
			if (canTeleport)
				addMenuItem("Teleport to waypoint", ACTION_TELEPORT_WAYPOINT);
			addMenuItem("\u00a7cDelete", ACTION_DELETE);
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
		menuW += 12;
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

	private void drawMenu(Tessellator t, FontRenderer fr, double mx, double my) {
		int menuH = menuLabels.size() * MENU_ROW + 4;
		int hover = menuIndexAt(mx, my);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		fill(t, menuX - 1, menuY - 1, menuX + menuW + 1, menuY + menuH + 1, 0.35f, 0.35f, 0.42f, 1.0f);
		fill(t, menuX, menuY, menuX + menuW, menuY + menuH, 0.06f, 0.06f, 0.09f, 0.97f);
		if (hover >= 0)
			fill(t, menuX + 1, menuY + 2 + hover * MENU_ROW, menuX + menuW - 1, menuY + 2 + (hover + 1) * MENU_ROW,
					0.25f, 0.40f, 0.75f, 0.85f);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		for (int i = 0; i < menuLabels.size(); i++)
			fr.drawStringWithShadow(menuLabels.get(i), menuX + 6, menuY + 4 + i * MENU_ROW, 0xFFFFFF);
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
		String cmd = MapConfig.instance.teleportCommand
				.replace("{x}", String.valueOf(x))
				.replace("{y}", String.valueOf(y))
				.replace("{z}", String.valueOf(z))
				.replace("{dim}", String.valueOf(viewDimension))
				.replace("{name}", name == null ? "" : name);
		Minecraft minecraft = this.mc;
		minecraft.displayGuiScreen(null);
		if (minecraft.thePlayer != null)
			minecraft.thePlayer.sendChatMessage(cmd);
	}

	private static int nextColor(int index) {
		int[] colors = { 0xFF5555, 0x55FF55, 0x5555FF, 0xFFFF55, 0xFF55FF, 0x55FFFF, 0xFF8800, 0xAA55FF };
		return colors[index % colors.length];
	}

	// ------------------------------------------------------------------- input

	private void zoom(int steps, double mouseX, double mouseY) {
		// Half-octave steps that always land exactly on 1x, 2x, 4x...
		double level = Math.rint(Math.log(targetScale) / Math.log(2.0) * 2.0) + steps;
		double next = Math.pow(2.0, level / 2.0);
		next = Math.max(MapConfig.MIN_FULLSCREEN_SCALE, Math.min(MapConfig.MAX_FULLSCREEN_SCALE, next));
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

		if (mouseY < TOP_BAR_HEIGHT) {
			if (mouseButton == 0 && this.fontRenderer != null) {
				int tabX = tabStartX(this.fontRenderer);
				for (int i = 0; i < DIM_NAMES.length; i++) {
					int tw = this.fontRenderer.getStringWidth(DIM_NAMES[i]);
					if (mouseX >= tabX - 4 && mouseX < tabX + tw + 4) {
						switchDimension(DIM_IDS[i]);
						return;
					}
					tabX += tw + TAB_GAP;
				}
			}
			return;
		}
		if (!inMapArea(mouseX, mouseY))
			return;

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
