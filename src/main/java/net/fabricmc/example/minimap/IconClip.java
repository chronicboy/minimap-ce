package net.fabricmc.example.minimap;

import net.minecraft.src.Tessellator;

/**
 * Clips textured icon quads to the minimap's shape, so a mob near the edge slides under the frame
 * instead of popping in and out. Quads are emitted as triangles (the tessellator must be in
 * GL_TRIANGLES mode). With no shape set, quads pass through unchanged.
 */
final class IconClip {
	private static final int MAX = 160;

	private static boolean active;
	private static double[] shapeX, shapeY;
	private static int shapeN;
	private static double originX, originY;
	/** Orientation of the shape: +1 or -1, so "inside" is the same side of every edge. */
	private static double sign;
	/** Points closer to the origin than this are inside the shape for sure (circle-like shapes). */
	private static double safeRadius;
	private static boolean square;
	private static double half;

	private static final double[] ax = new double[MAX], ay = new double[MAX], au = new double[MAX], av = new double[MAX];
	private static final double[] bx = new double[MAX], by = new double[MAX], bu = new double[MAX], bv = new double[MAX];

	private IconClip() {
	}

	/**
	 * Clip to a convex shape given relative to (x, y).
	 *
	 * @param circle true for the round minimap, false for the square one (an axis-aligned square)
	 */
	static void set(double x, double y, double[] sx, double[] sy, int n, double radius, boolean circle) {
		originX = x;
		originY = y;
		shapeX = sx;
		shapeY = sy;
		shapeN = n;
		double area = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			area += sx[i] * sy[j] - sx[j] * sy[i];
		}
		sign = area >= 0 ? 1.0 : -1.0;
		square = !circle;
		half = radius;
		safeRadius = circle ? radius * Math.cos(Math.PI / n) - 1.0e-6 : radius;
		active = n >= 3;
	}

	static void clear() {
		active = false;
		shapeX = shapeY = null;
	}

	/** Emits the quad (corners in order around it) as two triangles, clipped to the shape. */
	static void quad(Tessellator t, double x0, double y0, double u0, double v0, double x1, double y1, double u1,
			double v1, double x2, double y2, double u2, double v2, double x3, double y3, double u3, double v3) {
		if (!active || (inside(x0, y0) && inside(x1, y1) && inside(x2, y2) && inside(x3, y3))) {
			t.addVertexWithUV(x0, y0, 0.0, u0, v0);
			t.addVertexWithUV(x1, y1, 0.0, u1, v1);
			t.addVertexWithUV(x2, y2, 0.0, u2, v2);
			t.addVertexWithUV(x0, y0, 0.0, u0, v0);
			t.addVertexWithUV(x2, y2, 0.0, u2, v2);
			t.addVertexWithUV(x3, y3, 0.0, u3, v3);
			return;
		}
		ax[0] = x0 - originX; ay[0] = y0 - originY; au[0] = u0; av[0] = v0;
		ax[1] = x1 - originX; ay[1] = y1 - originY; au[1] = u1; av[1] = v1;
		ax[2] = x2 - originX; ay[2] = y2 - originY; au[2] = u2; av[2] = v2;
		ax[3] = x3 - originX; ay[3] = y3 - originY; au[3] = u3; av[3] = v3;
		int m = 4;
		double[] px = ax, py = ay, pu = au, pv = av, qx = bx, qy = by, qu = bu, qv = bv;
		for (int e = 0; e < shapeN && m > 0; e++) {
			double ex0 = shapeX[e], ey0 = shapeY[e];
			int f = (e + 1) % shapeN;
			double ex = shapeX[f] - ex0, ey = shapeY[f] - ey0;
			int k = 0;
			for (int i = 0; i < m; i++) {
				int j = (i + 1) % m;
				double di = sign * (ex * (py[i] - ey0) - ey * (px[i] - ex0));
				double dj = sign * (ex * (py[j] - ey0) - ey * (px[j] - ex0));
				if (di >= 0 && k < MAX) {
					qx[k] = px[i]; qy[k] = py[i]; qu[k] = pu[i]; qv[k] = pv[i];
					k++;
				}
				if ((di >= 0) != (dj >= 0) && k < MAX) {
					double s = di / (di - dj);
					qx[k] = px[i] + (px[j] - px[i]) * s;
					qy[k] = py[i] + (py[j] - py[i]) * s;
					qu[k] = pu[i] + (pu[j] - pu[i]) * s;
					qv[k] = pv[i] + (pv[j] - pv[i]) * s;
					k++;
				}
			}
			double[] tx = px, ty = py, tu = pu, tv = pv;
			px = qx; py = qy; pu = qu; pv = qv;
			qx = tx; qy = ty; qu = tu; qv = tv;
			m = k;
		}
		for (int i = 1; i + 1 < m; i++) {
			t.addVertexWithUV(originX + px[0], originY + py[0], 0.0, pu[0], pv[0]);
			t.addVertexWithUV(originX + px[i], originY + py[i], 0.0, pu[i], pv[i]);
			t.addVertexWithUV(originX + px[i + 1], originY + py[i + 1], 0.0, pu[i + 1], pv[i + 1]);
		}
	}

	private static boolean inside(double x, double y) {
		double dx = x - originX, dy = y - originY;
		if (square)
			return Math.abs(dx) <= half && Math.abs(dy) <= half;
		return dx * dx + dy * dy <= safeRadius * safeRadius;
	}
}
