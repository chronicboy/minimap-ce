package net.fabricmc.example.mixin;

import net.fabricmc.example.minimap.GuiFullscreenMap;
import net.fabricmc.example.minimap.GuiMapSettings;
import net.fabricmc.example.minimap.GuiWaypoints;
import net.fabricmc.example.minimap.MapConfig;
import net.fabricmc.example.minimap.SpawnTracker;
import net.fabricmc.example.minimap.Waypoint;
import net.fabricmc.example.minimap.WaypointManager;
import net.fabricmc.example.minimap.engine.MapEngine;
import net.fabricmc.example.minimap.engine.MapExport;
import net.minecraft.src.GuiGameOver;
import net.minecraft.src.Minecraft;
import net.minecraft.src.World;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Unique
	private static boolean wasNumpadPlusDown = false;
	@Unique
	private static boolean wasNumpadMinusDown = false;
	@Unique
	private static String lastWorldId = "";
	@Unique
	private static World lastWorldInstance = null;
	@Unique
	private static boolean wasDead = false;
	@Unique
	private static boolean engineErrorLogged = false;

	@Inject(at = @At("HEAD"), method = "runTick")
	private void onTick(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;

		// Map upkeep: world tracking, chunk sampling, background saving.
		try {
			MapEngine.INSTANCE.tick(mc);
		} catch (Throwable t) {
			if (!engineErrorLogged) {
				engineErrorLogged = true;
				System.err.println("[Minimap CE] Map engine tick failed:");
				t.printStackTrace();
			}
		}

		// Map export finished: tell the player where the picture is.
		String exportMessage;
		while ((exportMessage = MapExport.pollMessage()) != null) {
			if (mc.ingameGUI != null)
				mc.ingameGUI.getChatGUI().printChatMessage("§7[Minimap]§r " + exportMessage);
		}

		if (mc.theWorld != null && mc.thePlayer != null) {
			// Detect world changes and reload waypoints
			if (mc.theWorld != lastWorldInstance) {
				lastWorldInstance = mc.theWorld;
				String currentWorldId = WaypointManager.buildWorldId(mc.theWorld);
				if (!currentWorldId.equals(lastWorldId)) {
					lastWorldId = currentWorldId;
					WaypointManager.instance.loadForWorld(mc.theWorld);
					SpawnTracker.instance.onWorldLoad(mc);
				}
			}

			if (mc.currentScreen instanceof GuiGameOver && !wasDead) {
				wasDead = true;
				addDeathWaypoint(mc);
			} else if (!(mc.currentScreen instanceof GuiGameOver) && wasDead) {
				wasDead = false;
			}

			if (mc.currentScreen == null) {
				handleKeys(mc);
			}
		} else {
			// Player left the world: save and clear waypoints
			if (!lastWorldId.isEmpty()) {
				WaypointManager.instance.clearForWorldLeave();
				SpawnTracker.instance.onWorldLeave();
				lastWorldId = "";
			}
			lastWorldInstance = null;
		}
	}

	@Unique
	private static void addDeathWaypoint(Minecraft mc) {
		int maxDeath = 0;
		for (Waypoint w : WaypointManager.instance.getWaypoints()) {
			if (w.name == null)
				continue;
			if (w.name.equals("Death")) {
				maxDeath = Math.max(maxDeath, 1);
			} else if (w.name.startsWith("Death ")) {
				try {
					maxDeath = Math.max(maxDeath, Integer.parseInt(w.name.substring(6).trim()));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		String name = maxDeath == 0 ? "Death" : "Death " + (maxDeath + 1);

		int px = (int) Math.floor(mc.thePlayer.posX);
		int py = (int) Math.floor(mc.thePlayer.boundingBox.minY);
		int pz = (int) Math.floor(mc.thePlayer.posZ);
		for (Waypoint w : WaypointManager.instance.getWaypoints()) {
			if (w.x == px && w.y == py && w.z == pz && w.name != null && w.name.startsWith("Death"))
				return; // already recorded
		}
		WaypointManager.instance.addWaypoint(new Waypoint(name, px, py, pz, 0xFF0000));
	}

	@Unique
	private static void handleKeys(Minecraft mc) {
		MapConfig cfg = MapConfig.instance;
		boolean changed = false;

		// Zoom: the rebindable keys, plus numpad +/- as before.
		while (MapConfig.zoomInKey.isPressed()) {
			cfg.zoomMinimap(-1);
			changed = true;
		}
		while (MapConfig.zoomOutKey.isPressed()) {
			cfg.zoomMinimap(1);
			changed = true;
		}
		boolean plus = Keyboard.isKeyDown(Keyboard.KEY_ADD);
		if (plus && !wasNumpadPlusDown) {
			cfg.zoomMinimap(-1);
			changed = true;
		}
		wasNumpadPlusDown = plus;
		boolean minus = Keyboard.isKeyDown(Keyboard.KEY_SUBTRACT);
		if (minus && !wasNumpadMinusDown) {
			cfg.zoomMinimap(1);
			changed = true;
		}
		wasNumpadMinusDown = minus;

		while (MapConfig.increaseSizeKey.isPressed()) {
			cfg.minimapSize = Math.min(cfg.minimapSize + 16, MapConfig.MAX_SIZE);
			changed = true;
		}
		while (MapConfig.decreaseSizeKey.isPressed()) {
			cfg.minimapSize = Math.max(cfg.minimapSize - 16, MapConfig.MIN_SIZE);
			changed = true;
		}
		while (MapConfig.toggleEntitiesKey.isPressed()) {
			cfg.showEntities = !cfg.showEntities;
			changed = true;
		}
		while (MapConfig.toggleMinimapKey.isPressed()) {
			cfg.minimapEnabled = !cfg.minimapEnabled;
			changed = true;
		}
		if (changed)
			cfg.saveConfig();

		// Screens. Opening one releases all keys, so at most one of these fires per tick.
		if (MapConfig.waypointGuiKey.isPressed()) {
			mc.displayGuiScreen(new GuiWaypoints());
		} else if (MapConfig.fullscreenMapKey.isPressed()) {
			mc.displayGuiScreen(new GuiFullscreenMap());
		} else if (MapConfig.settingsKey.isPressed()) {
			mc.displayGuiScreen(new GuiMapSettings(null));
		} else if (MapConfig.quickWaypointKey.isPressed()) {
			GuiWaypoints.openNewWaypointAtPlayer(mc, null, null, mc.theWorld.provider.dimensionId);
		}
	}

	@Inject(at = @At("HEAD"), method = "shutdown")
	private void onShutdown(CallbackInfo ci) {
		try {
			MapConfig.instance.saveConfig();
			WaypointManager.instance.save();
		} catch (Throwable ignored) {
		}
	}

	/** Runs once on every exit path, before the world is torn down. */
	@Inject(at = @At("HEAD"), method = "shutdownMinecraftApplet")
	private void onShutdownApplet(CallbackInfo ci) {
		try {
			WaypointManager.instance.save();
		} catch (Throwable ignored) {
		}
		try {
			MapEngine.INSTANCE.shutdown();
		} catch (Throwable t) {
			System.err.println("[Minimap CE] Could not save the map on exit: " + t);
		}
	}
}
