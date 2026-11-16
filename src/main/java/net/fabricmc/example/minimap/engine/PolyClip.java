package net.fabricmc.example.minimap.engine;

/**
 * Sutherland-Hodgman clipping of a convex polygon against an axis-aligned
 * rectangle. Used to cut the visible map shape (circle or rotated square) into
 * one piece per region texture, so the map is masked by geometry and needs no
 * stencil buffer, framebuffer object or per-pixel CPU work.
 *
 * Instances own their scratch buffers; not thread-safe.
 */
public final class PolyClip {
	public static final int MAX_VERTICES = 192;

	private final double[] ax = new double[MAX_VERTICES];
	private final double[] az = new double[MAX_VERTICES];
	private final double[] bx = new double[MAX_VERTICES];
	private final double[] bz = new double[MAX_VERTICES];

	/** Result vertices of the last {@link #clip} call. */
	public double[] outX = ax;
	public double[] outZ = az;

	/**
	 * @return number of vertices of the clipped polygon (0 when fully outside).
	 *         The input polygon may have at most MAX_VERTICES - 8 vertices.
	 */
	public int clip(double[] px, double[] pz, int n, double minX, double minZ, double maxX, double maxZ) {
		int m = clipEdge(px, pz, n, ax, az, true, minX, true);
		m = clipEdge(ax, az, m, bx, bz, true, maxX, false);
		m = clipEdge(bx, bz, m, ax, az, false, minZ, true);
		m = clipEdge(ax, az, m, bx, bz, false, maxZ, false);
		outX = bx;
		outZ = bz;
		return m < 3 ? 0 : m;
	}

	private static int clipEdge(double[] ix, double[] iz, int n, double[] ox, double[] oz, boolean xAxis,
			double bound, boolean keepGreater) {
		if (n == 0)
			return 0;
		int m = 0;
		double prevX = ix[n - 1];
		double prevZ = iz[n - 1];
		double prevV = xAxis ? prevX : prevZ;
		boolean prevIn = keepGreater ? prevV >= bound : prevV <= bound;
		for (int i = 0; i < n; i++) {
			double curX = ix[i];
			double curZ = iz[i];
			double curV = xAxis ? curX : curZ;
			boolean curIn = keepGreater ? curV >= bound : curV <= bound;
			if (curIn != prevIn) {
				double t = (bound - prevV) / (curV - prevV);
				if (xAxis) {
					ox[m] = bound;
					oz[m] = prevZ + t * (curZ - prevZ);
				} else {
					ox[m] = prevX + t * (curX - prevX);
					oz[m] = bound;
				}
				m++;
			}
			if (curIn) {
				ox[m] = curX;
				oz[m] = curZ;
				m++;
			}
			prevX = curX;
			prevZ = curZ;
			prevV = curV;
			prevIn = curIn;
		}
		return m;
	}
}
