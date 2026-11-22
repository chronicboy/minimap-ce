package net.fabricmc.example.minimap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

import java.lang.reflect.Field;
import java.util.List;

/**
 * In-world waypoint labels: sets up the game's camera (perspective, pitch, yaw + 180 as EntityRenderer
 * does), captures it like the world pass, and checks where waypoints land on the HUD.
 */
public class WaypointLabelTest {
    static final int W = 800, H = 600, GUI = 2;
    static int failures;

    static void camera(float yaw, float pitch) {
        GL11.glViewport(0, 0, W, H);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        double near = 0.05, far = 512, top = near * Math.tan(Math.toRadians(70) / 2), right = top * W / H;
        GL11.glFrustum(-right, right, -top, top, near, far);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glRotatef(pitch, 1, 0, 0);
        GL11.glRotatef(yaw + 180.0f, 0, 1, 0);
    }

    @SuppressWarnings("unchecked")
    static double[] place(int x, int y, int z) throws Exception {
        Field lf = WaypointBeamRenderer.class.getDeclaredField("labels");
        lf.setAccessible(true);
        List<Object> labels = (List<Object>) lf.get(null);
        labels.clear();
        WaypointBeamRenderer.addLabel("test", x, y, z, 0xFF0000, GUI, H);
        if (labels.isEmpty())
            return null;
        Object l = labels.get(0);
        Field fx = l.getClass().getDeclaredField("x"), fy = l.getClass().getDeclaredField("y"), fd = l.getClass().getDeclaredField("distance");
        fx.setAccessible(true); fy.setAccessible(true); fd.setAccessible(true);
        return new double[] { fx.getDouble(l), fy.getDouble(l), fd.getDouble(l) };
    }

    static void check(String what, boolean ok, double[] p) {
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + what + (p == null ? "  (hidden)" : String.format("  at %.1f, %.1f, %.1f m", p[0], p[1], p[2])));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Pbuffer pb = new Pbuffer(W, H, new PixelFormat(8, 24, 0), null);
        pb.makeCurrent();
        for (String f : new String[] { "camX", "camY", "camZ" }) {
            Field cf = WaypointBeamRenderer.class.getDeclaredField(f);
            cf.setAccessible(true);
            cf.setDouble(null, f.equals("camY") ? 0.2 : 0.5); // so waypoint (0, 0, z) is at the eye's height (marker at y + 0.2)
        }
        double cx = W / 2.0 / GUI, cy = H / 2.0 / GUI;

        // Facing south (+z), level.
        camera(0, 0);
        WaypointBeamRenderer.captureCamera();
        double[] p = place(0, 0, 20);
        check("straight ahead is at the crosshair", p != null && Math.abs(p[0] - cx) < 0.01 && Math.abs(p[1] - cy) < 0.01 && Math.abs(p[2] - 20) < 1e-6, p);
        p = place(8, 0, 20);
        check("east is on the left when facing south", p != null && p[0] < cx - 10, p);
        p = place(0, 6, 20);
        check("higher is above", p != null && p[1] < cy - 10, p);
        p = place(0, 0, -20);
        check("behind is hidden", p == null, p);
        p = place(0, 0, 1);
        check("too close is hidden", p == null, p);
        p = place(0, 0, 5000);
        check("far beyond the render distance still shows", p != null && Math.abs(p[0] - cx) < 0.01, p);

        // Facing east (yaw -90), looking 30 degrees down.
        camera(-90, 30);
        WaypointBeamRenderer.captureCamera();
        p = place(20, 0, 0);
        check("facing east: east is in the upper middle", p != null && Math.abs(p[0] - cx) < 0.01 && p[1] < cy, p);
        p = place(20, -(int) Math.round(20 * Math.tan(Math.toRadians(30))), 0);
        check("looking down: the point 30 degrees below is at the crosshair", p != null && Math.abs(p[1] - cy) < 4, p);
        p = place(-20, 0, 0);
        check("facing east: west is hidden", p == null, p);

        // 90 degree field of view edge: a point 60 degrees to the side is off screen (horizontal half FOV ~ 43).
        camera(0, 0);
        WaypointBeamRenderer.captureCamera();
        p = place((int) Math.round(20 * Math.tan(Math.toRadians(60))), 0, 20);
        check("well outside the view is hidden", p == null, p);

        System.out.println("distance text: " + WaypointBeamRenderer.formatDistance(87.9) + ", " + WaypointBeamRenderer.formatDistance(1234)
                + ", " + WaypointBeamRenderer.formatDistance(25600));
        System.out.println(failures == 0 ? "WAYPOINT LABEL TEST PASSED" : "WAYPOINT LABEL TEST FAILED");
        pb.destroy();
        System.exit(failures == 0 ? 0 : 1);
    }
}
