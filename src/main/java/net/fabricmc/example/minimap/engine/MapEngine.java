package net.fabricmc.example.minimap.engine;

import net.fabricmc.example.minimap.WaypointManager;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.Minecraft;
import net.minecraft.src.World;
import org.lwjgl.opengl.GL11;

import java.io.File;

/**
 * Owns the map of the world the player is currently in and drives its
 * background work: listening for chunk changes, sampling them within a small
 * per-frame time budget, and saving in the background.
 */
public final class MapEngine {
	public static final MapEngine INSTANCE = new MapEngine();

	private static final String MAP_DIR = "config/minimap-ce-maps-v4";
	/** Written by Minimap CE 3.x. Imported lazily, never modified. */
	private static final String LEGACY_DIR = "config/minimap-ce-maps-v3";

	private static final long FRAME_BUDGET_NANOS = 1200000L;
	private static final long TICK_BUDGET_NANOS = 3000000L;
	private static final int INITIAL_SCAN_RADIUS = 16;

	private World world;
	private String worldId = "";
	private MapStorage storage;
	private MapUpdater updater;
	private int framesSinceTick;
	private int colorGeneration = 0;
	private int tickCounter;

	private MapEngine() {
	}

	/** Map of the current world, or null when not in a world. */
	public MapStorage storage() {
		return storage;
	}

	public String worldId() {
		return worldId;
	}

	public int pendingChunks() {
		return updater == null ? 0 : updater.pending();
	}

	/** Call at the start of every client tick. */
	public void tick(Minecraft mc) {
		World w = mc.theWorld;
		EntityPlayer player = mc.thePlayer;
		if (w == null || player == null) {
			if (storage != null || world != null)
				leaveWorld();
			return;
		}
		if (w != world)
			enterWorld(w, player);
		if (storage == null || updater == null)
			return;

		int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		try {
			storage.tick();
			updater.tick(player);

			int generation = BlockColors.generation();
			if (generation != colorGeneration) {
				// The resource pack changed: resample what is loaded around the player.
				// (The very first build needs nothing extra, entering the world already queued a scan.)
				if (colorGeneration > 0)
					updater.markAround(chunkCoord(player.posX), chunkCoord(player.posZ), INITIAL_SCAN_RADIUS);
				colorGeneration = generation;
			}

			// Normally sampling happens once per rendered frame; this keeps the map
			// updating when the HUD is not being drawn (F1).
			if (framesSinceTick == 0)
				updater.process(player, TICK_BUDGET_NANOS);
			framesSinceTick = 0;

			if ((++tickCounter & 255) == 0)
				updater.prune(player);
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
		}
	}

	/**
	 * Call once per rendered frame (with a valid GL context) before drawing the map.
	 * The caller is responsible for restoring the bound texture afterwards.
	 */
	public void frame(Minecraft mc) {
		if (storage == null || updater == null || mc.thePlayer == null)
			return;
		framesSinceTick++;
		storage.beginFrame();
		updater.process(mc.thePlayer, FRAME_BUDGET_NANOS);
	}

	private void enterWorld(World w, EntityPlayer player) {
		String id = WaypointManager.buildWorldId(w);
		if (id.isEmpty())
			return; // world not fully set up yet, try again next tick

		if (updater != null) {
			updater.deactivate();
			updater = null;
		}
		if (storage == null || !id.equals(worldId)) {
			if (storage != null)
				storage.close();
			worldId = id;
			storage = new MapStorage(new File(MAP_DIR, id), new File(LEGACY_DIR, id));
			System.out.println("[Minimap CE] Map storage opened for " + id);
		}
		world = w;

		int dim = w.provider.dimensionId;
		boolean caveMode = dim == -1 || (w.provider.hasNoSky && dim != 1);
		updater = new MapUpdater(w, storage, caveMode);
		w.addWorldAccess(updater);
		// Chunks that arrived before the listener was attached.
		updater.markAround(chunkCoord(player.posX), chunkCoord(player.posZ), INITIAL_SCAN_RADIUS);
	}

	private void leaveWorld() {
		if (updater != null) {
			updater.deactivate();
			updater = null;
		}
		if (storage != null) {
			storage.close();
			storage = null;
		}
		world = null;
		worldId = "";
	}

	/** Saves everything and waits (bounded) for the disk. Call when the game exits. */
	public void shutdown() {
		try {
			leaveWorld();
		} catch (Throwable ignored) {
		}
		MapIO.flush(8000);
	}

	/**
	 * Opens the stored map of another dimension of the current world for viewing.
	 * The caller owns the returned storage and must tick and close it.
	 *
	 * @return a new storage, or null when not in a world
	 */
	public MapStorage openDimension(int dimension) {
		if (worldId.isEmpty())
			return null;
		int idx = worldId.lastIndexOf("_dim");
		if (idx < 0)
			return null;
		String id = worldId.substring(0, idx) + "_dim" + dimension;
		return new MapStorage(new File(MAP_DIR, id), new File(LEGACY_DIR, id));
	}

	private static int chunkCoord(double pos) {
		return ((int) Math.floor(pos)) >> 4;
	}
}
