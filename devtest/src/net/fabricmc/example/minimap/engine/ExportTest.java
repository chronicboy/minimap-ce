package net.fabricmc.example.minimap.engine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Map export end to end, without the game: half the map saved in region files, half only in memory
 * (not saved yet), exported to PNG and compared pixel by pixel with the shading maths, across region
 * borders. Then again with a small pixel limit, which must give a scaled-down picture.
 *
 *   ExportTest <output directory>
 */
public class ExportTest {
    static final int C0 = -40, C1 = 40; // chunk range explored, crossing the region borders at -512, 0 and 512

    static int tile(int x, int z) {
        int h = 60 + (int) (10 * Math.sin(x / 23.0) + 8 * Math.cos(z / 17.0));
        int rgb = ((x * 7 + 64) & 0xFF) << 16 | ((z * 5 + 128) & 0xFF) << 8 | 0x40;
        return MapShade.pack(rgb, h);
    }

    static boolean explored(int cx, int cz) {
        return !(Math.abs(cx - 9) < 2 && Math.abs(cz + 6) < 2); // a hole
    }

    static int data(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (cx < C0 || cx > C1 || cz < C0 || cz > C1 || !explored(cx, cz))
            return 0;
        return tile(x, z);
    }

    static boolean waitForExport() throws InterruptedException {
        long until = System.currentTimeMillis() + 60000;
        while (MapExport.isRunning() && System.currentTimeMillis() < until)
            Thread.sleep(20);
        return !MapExport.isRunning();
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        File world = new File(out, "export-world");
        int failures = 0;
        int[] buf = new int[256];

        // West half (chunks C0..0): written to region files.
        MapStorage first = new MapStorage(world, null);
        for (int cz = C0; cz <= C1; cz++) for (int cx = C0; cx <= 0; cx++) {
            if (!explored(cx, cz)) continue;
            for (int i = 0; i < 256; i++) buf[i] = tile(cx * 16 + (i & 15), cz * 16 + (i >> 4));
            first.putChunk(cx, cz, buf);
        }
        first.close();
        MapIO.flush(30000);

        // East half: only in memory of a fresh storage that also knows the files.
        // Regions that are on disk load first (putChunk says false meanwhile), as in the game, so retry.
        MapStorage storage = new MapStorage(world, null);
        for (int attempt = 0; attempt < 50; attempt++) {
            int pending = 0;
            for (int cz = C0; cz <= C1; cz++) for (int cx = 1; cx <= C1; cx++) {
                if (!explored(cx, cz)) continue;
                for (int i = 0; i < 256; i++) buf[i] = tile(cx * 16 + (i & 15), cz * 16 + (i >> 4));
                if (!storage.putChunk(cx, cz, buf)) pending++;
            }
            if (pending == 0) break;
            MapIO.flush(10000);
            storage.tick();
        }

        File png = new File(out, "export-full.png");
        long t0 = System.nanoTime();
        if (!MapExport.start(storage, png)) { System.out.println("  FAIL export did not start"); failures++; }
        if (!waitForExport()) { System.out.println("  FAIL export did not finish"); failures++; }
        long ms = (System.nanoTime() - t0) / 1000000;
        System.out.println("  " + MapExport.status() + "  [" + ms + " ms]");
        String chat = MapExport.pollMessage();
        if (chat == null || !chat.startsWith("Map saved")) { System.out.println("  FAIL chat message: " + chat); failures++; }

        BufferedImage img = ImageIO.read(png);
        int x0 = C0 * 16, z0 = C0 * 16, size = (C1 - C0 + 1) * 16;
        if (img.getWidth() != size || img.getHeight() != size) {
            System.out.println("  FAIL size " + img.getWidth() + "x" + img.getHeight() + ", expected " + size);
            failures++;
        } else {
            int wrong = 0, seams = 0, holes = 0;
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                int wx = x0 + x, wz = z0 + y;
                int d = data(wx, wz);
                int expected = d == 0 ? 0 : 0xFF000000 | MapShade.shade(d, data(wx, wz - 1), data(wx - 1, wz));
                int got = img.getRGB(x, y);
                if (d == 0) {
                    if ((got >>> 24) != 0) holes++;
                } else if (got != expected) {
                    wrong++;
                    if ((wx & 511) == 0 || (wz & 511) == 0) seams++;
                }
            }
            System.out.println("  full size: " + size + "x" + size + ", wrong pixels " + wrong + " (on region borders " + seams
                    + "), unexplored not transparent " + holes);
            if (wrong > 0 || holes > 0) failures++;
        }

        // Scaled down: limit to 300 x 300 pixels.
        MapExport.maxPixels = 300L * 300L;
        File small = new File(out, "export-small.png");
        MapExport.start(storage, small);
        if (!waitForExport()) { System.out.println("  FAIL small export did not finish"); failures++; }
        MapExport.pollMessage();
        BufferedImage s = ImageIO.read(small);
        int expect = size / 8;
        System.out.println("  scaled: " + s.getWidth() + "x" + s.getHeight() + " (expected " + expect + "), " + MapExport.status());
        if (Math.abs(s.getWidth() - expect) > 1 || Math.abs(s.getHeight() - expect) > 1) failures++;
        // The middle (fully explored) is opaque.
        if ((s.getRGB(s.getWidth() / 4, s.getHeight() / 2) >>> 24) != 255) { System.out.println("  FAIL scaled picture has holes"); failures++; }

        storage.close();
        MapIO.flush(10000);
        System.out.println(failures == 0 ? "EXPORT TEST PASSED" : "EXPORT TEST FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
