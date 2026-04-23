package net.fabricmc.example.minimap;

import net.minecraft.src.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.nio.IntBuffer;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL12;

/**
 * JourneyMap-style fullscreen map GUI.
 * Features: pan by dragging, scroll-wheel zoom, waypoints, entities,
 * coordinates, compass.
 */
public class GuiFullscreenMap extends GuiScreen {
	// View state
	private double viewCenterX;
	private double viewCenterZ;
	private boolean initialized = false;

	// Texture state
	private int mapTextureId = -1;
	private IntBuffer mapBuffer = null;
	private int[] mapPixels = null;

	// Drag state
	private boolean dragging = false;
	private int dragStartX, dragStartY;
	private double dragStartCenterX, dragStartCenterZ;

	// Cache
	private int[] mapColorCache;
	private int[] mapHeightCache;
	private int cacheW, cacheH;
	private double cachedCenterX = Double.MIN_VALUE;
	private double cachedCenterZ = Double.MIN_VALUE;
	private int cachedZoom = Integer.MIN_VALUE;
	private int cachedViewDim = Integer.MIN_VALUE;

	// Cross-dimension viewing
	private int viewDimension = Integer.MIN_VALUE; // current player dimension by default
	private java.util.HashMap<Long, int[]> crossDimTiles = null;
	private java.util.List<Waypoint> crossDimWaypoints = null;

	// Layout
	private static final int TOP_BAR_HEIGHT = 26;
	private static final int BOTTOM_BAR_HEIGHT = 18;

	@SuppressWarnings("unchecked")
	@Override
	public void initGui() {
		Keyboard.enableRepeatEvents(true);

		Minecraft mc = Minecraft.getMinecraft();
		if (!initialized && mc.thePlayer != null) {
			viewCenterX = mc.thePlayer.posX;
			viewCenterZ = mc.thePlayer.posZ;
			initialized = true;
		}
		if (viewDimension == Integer.MIN_VALUE && mc.theWorld != null) {
			viewDimension = mc.theWorld.provider.dimensionId;
		}

		// Top bar buttons
		int btnW = 80;
		int btnH = 20;
		int gap = 4;
		int startX = gap;
		int btnY = 3;

		this.buttonList.add(new GuiButton(0, startX, btnY, btnW, btnH, "Waypoints"));
		this.buttonList.add(new GuiButton(1, startX + btnW + gap, btnY, btnW, btnH, "Settings"));

		// Close button on the right
		this.buttonList.add(new GuiButton(2, this.width - btnW - gap, btnY, btnW, btnH, "Close"));

		invalidateCache();
	}

	@Override
	public void onGuiClosed() {
		Keyboard.enableRepeatEvents(false);
		// Save the fullscreen zoom level
		MapConfig.instance.saveConfig();

		// Clean up texture
		if (mapTextureId != -1) {
			GL11.glDeleteTextures(mapTextureId);
			mapTextureId = -1;
		}
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (!button.enabled)
			return;
		switch (button.id) {
			case 0: // Waypoints
				this.mc.displayGuiScreen(new GuiWaypoints());
				break;
			case 1: // Settings
				this.mc.displayGuiScreen(new GuiMapSettings(this));
				break;
			case 2: // Close
				this.mc.displayGuiScreen(null);
				break;
		}
	}

	private void switchDimension(int dim) {
		if (dim == viewDimension)
			return;
		viewDimension = dim;
		invalidateCache();
		int currentDim = mc.theWorld != null ? mc.theWorld.provider.dimensionId : 0;
		if (dim != currentDim) {
			crossDimTiles = MapTileManager.instance.loadTilesForDim(dim);
			crossDimWaypoints = WaypointManager.instance.loadWaypointsForDim(dim);
		} else {
			crossDimTiles = null;
			crossDimWaypoints = null;
		}
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.theWorld == null || mc.thePlayer == null) {
			this.mc.displayGuiScreen(null);
			return;
		}

		World world = mc.theWorld;
		EntityPlayer player = mc.thePlayer;

		// Lazy-init texture sampling
		if (!TextureColorSampler.isInitialized()) {
			TextureColorSampler.init();
		}

		double zoomFactor = Math.pow(2.0, -MapConfig.instance.fullscreenZoom);

		int mapTop = TOP_BAR_HEIGHT;
		int mapBottom = this.height - BOTTOM_BAR_HEIGHT;
		int mapLeft = 0;
		int mapRight = this.width;
		int mapW = mapRight - mapLeft;
		int mapH = mapBottom - mapTop;

		// ---- Render map terrain ----
		GL11.glPushMatrix();
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// Dark background for the whole screen
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(0.08f, 0.08f, 0.10f, 1.0f);
		drawQuad(0, 0, this.width, this.height);

		// Full quality: we always use pixelStep 1 with the texture method for maximum sharpness.
		// Performance is maintained because we only upload once when the view changes.
		int pixelStep = 1;

		// Check if cache needs rebuild
		int playerBlockX = (int) Math.floor(viewCenterX);
		int playerBlockZ = (int) Math.floor(viewCenterZ);
		boolean cacheValid = (mapColorCache != null && mapHeightCache != null
				&& cacheW == mapW && cacheH == mapH
				&& cachedCenterX == playerBlockX
				&& cachedCenterZ == playerBlockZ
				&& cachedZoom == MapConfig.instance.fullscreenZoom
				&& cachedViewDim == viewDimension);

		if (!cacheValid) {
			if (mapColorCache == null || cacheW != mapW || cacheH != mapH) {
				mapColorCache = new int[mapW * mapH];
				mapHeightCache = new int[mapW * mapH];
				cacheW = mapW;
				cacheH = mapH;
			}

			int halfW = mapW / 2;
			int halfH = mapH / 2;

			// First pass: sample colors and heights (use cached tiles when available)
			for (int py = 0; py < mapH; py += pixelStep) {
				for (int px = 0; px < mapW; px += pixelStep) {
					int worldX = (int) Math.floor(viewCenterX + (px - halfW) / zoomFactor);
					int worldZ = (int) Math.floor(viewCenterZ + (py - halfH) / zoomFactor);

					int cachedData = 0;
					if (crossDimTiles != null) {
						cachedData = MapTileManager.getBlockColorFromTiles(crossDimTiles, worldX, worldZ);
						// Note: crossDimTiles currently don't store height, so it will fall back to 64
					} else {
						// Standard dimension lookup with height support
						int cx = Math.floorDiv(worldX, 16);
						int cz = Math.floorDiv(worldZ, 16);
						int[] colors = MapTileManager.instance.getChunkColors(cx, cz);
						if (colors != null) {
							cachedData = colors[Math.floorMod(worldZ, 16) * 16 + Math.floorMod(worldX, 16)];
						}
					}

					if (cachedData != 0) {
						int color = cachedData & 0xFFFFFF;
						int height = (cachedData >> 24) & 0xFF;
						if (height == 0)
							height = 64; // Fallback for old cache files

						mapColorCache[py * mapW + px] = color;
						mapHeightCache[py * mapW + px] = height;
					} else if (crossDimTiles == null) {
						// Fall back to live sampling only for current dimension
						long packed = MinimapRenderer.getSurfaceInfo(world, worldX, worldZ, player);
						int color = (int) (packed >> 32);
						int height = (int) (packed & 0xFFFFFFFFL);
						mapColorCache[py * mapW + px] = color;
						mapHeightCache[py * mapW + px] = height;
					}
				}
			}

			// Second pass: height shading
			for (int py = pixelStep; py < mapH; py += pixelStep) {
				for (int px = 0; px < mapW; px += pixelStep) {
					int idx = py * mapW + px;
					int idxAbove = (py - pixelStep) * mapW + px;

					int color = mapColorCache[idx];
					if (color == 0)
						continue;

					int h = mapHeightCache[idx];
					int hAbove = mapHeightCache[idxAbove];

					if (hAbove > 0) {
						if (h > hAbove) {
							color = brighten(color, 1.15f);
						} else if (h < hAbove) {
							color = darken(color, 0.85f);
						}
						mapColorCache[idx] = color;
					}
				}
			}

			cachedCenterX = playerBlockX;
			cachedCenterZ = playerBlockZ;
			cachedZoom = MapConfig.instance.fullscreenZoom;
			cachedViewDim = viewDimension;

			// Update texture
			if (mapTextureId == -1) {
				mapTextureId = GL11.glGenTextures();
			}

			if (mapBuffer == null || mapBuffer.capacity() < mapW * mapH) {
				mapBuffer = BufferUtils.createIntBuffer(mapW * mapH);
				mapPixels = new int[mapW * mapH];
			}

			// Convert mapColorCache to AARRGGBB for OpenGL
			// Use height cache to distinguish "no data" from "valid black block":
			// height > 0 means real terrain was sampled, even if color is 0x000000
			for (int i = 0; i < mapW * mapH; i++) {
				int color = mapColorCache[i];
				int height = mapHeightCache[i];
				if (color != 0 || height > 0) {
					mapPixels[i] = 0xFF000000 | color; // Fully opaque (real terrain)
				} else {
					mapPixels[i] = 0; // Truly no data - transparent
				}
			}

			mapBuffer.clear();
			mapBuffer.put(mapPixels);
			mapBuffer.flip();

			GL11.glBindTexture(GL11.GL_TEXTURE_2D, mapTextureId);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, mapW, mapH, 0, GL12.GL_BGRA,
					GL12.GL_UNSIGNED_INT_8_8_8_8_REV, mapBuffer);
		}

		// Render cached map as a single texture (high performance)
		if (mapTextureId != -1) {
			GL11.glEnable(GL11.GL_TEXTURE_2D);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, mapTextureId);
			GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

			Tessellator t = Tessellator.instance;
			t.startDrawingQuads();
			t.addVertexWithUV(mapLeft, mapBottom, 0.0, 0.0, 1.0);
			t.addVertexWithUV(mapRight, mapBottom, 0.0, 1.0, 1.0);
			t.addVertexWithUV(mapRight, mapTop, 0.0, 1.0, 0.0);
			t.addVertexWithUV(mapLeft, mapTop, 0.0, 0.0, 0.0);
			t.draw();
			GL11.glDisable(GL11.GL_TEXTURE_2D);
		}

		// ---- Chunk grid ----
		if (MapConfig.instance.showChunkGrid) {
			renderChunkGrid(mapLeft, mapTop, mapW, mapH, zoomFactor);
		}

		// ---- Spawn marker ----
		if (MapConfig.instance.showSpawnWaypoint && SpawnTracker.instance.hasSpawn()) {
			int spawnX, spawnZ;
			if (viewDimension == 1) {
				spawnX = 100;
				spawnZ = 0;
			} else if (viewDimension == -1) {
				spawnX = SpawnTracker.instance.getNetherSpawnX();
				spawnZ = SpawnTracker.instance.getNetherSpawnZ();
			} else {
				spawnX = SpawnTracker.instance.getSpawnX();
				spawnZ = SpawnTracker.instance.getSpawnZ();
			}
			renderMapMarker(spawnX, spawnZ, mapLeft, mapTop, mapW, mapH, zoomFactor, 0, 102, 204, "Spawn", mouseX,
					mouseY, false);
			renderMapMarker(spawnX, spawnZ, mapLeft, mapTop, mapW, mapH, zoomFactor, 0, 102, 204, "Spawn", mouseX,
					mouseY, true);
		}

		// ---- Waypoints ----
		if (MapConfig.instance.showWaypoints) {
			List<Waypoint> waypoints;
			if (crossDimWaypoints != null) {
				waypoints = crossDimWaypoints;
			} else {
				waypoints = WaypointManager.instance.getWaypoints();
			}

			// Pass 1: Draw all markers
			for (Waypoint wp : waypoints) {
				if (!wp.enabled)
					continue;
				renderMapMarker(wp.x, wp.z, mapLeft, mapTop, mapW, mapH, zoomFactor,
						wp.getRed(), wp.getGreen(), wp.getBlue(), wp.name, mouseX, mouseY, false);
			}

			// Pass 2: Draw all labels ON TOP of all markers
			for (Waypoint wp : waypoints) {
				if (!wp.enabled)
					continue;
				renderMapMarker(wp.x, wp.z, mapLeft, mapTop, mapW, mapH, zoomFactor,
						wp.getRed(), wp.getGreen(), wp.getBlue(), wp.name, mouseX, mouseY, true);
			}
		}

		// ---- Player arrow (Interpolated for smooth movement and rotation) ----
		double interpPlayerX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
		double interpPlayerZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
		float interpPlayerYaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;

		int playerScreenX = mapLeft + mapW / 2 + (int) ((interpPlayerX - viewCenterX) * zoomFactor);
		int playerScreenZ = mapTop + mapH / 2 + (int) ((interpPlayerZ - viewCenterZ) * zoomFactor);
		if (playerScreenX >= mapLeft && playerScreenX < mapRight
				&& playerScreenZ >= mapTop && playerScreenZ < mapBottom) {
			drawPlayerArrow(playerScreenX, playerScreenZ, interpPlayerYaw);
		}

		// ---- Compass labels ----
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		FontRenderer fr = mc.fontRenderer;
		if (fr != null && MapConfig.instance.showCompass) {
			int cx = this.width / 2;
			drawStringOutlined(fr, "N", cx - fr.getStringWidth("N") / 2, mapTop + 4, 0xFFFFFF, 0x000000);
			drawStringOutlined(fr, "S", cx - fr.getStringWidth("S") / 2, mapBottom - 14, 0xFFFFFF, 0x000000);
			drawStringOutlined(fr, "W", mapLeft + 4, mapTop + mapH / 2 - 4, 0xFFFFFF, 0x000000);
			drawStringOutlined(fr, "E", mapRight - fr.getStringWidth("E") - 4, mapTop + mapH / 2 - 4, 0xFFFFFF,
					0x000000);
		}

		// ---- Top bar ----
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(0.05f, 0.05f, 0.07f, 0.92f);
		drawQuad(0, 0, this.width, TOP_BAR_HEIGHT);
		// Bottom border line
		GL11.glColor4f(0.3f, 0.3f, 0.35f, 0.8f);
		drawQuad(0, TOP_BAR_HEIGHT - 1, this.width, TOP_BAR_HEIGHT);

		// ---- Dimension tabs (custom drawn) ----
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		if (fr != null) {
			String[] dimNames = { "Overworld", "Nether", "End" };
			int[] dimIds = { 0, -1, 1 };
			int tabGap = 16;
			int totalTabW = 0;
			for (String n : dimNames)
				totalTabW += fr.getStringWidth(n);
			totalTabW += tabGap * (dimNames.length - 1);
			int tabX = (this.width - totalTabW) / 2;
			int tabY = (TOP_BAR_HEIGHT - 8) / 2;
			for (int i = 0; i < dimNames.length; i++) {
				boolean active = (viewDimension == dimIds[i]);
				int tw = fr.getStringWidth(dimNames[i]);
				int color = active ? 0xFFFFFF : 0x888888;
				fr.drawStringWithShadow(dimNames[i], tabX, tabY, color);
				if (active) {
					GL11.glDisable(GL11.GL_TEXTURE_2D);
					GL11.glColor4f(0.4f, 0.6f, 1.0f, 1.0f);
					drawQuad(tabX - 1, tabY + 10, tabX + tw + 1, tabY + 12);
					GL11.glEnable(GL11.GL_TEXTURE_2D);
				}
				tabX += tw + tabGap;
			}
		}
		GL11.glDisable(GL11.GL_TEXTURE_2D);

		// ---- Bottom bar ----
		GL11.glColor4f(0.05f, 0.05f, 0.07f, 0.92f);
		drawQuad(0, this.height - BOTTOM_BAR_HEIGHT, this.width, this.height);
		// Top border line
		GL11.glColor4f(0.3f, 0.3f, 0.35f, 0.8f);
		drawQuad(0, this.height - BOTTOM_BAR_HEIGHT, this.width, this.height - BOTTOM_BAR_HEIGHT + 1);

		GL11.glEnable(GL11.GL_TEXTURE_2D);

		// Bottom bar info: cursor coordinates + biome
		if (fr != null) {
			int halfW = mapW / 2;
			int halfH = mapH / 2;
			int cursorWorldX = (int) (viewCenterX + (mouseX - mapLeft - halfW) / zoomFactor);
			int cursorWorldZ = (int) (viewCenterZ + (mouseY - mapTop - halfH) / zoomFactor);

			String cursorCoords = String.format("Cursor: x: %d, z: %d", cursorWorldX, cursorWorldZ);
			BiomeGenBase biome = world.getBiomeGenForCoords(cursorWorldX, cursorWorldZ);
			String biomeName = biome != null ? biome.biomeName : "Unknown";
			String playerCoords = String.format("Player: x: %d, y: %d, z: %d",
					(int) player.posX, (int) player.posY, (int) player.posZ);

			int bottomTextY = this.height - BOTTOM_BAR_HEIGHT + 5;
			fr.drawStringWithShadow(cursorCoords, 6, bottomTextY, 0xCCCCCC);
			fr.drawStringWithShadow("\u00a77" + biomeName, 6 + fr.getStringWidth(cursorCoords) + 12, bottomTextY,
					0x999999);

			// Player coords on the right
			fr.drawStringWithShadow(playerCoords, this.width - fr.getStringWidth(playerCoords) - 6, bottomTextY,
					0xCCCCCC);

			// Zoom level in center
			String zoomStr = "Zoom: " + MapConfig.instance.fullscreenZoom;
			fr.drawStringWithShadow(zoomStr, this.width / 2 - fr.getStringWidth(zoomStr) / 2, bottomTextY, 0xAAAAFF);
		}

		GL11.glDisable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
		GL11.glPopMatrix();

		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	// ---- Rendering helpers ----

	private void renderChunkGrid(int mapLeft, int mapTop, int mapW, int mapH, double zoomFactor) {
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(1f, 1f, 1f, 0.12f);

		int chunkPixels = (int) (16 * zoomFactor);
		if (chunkPixels < 4)
			return; // Too zoomed out for grid

		int halfW = mapW / 2;
		int halfH = mapH / 2;

		// Offset so grid lines align with actual chunk boundaries
		double offsetX = (viewCenterX % 16) * zoomFactor;
		double offsetZ = (viewCenterZ % 16) * zoomFactor;

		Tessellator t = Tessellator.instance;
		t.startDrawingQuads();
		t.setColorRGBA(255, 255, 255, 25);

		// Vertical lines
		for (int gx = (int) (-offsetX + halfW % chunkPixels); gx < mapW; gx += chunkPixels) {
			if (gx < 0)
				continue;
			t.addVertex(mapLeft + gx, mapTop, 0);
			t.addVertex(mapLeft + gx + 1, mapTop, 0);
			t.addVertex(mapLeft + gx + 1, mapTop + mapH, 0);
			t.addVertex(mapLeft + gx, mapTop + mapH, 0);
		}

		// Horizontal lines
		for (int gz = (int) (-offsetZ + halfH % chunkPixels); gz < mapH; gz += chunkPixels) {
			if (gz < 0)
				continue;
			t.addVertex(mapLeft, mapTop + gz, 0);
			t.addVertex(mapLeft + mapW, mapTop + gz, 0);
			t.addVertex(mapLeft + mapW, mapTop + gz + 1, 0);
			t.addVertex(mapLeft, mapTop + gz + 1, 0);
		}
		t.draw();
	}

	private void renderMapMarker(int worldX, int worldZ, int mapLeft, int mapTop,
			int mapW, int mapH, double zoomFactor, int r, int g, int b, String label, int mouseX, int mouseY,
			boolean drawLabelStep) {
		int halfW = mapW / 2;
		int halfH = mapH / 2;

		int screenX = mapLeft + halfW + (int) ((worldX + 0.5 - viewCenterX) * zoomFactor);
		int screenZ = mapTop + halfH + (int) ((worldZ + 0.5 - viewCenterZ) * zoomFactor);

		// Check if on screen
		if (screenX < mapLeft - 10 || screenX > mapLeft + mapW + 10
				|| screenZ < mapTop - 10 || screenZ > mapTop + mapH + 10) {
			return;
		}

		if (!drawLabelStep) {
			GL11.glDisable(GL11.GL_TEXTURE_2D);
			drawDiamond(screenX, screenZ, 5, r, g, b);
		} else {
			// Only show label if mouse is close (e.g. within 15 pixels) and not holding
			// right click
			double distSq = (mouseX - screenX) * (mouseX - screenX) + (mouseY - screenZ) * (mouseY - screenZ);
			boolean isHovered = distSq < 225; // 15^2
			boolean hideLabels = Mouse.isButtonDown(1);

			FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
			if (fr != null && label != null && isHovered && !hideLabels) {
				GL11.glEnable(GL11.GL_TEXTURE_2D);
				int textW = fr.getStringWidth(label);
				// Background box
				GL11.glDisable(GL11.GL_TEXTURE_2D);
				GL11.glColor4f(0.0f, 0.0f, 0.0f, 0.65f);
				drawQuad(screenX - textW / 2 - 2, screenZ - 16, screenX + textW / 2 + 2, screenZ - 6);
				GL11.glEnable(GL11.GL_TEXTURE_2D);

				int labelColor = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
				fr.drawStringWithShadow(label, screenX - textW / 2, screenZ - 15, labelColor);
			}
		}
	}

	private void drawPlayerArrow(int centerX, int centerY, float yaw) {
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_CULL_FACE);

		double angle = Math.toRadians(yaw + 90);
		double sz = 8.0;

		// Slim pointed arrow shape
		double tipX = centerX + Math.cos(angle) * sz;
		double tipY = centerY + Math.sin(angle) * sz;
		double wingLX = centerX + Math.cos(angle + Math.PI * 0.82) * (sz * 0.6);
		double wingLY = centerY + Math.sin(angle + Math.PI * 0.82) * (sz * 0.6);
		double wingRX = centerX + Math.cos(angle - Math.PI * 0.82) * (sz * 0.6);
		double wingRY = centerY + Math.sin(angle - Math.PI * 0.82) * (sz * 0.6);
		double tailX = centerX + Math.cos(angle + Math.PI) * (sz * 0.2);
		double tailY = centerY + Math.sin(angle + Math.PI) * (sz * 0.2);

		Tessellator t = Tessellator.instance;

		// 1. Drop shadow
		t.startDrawing(GL11.GL_TRIANGLE_FAN);
		t.setColorRGBA(0, 0, 0, 100);
		t.addVertex(tipX + 1, tipY + 1, 0.0);
		t.addVertex(wingLX + 1, wingLY + 1, 0.0);
		t.addVertex(tailX + 1, tailY + 1, 0.0);
		t.addVertex(wingRX + 1, wingRY + 1, 0.0);
		t.draw();

		// 2. Black outline
		GL11.glLineWidth(2.0f);
		t.startDrawing(GL11.GL_LINE_LOOP);
		t.setColorOpaque_F(0.0f, 0.0f, 0.0f);
		t.addVertex(tipX, tipY, 0.0);
		t.addVertex(wingLX, wingLY, 0.0);
		t.addVertex(tailX, tailY, 0.0);
		t.addVertex(wingRX, wingRY, 0.0);
		t.draw();

		// 3. Deep Gold fill
		t.startDrawing(GL11.GL_TRIANGLE_FAN);
		t.setColorOpaque_F(0.65f, 0.45f, 0.05f);
		t.addVertex(tipX, tipY, 0.0);
		t.addVertex(wingLX, wingLY, 0.0);
		t.addVertex(tailX, tailY, 0.0);
		t.addVertex(wingRX, wingRY, 0.0);
		t.draw();

		// 4. Bright gold inner highlight
		double hs = 0.6;
		double hTipX = centerX + Math.cos(angle) * (sz * hs);
		double hTipY = centerY + Math.sin(angle) * (sz * hs);
		double hWingLX = centerX + Math.cos(angle + Math.PI * 0.82) * (sz * 0.38);
		double hWingLY = centerY + Math.sin(angle + Math.PI * 0.82) * (sz * 0.38);
		double hWingRX = centerX + Math.cos(angle - Math.PI * 0.82) * (sz * 0.38);
		double hWingRY = centerY + Math.sin(angle - Math.PI * 0.82) * (sz * 0.38);
		double hTailX = centerX + Math.cos(angle + Math.PI) * (sz * 0.08);
		double hTailY = centerY + Math.sin(angle + Math.PI) * (sz * 0.08);

		t.startDrawing(GL11.GL_TRIANGLE_FAN);
		t.setColorOpaque_F(1.0f, 0.85f, 0.2f);
		t.addVertex(hTipX, hTipY, 0.0);
		t.addVertex(hWingLX, hWingLY, 0.0);
		t.addVertex(hTailX, hTailY, 0.0);
		t.addVertex(hWingRX, hWingRY, 0.0);
		t.draw();

		GL11.glLineWidth(1.0f);
		GL11.glEnable(GL11.GL_CULL_FACE);
	}

	private void drawDiamond(int cx, int cy, int size, int r, int g, int b) {
		Tessellator t = Tessellator.instance;
		float rf = r / 255.0f, gf = g / 255.0f, bf = b / 255.0f;

		GL11.glDisable(GL11.GL_CULL_FACE);

		// Half size minimalist marker
		int s = Math.max(1, size / 2);
		int outline = s + 1;

		// 1. Black outline (1px thick square border)
		t.startDrawing(GL11.GL_QUADS);
		t.setColorOpaque_F(0f, 0f, 0f);
		t.addVertex(cx - outline, cy - outline, 0.0);
		t.addVertex(cx + outline, cy - outline, 0.0);
		t.addVertex(cx + outline, cy + outline, 0.0);
		t.addVertex(cx - outline, cy + outline, 0.0);
		t.draw();

		// 2. Colored inner square
		t.startDrawing(GL11.GL_QUADS);
		t.setColorOpaque_F(rf, gf, bf);
		t.addVertex(cx - s, cy - s, 0.0);
		t.addVertex(cx + s, cy - s, 0.0);
		t.addVertex(cx + s, cy + s, 0.0);
		t.addVertex(cx - s, cy + s, 0.0);
		t.draw();

		// 3. Bright contrast dot in center for visibility
		if (s > 1) {
			t.startDrawing(GL11.GL_QUADS);
			t.setColorOpaque_F(1f, 1f, 1f); // White dot
			t.addVertex(cx - 1, cy - 1, 0.0);
			t.addVertex(cx + 1, cy - 1, 0.0);
			t.addVertex(cx + 1, cy + 1, 0.0);
			t.addVertex(cx - 1, cy + 1, 0.0);
			t.draw();
		}

		GL11.glEnable(GL11.GL_CULL_FACE);
	}

	private void drawQuad(int x1, int y1, int x2, int y2) {
		Tessellator t = Tessellator.instance;
		t.startDrawingQuads();
		t.addVertex(x1, y2, 0.0);
		t.addVertex(x2, y2, 0.0);
		t.addVertex(x2, y1, 0.0);
		t.addVertex(x1, y1, 0.0);
		t.draw();
	}

	private void drawStringOutlined(FontRenderer fr, String text, int x, int y, int color, int outline) {
		fr.drawString(text, x + 1, y, outline);
		fr.drawString(text, x - 1, y, outline);
		fr.drawString(text, x, y + 1, outline);
		fr.drawString(text, x, y - 1, outline);
		fr.drawString(text, x, y, color);
	}

	private int brighten(int color, float factor) {
		int r = Math.min((int) (((color >> 16) & 0xFF) * factor), 255);
		int g = Math.min((int) (((color >> 8) & 0xFF) * factor), 255);
		int b = Math.min((int) ((color & 0xFF) * factor), 255);
		return (r << 16) | (g << 8) | b;
	}

	private int darken(int color, float factor) {
		int r = (int) (((color >> 16) & 0xFF) * factor);
		int g = (int) (((color >> 8) & 0xFF) * factor);
		int b = (int) ((color & 0xFF) * factor);
		return (r << 16) | (g << 8) | b;
	}

	// ---- Input handling ----

	@Override
	protected void keyTyped(char c, int keyCode) {
		if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_J) {
			this.mc.displayGuiScreen(null);
			return;
		}

		// Arrow key panning
		double panAmount = 32.0 / Math.pow(2.0, -MapConfig.instance.fullscreenZoom);
		if (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_A) {
			viewCenterX -= panAmount;
			invalidateCache();
		} else if (keyCode == Keyboard.KEY_RIGHT || keyCode == Keyboard.KEY_D) {
			viewCenterX += panAmount;
			invalidateCache();
		} else if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_W) {
			viewCenterZ -= panAmount;
			invalidateCache();
		} else if (keyCode == Keyboard.KEY_DOWN || keyCode == Keyboard.KEY_S) {
			viewCenterZ += panAmount;
			invalidateCache();
		}

		// Re-center on player
		if (keyCode == Keyboard.KEY_C) {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.thePlayer != null) {
				viewCenterX = mc.thePlayer.posX;
				viewCenterZ = mc.thePlayer.posZ;
				invalidateCache();
			}
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
		super.mouseClicked(mouseX, mouseY, mouseButton);
		if (mouseButton != 0)
			return;

		// Check dimension tab clicks in top bar
		Minecraft mcInst = Minecraft.getMinecraft();
		FontRenderer fr2 = mcInst.fontRenderer;
		if (fr2 != null && mouseY < TOP_BAR_HEIGHT) {
			String[] dimNames = { "Overworld", "Nether", "End" };
			int[] dimIds = { 0, -1, 1 };
			int tabGap = 16;
			int totalTabW = 0;
			for (String n : dimNames)
				totalTabW += fr2.getStringWidth(n);
			totalTabW += tabGap * (dimNames.length - 1);
			int tabX = (this.width - totalTabW) / 2;
			for (int i = 0; i < dimNames.length; i++) {
				int tw = fr2.getStringWidth(dimNames[i]);
				if (mouseX >= tabX - 2 && mouseX < tabX + tw + 2) {
					switchDimension(dimIds[i]);
					return;
				}
				tabX += tw + tabGap;
			}
		}

		// Map drag
		if (mouseY >= TOP_BAR_HEIGHT && mouseY < this.height - BOTTOM_BAR_HEIGHT) {
			dragging = true;
			dragStartX = mouseX;
			dragStartY = mouseY;
			dragStartCenterX = viewCenterX;
			dragStartCenterZ = viewCenterZ;
		}
	}

	@Override
	protected void mouseMovedOrUp(int mouseX, int mouseY, int mouseButton) {
		super.mouseMovedOrUp(mouseX, mouseY, mouseButton);
		if (mouseButton == 0) {
			dragging = false;
		}
	}

	@Override
	public void handleMouseInput() {
		super.handleMouseInput();

		// Mouse drag panning
		if (dragging && Mouse.isButtonDown(0)) {
			int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
			int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
			double zoomFactor = Math.pow(2.0, -MapConfig.instance.fullscreenZoom);

			double dx = (mouseX - dragStartX) / zoomFactor;
			double dz = (mouseY - dragStartY) / zoomFactor;

			viewCenterX = dragStartCenterX - dx;
			viewCenterZ = dragStartCenterZ - dz;
			invalidateCache();
		}

		// Scroll wheel zoom
		int scroll = Mouse.getDWheel();
		if (scroll != 0) {
			if (scroll > 0) {
				MapConfig.instance.fullscreenZoom = Math.max(MapConfig.instance.fullscreenZoom - 1, -4);
			} else {
				MapConfig.instance.fullscreenZoom = Math.min(MapConfig.instance.fullscreenZoom + 1, 4);
			}
			invalidateCache();
		}
	}

	private void invalidateCache() {
		cachedCenterX = Double.MIN_VALUE;
		cachedCenterZ = Double.MIN_VALUE;
		cachedZoom = Integer.MIN_VALUE;
		cachedViewDim = Integer.MIN_VALUE;
		mapColorCache = null;
		mapHeightCache = null;
	}

	@Override
	public boolean doesGuiPauseGame() {
		return MapConfig.instance.fullscreenMapPausesGame;
	}
}
