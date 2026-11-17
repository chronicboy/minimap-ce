package net.fabricmc.example.minimap;

import net.minecraft.src.FontRenderer;
import net.minecraft.src.Tessellator;
import org.lwjgl.opengl.GL11;

/**
 * The look shared by every Minimap CE screen: a dark, flat theme with one blue
 * accent. Everything is drawn from plain rectangles and the game font, so it
 * works with any resource pack.
 *
 * All methods leave texturing enabled and blending on, which is what text
 * drawing expects.
 */
final class MapUi {
	static final int PANEL = 0xF0141519;
	static final int PANEL_RAISED = 0xF01C1E25;
	static final int BORDER = 0xFF30333D;
	static final int BORDER_LIGHT = 0xFF484C59;
	static final int ACCENT = 0xFF4C8DFF;
	static final int ACCENT_DARK = 0xFF2F64C8;
	static final int TEXT = 0xFFE6E7EC;
	static final int TEXT_DIM = 0xFFA3A6B2;
	static final int TEXT_MUTED = 0xFF6E7282;
	static final int DANGER = 0xFFE5534B;
	static final int SUCCESS = 0xFF5CCB70;
	static final int GOLD = 0xFFF0C24F;

	static final int STYLE_NORMAL = 0;
	static final int STYLE_PRIMARY = 1;
	static final int STYLE_DANGER = 2;
	static final int STYLE_TAB = 3;

	private MapUi() {
	}

	static void rect(double x0, double y0, double x1, double y1, int argb) {
		int alpha = argb >>> 24;
		if (alpha == 0 || x1 <= x0 || y1 <= y0)
			return;
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		Tessellator t = Tessellator.instance;
		t.startDrawingQuads();
		t.setColorRGBA_I(argb & 0xFFFFFF, alpha);
		t.addVertex(x0, y1, 0.0);
		t.addVertex(x1, y1, 0.0);
		t.addVertex(x1, y0, 0.0);
		t.addVertex(x0, y0, 0.0);
		t.draw();
		GL11.glEnable(GL11.GL_TEXTURE_2D);
	}

	/** One-pixel frame just inside the rectangle. */
	static void outline(double x0, double y0, double x1, double y1, int argb) {
		rect(x0, y0, x1, y0 + 1, argb);
		rect(x0, y1 - 1, x1, y1, argb);
		rect(x0, y0 + 1, x0 + 1, y1 - 1, argb);
		rect(x1 - 1, y0 + 1, x1, y1 - 1, argb);
	}

	/** A floating card: soft shadow, dark fill, thin border and a faint top highlight. */
	static void panel(double x0, double y0, double x1, double y1) {
		rect(x0 - 1, y0 + 1, x1 + 3, y1 + 3, 0x38000000);
		rect(x0 + 1, y0 + 2, x1 + 2, y1 + 2, 0x48000000);
		rect(x0, y0, x1, y1, PANEL);
		outline(x0, y0, x1, y1, BORDER);
		rect(x0 + 1, y0 + 1, x1 - 1, y0 + 2, 0x22FFFFFF);
	}

	/** A full-width toolbar (top or bottom of a screen). */
	static void bar(double x0, double y0, double x1, double y1, boolean lineBelow) {
		rect(x0, y0, x1, y1, 0xEC111216);
		if (lineBelow) {
			rect(x0, y1 - 1, x1, y1, BORDER);
			rect(x0, y1, x1, y1 + 2, 0x30000000);
		} else {
			rect(x0, y0, x1, y0 + 1, BORDER);
			rect(x0, y0 - 2, x1, y0, 0x30000000);
		}
	}

	/**
	 * A button.
	 *
	 * @param value if not null, the label is drawn on the left and this on the right
	 */
	static void button(FontRenderer fr, int x, int y, int w, int h, String label, String value, int style,
			boolean selected, boolean hover, boolean enabled) {
		int fill, border, text;
		if (!enabled && style != STYLE_TAB) {
			fill = 0xFF1B1C21;
			border = 0xFF26282F;
			text = TEXT_MUTED;
		} else if (style == STYLE_PRIMARY) {
			fill = hover ? 0xFF3B76E0 : ACCENT_DARK;
			border = hover ? 0xFF7FAEFF : ACCENT;
			text = 0xFFFFFFFF;
		} else if (style == STYLE_DANGER) {
			fill = hover ? 0xFF5A2328 : 0xFF3A1D20;
			border = hover ? 0xFF9A3B42 : 0xFF6E2E33;
			text = 0xFFFF9A92;
		} else if (style == STYLE_TAB) {
			fill = selected ? 0xFF243452 : (hover ? 0x40FFFFFF : 0x00000000);
			border = selected ? ACCENT : (hover ? BORDER_LIGHT : 0x00000000);
			text = selected ? 0xFFFFFFFF : (hover ? TEXT : TEXT_DIM);
		} else {
			fill = hover ? 0xFF2D303A : 0xFF23252D;
			border = hover ? 0xFF5A6072 : 0xFF383B47;
			text = hover ? 0xFFFFFFFF : TEXT;
		}
		rect(x, y, x + w, y + h, fill);
		if ((border >>> 24) != 0)
			outline(x, y, x + w, y + h, border);
		if (style != STYLE_TAB && enabled)
			rect(x + 1, y + h - 2, x + w - 1, y + h - 1, 0x40000000); // slight lower edge: reads as raised
		if (style == STYLE_TAB && selected)
			rect(x + 1, y + h - 2, x + w - 1, y + h - 1, ACCENT);

		int ty = y + (h - 8) / 2 + ((h & 1) == 0 ? 0 : 1);
		if (value == null) {
			fr.drawStringWithShadow(label, x + (w - fr.getStringWidth(label)) / 2, ty, text);
		} else {
			fr.drawStringWithShadow(label, x + 6, ty, enabled ? TEXT_DIM : TEXT_MUTED);
			fr.drawStringWithShadow(value, x + w - 6 - fr.getStringWidth(value), ty, text);
		}
	}

	/**
	 * A waypoint tile of any size: dark outline, coloured face with a soft top
	 * highlight, and the initial letter (dark on light colours, white on dark).
	 */
	static void tile(FontRenderer fr, int x, int y, int size, int rgb, String letter) {
		rect(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF0A0A0C);
		rect(x, y, x + size, y + size, 0xFF000000 | rgb);
		rect(x, y, x + size, y + 1, 0x38FFFFFF);
		rect(x, y + size - 1, x + size, y + size, 0x38000000);
		if (fr != null && letter != null) {
			int color = MapMarkers.tileLetterColor(rgb);
			int lw = fr.getStringWidth(letter) - 1;
			fr.drawString(letter, x + (size - lw + 1) / 2, y + (size - 7) / 2, color);
		}
	}

	/** Shortens text to fit a width, ending in "..". */
	static String fit(FontRenderer fr, String text, int width) {
		if (fr.getStringWidth(text) <= width)
			return text;
		String ellipsis = "..";
		return fr.trimStringToWidth(text, Math.max(0, width - fr.getStringWidth(ellipsis))) + ellipsis;
	}

	/** Distance in blocks as "12 m" or "1.4 km". */
	static String distance(double blocks) {
		if (blocks < 1000)
			return (int) Math.round(blocks) + " m";
		return (Math.round(blocks / 100.0) / 10.0) + " km";
	}
}
