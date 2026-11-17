package net.fabricmc.example.minimap;

import net.minecraft.src.GuiButton;
import net.minecraft.src.Minecraft;

/** A {@link GuiButton} drawn in the {@link MapUi} style. Behaves exactly like a vanilla button. */
class FlatButton extends GuiButton {
	int style = MapUi.STYLE_NORMAL;
	boolean selected;
	/** When set, the label is shown on the left and this on the right (settings rows). */
	String value;

	FlatButton(int id, int x, int y, int w, int h, String label) {
		super(id, x, y, w, h, label);
	}

	FlatButton style(int style) {
		this.style = style;
		return this;
	}

	FlatButton selected(boolean selected) {
		this.selected = selected;
		return this;
	}

	FlatButton value(String value) {
		this.value = value;
		return this;
	}

	boolean isHovered(int mouseX, int mouseY) {
		return mouseX >= this.xPosition && mouseY >= this.yPosition && mouseX < this.xPosition + this.width
				&& mouseY < this.yPosition + this.height;
	}

	@Override
	public void drawButton(Minecraft mc, int mouseX, int mouseY) {
		if (!this.drawButton)
			return;
		MapUi.button(mc.fontRenderer, this.xPosition, this.yPosition, this.width, this.height, this.displayString,
				this.value, this.style, this.selected, this.enabled && isHovered(mouseX, mouseY), this.enabled);
	}
}
