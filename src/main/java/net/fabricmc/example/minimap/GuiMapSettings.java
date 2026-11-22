package net.fabricmc.example.minimap;

import net.fabricmc.example.BTWNavigator;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.ScaledResolution;
import net.minecraft.src.Tessellator;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;

/**
 * In-game settings for Minimap CE, reachable from the fullscreen map, the
 * waypoint list, or the settings key (O).
 *
 * One card with three tabs. The real minimap is drawn live behind it, so every
 * change shows straight away; the card moves aside so it does not cover the
 * minimap when there is room. Hovering an option explains it.
 */
public class GuiMapSettings extends GuiScreen {
	private static final int PAGE_MINIMAP = 0;
	private static final int PAGE_MARKERS = 1;
	private static final int PAGE_WORLD_MAP = 2;
	private static final String[] PAGE_NAMES = { "Minimap", "Markers & Info", "World Map" };

	private static final int BTN_MINIMAP_ENABLED = 0;
	private static final int BTN_POSITION = 1;
	private static final int BTN_SHAPE = 2;
	private static final int BTN_ROTATE = 3;
	private static final int BTN_SIZE = 4;
	private static final int BTN_ZOOM = 5;
	private static final int BTN_OPACITY = 6;
	private static final int BTN_PADDING = 7;
	private static final int BTN_ENTITIES = 8;
	private static final int BTN_CHUNK_GRID = 9;
	private static final int BTN_COMPASS = 10;
	private static final int BTN_CLOCK = 11;
	private static final int BTN_COORDINATES = 12;
	private static final int BTN_BIOME = 13;
	private static final int BTN_WAYPOINTS = 14;
	private static final int BTN_TELEPORT = 15;
	private static final int BTN_PAUSE_GAME = 16;
	private static final int BTN_ENTITY_ICONS = 17;
	private static final int BTN_WAYPOINT_ICONS = 18;
	private static final int BTN_CAVE = 19;
	private static final int BTN_SHARP = 20;
	private static final int BTN_WORLD_LABELS = 21;
	private static final int BTN_DONE = 99;
	private static final int BTN_TAB_FIRST = 200;

	private static final int SIZE_STEP = 8;
	private static final float MIN_OPACITY = 0.25f;

	private static final int COLUMN_W = 150;
	private static final int PAD = 8;
	private static final int PANEL_W = COLUMN_W * 2 + PAD * 3;
	private static final int ROW_H = 20;
	private static final int TAB_H = 18;
	private static final int HELP_H = 24;

	/** Which tab was open last, for the rest of the game session. */
	private static int lastPage = PAGE_MINIMAP;

	private final GuiScreen parent;
	private final HashMap<Integer, String> help = new HashMap<Integer, String>();
	private int page = lastPage;
	private int guiScale = 1;
	private int panelX, panelY, panelH, rowsBottom;
	private boolean relayoutPending;

	public GuiMapSettings(GuiScreen parent) {
		this.parent = parent;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void initGui() {
		final MapConfig cfg = MapConfig.instance;
		guiScale = Math.max(1, new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight).getScaleFactor());
		help.clear();
		relayoutPending = false;

		boolean roomy = this.height >= 270;
		int rowStep = roomy ? ROW_H + 4 : ROW_H + 2;
		panelH = PAD + TAB_H + PAD + 5 * rowStep - (rowStep - ROW_H) + PAD + HELP_H + PAD;
		panelY = Math.max(16, (this.height - panelH - 26 + 10) / 2);
		panelX = choosePanelX();

		int tabW = (PANEL_W - PAD * 2 - 4) / 3;
		for (int i = 0; i < PAGE_NAMES.length; i++) {
			this.buttonList.add(new FlatButton(BTN_TAB_FIRST + i, panelX + PAD + i * (tabW + 2), panelY + PAD, tabW,
					TAB_H, PAGE_NAMES[i]).style(MapUi.STYLE_TAB).selected(i == page));
		}

		int left = panelX + PAD, right = panelX + PAD * 2 + COLUMN_W;
		int y = panelY + PAD + TAB_H + PAD + 2;

		if (page == PAGE_MINIMAP) {
			add(BTN_MINIMAP_ENABLED, left, y, "Show or hide the minimap. Mapping carries on either way.");
			add(BTN_POSITION, right, y, "Which corner of the screen the minimap sits in.");
			y += rowStep;
			add(BTN_SHAPE, left, y, "Round or square minimap.");
			add(BTN_ROTATE, right, y,
					"North Up keeps north at the top. Follows You turns the map so the way you face is up.");
			y += rowStep;
			this.buttonList.add(new MapSlider(BTN_SIZE, left, y, COLUMN_W, ROW_H) {
				@Override
				protected float position() {
					return (cfg.minimapSize - 64) / (float) (MapConfig.MAX_SIZE - 64);
				}

				@Override
				protected void apply(float p) {
					int size = 64 + Math.round(p * (MapConfig.MAX_SIZE - 64) / SIZE_STEP) * SIZE_STEP;
					cfg.minimapSize = Math.max(64, Math.min(MapConfig.MAX_SIZE, size));
				}

				@Override
				protected String name() {
					return "Size";
				}

				@Override
				protected String valueText() {
					return String.valueOf(cfg.minimapSize);
				}

				@Override
				protected void released() {
					cfg.saveConfig();
					relayoutPending = true; // a bigger minimap may now be in the card's way
				}
			});
			help.put(BTN_SIZE, "Minimap size. The text around it shrinks with small maps.");
			this.buttonList.add(new MapSlider(BTN_ZOOM, right, y, COLUMN_W, ROW_H) {
				@Override
				protected float position() {
					// Zoomed out on the left, zoomed in on the right.
					return (MapConfig.MAX_ZOOM - cfg.zoomLevel) / (float) (MapConfig.MAX_ZOOM - MapConfig.MIN_ZOOM);
				}

				@Override
				protected void apply(float p) {
					int steps = MapConfig.MAX_ZOOM - MapConfig.MIN_ZOOM;
					cfg.zoomLevel = MapConfig.MAX_ZOOM - Math.round(p * steps);
				}

				@Override
				protected String name() {
					return "Zoom";
				}

				@Override
				protected String valueText() {
					return zoomLabel(cfg.zoomLevel);
				}

				@Override
				protected void released() {
					cfg.saveConfig();
				}
			});
			help.put(BTN_ZOOM, "How much ground the minimap shows. Also on the zoom keys (= and - by default).");
			y += rowStep;
			this.buttonList.add(new MapSlider(BTN_OPACITY, left, y, COLUMN_W, ROW_H) {
				@Override
				protected float position() {
					return (cfg.mapOpacity - MIN_OPACITY) / (1.0f - MIN_OPACITY);
				}

				@Override
				protected void apply(float p) {
					float value = MIN_OPACITY + p * (1.0f - MIN_OPACITY);
					cfg.mapOpacity = Math.round(value * 20.0f) / 20.0f; // 5% steps
				}

				@Override
				protected String name() {
					return "Opacity";
				}

				@Override
				protected String valueText() {
					return Math.round(cfg.mapOpacity * 100) + "%";
				}

				@Override
				protected void released() {
					cfg.saveConfig();
				}
			});
			help.put(BTN_OPACITY, "Lower values let the world show through the minimap.");
			add(BTN_PADDING, right, y, "Extra space between the minimap and the screen edge.");
			y += rowStep;
			add(BTN_CAVE, left, y,
					"When there is rock above you, the minimap shows the caves at your level instead of the surface.");
		} else if (page == PAGE_MARKERS) {
			add(BTN_ENTITIES, left, y, "Show nearby mobs and players on the minimap.");
			add(BTN_ENTITY_ICONS, right, y, "Dots + Tab Faces: small dots, and each mob's face while you hold the "
					+ "player list key (Tab). Or always faces, arrows (which way a mob looks) or dots.");
			y += rowStep;
			add(BTN_WAYPOINTS, left, y, "Show waypoints on the maps and as beams in the world.");
			add(BTN_WAYPOINT_ICONS, right, y,
					"Letters: a coloured tile with the waypoint's initial. Squares: the small classic marker.");
			y += rowStep;
			add(BTN_CHUNK_GRID, left, y, "Draw chunk borders (every 16 blocks) on the maps.");
			add(BTN_COMPASS, right, y, "N, E, S and W around the minimap.");
			y += rowStep;
			add(BTN_CLOCK, left, y, "Day counter and time above the minimap. The number in brackets counts the days to the next gloom (new moon) night.");
			add(BTN_COORDINATES, right, y, "Your block position (x, z, y) under the minimap.");
			y += rowStep;
			add(BTN_BIOME, left, y, "Name of the biome you are standing in, with (Caves) while the cave view is on.");
			add(BTN_WORLD_LABELS, right, y, "Waypoints in the world: a beam and a diamond, with the name and distance "
					+ "while you look towards it (On Look) or all the time (Always). Beams Only hides diamonds and labels.");
		} else {
			add(BTN_PAUSE_GAME, left, y,
					"Whether the fullscreen map pauses a singleplayer game. Turn off to watch it while travelling.");
			add(BTN_TELEPORT, right, y,
					"Show teleport options in the waypoint list and the map's right-click menu (needs cheats or op).");
			y += rowStep;
			add(BTN_SHARP, left, y, "Sharp keeps map blocks crisp at in-between zoom levels and when rotating, "
					+ "with clean edges. Nearest is the plain fallback if your graphics driver has trouble with it.");
		}
		rowsBottom = y + ROW_H;

		this.buttonList.add(new FlatButton(BTN_DONE, panelX + (PANEL_W - 120) / 2, panelY + panelH + 6, 120, 20,
				"Done").style(MapUi.STYLE_PRIMARY));
	}

	/** Centre the card, unless it would cover the live minimap and there is room beside it. */
	private int choosePanelX() {
		int centered = (this.width - PANEL_W) / 2;
		MapConfig cfg = MapConfig.instance;
		if (this.mc.theWorld == null || !cfg.minimapEnabled || page == PAGE_WORLD_MAP)
			return centered;
		int mapW = cfg.minimapSize + 24;
		boolean mapOnRight = cfg.minimapPosition == 1 || cfg.minimapPosition == 3;
		boolean overlap = mapOnRight ? centered + PANEL_W > this.width - mapW : centered < mapW;
		int free = this.width - mapW;
		if (!overlap || free < PANEL_W + 8)
			return centered;
		int inFree = (free - PANEL_W) / 2;
		return mapOnRight ? inFree : mapW + inFree;
	}

	@SuppressWarnings("unchecked")
	private void add(int id, int x, int y, String description) {
		this.buttonList.add(new FlatButton(id, x, y, COLUMN_W, ROW_H, name(id)).value(value(id)));
		help.put(id, description);
	}

	private static String name(int id) {
		switch (id) {
			case BTN_MINIMAP_ENABLED:
				return "Minimap";
			case BTN_POSITION:
				return "Position";
			case BTN_SHAPE:
				return "Shape";
			case BTN_ROTATE:
				return "Rotation";
			case BTN_PADDING:
				return "Padding";
			case BTN_CAVE:
				return "Cave Mode";
			case BTN_ENTITIES:
				return "Entity Radar";
			case BTN_ENTITY_ICONS:
				return "Mobs";
			case BTN_WAYPOINTS:
				return "Waypoints";
			case BTN_WAYPOINT_ICONS:
				return "Waypoint Icons";
			case BTN_CHUNK_GRID:
				return "Chunk Grid";
			case BTN_COMPASS:
				return "Compass";
			case BTN_CLOCK:
				return "Clock";
			case BTN_COORDINATES:
				return "Coordinates";
			case BTN_BIOME:
				return "Biome";
			case BTN_TELEPORT:
				return "Teleport";
			case BTN_PAUSE_GAME:
				return "Map Pauses Game";
			case BTN_SHARP:
				return "Map Pixels";
			case BTN_WORLD_LABELS:
				return "In World";
			default:
				return "";
		}
	}

	private static String value(int id) {
		MapConfig cfg = MapConfig.instance;
		switch (id) {
			case BTN_MINIMAP_ENABLED:
				return onOff(cfg.minimapEnabled);
			case BTN_POSITION: {
				String[] positions = { "Top Left", "Top Right", "Bottom Left", "Bottom Right" };
				return positions[Math.max(0, Math.min(3, cfg.minimapPosition))];
			}
			case BTN_SHAPE:
				return cfg.minimapShape == MapConfig.SHAPE_CIRCLE ? "Circle" : "Square";
			case BTN_ROTATE:
				return cfg.rotateMinimap ? "Follows You" : "North Up";
			case BTN_PADDING:
				return onOff(cfg.useMinimapPadding);
			case BTN_CAVE:
				return cfg.caveMode ? "\u00a7aAuto" : "\u00a7cOff";
			case BTN_ENTITIES:
				return onOff(cfg.showEntities);
			case BTN_ENTITY_ICONS: {
				String[] styles = { "Faces", "Arrows", "Dots", "Dots + Tab Faces" };
				return styles[Math.max(0, Math.min(3, cfg.entityIconStyle))];
			}
			case BTN_WAYPOINTS:
				return onOff(cfg.showWaypoints);
			case BTN_WAYPOINT_ICONS:
				return cfg.waypointIconStyle == MapConfig.WAYPOINT_LETTERS ? "Letters" : "Squares";
			case BTN_CHUNK_GRID:
				return onOff(cfg.showChunkGrid);
			case BTN_COMPASS:
				return onOff(cfg.showCompass);
			case BTN_CLOCK:
				return onOff(cfg.showClock);
			case BTN_COORDINATES:
				return onOff(cfg.showCoordinates);
			case BTN_BIOME:
				return onOff(cfg.showBiome);
			case BTN_TELEPORT:
				return onOff(cfg.showTeleportButton);
			case BTN_PAUSE_GAME:
				return onOff(cfg.fullscreenMapPausesGame);
			case BTN_SHARP:
				return cfg.sharpScaling ? "Sharp" : "Nearest";
			case BTN_WORLD_LABELS: {
				String[] styles = { "On Look", "Always", "Beams Only" };
				return styles[Math.max(0, Math.min(2, cfg.worldWaypointLabels))];
			}
			default:
				return "";
		}
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (!button.enabled || button instanceof MapSlider)
			return; // sliders apply themselves while being dragged
		MapConfig cfg = MapConfig.instance;

		if (button.id >= BTN_TAB_FIRST && button.id < BTN_TAB_FIRST + PAGE_NAMES.length) {
			page = lastPage = button.id - BTN_TAB_FIRST;
			rebuild();
			return;
		}

		// Shift-click steps backwards through options with more than two values.
		boolean back = isShiftKeyDown();
		boolean relayout = false;
		switch (button.id) {
			case BTN_MINIMAP_ENABLED:
				cfg.minimapEnabled = !cfg.minimapEnabled;
				relayout = true;
				break;
			case BTN_POSITION:
				cfg.minimapPosition = (cfg.minimapPosition + (back ? 3 : 1)) % 4;
				relayout = true;
				break;
			case BTN_SHAPE:
				cfg.minimapShape = cfg.minimapShape == MapConfig.SHAPE_CIRCLE ? MapConfig.SHAPE_SQUARE
						: MapConfig.SHAPE_CIRCLE;
				break;
			case BTN_ROTATE:
				cfg.rotateMinimap = !cfg.rotateMinimap;
				break;
			case BTN_PADDING:
				cfg.useMinimapPadding = !cfg.useMinimapPadding;
				break;
			case BTN_CAVE:
				cfg.caveMode = !cfg.caveMode;
				break;
			case BTN_ENTITIES:
				cfg.showEntities = !cfg.showEntities;
				break;
			case BTN_ENTITY_ICONS: {
				// Order on screen: Dots + Tab Faces, Faces, Arrows, Dots.
				int[] order = { MapConfig.ENTITY_DOTS_FACES_ON_TAB, MapConfig.ENTITY_HEADS, MapConfig.ENTITY_ARROWS,
						MapConfig.ENTITY_DOTS };
				int index = 0;
				for (int i = 0; i < order.length; i++)
					if (order[i] == cfg.entityIconStyle)
						index = i;
				cfg.entityIconStyle = order[(index + (back ? order.length - 1 : 1)) % order.length];
				break;
			}
			case BTN_WAYPOINTS:
				cfg.showWaypoints = !cfg.showWaypoints;
				break;
			case BTN_WAYPOINT_ICONS:
				cfg.waypointIconStyle = cfg.waypointIconStyle == MapConfig.WAYPOINT_LETTERS ? MapConfig.WAYPOINT_SQUARES
						: MapConfig.WAYPOINT_LETTERS;
				break;
			case BTN_CHUNK_GRID:
				cfg.showChunkGrid = !cfg.showChunkGrid;
				break;
			case BTN_COMPASS:
				cfg.showCompass = !cfg.showCompass;
				break;
			case BTN_CLOCK:
				cfg.showClock = !cfg.showClock;
				break;
			case BTN_COORDINATES:
				cfg.showCoordinates = !cfg.showCoordinates;
				break;
			case BTN_BIOME:
				cfg.showBiome = !cfg.showBiome;
				break;
			case BTN_TELEPORT:
				cfg.showTeleportButton = !cfg.showTeleportButton;
				break;
			case BTN_PAUSE_GAME:
				cfg.fullscreenMapPausesGame = !cfg.fullscreenMapPausesGame;
				break;
			case BTN_SHARP:
				cfg.sharpScaling = !cfg.sharpScaling;
				break;
			case BTN_WORLD_LABELS:
				cfg.worldWaypointLabels = (cfg.worldWaypointLabels + (back ? 2 : 1)) % 3;
				break;
			case BTN_DONE:
				close();
				return;
			default:
				return;
		}
		cfg.saveConfig();
		if (relayout)
			rebuild();
		else if (button instanceof FlatButton)
			((FlatButton) button).value = value(button.id);
	}

	private void rebuild() {
		this.buttonList.clear();
		this.initGui();
	}

	private void close() {
		MapConfig.instance.saveConfig();
		this.mc.displayGuiScreen(parent);
	}

	@Override
	protected void keyTyped(char c, int keyCode) {
		if (keyCode == Keyboard.KEY_ESCAPE || keyCode == MapConfig.settingsKey.keyCode)
			close();
	}

	@Override
	public void onGuiClosed() {
		MapConfig.instance.saveConfig();
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		// After the size slider is let go, move the card if the minimap grew into its way.
		if (relayoutPending && (!org.lwjgl.input.Mouse.isCreated() || !org.lwjgl.input.Mouse.isButtonDown(0)))
			rebuild();
		boolean inWorld = this.mc.theWorld != null && this.mc.thePlayer != null;
		if (inWorld) {
			// Lighter than the usual menu backdrop, so the world and the live minimap stay readable.
			this.drawGradientRect(0, 0, this.width, this.height, 0x80101010, 0x98101010);
			if (page != PAGE_WORLD_MAP)
				drawLiveMinimap(partialTicks);
		} else {
			this.drawDefaultBackground();
		}

		MapUi.panel(panelX, panelY, panelX + PANEL_W, panelY + panelH);
		String title = "\u00a7lMap Settings";
		this.fontRenderer.drawStringWithShadow(title, panelX + (PANEL_W - this.fontRenderer.getStringWidth(title)) / 2,
				panelY - 12, 0xFFFFFF);
		MapUi.rect(panelX + PAD, panelY + PAD + TAB_H + 3, panelX + PANEL_W - PAD, panelY + PAD + TAB_H + 4,
				MapUi.BORDER);

		if (page == PAGE_WORLD_MAP) {
			int y = rowsBottom + 12;
			int x = panelX + PAD + 2;
			this.fontRenderer.drawStringWithShadow("Teleport command", x, y, MapUi.TEXT_DIM);
			String cmd = MapUi.fit(this.fontRenderer, MapConfig.instance.teleportCommand, PANEL_W - PAD * 2 - 104);
			this.fontRenderer.drawStringWithShadow(cmd, x + 100, y, 0xFFFFFF);
			this.fontRenderer.drawStringWithShadow("Change it in config/minimap-ce.conf", x, y + 12, MapUi.TEXT_MUTED);
			this.fontRenderer.drawStringWithShadow("Keys: Options > Controls, all start with [Minimap]", x, y + 24,
					MapUi.TEXT_MUTED);
		}

		super.drawScreen(mouseX, mouseY, partialTicks);

		// Explain whatever the mouse is over, in the strip at the bottom of the card.
		String text = null;
		for (Object o : this.buttonList) {
			GuiButton b = (GuiButton) o;
			if (b.drawButton && mouseX >= b.xPosition && mouseY >= b.yPosition && mouseX < b.xPosition + b.width
					&& mouseY < b.yPosition + b.height) {
				text = help.get(b.id);
				break;
			}
		}
		if (text == null && page != PAGE_WORLD_MAP)
			text = "Hover an option to see what it does. Changes show on the minimap straight away; shift-click "
					+ "steps backwards.";
		int stripTop = panelY + panelH - PAD - HELP_H;
		MapUi.rect(panelX + PAD, stripTop - 4, panelX + PANEL_W - PAD, stripTop - 3, MapUi.BORDER);
		if (text != null)
			this.fontRenderer.drawSplitString(text, panelX + PAD + 2, stripTop + 2, PANEL_W - PAD * 2 - 4,
					MapUi.TEXT_DIM & 0xFFFFFF);
	}

	/** Draws the real minimap over the dimmed backdrop as a live preview. */
	private void drawLiveMinimap(float partialTicks) {
		int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		try {
			BTWNavigator.minimapRenderer.renderMinimap(this.mc, this.mc.theWorld, this.mc.thePlayer, this.width,
					this.height, guiScale, partialTicks);
		} catch (Throwable t) {
			try {
				Tessellator.instance.draw();
			} catch (Throwable ignored) {
			}
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
			GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
		}
	}

	private static String onOff(boolean value) {
		return value ? "\u00a7aON" : "\u00a7cOFF";
	}

	private static String zoomLabel(int zoomLevel) {
		if (zoomLevel <= 0)
			return (1 << -zoomLevel) + "x";
		return "1/" + (1 << zoomLevel) + "x";
	}

	@Override
	public boolean doesGuiPauseGame() {
		return true;
	}
}
