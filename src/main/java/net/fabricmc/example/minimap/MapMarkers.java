package net.fabricmc.example.minimap;

import net.minecraft.src.Tessellator;

/**
 * Marker geometry shared by the minimap and the fullscreen map. Every method
 * appends coloured triangles to a batch the caller has already started with
 * {@code Tessellator.startDrawing(GL11.GL_TRIANGLES)}, so any number of markers
 * costs a single draw call.
 */
final class MapMarkers {
	private MapMarkers() {
	}

	// Scratch polygon for the chevron (tip, left wing, tail notch, right wing).
	private static final double[] PX = new double[4];
	private static final double[] PY = new double[4];
	private static final double[] OX = new double[4];
	private static final double[] OY = new double[4];

	/**
	 * Directional chevron with a constant-width dark outline.
	 *
	 * @param angle     screen direction in radians (0 = right, PI/2 = down)
	 * @param size      distance from the centre to the tip, in GUI pixels
	 * @param wingAngle wing direction as a fraction of PI away from the tip direction
	 * @param facet     0 for a flat fill; otherwise the left half is brightened and
	 *                  the right half darkened by this fraction, which reads as a fold
	 * @param outline   outline width in GUI pixels, 0 for none
	 */
	static void arrow(Tessellator t, double x, double y, double angle, double size, double wingAngle,
			double wingLength, double tailLength, float r, float g, float b, float facet, double outline) {
		double ca = Math.cos(angle), sa = Math.sin(angle);
		double cw = Math.cos(wingAngle * Math.PI), sw = Math.sin(wingAngle * Math.PI);
		double wl = size * wingLength, tl = size * tailLength;

		PX[0] = x + ca * size;
		PY[0] = y + sa * size;
		PX[1] = x + (ca * cw - sa * sw) * wl;
		PY[1] = y + (sa * cw + ca * sw) * wl;
		PX[2] = x - ca * tl;
		PY[2] = y - sa * tl;
		PX[3] = x + (ca * cw + sa * sw) * wl;
		PY[3] = y + (sa * cw - ca * sw) * wl;

		if (outline > 0) {
			offsetPolygon(outline);
			// Split along tip-tail: the tail is the only concave corner.
			t.setColorRGBA_F(0.03f, 0.03f, 0.03f, 1.0f);
			t.addVertex(OX[0], OY[0], 0.0);
			t.addVertex(OX[1], OY[1], 0.0);
			t.addVertex(OX[2], OY[2], 0.0);
			t.addVertex(OX[0], OY[0], 0.0);
			t.addVertex(OX[2], OY[2], 0.0);
			t.addVertex(OX[3], OY[3], 0.0);
		}

		float hi = 1.0f + facet, lo = 1.0f - facet;
		t.setColorRGBA_F(Math.min(1.0f, r * hi), Math.min(1.0f, g * hi), Math.min(1.0f, b * hi), 1.0f);
		t.addVertex(PX[0], PY[0], 0.0);
		t.addVertex(PX[1], PY[1], 0.0);
		t.addVertex(PX[2], PY[2], 0.0);
		t.setColorRGBA_F(r * lo, g * lo, b * lo, 1.0f);
		t.addVertex(PX[0], PY[0], 0.0);
		t.addVertex(PX[2], PY[2], 0.0);
		t.addVertex(PX[3], PY[3], 0.0);
	}

	/** Grows the scratch polygon outwards by a constant distance (mitred, with sharp corners capped). */
	private static void offsetPolygon(double distance) {
		double area = 0;
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) & 3;
			area += PX[i] * PY[j] - PX[j] * PY[i];
		}
		double sign = area >= 0 ? 1.0 : -1.0;
		for (int i = 0; i < 4; i++) {
			int prev = (i + 3) & 3, next = (i + 1) & 3;
			double e1x = PX[i] - PX[prev], e1y = PY[i] - PY[prev];
			double e2x = PX[next] - PX[i], e2y = PY[next] - PY[i];
			double l1 = Math.sqrt(e1x * e1x + e1y * e1y), l2 = Math.sqrt(e2x * e2x + e2y * e2y);
			if (l1 < 1.0e-9 || l2 < 1.0e-9) {
				OX[i] = PX[i];
				OY[i] = PY[i];
				continue;
			}
			// Outward normals of the two edges meeting here.
			double n1x = e1y / l1 * sign, n1y = -e1x / l1 * sign;
			double n2x = e2y / l2 * sign, n2y = -e2x / l2 * sign;
			double mx = n1x + n2x, my = n1y + n2y;
			double ml = Math.sqrt(mx * mx + my * my);
			double k = distance / Math.max(1.0e-6, 1.0 + n1x * n2x + n1y * n2y);
			// A very sharp tip would otherwise grow a long spike.
			double maxK = ml < 1.0e-6 ? 0.0 : 2.0 * distance / ml;
			if (k > maxK)
				k = maxK;
			OX[i] = PX[i] + mx * k;
			OY[i] = PY[i] + my * k;
		}
	}

	/** Entity blip: small two-tone chevron pointing where the entity looks. */
	static void entityArrow(Tessellator t, double x, double y, double angle, float r, float g, float b) {
		arrow(t, x, y, angle, 4.2, 0.80, 0.70, 0.22, r, g, b, 0.15f, 0.8);
	}

	/** The gold player arrow: outlined body plus a bright inner highlight. */
	static void playerArrow(Tessellator t, double x, double y, double angle, double size) {
		arrow(t, x, y, angle, size, 0.82, 0.60, 0.20, 0.65f, 0.45f, 0.05f, 0.0f, 1.0);
		arrow(t, x, y, angle, size * 0.6, 0.82, 0.633, 0.133, 1.0f, 0.85f, 0.2f, 0.0f, 0);
	}

	/** Round blip with a dark rim. */
	static void dot(Tessellator t, double x, double y, double radius, float r, float g, float b) {
		disc(t, x, y, radius + 0.8, 0.03f, 0.03f, 0.03f);
		disc(t, x, y, radius, r, g, b);
	}

	private static final int DISC_SEGMENTS = 12;
	private static final double[] DISC_COS = new double[DISC_SEGMENTS + 1];
	private static final double[] DISC_SIN = new double[DISC_SEGMENTS + 1];
	static {
		for (int i = 0; i <= DISC_SEGMENTS; i++) {
			double a = i * 2.0 * Math.PI / DISC_SEGMENTS;
			DISC_COS[i] = Math.cos(a);
			DISC_SIN[i] = Math.sin(a);
		}
	}

	private static void disc(Tessellator t, double x, double y, double radius, float r, float g, float b) {
		t.setColorRGBA_F(r, g, b, 1.0f);
		for (int i = 0; i < DISC_SEGMENTS; i++) {
			t.addVertex(x, y, 0.0);
			t.addVertex(x + DISC_COS[i + 1] * radius, y + DISC_SIN[i + 1] * radius, 0.0);
			t.addVertex(x + DISC_COS[i] * radius, y + DISC_SIN[i] * radius, 0.0);
		}
	}

	/** Size of the lettered waypoint tile (GUI pixels, outline included). */
	static final int TILE_SIZE = 11;

	/**
	 * Lettered waypoint tile: a 9x9 coloured square with a dark outline. The
	 * letter itself is text and is drawn afterwards with {@link #tileLetterX}.
	 *
	 * @param x,y centre of the tile (snapped to whole pixels)
	 */
	static void waypointTile(Tessellator t, int x, int y, int rgb) {
		float r = ((rgb >> 16) & 0xFF) / 255.0f;
		float g = ((rgb >> 8) & 0xFF) / 255.0f;
		float b = (rgb & 0xFF) / 255.0f;
		rect(t, x - 5, y - 5, x + 6, y + 6, 0.03f, 0.03f, 0.03f, 1.0f);
		rect(t, x - 4, y - 4, x + 5, y + 5, r, g, b, 1.0f);
		// Soft top-left highlight and bottom-right shade so the tile reads as a raised key.
		rect(t, x - 4, y - 4, x + 5, y - 3, 1.0f, 1.0f, 1.0f, 0.22f);
		rect(t, x - 4, y + 4, x + 5, y + 5, 0.0f, 0.0f, 0.0f, 0.22f);
	}

	/** The character shown on a waypoint tile. */
	static String tileLetter(String name) {
		if (name != null) {
			for (int i = 0; i < name.length(); i++) {
				char c = name.charAt(i);
				if (c == '\u00a7') {
					i++; // skip a formatting code
					continue;
				}
				if (!Character.isWhitespace(c))
					return String.valueOf(Character.toUpperCase(c));
			}
		}
		return "?";
	}

	/** Black on light tiles, white on dark ones. */
	static int tileLetterColor(int rgb) {
		int luminance = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
		return luminance > 150 ? 0x101010 : 0xFFFFFF;
	}

	/** Left edge for drawing a letter of the given font width centred on a tile at x. */
	static int tileLetterX(int x, int charWidth) {
		// The font's width includes one pixel of spacing after the glyph.
		int glyph = Math.max(1, charWidth - 1);
		return x - 4 + (9 - glyph) / 2;
	}

	/** Waypoint marker: outlined coloured square with a white centre dot. */
	static void waypoint(Tessellator t, double x, double y, int half, int rgb) {
		float r = ((rgb >> 16) & 0xFF) / 255.0f;
		float g = ((rgb >> 8) & 0xFF) / 255.0f;
		float b = (rgb & 0xFF) / 255.0f;
		quad(t, x, y, half + 1, 0.0f, 0.0f, 0.0f, 1.0f);
		quad(t, x, y, half, r, g, b, 1.0f);
		if (half > 1)
			quad(t, x, y, 1, 1.0f, 1.0f, 1.0f, 1.0f);
	}

	/** Axis-aligned square of the given half size, as two triangles. */
	static void quad(Tessellator t, double x, double y, double half, float r, float g, float b, float a) {
		rect(t, x - half, y - half, x + half, y + half, r, g, b, a);
	}

	static void rect(Tessellator t, double x0, double y0, double x1, double y1, float r, float g, float b, float a) {
		t.setColorRGBA_F(r, g, b, a);
		t.addVertex(x0, y0, 0.0);
		t.addVertex(x0, y1, 0.0);
		t.addVertex(x1, y1, 0.0);
		t.addVertex(x0, y0, 0.0);
		t.addVertex(x1, y1, 0.0);
		t.addVertex(x1, y0, 0.0);
	}
}
