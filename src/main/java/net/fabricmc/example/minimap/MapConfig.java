package net.fabricmc.example.minimap;

import net.minecraft.src.KeyBinding;
import org.lwjgl.input.Keyboard;

import java.io.*;
import java.util.Properties;

public class MapConfig {
	public boolean minimapEnabled = true;
	public int minimapSize = 128;
	public int minimapPosition = 1; // 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right
	public boolean showEntities = true;
	public int zoomLevel = 0; // 0=normal, positive=zoomed in, negative=zoomed out
	public boolean circular = true;
	public boolean showChunkGrid = true;
	public boolean showCompass = true;
	public boolean showCoordinates = true;
	public boolean showWaypoints = true;
	public boolean showSpawnWaypoint = true;

	public static KeyBinding toggleCoordinatesKey = new KeyBinding("Toggle Coordinates", Keyboard.KEY_F);
	public static KeyBinding increaseSizeKey = new KeyBinding("Minimap: Increase Size", Keyboard.KEY_RBRACKET);
	public static KeyBinding decreaseSizeKey = new KeyBinding("Minimap: Decrease Size", Keyboard.KEY_LBRACKET);
	public static KeyBinding waypointGuiKey = new KeyBinding("Waypoints", Keyboard.KEY_B);

	public static MapConfig instance = new MapConfig();

	private static final String CONFIG_FILE = "config/minimap-ce.conf";

	public void loadConfig() {
		// Delete existing .old file if it exists (BTW CE creates backups and can
		// conflict)
		File oldFile = new File(CONFIG_FILE + ".old");
		if (oldFile.exists()) {
			oldFile.delete();
		}

		File configFile = new File(CONFIG_FILE);
		if (!configFile.exists()) {
			System.out.println("[Minimap CE] Config file not found, using defaults: " + CONFIG_FILE);
			// Save defaults on first run
			saveConfig();
			return;
		}

		Properties props = new Properties();
		try (FileInputStream fis = new FileInputStream(configFile)) {
			props.load(fis);

			// Load all settings
			if (props.containsKey("minimapEnabled")) {
				minimapEnabled = Boolean.parseBoolean(props.getProperty("minimapEnabled"));
			}
			if (props.containsKey("minimapSize")) {
				minimapSize = Integer.parseInt(props.getProperty("minimapSize"));
				// Clamp to valid range
				minimapSize = Math.max(32, Math.min(256, minimapSize));
			}
			if (props.containsKey("minimapPosition")) {
				minimapPosition = Integer.parseInt(props.getProperty("minimapPosition"));
				minimapPosition = Math.max(0, Math.min(3, minimapPosition));
			}
			if (props.containsKey("showEntities")) {
				showEntities = Boolean.parseBoolean(props.getProperty("showEntities"));
			}
			if (props.containsKey("zoomLevel")) {
				zoomLevel = Integer.parseInt(props.getProperty("zoomLevel"));
				zoomLevel = Math.max(-4, Math.min(4, zoomLevel));
			}
			if (props.containsKey("circular")) {
				circular = Boolean.parseBoolean(props.getProperty("circular"));
			}
			if (props.containsKey("showChunkGrid")) {
				showChunkGrid = Boolean.parseBoolean(props.getProperty("showChunkGrid"));
			}
			if (props.containsKey("showCompass")) {
				showCompass = Boolean.parseBoolean(props.getProperty("showCompass"));
			}
			if (props.containsKey("showCoordinates")) {
				showCoordinates = Boolean.parseBoolean(props.getProperty("showCoordinates"));
			}
			if (props.containsKey("showWaypoints")) {
				showWaypoints = Boolean.parseBoolean(props.getProperty("showWaypoints"));
			}
			if (props.containsKey("showSpawnWaypoint")) {
				showSpawnWaypoint = Boolean.parseBoolean(props.getProperty("showSpawnWaypoint"));
			}
			System.out.println("[Minimap CE] Config loaded successfully from: " + CONFIG_FILE);
		} catch (IOException | NumberFormatException e) {
			System.err.println("[Minimap CE] Failed to load config: " + e.getMessage());
			e.printStackTrace();
		}
	}

	public void saveConfig() {
		File configDir = new File("config");
		if (!configDir.exists()) {
			configDir.mkdirs();
		}

		File configFile = new File(CONFIG_FILE);

		// Delete existing .old file if it exists (BTW CE creates backups)
		File oldFile = new File(CONFIG_FILE + ".old");
		if (oldFile.exists()) {
			oldFile.delete();
		}

		Properties props = new Properties();

		// Save all settings
		props.setProperty("minimapEnabled", String.valueOf(minimapEnabled));
		props.setProperty("minimapSize", String.valueOf(minimapSize));
		props.setProperty("minimapPosition", String.valueOf(minimapPosition));
		props.setProperty("showEntities", String.valueOf(showEntities));
		props.setProperty("zoomLevel", String.valueOf(zoomLevel));
		props.setProperty("circular", String.valueOf(circular));
		props.setProperty("showChunkGrid", String.valueOf(showChunkGrid));
		props.setProperty("showCompass", String.valueOf(showCompass));
		props.setProperty("showCoordinates", String.valueOf(showCoordinates));
		props.setProperty("showWaypoints", String.valueOf(showWaypoints));
		props.setProperty("showSpawnWaypoint", String.valueOf(showSpawnWaypoint));

		try (FileOutputStream fos = new FileOutputStream(configFile)) {
			props.store(fos, "Minimap CE Configuration");
			System.out.println("[Minimap CE] Config saved successfully to: " + CONFIG_FILE);
		} catch (IOException e) {
			System.err.println("[Minimap CE] Failed to save config: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
