package net.fabricmc.example.minimap;

import net.minecraft.src.GuiButton;
import net.minecraft.src.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * A vanilla-looking slider that is not tied to the game's own options enum.
 * Subclasses say how a knob position (0..1) maps to a setting; snapping to
 * steps is simply a matter of {@link #position()} reporting the snapped value.
 */
abstract class MapSlider extends GuiButton {
	private boolean dragging;

	MapSlider(int id, int x, int y, int width, int height) {
		super(id, x, y, width, height, "");
		this.displayString = label();
	}

	/** Current knob position, 0..1. */
	protected abstract float position();

	/** Store the setting for a knob position, 0..1. */
	protected abstract void apply(float position);

	protected abstract String label();

	/** Called when the knob is let go (a good moment to save). */
	protected void released() {
	}

	@Override
	protected int getHoverState(boolean hovered) {
		return 0; // draws the dark track
	}

	private void moveTo(int mouseX) {
		float p = (float) (mouseX - (this.xPosition + 4)) / (float) (this.width - 8);
		apply(p < 0.0f ? 0.0f : (p > 1.0f ? 1.0f : p));
		this.displayString = label();
	}

	@Override
	protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
		if (!this.drawButton)
			return;
		if (this.dragging)
			moveTo(mouseX);
		GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
		float p = position();
		p = p < 0.0f ? 0.0f : (p > 1.0f ? 1.0f : p);
		int knob = this.xPosition + (int) (p * (float) (this.width - 8));
		this.drawTexturedModalRect(knob, this.yPosition, 0, 66, 4, 20);
		this.drawTexturedModalRect(knob + 4, this.yPosition, 196, 66, 4, 20);
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
