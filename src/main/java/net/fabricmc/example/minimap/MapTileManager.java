package net.fabricmc.example.minimap;

import net.minecraft.src.World;

import java.io.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persistent tile cache for explored terrain.
 * Stores 16x16 chunk color data in region files (32x32 chunks per region).
 * Saved per-world using the same world ID system as waypoints.
 *
 * File format per region: DataOutputStream
 * - int: number of chunks
 * - For each chunk: byte localX (0-31), byte localZ (0-31), then 256 ints (RGB
 * colors)
 */
public class MapTileManager {
    public static final MapTileManager instance = new MapTileManager();

    // In-memory cache: key = packChunkCoords(chunkX, chunkZ), value = 16x16 RGB
    // color array
    private final HashMap<Long, int[]> tileCache = new HashMap<Long, int[]>();
    private final Set<Long> dirtyRegions = new HashSet<Long>();
    private String currentWorldId = "";
    private long lastSaveTime = 0;
    private static final long SAVE_INTERVAL_MS = 30000; // Auto-save every 30 seconds
    private static final String TILE_DIR = "config/minimap-ce-maps-v3";

    // ---- Public API ----

    /**
     * Store a chunk's color data (called as the player explores).
     * 
     * @param chunkX Chunk coordinate X (world block X >> 4)
     * @param chunkZ Chunk coordinate Z (world block Z >> 4)
     * @param colors 256 RGB ints (row-major 16x16, index = localZ * 16 + localX)
     */
    public void saveChunkColors(int chunkX, int chunkZ, int[] colors) {
        if (currentWorldId.isEmpty() || colors == null || colors.length != 256)
            return;

        long key = packChunkCoords(chunkX, chunkZ);
        int[] copy = new int[256];
        System.arraycopy(colors, 0, copy, 0, 256);
        tileCache.put(key, copy);

        // Mark this chunk's region as dirty
        long regionKey = packChunkCoords(chunkX >> 5, chunkZ >> 5);
        dirtyRegions.add(regionKey);
    }

    /**
     * Get cached color data for a chunk, or null if not explored.
     */
    public int[] getChunkColors(int chunkX, int chunkZ) {
        return tileCache.get(packChunkCoords(chunkX, chunkZ));
    }

    /**
     * Get a single block's cached color from chunk data.
     * 
     * @return RGB color, or 0 if not cached
     */
    public int getBlockColor(int worldX, int worldZ) {
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        int[] colors = getChunkColors(chunkX, chunkZ);
        if (colors == null)
            return 0;

        int localX = worldX & 15;
        int localZ = worldZ & 15;
        int cachedData = colors[localZ * 16 + localX];
        return cachedData & 0xFFFFFF;
    }

    /**
     * Load tile cache for a world.
     */
    public void loadForWorld(World world) {
        if (!currentWorldId.isEmpty()) {
            saveDirtyRegions();
        }

        tileCache.clear();
        dirtyRegions.clear();
        currentWorldId = WaypointManager.buildWorldId(world);

        if (currentWorldId.isEmpty())
            return;

        File worldDir = getWorldDir();
        if (!worldDir.exists()) {
            System.out.println("[Minimap CE] No map tiles for world: " + currentWorldId);
            return;
        }

        // Load all region files
        File[] regionFiles = worldDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("r.") && name.endsWith(".dat");
            }
        });

        if (regionFiles == null)
            return;

        int totalChunks = 0;
        for (File regionFile : regionFiles) {
            totalChunks += loadRegionFile(regionFile);
        }

        System.out.println("[Minimap CE] Loaded " + totalChunks + " cached map tiles for world: " + currentWorldId);
    }

    /**
     * Save and clear on world leave.
     */
    public void clearForWorldLeave() {
        if (!currentWorldId.isEmpty()) {
            saveDirtyRegions();
        }
        tileCache.clear();
        dirtyRegions.clear();
        currentWorldId = "";
    }

    /**
     * Periodic auto-save of dirty regions (call from tick loop).
     */
    public void tickAutoSave() {
        if (dirtyRegions.isEmpty())
            return;
        long now = System.currentTimeMillis();
        if (now - lastSaveTime > SAVE_INTERVAL_MS) {
            saveDirtyRegions();
            lastSaveTime = now;
        }
    }

    /**
     * Force save all dirty regions.
     */
    public void saveAll() {
        saveDirtyRegions();
    }

    public boolean hasWorld() {
        return !currentWorldId.isEmpty();
    }

    // ---- Region file I/O ----

    private void saveDirtyRegions() {
        if (currentWorldId.isEmpty() || dirtyRegions.isEmpty())
            return;

        File worldDir = getWorldDir();
        if (!worldDir.exists()) {
            worldDir.mkdirs();
        }

        Set<Long> toSave = new HashSet<Long>(dirtyRegions);
        dirtyRegions.clear();

        for (Long regionKey : toSave) {
            int regionX = (int) (regionKey >> 32);
            int regionZ = (int) (regionKey & 0xFFFFFFFFL);
            saveRegion(regionX, regionZ);
        }
    }

    private void saveRegion(int regionX, int regionZ) {
        // Collect all chunks belonging to this region
        int baseChunkX = regionX << 5;
        int baseChunkZ = regionZ << 5;

        HashMap<Long, int[]> regionChunks = new HashMap<Long, int[]>();
        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                long key = packChunkCoords(baseChunkX + lx, baseChunkZ + lz);
                int[] colors = tileCache.get(key);
                if (colors != null) {
                    regionChunks.put(key, colors);
                }
            }
        }

        if (regionChunks.isEmpty())
            return;

        File file = new File(getWorldDir(), "r." + regionX + "." + regionZ + ".dat");
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            dos.writeInt(regionChunks.size());

            for (Map.Entry<Long, int[]> entry : regionChunks.entrySet()) {
                long key = entry.getKey();
                int chunkX = (int) (key >> 32);
                int chunkZ = (int) (key & 0xFFFFFFFFL);
                // localX/Z must be positive since baseChunk is regionX * 32
                int localX = chunkX - baseChunkX;
                int localZ = chunkZ - baseChunkZ;

                // Ensure local offset is positive and within 0-31 range (Java bitwise modulo
                // handles negatives poorly)
                localX = (localX % 32 + 32) % 32;
                localZ = (localZ % 32 + 32) % 32;

                dos.writeByte(localX);
                dos.writeByte(localZ);

                int[] colors = entry.getValue();
                for (int i = 0; i < 256; i++) {
                    dos.writeInt(colors[i]);
                }
            }
        } catch (IOException e) {
            System.err.println("[Minimap CE] Failed to save region " + regionX + "," + regionZ + ": " + e.getMessage());
        }
    }

    private int loadRegionFile(File file) {
        // Parse region coords from filename "r.<rx>.<rz>.dat"
        String name = file.getName();
        String[] parts = name.substring(2, name.length() - 4).split("\\.");
        if (parts.length != 2)
            return 0;

        int regionX, regionZ;
        try {
            regionX = Integer.parseInt(parts[0]);
            regionZ = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }

        int baseChunkX = regionX << 5;
        int baseChunkZ = regionZ << 5;
        int loaded = 0;

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int count = dis.readInt();

            for (int c = 0; c < count; c++) {
                int localX = dis.readByte() & 0xFF;
                int localZ = dis.readByte() & 0xFF;

                int[] colors = new int[256];
                for (int i = 0; i < 256; i++) {
                    colors[i] = dis.readInt();
                }

                int chunkX = baseChunkX + localX;
                int chunkZ = baseChunkZ + localZ;
                tileCache.put(packChunkCoords(chunkX, chunkZ), colors);
                loaded++;
            }
        } catch (IOException e) {
            System.err.println("[Minimap CE] Failed to load region file " + file.getName() + ": " + e.getMessage());
        }

        return loaded;
    }

    // ---- Helpers ----

    private File getWorldDir() {
        return new File(TILE_DIR + "/" + currentWorldId);
    }

    public static long packChunkCoords(int x, int z) {
        // Correctly pack two signed 32-bit ints into a 64-bit long
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Load tile data for a specific dimension (read-only, for cross-dimension
     * viewing).
     * Returns a HashMap of packed chunk coords to color arrays.
     */
    public HashMap<Long, int[]> loadTilesForDim(int dim) {
        HashMap<Long, int[]> result = new HashMap<Long, int[]>();
        String worldId = WaypointManager.instance.getWorldIdForDim(dim);
        if (worldId.isEmpty())
            return result;

        File worldDir = new File(TILE_DIR + "/" + worldId);
        if (!worldDir.exists())
            return result;

        File[] regionFiles = worldDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("r.") && name.endsWith(".dat");
            }
        });
        if (regionFiles == null)
            return result;

        for (File file : regionFiles) {
            String name = file.getName();
            String[] parts = name.substring(2, name.length() - 4).split("\\.");
            if (parts.length != 2)
                continue;
            int regionX, regionZ;
            try {
                regionX = Integer.parseInt(parts[0]);
                regionZ = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }

            int baseChunkX = regionX << 5;
            int baseChunkZ = regionZ << 5;

            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                int count = dis.readInt();
                for (int c = 0; c < count; c++) {
                    int localX = dis.readByte() & 0xFF;
                    int localZ = dis.readByte() & 0xFF;
                    int[] colors = new int[256];
                    for (int i = 0; i < 256; i++) {
                        colors[i] = dis.readInt();
                    }
                    result.put(packChunkCoords(baseChunkX + localX, baseChunkZ + localZ), colors);
                }
            } catch (IOException e) {
                // silently skip corrupt files
            }
        }
        return result;
    }

    /**
     * Get a block color from a pre-loaded dimension tile set.
     */
    public static int getBlockColorFromTiles(HashMap<Long, int[]> tiles, int worldX, int worldZ) {
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        int[] colors = tiles.get(packChunkCoords(chunkX, chunkZ));
        if (colors == null)
            return 0;
        int localX = worldX & 15;
        int localZ = worldZ & 15;
        int cachedData = colors[localZ * 16 + localX];
        return cachedData & 0xFFFFFF;
    }
}
