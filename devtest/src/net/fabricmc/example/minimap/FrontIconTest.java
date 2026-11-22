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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Draws the straight-on model icons (snouts, beaks, noses, horns, ears) from the game's real skins with
 * the radar's own code, at map size and enlarged, and checks that icons clipped to a round minimap leave
 * nothing outside it.
 *
 *   FrontIconTest <minecraft jar> <output png>
 */
public class FrontIconTest {
    static final int GS = 2, GW = 400, GH = 230, W = GW * GS, H = GH * GS;

    static final String[] NAMES = { "pig/pig", "cow/cow", "cow/mooshroom", "chicken", "wolf/wolf", "wolf/wolf_angry",
            "mob/wolf/beast", "cat/ocelot", "villager/farmer", "villager/librarian", "witch", "iron_golem",
            "zombie/zombie_villager" };
    static final Class<?>[] CLASSES = { net.minecraft.src.EntityPig.class, net.minecraft.src.EntityCow.class,
            net.minecraft.src.EntityMooshroom.class, net.minecraft.src.EntityChicken.class, net.minecraft.src.EntityWolf.class,
            net.minecraft.src.EntityWolf.class, btw.entity.mob.DireWolfEntity.class, net.minecraft.src.EntityOcelot.class,
            net.minecraft.src.EntityVillager.class, net.minecraft.src.EntityVillager.class, net.minecraft.src.EntityWitch.class,
            net.minecraft.src.EntityIronGolem.class, null };

    static int[] upload(BufferedImage img) {
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
        return new int[] { id, w, h };
    }

    public static void main(String[] a) throws Exception {
        Pbuffer pb = new Pbuffer(W, H, new PixelFormat(8, 24, 0), null);
        pb.makeCurrent();
        boolean sharp = SharpShader.available();
        int[][] tex = new int[NAMES.length][];
        try (ZipFile zip = new ZipFile(a[0])) {
            for (int i = 0; i < NAMES.length; i++) {
                ZipEntry e = zip.getEntry("assets/minecraft/textures/entity/" + NAMES[i] + ".png");
                if (e == null) e = zip.getEntry("assets/btw/textures/entity/" + NAMES[i] + ".png");
                tex[i] = upload(ImageIO.read(zip.getInputStream(e)));
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
        int failures = 0;

        // Row 1: map size, on whole pixels. Row 2: enlarged 4x (drawn with a scaled matrix).
        double x = 8;
        double[] lefts = new double[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            EntityIcons.Head head = CLASSES[i] != null ? EntityIcons.forClass(CLASSES[i]) : EntityIcons.zombieVillagerHead();
            if (head == null || head.model == null) { System.out.println("  FAIL no model icon for " + NAMES[i]); failures++; continue; }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[i][0]);
            lefts[i] = x;
            EntityIcons.drawBound(t, head, tex[i][1], tex[i][2], x, 10, 1.0f, px, sharp);
            GL11.glPushMatrix();
            GL11.glTranslated(x * 1.0, 40, 0);
            GL11.glScaled(2.0, 2.0, 1.0);
            EntityIcons.drawBound(t, head, tex[i][1], tex[i][2], 0, 0, 1.0f, px / 2, sharp);
            GL11.glPopMatrix();
            System.out.println(String.format("  %-24s %4.1f x %4.1f GUI px, %d faces", NAMES[i], EntityIcons.iconWidth(head),
                    EntityIcons.iconHeight(head), head.model.faceCount()));
            x += 30;
        }

        // Clipping: a 60 px "minimap" circle with faces sliding out over its edge in every direction.
        double cx = 80, cy = 150, r = 40;
        int n = 72;
        double[] sx = new double[n], sy = new double[n];
        for (int i = 0; i < n; i++) { double ang = i * 2 * Math.PI / n; sx[i] = Math.cos(ang) * r; sy[i] = Math.sin(ang) * r; }
        if (sharp) SharpShader.end(prog);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(0.2f, 0.2f, 0.25f, 1f);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN); GL11.glVertex2d(cx, cy);
        for (int i = 0; i <= n; i++) GL11.glVertex2d(cx + sx[i % n], cy + sy[i % n]);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        prog = sharp ? SharpShader.begin() : 0;
        IconClip.set(cx, cy, sx, sy, n, r, true);
        for (int k = 0; k < 16; k++) {
            double ang = k * 2 * Math.PI / 16, d = r - 2 + (k % 4) * 2.5;
            int i = k % NAMES.length;
            EntityIcons.Head head = CLASSES[i] != null ? EntityIcons.forClass(CLASSES[i]) : EntityIcons.zombieVillagerHead();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[i][0]);
            double w = EntityIcons.iconWidth(head), h = EntityIcons.iconHeight(head);
            EntityIcons.drawBound(t, head, tex[i][1], tex[i][2], cx + Math.cos(ang) * d - w / 2 + 0.25, cy + Math.sin(ang) * d - h / 2, 1.0f, px, sharp);
        }
        // A flat face too (creeper), right on the edge.
        EntityIcons.Head creeper = EntityIcons.forClass(net.minecraft.src.EntityCreeper.class);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[0][0]);
        EntityIcons.drawBound(t, creeper, tex[0][1], tex[0][2], cx + r - 3, cy - 4, 1.0f, px, sharp);
        IconClip.clear();
        if (sharp) SharpShader.end(prog);

        int err = GL11.glGetError();
        GL11.glFinish();
        IntBuffer buf = BufferUtils.createIntBuffer(W * H);
        GL11.glReadPixels(0, 0, W, H, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[W];
        for (int y = 0; y < H; y++) { buf.position((H - 1 - y) * W); buf.get(row); img.setRGB(0, y, W, 1, row, 0, W); }
        ImageIO.write(img, "png", new File(a[1]));

        // Nothing may be painted outside the circle (allowing the one blended pixel on its edge).
        int bg = img.getRGB(2, 2) & 0xFFFFFF, outside = 0, inside = 0;
        for (int y = (int) ((cy - r - 20) * GS); y < (cy + r + 20) * GS; y++)
            for (int xx = (int) ((cx - r - 20) * GS); xx < (cx + r + 20) * GS; xx++) {
                double dx = (xx + 0.5) / GS - cx, dy = (y + 0.5) / GS - cy;
                double dist = Math.sqrt(dx * dx + dy * dy);
                boolean painted = (img.getRGB(xx, y) & 0xFFFFFF) != bg;
                if (dist > r + 0.75 && painted) outside++;
                if (dist < r - 1 && painted) inside++;
            }
        if (outside > 0) { failures++; System.out.println("  FAIL " + outside + " pixels painted outside the clip circle"); }
        // Each map-size icon must have painted something.
        for (int i = 0; i < NAMES.length; i++) {
            int painted = 0;
            for (int y = 10 * GS - 2; y < 26 * GS; y++)
                for (int xx = (int) (lefts[i] * GS) - 2; xx < (int) ((lefts[i] + 14) * GS); xx++)
                    if ((img.getRGB(xx, y) & 0xFFFFFF) != bg) painted++;
            if (painted < 20 * GS * GS) { failures++; System.out.println("  FAIL icon " + NAMES[i] + " painted " + painted); }
        }
        System.out.println("front icons: " + NAMES.length + ", clip: " + outside + " pixels outside, " + inside
                + " inside, sharp shader " + sharp + ", GL error " + err);
        System.out.println(failures == 0 && err == 0 ? "FRONT ICON TEST PASSED" : "FRONT ICON TEST FAILED");
        pb.destroy();
        System.exit(failures == 0 && err == 0 ? 0 : 1);
    }
}
