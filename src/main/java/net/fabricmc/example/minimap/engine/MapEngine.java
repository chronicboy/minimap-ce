package net.fabricmc.example.minimap.engine;

import net.fabricmc.example.minimap.MapConfig;
import net.fabricmc.example.minimap.WaypointManager;
import net.minecraft.src.Block;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.Material;
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
	private static final int RESCAN_RADIUS = 24;
	/** Ticks after entering a world at which everything around the player is queued once more. */
	private static final int FIRST_RESCAN_TICK = 40;
	private static final int SECOND_RESCAN_TICK = 200;

	/** Ticks between underground checks, and how many checks in a row must agree before switching. */
	private static final int CAVE_CHECK_INTERVAL = 5;
	private static final int CAVE_SWITCH_CHECKS = 3;
	private static final long CAVE_FRAME_BUDGET_NANOS = 900000L;
	private static final long SURFACE_FRAME_BUDGET_WHILE_CAVE_NANOS = 400000L;

	private World world;
	private String worldId = "";
	private MapStorage storage;
	private MapUpdater updater;

	// Live cave view (Overworld and other dimensions with a sky): memory only, rebuilt as the player moves.
	private MapStorage caveStorage;
	private MapUpdater caveUpdater;
	private boolean caveActive;
	private int caveVotes;
	private int framesSinceTick;
	private int colorGeneration = 0;
	private int tickCounter;
	private int ticksInWorld;

	private MapEngine() {
	}

	/** Map of the current world, or null when not in a world. */
	public MapStorage storage() {
		return storage;
	}

	/** What the minimap shows right now: the live cave slice when underground, otherwise the surface. */
	public MapStorage minimapStorage() {
		return caveActive && caveStorage != null ? caveStorage : storage;
	}

	public boolean isCaveViewActive() {
		return caveActive && caveStorage != null;
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
			if (caveUpdater != null) {
				updateCaveMode(w, player);
				caveStorage.tick();
				caveUpdater.tick(player);
			}

			// Chunks can arrive in the same network batch as the login, before the listener exists and
			// before the player's real position is known. Two late scans catch whatever that missed;
			// chunks that are already up to date cost a comparison and nothing else.
			ticksInWorld++;
			if (ticksInWorld == FIRST_RESCAN_TICK || ticksInWorld == SECOND_RESCAN_TICK)
				updater.markAround(chunkCoord(player.posX), chunkCoord(player.posZ), RESCAN_RADIUS);

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
			if (framesSinceTick == 0) {
				if (caveActive && caveUpdater != null)
					caveUpdater.process(player, TICK_BUDGET_NANOS);
				updater.process(player, TICK_BUDGET_NANOS);
			}
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
		if (caveActive && caveUpdater != null) {
			// The cave view is what is on screen: it goes first, the surface map keeps trickling in.
			caveStorage.beginFrame();
			caveUpdater.process(mc.thePlayer, CAVE_FRAME_BUDGET_NANOS);
			updater.process(mc.thePlayer, SURFACE_FRAME_BUDGET_WHILE_CAVE_NANOS);
		} else {
			updater.process(mc.thePlayer, FRAME_BUDGET_NANOS);
		}
	}

	private void updateCaveMode(World w, EntityPlayer player) {
		if (!MapConfig.instance.caveMode) {
			setCaveActive(false, player);
			caveVotes = 0;
			return;
		}
		if (ticksInWorld % CAVE_CHECK_INTERVAL != 0)
			return;
		boolean underground = isUnderground(w, player);
		if (underground == caveActive) {
			caveVotes = 0;
		} else if (++caveVotes >= CAVE_SWITCH_CHECKS) {
			caveVotes = 0;
			setCaveActive(underground, player);
		}
	}

	private void setCaveActive(boolean on, EntityPlayer player) {
		if (on == caveActive)
			return;
		caveActive = on;
		if (on) {
			// Start from a clean slate: an old slice may be from another cave at another height.
			caveStorage.close();
			caveStorage = MapStorage.memoryOnly();
			caveUpdater.setStorage(caveStorage);
		}
		caveUpdater.setEnabled(on, player);
	}

	/**
	 * Underground means a real roof: at least three opaque blocks (not leaves or wood, so trees and
	 * plank roofs do not count) somewhere above the head, in most of five columns around the player.
	 */
	private static boolean isUnderground(World w, EntityPlayer player) {
		int x = (int) Math.floor(player.posX);
		int z = (int) Math.floor(player.posZ);
		int head = (int) Math.floor(player.boundingBox.minY) + 2;
		if (head >= 255)
			return false;
		int covered = 0;
		int[][] offsets = { { 0, 0 }, { 3, 0 }, { -3, 0 }, { 0, 3 }, { 0, -3 } };
		for (int[] o : offsets) {
			if (columnCovered(w, x + o[0], head, z + o[1]))
				covered++;
		}
		return covered >= 3;
	}

	private static boolean columnCovered(World w, int x, int fromY, int z) {
		int top = Math.min(255, w.getHeightValue(x, z) + 1);
		if (top <= fromY)
			return false; // open sky straight above
		int solid = 0;
		for (int y = fromY; y <= top; y++) {
			int id = w.getBlockId(x, y, z);
			if (id <= 0 || id >= 4096 || !Block.opaqueCubeLookup[id])
				continue;
			Block b = Block.blocksList[id];
			if (b == null || b.blockMaterial == Material.leaves || b.blockMaterial == Material.wood)
				continue;
			if (++solid >= 3)
				return true;
		}
		return false;
	}

	private void enterWorld(World w, EntityPlayer player) {
		String id = WaypointManager.buildWorldId(w);
		if (id.isEmpty())
			return; // world not fully set up yet, try again next tick

		if (updater != null) {
			updater.deactivate();
			if (updater.world() != null)
				updater.world().removeWorldAccess(updater);
			updater = null;
		}
		closeCaveView();
		if (storage == null || !id.equals(worldId)) {
			if (storage != null)
				storage.close();
			worldId = id;
			storage = new MapStorage(new File(MAP_DIR, id), new File(LEGACY_DIR, id));
			System.out.println("[Minimap CE] Map storage opened for " + id);
		}
		world = w;
		ticksInWorld = 0;

		int dim = w.provider.dimensionId;
		boolean caveMode = dim == -1 || (w.provider.hasNoSky && dim != 1);
		updater = new MapUpdater(w, storage, caveMode);
		w.addWorldAccess(updater);
		// Chunks that arrived before the listener was attached.
		updater.markAround(chunkCoord(player.posX), chunkCoord(player.posZ), INITIAL_SCAN_RADIUS);

		if (!caveMode && !w.provider.hasNoSky) {
			caveStorage = MapStorage.memoryOnly();
			caveUpdater = new MapUpdater(w, caveStorage, true, false);
			caveUpdater.setEnabled(false, player);
			w.addWorldAccess(caveUpdater);
		}
	}

	private void closeCaveView() {
		if (caveUpdater != null) {
			caveUpdater.deactivate();
			if (caveUpdater.world() != null)
				caveUpdater.world().removeWorldAccess(caveUpdater);
			caveUpdater = null;
		}
		if (caveStorage != null) {
			caveStorage.close();
			caveStorage = null;
		}
		caveActive = false;
		caveVotes = 0;
	}

	private void leaveWorld() {
		if (updater != null) {
			updater.deactivate();
			updater = null;
		}
		closeCaveView();
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
