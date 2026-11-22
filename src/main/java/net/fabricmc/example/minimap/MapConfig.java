package net.fabricmc.example.minimap;

import net.minecraft.src.KeyBinding;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public class MapConfig {
	public static final int MIN_SIZE = 32;
	public static final int MAX_SIZE = 256;
	/** zoomLevel n means 2^-n GUI pixels per block: -2 = 4x ... 2 = 1/4x. */
	public static final int MIN_ZOOM = -2;
	public static final int MAX_ZOOM = 2;
	public static final double MIN_FULLSCREEN_SCALE = 1.0 / 64.0;
	public static final double MAX_FULLSCREEN_SCALE = 16.0;

	public static final int SHAPE_CIRCLE = 0;
	public static final int SHAPE_SQUARE = 1;

	public static final int ENTITY_HEADS = 0;
	public static final int ENTITY_ARROWS = 1;
	public static final int ENTITY_DOTS = 2;
	/** Small dots, faces while the player list key (Tab) is held. */
	public static final int ENTITY_DOTS_FACES_ON_TAB = 3;

	public static final int WAYPOINT_LETTERS = 0;
	public static final int WAYPOINT_SQUARES = 1;

	/** In the world: beam and diamond, "Name (distance)" while you look towards it. */
	public static final int WORLD_LABELS_ICONS = 0;
	/** In the world: beam, diamond and "Name (distance)" at all times. */
	public static final int WORLD_LABELS_FULL = 1;
	/** In the world: only the beams. */
	public static final int WORLD_LABELS_OFF = 2;

	public boolean minimapEnabled = true;
	public int minimapSize = 128;
	public int minimapPosition = 1; // 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right
	public int minimapShape = SHAPE_CIRCLE;
	public boolean rotateMinimap = false;
	/** Underground, show a live slice of the caves around the player instead of the surface. */
	public boolean caveMode = true;
	/** Crisp-with-smooth-edges sampling for in-between zoom levels and rotation (needs OpenGL 2.0). */
	public boolean sharpScaling = true;
	public float mapOpacity = 1.0f;
	public boolean showEntities = true;
	/** Radar style: dots with faces on Tab (default), always faces, direction arrows (3.x look) or dots. */
	public int entityIconStyle = ENTITY_DOTS_FACES_ON_TAB;
	/** Waypoint style: coloured tile with the name's initial, or the small 3.x square. */
	public int waypointIconStyle = WAYPOINT_LETTERS;
	/** How waypoints are labelled in the world (see WORLD_LABELS_*). */
	public int worldWaypointLabels = WORLD_LABELS_ICONS;
	public int zoomLevel = 0;
	public boolean showChunkGrid = false;
	public boolean showCompass = true;
	public boolean showCoordinates = true;
	public boolean showBiome = true;
	public boolean showClock = true;
	public boolean showWaypoints = true;
	public boolean showSpawnWaypoint = true;
	/** Fullscreen map zoom in GUI pixels per block. */
	public double fullscreenScale = 1.0;
	public boolean showTeleportButton = true;
	public boolean useMinimapPadding = true;
	public boolean fullscreenMapPausesGame = true;
	public String teleportCommand = "/tp {x} {y} {z}";

	// Only kept so that a 3.x build reading this file still finds its own keys.
	private int legacyFullscreenZoom = 0;
	private float legacyMinimapOpacity = 0.9f;

	public static KeyBinding toggleEntitiesKey = new KeyBinding("[Minimap] Toggle Entities", Keyboard.KEY_F);
	public static KeyBinding increaseSizeKey = new KeyBinding("[Minimap] Increase Size", Keyboard.KEY_RBRACKET);
	public static KeyBinding decreaseSizeKey = new KeyBinding("[Minimap] Decrease Size", Keyboard.KEY_LBRACKET);
	public static KeyBinding waypointGuiKey = new KeyBinding("[Minimap] Waypoints", Keyboard.KEY_B);
	public static KeyBinding fullscreenMapKey = new KeyBinding("[Minimap] Fullscreen Map", Keyboard.KEY_J);
	public static KeyBinding settingsKey = new KeyBinding("[Minimap] Map Settings", Keyboard.KEY_O);
	public static KeyBinding zoomInKey = new KeyBinding("[Minimap] Zoom In", Keyboard.KEY_EQUALS);
	public static KeyBinding zoomOutKey = new KeyBinding("[Minimap] Zoom Out", Keyboard.KEY_MINUS);
	public static KeyBinding quickWaypointKey = new KeyBinding("[Minimap] New Waypoint Here", Keyboard.KEY_N);
	public static KeyBinding toggleMinimapKey = new KeyBinding("[Minimap] Toggle Minimap", Keyboard.KEY_M);

	public static final KeyBinding[] ALL_KEYS = {
			toggleEntitiesKey, increaseSizeKey, decreaseSizeKey, waypointGuiKey, fullscreenMapKey, settingsKey,
			zoomInKey, zoomOutKey, quickWaypointKey, toggleMinimapKey
	};

	public static MapConfig instance = new MapConfig();

	private static final String CONFIG_FILE = "config/minimap-ce.conf";

	/** GUI pixels per block on the minimap. */
	public double minimapScale() {
		return Math.pow(2.0, -zoomLevel);
	}

	public void zoomMinimap(int delta) {
		zoomLevel = clamp(zoomLevel + delta, MIN_ZOOM, MAX_ZOOM);
	}

	public void loadConfig() {
		// Delete existing .old file if it exists (BTW CE creates backups and can conflict)
		File oldFile = new File(CONFIG_FILE + ".old");
		if (oldFile.exists()) {
			oldFile.delete();
		}

		File configFile = new File(CONFIG_FILE);
		if (!configFile.exists()) {
			System.out.println("[Minimap CE] Config file not found, using defaults: " + CONFIG_FILE);
			saveConfig();
			return;
		}

		Properties props = new Properties();
		try (FileInputStream fis = new FileInputStream(configFile)) {
			props.load(fis);
		} catch (IOException e) {
			System.err.println("[Minimap CE] Failed to load config: " + e.getMessage());
			return;
		}

		// Every value is parsed on its own, so one bad entry cannot discard the rest.
		minimapEnabled = getBool(props, "minimapEnabled", minimapEnabled);
		minimapSize = clamp(getInt(props, "minimapSize", minimapSize), MIN_SIZE, MAX_SIZE);
		minimapPosition = clamp(getInt(props, "minimapPosition", minimapPosition), 0, 3);
		if (props.containsKey("minimapShape")) {
			minimapShape = clamp(getInt(props, "minimapShape", minimapShape), 0, 1);
		} else if (props.containsKey("circular")) {
			minimapShape = getBool(props, "circular", true) ? SHAPE_CIRCLE : SHAPE_SQUARE;
		}
		rotateMinimap = getBool(props, "rotateMinimap", rotateMinimap);
		caveMode = getBool(props, "caveMode", caveMode);
		sharpScaling = getBool(props, "sharpScaling", sharpScaling);
		mapOpacity = clamp(getFloat(props, "mapOpacity", mapOpacity), 0.25f, 1.0f);
		showEntities = getBool(props, "showEntities", showEntities);
		// Stored under a new name: the previous builds' "entityIconStyle" predates the Tab default.
		entityIconStyle = clamp(getInt(props, "radarIcons", entityIconStyle), 0, 3);
		waypointIconStyle = clamp(getInt(props, "waypointIconStyle", waypointIconStyle), 0, 1);
		worldWaypointLabels = clamp(getInt(props, "worldWaypointLabels", worldWaypointLabels), 0, 2);
		zoomLevel = clamp(getInt(props, "zoomLevel", zoomLevel), MIN_ZOOM, MAX_ZOOM);
		showChunkGrid = getBool(props, "showChunkGrid", showChunkGrid);
		showCompass = getBool(props, "showCompass", showCompass);
		showCoordinates = getBool(props, "showCoordinates", showCoordinates);
		showBiome = getBool(props, "showBiome", showBiome);
		showClock = getBool(props, "showClock", showClock);
		showWaypoints = getBool(props, "showWaypoints", showWaypoints);
		showSpawnWaypoint = getBool(props, "showSpawnWaypoint", showSpawnWaypoint);
		showTeleportButton = getBool(props, "showTeleportButton", showTeleportButton);
		useMinimapPadding = getBool(props, "useMinimapPadding", useMinimapPadding);
		fullscreenMapPausesGame = getBool(props, "fullscreenMapPausesGame", fullscreenMapPausesGame);

		legacyFullscreenZoom = clamp(getInt(props, "fullscreenZoom", legacyFullscreenZoom), -4, 4);
		legacyMinimapOpacity = clamp(getFloat(props, "minimapOpacity", legacyMinimapOpacity), 0.1f, 1.0f);
		if (props.containsKey("fullscreenScale")) {
			fullscreenScale = getDouble(props, "fullscreenScale", fullscreenScale);
		} else {
			// First run after upgrading from 3.x: carry the old zoom step over.
			fullscreenScale = Math.pow(2.0, -legacyFullscreenZoom);
		}
		if (Double.isNaN(fullscreenScale))
			fullscreenScale = 1.0;
		fullscreenScale = Math.max(MIN_FULLSCREEN_SCALE, Math.min(MAX_FULLSCREEN_SCALE, fullscreenScale));

		String cmd = props.getProperty("teleportCommand");
		if (cmd != null) {
			cmd = cmd.trim();
			if (cmd.length() >= 2 && cmd.startsWith("\"") && cmd.endsWith("\"")) {
				cmd = cmd.substring(1, cmd.length() - 1);
			}
			if (!cmd.isEmpty())
				teleportCommand = cmd;
		}
		System.out.println("[Minimap CE] Config loaded from: " + CONFIG_FILE);
	}

	public void saveConfig() {
		File configDir = new File("config");
		if (!configDir.exists()) {
			configDir.mkdirs();
		}

		// Delete existing .old file if it exists (BTW CE creates backups)
		File oldFile = new File(CONFIG_FILE + ".old");
		if (oldFile.exists()) {
			oldFile.delete();
		}

		Properties props = new Properties();
		props.setProperty("minimapEnabled", String.valueOf(minimapEnabled));
		props.setProperty("minimapSize", String.valueOf(minimapSize));
		props.setProperty("minimapPosition", String.valueOf(minimapPosition));
		props.setProperty("minimapShape", String.valueOf(minimapShape));
		props.setProperty("circular", String.valueOf(minimapShape == SHAPE_CIRCLE));
		props.setProperty("rotateMinimap", String.valueOf(rotateMinimap));
		props.setProperty("caveMode", String.valueOf(caveMode));
		props.setProperty("sharpScaling", String.valueOf(sharpScaling));
		props.setProperty("mapOpacity", String.valueOf(mapOpacity));
		props.setProperty("showEntities", String.valueOf(showEntities));
		props.setProperty("radarIcons", String.valueOf(entityIconStyle));
		props.setProperty("waypointIconStyle", String.valueOf(waypointIconStyle));
		props.setProperty("worldWaypointLabels", String.valueOf(worldWaypointLabels));
		props.setProperty("zoomLevel", String.valueOf(zoomLevel));
		props.setProperty("showChunkGrid", String.valueOf(showChunkGrid));
		props.setProperty("showCompass", String.valueOf(showCompass));
		props.setProperty("showCoordinates", String.valueOf(showCoordinates));
		props.setProperty("showBiome", String.valueOf(showBiome));
		props.setProperty("showClock", String.valueOf(showClock));
		props.setProperty("showWaypoints", String.valueOf(showWaypoints));
		props.setProperty("showSpawnWaypoint", String.valueOf(showSpawnWaypoint));
		props.setProperty("fullscreenScale", String.valueOf(fullscreenScale));
		props.setProperty("fullscreenZoom", String.valueOf(legacyFullscreenZoom));
		props.setProperty("minimapOpacity", String.valueOf(legacyMinimapOpacity));
		props.setProperty("showTeleportButton", String.valueOf(showTeleportButton));
		props.setProperty("useMinimapPadding", String.valueOf(useMinimapPadding));
		props.setProperty("fullscreenMapPausesGame", String.valueOf(fullscreenMapPausesGame));

		String savedCommand = teleportCommand == null ? "" : teleportCommand;
		if (!savedCommand.startsWith("\"")) {
			savedCommand = "\"" + savedCommand + "\"";
		}
		props.setProperty("teleportCommand", savedCommand);

		// Written to a temporary file first and moved into place, so a crash mid-save keeps the old settings.
		File target = new File(CONFIG_FILE);
		File tmp = new File(CONFIG_FILE + ".tmp");
		try (FileOutputStream fos = new FileOutputStream(tmp)) {
			props.store(fos, "Minimap CE Configuration");
		} catch (IOException e) {
			System.err.println("[Minimap CE] Failed to save config: " + e.getMessage());
			tmp.delete();
			return;
		}
		try {
			try {
				Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			System.err.println("[Minimap CE] Failed to save config: " + e.getMessage());
			tmp.delete();
		}
	}

	private static boolean getBool(Properties p, String key, boolean def) {
		String v = p.getProperty(key);
		if (v == null)
			return def;
		v = v.trim();
		if (v.equalsIgnoreCase("true"))
			return true;
		if (v.equalsIgnoreCase("false"))
			return false;
		return def;
	}

	private static int getInt(Properties p, String key, int def) {
		String v = p.getProperty(key);
		if (v == null)
			return def;
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static float getFloat(Properties p, String key, float def) {
		String v = p.getProperty(key);
		if (v == null)
			return def;
		try {
			float f = Float.parseFloat(v.trim());
			return Float.isNaN(f) ? def : f;
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static double getDouble(Properties p, String key, double def) {
		String v = p.getProperty(key);
		if (v == null)
			return def;
		try {
			return Double.parseDouble(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static int clamp(int v, int min, int max) {
		return v < min ? min : (v > max ? max : v);
	}

	private static float clamp(float v, float min, float max) {
		return v < min ? min : (v > max ? max : v);
	}
}
