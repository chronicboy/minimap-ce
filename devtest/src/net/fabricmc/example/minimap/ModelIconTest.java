package net.fabricmc.example.minimap;

import net.fabricmc.example.minimap.engine.SharpShader;
import net.minecraft.src.Tessellator;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Draws the model-based horse icons (and some flat faces for comparison) from the game's real
 * textures with the radar's own code, at map size, at sub-pixel offsets and enlarged.
 *
 *   ModelIconTest <minecraft jar> <output png>
 */
public class ModelIconTest {
    static final int GS = 2, GW = 520, GH = 250 + 120, W = GW * GS, H = GH * GS;

    static int upload(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = img.getRGB(x, y);
            buf.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        }
        buf.flip();
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }

    public static void main(String[] a) throws Exception {
        Pbuffer pb = new Pbuffer(W, H, new PixelFormat(8, 24, 0), null);
        pb.makeCurrent();
        boolean sharp = SharpShader.available();

        String[] horses = { "horse/horse_white", "horse/horse_brown", "horse/horse_black", "horse/horse_chestnut",
                "horse/horse_creamy", "horse/horse_gray", "horse/horse_zombie", "horse/horse_skeleton", "horse/donkey",
                "horse/mule", "mob/horse/horse_brown_famished", "mob/horse/donkey_starving" };
        String[] flats = { "pig/pig", "cow/cow", "creeper/creeper", "sheep/sheep" };
        Class<?>[] flatClasses = { net.minecraft.src.EntityPig.class, net.minecraft.src.EntityCow.class,
                net.minecraft.src.EntityCreeper.class, net.minecraft.src.EntitySheep.class };
        List<int[]> tex = new ArrayList<int[]>();      // {id, w, h}
        List<Boolean> donkey = new ArrayList<Boolean>();
        List<int[]> flatTex = new ArrayList<int[]>();
        try (ZipFile zip = new ZipFile(a[0])) {
            for (String n : horses) {
                ZipEntry e = zip.getEntry("assets/minecraft/textures/entity/" + n + ".png");
                if (e == null) e = zip.getEntry("assets/btw/textures/entity/" + n + ".png");
                if (e == null) { System.out.println("  missing " + n); continue; }
                BufferedImage img = ImageIO.read(zip.getInputStream(e));
                tex.add(new int[] { upload(img), img.getWidth(), img.getHeight() });
                donkey.add(n.contains("donkey") || n.contains("mule"));
            }
            for (String n : flats) {
                BufferedImage img = ImageIO.read(zip.getInputStream(zip.getEntry("assets/minecraft/textures/entity/" + n + ".png")));
                flatTex.add(new int[] { upload(img), img.getWidth(), img.getHeight() });
            }
        }

        GL11.glViewport(0, 0, W, H);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
        GL11.glOrtho(0, GW, GH, 0, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity(); GL11.glTranslatef(0, 0, -2000);
        GL11.glClearColor(0.36f, 0.50f, 0.25f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        Tessellator t = Tessellator.instance;
        double px = 1.0 / GS;
        int prog = sharp ? SharpShader.begin() : 0;

        // Row 1: map size on whole pixels, flat faces first for comparison. Row 2: the same, a quarter pixel off.
        for (int row = 0; row < 2; row++) {
            double off = row == 0 ? 0.0 : 0.25;
            double x = 10, y = 12 + row * 22;
            for (int i = 0; i < flatTex.size(); i++, x += 16) {
                int[] f = flatTex.get(i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, f[0]);
                EntityIcons.drawBound(t, EntityIcons.forClass(flatClasses[i]), f[1], f[2], x + off, y + off, 1.0f, px, sharp);
            }
            x += 8;
            for (int i = 0; i < tex.size(); i++, x += 18) {
                int[] h = tex.get(i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, h[0]);
                EntityIcons.drawBound(t, EntityIcons.horseHead(donkey.get(i)), h[1], h[2], x + off, y + off, 1.0f, px, sharp);
            }
        }
        // Row 3: enlarged, to judge the shapes.
        double keep = EntityIcons.modelIconSize;
        EntityIcons.modelIconSize = 52;
        double x = 10;
        for (int i = 0; i < Math.min(8, tex.size()); i++, x += 62) {
            int[] h = tex.get(i == 7 ? tex.size() - 3 : i);
            boolean dk = donkey.get(i == 7 ? tex.size() - 3 : i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, h[0]);
            EntityIcons.drawBound(t, EntityIcons.horseHead(dk), h[1], h[2], x, 70, 1.0f, px * 4, sharp);
        }
        // Row 4: the dark variant used for mobs far above or below, enlarged.
        x = 10;
        for (int i = 0; i < 4; i++, x += 62) {
            int[] h = tex.get(i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, h[0]);
            EntityIcons.drawBound(t, EntityIcons.horseHead(false), h[1], h[2], x, 140, 0.55f, px * 4, sharp);
        }
        // Row 5: candidate poses {tilt, yaw, pitch} in degrees, white horse, enlarged.
        if (a.length > 2) {
            String[] poses = a[2].split(";");
            x = 10;
            for (String p : poses) {
                String[] v = p.split(",");
                EntityIcons.horseTilt = Math.toRadians(Double.parseDouble(v[0]));
                EntityIcons.horseYaw = Math.toRadians(Double.parseDouble(v[1]));
                EntityIcons.horsePitch = Math.toRadians(Double.parseDouble(v[2]));
                EntityIcons.rebuildModelIcons();
                int[] h = tex.get(4);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, h[0]);
                EntityIcons.drawBound(t, EntityIcons.horseHead(false), h[1], h[2], x, 190, 1.0f, px * 4, sharp);
                int[] d = tex.get(8);
                EntityIcons.modelIconSize = 11;
                EntityIcons.drawBound(t, EntityIcons.horseHead(false), h[1], h[2], x + 2, 176, 1.0f, px, sharp);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, d[0]);
                EntityIcons.drawBound(t, EntityIcons.horseHead(true), d[1], d[2], x + 18, 176, 1.0f, px, sharp);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, h[0]);
                EntityIcons.modelIconSize = 52;
                x += 62;
            }
        }
        EntityIcons.modelIconSize = keep;
        if (sharp) SharpShader.end(prog);

        int err = GL11.glGetError();
        GL11.glFinish();
        IntBuffer buf = BufferUtils.createIntBuffer(W * H);
        GL11.glReadPixels(0, 0, W, H, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] rowPx = new int[W];
        for (int y = 0; y < H; y++) { buf.position((H - 1 - y) * W); buf.get(rowPx); img.setRGB(0, y, W, 1, rowPx, 0, W); }
        ImageIO.write(img, "png", new File(a[1]));

        // Each map-size horse icon must have drawn something and be about the size it claims.
        int bg = img.getRGB(2, 2) & 0xFFFFFF, failures = 0;
        double iconW = EntityIcons.iconWidth(EntityIcons.horseHead(false)), iconH = EntityIcons.iconHeight(EntityIcons.horseHead(false));
        double hx = 10 + flatTex.size() * 16 + 8;
        for (int i = 0; i < tex.size(); i++, hx += 18) {
            int painted = 0;
            for (int yy = 12 * GS - 2; yy < (12 + 16) * GS; yy++)
                for (int xx = (int) (hx * GS) - 2; xx < (int) ((hx + 16) * GS); xx++)
                    if ((img.getRGB(xx, yy) & 0xFFFFFF) != bg) painted++;
            if (painted < iconW * iconH * GS * GS * 0.35) { failures++; System.out.println("  FAIL icon " + horses[i] + " painted " + painted); }
        }
        System.out.println("model icons: " + tex.size() + " horses, icon " + String.format("%.1f x %.1f", iconW, iconH)
                + " GUI px, faces drawn " + EntityIcons.horseHead(false).model.faceCount() + ", sharp shader " + sharp + ", GL error " + err);
        System.out.println(failures == 0 && err == 0 ? "MODEL ICON TEST PASSED" : "MODEL ICON TEST FAILED");
        pb.destroy();
        System.exit(failures == 0 && err == 0 ? 0 : 1);
    }
}
