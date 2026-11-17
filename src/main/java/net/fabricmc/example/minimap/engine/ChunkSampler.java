package net.fabricmc.example.minimap.engine;

import net.minecraft.src.Chunk;
import net.minecraft.src.ExtendedBlockStorage;
import net.minecraft.src.World;

/**
 * Turns one loaded chunk into 256 map tile values. Reads the chunk's block
 * storage directly, so a full chunk costs a few thousand array reads instead
 * of thousands of world lookups. Main thread only.
 */
public final class ChunkSampler {
	private static final int MAX_WATER_DEPTH = 24;
	private static final int CAVE_FLOOR_SEARCH = 48;

	// Biome tint blending: grass, leaves and water colours are averaged over a 5x5 area, across
	// chunk borders, so biome edges fade into each other instead of forming hard blocky lines.
	private static final int BLEND_RADIUS = 2;
	private static final int GRID = 16 + 2 * BLEND_RADIUS;
	private static final int BLEND_AREA = (2 * BLEND_RADIUS + 1) * (2 * BLEND_RADIUS + 1);
	private static final int[] gridBiomes = new int[GRID * GRID];
	private static final int[][] blendedTints = new int[4][256];
	private static final boolean[] blendedReady = new boolean[4];
	private static final int[] rowSums = new int[GRID * 16 * 3];
	private static final Chunk[] neighbours = new Chunk[9];
	private static boolean gridReady;
	private static Chunk gridChunk;
	private static World gridWorld;

	private ChunkSampler() {
	}

	/**
	 * Samples the highest visible block of every column (sky view).
	 *
	 * @param out receives 256 tile values (index = z * 16 + x)
	 * @return number of columns that contain a block
	 */
	public static int sampleSurface(Chunk chunk, World world, int[] out) {
		ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
		int topSection = -1;
		for (int i = sections.length - 1; i >= 0; i--) {
			if (sections[i] != null) {
				topSection = i;
				break;
			}
		}
		if (topSection < 0) {
			java.util.Arrays.fill(out, 0);
			return 0;
		}
		int topY = (topSection << 4) + 15;
		beginChunk(chunk, world);
		int found = 0;

		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int index = (z << 4) | x;
				int y = topY;
				int id = 0;
				ExtendedBlockStorage section = null;
				while (y >= 0) {
					section = sections[y >> 4];
					if (section == null) {
						y = (y & ~15) - 1;
						continue;
					}
					id = section.getExtBlockID(x, y & 15, z);
					if (id > 0 && id < 4096 && !BlockColors.isSkipped(id))
						break;
					y--;
				}
				if (y < 0) {
					out[index] = 0;
					continue;
				}

				int rgb = stateColor((id << 4) | (section.getExtBlockMetadata(x, y & 15, z) & 15), x, z);
				if (BlockColors.isWater(id))
					rgb = waterColor(chunk, x, y, z, rgb);
				out[index] = MapShade.pack(rgb, y);
				found++;
			}
		}
		return found;
	}

	/**
	 * Samples a horizontal slice around the reference height (the player's eye
	 * level). Open columns show their floor, solid rock shows as a dark wall.
	 * Known floors are never replaced by walls, so the map accumulates as the
	 * player moves between levels.
	 *
	 * @param prev the chunk's current tile values, or null
	 */
	public static int sampleCave(Chunk chunk, World world, int[] out, int[] prev, int refY) {
		if (refY < 1)
			refY = 1;
		if (refY > 254)
			refY = 254;
		beginChunk(chunk, world);
		int found = 0;

		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int index = (z << 4) | x;
				int previous = prev != null ? prev[index] : 0;
				int y = refY;

				if (!isOpen(chunk, x, y, z)) {
					int openY = -1;
					for (int dy = 1; dy <= 3 && openY < 0; dy++) {
						if (y + dy < 256 && isOpen(chunk, x, y + dy, z))
							openY = y + dy;
					}
					for (int dy = 1; dy <= 5 && openY < 0; dy++) {
						if (y - dy > 0 && isOpen(chunk, x, y - dy, z))
							openY = y - dy;
					}
					if (openY < 0) {
						// Solid rock at the player's level.
						if (previous != 0 && (previous >>> 24) != 0) {
							out[index] = previous;
						} else {
							int id = chunk.getBlockID(x, y, z);
							int rgb = (id > 0 && id < 4096)
									? stateColor((id << 4) | (chunk.getBlockMetadata(x, y, z) & 15), x, z)
									: 0x202020;
							out[index] = MapShade.pack(MapShade.scale(rgb, 84), 0);
						}
						found++;
						continue;
					}
					y = openY;
				}

				int floorY = y - 1;
				int minY = Math.max(0, y - CAVE_FLOOR_SEARCH);
				while (floorY >= minY && isOpen(chunk, x, floorY, z))
					floorY--;
				if (floorY < minY) {
					// Nothing below within range (void or an unfilled chunk).
					out[index] = previous;
					if (previous != 0)
						found++;
					continue;
				}

				int id = chunk.getBlockID(x, floorY, z);
				int rgb = stateColor((id << 4) | (chunk.getBlockMetadata(x, floorY, z) & 15), x, z);
				int below = refY - floorY;
				if (below > 6) {
					int f = 256 - (below - 6) * 5;
					rgb = MapShade.scale(rgb, f < 140 ? 140 : f);
				}
				out[index] = MapShade.pack(rgb, floorY);
				found++;
			}
		}
		return found;
	}

	private static boolean isOpen(Chunk chunk, int x, int y, int z) {
		int id = chunk.getBlockID(x, y, z);
		return id <= 0 || id >= 4096 || BlockColors.isSkipped(id);
	}

	private static void beginChunk(Chunk chunk, World world) {
		gridChunk = chunk;
		gridWorld = world;
		gridReady = false;
		for (int i = 0; i < blendedReady.length; i++)
			blendedReady[i] = false;
	}

	/** Biome ids of the chunk plus a border taken from loaded neighbours (or the chunk's own edge). */
	private static void buildGrid() {
		gridReady = true;
		Chunk chunk = gridChunk;
		for (int i = 0; i < 9; i++) {
			Chunk n = null;
			if (i == 4) {
				n = chunk;
			} else if (gridWorld != null) {
				try {
					n = gridWorld.getChunkFromChunkCoords(chunk.xPosition + (i % 3) - 1, chunk.zPosition + (i / 3) - 1);
					if (n != null && n.isEmpty())
						n = null;
				} catch (Throwable t) {
					n = null;
				}
			}
			neighbours[i] = n;
		}
		for (int gz = 0; gz < GRID; gz++) {
			for (int gx = 0; gx < GRID; gx++) {
				int lx = gx - BLEND_RADIUS, lz = gz - BLEND_RADIUS;
				int ox = lx < 0 ? 0 : (lx > 15 ? 2 : 1);
				int oz = lz < 0 ? 0 : (lz > 15 ? 2 : 1);
				Chunk n = neighbours[oz * 3 + ox];
				if (n == null) {
					// Neighbour not loaded: continue the chunk's own edge.
					n = chunk;
					lx = lx < 0 ? 0 : (lx > 15 ? 15 : lx);
					lz = lz < 0 ? 0 : (lz > 15 ? 15 : lz);
				}
				gridBiomes[gz * GRID + gx] = biomeAt(n, gridWorld, n.getBiomeArray(), lx & 15, lz & 15);
			}
		}
		for (int i = 0; i < 9; i++)
			neighbours[i] = null;
	}

	/** Tint of a type at a column, averaged over the surrounding 5x5 blocks. */
	private static int blendedTint(int type, int x, int z) {
		if (!blendedReady[type]) {
			if (!gridReady)
				buildGrid();
			int[] out = blendedTints[type];
			int width = 2 * BLEND_RADIUS + 1;
			// Horizontal pass: GRID rows x 16 columns of sums.
			for (int gz = 0; gz < GRID; gz++) {
				for (int x0 = 0; x0 < 16; x0++) {
					int r = 0, g = 0, b = 0;
					for (int k = 0; k < width; k++) {
						int rgb = BlockColors.biomeTint(type, gridBiomes[gz * GRID + x0 + k]);
						r += (rgb >> 16) & 0xFF;
						g += (rgb >> 8) & 0xFF;
						b += rgb & 0xFF;
					}
					int i = (gz * 16 + x0) * 3;
					rowSums[i] = r;
					rowSums[i + 1] = g;
					rowSums[i + 2] = b;
				}
			}
			// Vertical pass.
			for (int z0 = 0; z0 < 16; z0++) {
				for (int x0 = 0; x0 < 16; x0++) {
					int r = 0, g = 0, b = 0;
					for (int k = 0; k < width; k++) {
						int i = ((z0 + k) * 16 + x0) * 3;
						r += rowSums[i];
						g += rowSums[i + 1];
						b += rowSums[i + 2];
					}
					out[(z0 << 4) | x0] = ((r / BLEND_AREA) << 16) | ((g / BLEND_AREA) << 8) | (b / BLEND_AREA);
				}
			}
			blendedReady[type] = true;
		}
		return blendedTints[type][(z << 4) | x];
	}

	private static int biomeAt(Chunk chunk, World world, byte[] biomes, int x, int z) {
		int id = biomes != null && biomes.length >= 256 ? biomes[(z << 4) | x] & 255 : 255;
		if (id == 255) {
			try {
				id = chunk.getBiomeGenForWorldCoords(x, z, world.getWorldChunkManager()).biomeID & 255;
			} catch (Throwable t) {
				id = 1;
			}
			if (id == 255)
				id = 1;
		}
		return id;
	}

	private static int stateColor(int state, int x, int z) {
		int rgb = BlockColors.color(state);
		if (rgb == 0) {
			rgb = BlockColors.color(state & ~15);
			if (rgb == 0)
				return 0x7F7F7F;
		}
		int type = BlockColors.tintType(state);
		return type == BlockColors.TINT_NONE ? rgb : MapShade.multiply(rgb, blendedTint(type, x, z));
	}

	/** Shallow water lets the bed show through; deep water gets darker. */
	private static int waterColor(Chunk chunk, int x, int surfaceY, int z, int waterRgb) {
		int y = surfaceY - 1;
		int limit = Math.max(0, surfaceY - MAX_WATER_DEPTH);
		int floorId = 0;
		while (y >= limit) {
			int id = chunk.getBlockID(x, y, z);
			if (id > 0 && id < 4096 && !BlockColors.isWater(id) && !BlockColors.isSkipped(id)) {
				floorId = id;
				break;
			}
			y--;
		}
		int depth = surfaceY - y;
		int rgb = waterRgb;
		if (floorId != 0) {
			int floorRgb = stateColor((floorId << 4) | (chunk.getBlockMetadata(x, y, z) & 15), x, z);
			int opacity = 150 + depth * 14;
			if (opacity > 240)
				opacity = 240;
			rgb = MapShade.lerp(floorRgb, waterRgb, opacity);
		}
		int darken = 256 - Math.min(depth, 20) * 4;
		return MapShade.scale(rgb, darken);
	}
}
