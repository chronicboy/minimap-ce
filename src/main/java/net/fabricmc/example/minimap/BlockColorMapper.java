package net.fabricmc.example.minimap;

import net.minecraft.src.*;

/**
 * Entry point for block color lookup. Delegates to TextureColorSampler
 * for texture-based colors, falls back to material-based colors.
 */
public class BlockColorMapper {

	public static int getBlockColor(Block block, World world, int x, int y, int z) {
		if (block == null)
			return 0;

		// Texture-sampled color (JourneyMap-quality)
		if (TextureColorSampler.isInitialized()) {
			BiomeGenBase biome = world != null ? world.getBiomeGenForCoords(x, z) : null;
			int sampled = TextureColorSampler.getBlockColor(block.blockID, biome);
			if (sampled != 0)
				return sampled;
		}

		// Fallback: material-based colors
		Material mat = block.blockMaterial;
		if (mat == Material.water)
			return 0x1976D2;
		if (mat == Material.lava)
			return 0xFF6F00;
		if (mat == Material.ground)
			return 0x8D6E63;
		if (mat == Material.rock)
			return 0x757575;
		if (mat == Material.wood)
			return 0x5D4037;
		if (mat == Material.leaves)
			return 0x388E3C;
		if (mat == Material.plants)
			return 0x66BB6A;
		if (mat == Material.snow)
			return 0xFFFFFF;
		if (mat == Material.ice)
			return 0x81D4FA;
		return 0x9E9E9E;
	}
}
