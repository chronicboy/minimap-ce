import net.fabricmc.example.minimap.engine.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.io.*;
import java.nio.IntBuffer;
import java.util.*;

/**
 * Offscreen end-to-end test of the map pipeline: region files -> MapStorage ->
 * GL textures -> MapDraw -> framebuffer, compared pixel by pixel with what the
 * synthetic world says should be there.
 */
public class GLTest {
    static final int W = 512, H = 512;
    static int fails = 0, checks = 0;
    static int[] frame = new int[W * H];

    static void check(boolean c, String msg) { checks++; if (!c) { fails++; if (fails < 40) System.out.println("FAIL: " + msg); } }

    static int pattern(int x, int z) {
        return (((x * 3) & 255) << 16) | (((z * 5) & 255) << 8) | ((x ^ z) & 255);
    }

    static int[][] regionChunks(int rx, int rz, int height) {
        int[][] chunks = new int[1024][];
        for (int ci = 0; ci < 1024; ci++) {
            int[] c = new int[256];
            int bx = rx * 512 + (ci & 31) * 16, bz = rz * 512 + (ci >> 5) * 16;
            for (int i = 0; i < 256; i++) c[i] = MapShade.pack(pattern(bx + (i & 15), bz + (i >> 4)), height);
            chunks[ci] = c;
        }
        return chunks;
    }

    static void glErr(String where) {
        int e = GL11.glGetError();
        check(e == 0, "GL error 0x" + Integer.toHexString(e) + " after " + where);
    }

    static void readFrame() {
        IntBuffer buf = BufferUtils.createIntBuffer(W * H);
        GL11.glReadPixels(0, 0, W, H, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        buf.get(frame);
    }

    /** Pixel at GUI coordinates (origin top-left). */
    static int px(int sx, int sy) { return frame[(H - 1 - sy) * W + sx] & 0xFFFFFF; }

    static boolean near(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= tol && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= tol
                && Math.abs((a & 255) - (b & 255)) <= tol;
    }

    static void beginGui() {
        GL11.glViewport(0, 0, W, H);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, W, H, 0.0D, 1000.0D, 3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
        GL11.glClearColor(1f, 0f, 1f, 1f); // magenta = "nothing drawn"
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    static final double[] RX = { -256, 256, 256, -256 }, RY = { -256, -256, 256, 256 };

    /** Draws until everything in view is loaded; returns number of frames needed. */
    static int drawUntilComplete(MapStorage s, double cx, double cz, double scale, double rot, double[] shx, double[] shy, int n, float alpha, int guiScale) {
        for (int i = 1; i <= 400; i++) {
            s.beginFrame();
            beginGui();
            boolean complete = MapDraw.draw(s, cx, cz, scale, rot, 256, 256, shx, shy, n, alpha, guiScale, true);
            s.tick();
            MapIO.flush(2000);
            s.tick();
            if (complete) {
                // one more clean frame so late uploads (edge fix-ups, mipmaps) are included
                s.beginFrame();
                beginGui();
                MapDraw.draw(s, cx, cz, scale, rot, 256, 256, shx, shy, n, alpha, guiScale, true);
                GL11.glFinish();
                return i;
            }
        }
        check(false, "never completed");
        return -1;
    }

    public static void main(String[] args) throws Exception {
        File base = new File(args[0]);
        File dir = new File(base, "v4/world");
        File legacyDir = new File(base, "v3/world");
        dir.mkdirs(); legacyDir.mkdirs();

        Pbuffer pb = new Pbuffer(W, H, new PixelFormat(8, 24, 0), null);
        pb.makeCurrent();
        System.out.println("GL: " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));

        // ---------------- world: 4 regions around the origin, flat height 64 (shading = identity)
        for (int rz = -1; rz <= 0; rz++) for (int rx = -1; rx <= 0; rx++)
            RegionCodec.write(new File(dir, "r." + rx + "." + rz + ".mmr"), regionChunks(rx, rz, 64));

        MapStorage s = new MapStorage(dir, legacyDir);
        check(s.exists(0, 0) && s.exists(-1, -1) && !s.exists(1, 0) && !s.exists(5, 5), "exists() index");

        // ---------------- 1. scale 1, no rotation, view straddling all four regions
        int frames = drawUntilComplete(s, 10.0, -20.0, 1.0, 0.0, RX, RY, 4, 1.0f, 1);
        glErr("test 1");
        readFrame();
        int bad = 0;
        for (int sy = 0; sy < H; sy += 3) for (int sx = 0; sx < W; sx += 3) {
            int wx = (int) Math.floor(10.0 + (sx + 0.5 - 256)), wz = (int) Math.floor(-20.0 + (sy + 0.5 - 256));
            if (px(sx, sy) != pattern(wx, wz)) { bad++; if (bad < 5) System.out.println("  t1 mismatch at " + sx + "," + sy + " world " + wx + "," + wz + " got " + Integer.toHexString(px(sx, sy)) + " want " + Integer.toHexString(pattern(wx, wz))); }
        }
        check(bad == 0, "test 1 (1:1 exact): " + bad + " wrong pixels");
        System.out.println("test 1 done in " + frames + " frames, loaded regions " + s.loadedRegionCount());

        // region seam rows/columns specifically (x = -1/0, z = -1/0)
        for (int k = 0; k < W; k++) {
            int sxSeam = 246, sySeam = 276; // world x = 0 at sx 246 ; world z = 0 at sy 276
            for (int d = -1; d <= 0; d++) {
                int wxA = (sxSeam + d) - 246, wzA = (int) Math.floor(-20.0 + (k + 0.5 - 256));
                if (px(sxSeam + d, k) != pattern(wxA, wzA)) bad++;
                int wxB = (int) Math.floor(10.0 + (k + 0.5 - 256)), wzB = (sySeam + d) - 276;
                if (px(k, sySeam + d) != pattern(wxB, wzB)) bad++;
            }
        }
        check(bad == 0, "seams exact: " + bad);

        // ---------------- 2. sub-pixel scrolling: half a block offset still lands on the right texels (nearest)
        drawUntilComplete(s, 10.25, -20.25, 1.0, 0.0, RX, RY, 4, 1.0f, 1);
        readFrame(); bad = 0;
        for (int sy = 5; sy < H; sy += 7) for (int sx = 5; sx < W; sx += 7) {
            int wx = (int) Math.floor(10.25 + (sx + 0.5 - 256)), wz = (int) Math.floor(-20.25 + (sy + 0.5 - 256));
            if (px(sx, sy) != pattern(wx, wz)) bad++;
        }
        check(bad == 0, "test 2 (sub-block offset): " + bad);

        // ---------------- 3. zoom 4x: every block is a crisp 4x4 square
        drawUntilComplete(s, 3.0, 5.0, 4.0, 0.0, RX, RY, 4, 1.0f, 1);
        glErr("test 3");
        readFrame(); bad = 0;
        for (int sy = 1; sy < H; sy += 5) for (int sx = 1; sx < W; sx += 5) {
            int wx = (int) Math.floor(3.0 + (sx + 0.5 - 256) / 4.0), wz = (int) Math.floor(5.0 + (sy + 0.5 - 256) / 4.0);
            if (px(sx, sy) != pattern(wx, wz)) bad++;
        }
        check(bad == 0, "test 3 (4x nearest): " + bad);

        // ---------------- 4. rotation by 90 degrees
        double rot = Math.PI / 2;
        drawUntilComplete(s, 10.0, -20.0, 1.0, rot, RX, RY, 4, 1.0f, 1);
        glErr("test 4");
        readFrame(); bad = 0;
        for (int sy = 4; sy < H - 4; sy += 9) for (int sx = 4; sx < W - 4; sx += 9) {
            double ox = sx + 0.5 - 256, oy = sy + 0.5 - 256;
            int wx = (int) Math.floor(10.0 + oy), wz = (int) Math.floor(-20.0 - ox);
            if (!near(px(sx, sy), pattern(wx, wz), 3)) { bad++; if (bad < 4) System.out.println("  t4 " + sx + "," + sy + " got " + Integer.toHexString(px(sx, sy)) + " want " + Integer.toHexString(pattern(wx, wz))); }
        }
        check(bad == 0, "test 4 (rotated 90): " + bad);

        // rotation by 180 - 37 degrees (arbitrary yaw), checked through the documented transform
        rot = Math.toRadians(180.0 - 37.0);
        drawUntilComplete(s, 100.5, 60.5, 2.0, rot, RX, RY, 4, 1.0f, 1);
        readFrame(); bad = 0;
        double cos = Math.cos(rot), sin = Math.sin(rot);
        for (int sy = 6; sy < H - 6; sy += 11) for (int sx = 6; sx < W - 6; sx += 11) {
            double ox = sx + 0.5 - 256, oy = sy + 0.5 - 256;
            double fx = 100.5 + (cos * ox + sin * oy) / 2.0, fz = 60.5 + (-sin * ox + cos * oy) / 2.0;
            // skip samples too close to a texel border (rasterisation may pick either side)
            if (Math.abs(fx - Math.rint(fx)) < 0.2 || Math.abs(fz - Math.rint(fz)) < 0.2) continue;
            if (px(sx, sy) != pattern((int) Math.floor(fx), (int) Math.floor(fz))) bad++;
        }
        check(bad == 0, "test 4b (arbitrary rotation, 2x): " + bad);

        // ---------------- 5. circle mask
        int n = 72; double[] cxs = new double[n], cys = new double[n];
        for (int i = 0; i < n; i++) { double a = i * 2 * Math.PI / n; cxs[i] = Math.cos(a) * 100; cys[i] = Math.sin(a) * 100; }
        drawUntilComplete(s, 10.0, -20.0, 1.0, 0.0, cxs, cys, n, 1.0f, 1);
        glErr("test 5");
        readFrame(); bad = 0; int inside = 0, outside = 0;
        for (int sy = 0; sy < H; sy += 2) for (int sx = 0; sx < W; sx += 2) {
            double d = Math.hypot(sx + 0.5 - 256, sy + 0.5 - 256);
            if (d < 99.0) { inside++; int wx = (int) Math.floor(10.0 + (sx + 0.5 - 256)), wz = (int) Math.floor(-20.0 + (sy + 0.5 - 256)); if (px(sx, sy) != pattern(wx, wz)) bad++; }
            else if (d > 100.6) { outside++; if (px(sx, sy) != 0xFF00FF) bad++; }
        }
        check(bad == 0 && inside > 5000 && outside > 30000, "test 5 (circle mask): " + bad);

        // ---------------- 6. opacity: 50% over magenta
        drawUntilComplete(s, 10.0, -20.0, 1.0, 0.0, RX, RY, 4, 0.5f, 1);
        readFrame(); bad = 0;
        for (int sy = 8; sy < H; sy += 31) for (int sx = 8; sx < W; sx += 31) {
            int p = pattern((int) Math.floor(10.0 + (sx + 0.5 - 256)), (int) Math.floor(-20.0 + (sy + 0.5 - 256)));
            int want = ((((p >> 16) & 255) + 255) / 2 << 16) | ((((p >> 8) & 255)) / 2 << 8) | (((p & 255) + 255) / 2);
            if (!near(px(sx, sy), want, 2)) bad++;
        }
        check(bad == 0, "test 6 (opacity blend): " + bad);

        // ---------------- 7. live chunk update with relief shading, across a region border
        int[] hill = new int[256];
        for (int i = 0; i < 256; i++) hill[i] = MapShade.pack(0x808080, 64 + (i & 15) + (i >> 4)); // rises to the south-east
        check(s.putChunk(0, 0, hill), "putChunk into loaded region");
        check(s.putChunk(-1, -1, hill), "putChunk into loaded region (NW)");
        drawUntilComplete(s, 0.0, 0.0, 1.0, 0.0, RX, RY, 4, 1.0f, 1);
        glErr("test 7");
        readFrame(); bad = 0;
        for (int wz = -20; wz < 20; wz++) for (int wx = -20; wx < 20; wx++) {
            int d = s.dataAt(wx, wz);
            int want = MapShade.shade(d, s.dataAt(wx, wz - 1), s.dataAt(wx - 1, wz)) & 0xFFFFFF;
            int got = px(wx + 256, wz + 256);
            if (got != want) { bad++; if (bad < 5) System.out.println("  t7 world " + wx + "," + wz + " got " + Integer.toHexString(got) + " want " + Integer.toHexString(want)); }
        }
        check(bad == 0, "test 7 (chunk update + shading incl. neighbours): " + bad);
        check((s.dataAt(5, 5) >>> 24) == 64 + 10 && (s.dataAt(-11, -11) >>> 24) == 64 + 10, "dataAt heights");
        // the hill must actually look shaded (not flat grey)
        check(px(256 + 5, 256 + 5) != 0x808080, "relief visible");
        // column x=16 (east neighbour chunk) shades against the hill's east edge
        int eastWant = MapShade.shade(s.dataAt(16, 3), s.dataAt(16, 2), s.dataAt(15, 3)) & 0xFFFFFF;
        check(px(256 + 16, 256 + 3) == eastWant && eastWant != pattern(16, 3), "east neighbour re-shaded");

        // unchanged data must not dirty anything / must report success
        check(s.putChunk(0, 0, hill), "putChunk identical");

        // ---------------- 8. zoomed out 2x (mipmapped minification): average of 2x2 blocks
        drawUntilComplete(s, 200.0, -200.0, 0.5, 0.0, RX, RY, 4, 1.0f, 1);
        glErr("test 8");
        readFrame(); bad = 0;
        for (int sy = 10; sy < H; sy += 13) for (int sx = 10; sx < W; sx += 13) {
            int bx = 200 + (sx - 256) * 2, bz = -200 + (sy - 256) * 2;
            if (bx < -500 || bx > 500 || bz < -500 || bz > 500) continue;
            if (bx >= -18 && bx < 34 && bz >= -18 && bz < 34) continue; // chunks overwritten by the hill in test 7 (+ shaded neighbours)
            int r = 0, g = 0, b = 0;
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) { int p = pattern(bx + dx, bz + dz); r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; }
            if (!near(px(sx, sy), ((r / 4) << 16) | ((g / 4) << 8) | (b / 4), 6)) { bad++; if (bad < 4) System.out.println("  t8 " + sx + "," + sy + " got " + Integer.toHexString(px(sx, sy))); }
        }
        check(bad == 0, "test 8 (0.5x minification): " + bad);

        // ---------------- 9. persistence: save, reopen, data identical; then unload behaviour
        s.saveAll();
        MapIO.flush(5000);
        s.tick();
        s.close();
        glErr("close");
        MapStorage s2 = new MapStorage(dir, legacyDir);
        drawUntilComplete(s2, 0.0, 0.0, 1.0, 0.0, RX, RY, 4, 1.0f, 1);
        readFrame(); bad = 0;
        for (int wz = -20; wz < 20; wz++) for (int wx = -20; wx < 20; wx++) {
            int want = MapShade.shade(s2.dataAt(wx, wz), s2.dataAt(wx, wz - 1), s2.dataAt(wx - 1, wz)) & 0xFFFFFF;
            if (px(wx + 256, wz + 256) != want) bad++;
        }
        check(bad == 0, "test 9 (reload from disk, loader-built pixels + edge fix): " + bad);
        check((s2.dataAt(5, 5) >>> 24) == 74, "hill persisted");
        // whole first row/column of region (0,0) must be shaded against the neighbouring regions
        bad = 0;
        for (int k = 0; k < 250; k++) {
            int w1 = MapShade.shade(s2.dataAt(k, 0), s2.dataAt(k, -1), s2.dataAt(k - 1, 0)) & 0xFFFFFF;
            if (px(256 + k, 256) != w1) bad++;
            int w2 = MapShade.shade(s2.dataAt(0, k), s2.dataAt(0, k - 1), s2.dataAt(-1, k)) & 0xFFFFFF;
            if (px(256, 256 + k) != w2) bad++;
        }
        check(bad == 0, "test 9b (cross-region edge shading): " + bad);

        // ---------------- 10. overview mode (far zoom): reads only the file headers
        MapStorage s3 = new MapStorage(dir, legacyDir);
        drawUntilComplete(s3, 0.0, 0.0, 0.125, 0.0, RX, RY, 4, 1.0f, 1);
        glErr("test 10");
        check(s3.loadedRegionCount() == 0, "overview mode must not load full regions, loaded " + s3.loadedRegionCount());
        readFrame(); bad = 0; int covered = 0;
        for (int sy = 200; sy < 312; sy += 5) for (int sx = 200; sx < 312; sx += 5) {
            int bx = (sx - 256) * 8, bz = (sy - 256) * 8;
            if (bx >= -16 && bx < 32 && bz >= -16 && bz < 32) continue; // hill area differs
            int r = 0, g = 0, b = 0;
            for (int dz = 0; dz < 8; dz++) for (int dx = 0; dx < 8; dx++) { int p = pattern(bx + dx, bz + dz); r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; }
            covered++;
            if (!near(px(sx, sy), ((r / 64) << 16) | ((g / 64) << 8) | (b / 64), 10)) { bad++; if (bad < 4) System.out.println("  t10 " + sx + "," + sy + " got " + Integer.toHexString(px(sx, sy)) + " want " + Integer.toHexString(((r / 64) << 16) | ((g / 64) << 8) | (b / 64))); }
        }
        check(bad == 0 && covered > 300, "test 10 (overview): " + bad + " of " + covered);
        // outside the explored 1024x1024 area nothing is drawn
        check(px(40, 40) == 0xFF00FF && px(470, 470) == 0xFF00FF, "unexplored stays empty");
        s3.close();

        // ---------------- 11. legacy 3.x import: old file only, drawn, then migrated on save
        File lf = new File(legacyDir, "r.3.3.dat");
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(lf)))) {
            dos.writeInt(2);
            for (int c = 0; c < 2; c++) {
                dos.writeByte(c); dos.writeByte(0);
                for (int i = 0; i < 256; i++) dos.writeInt((64 << 24) | 0x3366CC);
            }
        }
        long legacySize = lf.length(), legacyTime = lf.lastModified();
        MapStorage s4 = new MapStorage(dir, legacyDir);
        check(s4.exists(3, 3), "legacy region indexed");
        drawUntilComplete(s4, 3 * 512 + 16.0, 3 * 512 + 8.0, 1.0, 0.0, RX, RY, 4, 1.0f, 1);
        glErr("test 11");
        readFrame();
        check(px(256 - 10, 256) == 0x3366CC && px(256 + 10, 256) == 0x3366CC, "legacy tiles drawn: " + Integer.toHexString(px(250, 256)));
        check(px(256 + 40, 256) == 0xFF00FF, "legacy: unexplored chunk empty");
        check(!new File(dir, "r.3.3.mmr").exists(), "not migrated before save");
        s4.close();          // dirty (migration) -> saved on close
        MapIO.flush(5000);
        check(new File(dir, "r.3.3.mmr").isFile(), "legacy region migrated to v4 on close");
        check(lf.length() == legacySize && lf.lastModified() == legacyTime, "legacy file untouched");
        int[][] mig = RegionCodec.read(new File(dir, "r.3.3.mmr"));
        check(mig[0] != null && mig[1] != null && mig[2] == null && mig[0][17] == ((64 << 24) | 0x3366CC), "migrated content");

        // ---------------- 12. new exploration in an empty world dir
        File dir2 = new File(base, "v4/fresh");
        MapStorage s5 = new MapStorage(dir2, new File(base, "v3/none"));
        check(!s5.exists(0, 0), "fresh world empty");
        int[] flat = new int[256]; Arrays.fill(flat, MapShade.pack(0x22AA44, 64));
        check(s5.putChunk(100, -3, flat), "putChunk creates region immediately");
        check(s5.exists(3, -1), "exists after put");
        drawUntilComplete(s5, 100 * 16 + 8.0, -3 * 16 + 8.0, 1.0, 0.0, RX, RY, 4, 1.0f, 1);
        glErr("test 12");
        readFrame();
        check(px(256, 256) == 0x22AA44 && px(256 - 7, 256 + 7) == 0x22AA44 && px(256 + 12, 256) == 0xFF00FF, "fresh chunk drawn " + Integer.toHexString(px(256, 256)));
        s5.close();
        MapIO.flush(5000);
        check(new File(dir2, "r.3.-1.mmr").isFile(), "fresh region saved on close");
        check(!new File(dir2, "r.3.-1.mmr.tmp").exists(), "no temp file left");

        // ---------------- 13. corrupt file is quarantined, not fatal
        File dir3 = new File(base, "v4/corrupt"); dir3.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir3, "r.0.0.mmr"))) { o.write(new byte[100]); }
        MapStorage s6 = new MapStorage(dir3, null);
        for (int i = 0; i < 5; i++) { s6.beginFrame(); beginGui(); MapDraw.draw(s6, 10, 10, 1, 0, 256, 256, RX, RY, 4, 1f, 1, true); MapIO.flush(2000); s6.tick(); }
        glErr("test 13");
        check(new File(dir3, "r.0.0.mmr.corrupt").isFile(), "corrupt file quarantined");
        check(s6.putChunk(0, 0, flat), "region usable after corrupt load");
        s6.close();
        MapIO.flush(5000);

        s2.close();
        glErr("end");
        pb.destroy();
        System.out.println(checks + " checks, " + (fails == 0 ? "ALL GL TESTS PASSED" : fails + " FAILURES"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
