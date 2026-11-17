package net.fabricmc.example.minimap;

import net.fabricmc.example.minimap.engine.*;
import net.minecraft.src.Minecraft;
import net.minecraft.src.Tessellator;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;

/** Renders the real minimap code offscreen over synthetic terrain and saves PNGs to look at. */
public class VisualTest {
    static final int GUI_SCALE = 2;
    static final int GW = 480, GH = 300;          // GUI units
    static final int W = GW * GUI_SCALE, H = GH * GUI_SCALE;

    // ---- synthetic terrain -------------------------------------------------
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

    static void set(Object o, String f, Object v) throws Exception { Field fl = o.getClass().getDeclaredField(f); fl.setAccessible(true); fl.set(o, v); }

    static void beginGui() {
        GL11.glViewport(0, 0, W, H);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, GW, GH, 0.0D, 1000.0D, 3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity(); GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
    }

    static MinimapRenderer renderer = new MinimapRenderer();
    static Method drawMinimap, buildShape;
    static Minecraft fakeMc;

    static void minimap(int mapX, int mapY, int size, boolean circle, double viewX, double viewZ, int zoomLevel, float yaw, boolean rotate) throws Exception {
        MapConfig cfg = MapConfig.instance;
        cfg.minimapSize = size; cfg.minimapShape = circle ? 0 : 1; cfg.zoomLevel = zoomLevel; cfg.rotateMinimap = rotate;
        double rot = rotate ? Math.toRadians(180.0 - yaw) : 0.0;
        set(renderer, "radius", size / 2); set(renderer, "circle", circle);
        set(renderer, "centerX", (double) (mapX + size / 2)); set(renderer, "centerY", (double) (mapY + size / 2));
        set(renderer, "viewX", viewX); set(renderer, "viewZ", viewZ); set(renderer, "viewScale", cfg.minimapScale());
        set(renderer, "viewRotation", rot); set(renderer, "viewCos", Math.cos(rot)); set(renderer, "viewSin", Math.sin(rot));
        buildShape.invoke(renderer);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT | GL11.GL_LIGHTING_BIT); // same bits as renderMinimap
        drawMinimap.invoke(renderer, fakeMc, null, null, mapX, mapY, size, GW, GH, GUI_SCALE, 1.0f, yaw);
        GL11.glPopAttrib();
        // a few entity blips straight from the shared marker code
        Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_DEPTH_TEST);
        t.startDrawing(GL11.GL_TRIANGLES);
        double cx = mapX + size / 2.0, cy = mapY + size / 2.0;
        MapMarkers.entityArrow(t, cx - 22, cy - 14, 0.4, 0.96f, 0.22f, 0.22f);
        MapMarkers.entityArrow(t, cx + 18, cy + 25, 2.5, 0.22f, 0.90f, 0.35f);
        MapMarkers.entityArrow(t, cx + 30, cy - 20, 4.1, 0.25f, 0.75f, 1.0f);
        MapMarkers.entityArrow(t, cx - 30, cy + 22, 5.3, 1.0f, 0.80f, 0.15f);
        MapMarkers.dot(t, cx + 2, cy + 34, 2.2, 0.96f, 0.22f, 0.22f);
        MapMarkers.dot(t, cx - 12, cy + 30, 2.2, 0.22f, 0.90f, 0.35f);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    static void save(File f) throws Exception {
        GL11.glFinish();
        IntBuffer buf = BufferUtils.createIntBuffer(W * H);
        GL11.glReadPixels(0, 0, W, H, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[W];
        for (int y = 0; y < H; y++) { buf.position((H - 1 - y) * W); buf.get(row); img.setRGB(0, y, W, 1, row, 0, W); }
        ImageIO.write(img, "png", f);
        // 4x nearest-neighbour crop of the first minimap for a close look at the markers
        int cw = 150, ch = 150, z = 4;
        BufferedImage crop = new BufferedImage(cw * z, ch * z, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < ch * z; y++) for (int x = 0; x < cw * z; x++) crop.setRGB(x, y, img.getRGB(70 + x / z, 60 + y / z));
        ImageIO.write(crop, "png", new File(f.getParentFile(), "crop.png"));
        // 3x crop of the turned square map (frame, compass badges, unexplored corners)
        int c2 = 300, z2 = 3;
        BufferedImage crop2 = new BufferedImage(c2 * z2, c2 * z2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < c2 * z2; y++) for (int x = 0; x < c2 * z2; x++) crop2.setRGB(x, y, img.getRGB(300 + x / z2, 300 + y / z2));
        ImageIO.write(crop2, "png", new File(f.getParentFile(), "crop-square.png"));
        System.out.println("wrote " + f);
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        Pbuffer pb = new Pbuffer(W, H, new PixelFormat(8, 24, 0), null);
        pb.makeCurrent();

        MapStorage storage = new MapStorage(new File(out, "world"), null);
        int[] buf = new int[256];
        // explored area: chunks -40..40 with a ragged "unexplored" edge and a hole; crosses region borders at 0 and +-512
        for (int cz = -40; cz <= 40; cz++) for (int cx = -40; cx <= 40; cx++) {
            if (cx * cx + cz * cz > 38 * 38 + hash(cx, cz) * 300) continue;
            if (Math.abs(cx - 9) < 2 && Math.abs(cz + 6) < 2) continue;
            for (int i = 0; i < 256; i++) buf[i] = tile(cx * 16 + (i & 15), cz * 16 + (i >> 4));
            storage.putChunk(cx, cz, buf);
        }
        Field sf = MapEngine.class.getDeclaredField("storage"); sf.setAccessible(true); sf.set(MapEngine.INSTANCE, storage);

        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
        fakeMc = (Minecraft) ((sun.misc.Unsafe) uf.get(null)).allocateInstance(Minecraft.class);
        drawMinimap = MinimapRenderer.class.getDeclaredMethod("drawMinimap", Minecraft.class, net.minecraft.src.World.class, net.minecraft.src.EntityPlayer.class,
                int.class, int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        drawMinimap.setAccessible(true);
        buildShape = MinimapRenderer.class.getDeclaredMethod("buildShape"); buildShape.setAccessible(true);

        MapConfig cfg = MapConfig.instance;
        cfg.showEntities = false; cfg.showSpawnWaypoint = false; cfg.showWaypoints = true;
        WaypointManager.instance.getWaypoints().add(new Waypoint("Home", 30, 64, -25, 0xFF5555));
        WaypointManager.instance.getWaypoints().add(new Waypoint("Mine", -40, 64, 35, 0x55FF55));
        WaypointManager.instance.getWaypoints().add(new Waypoint("Far", 900, 64, -700, 0xFFFF55));

        for (int pass = 0; pass < 6; pass++) {      // several frames so every region texture is created
            storage.beginFrame();
            beginGui();
            // "sky + grass" backdrop to judge contrast against a game-like background
            GL11.glClearColor(0.45f, 0.62f, 0.85f, 1f); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glColor4f(0.30f, 0.45f, 0.22f, 1f);
            GL11.glBegin(GL11.GL_QUADS); GL11.glVertex2f(0, 170); GL11.glVertex2f(0, GH); GL11.glVertex2f(GW, GH); GL11.glVertex2f(GW, 170); GL11.glEnd();
            GL11.glColor4f(1, 1, 1, 1); GL11.glEnable(GL11.GL_TEXTURE_2D); GL11.glEnable(GL11.GL_CULL_FACE); GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glShadeModel(GL11.GL_FLAT); // what the game leaves set after drawing the world

            cfg.showChunkGrid = false; cfg.mapOpacity = 1.0f;
            minimap(10, 10, 128, true, 4.3, -3.7, 0, 200f, false);          // circle, 1x, north up
            minimap(160, 10, 128, false, 4.3, -3.7, -1, 35f, false);        // square, 2x
            cfg.showChunkGrid = true;
            minimap(310, 10, 128, true, 4.3, -3.7, 1, 135f, true);          // circle, 1/2x, rotating, grid
            minimap(10, 160, 128, false, 500.0, 20.0, 0, 300f, true);       // square, rotating, grid, at a region border
            cfg.showChunkGrid = false;
            minimap(160, 160, 128, false, -560.0, 150.0, 2, 135f, true);    // square turned 45 degrees, 1/4x, corners unexplored
            cfg.mapOpacity = 0.55f;
            minimap(310, 160, 128, true, 4.3, -3.7, -2, 10f, false);        // 4x, translucent
            storage.tick();
        }
        int err = GL11.glGetError();
        System.out.println("shade model restored to flat: " + (GL11.glGetInteger(GL11.GL_SHADE_MODEL) == GL11.GL_FLAT));
        System.out.println("GL error: " + err + ", depth test restored: " + GL11.glIsEnabled(GL11.GL_DEPTH_TEST) + ", cull restored: " + GL11.glIsEnabled(GL11.GL_CULL_FACE)
                + ", alpha test restored: " + GL11.glIsEnabled(GL11.GL_ALPHA_TEST) + ", blend: " + GL11.glIsEnabled(GL11.GL_BLEND));
        save(new File(out, "minimaps.png"));
        storage.close();
        pb.destroy();
        System.exit(0);
    }
}
