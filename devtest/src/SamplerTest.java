import net.fabricmc.example.minimap.engine.*;
import net.minecraft.src.Chunk;
import net.minecraft.src.ExtendedBlockStorage;

import java.lang.reflect.Field;
import java.util.Arrays;

/** Runs ChunkSampler against real Chunk / ExtendedBlockStorage objects with a stand-in colour table. */
public class SamplerTest {
    static int fails = 0, checks = 0;
    static void check(boolean c, String m) { checks++; if (!c) { fails++; System.out.println("FAIL: " + m); } }

    static final int STONE = 1, GRASS = 2, DIRT = 3, WATER = 9, LAVA = 11, SAND = 12, LEAVES = 18, GLASS = 20, TALLGRASS = 31, WOOL = 35, RACK = 87, BIG = 2500;
    static final int GRASS_TINT = 0x91BD59, FOLIAGE_TINT = 0x77AB2F, WATER_TINT = 0xE0FFAE;

    static Chunk chunk;
    static void set(int x, int y, int z, int id, int meta) {
        ExtendedBlockStorage[] secs = chunk.getBlockStorageArray();
        if (secs[y >> 4] == null) secs[y >> 4] = new ExtendedBlockStorage(y >> 4 << 4, true);
        ExtendedBlockStorage s = secs[y >> 4];
        s.getBlockLSBArray()[(y & 15) << 8 | z << 4 | x] = (byte) (id & 255);
        if (id > 255) { if (s.getBlockMSBArray() == null) s.setBlockMSBArray(s.createBlockMSBArray()); s.getBlockMSBArray().set(x, y & 15, z, id >> 8); }
        s.getMetadataArray().set(x, y & 15, z, meta);
    }
    static void fill(int y0, int y1, int id) { for (int y = y0; y <= y1; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) set(x, y, z, id, 0); }
    static void newChunk() { chunk = new Chunk(null, 3, -7); Arrays.fill(chunk.getBiomeArray(), (byte) 1); }

    @SuppressWarnings("unchecked")
    static <T> T field(String name) throws Exception { Field f = BlockColors.class.getDeclaredField(name); f.setAccessible(true); return (T) f.get(null); }

    public static void main(String[] a) throws Exception {
        int[] colors = field("colors"); byte[] tint = field("tint"); boolean[] skip = field("skip"); boolean[] water = field("water"); int[][] biomeTints = field("biomeTints");
        Arrays.fill(skip, true);
        int[][] defs = { {STONE, 0x7D7D7D, 0}, {GRASS, 0x959595, 1}, {DIRT, 0x866043, 0}, {WATER, 0x2F43F4, 3}, {LAVA, 0xD45A12, 0}, {SAND, 0xDBD3A0, 0}, {LEAVES, 0x7A7A7A, 2}, {GLASS, 0xDAF0F4, 0}, {RACK, 0x6F3634, 0}, {BIG, 0x123456, 0} };
        for (int[] d : defs) { skip[d[0]] = false; for (int m = 0; m < 16; m++) { colors[d[0] << 4 | m] = d[1]; tint[d[0] << 4 | m] = (byte) d[2]; } }
        water[WATER] = true;
        skip[WOOL] = false; for (int m = 0; m < 16; m++) colors[WOOL << 4 | m] = 0x100000 * m + 0x0F0F0F;
        // spruce leaves (meta 1) carry a fixed colour and no biome tint, like the real table
        for (int m = 0; m < 16; m++) if ((m & 3) == 1) { colors[LEAVES << 4 | m] = 0x4C6B4C; tint[LEAVES << 4 | m] = 0; }
        biomeTints[1][1] = 0xFF000000 | GRASS_TINT; biomeTints[2][1] = 0xFF000000 | FOLIAGE_TINT; biomeTints[3][1] = 0xFF000000 | WATER_TINT;
        // tall grass stays skipped (as the real table does)

        int[] out = new int[256];

        // ---- surface: layered ground
        newChunk();
        fill(0, 60, STONE); fill(61, 62, DIRT); fill(63, 63, GRASS);
        int found = ChunkSampler.sampleSurface(chunk, null, out);
        int grassRgb = MapShade.multiply(0x959595, GRASS_TINT);
        check(found == 256, "all columns found: " + found);
        boolean all = true; for (int v : out) all &= v == MapShade.pack(grassRgb, 63);
        check(all, "flat grass at y=63, got " + Integer.toHexString(out[0]) + " want " + Integer.toHexString(MapShade.pack(grassRgb, 63)));

        // tall grass on top is ignored; wool metadata picks its own colour; index = z*16+x
        set(5, 64, 9, TALLGRASS, 1);
        set(3, 64, 11, WOOL, 14);
        set(15, 70, 0, STONE, 0);
        set(0, 65, 15, LEAVES, 0); set(1, 65, 15, LEAVES, 1); set(2, 65, 15, LEAVES, 5);
        ChunkSampler.sampleSurface(chunk, null, out);
        check(out[9 << 4 | 5] == MapShade.pack(grassRgb, 63), "tall grass skipped");
        check(out[11 << 4 | 3] == MapShade.pack(0xE00000 + 0x0F0F0F, 64), "wool meta 14 at (3,11): " + Integer.toHexString(out[11 << 4 | 3]));
        check(out[3 << 4 | 11] == MapShade.pack(grassRgb, 63), "x/z not swapped");
        check(out[0 << 4 | 15] == MapShade.pack(0x7D7D7D, 70), "stone pillar at (15,0) height 70");
        check(out[15 << 4 | 0] == MapShade.pack(MapShade.multiply(0x7A7A7A, FOLIAGE_TINT), 65), "oak leaves biome tinted");
        check(out[15 << 4 | 1] == MapShade.pack(0x4C6B4C, 65), "spruce leaves fixed colour");
        check(out[15 << 4 | 2] == MapShade.pack(0x4C6B4C, 65), "spruce leaves with decay bit (meta 5) uses its own state colour");

        // something far above in an otherwise empty section (glass roof), with empty sections in between
        set(7, 200, 7, GLASS, 0);
        ChunkSampler.sampleSurface(chunk, null, out);
        check(out[7 << 4 | 7] == MapShade.pack(0xDAF0F4, 200), "glass at y=200 found through empty sections");
        check(out[7 << 4 | 8] == MapShade.pack(grassRgb, 63), "neighbour column falls through empty sections to the ground");
        // 12-bit block id
        set(4, 90, 4, BIG, 0);
        ChunkSampler.sampleSurface(chunk, null, out);
        check(out[4 << 4 | 4] == MapShade.pack(0x123456, 90), "extended block id: " + Integer.toHexString(out[4 << 4 | 4]));
        // top of the world
        set(9, 255, 9, STONE, 0);
        ChunkSampler.sampleSurface(chunk, null, out);
        check(out[9 << 4 | 9] == MapShade.pack(0x7D7D7D, 255), "y=255");

        // ---- water: 4 deep over sand, and very deep with no floor in range
        newChunk();
        fill(0, 30, STONE); fill(31, 62, WATER);
        for (int y = 31; y <= 58; y++) set(2, y, 2, SAND, 0);          // column (2,2): sand up to 58, water 59..62
        ChunkSampler.sampleSurface(chunk, null, out);
        int waterRgb = MapShade.multiply(0x2F43F4, WATER_TINT);
        int depth = 4, op = Math.min(240, 150 + depth * 14);
        int want = MapShade.scale(MapShade.lerp(0xDBD3A0, waterRgb, op), 256 - depth * 4);
        check(out[2 << 4 | 2] == MapShade.pack(want, 62), "shallow water blends with the bed: " + Integer.toHexString(out[2 << 4 | 2]) + " want " + Integer.toHexString(MapShade.pack(want, 62)));
        int deepWant = MapShade.scale(waterRgb, 256 - 20 * 4);
        check(out[0] == MapShade.pack(deepWant, 62), "deep water (no bed within range) is dark water: " + Integer.toHexString(out[0]));
        check((out[2 << 4 | 2] >>> 24) == 62 && (out[0] >>> 24) == 62, "water height is the surface (no relief on water)");
        int r1 = (out[2 << 4 | 2] >> 16) & 255, r2 = (out[0] >> 16) & 255;
        check(r1 > r2, "shallows are lighter than the deep");

        // ---- empty chunk
        newChunk();
        check(ChunkSampler.sampleSurface(chunk, null, out) == 0 && out[0] == 0 && out[255] == 0, "empty chunk gives nothing");
        chunk.getBlockStorageArray()[4] = new ExtendedBlockStorage(64, true); // allocated but all air
        check(ChunkSampler.sampleSurface(chunk, null, out) == 0, "all-air section gives nothing");

        // ---- cave slice (Nether style)
        newChunk();
        fill(0, 127, RACK);
        for (int y = 31; y <= 35; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 16; x++) set(x, y, z, 0, 0); // open hall over z 0..7, floor y=30
        for (int z = 0; z < 8; z++) set(5, 30, z, LAVA, 0);                                                          // a lava channel in the floor
        for (int y = 35; y <= 40; y++) set(1, y, 12, 0, 0);     // pocket above the player's level at (1,12): open from 3 above ref, floor y=34
        for (int y = 37; y <= 40; y++) set(3, y, 12, 0, 0);     // too far above (5): stays a wall
        for (int y = 26; y <= 28; y++) set(2, y, 12, 0, 0);     // pocket below at (2,12): floor y=25
        int ref = 32;
        found = ChunkSampler.sampleCave(chunk, null, out, null, ref);
        check(found == 256, "cave: every column has something: " + found);
        check(out[3 << 4 | 0] == MapShade.pack(0x6F3634, 30), "hall floor: " + Integer.toHexString(out[3 << 4]));
        check(out[3 << 4 | 5] == MapShade.pack(0xD45A12, 30), "lava channel");
        int wall = MapShade.pack(MapShade.scale(0x6F3634, 84), 0);
        check(out[10 << 4 | 8] == wall, "solid rock is a dark wall with no height: " + Integer.toHexString(out[10 << 4 | 8]));
        check(out[12 << 4 | 3] == wall, "pocket 5 above eye level is not shown");
        check(out[12 << 4 | 1] == MapShade.pack(0x6F3634, 34), "ledge just above eye level: " + Integer.toHexString(out[12 << 4 | 1]));
        check(out[12 << 4 | 2] == MapShade.pack(MapShade.scale(0x6F3634, 256 - (32 - 25 - 6) * 5), 25), "pocket just below (slightly darkened by distance): " + Integer.toHexString(out[12 << 4 | 2]));

        // moving up a level: floors seen before are kept where the new level is solid rock
        int[] prev = out.clone();
        found = ChunkSampler.sampleCave(chunk, null, out, prev, 60);
        check(out[3 << 4 | 0] == prev[3 << 4 | 0], "known floor kept under solid rock");
        check(out[10 << 4 | 8] == wall, "wall stays wall");
        // deep floors get darker with distance below the player
        newChunk(); fill(0, 10, RACK);
        ChunkSampler.sampleCave(chunk, null, out, null, 40);
        int far = out[0];
        check((far >>> 24) == 10 && ((far >> 16) & 255) < 0x6F, "distant floor darker: " + Integer.toHexString(far));
        // void / unfilled chunk in cave mode
        newChunk();
        check(ChunkSampler.sampleCave(chunk, null, out, null, 64) == 0, "cave: empty chunk gives nothing");
        check(ChunkSampler.sampleCave(chunk, null, out, prev, 64) == 256 && out[5] == prev[5], "cave: empty chunk keeps previous data");
        // out-of-range reference heights must not throw
        newChunk(); fill(0, 5, RACK);
        ChunkSampler.sampleCave(chunk, null, out, null, -50);
        ChunkSampler.sampleCave(chunk, null, out, null, 4000);
        check(true, "extreme reference heights");

        // ---- timing
        newChunk(); fill(0, 62, STONE); fill(63, 63, GRASS);
        for (int i = 0; i < 300; i++) ChunkSampler.sampleSurface(chunk, null, out);
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) ChunkSampler.sampleSurface(chunk, null, out);
        double us = (System.nanoTime() - t0) / 1000.0 / 1000.0;
        fill(0, 31, STONE); fill(32, 62, WATER); fill(63, 63, 0);
        for (int i = 0; i < 300; i++) ChunkSampler.sampleSurface(chunk, null, out);
        t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) ChunkSampler.sampleSurface(chunk, null, out);
        double usWater = (System.nanoTime() - t0) / 1000.0 / 1000.0;
        System.out.printf("sampleSurface: %.1f us per land chunk, %.1f us per deep-ocean chunk%n", us, usWater);

        System.out.println(checks + " checks, " + (fails == 0 ? "ALL SAMPLER TESTS PASSED" : fails + " FAILURES"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
