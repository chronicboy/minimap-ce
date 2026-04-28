package net.fabricmc.example.minimap;

import net.minecraft.src.*;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class WaypointBeamRenderer {
    private static final float DOT_THRESHOLD = 0.966f; // cos(15°)
    private static final float DIST_CUTOFF = 10.0f;

    public static void renderAll(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null)
            return;
        if (!MapConfig.instance.showWaypoints)
            return;

        double camX = RenderManager.renderPosX;
        double camY = RenderManager.renderPosY;
        double camZ = RenderManager.renderPosZ;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);

        // DISABLE ALPHA TEST to prevent flickering
        GL11.glDisable(GL11.GL_ALPHA_TEST);

        // ---- Pass 1: Beams + Icons (No Texture, Depth Test ON but No Write) ----
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);

        renderPass(mc, camX, camY, camZ, true);

        // ---- Pass 2: Labels (Texture ON, Depth Test OFF) ----
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        // Depth mask still false

        renderPass(mc, camX, camY, camZ, false);

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private static void renderPass(Minecraft mc, double camX, double camY, double camZ, boolean minimal) {
        if (MapConfig.instance.showSpawnWaypoint && SpawnTracker.instance.hasSpawn()) {
            int dim = mc.theWorld.provider.dimensionId;
            int sx, sy, sz;
            if (dim == 1) {
                sx = 100;
                sy = 49;
                sz = 0;
            } else if (dim == -1) {
                sx = SpawnTracker.instance.getNetherSpawnX();
                sz = SpawnTracker.instance.getNetherSpawnZ();
                sy = SpawnTracker.instance.getSpawnY();
            } else {
                sx = SpawnTracker.instance.getSpawnX();
                sz = SpawnTracker.instance.getSpawnZ();
                sy = SpawnTracker.instance.getSpawnY();
            }
            renderWaypoint(mc, "Spawn", sx, sy, sz, 0x5599FF, camX, camY, camZ, minimal);
        }

        List<Waypoint> waypoints = WaypointManager.instance.getWaypoints();
        for (Waypoint wp : waypoints) {
            if (!wp.enabled)
                continue;
            renderWaypoint(mc, wp.name, wp.x, wp.y, wp.z, wp.color, camX, camY, camZ, minimal);
        }
    }

    private static void renderWaypoint(Minecraft mc, String name, int wx, int wy, int wz, int color,
            double camX, double camY, double camZ, boolean minimal) {
        double dx = wx + 0.5 - camX;
        double dy = wy + 0.5 - camY;
        double dz = wz + 0.5 - camZ;

        double distH = Math.sqrt(dx * dx + dz * dz);
        if (distH < DIST_CUTOFF)
            return;

        double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean focused = isFocused(dx, dy, dz, dist3D, mc);

        // Project beam to render distance boundary if too far
        // This ensures beams are always visible even beyond MC's render distance
        double renderDx = dx;
        double renderDy = dy;
        double renderDz = dz;

        // Use the actual clipping distance from the renderer, but stay within GL clip range
        // Note: 0.95 factor ensures it's clearly inside the clipping plane
        double maxRender = 500.0; 
        
        try {
            // Shadow access to farPlaneDistance would be better, but we can safely 
            // calculate it from renderDistance setting if field access is complex.
            // Short = 32, Tiny = 16, etc.
            float farPlane = 256 >> mc.gameSettings.renderDistance;
            maxRender = Math.min(500.0, farPlane * 0.95);
        } catch (Exception e) {}

        if (distH > maxRender) {
            double ratio = maxRender / distH;
            renderDx = dx * ratio;
            renderDy = dy * ratio; // Flatten Y when projected
            renderDz = dz * ratio;
        }

        float r = (float) (color >> 16 & 255) / 255.0F;
        float g = (float) (color >> 8 & 255) / 255.0F;
        float b = (float) (color & 255) / 255.0F;

        if (minimal) {
            renderBeacon(renderDx, renderDy, renderDz, r, g, b, 1.0f, mc);
            renderDiamond(renderDx, renderDy, renderDz, r, g, b, 1.0f, mc);
        } else {
            if (focused) {
                double renderDist3D = Math.sqrt(renderDx * renderDx + renderDy * renderDy + renderDz * renderDz);
                renderLabel(name, color, renderDx, renderDy, renderDz, dist3D, renderDist3D, 1.0f, mc);
            }
        }
    }

    private static boolean isFocused(double dx, double dy, double dz, double dist, Minecraft mc) {
        if (dist < 0.1)
            return true;

        dx /= dist;
        dy /= dist;
        dz /= dist;

        Entity viewer = mc.renderViewEntity;
        float yawRad = (float) Math.toRadians(viewer.rotationYaw);
        float pitchRad = (float) Math.toRadians(viewer.rotationPitch);
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

        double dot = dx * lookX + dy * lookY + dz * lookZ;
        return dot > DOT_THRESHOLD;
    }

    private static void renderBeacon(double dx, double dy, double dz,
            float r, float g, float b, float alpha, Minecraft mc) {
        GL11.glPushMatrix();
        GL11.glTranslated(dx, dy, dz);

        // "Even More translucent" Hollow Beam

        // Shell (The "outer part" that is visible)
        // Reduced opacity from 0.35 to 0.18
        GL11.glColor4f(r, g, b, 0.18f * alpha);
        drawBeamQuads(0.25f, 0, 256);

        // Minimal Outer Glow (very faint)
        GL11.glColor4f(r, g, b, 0.04f * alpha);
        drawBeamQuads(0.35f, 0, 256);

        GL11.glPopMatrix();
    }

    private static void drawBeamQuads(float half, float yMin, float yMax) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(-half, yMin, -half);
        GL11.glVertex3f(half, yMin, -half);
        GL11.glVertex3f(half, yMax, -half);
        GL11.glVertex3f(-half, yMax, -half);

        GL11.glVertex3f(half, yMin, half);
        GL11.glVertex3f(-half, yMin, half);
        GL11.glVertex3f(-half, yMax, half);
        GL11.glVertex3f(half, yMax, half);

        GL11.glVertex3f(-half, yMin, half);
        GL11.glVertex3f(-half, yMin, -half);
        GL11.glVertex3f(-half, yMax, -half);
        GL11.glVertex3f(-half, yMax, half);

        GL11.glVertex3f(half, yMin, -half);
        GL11.glVertex3f(half, yMin, half);
        GL11.glVertex3f(half, yMax, half);
        GL11.glVertex3f(half, yMax, -half);
        GL11.glEnd();
    }

    private static void renderDiamond(double dx, double dy, double dz,
            float r, float g, float b, float alpha, Minecraft mc) {
        float size = 0.35f;

        GL11.glPushMatrix();
        GL11.glTranslated(dx, dy + 0.5, dz); // Center of block
        GL11.glRotatef(-mc.renderViewEntity.rotationYaw, 0, 1, 0);
        GL11.glRotatef(mc.renderViewEntity.rotationPitch, 1, 0, 0);

        // 1. Filled Square (Solid color)
        GL11.glColor4f(r, g, b, 0.95f * alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(0, size, 0);
        GL11.glVertex3f(size, 0, 0);
        GL11.glVertex3f(0, -size, 0);
        GL11.glVertex3f(-size, 0, 0);
        GL11.glEnd();

        // 2. Outline (Black Line Loop)
        GL11.glLineWidth(2.0f);
        GL11.glColor4f(0.0f, 0.0f, 0.0f, 1.0f * alpha); // Black
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex3f(0, size, 0);
        GL11.glVertex3f(size, 0, 0);
        GL11.glVertex3f(0, -size, 0);
        GL11.glVertex3f(-size, 0, 0);
        GL11.glEnd();

        GL11.glPopMatrix();
    }

    private static void renderLabel(String name, int color, double dx, double dy, double dz,
            double trueDist, double renderDist, float alpha, Minecraft mc) {
        FontRenderer fr = mc.fontRenderer;
        if (fr == null)
            return;

        dy += 1.5;

        int blockDist = (int) trueDist;
        String text = name + " (" + blockDist + "m)";

        // Scale text with the visual rendered distance, not the true distance,
        // so it doesn't look gigantic when projected closer at low render distances.
        float scale = (float) Math.max(0.04, Math.min(0.6, renderDist * 0.003 + 0.04));
        int textW = fr.getStringWidth(text);
        int textH = 8;

        GL11.glPushMatrix();
        GL11.glTranslated(dx, dy, dz);
        GL11.glRotatef(-mc.renderViewEntity.rotationYaw, 0, 1, 0);
        GL11.glRotatef(mc.renderViewEntity.rotationPitch, 1, 0, 0);
        GL11.glScalef(-scale, -scale, scale);

        int pad = 2;
        int x1 = -textW / 2 - pad;
        int x2 = textW / 2 + pad;
        int y1 = -2;
        int y2 = textH + 2;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(0.1f, 0.1f, 0.1f, 0.7f * alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(x1, y1, 0);
        GL11.glVertex3f(x2, y1, 0);
        GL11.glVertex3f(x2, y2, 0);
        GL11.glVertex3f(x1, y2, 0);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        int c = color | ((int) (255 * alpha) << 24);
        fr.drawStringWithShadow(text, -textW / 2, 0, c);

        GL11.glPopMatrix();
    }
}
