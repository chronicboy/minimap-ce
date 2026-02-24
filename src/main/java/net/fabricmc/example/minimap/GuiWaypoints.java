package net.fabricmc.example.minimap;

import net.minecraft.src.*;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Waypoint management GUI.
 * Shows spawn point as a special non-deletable entry at the top,
 * followed by user waypoints with proper GuiButton actions.
 */
public class GuiWaypoints extends GuiScreen {
    private int scrollOffset = 0;
    private static final int ENTRY_HEIGHT = 28;
    private static final int VISIBLE_ENTRIES = 7;
    private int listTop;
    private int listLeft;
    private int listWidth;
    private static final int BTN_W = 30;
    private static final int BTN_H = 16;
    private static final int BTN_GAP = 2;

    public GuiWaypoints() {
    }

    @SuppressWarnings("unchecked")
    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);

        listWidth = 310;
        listLeft = (this.width - listWidth) / 2;
        listTop = 44;

        int buttonY = listTop + VISIBLE_ENTRIES * ENTRY_HEIGHT + 12;
        int gap = 8;

        int totalW = 90 * 3 + gap * 2;
        int startX = (this.width - totalW) / 2;

        this.buttonList.add(new GuiButton(0, startX, buttonY, 90, 20, "\u00a7aAdd New"));
        this.buttonList.add(new GuiButton(1, startX + 90 + gap, buttonY, 90, 20, "\u00a7eToggle All"));
        this.buttonList.add(new GuiButton(2, startX + (90 + gap) * 2, buttonY, 90, 20, "Done"));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled)
            return;

        switch (button.id) {
            case 0: // Add Here
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.thePlayer != null) {
                    int px = (int) Math.floor(mc.thePlayer.posX);
                    int pz = (int) Math.floor(mc.thePlayer.posZ);
                    int py = (int) mc.thePlayer.posY;

                    // Scan downwards for ground
                    for (int y = py; y > 0; y--) {
                        int id = mc.theWorld.getBlockId(px, y - 1, pz);
                        if (id != 0) { // Found potential block
                            Block b = Block.blocksList[id];
                            if (b != null && b.blockMaterial.isSolid()) {
                                py = y - 1;
                                break;
                            }
                        }
                    }

                    String name = "Waypoint " + (WaypointManager.instance.getWaypoints().size() + 1);
                    int color = getNextColor(WaypointManager.instance.getWaypoints().size());
                    Waypoint wp = new Waypoint(name, px, py, pz, color);
                    WaypointManager.instance.addWaypoint(wp);
                    mc.displayGuiScreen(new GuiWaypointEdit(this, wp, true));
                }
                break;
            case 1: // Toggle All
                List<Waypoint> wps = WaypointManager.instance.getWaypoints();
                boolean anyEnabled = false;
                for (Waypoint w : wps) {
                    if (w.enabled) {
                        anyEnabled = true;
                        break;
                    }
                }
                for (Waypoint w : wps) {
                    w.enabled = !anyEnabled;
                }
                WaypointManager.instance.save();
                break;
            case 2: // Done
                this.mc.displayGuiScreen(null);
                break;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        // Title
        this.drawCenteredString(this.fontRenderer, "\u00a7l\u00a7nWaypoints", this.width / 2, 12, 0xFFFFFF);

        // Subtitle
        this.drawCenteredString(this.fontRenderer, "\u00a77Per-world waypoint manager", this.width / 2, 26, 0x888888);

        // ---- Spawn Point (always first, special entry) ----
        Minecraft mc = Minecraft.getMinecraft();
        int spawnEntryY = listTop;
        boolean hasSpawn = mc.theWorld != null && mc.theWorld.getWorldInfo() != null;

        if (hasSpawn) {
            int spawnX = mc.theWorld.getWorldInfo().getSpawnX();
            int spawnY = mc.theWorld.getWorldInfo().getSpawnY();
            int spawnZ = mc.theWorld.getWorldInfo().getSpawnZ();
            boolean spawnVisible = MapConfig.instance.showSpawnWaypoint;

            // Spawn entry background (blue-tinted, dimmed when off)
            drawRect(listLeft - 2, spawnEntryY - 2, listLeft + listWidth + 2, spawnEntryY + ENTRY_HEIGHT,
                    spawnVisible ? 0x60003366 : 0x40222222);

            // Spawn icon (blue diamond)
            drawColorSwatch(listLeft + 4, spawnEntryY + (ENTRY_HEIGHT - 12) / 2, 12, 0x0066CC);

            // Spawn label (strikethrough when hidden)
            String spawnLabel = spawnVisible ? "\u00a7b\u00a7lSpawn Point" : "\u00a77\u00a7m\u00a7lSpawn Point";
            this.fontRenderer.drawString(spawnLabel, listLeft + 22, spawnEntryY + 3, 0x5599FF);
            String spawnCoords = String.format("\u00a77x: %d, z: %d, y: %d", spawnX, spawnZ, spawnY);
            this.fontRenderer.drawString(spawnCoords, listLeft + 22, spawnEntryY + 14, 0x888888);

            // Buttons (right to left)
            int bx = listLeft + listWidth - 4;

            // TP button
            bx -= BTN_W + 4;
            boolean tpHovered = mouseX >= bx && mouseX < bx + BTN_W + 4
                    && mouseY >= spawnEntryY + 4 && mouseY < spawnEntryY + 4 + BTN_H;
            drawButton(bx, spawnEntryY + 4, BTN_W + 4, BTN_H, "\u00a7aTP", tpHovered);
            bx -= BTN_GAP;

            // Toggle visibility button
            bx -= BTN_W + 4;
            String togLabel = spawnVisible ? "\u00a7aON" : "\u00a7cOFF";
            boolean togHovered = mouseX >= bx && mouseX < bx + BTN_W + 4
                    && mouseY >= spawnEntryY + 4 && mouseY < spawnEntryY + 4 + BTN_H;
            drawButton(bx, spawnEntryY + 4, BTN_W + 4, BTN_H, togLabel, togHovered);
        }

        // ---- User Waypoints ----
        List<Waypoint> waypoints = WaypointManager.instance.getWaypoints();
        int waypointListTop = listTop + ENTRY_HEIGHT + 4; // Below spawn entry

        if (waypoints.isEmpty()) {
            this.drawCenteredString(this.fontRenderer, "\u00a78No waypoints. Click 'Add Here' to create one.",
                    this.width / 2, waypointListTop + 20, 0x666666);
        } else {
            int maxScroll = Math.max(0, waypoints.size() - VISIBLE_ENTRIES + 1);
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

            // List background
            drawRect(listLeft - 2, waypointListTop - 2, listLeft + listWidth + 2,
                    waypointListTop + (Math.min(waypoints.size(), VISIBLE_ENTRIES - 1)) * ENTRY_HEIGHT + 2, 0x50000000);

            for (int i = 0; i < VISIBLE_ENTRIES - 1 && i + scrollOffset < waypoints.size(); i++) {
                Waypoint wp = waypoints.get(i + scrollOffset);
                int entryY = waypointListTop + i * ENTRY_HEIGHT;

                // Row hover highlight
                boolean rowHovered = mouseX >= listLeft && mouseX < listLeft + listWidth
                        && mouseY >= entryY && mouseY < entryY + ENTRY_HEIGHT;
                if (rowHovered) {
                    drawRect(listLeft, entryY, listLeft + listWidth, entryY + ENTRY_HEIGHT, 0x30FFFFFF);
                }

                // Separator line
                if (i > 0) {
                    drawRect(listLeft + 4, entryY, listLeft + listWidth - 4, entryY + 1, 0x30FFFFFF);
                }

                // Color swatch
                drawColorSwatch(listLeft + 4, entryY + (ENTRY_HEIGHT - 12) / 2, 12, wp.color);

                // Name (strikethrough if disabled)
                String displayName = wp.enabled ? wp.name : "\u00a77\u00a7m" + wp.name + "\u00a7r";
                this.fontRenderer.drawString(displayName, listLeft + 22, entryY + 4, wp.enabled ? 0xFFFFFF : 0x888888);

                // Coords
                String coords = String.format("\u00a77x: %d, z: %d, y: %d", wp.x, wp.z, wp.y);
                this.fontRenderer.drawString(coords, listLeft + 22, entryY + 14, 0x888888);

                // Action buttons (right to left)
                int bx = listLeft + listWidth - 4;

                // Delete button (red X)
                bx -= BTN_W;
                boolean delHover = mouseX >= bx && mouseX < bx + BTN_W && mouseY >= entryY + 5
                        && mouseY < entryY + 5 + BTN_H;
                drawButton(bx, entryY + 5, BTN_W, BTN_H, "\u00a7cDel", delHover);
                bx -= BTN_GAP;

                // Edit button
                bx -= BTN_W;
                boolean editHover = mouseX >= bx && mouseX < bx + BTN_W && mouseY >= entryY + 5
                        && mouseY < entryY + 5 + BTN_H;
                drawButton(bx, entryY + 5, BTN_W, BTN_H, "\u00a79Edit", editHover);
                bx -= BTN_GAP;

                // Teleport button
                bx -= BTN_W;
                boolean tpHover = mouseX >= bx && mouseX < bx + BTN_W && mouseY >= entryY + 5
                        && mouseY < entryY + 5 + BTN_H;
                drawButton(bx, entryY + 5, BTN_W, BTN_H, "\u00a7aTP", tpHover);
                bx -= BTN_GAP;

                // Toggle button
                bx -= BTN_W + 4;
                String toggleLabel = wp.enabled ? "\u00a7aON" : "\u00a7cOFF";
                boolean togHover = mouseX >= bx && mouseX < bx + BTN_W + 4 && mouseY >= entryY + 5
                        && mouseY < entryY + 5 + BTN_H;
                drawButton(bx, entryY + 5, BTN_W + 4, BTN_H, toggleLabel, togHover);
            }

            // Scroll info
            if (waypoints.size() > VISIBLE_ENTRIES - 1) {
                int showing = Math.min(VISIBLE_ENTRIES - 1, waypoints.size() - scrollOffset);
                String scrollText = String.format("\u00a78\u2191\u2193 %d-%d of %d",
                        scrollOffset + 1, scrollOffset + showing, waypoints.size());
                this.drawCenteredString(this.fontRenderer, scrollText, this.width / 2,
                        waypointListTop + (VISIBLE_ENTRIES - 1) * ENTRY_HEIGHT + 2, 0x666666);
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /**
     * Draws a small button-like box with text.
     */
    private void drawButton(int x, int y, int w, int h, String text, boolean hovered) {
        int bg = hovered ? 0xA0555555 : 0x80333333;
        int border = hovered ? 0xC0888888 : 0x80555555;
        drawRect(x, y, x + w, y + h, bg);
        drawRect(x, y, x + w, y + 1, border); // top
        drawRect(x, y + h - 1, x + w, y + h, border); // bottom
        drawRect(x, y, x + 1, y + h, border); // left
        drawRect(x + w - 1, y, x + w, y + h, border); // right
        int textW = this.fontRenderer.getStringWidth(text.replaceAll("\u00a7.", ""));
        this.fontRenderer.drawString(text, x + (w - textW) / 2, y + (h - 8) / 2, 0xFFFFFF);
    }

    /**
     * Draws a colored square swatch.
     */
    private void drawColorSwatch(int x, int y, int size, int color) {
        drawRect(x, y, x + size, y + size, 0xFF000000 | color);
        drawRect(x, y, x + size, y + 1, 0x60FFFFFF); // highlight top
        drawRect(x, y + size - 1, x + size, y + size, 0x40000000); // shadow bottom
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0)
            return;

        Minecraft mc = Minecraft.getMinecraft();

        // Check spawn button clicks
        int spawnEntryY = listTop;
        boolean hasSpawn = mc.theWorld != null && mc.theWorld.getWorldInfo() != null;
        if (hasSpawn) {
            int bx = listLeft + listWidth - 4;

            // TP button
            bx -= BTN_W + 4;
            if (mouseX >= bx && mouseX < bx + BTN_W + 4
                    && mouseY >= spawnEntryY + 4 && mouseY < spawnEntryY + 4 + BTN_H) {
                int spawnX = mc.theWorld.getWorldInfo().getSpawnX();
                int spawnY = mc.theWorld.getWorldInfo().getSpawnY();
                int spawnZ = mc.theWorld.getWorldInfo().getSpawnZ();
                mc.displayGuiScreen(null);
                mc.thePlayer.sendChatMessage("/tp " + spawnX + " " + (spawnY + 2) + " " + spawnZ);
                return;
            }
            bx -= BTN_GAP;

            // Toggle visibility button
            bx -= BTN_W + 4;
            if (mouseX >= bx && mouseX < bx + BTN_W + 4
                    && mouseY >= spawnEntryY + 4 && mouseY < spawnEntryY + 4 + BTN_H) {
                MapConfig.instance.showSpawnWaypoint = !MapConfig.instance.showSpawnWaypoint;
                MapConfig.instance.saveConfig();
                return;
            }
        }

        // Check waypoint button clicks
        List<Waypoint> waypoints = WaypointManager.instance.getWaypoints();
        int waypointListTop = listTop + ENTRY_HEIGHT + 4;

        for (int i = 0; i < VISIBLE_ENTRIES - 1 && i + scrollOffset < waypoints.size(); i++) {
            int entryY = waypointListTop + i * ENTRY_HEIGHT;
            int idx = i + scrollOffset;

            if (mouseX < listLeft || mouseX >= listLeft + listWidth
                    || mouseY < entryY || mouseY >= entryY + ENTRY_HEIGHT) {
                continue;
            }

            // Check each button right to left
            int bx = listLeft + listWidth - 4;

            // Delete
            bx -= BTN_W;
            if (mouseX >= bx && mouseX < bx + BTN_W && mouseY >= entryY + 5 && mouseY < entryY + 5 + BTN_H) {
                WaypointManager.instance.removeWaypoint(idx);
                return;
            }
            bx -= BTN_GAP;

            // Edit
            bx -= BTN_W;
            if (mouseX >= bx && mouseX < bx + BTN_W && mouseY >= entryY + 5 && mouseY < entryY + 5 + BTN_H) {
                mc.displayGuiScreen(new GuiWaypointEdit(this, waypoints.get(idx)));
                return;
            }
            bx -= BTN_GAP;

            // Teleport
            bx -= BTN_W;
            if (mouseX >= bx && mouseX < bx + BTN_W && mouseY >= entryY + 5 && mouseY < entryY + 5 + BTN_H) {
                Waypoint wp = waypoints.get(idx);
                mc.displayGuiScreen(null);
                mc.thePlayer.sendChatMessage("/tp " + wp.x + " " + (wp.y + 2) + " " + wp.z);
                return;
            }
            bx -= BTN_GAP;

            // Toggle
            bx -= BTN_W + 4;
            if (mouseX >= bx && mouseX < bx + BTN_W + 4 && mouseY >= entryY + 5 && mouseY < entryY + 5 + BTN_H) {
                WaypointManager.instance.toggleWaypoint(idx);
                return;
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int scroll = org.lwjgl.input.Mouse.getDWheel();
        if (scroll != 0) {
            scrollOffset += (scroll > 0) ? -1 : 1;
            int maxScroll = Math.max(0, WaypointManager.instance.getWaypoints().size() - (VISIBLE_ENTRIES - 1));
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        }
    }

    private int getNextColor(int index) {
        int[] colors = {
                0xFF5555, 0x55FF55, 0x5555FF, 0xFFFF55,
                0xFF55FF, 0x55FFFF, 0xFF8800, 0xAA55FF
        };
        return colors[index % colors.length];
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }
}
