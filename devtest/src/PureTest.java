import net.fabricmc.example.minimap.engine.*;
import java.io.*;
import java.util.*;

public class PureTest {
    static int fails = 0;
    static void check(boolean c, String msg) { if (!c) { fails++; System.out.println("FAIL: " + msg); } }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        Random rnd = new Random(42);

        // ---- codec round trip
        int[][] chunks = new int[1024][];
        for (int i = 0; i < 1024; i++) {
            if (rnd.nextInt(3) == 0) continue;
            int[] c = new int[256];
            int baseH = 60 + rnd.nextInt(20);
            for (int j = 0; j < 256; j++) c[j] = rnd.nextInt(20) == 0 ? 0 : MapShade.pack(0x30A030 + rnd.nextInt(16), baseH + rnd.nextInt(3));
            chunks[i] = c;
        }
        File f = new File(dir, "r.-3.7.mmr");
        long t0 = System.nanoTime();
        RegionCodec.write(f, chunks);
        long t1 = System.nanoTime();
        int[][] back = RegionCodec.read(f);
        long t2 = System.nanoTime();
        for (int i = 0; i < 1024; i++) check(Arrays.equals(chunks[i], back[i]), "chunk " + i + " mismatch");
        int[] lod = RegionCodec.readLod(f);
        long t3 = System.nanoTime();
        check(Arrays.equals(lod, RegionCodec.buildLod(chunks)), "lod mismatch");
        check(!new File(dir, "r.-3.7.mmr.tmp").exists(), "tmp file left behind");
        System.out.printf("codec: write %.1fms read %.1fms lod %.1fms size %dKB (raw ~%dKB)%n", (t1-t0)/1e6, (t2-t1)/1e6, (t3-t2)/1e6, f.length()/1024, 683);

        // overwrite existing file must work (atomic replace)
        chunks[5] = null;
        chunks[6] = new int[256];
        Arrays.fill(chunks[6], MapShade.pack(0x123456, 70));
        RegionCodec.write(f, chunks);
        int[][] again = RegionCodec.read(f);
        check(again[5] == null && again[6] != null && again[6][0] == MapShade.pack(0x123456, 70), "overwrite failed");

        // empty region
        File e = new File(dir, "empty.mmr");
        RegionCodec.write(e, new int[1024][]);
        check(RegionCodec.isEmpty(RegionCodec.read(e)), "empty roundtrip");

        // corrupt file must throw, not return garbage
        File bad = new File(dir, "bad.mmr");
        try (FileOutputStream o = new FileOutputStream(bad)) { o.write(new byte[]{1,2,3,4,5,6,7,8,9}); }
        boolean threw = false;
        try { RegionCodec.read(bad); } catch (IOException ex) { threw = true; }
        check(threw, "corrupt file accepted");
        // truncated valid file must throw
        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        File trunc = new File(dir, "trunc.mmr");
        try (FileOutputStream o = new FileOutputStream(trunc)) { o.write(all, 0, all.length / 2); }
        threw = false;
        try { RegionCodec.read(trunc); } catch (IOException ex) { threw = true; }
        check(threw, "truncated file accepted");

        // ---- legacy v3 format (exactly as the 3.x MapTileManager wrote it)
        File lf = new File(dir, "r.0.0.dat");
        int[][] legacy = new int[1024][];
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(lf)))) {
            dos.writeInt(3);
            int[][] pos = {{0,0},{31,31},{7,19}};
            for (int[] p : pos) {
                dos.writeByte(p[0]); dos.writeByte(p[1]);
                int[] c = new int[256];
                for (int j = 0; j < 256; j++) { c[j] = (70 << 24) | rnd.nextInt(0xFFFFFF); dos.writeInt(c[j]); }
                legacy[p[1] * 32 + p[0]] = c;
            }
        }
        int[][] lback = RegionCodec.readLegacy(lf);
        for (int i = 0; i < 1024; i++) check(Arrays.equals(legacy[i], lback[i]), "legacy chunk " + i);
        // truncated legacy -> partial result, no exception
        byte[] lall = java.nio.file.Files.readAllBytes(lf.toPath());
        File ltr = new File(dir, "r.1.1.dat");
        try (FileOutputStream o = new FileOutputStream(ltr)) { o.write(lall, 0, 4 + 1026 + 500); }
        int[][] part = RegionCodec.readLegacy(ltr);
        int cnt = 0; for (int[] c : part) if (c != null) cnt++;
        check(cnt == 1, "truncated legacy should give 1 chunk, got " + cnt);

        // ---- pixels: buildPixels agrees with direct shade + dataAt
        int[] px = RegionCodec.buildPixels(back);
        for (int k = 0; k < 20000; k++) {
            int x = rnd.nextInt(512), z = rnd.nextInt(512);
            int d = RegionCodec.dataAt(back, x, z);
            int expect = d == 0 ? 0 : MapShade.shade(d, RegionCodec.dataAt(back, x, z - 1), RegionCodec.dataAt(back, x - 1, z));
            if (px[z * 512 + x] != expect) { check(false, "pixel mismatch at " + x + "," + z); break; }
        }
        check(RegionCodec.dataAt(back, -1, 0) == 0 && RegionCodec.dataAt(back, 512, 3) == 0 && RegionCodec.dataAt(back, 3, 512) == 0, "dataAt bounds");

        // ---- shade behaviour
        int flat = MapShade.shade(MapShade.pack(0x808080, 64), MapShade.pack(0, 64), MapShade.pack(0, 64));
        int up = MapShade.shade(MapShade.pack(0x808080, 66), MapShade.pack(0, 64), MapShade.pack(0, 64));
        int down = MapShade.shade(MapShade.pack(0x808080, 62), MapShade.pack(0, 64), MapShade.pack(0, 64));
        check((flat & 0xFF) == 0x80, "flat at sea level should be unchanged: " + Integer.toHexString(flat));
        check((up & 0xFF) > (flat & 0xFF) && (down & 0xFF) < (flat & 0xFF), "relief direction");
        check((flat >>> 24) == 0xFF, "alpha opaque");
        check(MapShade.shade(MapShade.pack(0xFFFFFF, 255), MapShade.pack(0, 1), MapShade.pack(0, 1)) == 0xFFFFFFFF, "clamp");
        check(MapShade.pack(0, 0) != 0, "pack never returns the unexplored marker");
        check(MapShade.shade(MapShade.pack(0x404040, 0), MapShade.pack(0, 90), 0) == 0xFF404040, "height 0 disables shading");
        check(MapShade.multiply(0xFFFFFF, 0x123456) == 0x123456, "multiply identity");
        check(MapShade.lerp(0x000000, 0xFFFFFF, 256) == 0xFFFFFF && MapShade.lerp(0x112233, 0xFFFFFF, 0) == 0x112233, "lerp ends");

        // ---- polygon clipping: pieces must tile the shape exactly
        PolyClip clip = new PolyClip();
        for (int trial = 0; trial < 200; trial++) {
            int n; double[] x, z;
            double cx = (rnd.nextDouble() - 0.5) * 4000, cz = (rnd.nextDouble() - 0.5) * 4000;
            double rad = 20 + rnd.nextDouble() * 900;
            if (trial % 2 == 0) { n = 64; x = new double[n]; z = new double[n];
                for (int i = 0; i < n; i++) { double an = i * Math.PI * 2 / n; x[i] = cx + Math.cos(an) * rad; z[i] = cz + Math.sin(an) * rad; } }
            else { n = 4; x = new double[n]; z = new double[n]; double rot = rnd.nextDouble() * 7;
                for (int i = 0; i < n; i++) { double an = rot + i * Math.PI / 2 + Math.PI / 4; x[i] = cx + Math.cos(an) * rad; z[i] = cz + Math.sin(an) * rad; } }
            double total = area(x, z, n), sum = 0;
            int rx0 = (int) Math.floor((cx - rad) / 512), rx1 = (int) Math.floor((cx + rad) / 512);
            int rz0 = (int) Math.floor((cz - rad) / 512), rz1 = (int) Math.floor((cz + rad) / 512);
            for (int rz = rz0; rz <= rz1; rz++) for (int rx = rx0; rx <= rx1; rx++) {
                int m = clip.clip(x, z, n, rx * 512.0, rz * 512.0, rx * 512.0 + 512, rz * 512.0 + 512);
                check(m <= PolyClip.MAX_VERTICES, "vertex overflow");
                for (int i = 0; i < m; i++) check(clip.outX[i] >= rx * 512.0 - 1e-6 && clip.outX[i] <= rx * 512.0 + 512 + 1e-6
                        && clip.outZ[i] >= rz * 512.0 - 1e-6 && clip.outZ[i] <= rz * 512.0 + 512 + 1e-6, "vertex outside rect");
                sum += area(clip.outX, clip.outZ, m);
            }
            check(Math.abs(sum - total) < 1e-6 * total, "area mismatch trial " + trial + ": " + sum + " vs " + total);
        }
        // fully outside -> 0
        check(clip.clip(new double[]{0,10,10,0}, new double[]{0,0,10,10}, 4, 100, 100, 200, 200) == 0, "outside should be 0");
        // fully inside -> unchanged count
        check(clip.clip(new double[]{110,120,120,110}, new double[]{110,110,120,120}, 4, 100, 100, 200, 200) == 4, "inside should be 4");

        // ---- IO executor ordering + flush
        final StringBuilder order = new StringBuilder();
        for (int i = 0; i < 5; i++) { final int k = i; MapIO.submit(new Runnable() { public void run() { order.append(k); } }); }
        MapIO.submit(new Runnable() { public void run() { throw new RuntimeException("boom (expected in log)"); } });
        MapIO.submit(new Runnable() { public void run() { order.append("x"); } });
        MapIO.flush(5000);
        check(order.toString().equals("01234x"), "io order " + order);

        System.out.println(fails == 0 ? "ALL PURE TESTS PASSED" : (fails + " FAILURES"));
        System.exit(fails == 0 ? 0 : 1);
    }
    static double area(double[] x, double[] z, int n) { double s = 0; for (int i = 0; i < n; i++) { int j = (i + 1) % n; s += x[i] * z[j] - x[j] * z[i]; } return Math.abs(s) / 2; }
}
