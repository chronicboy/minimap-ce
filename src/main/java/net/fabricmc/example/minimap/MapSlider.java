package net.fabricmc.example.minimap;

import net.minecraft.src.GuiButton;
import net.minecraft.src.Minecraft;

/**
 * A slider in the {@link MapUi} style that is not tied to the game's options.
 * Subclasses say how a knob position (0..1) maps to a setting; snapping to
 * steps is simply a matter of {@link #position()} reporting the snapped value.
 */
abstract class MapSlider extends GuiButton {
	private boolean dragging;

	MapSlider(int id, int x, int y, int width, int height) {
		super(id, x, y, width, height, "");
	}

	/** Current knob position, 0..1. */
	protected abstract float position();

	/** Store the setting for a knob position, 0..1. */
	protected abstract void apply(float position);

	protected abstract String name();

	protected abstract String valueText();

	/** Called when the knob is let go (a good moment to save). */
	protected void released() {
	}

	private void moveTo(int mouseX) {
		float p = (float) (mouseX - (this.xPosition + 4)) / (float) (this.width - 8);
		apply(p < 0.0f ? 0.0f : (p > 1.0f ? 1.0f : p));
	}

	@Override
	public void drawButton(Minecraft mc, int mouseX, int mouseY) {
		if (!this.drawButton)
			return;
		if (this.dragging)
			moveTo(mouseX);
		boolean hover = this.dragging || (mouseX >= this.xPosition && mouseY >= this.yPosition
				&& mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height);
		int x = this.xPosition, y = this.yPosition, w = this.width, h = this.height;

		MapUi.rect(x, y, x + w, y + h, hover ? 0xFF2D303A : 0xFF23252D);
		float p = position();
		p = p < 0.0f ? 0.0f : (p > 1.0f ? 1.0f : p);
		int knob = x + 2 + Math.round(p * (w - 7));
		// Filled part of the track up to the knob.
		MapUi.rect(x + 1, y + 1, knob + 1, y + h - 1, 0x502F64C8);
		MapUi.outline(x, y, x + w, y + h, hover ? 0xFF5A6072 : 0xFF383B47);
		MapUi.rect(knob, y + 2, knob + 3, y + h - 2, hover ? 0xFFFFFFFF : 0xFFC9CCD6);

		int ty = y + (h - 8) / 2;
		String name = name(), value = valueText();
		mc.fontRenderer.drawStringWithShadow(name, x + 6, ty, MapUi.TEXT_DIM);
		mc.fontRenderer.drawStringWithShadow(value, x + w - 6 - mc.fontRenderer.getStringWidth(value), ty, 0xFFFFFFFF);
	}

	@Override
	public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
		if (!super.mousePressed(mc, mouseX, mouseY))
			return false;
		moveTo(mouseX);
		this.dragging = true;
		return true;
	}

	@Override
	public void mouseReleased(int mouseX, int mouseY) {
		if (this.dragging) {
			this.dragging = false;
			released();
		}
	}
}
