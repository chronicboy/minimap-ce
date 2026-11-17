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
 * Three tabs keep each page short enough for the smallest window. The minimap
 * is drawn live on top of the screen, so every change is visible immediately,
 * and hovering an option explains it at the bottom.
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
	private static final int BTN_DONE = 99;
	private static final int BTN_TAB_FIRST = 200;

	private static final int SIZE_STEP = 8;
	private static final float MIN_OPACITY = 0.25f;

	/** Which tab was open last, for the rest of the game session. */
	private static int lastPage = PAGE_MINIMAP;

	private final GuiScreen parent;
	private final HashMap<Integer, String> help = new HashMap<Integer, String>();
	private int page = lastPage;
	private int guiScale = 1;

	public GuiMapSettings(GuiScreen parent) {
		this.parent = parent;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void initGui() {
		final MapConfig cfg = MapConfig.instance;
		guiScale = Math.max(1, new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight).getScaleFactor());
		help.clear();

		int centerX = this.width / 2;
		boolean roomy = this.height >= 270;

		// Tabs: the open one is shown as a disabled (dark) button.
		int tabW = 100, tabGap = 4, tabY = roomy ? 26 : 18;
		int tabX = centerX - (tabW * 3 + tabGap * 2) / 2;
		for (int i = 0; i < PAGE_NAMES.length; i++) {
			GuiButton tab = new GuiButton(BTN_TAB_FIRST + i, tabX + i * (tabW + tabGap), tabY, tabW, 20, PAGE_NAMES[i]);
			tab.enabled = i != page;
			this.buttonList.add(tab);
		}

		int w = 150, h = 20;
		int left = centerX - w - 5, right = centerX + 5;
		int gap = roomy ? 24 : 22;
		int y = tabY + (roomy ? 32 : 26);

		if (page == PAGE_MINIMAP) {
			add(BTN_MINIMAP_ENABLED, left, y, w, h, "Show or hide the minimap. Mapping carries on either way.");
			add(BTN_POSITION, right, y, w, h, "Which corner of the screen the minimap sits in.");
			y += gap;
			add(BTN_SHAPE, left, y, w, h, "Round or square minimap.");
			add(BTN_ROTATE, right, y, w, h,
					"North Up keeps north at the top. Follows Player turns the map so the way you face is up.");
			y += gap;
			this.buttonList.add(new MapSlider(BTN_SIZE, left, y, w, h) {
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
				protected String label() {
					return "Size: " + cfg.minimapSize;
				}

				@Override
				protected void released() {
					cfg.saveConfig();
				}
			});
			help.put(BTN_SIZE, "Minimap size in GUI pixels. The info text shrinks with small maps.");
			this.buttonList.add(new MapSlider(BTN_ZOOM, right, y, w, h) {
				@Override
				protected float position() {
					// Slider runs from zoomed out (left) to zoomed in (right).
					return (MapConfig.MAX_ZOOM - cfg.zoomLevel) / (float) (MapConfig.MAX_ZOOM - MapConfig.MIN_ZOOM);
				}

				@Override
				protected void apply(float p) {
					int steps = MapConfig.MAX_ZOOM - MapConfig.MIN_ZOOM;
					cfg.zoomLevel = MapConfig.MAX_ZOOM - Math.round(p * steps);
				}

				@Override
				protected String label() {
					return "Zoom: " + zoomLabel(cfg.zoomLevel);
				}

				@Override
				protected void released() {
					cfg.saveConfig();
				}
			});
			help.put(BTN_ZOOM, "How much ground the minimap shows. Also on the zoom keys (= and - by default).");
			y += gap;
			this.buttonList.add(new MapSlider(BTN_OPACITY, left, y, w, h) {
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
				protected String label() {
					return "Opacity: " + Math.round(cfg.mapOpacity * 100) + "%";
				}

				@Override
				protected void released() {
					cfg.saveConfig();
				}
			});
			help.put(BTN_OPACITY, "Lower values let the world show through the minimap.");
			add(BTN_PADDING, right, y, w, h, "Extra space between the minimap and the screen edge.");
			y += gap;
			add(BTN_CAVE, left, y, w, h,
					"When there is rock above you, the minimap shows the caves at your level instead of the surface.");
		} else if (page == PAGE_MARKERS) {
			add(BTN_ENTITIES, left, y, w, h, "Show nearby mobs and players on the minimap.");
			add(BTN_ENTITY_ICONS, right, y, w, h,
					"Heads shows each mob's face. Arrows shows which way it is looking. Dots is the plainest.");
			y += gap;
			add(BTN_WAYPOINTS, left, y, w, h, "Show waypoints on the maps and as beams in the world.");
			add(BTN_WAYPOINT_ICONS, right, y, w, h,
					"Letters shows a coloured tile with the waypoint's initial. Squares is the small classic marker.");
			y += gap;
			add(BTN_CHUNK_GRID, left, y, w, h, "Draw chunk borders (every 16 blocks) on the maps.");
			add(BTN_COMPASS, right, y, w, h, "N, E, S and W around the minimap.");
			y += gap;
			add(BTN_CLOCK, left, y, w, h, "Day counter and time of day above the minimap.");
			add(BTN_COORDINATES, right, y, w, h, "Your block position (x, z, y) under the minimap.");
			y += gap;
			add(BTN_BIOME, left, y, w, h, "Name of the biome you are standing in.");
		} else {
			add(BTN_PAUSE_GAME, left, y, w, h,
					"Whether opening the fullscreen map pauses a singleplayer game. Turn off to watch it while travelling.");
			add(BTN_TELEPORT, right, y, w, h,
					"Show teleport options in the waypoint list and the map's right-click menu (needs cheats or op).");
			y += gap;
			add(BTN_SHARP, left, y, w, h,
					"Sharp keeps map blocks crisp at in-between zoom levels and when rotating, with clean edges. "
							+ "Nearest is the plain fallback if your graphics driver has trouble with it.");
		}

		this.buttonList.add(new GuiButton(BTN_DONE, centerX - 100, this.height - 26, 200, 20, "Done"));
	}

	@SuppressWarnings("unchecked")
	private void add(int id, int x, int y, int w, int h, String description) {
		this.buttonList.add(new GuiButton(id, x, y, w, h, label(id)));
		help.put(id, description);
	}

	private static String label(int id) {
		MapConfig cfg = MapConfig.instance;
		switch (id) {
			case BTN_MINIMAP_ENABLED:
				return toggle("Minimap", cfg.minimapEnabled);
			case BTN_POSITION: {
				String[] positions = { "Top-Left", "Top-Right", "Bottom-Left", "Bottom-Right" };
				return "Position: " + positions[Math.max(0, Math.min(3, cfg.minimapPosition))];
			}
			case BTN_SHAPE:
				return "Shape: " + (cfg.minimapShape == MapConfig.SHAPE_CIRCLE ? "Circle" : "Square");
			case BTN_ROTATE:
				return "Rotation: " + (cfg.rotateMinimap ? "Follows Player" : "North Up");
			case BTN_PADDING:
				return toggle("Padding", cfg.useMinimapPadding);
			case BTN_ENTITIES:
				return toggle("Entity Radar", cfg.showEntities);
			case BTN_ENTITY_ICONS: {
				String[] styles = { "Heads", "Arrows", "Dots" };
				return "Entity Icons: " + styles[Math.max(0, Math.min(2, cfg.entityIconStyle))];
			}
			case BTN_WAYPOINTS:
				return toggle("Waypoints", cfg.showWaypoints);
			case BTN_WAYPOINT_ICONS:
				return "Waypoint Icons: " + (cfg.waypointIconStyle == MapConfig.WAYPOINT_LETTERS ? "Letters" : "Squares");
			case BTN_CHUNK_GRID:
				return toggle("Chunk Grid", cfg.showChunkGrid);
			case BTN_COMPASS:
				return toggle("Compass", cfg.showCompass);
			case BTN_CLOCK:
				return toggle("Clock", cfg.showClock);
			case BTN_COORDINATES:
				return toggle("Coordinates", cfg.showCoordinates);
			case BTN_BIOME:
				return toggle("Biome", cfg.showBiome);
			case BTN_TELEPORT:
				return toggle("Teleport Options", cfg.showTeleportButton);
			case BTN_CAVE:
				return "Cave Mode: " + (cfg.caveMode ? "\u00a7aAuto" : "\u00a7cOff");
			case BTN_SHARP:
				return "Map Pixels: " + (cfg.sharpScaling ? "Sharp" : "Nearest");
			case BTN_PAUSE_GAME:
				return toggle("Map Pauses Game", cfg.fullscreenMapPausesGame);
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
			this.buttonList.clear();
			this.initGui();
			return;
		}

		// Hold shift to step backwards through options with more than two values.
		boolean back = isShiftKeyDown();
		switch (button.id) {
			case BTN_MINIMAP_ENABLED:
				cfg.minimapEnabled = !cfg.minimapEnabled;
				break;
			case BTN_POSITION:
				cfg.minimapPosition = (cfg.minimapPosition + (back ? 3 : 1)) % 4;
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
			case BTN_ENTITIES:
				cfg.showEntities = !cfg.showEntities;
				break;
			case BTN_ENTITY_ICONS:
				cfg.entityIconStyle = (cfg.entityIconStyle + (back ? 2 : 1)) % 3;
				break;
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
			case BTN_CAVE:
				cfg.caveMode = !cfg.caveMode;
				break;
			case BTN_SHARP:
				cfg.sharpScaling = !cfg.sharpScaling;
				break;
			case BTN_PAUSE_GAME:
				cfg.fullscreenMapPausesGame = !cfg.fullscreenMapPausesGame;
				break;
			case BTN_DONE:
				close();
				return;
			default:
				return;
		}
		button.displayString = label(button.id);
		cfg.saveConfig();
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
		boolean inWorld = this.mc.theWorld != null && this.mc.thePlayer != null;
		if (inWorld) {
			// Lighter than the usual menu backdrop, so the world and the live minimap stay readable.
			this.drawGradientRect(0, 0, this.width, this.height, 0x8C101010, 0xA0101010);
			if (page != PAGE_WORLD_MAP)
				drawLiveMinimap(partialTicks);
		} else {
			this.drawDefaultBackground();
		}

		boolean roomy = this.height >= 270;
		this.drawCenteredString(this.fontRenderer, "\u00a7lMap Settings", this.width / 2, roomy ? 10 : 5, 0xFFFFFF);

		if (page == PAGE_WORLD_MAP) {
			int y = (roomy ? 26 : 18) + (roomy ? 32 : 26) + (roomy ? 24 : 22) + 30;
			this.drawCenteredString(this.fontRenderer, "\u00a77Teleport command: \u00a7f" + MapConfig.instance.teleportCommand,
					this.width / 2, y, 0xFFFFFF);
			this.drawCenteredString(this.fontRenderer, "\u00a78Change it in config/minimap-ce.conf. {x} {y} {z} {dim} {name} are filled in.",
					this.width / 2, y + 12, 0xFFFFFF);
			this.drawCenteredString(this.fontRenderer, "\u00a78Keys are set under Options > Controls, all starting with [Minimap].",
					this.width / 2, y + 30, 0xFFFFFF);
		}

		super.drawScreen(mouseX, mouseY, partialTicks);

		// Explain whatever the mouse is over.
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
			text = "\u00a78Changes show on the minimap straight away. Shift-click steps backwards.";
		if (text != null) {
			int boxW = Math.min(this.width - 20, 320);
			int lines = this.fontRenderer.listFormattedStringToWidth(text, boxW).size();
			int top = this.height - 32 - lines * 9;
			this.fontRenderer.drawSplitString(text, (this.width - boxW) / 2, top, boxW, 0xC8C8C8);
		}
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

	private static String toggle(String name, boolean value) {
		return name + ": " + (value ? "\u00a7aON" : "\u00a7cOFF");
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
