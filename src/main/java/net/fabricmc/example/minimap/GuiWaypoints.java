package net.fabricmc.example.minimap;

import net.minecraft.src.Block;
import net.minecraft.src.FontRenderer;
import net.minecraft.src.GuiButton;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.List;

/**
 * Waypoint list: one card with a dimension switcher, the spawn point pinned at
 * the top, then every waypoint with its colour tile, coordinates, distance and
 * actions. Clicking a row opens it for editing; deleting asks for a second click.
 */
public class GuiWaypoints extends GuiScreen {
	private static final String[] DIM_NAMES = { "Overworld", "Nether", "End" };
	private static final int[] DIM_IDS = { 0, -1, 1 };

	private static final int BTN_ADD = 0;
	private static final int BTN_TOGGLE_ALL = 1;
	private static final int BTN_DONE = 2;
	private static final int BTN_SETTINGS = 3;
	private static final int BTN_DIM_FIRST = 10;

	private static final int ROW_H = 24;
	private static final int HEADER_H = 28;
	private static final int FOOTER_H = 30;
	private static final int SMALL_H = 14;
	private static final long CONFIRM_MS = 3000L;

	private final GuiScreen parent;
	private int viewDimension = Integer.MIN_VALUE;
	private List<Waypoint> crossDimWaypoints;

	private int panelX, panelY, panelW, panelH;
	private int listTop, listBottom;
	private int scroll;
	private Waypoint pendingDelete;
	private long pendingDeleteUntil;

	public GuiWaypoints() {
		this(null);
	}

	/** @param parent screen to return to when done (null returns to the game) */
	public GuiWaypoints(GuiScreen parent) {
		this.parent = parent;
	}

	/**
	 * Creates a waypoint on the ground under the player and opens it for naming.
	 * Cancelling the edit screen removes it again. For another dimension the
	 * position is converted (Nether coordinates are an eighth of the Overworld's).
	 *
	 * @param crossDimList the waypoint list being edited when it is not the current
	 *                     dimension's, otherwise null
	 */
	public static void openNewWaypointAtPlayer(Minecraft mc, GuiScreen parent, List<Waypoint> crossDimList,
			int dimension) {
		if (mc.thePlayer == null || mc.theWorld == null)
			return;
		int px = (int) Math.floor(mc.thePlayer.posX);
		int pz = (int) Math.floor(mc.thePlayer.posZ);
		int py = (int) Math.floor(mc.thePlayer.boundingBox.minY);

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

		int here = mc.theWorld.provider.dimensionId;
		if (here == 0 && dimension == -1) {
			px = Math.floorDiv(px, 8);
			pz = Math.floorDiv(pz, 8);
		} else if (here == -1 && dimension == 0) {
			px *= 8;
			pz *= 8;
		}

		List<Waypoint> list = crossDimList != null ? crossDimList : WaypointManager.instance.getWaypoints();
		Waypoint wp = new Waypoint("Waypoint " + (list.size() + 1), px, py, pz, nextColor(list.size()));
		list.add(wp); // GuiWaypointEdit removes it again if cancelled
		mc.displayGuiScreen(new GuiWaypointEdit(parent, wp, true, dimension, crossDimList));
	}

	@SuppressWarnings("unchecked")
	@Override
	public void initGui() {
		Keyboard.enableRepeatEvents(true);
		Minecraft mc = Minecraft.getMinecraft();
		if (viewDimension == Integer.MIN_VALUE && mc.theWorld != null)
			viewDimension = mc.theWorld.provider.dimensionId;

		panelW = Math.min(420, this.width - 16);
		panelX = (this.width - panelW) / 2;
		panelY = this.height >= 300 ? 16 : 8;
		panelH = this.height - panelY * 2;
		listTop = panelY + HEADER_H + ROW_H + 4; // below the pinned spawn row
		listBottom = panelY + panelH - FOOTER_H;

		// Dimension switcher, right side of the header.
		int tabX = panelX + panelW - 6;
		for (int i = DIM_NAMES.length - 1; i >= 0; i--) {
			int w = this.fontRenderer.getStringWidth(DIM_NAMES[i]) + 14;
			tabX -= w;
			this.buttonList.add(new FlatButton(BTN_DIM_FIRST + i, tabX, panelY + 6, w, 16, DIM_NAMES[i])
					.style(MapUi.STYLE_TAB).selected(DIM_IDS[i] == viewDimension));
			tabX -= 2;
		}

		int by = panelY + panelH - FOOTER_H + 6;
		int x = panelX + 6;
		this.buttonList.add(new FlatButton(BTN_ADD, x, by, 106, 18, "+ New Waypoint").style(MapUi.STYLE_PRIMARY));
		this.buttonList.add(new FlatButton(BTN_TOGGLE_ALL, x + 110, by, 64, 18, anyShown() ? "Hide All" : "Show All"));
		int right = panelX + panelW - 6;
		this.buttonList.add(new FlatButton(BTN_DONE, right - 50, by, 50, 18, "Done"));
		this.buttonList.add(new FlatButton(BTN_SETTINGS, right - 50 - 4 - 56, by, 56, 18, "Settings"));
	}

	@Override
	public void onGuiClosed() {
		Keyboard.enableRepeatEvents(false);
	}

	private List<Waypoint> waypoints() {
		return crossDimWaypoints != null ? crossDimWaypoints : WaypointManager.instance.getWaypoints();
	}

	private void save() {
		if (crossDimWaypoints != null)
			WaypointManager.instance.saveWaypointsForDim(viewDimension, crossDimWaypoints);
		else
			WaypointManager.instance.save();
	}

	private boolean anyShown() {
		for (Waypoint w : waypoints())
			if (w.enabled)
				return true;
		return false;
	}

	private boolean isCurrentDimension() {
		Minecraft mc = Minecraft.getMinecraft();
		return mc.theWorld != null && viewDimension == mc.theWorld.provider.dimensionId;
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (!button.enabled)
			return;
		if (button.id >= BTN_DIM_FIRST && button.id < BTN_DIM_FIRST + DIM_IDS.length) {
			switchDimension(DIM_IDS[button.id - BTN_DIM_FIRST]);
			return;
		}
		switch (button.id) {
			case BTN_ADD:
				openNewWaypointAtPlayer(Minecraft.getMinecraft(), this, crossDimWaypoints, viewDimension);
				break;
			case BTN_TOGGLE_ALL: {
				boolean show = !anyShown();
				for (Waypoint w : waypoints())
					w.enabled = show;
				save();
				button.displayString = show ? "Hide All" : "Show All";
				break;
			}
			case BTN_DONE:
				this.mc.displayGuiScreen(parent);
				break;
			case BTN_SETTINGS:
				this.mc.displayGuiScreen(new GuiMapSettings(this));
				break;
		}
	}

	private void switchDimension(int dim) {
		if (dim == viewDimension)
			return;
		viewDimension = dim;
		crossDimWaypoints = isCurrentDimension() ? null : WaypointManager.instance.loadWaypointsForDim(dim);
		scroll = 0;
		pendingDelete = null;
		this.buttonList.clear();
		initGui();
	}

	// ----------------------------------------------------------------- layout

	private int visibleRows() {
		return Math.max(1, (listBottom - listTop) / ROW_H);
	}

	private int maxScroll() {
		return Math.max(0, waypoints().size() - visibleRows());
	}

	/** Small buttons of a row, right to left: {x, width} pairs, given the row's actions. */
	private int[] rowButtons(int rowRight, boolean spawn) {
		boolean tp = MapConfig.instance.showTeleportButton;
		int[] widths = spawn ? (tp ? new int[] { 24, 34 } : new int[] { 34 })
				: (tp ? new int[] { 34, 26, 24, 34 } : new int[] { 34, 26, 34 });
		int[] out = new int[widths.length * 2];
		int x = rowRight;
		for (int i = 0; i < widths.length; i++) {
			x -= widths[i];
			out[i * 2] = x;
			out[i * 2 + 1] = widths[i];
			x -= 3;
		}
		return out;
	}

	/** What each small button does, in the same right-to-left order as {@link #rowButtons}. */
	private String[] rowActions(boolean spawn) {
		boolean tp = MapConfig.instance.showTeleportButton;
		if (spawn)
			return tp ? new String[] { "tp", "toggle" } : new String[] { "toggle" };
		return tp ? new String[] { "delete", "edit", "tp", "toggle" } : new String[] { "delete", "edit", "toggle" };
	}

	private int spawnX() {
		if (viewDimension == 1)
			return 100;
		return viewDimension == -1 ? SpawnTracker.instance.getNetherSpawnX() : SpawnTracker.instance.getSpawnX();
	}

	private int spawnY() {
		return viewDimension == 1 ? 49 : SpawnTracker.instance.getSpawnY();
	}

	private int spawnZ() {
		if (viewDimension == 1)
			return 0;
		return viewDimension == -1 ? SpawnTracker.instance.getNetherSpawnZ() : SpawnTracker.instance.getSpawnZ();
	}

	// ------------------------------------------------------------------- draw

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		FontRenderer fr = this.fontRenderer;
		if (pendingDelete != null && System.currentTimeMillis() > pendingDeleteUntil)
			pendingDelete = null;
		scroll = Math.max(0, Math.min(scroll, maxScroll()));

		MapUi.panel(panelX, panelY, panelX + panelW, panelY + panelH);
		fr.drawStringWithShadow("§lWaypoints", panelX + 10, panelY + 10, 0xFFFFFF);
		MapUi.rect(panelX + 6, panelY + HEADER_H - 2, panelX + panelW - 6, panelY + HEADER_H - 1, MapUi.BORDER);

		int rowLeft = panelX + 6, rowRight = panelX + panelW - 6;

		// Pinned spawn row.
		int spawnY = panelY + HEADER_H;
		if (SpawnTracker.instance.hasSpawn()) {
			boolean shown = MapConfig.instance.showSpawnWaypoint;
			MapUi.rect(rowLeft, spawnY + 1, rowRight, spawnY + ROW_H - 1, 0x401F3B66);
			drawRow(fr, mouseX, mouseY, rowLeft, rowRight, spawnY, "Spawn Point", spawnX(), spawnY(), spawnZ(),
					0x3F7FE0, "S", shown, true, null);
		} else {
			fr.drawStringWithShadow("Spawn point not recorded yet", rowLeft + 6, spawnY + 8, MapUi.TEXT_MUTED);
		}
		MapUi.rect(rowLeft, listTop - 3, rowRight, listTop - 2, MapUi.BORDER);

		List<Waypoint> list = waypoints();
		if (list.isEmpty()) {
			String a = isCurrentDimension() ? "No waypoints yet" : "No waypoints in the " + dimName(viewDimension);
			String b = isCurrentDimension() ? "Click + New Waypoint, press N in game, or double-click the map (J)"
					: "Click + New Waypoint to add one at the matching position";
			int cy = (listTop + listBottom) / 2 - 8;
			fr.drawStringWithShadow(a, panelX + (panelW - fr.getStringWidth(a)) / 2, cy, MapUi.TEXT_DIM);
			b = MapUi.fit(fr, b, panelW - 20);
			fr.drawStringWithShadow(b, panelX + (panelW - fr.getStringWidth(b)) / 2, cy + 12, MapUi.TEXT_MUTED);
		} else {
			boolean scrollbar = maxScroll() > 0;
			int right = scrollbar ? rowRight - 8 : rowRight;
			int rows = visibleRows();
			for (int i = 0; i < rows && i + scroll < list.size(); i++) {
				Waypoint wp = list.get(i + scroll);
				int y = listTop + i * ROW_H;
				boolean hover = mouseX >= rowLeft && mouseX < right && mouseY >= y && mouseY < y + ROW_H;
				if (hover)
					MapUi.rect(rowLeft, y, right, y + ROW_H, 0x24FFFFFF);
				else if (((i + scroll) & 1) == 1)
					MapUi.rect(rowLeft, y, right, y + ROW_H, 0x20FFFFFF);
				drawRow(fr, mouseX, mouseY, rowLeft, right, y, wp.name, wp.x, wp.y, wp.z, wp.color,
						MapMarkers.tileLetter(wp.name), wp.enabled, false, wp);
			}
			if (scrollbar) {
				int trackTop = listTop, trackBottom = listTop + rows * ROW_H;
				MapUi.rect(rowRight - 4, trackTop, rowRight - 1, trackBottom, 0x30FFFFFF);
				int total = list.size();
				int thumbH = Math.max(12, (trackBottom - trackTop) * rows / total);
				int thumbY = trackTop + (trackBottom - trackTop - thumbH) * scroll / Math.max(1, maxScroll());
				MapUi.rect(rowRight - 4, thumbY, rowRight - 1, thumbY + thumbH, 0xC0A3A6B2);
			}
		}

		// Footer
		MapUi.rect(rowLeft, listBottom, rowRight, listBottom + 1, MapUi.BORDER);
		String count = list.size() == 1 ? "1 waypoint" : list.size() + " waypoints";
		int countX = panelX + 6 + 110 + 64 + 8;
		int countRight = panelX + panelW - 6 - 50 - 4 - 56 - 6;
		if (countX + fr.getStringWidth(count) <= countRight)
			fr.drawStringWithShadow(count, countX, listBottom + 11, MapUi.TEXT_MUTED);

		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	private void drawRow(FontRenderer fr, int mouseX, int mouseY, int left, int right, int y, String name, int x,
			int wy, int z, int color, String letter, boolean shown, boolean spawn, Waypoint wp) {
		MapUi.tile(fr, left + 6, y + 6, 12, color, letter);
		int[] buttons = rowButtons(right - 4, spawn);
		String[] actions = rowActions(spawn);
		int textRight = buttons[(actions.length - 1) * 2] - 6;

		String distance = "";
		Minecraft mc = Minecraft.getMinecraft();
		if (isCurrentDimension() && mc.thePlayer != null) {
			double dx = x + 0.5 - mc.thePlayer.posX, dz = z + 0.5 - mc.thePlayer.posZ;
			distance = MapUi.distance(Math.sqrt(dx * dx + dz * dz));
			int dw = fr.getStringWidth(distance);
			fr.drawStringWithShadow(distance, textRight - dw, y + 8, MapUi.TEXT_DIM);
			textRight -= dw + 8;
		}

		String title = MapUi.fit(fr, name, textRight - (left + 24));
		fr.drawStringWithShadow(title, left + 24, y + 3, shown ? 0xFFFFFF : 0x80808A);
		String coords = "x " + x + "   y " + wy + "   z " + z;
		fr.drawStringWithShadow(MapUi.fit(fr, coords, textRight - (left + 24)), left + 24, y + 13, MapUi.TEXT_MUTED);

		for (int i = 0; i < actions.length; i++) {
			int bx = buttons[i * 2], bw = buttons[i * 2 + 1];
			int byTop = y + (ROW_H - SMALL_H) / 2;
			boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= byTop && mouseY < byTop + SMALL_H;
			String action = actions[i];
			String label;
			int style = MapUi.STYLE_NORMAL;
			if (action.equals("toggle")) {
				label = shown ? "Hide" : "Show";
			} else if (action.equals("tp")) {
				label = "TP";
			} else if (action.equals("edit")) {
				label = "Edit";
			} else {
				boolean confirm = wp != null && wp == pendingDelete;
				label = confirm ? "Sure?" : "Del";
				style = MapUi.STYLE_DANGER;
			}
			MapUi.button(fr, bx, byTop, bw, SMALL_H, label, null, style, false, hover, true);
		}
	}

	private static String dimName(int dim) {
		return dim == -1 ? "Nether" : (dim == 1 ? "End" : "Overworld");
	}

	// ------------------------------------------------------------------ input

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
		super.mouseClicked(mouseX, mouseY, mouseButton);
		if (mouseButton != 0 || this.mc.currentScreen != this)
			return;
		int rowLeft = panelX + 6, rowRight = panelX + panelW - 6;

		int spawnRowY = panelY + HEADER_H;
		if (SpawnTracker.instance.hasSpawn() && mouseY >= spawnRowY && mouseY < spawnRowY + ROW_H) {
			String action = actionAt(mouseX, mouseY, rowRight - 4, spawnRowY, true);
			if ("toggle".equals(action)) {
				MapConfig.instance.showSpawnWaypoint = !MapConfig.instance.showSpawnWaypoint;
				MapConfig.instance.saveConfig();
			} else if ("tp".equals(action)) {
				Teleport.to(this.mc, spawnX(), spawnY() + 2, spawnZ(), viewDimension, "Spawn Point");
			}
			return;
		}

		List<Waypoint> list = waypoints();
		boolean scrollbar = maxScroll() > 0;
		int right = scrollbar ? rowRight - 8 : rowRight;
		if (mouseX < rowLeft || mouseX >= right || mouseY < listTop)
			return;
		int index = (mouseY - listTop) / ROW_H;
		if (index >= visibleRows() || index + scroll >= list.size())
			return;
		Waypoint wp = list.get(index + scroll);
		int y = listTop + index * ROW_H;
		String action = actionAt(mouseX, mouseY, right - 4, y, false);
		if (action == null) {
			this.mc.displayGuiScreen(new GuiWaypointEdit(this, wp, false, viewDimension, crossDimWaypoints));
		} else if (action.equals("toggle")) {
			wp.enabled = !wp.enabled;
			save();
		} else if (action.equals("edit")) {
			this.mc.displayGuiScreen(new GuiWaypointEdit(this, wp, false, viewDimension, crossDimWaypoints));
		} else if (action.equals("tp")) {
			Teleport.to(this.mc, wp.x, wp.y + 2, wp.z, viewDimension, wp.name);
		} else if (action.equals("delete")) {
			if (pendingDelete == wp) {
				list.remove(wp);
				pendingDelete = null;
				save();
			} else {
				pendingDelete = wp;
				pendingDeleteUntil = System.currentTimeMillis() + CONFIRM_MS;
			}
		}
	}

	private String actionAt(int mouseX, int mouseY, int rowRight, int rowY, boolean spawn) {
		int[] buttons = rowButtons(rowRight, spawn);
		String[] actions = rowActions(spawn);
		int top = rowY + (ROW_H - SMALL_H) / 2;
		if (mouseY < top || mouseY >= top + SMALL_H)
			return mouseX >= buttons[(actions.length - 1) * 2] ? "" : null;
		for (int i = 0; i < actions.length; i++) {
			if (mouseX >= buttons[i * 2] && mouseX < buttons[i * 2] + buttons[i * 2 + 1])
				return actions[i];
		}
		// Between the buttons: do nothing rather than open the editor by accident.
		return mouseX >= buttons[(actions.length - 1) * 2] ? "" : null;
	}

	@Override
	public void handleMouseInput() {
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if (wheel != 0)
			scroll = Math.max(0, Math.min(maxScroll(), scroll + (wheel > 0 ? -1 : 1)));
	}

	@Override
	protected void keyTyped(char c, int keyCode) {
		if (keyCode == Keyboard.KEY_ESCAPE || keyCode == MapConfig.waypointGuiKey.keyCode) {
			this.mc.displayGuiScreen(parent);
		} else if (keyCode == Keyboard.KEY_UP) {
			scroll = Math.max(0, scroll - 1);
		} else if (keyCode == Keyboard.KEY_DOWN) {
			scroll = Math.min(maxScroll(), scroll + 1);
		}
	}

	private static int nextColor(int index) {
		int[] colors = { 0xFF5555, 0x55FF55, 0x5599FF, 0xFFE14D, 0xE05CE0, 0x4DD2FF, 0xFF9933, 0x9966FF };
		return colors[index % colors.length];
	}

	@Override
	public boolean doesGuiPauseGame() {
		return true;
	}
}
