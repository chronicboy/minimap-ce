package net.fabricmc.example.minimap;

import net.minecraft.src.*;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class MinimapRenderer {
	private int[] colorCache;
	private int[] heightCache;
	private int cacheSize = 0;
	private int cachedPlayerX = Integer.MIN_VALUE;
	private int cachedPlayerZ = Integer.MIN_VALUE;
	private int cachedZoomLevel = -999;
	private int cachedMapSize = -1;

	public void renderMinimap(World world, EntityPlayer player, int screenWidth, int screenHeight, float partialTicks) {
		if (world == null || player == null || !MapConfig.instance.minimapEnabled) {
			return;
		}

		// Lazy-init texture sampling (one-time)
		if (!TextureColorSampler.isInitialized()) {
			TextureColorSampler.init();
		}

		int mapSize = MapConfig.instance.minimapSize;
		int mapRadius = mapSize / 2;

		// Calculate position based on config
		int mapX, mapY;
		switch (MapConfig.instance.minimapPosition) {
			case 0: // Top-left
				mapX = 10;
				mapY = 10;
				break;
			case 1: // Top-right
				mapX = screenWidth - mapSize - 10;
				mapY = 10;
				break;
			case 2: // Bottom-left
				mapX = 10;
				mapY = screenHeight - mapSize - 10;
				break;
			case 3: // Bottom-right
				mapX = screenWidth - mapSize - 10;
				mapY = screenHeight - mapSize - 10;
				break;
			default:
				mapX = screenWidth - mapSize - 10;
				mapY = 10;
		}

		// Layout Adjustment: If top-aligned, push map down to make room for time
		if (MapConfig.instance.minimapPosition <= 1) {
			mapY += 20;
		}

		GL11.glPushMatrix();
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// Background (JourneyMap style: Solid dark circle border for map content)
		GL11.glColor4f(0.1f, 0.1f, 0.1f, 0.9f);
		drawCircle(mapX + mapRadius, mapY + mapRadius, mapRadius);

		double zoomFactor = Math.pow(2.0, -MapConfig.instance.zoomLevel);

		GL11.glDisable(GL11.GL_TEXTURE_2D);

		int playerBlockX = (int) Math.floor(player.posX);
		int playerBlockZ = (int) Math.floor(player.posZ);

		// Check if cache needs refresh
		boolean cacheValid = (colorCache != null && heightCache != null
				&& cacheSize == mapSize
				&& cachedPlayerX == playerBlockX
				&& cachedPlayerZ == playerBlockZ
				&& cachedZoomLevel == MapConfig.instance.zoomLevel
				&& cachedMapSize == mapSize);

		int pixelStep = zoomFactor < 0.25 ? 3 : (zoomFactor < 0.5 ? 2 : 1);

		if (!cacheValid) {
			// Rebuild cache
			if (colorCache == null || cacheSize != mapSize) {
				colorCache = new int[mapSize * mapSize];
				heightCache = new int[mapSize * mapSize];
				cacheSize = mapSize;
			}

			// First Pass: Calculate Heights & Base Colors
			for (int z = 0; z < mapSize; z += pixelStep) {
				for (int x = 0; x < mapSize; x += pixelStep) {
					double dxm = x - mapRadius;
					double dzm = z - mapRadius;
					if (dxm * dxm + dzm * dzm > mapRadius * mapRadius) {
						colorCache[z * mapSize + x] = -1; // Outside circle
						heightCache[z * mapSize + x] = -1;
						continue;
					}

					int worldX = (int) (player.posX + (x - mapRadius) / zoomFactor);
					int worldZ = (int) (player.posZ + (z - mapRadius) / zoomFactor);

					// Get color and height
					long packed = getSurfaceInfo(world, worldX, worldZ, player);
					int color = (int) (packed >> 32);
					int height = (int) (packed & 0xFFFFFFFFL);

					colorCache[z * mapSize + x] = color;
					heightCache[z * mapSize + x] = height;
				}
			}

			// Second Pass: Apply Shading using North-South height difference
			for (int z = pixelStep; z < mapSize; z += pixelStep) {
				for (int x = 0; x < mapSize; x += pixelStep) {
					int idx = z * mapSize + x;
					int idxAbove = (z - pixelStep) * mapSize + x;

					int color = colorCache[idx];
					if (color == -1)
						continue;

					int h = heightCache[idx];
					int hAbove = heightCache[idxAbove];

					if (hAbove != -1) {
						if (h > hAbove) {
							// South-facing slope -> Brighter
							color = brighten(color, 1.15f);
						} else if (h < hAbove) {
							// North-facing slope -> Darker
							color = darken(color, 0.85f);
						}
						colorCache[idx] = color;
					}
				}
			}

			cachedPlayerX = playerBlockX;
			cachedPlayerZ = playerBlockZ;
			cachedZoomLevel = MapConfig.instance.zoomLevel;
			cachedMapSize = mapSize;
		}

		// Render from cache
		Tessellator t = Tessellator.instance;
		t.startDrawingQuads();

		for (int z = 0; z < mapSize; z += pixelStep) {
			for (int x = 0; x < mapSize; x += pixelStep) {
				int color = colorCache[z * mapSize + x];
				if (color == -1)
					continue;

				int r = (color >> 16) & 0xFF;
				int g = (color >> 8) & 0xFF;
				int b = color & 0xFF;

				int size = pixelStep;
				t.setColorRGBA(r, g, b, 255);
				t.addVertex(mapX + x, mapY + z + size, 0.0);
				t.addVertex(mapX + x + size, mapY + z + size, 0.0);
				t.addVertex(mapX + x + size, mapY + z, 0.0);
				t.addVertex(mapX + x, mapY + z, 0.0);
			}
		}
		t.draw();

		// Premium minimap frame: beveled ring with shadow depth
		GL11.glDisable(GL11.GL_TEXTURE_2D);

		// Outer shadow glow (subtle dark halo)
		GL11.glLineWidth(6.0f);
		GL11.glColor4f(0.0f, 0.0f, 0.0f, 0.35f);
		drawCircleOutline(mapX + mapRadius, mapY + mapRadius, mapRadius + 3);

		// Thick dark border ring
		GL11.glLineWidth(4.0f);
		GL11.glColor4f(0.15f, 0.15f, 0.18f, 1.0f);
		drawCircleOutline(mapX + mapRadius, mapY + mapRadius, mapRadius + 1);

		// Mid-tone ring
		GL11.glLineWidth(2.0f);
		GL11.glColor4f(0.30f, 0.30f, 0.35f, 1.0f);
		drawCircleOutline(mapX + mapRadius, mapY + mapRadius, mapRadius);

		// Inner bright highlight (bevel effect)
		GL11.glLineWidth(1.0f);
		GL11.glColor4f(0.55f, 0.55f, 0.60f, 0.3f);
		drawCircleOutline(mapX + mapRadius, mapY + mapRadius, mapRadius - 1);

		GL11.glLineWidth(1.0f);

		if (MapConfig.instance.showChunkGrid) {
			renderGrid(mapX, mapY, mapSize, mapRadius, zoomFactor);
		}

		renderSpawnOnMap(world, player, mapX, mapY, mapSize, mapRadius, zoomFactor);

		// Render waypoints on map
		if (MapConfig.instance.showWaypoints) {
			renderWaypointsOnMap(player, mapX, mapY, mapSize, mapRadius, zoomFactor);
		}

		if (MapConfig.instance.showEntities) {
			renderEntitiesOnMap(world, player, mapX, mapY, mapSize, mapRadius, zoomFactor);
		}

		int centerX = mapX + mapRadius;
		int centerY = mapY + mapRadius;

		// Player Arrow (Updated Logic)
		drawPlayerArrow(centerX, centerY, player.rotationYaw);

		if (MapConfig.instance.showCompass) {
			drawCompassLabels(centerX, centerY, mapRadius);
		}

		GL11.glEnable(GL11.GL_TEXTURE_2D);

		// Draw time and coordinate info (JourneyMap Style: Floating Boxes)
		renderInfoOverlay(world, player, mapX, mapY, mapSize, mapRadius);

		GL11.glDisable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
		GL11.glPopMatrix();
	}

	private int brighten(int color, float factor) {
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		r = Math.min((int) (r * factor), 255);
		g = Math.min((int) (g * factor), 255);
		b = Math.min((int) (b * factor), 255);
		return (r << 16) | (g << 8) | b;
	}

	private int darken(int color, float factor) {
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		r = (int) (r * factor);
		g = (int) (g * factor);
		b = (int) (b * factor);
		return (r << 16) | (g << 8) | b;
	}

	// Returns packed [Color (high 32) | Height (low 32)]
	private long getSurfaceInfo(World world, int x, int z, EntityPlayer player) {
		int h = 64;
		int dim = world.provider.dimensionId;

		if (dim == -1) {
			// NETHER LOGIC
			int startY = Math.min((int) player.posY + 15, 126);
			int endY = Math.max((int) player.posY - 25, 1);
			boolean found = false;
			for (int y = startY; y >= endY; y--) {
				int id = world.getBlockId(x, y, z);
				if (id != 0 && Block.blocksList[id] != null && Block.blocksList[id].blockMaterial != Material.air) {
					h = y;
					found = true;
					break;
				}
			}
			if (!found) {
				h = world.getHeightValue(x, z);
				if (h >= 126) {
					for (int y = startY; y >= 0; y--) {
						int id = world.getBlockId(x, y, z);
						if (id != 0) {
							h = y;
							break;
						}
					}
				}
			}
		} else {
			// OVERWORLD LOGIC
			h = world.getHeightValue(x, z);
			if (h < 0)
				h = 64;
		}

		Block block = null;
		int blockY = h;
		for (int y = Math.min(h + 3, 256); y >= Math.max(h - 3, 0); y--) {
			int blockId = world.getBlockId(x, y, z);
			if (blockId == 0)
				continue;
			Block testBlock = Block.blocksList[blockId];
			if (testBlock != null && testBlock.blockMaterial != Material.air) {
				block = testBlock;
				blockY = y;
				break;
			}
		}

		if (block == null)
			return 0xFF000000L | blockY;

		int color = BlockColorMapper.getBlockColor(block, world, x, blockY, z);
		if (color == 0 || color == 0x000000) {
			if (block.blockMaterial == Material.water)
				color = 0x1c6dd0;
			else if (block.blockMaterial == Material.lava)
				color = 0xFF6F00;
			else if (block.blockMaterial == Material.ground)
				color = 0x8B7355;
			else if (block.blockMaterial == Material.rock)
				color = 0x808080;
			else
				color = 0x8a8a8a;
		}
		return ((long) color << 32) | (long) blockY;
	}

	private void renderGrid(int mapX, int mapY, int mapSize, int mapRadius, double zoomFactor) {
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(1f, 1f, 1f, 0.15f);
		int chunkStep = (int) (16 / zoomFactor);
		if (chunkStep < 2)
			chunkStep = 2;

		int centerX = mapX + mapRadius;
		int centerY = mapY + mapRadius;

		for (int gx = mapRadius % chunkStep; gx < mapSize; gx += chunkStep) {
			drawLineSegmentInCircle(mapX + gx, mapY, mapX + gx, mapY + mapSize, centerX, centerY, mapRadius);
		}
		for (int gz = mapRadius % chunkStep; gz < mapSize; gz += chunkStep) {
			drawLineSegmentInCircle(mapX, mapY + gz, mapX + mapSize, mapY + gz, centerX, centerY, mapRadius);
		}
		GL11.glEnable(GL11.GL_TEXTURE_2D);
	}

	private void renderWaypointsOnMap(EntityPlayer player, int mapX, int mapY, int mapSize, int mapRadius,
			double zoomFactor) {
		List<Waypoint> waypoints = WaypointManager.instance.getWaypoints();
		if (waypoints.isEmpty())
			return;

		double playerX = player.posX;
		double playerZ = player.posZ;
		int centerX = mapX + mapRadius;
		int centerY = mapY + mapRadius;

		for (Waypoint wp : waypoints) {
			if (!wp.enabled)
				continue;

			double dx = (wp.x + 0.5 - playerX) * zoomFactor;
			double dz = (wp.z + 0.5 - playerZ) * zoomFactor;
			double dist = Math.sqrt(dx * dx + dz * dz);

			int px = centerX + (int) dx;
			int pz = centerY + (int) dz;
			boolean withinMap = dist <= mapRadius - 4; // Keep inside map bounds

			GL11.glDisable(GL11.GL_TEXTURE_2D);
			if (withinMap) {
				drawSpawnDiamond(px, pz, 4, wp.getRed(), wp.getGreen(), wp.getBlue());
			} else {
				// Edge rendering
				double angle = Math.atan2(dz, dx);
				int edgeX = centerX + (int) (Math.cos(angle) * (mapRadius - 5));
				int edgeY = centerY + (int) (Math.sin(angle) * (mapRadius - 5));
				drawSpawnDiamond(edgeX, edgeY, 4, wp.getRed(), wp.getGreen(), wp.getBlue());
			}
			GL11.glEnable(GL11.GL_TEXTURE_2D);
		}
	}

	private void renderInfoOverlay(World world, EntityPlayer player, int mapX, int mapY, int mapSize, int mapRadius) {
		if (world == null || player == null)
			return;

		FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
		if (fr == null)
			return;

		// 1. Time Overlay
		long time = world.getWorldTime();
		long dayTime = time % 24000;
		int days = (int) (time / 24000) + 1;
		int hours = (int) ((dayTime / 1000 + 6) % 24);
		int minutes = (int) ((dayTime % 1000) * 60 / 1000);

		String timeStr = String.format("Day %d, %02d:%02d", days, hours, minutes);
		String period = (hours >= 6 && hours < 19) ? "Day" : "Night";
		String clockStr = timeStr + " " + period;

		int centerX = mapX + mapRadius;
		int clockW = fr.getStringWidth(clockStr) + 8;
		int clockH = 12;

		// Position: Fixed above map
		int clockY = mapY - 20;

		drawFloatingBox(centerX - clockW / 2, clockY, clockW, clockH);
		fr.drawStringWithShadow(clockStr, centerX - clockW / 2 + 4, clockY + 2, 0xFFFFFF);

		int x = (int) player.posX;
		int y = (int) player.posY;
		int z = (int) player.posZ;
		int dim = world.provider.dimensionId;
		String coordStr = String.format("x: %d, z: %d, y: %d (%d)", x, z, y, dim);
		BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
		String biomeName = biome != null ? biome.biomeName : "Unknown";

		int coordW = fr.getStringWidth(coordStr) + 8;
		int biomeW = fr.getStringWidth(biomeName) + 8;
		int maxInfoW = Math.max(coordW, biomeW);

		int infoY = mapY + mapSize + 8;
		int infoH = (MapConfig.instance.showCoordinates ? 24 : 12);

		drawFloatingBox(centerX - maxInfoW / 2, infoY, maxInfoW, infoH);

		if (MapConfig.instance.showCoordinates) {
			fr.drawStringWithShadow(coordStr, centerX - fr.getStringWidth(coordStr) / 2, infoY + 2, 0xFFFFFF);
			fr.drawStringWithShadow(biomeName, centerX - fr.getStringWidth(biomeName) / 2, infoY + 12, 0xAAAAAA);
		} else {
			fr.drawStringWithShadow(biomeName, centerX - fr.getStringWidth(biomeName) / 2, infoY + 2, 0xFFFFFF);
		}
	}

	private void drawFloatingBox(int x, int y, int w, int h) {
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glColor4f(0.0f, 0.0f, 0.0f, 0.7f);
		Tessellator t = Tessellator.instance;
		t.startDrawingQuads();
		t.addVertex(x, y + h, 0.0);
		t.addVertex(x + w, y + h, 0.0);
		t.addVertex(x + w, y, 0.0);
		t.addVertex(x, y, 0.0);
		t.draw();
		GL11.glEnable(GL11.GL_TEXTURE_2D);
	}

	private void renderSpawnOnMap(World world, EntityPlayer player, int mapX, int mapY, int mapSize, int mapRadius,
			double zoomFactor) {
		if (world == null || world.getWorldInfo() == null)
			return;
		int spawnX = world.getWorldInfo().getSpawnX();
		int spawnZ = world.getWorldInfo().getSpawnZ();
		double dx = (spawnX - player.posX) * zoomFactor;
		double dz = (spawnZ - player.posZ) * zoomFactor;
		double dist = Math.sqrt(dx * dx + dz * dz);
		int centerX = mapX + mapRadius;
		int centerY = mapY + mapRadius;
		int px = centerX + (int) dx;
		int pz = centerY + (int) dz;
		boolean withinMap = dist <= mapRadius - 4;

		GL11.glDisable(GL11.GL_TEXTURE_2D);
		if (withinMap) {
			drawSpawnDiamond(px, pz, 5, 0, 102, 204);
		} else {
			// Always show spawn at map edge when outside
			double angle = Math.atan2(dz, dx);
			int edgeX = centerX + (int) (Math.cos(angle) * (mapRadius - 5));
			int edgeY = centerY + (int) (Math.sin(angle) * (mapRadius - 5));
			drawSpawnDiamond(edgeX, edgeY, 4, 0, 102, 204);
		}
		GL11.glEnable(GL11.GL_TEXTURE_2D);
	}

	private void renderEntitiesOnMap(World world, EntityPlayer player, int mapX, int mapY, int mapSize, int mapRadius,
			double zoomFactor) {
		double startX = player.posX;
		double startZ = player.posZ;
		double maxDist = mapRadius / zoomFactor;

		int entityCount = 0;
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		for (Object obj : world.loadedEntityList) {
			if (entityCount > 100)
				break;
			if (!(obj instanceof Entity))
				continue;
			Entity e = (Entity) obj;
			if (e == player || e.isDead)
				continue;

			// Filter clutter
			if (e instanceof EntityItem || e instanceof EntityXPOrb)
				continue;
			if (e instanceof EntitySquid || e instanceof EntityBat)
				continue;

			double dx = e.posX - startX;
			double dz = e.posZ - startZ;
			if (Math.abs(dx) > maxDist || Math.abs(dz) > maxDist)
				continue;
			if (Math.abs(e.posY - player.posY) > 20)
				continue;

			int px = mapX + mapRadius + (int) (dx * zoomFactor);
			int pz = mapY + mapRadius + (int) (dz * zoomFactor);

			if (px >= mapX && px < mapX + mapSize && pz >= mapY && pz < mapY + mapSize) {
				if (MapConfig.instance.circular) {
					double d = Math.sqrt((dx * zoomFactor) * (dx * zoomFactor) + (dz * zoomFactor) * (dz * zoomFactor));
					if (d > mapRadius)
						continue;
				}

				entityCount++;

				// Colors: Hostile=Red, Passive=Green, Neutral=Yellow
				float r = 1.0f, g = 1.0f, b = 0.0f; // Default Yellow (Neutral)
				if (e instanceof EntityMob) {
					// Hostile
					r = 0.9f;
					g = 0.1f;
					b = 0.1f;
				} else if (e instanceof EntityAnimal) {
					// Passive
					r = 0.1f;
					g = 0.9f;
					b = 0.1f;
				}

				drawEntityArrow(px, pz, e.rotationYaw, r, g, b);
			}
		}
		GL11.glEnable(GL11.GL_TEXTURE_2D);
	}

	private void drawEntityArrow(int x, int y, float yaw, float r, float g, float b) {
		Tessellator t = Tessellator.instance;
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_CULL_FACE);

		double angle = Math.toRadians(yaw + 90);
		double sz = 3.0;

		// Simple pointed triangle: tip + two wing points
		double tipX = x + Math.cos(angle) * sz;
		double tipY = y + Math.sin(angle) * sz;
		double wingLX = x + Math.cos(angle + Math.PI * 0.75) * sz;
		double wingLY = y + Math.sin(angle + Math.PI * 0.75) * sz;
		double wingRX = x + Math.cos(angle - Math.PI * 0.75) * sz;
		double wingRY = y + Math.sin(angle - Math.PI * 0.75) * sz;

		// 1. Black outline (slightly larger)
		GL11.glLineWidth(2.0f);
		t.startDrawing(GL11.GL_LINE_LOOP);
		t.setColorOpaque_F(0.0f, 0.0f, 0.0f);
		t.addVertex(tipX, tipY, 0.0);
		t.addVertex(wingLX, wingLY, 0.0);
		t.addVertex(wingRX, wingRY, 0.0);
		t.draw();

		// 2. Solid colored fill
		t.startDrawing(GL11.GL_TRIANGLES);
		t.setColorOpaque_F(r, g, b);
		t.addVertex(tipX, tipY, 0.0);
		t.addVertex(wingLX, wingLY, 0.0);
		t.addVertex(wingRX, wingRY, 0.0);
		t.draw();

		GL11.glLineWidth(1.0f);
		GL11.glEnable(GL11.GL_CULL_FACE);
	}

	private void drawPlayerArrow(int centerX, int centerY, float yaw) {
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_CULL_FACE);

		double angle = Math.toRadians(yaw + 90);
		double sz = 6.0;

		// Clean chevron shape
		double tipX = centerX + Math.cos(angle) * sz;
		double tipY = centerY + Math.sin(angle) * sz;
		double wingLX = centerX + Math.cos(angle + Math.PI * 0.7) * (sz * 0.65);
		double wingLY = centerY + Math.sin(angle + Math.PI * 0.7) * (sz * 0.65);
		double wingRX = centerX + Math.cos(angle - Math.PI * 0.7) * (sz * 0.65);
		double wingRY = centerY + Math.sin(angle - Math.PI * 0.7) * (sz * 0.65);
		double notchX = centerX + Math.cos(angle + Math.PI) * (sz * 0.15);
		double notchY = centerY + Math.sin(angle + Math.PI) * (sz * 0.15);

		Tessellator t = Tessellator.instance;

		// 1. Black outline
		GL11.glLineWidth(2.5f);
		t.startDrawing(GL11.GL_LINE_LOOP);
		t.setColorOpaque_F(0.0f, 0.0f, 0.0f);
		t.addVertex(tipX, tipY, 0.0);
		t.addVertex(wingLX, wingLY, 0.0);
		t.addVertex(notchX, notchY, 0.0);
		t.addVertex(wingRX, wingRY, 0.0);
		t.draw();

		// 2. White fill
		t.startDrawing(GL11.GL_TRIANGLE_FAN);
		t.setColorOpaque_F(1.0f, 1.0f, 1.0f);
		t.addVertex(tipX, tipY, 0.0);
		t.addVertex(wingLX, wingLY, 0.0);
		t.addVertex(notchX, notchY, 0.0);
		t.addVertex(wingRX, wingRY, 0.0);
		t.draw();

		// 3. Small cyan center dot
		double dotR = 1.5;
		t.startDrawing(GL11.GL_TRIANGLE_FAN);
		t.setColorOpaque_F(0.0f, 0.85f, 1.0f);
		for (int i = 0; i <= 6; i++) {
			double a = i / 6.0 * Math.PI * 2.0;
			t.addVertex(centerX + Math.cos(a) * dotR, centerY + Math.sin(a) * dotR, 0);
		}
		t.draw();

		GL11.glLineWidth(1.0f);
		GL11.glEnable(GL11.GL_CULL_FACE);
	}

	private void drawCircle(int centerX, int centerY, int radius) {
		Tessellator t = Tessellator.instance;
		t.startDrawing(GL11.GL_TRIANGLE_FAN);
		t.addVertex(centerX, centerY, 0.0);
		for (int i = 0; i <= 32; i++) {
			double angle = (i / 32.0) * Math.PI * 2.0;
			t.addVertex(centerX + Math.cos(angle) * radius, centerY + Math.sin(angle) * radius, 0.0);
		}
		t.draw();
	}

	private void drawCircleOutline(int centerX, int centerY, int radius) {
		Tessellator t = Tessellator.instance;
		t.startDrawing(GL11.GL_LINE_LOOP);
		for (int i = 0; i < 48; i++) {
			double angle = (i / 48.0) * Math.PI * 2.0;
			t.addVertex(centerX + Math.cos(angle) * radius, centerY + Math.sin(angle) * radius, 0.0);
		}
		t.draw();
	}

	private void drawSpawnDiamond(int centerX, int centerY, int size, int r, int g, int b) {
		Tessellator t = Tessellator.instance;
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		float rf = r / 255.0f;
		float gf = g / 255.0f;
		float bf = b / 255.0f;
		GL11.glColor4f(rf, gf, bf, 1.0f);

		t.startDrawing(GL11.GL_QUADS);
		t.addVertex(centerX, centerY - size, 0.0);
		t.addVertex(centerX - size, centerY, 0.0);
		t.addVertex(centerX, centerY + size, 0.0);
		t.addVertex(centerX + size, centerY, 0.0);
		t.draw();

		GL11.glColor4f(0f, 0f, 0f, 1f);
		GL11.glLineWidth(1.0f);
		t.startDrawing(GL11.GL_LINE_LOOP);
		t.addVertex(centerX, centerY - size, 0.0);
		t.addVertex(centerX - size, centerY, 0.0);
		t.addVertex(centerX, centerY + size, 0.0);
		t.addVertex(centerX + size, centerY, 0.0);
		t.draw();
	}

	private void drawCompassLabels(int centerX, int centerY, int radius) {
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		Minecraft mc = Minecraft.getMinecraft();
		FontRenderer fr = mc.fontRenderer;
		if (fr == null)
			return;
		int offset = radius + 2; // Push labels right to the edge/outside the frame

		drawStringWithOutline(fr, "N", centerX - fr.getStringWidth("N") / 2, centerY - offset - 4, 0xFFFFFF, 0x000000);
		drawStringWithOutline(fr, "S", centerX - fr.getStringWidth("S") / 2, centerY + offset - 5, 0xFFFFFF, 0x000000);
		drawStringWithOutline(fr, "W", centerX - offset - 3, centerY - 4, 0xFFFFFF, 0x000000);
		drawStringWithOutline(fr, "E", centerX + offset - 3, centerY - 4, 0xFFFFFF, 0x000000);
	}

	private void drawStringWithOutline(FontRenderer fr, String text, int x, int y, int color, int outlineColor) {
		fr.drawString(text, x + 1, y, outlineColor);
		fr.drawString(text, x - 1, y, outlineColor);
		fr.drawString(text, x, y + 1, outlineColor);
		fr.drawString(text, x, y - 1, outlineColor);
		fr.drawString(text, x, y, color);
	}

	private void drawLineSegmentInCircle(int x1, int y1, int x2, int y2, int centerX, int centerY, int radius) {
		Tessellator t = Tessellator.instance;
		t.startDrawingQuads();
		t.setColorRGBA(255, 255, 255, 30);
		if (x1 == x2) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				int dx = x1 - centerX;
				int dy = y - centerY;
				if (dx * dx + dy * dy <= radius * radius) {
					t.addVertex(x1, y + 1, 0.0);
					t.addVertex(x1 + 1, y + 1, 0.0);
					t.addVertex(x1 + 1, y, 0.0);
					t.addVertex(x1, y, 0.0);
				}
			}
		} else if (y1 == y2) {
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
				int dx = x - centerX;
				int dy = y1 - centerY;
				if (dx * dx + dy * dy <= radius * radius) {
					t.addVertex(x, y1 + 1, 0.0);
					t.addVertex(x + 1, y1 + 1, 0.0);
					t.addVertex(x + 1, y1, 0.0);
					t.addVertex(x, y1, 0.0);
				}
			}
		}
		t.draw();
	}
}
