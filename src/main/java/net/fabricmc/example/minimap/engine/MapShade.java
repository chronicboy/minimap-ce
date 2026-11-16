package net.fabricmc.example.minimap.engine;

/**
 * Pure colour maths shared by the render thread and the I/O thread.
 *
 * Tile encoding (one int per block column): (height << 24) | rgb.
 * A value of 0 means "unexplored". A height of 0 means "no height information"
 * (used for cave walls), which disables relief shading for that column.
 */
public final class MapShade {
	private MapShade() {
	}

	public static int pack(int rgb, int height) {
		int v = ((height & 0xFF) << 24) | (rgb & 0xFFFFFF);
		return v == 0 ? 1 : v;
	}

	public static int height(int data) {
		return data >>> 24;
	}

	/**
	 * Turns a stored tile value into the final opaque ARGB map pixel.
	 * Relief comes from the height difference to the north and west neighbours
	 * (light from the north-west), plus a very slight "higher is brighter" ramp.
	 *
	 * @param data  packed tile value, must be non-zero
	 * @param north packed tile value at z - 1, or 0 when unknown
	 * @param west  packed tile value at x - 1, or 0 when unknown
	 */
	public static int shade(int data, int north, int west) {
		int h = data >>> 24;
		int f = 256;
		if (h != 0) {
			int d = 0;
			int hn = north >>> 24;
			if (hn != 0)
				d += h - hn;
			int hw = west >>> 24;
			if (hw != 0)
				d += h - hw;
			if (d != 0) {
				int s = d * 13;
				if (s > 56)
					s = 56;
				else if (s < -72)
					s = -72;
				f += s;
			}
			int e = ((h - 64) * 3) >> 3;
			if (e > 30)
				e = 30;
			else if (e < -20)
				e = -20;
			f += e;
		}
		int r = (((data >> 16) & 0xFF) * f) >> 8;
		int g = (((data >> 8) & 0xFF) * f) >> 8;
		int b = ((data & 0xFF) * f) >> 8;
		if (r > 255)
			r = 255;
		if (g > 255)
			g = 255;
		if (b > 255)
			b = 255;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Component-wise multiply of two RGB colours. */
	public static int multiply(int a, int b) {
		int r = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) + 127) / 255;
		int g = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) + 127) / 255;
		int bl = ((a & 0xFF) * (b & 0xFF) + 127) / 255;
		return (r << 16) | (g << 8) | bl;
	}

	/** Scales an RGB colour by factor/256 (clamped). */
	public static int scale(int rgb, int factor256) {
		int r = (((rgb >> 16) & 0xFF) * factor256) >> 8;
		int g = (((rgb >> 8) & 0xFF) * factor256) >> 8;
		int b = ((rgb & 0xFF) * factor256) >> 8;
		if (r > 255)
			r = 255;
		if (g > 255)
			g = 255;
		if (b > 255)
			b = 255;
		return (r << 16) | (g << 8) | b;
	}

	/** Linear blend: t256 = 0 gives a, t256 = 256 gives b. */
	public static int lerp(int a, int b, int t256) {
		int inv = 256 - t256;
		int r = (((a >> 16) & 0xFF) * inv + ((b >> 16) & 0xFF) * t256) >> 8;
		int g = (((a >> 8) & 0xFF) * inv + ((b >> 8) & 0xFF) * t256) >> 8;
		int bl = ((a & 0xFF) * inv + (b & 0xFF) * t256) >> 8;
		return (r << 16) | (g << 8) | bl;
	}
}
