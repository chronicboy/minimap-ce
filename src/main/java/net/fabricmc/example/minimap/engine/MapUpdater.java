package net.fabricmc.example.minimap.engine;

import net.minecraft.src.Chunk;
import net.minecraft.src.Entity;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.IWorldAccess;
import net.minecraft.src.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps the map of one client world up to date. Registered as a world listener,
 * so it hears about exactly the chunks that were loaded or changed and samples
 * only those, a few per frame within a time budget, nearest to the player first.
 */
public final class MapUpdater implements IWorldAccess {
	private static final int PICK = 12;
	private static final int BLOCK_CHANGE_DELAY_TICKS = 3;
	private static final int CHUNK_LOAD_DELAY_TICKS = 1;
	private static final int RETRY_DELAY_TICKS = 2;
	private static final int CAVE_REFRESH_RADIUS = 5;
	private static final int CAVE_REFRESH_STEP = 4;

	private final World world;
	private final MapStorage storage;
	private final boolean caveMode;

	/** Chunk key -> tick at which the chunk may be sampled. */
	private final HashMap<Long, Integer> dirty = new HashMap<Long, Integer>();
	private final int[] scratch = new int[256];
	private final Long[] pickKeys = new Long[PICK];
	private final int[] pickDist = new int[PICK];

	private int tick;
	private int caveRefY = Integer.MIN_VALUE;
	private boolean active = true;

	public MapUpdater(World world, MapStorage storage, boolean caveMode) {
		this.world = world;
		this.storage = storage;
		this.caveMode = caveMode;
	}

	public World world() {
		return world;
	}

	public void deactivate() {
		active = false;
		dirty.clear();
	}

	public int pending() {
		return dirty.size();
	}

	private static Long chunkKey(int cx, int cz) {
		return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
	}

	private void mark(int cx, int cz, int delay) {
		Long key = chunkKey(cx, cz);
		if (!dirty.containsKey(key))
			dirty.put(key, tick + delay);
	}

	/** Queues every chunk in a square around a chunk position (unloaded ones are dropped when reached). */
	public void markAround(int centerChunkX, int centerChunkZ, int radius) {
		if (!active)
			return;
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++)
				mark(centerChunkX + dx, centerChunkZ + dz, 0);
		}
	}

	/** Called once per client tick. */
	public void tick(EntityPlayer player) {
		tick++;
		if (caveMode && player != null && active) {
			int y = (int) Math.floor(player.posY);
			if (caveRefY == Integer.MIN_VALUE) {
				caveRefY = y;
			} else if (Math.abs(y - caveRefY) >= CAVE_REFRESH_STEP) {
				caveRefY = y;
				markAround(((int) Math.floor(player.posX)) >> 4, ((int) Math.floor(player.posZ)) >> 4,
						CAVE_REFRESH_RADIUS);
			}
		}
	}

	/**
	 * Samples queued chunks until the time budget is used up.
	 *
	 * @return number of chunks sampled
	 */
	public int process(EntityPlayer player, long budgetNanos) {
		if (!active || dirty.isEmpty() || player == null)
			return 0;
		if (!BlockColors.ensureReady())
			return 0;

		int pcx = ((int) Math.floor(player.posX)) >> 4;
		int pcz = ((int) Math.floor(player.posZ)) >> 4;

		// One pass: the PICK nearest chunks that are due.
		int picked = 0;
		for (Map.Entry<Long, Integer> e : dirty.entrySet()) {
			if (e.getValue() > tick)
				continue;
			long k = e.getKey();
			int dx = (int) (k >> 32) - pcx;
			int dz = (int) k - pcz;
			int dist = dx * dx + dz * dz;
			if (picked == PICK && dist >= pickDist[PICK - 1])
				continue;
			int i = picked < PICK ? picked++ : PICK - 1;
			while (i > 0 && pickDist[i - 1] > dist) {
				pickDist[i] = pickDist[i - 1];
				pickKeys[i] = pickKeys[i - 1];
				i--;
			}
			pickDist[i] = dist;
			pickKeys[i] = e.getKey();
		}
		if (picked == 0)
			return 0;

		long start = System.nanoTime();
		int refY = caveRefY == Integer.MIN_VALUE ? (int) Math.floor(player.posY) : caveRefY;
		int sampled = 0;
		for (int i = 0; i < picked; i++) {
			Long key = pickKeys[i];
			pickKeys[i] = null;
			long k = key;
			int cx = (int) (k >> 32);
			int cz = (int) k;

			Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
			if (chunk == null || chunk.isEmpty()) {
				dirty.remove(key);
				continue;
			}
			MapRegion region = storage.request(cx >> 5, cz >> 5, true);
			if (region == null) {
				dirty.remove(key);
				continue;
			}
			if (!region.isReady()) {
				// Region file still loading: look at this chunk again shortly.
				dirty.put(key, tick + RETRY_DELAY_TICKS);
				continue;
			}
			dirty.remove(key);

			int found = caveMode
					? ChunkSampler.sampleCave(chunk, world, scratch, region.chunk(cx, cz), refY)
					: ChunkSampler.sampleSurface(chunk, world, scratch);
			if (found > 0)
				storage.putChunk(cx, cz, scratch);
			sampled++;

			if (System.nanoTime() - start > budgetNanos) {
				for (int j = i + 1; j < picked; j++)
					pickKeys[j] = null;
				break;
			}
		}
		return sampled;
	}

	/** Drops queued chunks that are far outside any sensible view distance. */
	public void prune(EntityPlayer player) {
		if (dirty.size() < 4096 || player == null)
			return;
		int pcx = ((int) Math.floor(player.posX)) >> 4;
		int pcz = ((int) Math.floor(player.posZ)) >> 4;
		Iterator<Map.Entry<Long, Integer>> it = dirty.entrySet().iterator();
		while (it.hasNext()) {
			long k = it.next().getKey();
			if (Math.abs((int) (k >> 32) - pcx) > 40 || Math.abs((int) k - pcz) > 40)
				it.remove();
		}
	}

	// ------------------------------------------------------------ IWorldAccess

	@Override
	public void markBlockForUpdate(int x, int y, int z) {
		if (active)
			mark(x >> 4, z >> 4, BLOCK_CHANGE_DELAY_TICKS);
	}

	@Override
	public void markBlockForRenderUpdate(int x, int y, int z) {
		// Lighting only: the map does not depend on light.
	}

	@Override
	public void markBlockRangeForRenderUpdate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		if (!active)
			return;
		int cx0 = minX >> 4, cx1 = maxX >> 4, cz0 = minZ >> 4, cz1 = maxZ >> 4;
		if (cx1 < cx0 || cz1 < cz0 || (long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > 1024)
			return;
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++)
				mark(cx, cz, CHUNK_LOAD_DELAY_TICKS);
		}
	}

	@Override
	public void playSound(String name, double x, double y, double z, float volume, float pitch) {
	}

	@Override
	public void playSoundToNearExcept(EntityPlayer player, String name, double x, double y, double z, float volume,
			float pitch) {
	}

	@Override
	public void spawnParticle(String name, double x, double y, double z, double vx, double vy, double vz) {
	}

	@Override
	public void onEntityCreate(Entity entity) {
	}

	@Override
	public void onEntityDestroy(Entity entity) {
	}

	@Override
	public void playRecord(String name, int x, int y, int z) {
	}

	@Override
	public void broadcastSound(int a, int b, int c, int d, int e) {
	}

	@Override
	public void playAuxSFX(EntityPlayer player, int a, int b, int c, int d, int e) {
	}

	@Override
	public void destroyBlockPartially(int a, int b, int c, int d, int e) {
	}
}
