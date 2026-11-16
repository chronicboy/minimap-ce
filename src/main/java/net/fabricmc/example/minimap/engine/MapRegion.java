package net.fabricmc.example.minimap.engine;

/**
 * One 512x512 block area of the map: its tile data and (lazily) its texture.
 * All fields are owned by the main thread.
 */
public final class MapRegion {
	static final int LOADING = 0;
	static final int READY = 1;

	public final int rx;
	public final int rz;
	final Long key;

	int state = LOADING;
	/** Per-chunk tile arrays, index = localZ * 32 + localX. Arrays are never mutated after being stored. */
	int[][] chunks;
	boolean hasData;
	boolean dirty;
	long dirtySince;
	long lastUsed;
	int textureId = -1;
	boolean mipDirty;
	/** Shaded image prepared by the loader thread; dropped as soon as the data changes. */
	int[] pendingPixels;

	MapRegion(int rx, int rz, Long key) {
		this.rx = rx;
		this.rz = rz;
		this.key = key;
	}

	public boolean isReady() {
		return state == READY;
	}

	public boolean hasData() {
		return hasData;
	}

	/** Tile array of a chunk (world chunk coordinates), or null. Treat as read-only. */
	public int[] chunk(int chunkX, int chunkZ) {
		return chunks == null ? null : chunks[((chunkZ & 31) << 5) | (chunkX & 31)];
	}
}
