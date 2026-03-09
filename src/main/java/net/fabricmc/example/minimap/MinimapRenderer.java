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
	private long lastSaveTime = 0;
	private final java.util.HashSet<Long> tempChunkSet = new java.util.HashSet<Long>();

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
		} else {
			// If bottom-aligned, push map up to make room for coords/biome below
			mapY -= 30;
		}

		GL11.glPushMatrix();
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		// Background (JourneyMap style: Solid dark circle border for map content)
		GL11.glColor4f(0.1f, 0.1f, 0.1f, MapConfig.instance.minimapOpacity);
		drawCircle(mapX + mapRadius, mapY + mapRadius, mapRadius);

		double zoomFactor = Math.pow(2.0, -MapConfig.instance.zoomLevel);

		GL11.glDisable(GL11.GL_TEXTURE_2D);

		int playerBlockX = (int) Math.floor(player.posX);
		int playerBlockZ = (int) Math.floor(player.posZ);

		// Sub-pixel offset for smooth scrolling (fractional part of player position)
		double fracX = player.posX - playerBlockX;
		double fracZ = player.posZ - playerBlockZ;

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

					// Use integer-aligned center for stable sampling
					int worldX = playerBlockX + (int) Math.floor((x - mapRadius) / zoomFactor);
					int worldZ = playerBlockZ + (int) Math.floor((z - mapRadius) / zoomFactor);

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

			// Save explored terrain to persistent tile cache (throttled to every 500ms to
			// prevent map holes)
			long now = System.currentTimeMillis();
			if (MapTileManager.instance.hasWorld() && pixelStep == 1 && now - lastSaveTime > 500) {
				lastSaveTime = now;
				saveExploredChunks(world, player, mapSize, mapRadius, zoomFactor);
			}
		}

		// Apply sub-pixel offset for smooth scrolling
		double offsetX = -fracX * zoomFactor;
		double offsetZ = -fracZ * zoomFactor;

		// Render from cache
		Tessellator t = Tessellator.instance;
		t.startDrawingQuads();

		for (int z = 0; z < mapSize; z += pixelStep) {
			int zOffset = z * mapSize;
			double basePz = mapY + z + offsetZ;
			for (int x = 0; x < mapSize; x += pixelStep) {
				int color = colorCache[zOffset + x];
				if (color == -1)
					continue;

				int r = (color >> 16) & 0xFF;
				int g = (color >> 8) & 0xFF;
				int b = color & 0xFF;

				double px = mapX + x + offsetX;
				t.setColorRGBA(r, g, b, 255);
				t.addVertex(px, basePz + pixelStep, 0.0);
				t.addVertex(px + pixelStep, basePz + pixelStep, 0.0);
				t.addVertex(px + pixelStep, basePz, 0.0);
				t.addVertex(px, basePz, 0.0);
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

		if (MapConfig.instance.showSpawnWaypoint) {
			renderSpawnOnMap(world, player, mapX, mapY, mapSize, mapRadius, zoomFactor);
		}

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
		renderInfoOverlay(world, player, mapX, mapY, mapSize, mapRadius, screenWidth, screenHeight);

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

	private void saveExploredChunks(World world, EntityPlayer player, int mapSize, int mapRadius, double zoomFactor) {
		// Reuse field-level set to avoid per-call allocation
		tempChunkSet.clear();

		// Calculate world bounds based on map radius and zoom
		// Add 1 chunk padding so we catch chunks right at the edge of the load distance
		int worldRadius = (int) Math.ceil(mapRadius / zoomFactor);
		int minChunkX = (((int) player.posX - worldRadius) >> 4) - 1;
		int maxChunkX = (((int) player.posX + worldRadius) >> 4) + 1;
		int minChunkZ = (((int) player.posZ - worldRadius) >> 4) - 1;
		int maxChunkZ = (((int) player.posZ + worldRadius) >> 4) + 1;

		for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
			for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
				long chunkKey = MapTileManager.packChunkCoords(chunkX, chunkZ);
				if (tempChunkSet.contains(chunkKey))
					continue;

				// Check if this chunk is loaded
				if (!world.getChunkProvider().chunkExists(chunkX, chunkZ))
					continue;

				// Check if we should skip saving this chunk
				int[] existing = MapTileManager.instance.getChunkColors(chunkX, chunkZ);
				if (existing != null) {
					boolean hasEmpty = false;
					for (int i = 0; i < 256; i++) {
						if (existing[i] == 0) {
							hasEmpty = true;
							break;
						}
					}
					// If the chunk is fully cached and has no holes, skip re-evaluating it
					if (!hasEmpty)
						continue;
				}

				tempChunkSet.add(chunkKey);

				// Sample the full 16x16 chunk
				int[] colors = new int[256];
				for (int lz = 0; lz < 16; lz++) {
					for (int lx = 0; lx < 16; lx++) {
						int bx = chunkX * 16 + lx;
						int bz = chunkZ * 16 + lz;
						long packed = getSurfaceInfo(world, bx, bz, player);
						colors[lz * 16 + lx] = (int) (packed >> 32);
					}
				}
				MapTileManager.instance.saveChunkColors(chunkX, chunkZ, colors);
			}
		}
	}

	// Returns packed [Color (high 32) | Height (low 32)]
	public static long getSurfaceInfo(World world, int x, int z, EntityPlayer player) {
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
				drawWaypointMarker(px, pz, 4, wp.getRed(), wp.getGreen(), wp.getBlue());
			} else {
				// Edge rendering
				double angle = Math.atan2(dz, dx);
				int edgeX = centerX + (int) (Math.cos(angle) * (mapRadius - 5));
				int edgeY = centerY + (int) (Math.sin(angle) * (mapRadius - 5));
				drawWaypointMarker(edgeX, edgeY, 4, wp.getRed(), wp.getGreen(), wp.getBlue());
			}
			GL11.glEnable(GL11.GL_TEXTURE_2D);
		}
	}

	private void renderInfoOverlay(World world, EntityPlayer player, int mapX, int mapY, int mapSize, int mapRadius,
			int screenWidth, int screenHeight) {
		if (world == null || player == null)
			return;

		FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
		if (fr == null)
			return;

		// Calculate text scale based on minimap size (baseline 128 = 1.0f)
		float textScale = Math.max(0.5f, Math.min(1.0f, mapSize / 128.0f));
		float invScale = 1.0f / textScale;

		GL11.glPushMatrix();
		GL11.glScalef(textScale, textScale, 1.0f);

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

		int clockW = (int) (fr.getStringWidth(clockStr) * textScale) + 8;
		int clockH = (int) (12 * textScale);

		// Position: Fixed above map (clamp to screen)
		int clockY = Math.max(2, mapY - clockH - 8);
		int clockX = centerX - clockW / 2;
		clockX = Math.max(2, Math.min(clockX, screenWidth - clockW - 2));

		fr.drawStringWithShadow(clockStr, (int) ((clockX + 4) * invScale), (int) ((clockY + 2 * textScale) * invScale),
				0xFFFFFF);

		int x = (int) player.posX;
		int y = (int) player.posY;
		int z = (int) player.posZ;
		String coordStr = String.format("x: %d, z: %d, y: %d", x, z, y);
		BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
		String biomeName = biome != null ? biome.biomeName : "Unknown";

		int coordW = (int) (fr.getStringWidth(coordStr) * textScale) + 8;
		int biomeW = (int) (fr.getStringWidth(biomeName) * textScale) + 8;
		int maxInfoW = Math.max(coordW, biomeW);

		boolean showCoords = MapConfig.instance.showCoordinates;
		boolean showBiome = MapConfig.instance.showBiome;
		int infoLines = (showCoords ? 1 : 0) + (showBiome ? 1 : 0);
		int infoH = (int) (infoLines * 12 * textScale);
		if (infoH == 0) {
			GL11.glPopMatrix();
			return; // Nothing to show
		}

		// Place below map, clamp to screen bounds
		int infoY = mapY + mapSize + 8;
		infoY = Math.min(infoY, screenHeight - infoH - 2);
		int infoX = centerX - maxInfoW / 2;
		infoX = Math.max(2, Math.min(infoX, screenWidth - maxInfoW - 2));

		int textY = infoY + (int) (2 * textScale);
		if (showCoords) {
			int coordTxtW = (int) (fr.getStringWidth(coordStr) * textScale);
			fr.drawStringWithShadow(coordStr, (int) ((infoX + (maxInfoW - coordTxtW) / 2) * invScale),
					(int) (textY * invScale), 0xFFFFFF);
			textY += (int) (12 * textScale);
		}
		if (showBiome) {
			int biomeTxtW = (int) (fr.getStringWidth(biomeName) * textScale);
			fr.drawStringWithShadow(biomeName, (int) ((infoX + (maxInfoW - biomeTxtW) / 2) * invScale),
					(int) (textY * invScale), 0xAAAAAA);
		}

		GL11.glPopMatrix();
	}

	private void renderSpawnOnMap(World world, EntityPlayer player, int mapX, int mapY, int mapSize, int mapRadius,
			double zoomFactor) {
		if (!SpawnTracker.instance.hasSpawn())
			return;
		int spawnX, spawnZ;
		int dim = world.provider.dimensionId;
		if (dim == 1) {
			spawnX = 100;
			spawnZ = 0;
		} else if (dim == -1) {
			spawnX = SpawnTracker.instance.getNetherSpawnX();
			spawnZ = SpawnTracker.instance.getNetherSpawnZ();
		} else {
			spawnX = SpawnTracker.instance.getSpawnX();
			spawnZ = SpawnTracker.instance.getSpawnZ();
		}
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
			drawWaypointMarker(px, pz, 4, 0, 102, 204);
		} else {
			// Always show spawn at map edge when outside
			double angle = Math.atan2(dz, dx);
			int edgeX = centerX + (int) (Math.cos(angle) * (mapRadius - 5));
			int edgeY = centerY + (int) (Math.sin(angle) * (mapRadius - 5));
			drawWaypointMarker(edgeX, edgeY, 4, 0, 102, 204);
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
		double sz = 7.0;

		// Sleek pointed arrow shape — slim profile
		double tipX = centerX + Math.cos(angle) * sz;
		double tipY = centerY + Math.sin(angle) * sz;
		double wingLX = centerX + Math.cos(angle + Math.PI * 0.82) * (sz * 0.6);
		double wingLY = centerY + Math.sin(angle + Math.PI * 0.82) * (sz * 0.6);
		double wingRX = centerX + Math.cos(angle - Math.PI * 0.82) * (sz * 0.6);
		double wingRY = centerY + Math.sin(angle - Math.PI * 0.82) * (sz * 0.6);
		double tailX = centerX + Math.cos(angle + Math.PI) * (sz * 0.2);
		double tailY = centerY + Math.sin(angle + Math.PI) * (sz * 0.2);

		Tessellator t = Tessellator.instance;

		// 1. Drop shadow (offset slightly down-right)
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

		// 3. Main body fill — dark navy blue
		t.startDrawing(GL11.GL_TRIANGLE_FAN);
		t.setColorOpaque_F(0.10f, 0.15f, 0.30f);
		t.addVertex(tipX, tipY, 0.0);
		t.addVertex(wingLX, wingLY, 0.0);
		t.addVertex(tailX, tailY, 0.0);
		t.addVertex(wingRX, wingRY, 0.0);
		t.draw();

		// 4. Bright cyan inner highlight (slightly smaller)
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
		t.setColorOpaque_F(0.2f, 0.75f, 1.0f);
		t.addVertex(hTipX, hTipY, 0.0);
		t.addVertex(hWingLX, hWingLY, 0.0);
		t.addVertex(hTailX, hTailY, 0.0);
		t.addVertex(hWingRX, hWingRY, 0.0);
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

	private void drawWaypointMarker(int centerX, int centerY, int size, int r, int g, int b) {
		Tessellator t = Tessellator.instance;
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_CULL_FACE);
		float rf = r / 255.0f;
		float gf = g / 255.0f;
		float bf = b / 255.0f;

		// Half size minimalist marker
		int s = Math.max(1, size / 2);
		int outline = s + 1;

		// 1. Black outline (1px thick square border)
		t.startDrawing(GL11.GL_QUADS);
		t.setColorOpaque_F(0f, 0f, 0f);
		t.addVertex(centerX - outline, centerY - outline, 0.0);
		t.addVertex(centerX + outline, centerY - outline, 0.0);
		t.addVertex(centerX + outline, centerY + outline, 0.0);
		t.addVertex(centerX - outline, centerY + outline, 0.0);
		t.draw();

		// 2. Colored inner square
		t.startDrawing(GL11.GL_QUADS);
		t.setColorOpaque_F(rf, gf, bf);
		t.addVertex(centerX - s, centerY - s, 0.0);
		t.addVertex(centerX + s, centerY - s, 0.0);
		t.addVertex(centerX + s, centerY + s, 0.0);
		t.addVertex(centerX - s, centerY + s, 0.0);
		t.draw();

		// 3. Bright contrast dot in center for visibility
		if (s > 1) {
			t.startDrawing(GL11.GL_QUADS);
			t.setColorOpaque_F(1f, 1f, 1f); // White dot
			t.addVertex(centerX - 1, centerY - 1, 0.0);
			t.addVertex(centerX + 1, centerY - 1, 0.0);
			t.addVertex(centerX + 1, centerY + 1, 0.0);
			t.addVertex(centerX - 1, centerY + 1, 0.0);
			t.draw();
		}

		GL11.glEnable(GL11.GL_CULL_FACE);
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
