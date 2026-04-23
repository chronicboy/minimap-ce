package net.fabricmc.example.minimap;

import net.minecraft.src.*;
import org.lwjgl.input.Keyboard;

/**
 * In-game settings GUI for all Minimap CE options.
 * Accessible from fullscreen map or with O key.
 */
public class GuiMapSettings extends GuiScreen {
	private final GuiScreen parent;

	// Button IDs
	private static final int BTN_MINIMAP_ENABLED = 0;
	private static final int BTN_ENTITIES = 2;
	private static final int BTN_CHUNK_GRID = 3;
	private static final int BTN_COMPASS = 4;
	private static final int BTN_COORDINATES = 5;
	private static final int BTN_WAYPOINTS = 6;
	private static final int BTN_BIOME = 7;
	private static final int BTN_POSITION = 8;
	private static final int BTN_TELEPORT = 9;
	private static final int BTN_PAUSE_GAME = 10;
	private static final int BTN_PADDING = 11;
	private static final int BTN_DONE = 99;

	public GuiMapSettings(GuiScreen parent) {
		this.parent = parent;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void initGui() {
		int centerX = this.width / 2;
		int startY = 36;
		int btnW = 200;
		int btnH = 20;
		int gap = 24;
		int leftCol = centerX - btnW - 4;
		int rightCol = centerX + 4;

		// Left column
		int y = startY;
		this.buttonList.add(new GuiButton(BTN_MINIMAP_ENABLED, leftCol, y, btnW, btnH,
				getLabel("Minimap", MapConfig.instance.minimapEnabled)));
		y += gap;
		this.buttonList.add(new GuiButton(BTN_ENTITIES, leftCol, y, btnW, btnH,
				getLabel("Show Entities", MapConfig.instance.showEntities)));
		y += gap;
		this.buttonList.add(new GuiButton(BTN_CHUNK_GRID, leftCol, y, btnW, btnH,
				getLabel("Chunk Grid", MapConfig.instance.showChunkGrid)));
		y += gap;
		this.buttonList.add(new GuiButton(BTN_COMPASS, leftCol, y, btnW, btnH,
				getLabel("Compass", MapConfig.instance.showCompass)));

		// Right column
		y = startY;
		this.buttonList.add(new GuiButton(BTN_COORDINATES, rightCol, y, btnW, btnH,
				getLabel("Coordinates", MapConfig.instance.showCoordinates)));
		y += gap;
		this.buttonList.add(new GuiButton(BTN_BIOME, rightCol, y, btnW, btnH,
				getLabel("Biome Display", MapConfig.instance.showBiome)));
		y += gap;
		this.buttonList.add(new GuiButton(BTN_WAYPOINTS, rightCol, y, btnW, btnH,
				getLabel("Waypoints", MapConfig.instance.showWaypoints)));
		y += gap;
		this.buttonList.add(new GuiButton(BTN_POSITION, rightCol, y, btnW, btnH, getPositionLabel()));

		// Full-width rows
		y = startY + gap * 4;
		this.buttonList.add(new GuiButton(BTN_TELEPORT, leftCol, y, btnW, btnH,
				getLabel("Teleport Buttons", MapConfig.instance.showTeleportButton)));
		this.buttonList.add(new GuiButton(BTN_PAUSE_GAME, rightCol, y, btnW, btnH,
				getLabel("Pause Game", MapConfig.instance.fullscreenMapPausesGame)));
		y += gap;
		this.buttonList.add(new GuiButton(BTN_PADDING, leftCol, y, btnW, btnH,
				getLabel("Minimap Padding", MapConfig.instance.useMinimapPadding)));

		// Done button
		int doneY = startY + gap * 6;
		this.buttonList.add(new GuiButton(BTN_DONE, centerX - 100, doneY, 200, 20, "Done"));
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (!button.enabled)
			return;

		switch (button.id) {
			case BTN_MINIMAP_ENABLED:
				MapConfig.instance.minimapEnabled = !MapConfig.instance.minimapEnabled;
				break;
			case BTN_ENTITIES:
				MapConfig.instance.showEntities = !MapConfig.instance.showEntities;
				break;
			case BTN_CHUNK_GRID:
				MapConfig.instance.showChunkGrid = !MapConfig.instance.showChunkGrid;
				break;
			case BTN_COMPASS:
				MapConfig.instance.showCompass = !MapConfig.instance.showCompass;
				break;
			case BTN_COORDINATES:
				MapConfig.instance.showCoordinates = !MapConfig.instance.showCoordinates;
				break;
			case BTN_BIOME:
				MapConfig.instance.showBiome = !MapConfig.instance.showBiome;
				break;
			case BTN_WAYPOINTS:
				MapConfig.instance.showWaypoints = !MapConfig.instance.showWaypoints;
				break;
			case BTN_POSITION:
				MapConfig.instance.minimapPosition = (MapConfig.instance.minimapPosition + 1) % 4;
				break;
			case BTN_TELEPORT:
				MapConfig.instance.showTeleportButton = !MapConfig.instance.showTeleportButton;
				break;
			case BTN_PAUSE_GAME:
				MapConfig.instance.fullscreenMapPausesGame = !MapConfig.instance.fullscreenMapPausesGame;
				break;
			case BTN_PADDING:
				MapConfig.instance.useMinimapPadding = !MapConfig.instance.useMinimapPadding;
				break;
			case BTN_DONE:
				MapConfig.instance.saveConfig();
				this.mc.displayGuiScreen(parent);
				return;
		}

		// Save and refresh UI
		MapConfig.instance.saveConfig();
		this.buttonList.clear();
		this.initGui();
	}

	@Override
	protected void keyTyped(char c, int keyCode) {
		if (keyCode == Keyboard.KEY_ESCAPE) {
			MapConfig.instance.saveConfig();
			this.mc.displayGuiScreen(parent);
		}
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		this.drawCenteredString(this.fontRenderer, "\u00a7l\u00a7nMap Settings", this.width / 2, 12, 0xFFFFFF);
		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	private String getLabel(String name, boolean value) {
		return name + ": " + (value ? "\u00a7aON" : "\u00a7cOFF");
	}

	private String getPositionLabel() {
		String[] positions = { "Top-Left", "Top-Right", "Bottom-Left", "Bottom-Right" };
		int pos = Math.max(0, Math.min(3, MapConfig.instance.minimapPosition));
		return "Position: " + positions[pos];
	}

	@Override
	public boolean doesGuiPauseGame() {
		return true;
	}
}
