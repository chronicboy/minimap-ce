package net.fabricmc.example.minimap;

import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * In-game settings GUI for all Minimap CE options.
 * Accessible from the fullscreen map, the waypoint list, or with the settings key (O).
 */
public class GuiMapSettings extends GuiScreen {
	private final GuiScreen parent;

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
	private static final int BTN_DONE = 99;

	private static final float[] OPACITY_STEPS = { 1.0f, 0.85f, 0.7f, 0.55f, 0.4f };

	public GuiMapSettings(GuiScreen parent) {
		this.parent = parent;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void initGui() {
		MapConfig cfg = MapConfig.instance;
		int centerX = this.width / 2;
		int btnW = Math.min(200, (this.width - 24) / 2);
		int btnH = 20;
		// Nine rows plus Done have to fit a 240 pixel tall screen.
		int gap = this.height >= 270 ? 24 : 21;
		int startY = this.height >= 270 ? 34 : 24;
		int leftCol = centerX - btnW - 4;
		int rightCol = centerX + 4;

		int y = startY;
		addRow(y, leftCol, rightCol, btnW, btnH,
				BTN_MINIMAP_ENABLED, toggle("Minimap", cfg.minimapEnabled),
				BTN_POSITION, positionLabel());
		y += gap;
		addRow(y, leftCol, rightCol, btnW, btnH,
				BTN_SHAPE, "Shape: " + (cfg.minimapShape == MapConfig.SHAPE_CIRCLE ? "Circle" : "Square"),
				BTN_ROTATE, "Rotation: " + (cfg.rotateMinimap ? "Follows Player" : "North Up"));
		y += gap;
		addRow(y, leftCol, rightCol, btnW, btnH,
				BTN_SIZE, "Size: " + cfg.minimapSize,
				BTN_ZOOM, "Zoom: " + zoomLabel(cfg.zoomLevel));
		y += gap;
		addRow(y, leftCol, rightCol, btnW, btnH,
				BTN_OPACITY, "Opacity: " + Math.round(cfg.mapOpacity * 100) + "%",
				BTN_PADDING, toggle("Minimap Padding", cfg.useMinimapPadding));
		y += gap;
		addRow(y, leftCol, rightCol, btnW, btnH,
				BTN_ENTITIES, toggle("Show Entities", cfg.showEntities),
				BTN_CHUNK_GRID, toggle("Chunk Grid", cfg.showChunkGrid));
		y += gap;
		addRow(y, leftCol, rightCol, btnW, btnH,
				BTN_COMPASS, toggle("Compass", cfg.showCompass),
				BTN_CLOCK, toggle("Clock", cfg.showClock));
		y += gap;
		addRow(y, leftCol, rightCol, btnW, btnH,
				BTN_COORDINATES, toggle("Coordinates", cfg.showCoordinates),
				BTN_BIOME, toggle("Biome Display", cfg.showBiome));
		y += gap;
		addRow(y, leftCol, rightCol, btnW, btnH,
				BTN_WAYPOINTS, toggle("Waypoints", cfg.showWaypoints),
				BTN_TELEPORT, toggle("Teleport Buttons", cfg.showTeleportButton));
		y += gap;
		this.buttonList.add(new GuiButton(BTN_PAUSE_GAME, leftCol, y, btnW, btnH,
				toggle("Map Pauses Game", cfg.fullscreenMapPausesGame)));
		this.buttonList.add(new GuiButton(BTN_DONE, rightCol, y, btnW, btnH, "Done"));
	}

	@SuppressWarnings("unchecked")
	private void addRow(int y, int leftCol, int rightCol, int w, int h, int leftId, String leftLabel, int rightId,
			String rightLabel) {
		this.buttonList.add(new GuiButton(leftId, leftCol, y, w, h, leftLabel));
		this.buttonList.add(new GuiButton(rightId, rightCol, y, w, h, rightLabel));
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (!button.enabled)
			return;
		MapConfig cfg = MapConfig.instance;
		// Hold shift to step backwards through multi-value options.
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
			case BTN_SIZE: {
				int size = cfg.minimapSize + (back ? -16 : 16);
				if (size > MapConfig.MAX_SIZE)
					size = 64;
				else if (size < 64)
					size = MapConfig.MAX_SIZE;
				cfg.minimapSize = size;
				break;
			}
			case BTN_ZOOM: {
				// Cycles from zoomed in to zoomed out.
				int zoom = cfg.zoomLevel + (back ? -1 : 1);
				if (zoom > MapConfig.MAX_ZOOM)
					zoom = MapConfig.MIN_ZOOM;
				else if (zoom < MapConfig.MIN_ZOOM)
					zoom = MapConfig.MAX_ZOOM;
				cfg.zoomLevel = zoom;
				break;
			}
			case BTN_OPACITY: {
				int index = 0;
				float best = Float.MAX_VALUE;
				for (int i = 0; i < OPACITY_STEPS.length; i++) {
					float d = Math.abs(OPACITY_STEPS[i] - cfg.mapOpacity);
					if (d < best) {
						best = d;
						index = i;
					}
				}
				int n = OPACITY_STEPS.length;
				cfg.mapOpacity = OPACITY_STEPS[(index + (back ? n - 1 : 1)) % n];
				break;
			}
			case BTN_PADDING:
				cfg.useMinimapPadding = !cfg.useMinimapPadding;
				break;
			case BTN_ENTITIES:
				cfg.showEntities = !cfg.showEntities;
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
			case BTN_WAYPOINTS:
				cfg.showWaypoints = !cfg.showWaypoints;
				break;
			case BTN_TELEPORT:
				cfg.showTeleportButton = !cfg.showTeleportButton;
				break;
			case BTN_PAUSE_GAME:
				cfg.fullscreenMapPausesGame = !cfg.fullscreenMapPausesGame;
				break;
			case BTN_DONE:
				cfg.saveConfig();
				this.mc.displayGuiScreen(parent);
				return;
			default:
				return;
		}

		cfg.saveConfig();
		this.buttonList.clear();
		this.initGui();
	}

	@Override
	protected void keyTyped(char c, int keyCode) {
		if (keyCode == Keyboard.KEY_ESCAPE || keyCode == MapConfig.settingsKey.keyCode) {
			MapConfig.instance.saveConfig();
			this.mc.displayGuiScreen(parent);
		}
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		this.drawCenteredString(this.fontRenderer, "§l§nMap Settings", this.width / 2,
				this.height >= 270 ? 12 : 8, 0xFFFFFF);
		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	private static String toggle(String name, boolean value) {
		return name + ": " + (value ? "§aON" : "§cOFF");
	}

	private static String positionLabel() {
		String[] positions = { "Top-Left", "Top-Right", "Bottom-Left", "Bottom-Right" };
		int pos = Math.max(0, Math.min(3, MapConfig.instance.minimapPosition));
		return "Position: " + positions[pos];
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
