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

	/**
	 * Directional chevron with a dark outline and a two-tone fill.
	 *
	 * @param angle     screen direction in radians (0 = right, PI/2 = down)
	 * @param size      distance from the centre to the tip, in GUI pixels
	 * @param wingAngle wing direction as a fraction of PI away from the tip direction
	 */
	static void arrow(Tessellator t, double x, double y, double angle, double size, double wingAngle,
			double wingLength, double tailLength, float r, float g, float b, double outline) {
		double ca = Math.cos(angle), sa = Math.sin(angle);
		double cw = Math.cos(wingAngle * Math.PI), sw = Math.sin(wingAngle * Math.PI);
		double wl = size * wingLength, tl = size * tailLength;

		double tipX = ca * size, tipY = sa * size;
		double leftX = (ca * cw - sa * sw) * wl, leftY = (sa * cw + ca * sw) * wl;
		double rightX = (ca * cw + sa * sw) * wl, rightY = (sa * cw - ca * sw) * wl;
		double tailX = -ca * tl, tailY = -sa * tl;

		if (outline > 0) {
			double kTip = (size + outline) / size;
			double kWing = (wl + outline) / wl;
			double kTail = (tl + outline * 0.6) / tl;
			t.setColorRGBA_F(0.03f, 0.03f, 0.03f, 1.0f);
			t.addVertex(x + tipX * kTip, y + tipY * kTip, 0.0);
			t.addVertex(x + leftX * kWing, y + leftY * kWing, 0.0);
			t.addVertex(x + tailX * kTail, y + tailY * kTail, 0.0);
			t.addVertex(x + tipX * kTip, y + tipY * kTip, 0.0);
			t.addVertex(x + tailX * kTail, y + tailY * kTail, 0.0);
			t.addVertex(x + rightX * kWing, y + rightY * kWing, 0.0);
		}

		// Left facet slightly brighter, right facet slightly darker: reads as a folded shape.
		t.setColorRGBA_F(Math.min(1.0f, r * 1.15f), Math.min(1.0f, g * 1.15f), Math.min(1.0f, b * 1.15f), 1.0f);
		t.addVertex(x + tipX, y + tipY, 0.0);
		t.addVertex(x + leftX, y + leftY, 0.0);
		t.addVertex(x + tailX, y + tailY, 0.0);
		t.setColorRGBA_F(r * 0.85f, g * 0.85f, b * 0.85f, 1.0f);
		t.addVertex(x + tipX, y + tipY, 0.0);
		t.addVertex(x + tailX, y + tailY, 0.0);
		t.addVertex(x + rightX, y + rightY, 0.0);
	}

	/** Entity blip: small chevron pointing where the entity looks. */
	static void entityArrow(Tessellator t, double x, double y, double angle, float r, float g, float b) {
		arrow(t, x, y, angle, 4.2, 0.80, 0.70, 0.22, r, g, b, 0.9);
	}

	/** The gold player arrow: outlined body plus a bright inner highlight. */
	static void playerArrow(Tessellator t, double x, double y, double angle, double size) {
		arrow(t, x, y, angle, size, 0.82, 0.60, 0.20, 0.65f, 0.45f, 0.05f, 1.1);
		arrow(t, x, y, angle, size * 0.6, 0.82, 0.633, 0.133, 1.0f, 0.85f, 0.2f, 0);
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
