package net.fabricmc.example.minimap.engine;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.io.File;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The explored map of one world/dimension: region tile data in memory, region
 * textures on the GPU and region files on disk.
 *
 * Everything public here must be called from the main (render) thread. Disk
 * access happens on the {@link MapIO} thread; results come back through a
 * queue that {@link #tick()} drains.
 */
public final class MapStorage {
	private static final int SIZE = RegionCodec.REGION_SIZE;
	private static final long UNLOAD_AFTER_MS = 20000L;
	private static final long AUTOSAVE_AFTER_MS = 12000L;
	private static final long MAINTENANCE_INTERVAL_MS = 2000L;
	private static final long LOD_UNLOAD_AFTER_MS = 30000L;
	private static final int MAX_REGIONS = 96;
	private static final int MAX_LOD_TILES = 1024;
	private static final int TEXTURES_PER_FRAME = 2;
	private static final int LOD_UPLOADS_PER_FRAME = 8;

	private static IntBuffer scratch;
	private static int[] buildBuffer;
	private static int mipMode = -1; // 0 none, 1 GL30, 2 EXT_framebuffer_object

	private static final class LodTile {
		int textureId = -1;
		int[] pixels;
		long lastUsed;
		boolean stale = true;
		boolean loading;
	}

	private final File dir;
	private final File legacyDir;
	private final HashMap<Long, MapRegion> regions = new HashMap<Long, MapRegion>();
	private final HashSet<Long> onDisk = new HashSet<Long>();
	private final HashSet<Long> legacyOnDisk = new HashSet<Long>();
	private final HashMap<Long, LodTile> lods = new HashMap<Long, LodTile>();
	private final ConcurrentLinkedQueue<Runnable> completed = new ConcurrentLinkedQueue<Runnable>();

	private boolean closed;
	private long now = System.currentTimeMillis();
	private long lastMaintenance = now;
	private int textureBudget = TEXTURES_PER_FRAME;
	private int lodBudget = LOD_UPLOADS_PER_FRAME;

	private boolean cacheValid;
	private int cacheRx, cacheRz;
	private MapRegion cacheRegion;

	/**
	 * @param dir       directory for this world's region files (created on first save)
	 * @param legacyDir directory with Minimap CE 3.x region files to import from, or null.
	 *                  Legacy files are only ever read, never modified.
	 */
	public MapStorage(File dir, File legacyDir) {
		this.dir = dir;
		this.legacyDir = legacyDir;
		scan(dir, ".mmr", onDisk);
		if (legacyDir != null)
			scan(legacyDir, ".dat", legacyOnDisk);
		legacyOnDisk.removeAll(onDisk);
	}

	private static void scan(File directory, String extension, HashSet<Long> out) {
		String[] names = directory.list();
		if (names == null)
			return;
		for (String name : names) {
			if (!name.startsWith("r.") || !name.endsWith(extension))
				continue;
			String[] parts = name.substring(2, name.length() - extension.length()).split("\\.");
			if (parts.length != 2)
				continue;
			try {
				out.add(key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
			} catch (NumberFormatException ignored) {
			}
		}
	}

	static Long key(int rx, int rz) {
		return (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
	}

	private File regionFile(int rx, int rz) {
		return new File(dir, "r." + rx + "." + rz + ".mmr");
	}

	private File legacyFile(int rx, int rz) {
		return legacyDir == null ? null : new File(legacyDir, "r." + rx + "." + rz + ".dat");
	}

	// ------------------------------------------------------------ frame / tick

	/** Call once per rendered frame before drawing from this storage. */
	public void beginFrame() {
		now = System.currentTimeMillis();
		textureBudget = TEXTURES_PER_FRAME;
		lodBudget = LOD_UPLOADS_PER_FRAME;
	}

	/** Applies finished disk work and does periodic saving/unloading. May touch GL textures. */
	public void tick() {
		now = System.currentTimeMillis();
		Runnable task;
		while ((task = completed.poll()) != null)
			task.run();
		if (closed)
			return;
		if (now - lastMaintenance >= MAINTENANCE_INTERVAL_MS) {
			lastMaintenance = now;
			maintain();
		}
	}

	private void maintain() {
		ArrayList<MapRegion> unload = null;
		for (MapRegion r : regions.values()) {
			if (r.state != MapRegion.READY)
				continue;
			if (now - r.lastUsed > UNLOAD_AFTER_MS) {
				if (unload == null)
					unload = new ArrayList<MapRegion>();
				unload.add(r);
			} else if (r.dirty && now - r.dirtySince > AUTOSAVE_AFTER_MS) {
				submitSave(r, false);
			}
		}
		if (unload != null) {
			for (MapRegion r : unload)
				unload(r);
		}
		// Hard cap: drop the least recently used regions that are not on screen right now.
		while (regions.size() > MAX_REGIONS) {
			MapRegion oldest = null;
			for (MapRegion r : regions.values()) {
				if (r.state != MapRegion.READY || now - r.lastUsed < 1500)
					continue;
				if (oldest == null || r.lastUsed < oldest.lastUsed)
					oldest = r;
			}
			if (oldest == null)
				break;
			unload(oldest);
		}

		if (!lods.isEmpty()) {
			boolean over = lods.size() > MAX_LOD_TILES;
			Iterator<Map.Entry<Long, LodTile>> it = lods.entrySet().iterator();
			while (it.hasNext()) {
				LodTile t = it.next().getValue();
				if (t.loading)
					continue;
				long idle = now - t.lastUsed;
				if (idle > LOD_UNLOAD_AFTER_MS || (over && idle > 3000)) {
					if (t.textureId != -1)
						GL11.glDeleteTextures(t.textureId);
					it.remove();
				}
			}
		}
	}

	private void unload(MapRegion r) {
		if (r.dirty)
			submitSave(r, true);
		if (r.textureId != -1) {
			GL11.glDeleteTextures(r.textureId);
			r.textureId = -1;
		}
		regions.remove(r.key);
		cacheValid = false;
	}

	// ------------------------------------------------------------ region access

	/** The region if it is currently in memory (possibly still loading), else null. Never triggers a load. */
	public MapRegion loaded(int rx, int rz) {
		if (cacheValid && cacheRx == rx && cacheRz == rz)
			return cacheRegion;
		MapRegion r = regions.get(key(rx, rz));
		cacheRegion = r;
		cacheRx = rx;
		cacheRz = rz;
		cacheValid = true;
		return r;
	}

	/**
	 * Returns the region, starting a background load if it exists on disk.
	 *
	 * @param create when true an empty region is created if nothing exists yet
	 * @return the region (check {@link MapRegion#isReady()}), or null if there is no such region
	 */
	public MapRegion request(int rx, int rz, boolean create) {
		MapRegion r = loaded(rx, rz);
		if (r != null) {
			r.lastUsed = now;
			return r;
		}
		if (closed)
			return null;
		Long k = key(rx, rz);
		boolean file = onDisk.contains(k) || legacyOnDisk.contains(k);
		if (!file && !create)
			return null;
		r = new MapRegion(rx, rz, k);
		r.lastUsed = now;
		regions.put(k, r);
		cacheValid = false;
		if (file) {
			submitLoad(r);
		} else {
			r.chunks = new int[RegionCodec.CHUNK_COUNT][];
			r.state = MapRegion.READY;
		}
		return r;
	}

	/** True if any map data exists for the region (in memory or on disk). */
	public boolean exists(int rx, int rz) {
		MapRegion r = loaded(rx, rz);
		if (r != null && r.hasData)
			return true;
		Long k = key(rx, rz);
		return onDisk.contains(k) || legacyOnDisk.contains(k);
	}

	/** Raw tile value at world block coordinates from regions that are in memory; 0 when unknown. */
	public int dataAt(int worldX, int worldZ) {
		MapRegion r = loaded(worldX >> 9, worldZ >> 9);
		if (r == null || r.chunks == null)
			return 0;
		int[] c = r.chunks[(((worldZ >> 4) & 31) << 5) | ((worldX >> 4) & 31)];
		return c == null ? 0 : c[((worldZ & 15) << 4) | (worldX & 15)];
	}

	/**
	 * Stores freshly sampled tile values for a chunk and updates the texture.
	 *
	 * @return false when the chunk's region is still loading (try again later)
	 */
	public boolean putChunk(int chunkX, int chunkZ, int[] data) {
		MapRegion r = request(chunkX >> 5, chunkZ >> 5, true);
		if (r == null || r.state != MapRegion.READY)
			return false;
		int index = ((chunkZ & 31) << 5) | (chunkX & 31);
		int[] old = r.chunks[index];
		if (old != null && Arrays.equals(old, data))
			return true;
		r.chunks[index] = data.clone();
		r.hasData = true;
		r.pendingPixels = null;
		if (!r.dirty) {
			r.dirty = true;
			r.dirtySince = now;
		}
		// One extra row/column: the neighbours to the south and east shade against this chunk.
		uploadRect(chunkX << 4, chunkZ << 4, 17, 17);
		return true;
	}

	// ----------------------------------------------------------------- textures

	/**
	 * GL texture of a region, creating it if needed (a limited number per frame).
	 *
	 * @return texture id, or -1 if there is nothing to draw (yet)
	 */
	public int texture(MapRegion r) {
		if (r == null || r.state != MapRegion.READY || !r.hasData)
			return -1;
		r.lastUsed = now;
		if (r.textureId == -1) {
			if (textureBudget <= 0)
				return -1;
			textureBudget--;
			createTexture(r);
		}
		if (r.mipDirty) {
			r.mipDirty = false;
			if (mipMode() != 0) {
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, r.textureId);
				generateMipmap();
			}
		}
		return r.textureId;
	}

	private void createTexture(MapRegion r) {
		IntBuffer buf = scratch();
		buf.clear();
		boolean fromLoader = r.pendingPixels != null;
		if (fromLoader) {
			buf.put(r.pendingPixels);
			r.pendingPixels = null;
		} else {
			buf.put(buildPixels(r));
		}
		buf.flip();

		int id = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
		setupTextureParameters();
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL12.GL_BGRA,
				GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
		r.textureId = id;
		r.mipDirty = true;

		if (fromLoader) {
			// The loader could not see neighbouring regions: redo the two edges that shade across the border.
			int x0 = r.rx << 9, z0 = r.rz << 9;
			uploadRect(x0, z0, SIZE, 1);
			uploadRect(x0, z0, 1, SIZE);
		}
	}

	private static void setupTextureParameters() {
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
				mipMode() != 0 ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
	}

	/** Full shaded image of a region, with correct shading across region borders. */
	private int[] buildPixels(MapRegion r) {
		if (buildBuffer == null)
			buildBuffer = new int[SIZE * SIZE];
		int[] out = buildBuffer;
		Arrays.fill(out, 0);
		int[][] chunks = r.chunks;
		int originX = r.rx << 9, originZ = r.rz << 9;
		for (int ci = 0; ci < RegionCodec.CHUNK_COUNT; ci++) {
			int[] c = chunks[ci];
			if (c == null)
				continue;
			int bx = (ci & 31) << 4;
			int bz = (ci >> 5) << 4;
			for (int lz = 0; lz < 16; lz++) {
				int row = (bz + lz) * SIZE + bx;
				for (int lx = 0; lx < 16; lx++) {
					int d = c[(lz << 4) | lx];
					if (d == 0)
						continue;
					int north = lz > 0 ? c[((lz - 1) << 4) | lx] : dataAt(originX + bx + lx, originZ + bz - 1);
					int west = lx > 0 ? c[(lz << 4) | (lx - 1)] : dataAt(originX + bx - 1, originZ + bz + lz);
					out[row + lx] = MapShade.shade(d, north, west);
				}
			}
		}
		return out;
	}

	/**
	 * Re-shades a rectangle of world block columns and uploads it to whichever
	 * loaded region textures it touches.
	 */
	private void uploadRect(int worldX, int worldZ, int width, int height) {
		int rx0 = worldX >> 9, rx1 = (worldX + width - 1) >> 9;
		int rz0 = worldZ >> 9, rz1 = (worldZ + height - 1) >> 9;
		for (int rz = rz0; rz <= rz1; rz++) {
			for (int rx = rx0; rx <= rx1; rx++) {
				MapRegion r = loaded(rx, rz);
				if (r == null || r.state != MapRegion.READY || r.textureId == -1)
					continue;
				int x0 = Math.max(worldX, rx << 9);
				int x1 = Math.min(worldX + width, (rx + 1) << 9);
				int z0 = Math.max(worldZ, rz << 9);
				int z1 = Math.min(worldZ + height, (rz + 1) << 9);
				if (x1 <= x0 || z1 <= z0)
					continue;
				IntBuffer buf = scratch();
				buf.clear();
				for (int z = z0; z < z1; z++) {
					for (int x = x0; x < x1; x++) {
						int d = dataAt(x, z);
						buf.put(d == 0 ? 0 : MapShade.shade(d, dataAt(x, z - 1), dataAt(x - 1, z)));
					}
				}
				buf.flip();
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, r.textureId);
				GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x0 - (rx << 9), z0 - (rz << 9), x1 - x0, z1 - z0,
						GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
				r.mipDirty = true;
			}
		}
	}

	private static IntBuffer scratch() {
		if (scratch == null)
			scratch = BufferUtils.createIntBuffer(SIZE * SIZE);
		return scratch;
	}

	private static int mipMode() {
		if (mipMode == -1) {
			int mode = 0;
			try {
				ContextCapabilities caps = GLContext.getCapabilities();
				if (caps.OpenGL30)
					mode = 1;
				else if (caps.GL_EXT_framebuffer_object)
					mode = 2;
			} catch (Throwable ignored) {
			}
			mipMode = mode;
		}
		return mipMode;
	}

	private static void generateMipmap() {
		if (mipMode == 1)
			GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
		else if (mipMode == 2)
			EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D);
	}

	// ---------------------------------------------------------- overview (LOD)

	/**
	 * Low resolution (128x128) texture of a region for far zoom levels, read from
	 * the region file header without loading the region itself.
	 *
	 * @return texture id, or -1 if not available (yet)
	 */
	public int lodTexture(int rx, int rz) {
		final Long k = key(rx, rz);
		LodTile t = lods.get(k);
		if (t == null) {
			if (closed)
				return -1;
			t = new LodTile();
			lods.put(k, t);
		}
		t.lastUsed = now;
		if (t.stale && !t.loading)
			requestLod(rx, rz, k, t);
		if (t.pixels != null && lodBudget > 0) {
			lodBudget--;
			IntBuffer buf = scratch();
			buf.clear();
			buf.put(t.pixels);
			buf.flip();
			t.pixels = null;
			boolean fresh = t.textureId == -1;
			if (fresh)
				t.textureId = GL11.glGenTextures();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.textureId);
			if (fresh) {
				setupTextureParameters();
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			}
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, RegionCodec.LOD_SIZE, RegionCodec.LOD_SIZE, 0,
					GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
			if (mipMode() != 0)
				generateMipmap();
		}
		return t.textureId;
	}

	/** Overview texture of a region if one is already on the GPU; never starts a load. */
	public int cachedLodTexture(int rx, int rz) {
		LodTile t = lods.get(key(rx, rz));
		if (t == null)
			return -1;
		t.lastUsed = now;
		return t.textureId;
	}

	private void requestLod(int rx, int rz, final Long k, final LodTile t) {
		t.stale = false;
		final boolean hasFile = onDisk.contains(k);
		final boolean hasLegacy = legacyOnDisk.contains(k);
		if (!hasFile && !hasLegacy)
			return;
		t.loading = true;
		final File file = regionFile(rx, rz);
		final File legacy = legacyFile(rx, rz);
		MapIO.submit(new Runnable() {
			@Override
			public void run() {
				int[] pixels = null;
				boolean migrated = false;
				try {
					if (file.isFile()) {
						pixels = RegionCodec.readLod(file);
					} else if (legacy != null && legacy.isFile()) {
						int[][] chunks = RegionCodec.readLegacy(legacy);
						RegionCodec.write(file, chunks);
						migrated = true;
						pixels = RegionCodec.buildLod(chunks);
					}
				} catch (Throwable e) {
					System.err.println("[Minimap CE] Could not read overview of " + file.getName() + ": " + e);
				}
				final int[] result = pixels;
				final boolean didMigrate = migrated;
				completed.add(new Runnable() {
					@Override
					public void run() {
						t.loading = false;
						if (closed)
							return;
						if (didMigrate) {
							onDisk.add(k);
							legacyOnDisk.remove(k);
						}
						if (result != null && lods.get(k) == t)
							t.pixels = result;
					}
				});
			}
		});
	}

	// ----------------------------------------------------------------- disk I/O

	private void submitLoad(final MapRegion r) {
		final File file = regionFile(r.rx, r.rz);
		final File legacy = legacyFile(r.rx, r.rz);
		MapIO.submit(new Runnable() {
			@Override
			public void run() {
				int[][] chunks = null;
				boolean fromLegacy = false;
				try {
					if (file.isFile()) {
						chunks = RegionCodec.read(file);
					} else if (legacy != null && legacy.isFile()) {
						chunks = RegionCodec.readLegacy(legacy);
						fromLegacy = true;
					}
				} catch (Throwable e) {
					System.err.println("[Minimap CE] Could not read " + file.getName() + ": " + e);
					quarantine(file);
					chunks = null;
				}
				final boolean empty = chunks == null || RegionCodec.isEmpty(chunks);
				final int[][] result = chunks != null ? chunks : new int[RegionCodec.CHUNK_COUNT][];
				final int[] pixels = empty ? null : RegionCodec.buildPixels(result);
				final boolean migrate = fromLegacy && !empty;
				completed.add(new Runnable() {
					@Override
					public void run() {
						if (closed || regions.get(r.key) != r)
							return;
						r.chunks = result;
						r.hasData = !empty;
						r.pendingPixels = pixels;
						r.state = MapRegion.READY;
						cacheValid = false;
						if (migrate) {
							// Write imported 3.x data in the new format at the next autosave.
							r.dirty = true;
							r.dirtySince = now;
						}
						// Regions to the east and south shade their first column/row against this one.
						int x0 = r.rx << 9, z0 = r.rz << 9;
						uploadRect(x0 + SIZE, z0, 1, SIZE);
						uploadRect(x0, z0 + SIZE, SIZE, 1);
					}
				});
			}
		});
	}

	/** Moves an unreadable file aside so it is neither lost nor overwritten silently. */
	private static void quarantine(File file) {
		try {
			if (file.isFile()) {
				File target = new File(file.getParentFile(), file.getName() + ".corrupt");
				if (target.exists())
					target.delete();
				file.renameTo(target);
			}
		} catch (Throwable ignored) {
		}
	}

	/**
	 * @param transfer true when the region is being dropped from memory, so its
	 *                 arrays can be handed to the I/O thread without copying
	 */
	private void submitSave(MapRegion r, boolean transfer) {
		r.dirty = false;
		if (r.chunks == null)
			return;
		final int[][] snapshot = transfer ? r.chunks : r.chunks.clone();
		final File file = regionFile(r.rx, r.rz);
		final Long k = r.key;
		MapIO.submit(new Runnable() {
			@Override
			public void run() {
				try {
					RegionCodec.write(file, snapshot);
				} catch (Throwable e) {
					System.err.println("[Minimap CE] Could not save " + file.getName() + ": " + e);
					return;
				}
				completed.add(new Runnable() {
					@Override
					public void run() {
						onDisk.add(k);
						legacyOnDisk.remove(k);
						LodTile t = lods.get(k);
						if (t != null)
							t.stale = true;
					}
				});
			}
		});
	}

	/** Queues a save of everything that changed. Does not wait for the disk. */
	public void saveAll() {
		for (MapRegion r : regions.values()) {
			if (r.state == MapRegion.READY && r.dirty)
				submitSave(r, false);
		}
	}

	/** Queues final saves and releases all textures. The storage must not be used afterwards. */
	public void close() {
		if (closed)
			return;
		closed = true;
		for (MapRegion r : regions.values()) {
			if (r.state == MapRegion.READY && r.dirty)
				submitSave(r, true);
			if (r.textureId != -1) {
				GL11.glDeleteTextures(r.textureId);
				r.textureId = -1;
			}
		}
		regions.clear();
		for (LodTile t : lods.values()) {
			if (t.textureId != -1)
				GL11.glDeleteTextures(t.textureId);
		}
		lods.clear();
		cacheValid = false;
	}

	public boolean isClosed() {
		return closed;
	}

	public int loadedRegionCount() {
		return regions.size();
	}
}
