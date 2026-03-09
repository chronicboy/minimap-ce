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
    private int visibleEntries;
    private int listTop;
    private int listLeft;
    private int listWidth;
    private static final int BTN_W = 30;
    private static final int BTN_H = 16;
    private static final int BTN_GAP = 2;

    // Cross-dimension viewing
    private int viewDimension = Integer.MIN_VALUE;
    private java.util.List<Waypoint> crossDimWaypoints = null;

    public GuiWaypoints() {
    }

    @SuppressWarnings("unchecked")
    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);

        Minecraft mc = Minecraft.getMinecraft();
        if (viewDimension == Integer.MIN_VALUE && mc.theWorld != null) {
            viewDimension = mc.theWorld.provider.dimensionId;
        }

        listWidth = 310;
        listLeft = (this.width - listWidth) / 2;
        listTop = 58;

        int availableHeight = this.height - listTop - ENTRY_HEIGHT - 4 - 32 - 10;
        visibleEntries = Math.max(2, availableHeight / ENTRY_HEIGHT) + 1;

        int buttonY = listTop + visibleEntries * ENTRY_HEIGHT + 12;
        int gap = 6;
        int btnW = 80;

        // Dimension tabs at top will be custom drawn

        int totalW = btnW * 4 + gap * 3;
        int startX = (this.width - totalW) / 2;

        this.buttonList.add(new GuiButton(0, startX, buttonY, btnW, 20, "\u00a7aAdd New"));
        this.buttonList.add(new GuiButton(1, startX + btnW + gap, buttonY, btnW, 20, "\u00a7eToggle All"));
        this.buttonList.add(new GuiButton(3, startX + (btnW + gap) * 2, buttonY, btnW, 20, "\u2699 Settings"));
        this.buttonList.add(new GuiButton(2, startX + (btnW + gap) * 3, buttonY, btnW, 20, "Done"));
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

                    for (int y = py; y > 0; y--) {
                        int id = mc.theWorld.getBlockId(px, y - 1, pz);
                        if (id != 0) {
                            Block b = Block.blocksList[id];
                            if (b != null && b.blockMaterial.isSolid()) {
                                py = y - 1;
                                break;
                            }
                        }
                    }

                    List<Waypoint> list = crossDimWaypoints != null ? crossDimWaypoints
                            : WaypointManager.instance.getWaypoints();
                    String name = "Waypoint " + (list.size() + 1);
                    int color = getNextColor(list.size());
                    Waypoint wp = new Waypoint(name, px, py, pz, color);
                    list.add(wp); // Temporarily add it, GuiWaypointEdit will remove it if cancelled
                    mc.displayGuiScreen(new GuiWaypointEdit(this, wp, true, viewDimension, crossDimWaypoints));
                }
                break;
            case 1: // Toggle All
                List<Waypoint> wps = crossDimWaypoints != null ? crossDimWaypoints
                        : WaypointManager.instance.getWaypoints();
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
                if (crossDimWaypoints != null) {
                    WaypointManager.instance.saveWaypointsForDim(viewDimension, crossDimWaypoints);
                } else {
                    WaypointManager.instance.save();
                }
                break;
            case 2: // Done
                this.mc.displayGuiScreen(null);
                break;
            case 3: // Settings
                this.mc.displayGuiScreen(new GuiMapSettings(this));
                break;
        }
    }

    private void switchDimension(int dim) {
        if (dim == viewDimension)
            return;
        viewDimension = dim;
        Minecraft mc = Minecraft.getMinecraft();
        int currentDim = mc.theWorld != null ? mc.theWorld.provider.dimensionId : 0;
        if (dim != currentDim) {
            crossDimWaypoints = WaypointManager.instance.loadWaypointsForDim(dim);
        } else {
            crossDimWaypoints = null;
        }
        scrollOffset = 0;
        this.buttonList.clear();
        initGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        // ---- Dimension tabs (custom drawn) ----
        if (fontRenderer != null) {
            String[] dimNames = { "Overworld", "Nether", "End" };
            int[] dimIds = { 0, -1, 1 };
            int tabGap = 16;
            int totalTabW = 0;
            for (String n : dimNames)
                totalTabW += fontRenderer.getStringWidth(n);
            totalTabW += tabGap * (dimNames.length - 1);
            int tabX = (this.width - totalTabW) / 2;
            int tabY = (30 - 8) / 2; // Matches GuiFullscreenMap
            for (int i = 0; i < dimNames.length; i++) {
                boolean active = (viewDimension == dimIds[i]);
                int tw = fontRenderer.getStringWidth(dimNames[i]);
                int color = active ? 0xFFFFFF : 0x888888;
                fontRenderer.drawStringWithShadow(dimNames[i], tabX, tabY, color);
                if (active) {
                    drawRect(tabX - 1, tabY + 10, tabX + tw + 1, tabY + 12, 0xFF6699FF);
                }
                tabX += tw + tabGap;
            }
        }

        // Title
        this.drawCenteredString(this.fontRenderer, "\u00a7l\u00a7nWaypoints", this.width / 2, 32, 0xFFFFFF);

        // Subtitle showing viewed dimension
        String dimName = viewDimension == 0 ? "Overworld" : (viewDimension == -1 ? "Nether" : "End");
        this.drawCenteredString(this.fontRenderer, "\u00a77" + dimName, this.width / 2, 46, 0x888888);

        // ---- Spawn Point (always first, special entry) ----
        Minecraft mc = Minecraft.getMinecraft();
        int spawnEntryY = listTop;
        boolean hasSpawn = SpawnTracker.instance.hasSpawn();

        if (hasSpawn) {
            int spawnX, spawnY, spawnZ;
            if (viewDimension == 1) {
                spawnX = 100;
                spawnY = 49;
                spawnZ = 0;
            } else if (viewDimension == -1) {
                spawnX = SpawnTracker.instance.getNetherSpawnX();
                spawnZ = SpawnTracker.instance.getNetherSpawnZ();
                spawnY = SpawnTracker.instance.getSpawnY();
            } else {
                spawnX = SpawnTracker.instance.getSpawnX();
                spawnZ = SpawnTracker.instance.getSpawnZ();
                spawnY = SpawnTracker.instance.getSpawnY();
            }

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

            // TP button (only if enabled in config)
            if (MapConfig.instance.showTeleportButton) {
                bx -= BTN_W + 4;
                boolean tpHovered = mouseX >= bx && mouseX < bx + BTN_W + 4
                        && mouseY >= spawnEntryY + 4 && mouseY < spawnEntryY + 4 + BTN_H;
                drawButton(bx, spawnEntryY + 4, BTN_W + 4, BTN_H, "\u00a7aTP", tpHovered);
                bx -= BTN_GAP;
            }

            // Toggle visibility button
            bx -= BTN_W + 4;
            String togLabel = spawnVisible ? "\u00a7aON" : "\u00a7cOFF";
            boolean togHovered = mouseX >= bx && mouseX < bx + BTN_W + 4
                    && mouseY >= spawnEntryY + 4 && mouseY < spawnEntryY + 4 + BTN_H;
            drawButton(bx, spawnEntryY + 4, BTN_W + 4, BTN_H, togLabel, togHovered);
        }

        // ---- User Waypoints ----
        List<Waypoint> waypoints = crossDimWaypoints != null ? crossDimWaypoints
                : WaypointManager.instance.getWaypoints();
        int waypointListTop = listTop + ENTRY_HEIGHT + 4; // Below spawn entry
        boolean isCurrentDim = mc.theWorld != null && viewDimension == mc.theWorld.provider.dimensionId;

        if (waypoints.isEmpty()) {
            String emptyMsg = isCurrentDim ? "\u00a78No waypoints. Click 'Add New' to create one."
                    : "\u00a78No waypoints in " + dimName + ".";
            this.drawCenteredString(this.fontRenderer, emptyMsg,
                    this.width / 2, waypointListTop + 20, 0x666666);
        } else {
            int maxScroll = Math.max(0, waypoints.size() - visibleEntries + 1);
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

            // List background
            drawRect(listLeft - 2, waypointListTop - 2, listLeft + listWidth + 2,
                    waypointListTop + (Math.min(waypoints.size(), visibleEntries - 1)) * ENTRY_HEIGHT + 2, 0x50000000);

            for (int i = 0; i < visibleEntries - 1 && i + scrollOffset < waypoints.size(); i++) {
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

                // Action buttons
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

                // Teleport button (only if enabled in config)
                if (MapConfig.instance.showTeleportButton) {
                    bx -= BTN_W;
                    boolean tpHover = mouseX >= bx && mouseX < bx + BTN_W && mouseY >= entryY + 5
                            && mouseY < entryY + 5 + BTN_H;
                    drawButton(bx, entryY + 5, BTN_W, BTN_H, "\u00a7aTP", tpHover);
                    bx -= BTN_GAP;
                }

                // Toggle button
                bx -= BTN_W + 4;
                String toggleLabel = wp.enabled ? "\u00a7aON" : "\u00a7cOFF";
                boolean togHover = mouseX >= bx && mouseX < bx + BTN_W + 4 && mouseY >= entryY + 5
                        && mouseY < entryY + 5 + BTN_H;
                drawButton(bx, entryY + 5, BTN_W + 4, BTN_H, toggleLabel, togHover);
            }

            // Scroll info
            if (waypoints.size() > visibleEntries - 1) {
                int showing = Math.min(visibleEntries - 1, waypoints.size() - scrollOffset);
                String scrollText = String.format("\u00a78\u2191\u2193 %d-%d of %d",
                        scrollOffset + 1, scrollOffset + showing, waypoints.size());
                this.drawCenteredString(this.fontRenderer, scrollText, this.width / 2,
                        waypointListTop + (visibleEntries - 1) * ENTRY_HEIGHT + 2, 0x666666);
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

        // Check dimension tab clicks at top (y goes up to 30)
        if (mouseY < 30 && fontRenderer != null) {
            String[] dimNames = { "Overworld", "Nether", "End" };
            int[] dimIds = { 0, -1, 1 };
            int tabGap = 16;
            int totalTabW = 0;
            for (String n : dimNames)
                totalTabW += fontRenderer.getStringWidth(n);
            totalTabW += tabGap * (dimNames.length - 1);
            int tabX = (this.width - totalTabW) / 2;
            for (int i = 0; i < dimNames.length; i++) {
                int tw = fontRenderer.getStringWidth(dimNames[i]);
                if (mouseX >= tabX - 2 && mouseX < tabX + tw + 2) {
                    switchDimension(dimIds[i]);
                    return;
                }
                tabX += tw + tabGap;
            }
        }

        // Check spawn button clicks
        int spawnEntryY = listTop;
        boolean hasSpawn = SpawnTracker.instance.hasSpawn();
        boolean isCurrentDim = mc.theWorld != null && viewDimension == mc.theWorld.provider.dimensionId;

        if (hasSpawn) {
            int bx = listLeft + listWidth - 4;

            // TP button (only in current dimension)
            bx -= BTN_W + 4;
            if (isCurrentDim && MapConfig.instance.showTeleportButton
                    && mouseX >= bx && mouseX < bx + BTN_W + 4
                    && mouseY >= spawnEntryY + 4 && mouseY < spawnEntryY + 4 + BTN_H) {
                int dim = mc.theWorld.provider.dimensionId;
                int spawnX, spawnY, spawnZ;
                if (dim == 1) {
                    spawnX = 100;
                    spawnY = 49;
                    spawnZ = 0;
                } else if (dim == -1) {
                    spawnX = SpawnTracker.instance.getNetherSpawnX();
                    spawnZ = SpawnTracker.instance.getNetherSpawnZ();
                    spawnY = SpawnTracker.instance.getSpawnY();
                } else {
                    spawnX = SpawnTracker.instance.getSpawnX();
                    spawnZ = SpawnTracker.instance.getSpawnZ();
                    spawnY = SpawnTracker.instance.getSpawnY();
                }
                mc.displayGuiScreen(null);

                String cmdTemplate = MapConfig.instance.teleportCommand;
                if (dim != mc.theWorld.provider.dimensionId) {
                    // Try integrated server teleport if we have cheats
                    if (mc.isSingleplayer() && mc.getIntegratedServer() != null
                            && mc.getIntegratedServer().getWorldName() != null && mc.getIntegratedServer()
                                    .getConfigurationManager().getOps().contains(mc.thePlayer.username.toLowerCase())) {
                        net.minecraft.src.EntityPlayerMP playerMP = mc.getIntegratedServer().getConfigurationManager()
                                .getPlayerEntity(mc.thePlayer.username);
                        if (playerMP != null) {
                            mc.getIntegratedServer().getConfigurationManager().transferPlayerToDimension(playerMP, dim);
                            mc.thePlayer.sendChatMessage("/tp " + spawnX + " " + (spawnY + 2) + " " + spawnZ);
                            return;
                        }
                    }

                    if (!cmdTemplate.contains("{dim}")) {
                        mc.thePlayer.addChatMessage(
                                "\u00a7cCannot teleport across dimensions! Please configure a cross-dimension teleportCommand in minimap-ce.conf that uses {dim}.");
                        return;
                    }
                }

                String cmd = cmdTemplate
                        .replace("{x}", String.valueOf(spawnX))
                        .replace("{y}", String.valueOf(spawnY + 2))
                        .replace("{z}", String.valueOf(spawnZ))
                        .replace("{dim}", String.valueOf(dim))
                        .replace("{name}", "Spawn Point");
                mc.thePlayer.sendChatMessage(cmd);
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
        List<Waypoint> waypoints = crossDimWaypoints != null ? crossDimWaypoints
                : WaypointManager.instance.getWaypoints();
        int waypointListTop = listTop + ENTRY_HEIGHT + 4;

        for (int i = 0; i < visibleEntries - 1 && i + scrollOffset < waypoints.size(); i++) {
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
                if (crossDimWaypoints != null) {
                    crossDimWaypoints.remove(idx);
                    WaypointManager.instance.saveWaypointsForDim(viewDimension, crossDimWaypoints);
                } else {
                    WaypointManager.instance.removeWaypoint(idx);
                }
                return;
            }
            bx -= BTN_GAP;

            // Edit
            bx -= BTN_W;
            if (mouseX >= bx && mouseX < bx + BTN_W && mouseY >= entryY + 5 && mouseY < entryY + 5 + BTN_H) {
                mc.displayGuiScreen(
                        new GuiWaypointEdit(this, waypoints.get(idx), false, viewDimension, crossDimWaypoints));
                return;
            }
            bx -= BTN_GAP;

            // Teleport
            if (MapConfig.instance.showTeleportButton) {
                bx -= BTN_W;
                if (mouseX >= bx && mouseX < bx + BTN_W && mouseY >= entryY + 5 && mouseY < entryY + 5 + BTN_H) {
                    Waypoint wp = waypoints.get(idx);
                    mc.displayGuiScreen(null);

                    String cmdTemplate = MapConfig.instance.teleportCommand;
                    if (viewDimension != mc.theWorld.provider.dimensionId) {
                        // Try integrated server teleport if we have cheats
                        if (mc.isSingleplayer() && mc.getIntegratedServer() != null
                                && mc.getIntegratedServer().getWorldName() != null
                                && mc.getIntegratedServer().getConfigurationManager().getOps()
                                        .contains(mc.thePlayer.username.toLowerCase())) {
                            net.minecraft.src.EntityPlayerMP playerMP = mc.getIntegratedServer()
                                    .getConfigurationManager().getPlayerEntity(mc.thePlayer.username);
                            if (playerMP != null) {
                                mc.getIntegratedServer().getConfigurationManager().transferPlayerToDimension(playerMP,
                                        viewDimension);
                                mc.thePlayer.sendChatMessage("/tp " + wp.x + " " + (wp.y + 2) + " " + wp.z);
                                return;
                            }
                        }

                        if (!cmdTemplate.contains("{dim}")) {
                            mc.thePlayer.addChatMessage(
                                    "\u00a7cCannot teleport across dimensions! Please configure a cross-dimension teleportCommand in minimap-ce.conf that uses {dim}.");
                            return;
                        }
                    }

                    String cmd = cmdTemplate
                            .replace("{x}", String.valueOf(wp.x))
                            .replace("{y}", String.valueOf(wp.y + 2))
                            .replace("{z}", String.valueOf(wp.z))
                            .replace("{dim}", String.valueOf(viewDimension))
                            .replace("{name}", wp.name);
                    mc.thePlayer.sendChatMessage(cmd);
                    return;
                }
                bx -= BTN_GAP;
            }

            // Toggle
            bx -= BTN_W + 4;
            if (mouseX >= bx && mouseX < bx + BTN_W + 4 && mouseY >= entryY + 5 && mouseY < entryY + 5 + BTN_H) {
                if (crossDimWaypoints != null) {
                    Waypoint wp = crossDimWaypoints.get(idx);
                    wp.enabled = !wp.enabled;
                    WaypointManager.instance.saveWaypointsForDim(viewDimension, crossDimWaypoints);
                } else {
                    WaypointManager.instance.toggleWaypoint(idx);
                }
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
            int maxScroll = Math.max(0, WaypointManager.instance.getWaypoints().size() - (visibleEntries - 1));
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
