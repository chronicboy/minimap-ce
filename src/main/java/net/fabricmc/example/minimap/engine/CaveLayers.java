package net.fabricmc.example.minimap.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;

/**
 * Saved cave maps of one world, one map per 16-block height band ("layer").
 *
 * The live cave view samples a slice at the player's height; every slice is
 * also merged into the layer it belongs to, so caves you have walked through
 * stay on the fullscreen map afterwards, like the surface does. Floors always
 * win over walls when merging, because a slice taken a few blocks higher or
 * lower in the same band may see rock where the band has a known floor.
 *
 * Files: {@code <world map folder>/caves/y<bottom of band>/r.X.Z.mmr}, the same
 * format as the surface map. Main thread only.
 */
public final class CaveLayers {
	public static final int LAYER_HEIGHT = 16;
	private static final long CLOSE_AFTER_MS = 60000L;

	private final File baseDir;
	private final HashMap<Integer, MapStorage> open = new HashMap<Integer, MapStorage>();
	private final HashMap<Integer, Long> lastUsed = new HashMap<Integer, Long>();
	/** Slices that arrived while their region was still loading from disk. */
	private final HashMap<Integer, HashMap<Long, int[]>> pending = new HashMap<Integer, HashMap<Long, int[]>>();
	private final TreeSet<Integer> known = new TreeSet<Integer>();
	private final int[] merged = new int[256];
	private boolean closed;

	public CaveLayers(File baseDir) {
		this.baseDir = baseDir;
		String[] names = baseDir.list();
		if (names != null) {
			for (String name : names) {
				if (!name.startsWith("y"))
					continue;
				try {
					int bottom = Integer.parseInt(name.substring(1));
					if (Math.floorMod(bottom, LAYER_HEIGHT) == 0)
						known.add(bottom / LAYER_HEIGHT);
				} catch (NumberFormatException ignored) {
				}
			}
		}
	}

	public static int layerOf(int y) {
		return Math.floorDiv(y, LAYER_HEIGHT);
	}

	public static int bottomOf(int layer) {
		return layer * LAYER_HEIGHT;
	}

	/** Layers that have any saved data, lowest first. */
	public int[] knownLayers() {
		int[] out = new int[known.size()];
		int i = 0;
		for (Integer layer : known)
			out[i++] = layer;
		return out;
	}

	/** The map of a layer, opened on first use. Null once closed. */
	public MapStorage layer(int layer) {
		if (closed)
			return null;
		MapStorage storage = open.get(layer);
		if (storage == null) {
			storage = new MapStorage(new File(baseDir, "y" + bottomOf(layer)), null);
			open.put(layer, storage);
		}
		lastUsed.put(layer, System.currentTimeMillis());
		return storage;
	}

	/** Merges a freshly sampled cave slice into its layer. */
	public void record(int chunkX, int chunkZ, int refY, int[] slice) {
		if (closed)
			return;
		int layer = layerOf(refY);
		known.add(layer);
		if (!merge(layer, chunkX, chunkZ, slice)) {
			HashMap<Long, int[]> waiting = pending.get(layer);
			if (waiting == null) {
				waiting = new HashMap<Long, int[]>();
				pending.put(layer, waiting);
			}
			waiting.put(MapStorage.key(chunkX, chunkZ), slice.clone());
		}
	}

	/** @return false if the region is still loading */
	private boolean merge(int layer, int chunkX, int chunkZ, int[] slice) {
		MapStorage storage = layer(layer);
		MapRegion region = storage.request(chunkX >> 5, chunkZ >> 5, true);
		if (region == null)
			return true;
		if (!region.isReady())
			return false;
		int[] old = region.chunk(chunkX, chunkZ);
		for (int i = 0; i < 256; i++) {
			int now = slice[i];
			int before = old != null ? old[i] : 0;
			if (now == 0 || (MapShade.height(now) == 0 && before != 0))
				merged[i] = before; // nothing new, or a wall where something is already known
			else
				merged[i] = now;
		}
		if (old == null || !Arrays.equals(old, merged))
			storage.putChunk(chunkX, chunkZ, merged);
		return true;
	}

	/** Call every client tick: finishes waiting merges, saves and closes layers nobody looked at for a while. */
	public void tick() {
		if (closed)
			return;
		if (!pending.isEmpty()) {
			Iterator<Map.Entry<Integer, HashMap<Long, int[]>>> layers = pending.entrySet().iterator();
			while (layers.hasNext()) {
				Map.Entry<Integer, HashMap<Long, int[]>> entry = layers.next();
				Iterator<Map.Entry<Long, int[]>> it = entry.getValue().entrySet().iterator();
				while (it.hasNext()) {
					Map.Entry<Long, int[]> e = it.next();
					long k = e.getKey();
					if (merge(entry.getKey(), (int) (k >> 32), (int) k, e.getValue()))
						it.remove();
				}
				if (entry.getValue().isEmpty())
					layers.remove();
			}
		}
		long now = System.currentTimeMillis();
		ArrayList<Integer> idle = null;
		for (Map.Entry<Integer, MapStorage> e : open.entrySet()) {
			e.getValue().tick();
			Long used = lastUsed.get(e.getKey());
			if (!pending.containsKey(e.getKey()) && (used == null || now - used > CLOSE_AFTER_MS)) {
				if (idle == null)
					idle = new ArrayList<Integer>();
				idle.add(e.getKey());
			}
		}
		if (idle != null) {
			for (Integer layer : idle) {
				open.remove(layer).close();
				lastUsed.remove(layer);
			}
		}
	}

	/** Saves everything (in the background) and releases all textures. */
	public void close() {
		if (closed)
			return;
		tick(); // last chance for waiting merges whose regions have arrived
		closed = true;
		for (MapStorage storage : open.values())
			storage.close();
		open.clear();
		pending.clear();
	}
}
