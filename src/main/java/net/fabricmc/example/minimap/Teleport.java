package net.fabricmc.example.minimap;

import net.minecraft.src.EntityPlayerMP;
import net.minecraft.src.Minecraft;

/** Teleporting to map positions through the configured chat command. */
final class Teleport {
	private Teleport() {
	}

	/**
	 * Closes any open screen and teleports the player. Within the same dimension
	 * the configured command is used. Across dimensions a singleplayer operator is
	 * moved directly; otherwise the command must contain {dim}.
	 *
	 * @param y the height to arrive at
	 */
	static void to(Minecraft mc, int x, int y, int z, int dimension, String name) {
		if (mc.thePlayer == null || mc.theWorld == null)
			return;
		mc.displayGuiScreen(null);
		String template = MapConfig.instance.teleportCommand;
		if (dimension != mc.theWorld.provider.dimensionId) {
			try {
				if (mc.isSingleplayer() && mc.getIntegratedServer() != null
						&& mc.getIntegratedServer().getWorldName() != null
						&& mc.getIntegratedServer().getConfigurationManager().getOps()
								.contains(mc.thePlayer.username.toLowerCase())) {
					EntityPlayerMP playerMP = mc.getIntegratedServer().getConfigurationManager()
							.getPlayerEntity(mc.thePlayer.username);
					if (playerMP != null) {
						mc.getIntegratedServer().getConfigurationManager().transferPlayerToDimension(playerMP,
								dimension);
						mc.thePlayer.sendChatMessage("/tp " + x + " " + y + " " + z);
						return;
					}
				}
			} catch (Throwable ignored) {
			}
			if (!template.contains("{dim}")) {
				mc.thePlayer.addChatMessage("§c[Minimap] Cannot teleport to another dimension here. Set a "
						+ "teleportCommand that uses {dim} in config/minimap-ce.conf.");
				return;
			}
		}
		String command = template
				.replace("{x}", String.valueOf(x))
				.replace("{y}", String.valueOf(y))
				.replace("{z}", String.valueOf(z))
				.replace("{dim}", String.valueOf(dimension))
				.replace("{name}", name == null ? "" : name);
		mc.thePlayer.sendChatMessage(command);
	}
}
