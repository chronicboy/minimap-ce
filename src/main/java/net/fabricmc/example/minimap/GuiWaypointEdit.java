package net.fabricmc.example.minimap;

import net.minecraft.src.FontRenderer;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.GuiTextField;
import net.minecraft.src.Minecraft;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Create or edit one waypoint: name, position (with a button to use where you
 * stand), colour from a palette, and a live preview of its map tile.
 */
public class GuiWaypointEdit extends GuiScreen {
	private static final int[] PALETTE = {
			0xFF5555, 0xFF9933, 0xFFE14D, 0x9BE34B, 0x3DBF5A, 0x33C9B0, 0x4DD2FF, 0x5599FF,
			0x3F5BFF, 0x9966FF, 0xE05CE0, 0xFF7AB3, 0xF2F2F2, 0xA8A8B3, 0x5E5E66, 0x9C6B3E,
	};
	private static final int SWATCH = 15;
	private static final int SWATCH_GAP = 4;
	private static final int PANEL_W = 268;
	private static final int PANEL_H = 212;

	private static final int BTN_SAVE = 0;
	private static final int BTN_CANCEL = 1;
	private static final int BTN_DELETE = 2;
	private static final int BTN_HERE = 3;
	private static final long CONFIRM_MS = 3000L;

	private final GuiScreen parent;
	private final Waypoint waypoint;
	private final boolean isNew;
	private final int saveDim;
	private final List<Waypoint> saveList;
	private int color;

	private GuiTextField nameField, xField, yField, zField;
	private GuiTextField[] fields;
	/** Top-left corner of each field (the field keeps its own position private). */
	private final int[] fieldX = new int[4], fieldY = new int[4], fieldW = new int[4];
	private int panelX, panelY, swatchX, swatchY;
	private FlatButton saveButton, deleteButton;
	private long deleteConfirmUntil;

	public GuiWaypointEdit(GuiScreen parent, Waypoint waypoint) {
		this(parent, waypoint, false, Integer.MIN_VALUE, null);
	}

	public GuiWaypointEdit(GuiScreen parent, Waypoint waypoint, boolean isNew) {
		this(parent, waypoint, isNew, Integer.MIN_VALUE, null);
	}

	/**
	 * @param saveDim  dimension the waypoint belongs to, or Integer.MIN_VALUE for the current one
	 * @param saveList the other dimension's list it lives in, or null for the current dimension
	 */
	public GuiWaypointEdit(GuiScreen parent, Waypoint waypoint, boolean isNew, int saveDim, List<Waypoint> saveList) {
		this.parent = parent;
		this.waypoint = waypoint;
		this.isNew = isNew;
		this.saveDim = saveDim;
		this.saveList = saveList;
		this.color = waypoint.color;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void initGui() {
		Keyboard.enableRepeatEvents(true);
		panelX = (this.width - PANEL_W) / 2;
		panelY = Math.max(4, (this.height - PANEL_H) / 2);
		int left = panelX + 12;

		// Keep what was typed when the window is resized.
		String oldName = nameField != null ? nameField.getText() : waypoint.name;
		String oldX = xField != null ? xField.getText() : String.valueOf(waypoint.x);
		String oldY = yField != null ? yField.getText() : String.valueOf(waypoint.y);
		String oldZ = zField != null ? zField.getText() : String.valueOf(waypoint.z);

		nameField = new GuiTextField(this.fontRenderer, left + 30, panelY + 36, PANEL_W - 24 - 30, 18);
		fieldX[0] = left + 30;
		fieldY[0] = panelY + 36;
		fieldW[0] = PANEL_W - 24 - 30;
		nameField.setMaxStringLength(32);
		nameField.setText(oldName);
		nameField.setFocused(true);

		int fieldW = 58, gap = 6, coordY = panelY + 80;
		xField = coordField(left, coordY, fieldW, oldX);
		yField = coordField(left + fieldW + gap, coordY, fieldW, oldY);
		zField = coordField(left + (fieldW + gap) * 2, coordY, fieldW, oldZ);
		fields = new GuiTextField[] { nameField, xField, yField, zField };
		for (int i = 1; i < 4; i++) {
			fieldX[i] = left + (fieldW + gap) * (i - 1);
			fieldY[i] = coordY;
			this.fieldW[i] = fieldW;
		}

		int hereX = left + (fieldW + gap) * 3;
		FlatButton here = new FlatButton(BTN_HERE, hereX, coordY, panelX + PANEL_W - 12 - hereX, 18, "Here");
		here.enabled = Minecraft.getMinecraft().thePlayer != null;
		this.buttonList.add(here);

		int paletteW = 8 * SWATCH + 7 * SWATCH_GAP;
		swatchX = panelX + (PANEL_W - paletteW) / 2;
		swatchY = panelY + 124;

		int by = panelY + PANEL_H - 30;
		saveButton = new FlatButton(BTN_SAVE, panelX + PANEL_W - 12 - 70, by, 70, 20, isNew ? "Create" : "Save")
				.style(MapUi.STYLE_PRIMARY);
		this.buttonList.add(saveButton);
		this.buttonList.add(new FlatButton(BTN_CANCEL, panelX + PANEL_W - 12 - 70 - 6 - 62, by, 62, 20, "Cancel"));
		deleteButton = null;
		if (!isNew) {
			deleteButton = new FlatButton(BTN_DELETE, left, by, 62, 20, "Delete").style(MapUi.STYLE_DANGER);
			this.buttonList.add(deleteButton);
		}
	}

	private GuiTextField coordField(int x, int y, int w, String text) {
		GuiTextField f = new GuiTextField(this.fontRenderer, x, y, w, 18);
		f.setMaxStringLength(9);
		f.setText(text);
		return f;
	}

	@Override
	public void onGuiClosed() {
		Keyboard.enableRepeatEvents(false);
	}

	private int dimension() {
		if (saveDim != Integer.MIN_VALUE)
			return saveDim;
		Minecraft mc = Minecraft.getMinecraft();
		return mc.theWorld != null ? mc.theWorld.provider.dimensionId : 0;
	}

	private static boolean isNumber(String s) {
		s = s.trim();
		if (s.isEmpty() || s.equals("-"))
			return false;
		try {
			Integer.parseInt(s);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private boolean valid() {
		return isNumber(xField.getText()) && isNumber(yField.getText()) && isNumber(zField.getText());
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (!button.enabled)
			return;
		switch (button.id) {
			case BTN_SAVE:
				if (valid()) {
					saveWaypoint();
					this.mc.displayGuiScreen(parent);
				}
				break;
			case BTN_CANCEL:
				cancel();
				break;
			case BTN_HERE:
				useCurrentPosition();
				break;
			case BTN_DELETE:
				if (System.currentTimeMillis() < deleteConfirmUntil) {
					list().remove(waypoint);
					persist();
					this.mc.displayGuiScreen(parent);
				} else {
					deleteConfirmUntil = System.currentTimeMillis() + CONFIRM_MS;
					button.displayString = "Sure?";
				}
				break;
		}
	}

	/** Fills in the block under the player, converted if the waypoint is for the other dimension. */
	private void useCurrentPosition() {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.thePlayer == null || mc.theWorld == null)
			return;
		int x = (int) Math.floor(mc.thePlayer.posX);
		int y = (int) Math.floor(mc.thePlayer.boundingBox.minY) - 1;
		int z = (int) Math.floor(mc.thePlayer.posZ);
		int here = mc.theWorld.provider.dimensionId, target = dimension();
		if (here == 0 && target == -1) {
			x = Math.floorDiv(x, 8);
			z = Math.floorDiv(z, 8);
		} else if (here == -1 && target == 0) {
			x *= 8;
			z *= 8;
		}
		xField.setText(String.valueOf(x));
		yField.setText(String.valueOf(y));
		zField.setText(String.valueOf(z));
	}

	private List<Waypoint> list() {
		return saveList != null ? saveList : WaypointManager.instance.getWaypoints();
	}

	private void persist() {
		if (saveList != null)
			WaypointManager.instance.saveWaypointsForDim(saveDim, saveList);
		else
			WaypointManager.instance.save();
	}

	/** Leaves without saving. A waypoint that was only just created is removed again. */
	private void cancel() {
		if (isNew) {
			if (saveList != null)
				saveList.remove(waypoint);
			else
				WaypointManager.instance.removeWaypoint(waypoint);
		}
		this.mc.displayGuiScreen(parent);
	}

	private void saveWaypoint() {
		String name = nameField.getText().trim();
		if (!name.isEmpty())
			waypoint.name = name;
		waypoint.x = Integer.parseInt(xField.getText().trim());
		waypoint.y = Integer.parseInt(yField.getText().trim());
		waypoint.z = Integer.parseInt(zField.getText().trim());
		waypoint.color = color;
		persist();
	}

	@Override
	public void updateScreen() {
		for (GuiTextField f : fields)
			f.updateCursorCounter();
		saveButton.enabled = valid();
		if (deleteButton != null && System.currentTimeMillis() >= deleteConfirmUntil)
			deleteButton.displayString = "Delete";
	}

	@Override
	protected void keyTyped(char c, int keyCode) {
		if (keyCode == Keyboard.KEY_ESCAPE) {
			cancel();
			return;
		}
		if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
			if (valid()) {
				saveWaypoint();
				this.mc.displayGuiScreen(parent);
			}
			return;
		}
		if (keyCode == Keyboard.KEY_TAB) {
			int focused = 0;
			for (int i = 0; i < fields.length; i++)
				if (fields[i].isFocused())
					focused = i;
			int next = (focused + (isShiftKeyDown() ? fields.length - 1 : 1)) % fields.length;
			for (int i = 0; i < fields.length; i++)
				fields[i].setFocused(i == next);
			return;
		}
		if (nameField.isFocused()) {
			nameField.textboxKeyTyped(c, keyCode);
			return;
		}
		// Coordinates: digits, minus and editing keys only (ctrl shortcuts such as paste pass through).
		boolean allowed = Character.isDigit(c) || c == '-' || isCtrlKeyDown() || keyCode == Keyboard.KEY_BACK
				|| keyCode == Keyboard.KEY_DELETE || keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RIGHT
				|| keyCode == Keyboard.KEY_HOME || keyCode == Keyboard.KEY_END;
		if (!allowed)
			return;
		for (int i = 1; i < fields.length; i++)
			if (fields[i].isFocused())
				fields[i].textboxKeyTyped(c, keyCode);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
		super.mouseClicked(mouseX, mouseY, mouseButton);
		if (this.mc.currentScreen != this)
			return;
		for (GuiTextField f : fields)
			f.mouseClicked(mouseX, mouseY, mouseButton);
		if (mouseButton == 0) {
			int i = swatchAt(mouseX, mouseY);
			if (i >= 0)
				color = PALETTE[i];
		}
	}

	private int swatchAt(int mouseX, int mouseY) {
		for (int i = 0; i < PALETTE.length; i++) {
			int sx = swatchX + (i % 8) * (SWATCH + SWATCH_GAP);
			int sy = swatchY + (i / 8) * (SWATCH + SWATCH_GAP);
			if (mouseX >= sx && mouseX < sx + SWATCH && mouseY >= sy && mouseY < sy + SWATCH)
				return i;
		}
		return -1;
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		FontRenderer fr = this.fontRenderer;
		int left = panelX + 12;

		MapUi.panel(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H);
		fr.drawStringWithShadow(isNew ? "\u00a7lNew Waypoint" : "\u00a7lEdit Waypoint", left, panelY + 10, 0xFFFFFF);
		int d = dimension();
		String dim = d == -1 ? "Nether" : (d == 1 ? "End" : "Overworld");
		fr.drawStringWithShadow(dim, panelX + PANEL_W - 12 - fr.getStringWidth(dim), panelY + 10, MapUi.TEXT_MUTED);
		MapUi.rect(panelX + 8, panelY + 24, panelX + PANEL_W - 8, panelY + 25, MapUi.BORDER);

		// Live tile preview beside the name.
		String name = nameField.getText().trim().isEmpty() ? waypoint.name : nameField.getText();
		MapUi.tile(fr, left + 2, panelY + 38, 14, color, MapMarkers.tileLetter(name));

		fr.drawStringWithShadow("X", fieldX[1] + 1, fieldY[1] - 10, 0xFF8080);
		fr.drawStringWithShadow("Y", fieldX[2] + 1, fieldY[2] - 10, 0x80E080);
		fr.drawStringWithShadow("Z", fieldX[3] + 1, fieldY[3] - 10, 0x8FB4FF);

		for (int i = 0; i < fields.length; i++) {
			GuiTextField f = fields[i];
			boolean bad = i > 0 && !isNumber(f.getText());
			int ring = bad ? MapUi.DANGER : (f.isFocused() ? MapUi.ACCENT : 0);
			if (ring != 0)
				MapUi.outline(fieldX[i] - 2, fieldY[i] - 2, fieldX[i] + fieldW[i] + 2, fieldY[i] + 20, ring);
			f.drawTextBox();
		}

		fr.drawStringWithShadow("Colour", left, swatchY - 12, MapUi.TEXT_DIM);
		int hovered = swatchAt(mouseX, mouseY);
		for (int i = 0; i < PALETTE.length; i++) {
			int sx = swatchX + (i % 8) * (SWATCH + SWATCH_GAP);
			int sy = swatchY + (i / 8) * (SWATCH + SWATCH_GAP);
			if (PALETTE[i] == color)
				MapUi.outline(sx - 3, sy - 3, sx + SWATCH + 3, sy + SWATCH + 3, 0xFFFFFFFF);
			else if (i == hovered)
				MapUi.outline(sx - 3, sy - 3, sx + SWATCH + 3, sy + SWATCH + 3, MapUi.BORDER_LIGHT);
			MapUi.rect(sx - 1, sy - 1, sx + SWATCH + 1, sy + SWATCH + 1, 0xFF0A0A0C);
			MapUi.rect(sx, sy, sx + SWATCH, sy + SWATCH, 0xFF000000 | PALETTE[i]);
			MapUi.rect(sx, sy, sx + SWATCH, sy + 1, 0x38FFFFFF);
		}

		String hint = MapUi.fit(fr, "Enter: save   Esc: cancel   Tab: next field", PANEL_W - 24);
		fr.drawStringWithShadow(hint, panelX + (PANEL_W - fr.getStringWidth(hint)) / 2, panelY + PANEL_H - 44,
				MapUi.TEXT_MUTED);

		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	@Override
	public boolean doesGuiPauseGame() {
		return true;
	}
}
