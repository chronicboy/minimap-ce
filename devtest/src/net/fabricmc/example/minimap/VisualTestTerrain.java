package net.fabricmc.example.minimap;

import net.fabricmc.example.minimap.engine.MapShade;

/** Synthetic terrain shared by the visual tools: hills, lakes, sand, forest speckle, stone and snow. */
final class VisualTestTerrain {
    static double hash(int x, int z) { int h = x * 374761393 + z * 668265263; h = (h ^ (h >>> 13)) * 1274126177; return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0; }
    static double smooth(double t) { return t * t * (3 - 2 * t); }
    static double noise(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z); double fx = smooth(x - xi), fz = smooth(z - zi);
        double a = hash(xi, zi), b = hash(xi + 1, zi), c = hash(xi, zi + 1), d = hash(xi + 1, zi + 1);
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
    }
    static double fbm(double x, double z) { double s = 0, amp = 1, f = 1, n = 0; for (int i = 0; i < 5; i++) { s += noise(x * f, z * f) * amp; n += amp; amp *= 0.5; f *= 2; } return s / n; }

    static int tile(int x, int z) {
        double e = fbm(x / 90.0 + 7, z / 90.0 + 3);
        int h = (int) (40 + e * 75);
        int sea = 63;
        if (h < sea) {
            int depth = sea - h; int floor = 0xD6CF9E; int water = 0x2F43F4;
            int op = Math.min(236, 115 + depth * 18);
            int rgb = MapShade.scale(MapShade.lerp(floor, water, op), 256 - Math.min(depth, 20) * 4);
            return MapShade.pack(rgb, sea - 1);
        }
        int rgb;
        if (h < sea + 2) rgb = 0xDBD3A0;                       // sand
        else if (h < 92) { rgb = MapShade.multiply(0x959595, fbm(x / 200.0, z / 200.0) > 0.5 ? 0x91BD59 : 0x79C05A);
            if (hash(x * 3 + 11, z * 3 + 5) > 0.93 && h > sea + 4) { rgb = MapShade.multiply(0x7A7A7A, 0x77AB2F); h += 5; } } // trees
        else if (h < 104) rgb = 0x7D7D7D;                      // stone
        else rgb = 0xF0FBFB;                                   // snow
        return MapShade.pack(rgb, h);
    }

}
