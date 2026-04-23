package net.fabricmc.example.mixin;

import net.fabricmc.example.minimap.GuiFullscreenMap;
import net.fabricmc.example.minimap.GuiMapSettings;
import net.fabricmc.example.minimap.GuiWaypoints;
import net.fabricmc.example.minimap.MapConfig;
import net.fabricmc.example.minimap.MapTileManager;
import net.fabricmc.example.minimap.SpawnTracker;
import net.fabricmc.example.minimap.WaypointManager;
import net.minecraft.src.*;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	private static boolean wasPlusPressed = false;
	private static boolean wasMinusPressed = false;
	private static boolean wasIncreaseSizePressed = false;
	private static boolean wasDecreaseSizePressed = false;
	private static boolean wasCoordinatesKeyPressed = false;
	private static boolean wasWaypointKeyPressed = false;
	private static boolean wasFullscreenMapKeyPressed = false;
	private static boolean wasSettingsKeyPressed = false;
	private static String lastWorldId = "";
	private static World lastWorldInstance = null;
	private static boolean wasDead = false;

	@Inject(at = @At("HEAD"), method = "runTick")
	private void onTick(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;

		if (mc.theWorld != null && mc.thePlayer != null) {
			// Detect world changes and reload waypoints
			if (mc.theWorld != lastWorldInstance) {
				lastWorldInstance = mc.theWorld;
				String currentWorldId = WaypointManager.buildWorldId(mc.theWorld);
				if (!currentWorldId.equals(lastWorldId)) {
					lastWorldId = currentWorldId;
					WaypointManager.instance.loadForWorld(mc.theWorld);
					MapTileManager.instance.loadForWorld(mc.theWorld);
					SpawnTracker.instance.onWorldLoad(mc);
				}
			}

			// Auto-save map tiles periodically
			MapTileManager.instance.tickAutoSave();

			if (mc.currentScreen instanceof GuiGameOver && !wasDead) {
				wasDead = true;
				int maxDeath = 0;
				for (net.fabricmc.example.minimap.Waypoint w : WaypointManager.instance.getWaypoints()) {
					if (w.name.startsWith("Death")) {
						if (w.name.equals("Death")) {
							maxDeath = Math.max(maxDeath, 1);
						} else if (w.name.startsWith("Death ")) {
							try {
								maxDeath = Math.max(maxDeath, Integer.parseInt(w.name.substring(6)));
							} catch (NumberFormatException ignored) {
							}
						}
					}
				}
				String name = maxDeath == 0 ? "Death" : "Death " + (maxDeath + 1);

				int px = (int) Math.floor(mc.thePlayer.posX);
				int py = (int) mc.thePlayer.posY;
				int pz = (int) Math.floor(mc.thePlayer.posZ);
				boolean exists = false;
				for (net.fabricmc.example.minimap.Waypoint w : WaypointManager.instance.getWaypoints()) {
					if (w.x == px && w.y == py && w.z == pz && w.name.startsWith("Death")) {
						exists = true;
						break;
					}
				}
				if (!exists) {
					net.fabricmc.example.minimap.Waypoint wp = new net.fabricmc.example.minimap.Waypoint(name, px, py,
							pz, 0xFF0000);
					WaypointManager.instance.addWaypoint(wp);
					WaypointManager.instance.save();
				}
			} else if (!(mc.currentScreen instanceof GuiGameOver) && wasDead) {
				wasDead = false;
			}

			if (mc.currentScreen == null) {
				// Zoom controls
				if (Keyboard.isKeyDown(Keyboard.KEY_EQUALS) || Keyboard.isKeyDown(Keyboard.KEY_ADD)) {
					if (!wasPlusPressed) {
						wasPlusPressed = true;
						MapConfig.instance.zoomLevel = Math.max(
								MapConfig.instance.zoomLevel - 1, -1);
						MapConfig.instance.saveConfig();
					}
				} else {
					wasPlusPressed = false;
				}

				if (Keyboard.isKeyDown(Keyboard.KEY_MINUS) || Keyboard.isKeyDown(Keyboard.KEY_SUBTRACT)) {
					if (!wasMinusPressed) {
						wasMinusPressed = true;
						MapConfig.instance.zoomLevel = Math.min(
								MapConfig.instance.zoomLevel + 1, 1);
						MapConfig.instance.saveConfig();
					}
				} else {
					wasMinusPressed = false;
				}

				// Size adjustment
				if (MapConfig.increaseSizeKey.isPressed()) {
					if (!wasIncreaseSizePressed) {
						wasIncreaseSizePressed = true;
						MapConfig.instance.minimapSize = Math.min(
								MapConfig.instance.minimapSize + 16, 256);
						MapConfig.instance.saveConfig();
					}
				} else {
					wasIncreaseSizePressed = false;
				}

				if (MapConfig.decreaseSizeKey.isPressed()) {
					if (!wasDecreaseSizePressed) {
						wasDecreaseSizePressed = true;
						MapConfig.instance.minimapSize = Math.max(
								MapConfig.instance.minimapSize - 16, 32);
						MapConfig.instance.saveConfig();
					}
				} else {
					wasDecreaseSizePressed = false;
				}

				// Toggle entities (F key)
				if (MapConfig.toggleEntitiesKey.isPressed()) {
					if (!wasCoordinatesKeyPressed) {
						wasCoordinatesKeyPressed = true;
						MapConfig.instance.showEntities = !MapConfig.instance.showEntities;
						MapConfig.instance.saveConfig();
					}
				} else {
					wasCoordinatesKeyPressed = false;
				}

				// Waypoint GUI
				if (MapConfig.waypointGuiKey.isPressed()) {
					if (!wasWaypointKeyPressed) {
						wasWaypointKeyPressed = true;
						mc.displayGuiScreen(new GuiWaypoints());
					}
				} else {
					wasWaypointKeyPressed = false;
				}

				// Fullscreen Map (J key)
				if (MapConfig.fullscreenMapKey.isPressed()) {
					if (!wasFullscreenMapKeyPressed) {
						wasFullscreenMapKeyPressed = true;
						mc.displayGuiScreen(new GuiFullscreenMap());
					}
				} else {
					wasFullscreenMapKeyPressed = false;
				}

				// Map Settings (O key)
				if (MapConfig.settingsKey.isPressed()) {
					if (!wasSettingsKeyPressed) {
						wasSettingsKeyPressed = true;
						mc.displayGuiScreen(new GuiMapSettings(null));
					}
				} else {
					wasSettingsKeyPressed = false;
				}
			}
		} else {
			// Player left the world — save and clear waypoints
			if (!lastWorldId.isEmpty()) {
				WaypointManager.instance.clearForWorldLeave();
				MapTileManager.instance.clearForWorldLeave();
				SpawnTracker.instance.onWorldLeave();
				lastWorldId = "";
			}
		}
	}

	@Inject(at = @At("HEAD"), method = "shutdown")
	private void onShutdown(CallbackInfo ci) {
		MapConfig.instance.saveConfig();
		WaypointManager.instance.save();
		MapTileManager.instance.saveAll();
	}
}
