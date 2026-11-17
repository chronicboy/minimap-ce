import net.fabricmc.example.minimap.engine.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Renders a saved map (a folder of .mmr region files) to a PNG, to look at what
 * the game actually recorded. Works on a copy; the original files are not opened for writing.
 *
 *   RealMapView <map folder> <output folder> [centerX centerZ [scale]]
 */
public class RealMapView {
    public static void main(String[] a) throws Exception {
        File src = new File(a[0]), out = new File(a[1]);
        File copy = new File(out, "copy");
        copy.mkdirs();
        int minRx = Integer.MAX_VALUE, maxRx = Integer.MIN_VALUE, minRz = Integer.MAX_VALUE, maxRz = Integer.MIN_VALUE;
        for (File f : src.listFiles()) {
            if (!f.getName().endsWith(".mmr")) continue;
            Files.copy(f.toPath(), new File(copy, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            String[] p = f.getName().split("\\.");
            int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
            minRx = Math.min(minRx, rx); maxRx = Math.max(maxRx, rx); minRz = Math.min(minRz, rz); maxRz = Math.max(maxRz, rz);
        }
        double cx = a.length > 3 ? Double.parseDouble(a[2]) : (minRx + maxRx + 1) * 256.0;
        double cz = a.length > 3 ? Double.parseDouble(a[3]) : (minRz + maxRz + 1) * 256.0;
        double scale = a.length > 4 ? Double.parseDouble(a[4]) : 1.0;
        int W = 1024, H = 1024;

        Pbuffer pb = new Pbuffer(W, H, new PixelFormat(8, 24, 0), null);
        pb.makeCurrent();
        MapStorage s = new MapStorage(copy, null);
        double[] rx = { -W / 2.0, W / 2.0, W / 2.0, -W / 2.0 }, ry = { -H / 2.0, -H / 2.0, H / 2.0, H / 2.0 };
        for (int i = 0; i < 60; i++) {
            s.beginFrame();
            GL11.glViewport(0, 0, W, H);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity(); GL11.glOrtho(0, W, H, 0, 1000, 3000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity(); GL11.glTranslatef(0, 0, -2000);
            GL11.glClearColor(0.07f, 0.07f, 0.09f, 1f); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glEnable(GL11.GL_BLEND); GL11.glEnable(GL11.GL_TEXTURE_2D);
            boolean done = MapDraw.draw(s, cx, cz, scale, 0, W / 2.0, H / 2.0, rx, ry, 4, 1f, 1, true);
            s.tick(); MapIO.flush(2000); s.tick();
            if (done && i > 2) break;
        }
        GL11.glFinish();
        IntBuffer buf = BufferUtils.createIntBuffer(W * H);
        GL11.glReadPixels(0, 0, W, H, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[W];
        for (int y = 0; y < H; y++) { buf.position((H - 1 - y) * W); buf.get(row); img.setRGB(0, y, W, 1, row, 0, W); }
        File png = new File(out, "realmap.png");
        ImageIO.write(img, "png", png);
        System.out.println("regions x " + minRx + ".." + maxRx + " z " + minRz + ".." + maxRz + " centre " + cx + "," + cz + " -> " + png);
        s.close();
        pb.destroy();
        System.exit(0);
    }
}
