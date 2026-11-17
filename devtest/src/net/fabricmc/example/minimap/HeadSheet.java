package net.fabricmc.example.minimap;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cuts every mob face out of the game's real skin textures using the same
 * table the radar uses, and lays them out on one sheet to check by eye.
 *
 *   HeadSheet <minecraft jar> <output png>
 */
public class HeadSheet {
    public static void main(String[] a) throws Exception {
        Object[][] mobs = {
                { net.minecraft.src.EntityOtherPlayerMP.class, "entity/steve.png" },
                { net.minecraft.src.EntityZombie.class, "entity/zombie/zombie.png" },
                { net.minecraft.src.EntityPigZombie.class, "entity/zombie_pigman.png" },
                { net.minecraft.src.EntitySkeleton.class, "entity/skeleton/" },
                { net.minecraft.src.EntityCreeper.class, "entity/creeper/creeper.png" },
                { net.minecraft.src.EntityCreeper.class, "entity/mob/creeper_snipped.png" },
                { net.minecraft.src.EntityEnderman.class, "entity/enderman/enderman.png" },
                { net.minecraft.src.EntityBlaze.class, "entity/blaze.png" },
                { net.minecraft.src.EntitySnowman.class, "entity/snowman.png" },
                { net.minecraft.src.EntitySlime.class, "entity/slime/slime.png" },
                { net.minecraft.src.EntityGhast.class, "entity/ghast/ghast" },
                { net.minecraft.src.EntitySpider.class, "entity/spider/" },
                { net.minecraft.src.EntitySpider.class, "entity/mob/spider/" },
                { net.minecraft.src.EntityPig.class, "entity/pig/pig.png" },
                { net.minecraft.src.EntityPig.class, "entity/mob/pig/" },
                { net.minecraft.src.EntityCow.class, "entity/cow/" },
                { net.minecraft.src.EntityCow.class, "entity/mob/cow/" },
                { net.minecraft.src.EntitySheep.class, "entity/sheep/sheep.png" },
                { net.minecraft.src.EntitySheep.class, "entity/mob/sheep/" },
                { net.minecraft.src.EntityChicken.class, "entity/chicken.png" },
                { net.minecraft.src.EntityChicken.class, "entity/mob/chicken/" },
                { net.minecraft.src.EntityWolf.class, "entity/wolf/wolf" },
                { net.minecraft.src.EntityWolf.class, "entity/mob/wolf/wolf" },
                { net.minecraft.src.EntityOcelot.class, "entity/cat/" },
                { net.minecraft.src.EntityOcelot.class, "entity/mob/cat_tabby.png" },
                { net.minecraft.src.EntityVillager.class, "entity/villager/" },
                { net.minecraft.src.EntityVillager.class, "entity/mob/villager/" },
                { net.minecraft.src.EntityWitch.class, "entity/witch.png" },
                { net.minecraft.src.EntityIronGolem.class, "entity/iron_golem.png" },
                { net.minecraft.src.EntityHorse.class, "entity/horse/horse_" },
                { net.minecraft.src.EntityHorse.class, "entity/horse/donkey" },
                { net.minecraft.src.EntityHorse.class, "entity/horse/mule" },
                { net.minecraft.src.EntityHorse.class, "entity/mob/horse/" },
                { btw.entity.mob.DireWolfEntity.class, "entity/mob/wolf/beast.png" },
                { "zombie_villager", "entity/zombie/zombie_villager.png" },
        };

        List<BufferedImage> faces = new ArrayList<BufferedImage>();
        List<String> names = new ArrayList<String>();
        try (ZipFile zip = new ZipFile(a[0])) {
            for (Object[] mob : mobs) {
                EntityIcons.Head head = mob[0] instanceof Class ? EntityIcons.forClass((Class<?>) mob[0]) : EntityIcons.zombieVillagerHead();
                String key = (String) mob[1];
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    // default look only (skip the alternative "programmer art" set), vanilla and BTW namespaces
                    if (!n.startsWith("assets/") || !n.endsWith(".png") || !n.contains("/textures/" + key)) continue;
                    if (n.contains("_eyes") || n.contains("collar") || n.contains("_fur") || n.contains("saddle") || n.contains("armor") || n.contains("markings")) continue;
                    BufferedImage skin = ImageIO.read(zip.getInputStream(e));
                    if (skin == null || head == null) continue;
                    // Same maths as EntityIcons.drawHead: table units -> real texels through the image's own shape.
                    double unitsHigh = head.unitsWide * skin.getHeight() / (double) skin.getWidth();
                    BufferedImage face = cut(skin, head.x, head.y, head.w, head.h, head.unitsWide, unitsHigh);
                    if (head.overlayX >= 0) {
                        Graphics2D g = face.createGraphics();
                        g.drawImage(cut(skin, head.overlayX, head.overlayY, head.w, head.h, head.unitsWide, unitsHigh), 0, 0, null);
                        g.dispose();
                    }
                    faces.add(face);
                    names.add(n.substring(n.indexOf("/textures/entity/") + 17).replace(".png", ""));
                }
            }
        }

        int cols = 8, cell = 110, rows = (faces.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * cell, rows * cell, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(60, 90, 50));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(g.getFont().deriveFont(9f));
        for (int i = 0; i < faces.size(); i++) {
            BufferedImage f = faces.get(i);
            int x = (i % cols) * cell + 8, y = (i / cols) * cell + 6;
            int max = Math.max(f.getWidth(), f.getHeight());
            int w = 80 * f.getWidth() / max, h = 80 * f.getHeight() / max;
            g.setColor(Color.BLACK);
            g.fillRect(x - 2, y - 2, w + 4, h + 4);
            g.drawImage(f, x, y, w, h, null); // nearest neighbour by default
            g.setColor(Color.WHITE);
            String label = names.get(i);
            g.drawString(label.length() > 22 ? label.substring(label.length() - 22) : label, x - 4, y + 94);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(a[1]));
        System.out.println(faces.size() + " faces -> " + a[1]);
    }

    static BufferedImage cut(BufferedImage skin, int x, int y, int w, int h, double unitsWide, double unitsHigh) {
        double sx = skin.getWidth() / unitsWide, sy = skin.getHeight() / unitsHigh;
        int px = (int) Math.round(x * sx), py = (int) Math.round(y * sy);
        int pw = Math.max(1, (int) Math.round(w * sx)), ph = Math.max(1, (int) Math.round(h * sy));
        BufferedImage out = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        for (int j = 0; j < ph; j++) for (int i = 0; i < pw; i++) {
            int qx = px + i, qy = py + j;
            if (qx < skin.getWidth() && qy < skin.getHeight()) out.setRGB(i, j, skin.getRGB(qx, qy));
        }
        return out;
    }
}
