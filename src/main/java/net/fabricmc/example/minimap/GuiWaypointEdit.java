package net.fabricmc.example.minimap;

import net.minecraft.src.*;
import org.lwjgl.input.Keyboard;

public class GuiWaypointEdit extends GuiScreen {
    // Color picker layout
    private int colorSwatchY;
    private int colorSwatchStartX;
    private static final int SWATCH_SIZE = 18;
    private static final int SWATCH_GAP = 3;
    private final GuiScreen parent;
    private final Waypoint waypoint;
    private final boolean isNew;
    private final int saveDim;
    private final java.util.List<Waypoint> saveList;
    private GuiTextField nameField;
    private GuiTextField xField;
    private GuiTextField yField;
    private GuiTextField zField;

    private static final int[] COLOR_PRESETS = {
            0xFF5555, // Red
            0x55FF55, // Green
            0x5555FF, // Blue
            0xFFFF55, // Yellow
            0xFF55FF, // Magenta
            0x55FFFF, // Cyan
            0xFF8800, // Orange
            0xAA55FF, // Purple
            0xFFFFFF, // White
    };

    public GuiWaypointEdit(GuiScreen parent, Waypoint waypoint) {
        this(parent, waypoint, false, Integer.MIN_VALUE, null);
    }

    public GuiWaypointEdit(GuiScreen parent, Waypoint waypoint, boolean isNew) {
        this(parent, waypoint, isNew, Integer.MIN_VALUE, null);
    }

    public GuiWaypointEdit(GuiScreen parent, Waypoint waypoint, boolean isNew, int saveDim,
            java.util.List<Waypoint> saveList) {
        this.parent = parent;
        this.waypoint = waypoint;
        this.isNew = isNew;
        this.saveDim = saveDim;
        this.saveList = saveList;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);

        int centerX = this.width / 2;
        int startY = 50;

        // Name field
        this.nameField = new GuiTextField(this.fontRenderer, centerX - 100, startY, 200, 18);
        this.nameField.setMaxStringLength(32);
        this.nameField.setText(waypoint.name);
        this.nameField.setFocused(true);

        // Coordinate fields
        int coordY = startY + 35;
        int fieldW = 58;
        int gap = 8;
        int totalW = fieldW * 3 + gap * 2;
        int coordStartX = centerX - totalW / 2;

        this.xField = new GuiTextField(this.fontRenderer, coordStartX, coordY, fieldW, 18);
        this.xField.setMaxStringLength(10);
        this.xField.setText(String.valueOf(waypoint.x));

        this.yField = new GuiTextField(this.fontRenderer, coordStartX + fieldW + gap, coordY, fieldW, 18);
        this.yField.setMaxStringLength(10);
        this.yField.setText(String.valueOf(waypoint.y));

        this.zField = new GuiTextField(this.fontRenderer, coordStartX + (fieldW + gap) * 2, coordY, fieldW, 18);
        this.zField.setMaxStringLength(10);
        this.zField.setText(String.valueOf(waypoint.z));

        // Color picker layout (no GuiButtons — we draw and handle clicks manually)
        int colorY = coordY + 40;
        int totalColorW = COLOR_PRESETS.length * (SWATCH_SIZE + SWATCH_GAP) - SWATCH_GAP;
        colorSwatchStartX = centerX - totalColorW / 2;
        colorSwatchY = colorY + 12;

        // Save / Cancel buttons
        int bottomY = colorY + 40;
        this.buttonList.add(new GuiButton(0, centerX - 104, bottomY, 100, 20, "Save"));
        this.buttonList.add(new GuiButton(1, centerX + 4, bottomY, 100, 20, "Cancel"));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled)
            return;

        if (button.id == 0) {
            // Save
            saveWaypoint();
            this.mc.displayGuiScreen(parent);
        } else if (button.id == 1) {
            // Cancel — remove waypoint if it was just newly added
            if (isNew) {
                if (saveList != null) {
                    saveList.remove(waypoint);
                } else {
                    WaypointManager.instance.removeWaypoint(waypoint);
                }
            }
            this.mc.displayGuiScreen(parent);
        }
    }

    private void saveWaypoint() {
        String name = nameField.getText().trim();
        if (!name.isEmpty()) {
            waypoint.name = name;
        }

        try {
            waypoint.x = Integer.parseInt(xField.getText().trim());
        } catch (NumberFormatException ignored) {
        }

        try {
            waypoint.y = Integer.parseInt(yField.getText().trim());
        } catch (NumberFormatException ignored) {
        }

        try {
            waypoint.z = Integer.parseInt(zField.getText().trim());
        } catch (NumberFormatException ignored) {
        }

        if (saveList != null) {
            WaypointManager.instance.saveWaypointsForDim(saveDim, saveList);
        } else {
            WaypointManager.instance.save();
        }
    }

    @Override
    protected void keyTyped(char c, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN) {
            saveWaypoint();
            this.mc.displayGuiScreen(parent);
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            if (nameField.isFocused()) {
                nameField.setFocused(false);
                xField.setFocused(true);
            } else if (xField.isFocused()) {
                xField.setFocused(false);
                yField.setFocused(true);
            } else if (yField.isFocused()) {
                yField.setFocused(false);
                zField.setFocused(true);
            } else {
                zField.setFocused(false);
                nameField.setFocused(true);
            }
            return;
        }

        this.nameField.textboxKeyTyped(c, keyCode);
        // Only allow digits, minus, and control keys for coord fields
        if (Character.isDigit(c) || c == '-' || keyCode == Keyboard.KEY_BACK
                || keyCode == Keyboard.KEY_DELETE || keyCode == Keyboard.KEY_LEFT
                || keyCode == Keyboard.KEY_RIGHT || keyCode == Keyboard.KEY_HOME
                || keyCode == Keyboard.KEY_END) {
            this.xField.textboxKeyTyped(c, keyCode);
            this.yField.textboxKeyTyped(c, keyCode);
            this.zField.textboxKeyTyped(c, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.nameField.mouseClicked(mouseX, mouseY, mouseButton);
        this.xField.mouseClicked(mouseX, mouseY, mouseButton);
        this.yField.mouseClicked(mouseX, mouseY, mouseButton);
        this.zField.mouseClicked(mouseX, mouseY, mouseButton);

        // Color swatch click handling
        if (mouseButton == 0 && mouseY >= colorSwatchY && mouseY < colorSwatchY + SWATCH_SIZE) {
            for (int i = 0; i < COLOR_PRESETS.length; i++) {
                int bx = colorSwatchStartX + i * (SWATCH_SIZE + SWATCH_GAP);
                if (mouseX >= bx && mouseX < bx + SWATCH_SIZE) {
                    waypoint.color = COLOR_PRESETS[i];
                    break;
                }
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        // Title
        this.drawCenteredString(this.fontRenderer, "Edit Waypoint", this.width / 2, 20, 0xFFFFFF);

        int centerX = this.width / 2;
        int startY = 50;

        // Labels
        this.fontRenderer.drawString("Name:", centerX - 100, startY - 10, 0xAAAAAA);

        int coordY = startY + 35;
        int fieldW = 58;
        int gap = 8;
        int totalW = fieldW * 3 + gap * 2;
        int coordStartX = centerX - totalW / 2;

        this.fontRenderer.drawString("x:", coordStartX, coordY - 10, 0xFF6666);
        this.fontRenderer.drawString("y:", coordStartX + fieldW + gap, coordY - 10, 0x66FF66);
        this.fontRenderer.drawString("z:", coordStartX + (fieldW + gap) * 2, coordY - 10, 0x6666FF);

        // Draw text fields
        this.nameField.drawTextBox();
        this.xField.drawTextBox();
        this.yField.drawTextBox();
        this.zField.drawTextBox();

        // Color label
        int colorLabelY = coordY + 30;
        this.fontRenderer.drawString("Color:", centerX - 100, colorLabelY, 0xAAAAAA);

        // Draw clean color swatches
        for (int i = 0; i < COLOR_PRESETS.length; i++) {
            int bx = colorSwatchStartX + i * (SWATCH_SIZE + SWATCH_GAP);
            int by = colorSwatchY;

            // Selection ring (white border for selected)
            if (waypoint.color == COLOR_PRESETS[i]) {
                drawRect(bx - 2, by - 2, bx + SWATCH_SIZE + 2, by + SWATCH_SIZE + 2, 0xFFFFFFFF);
            }

            // Dark border around each swatch
            drawRect(bx - 1, by - 1, bx + SWATCH_SIZE + 1, by + SWATCH_SIZE + 1, 0xFF222222);

            // Filled color swatch
            drawRect(bx, by, bx + SWATCH_SIZE, by + SWATCH_SIZE, 0xFF000000 | COLOR_PRESETS[i]);

            // Hover highlight
            if (mouseX >= bx && mouseX < bx + SWATCH_SIZE && mouseY >= by && mouseY < by + SWATCH_SIZE) {
                drawRect(bx, by, bx + SWATCH_SIZE, by + SWATCH_SIZE, 0x30FFFFFF);
            }
        }

        // Current color preview (larger, next to label)
        int previewX = centerX + 60;
        drawRect(previewX - 1, colorLabelY - 3, previewX + 31, colorLabelY + 13, 0xFF222222);
        drawRect(previewX, colorLabelY - 2, previewX + 30, colorLabelY + 12, 0xFF000000 | waypoint.color);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }
}
