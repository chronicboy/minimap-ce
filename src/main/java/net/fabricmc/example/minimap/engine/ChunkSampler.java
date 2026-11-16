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
		byte[] biomes = chunk.getBiomeArray();
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

				int biome = biomeAt(chunk, world, biomes, x, z);
				int rgb = stateColor((id << 4) | (section.getExtBlockMetadata(x, y & 15, z) & 15), biome);
				if (BlockColors.isWater(id))
					rgb = waterColor(chunk, x, y, z, rgb, biome);
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
		byte[] biomes = chunk.getBiomeArray();
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
									? stateColor((id << 4) | (chunk.getBlockMetadata(x, y, z) & 15),
											biomeAt(chunk, world, biomes, x, z))
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
				int rgb = stateColor((id << 4) | (chunk.getBlockMetadata(x, floorY, z) & 15),
						biomeAt(chunk, world, biomes, x, z));
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

	private static int stateColor(int state, int biome) {
		int rgb = BlockColors.color(state);
		if (rgb == 0) {
			rgb = BlockColors.color(state & ~15);
			if (rgb == 0)
				return 0x7F7F7F;
		}
		int type = BlockColors.tintType(state);
		return type == BlockColors.TINT_NONE ? rgb : MapShade.multiply(rgb, BlockColors.biomeTint(type, biome));
	}

	/** Shallow water lets the bed show through; deep water gets darker. */
	private static int waterColor(Chunk chunk, int x, int surfaceY, int z, int waterRgb, int biome) {
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
			int floorRgb = stateColor((floorId << 4) | (chunk.getBlockMetadata(x, y, z) & 15), biome);
			int opacity = 115 + depth * 18;
			if (opacity > 236)
				opacity = 236;
			rgb = MapShade.lerp(floorRgb, waterRgb, opacity);
		}
		int darken = 256 - Math.min(depth, 20) * 4;
		return MapShade.scale(rgb, darken);
	}
}
