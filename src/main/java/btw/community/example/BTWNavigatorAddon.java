package btw.community.example;

import api.AddonHandler;
import api.BTWAddon;
import net.fabricmc.example.minimap.MapConfig;

import java.io.File;

public class BTWNavigatorAddon extends BTWAddon {
	private static BTWNavigatorAddon instance;
	private static final String CONFIG_FILE = "config/minimap-ce.conf";

	public BTWNavigatorAddon() {
		super();
	}

	public static BTWNavigatorAddon getInstance() {
		return instance;
	}

	@Override
	public void preInitialize() {
		// Delete existing .old file if it exists (BTW CE creates backups and can conflict)
		// This runs before BTW CE processes configs, preventing the FileAlreadyExistsException
		File oldFile = new File(CONFIG_FILE + ".old");
		if (oldFile.exists()) {
			oldFile.delete();
		}
	}

	@Override
	public void initialize() {
		instance = this;
		// Load config after BTW CE has processed its configs
		// This ensures the config directory exists and we're not interfering with BTW CE's config system
		MapConfig.instance.loadConfig();
		AddonHandler.logMessage(this.getName() + " v" + this.getVersionString() + " initialized");
	}
}

