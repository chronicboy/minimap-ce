package net.fabricmc.example.minimap;

import net.minecraft.src.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.IntBuffer;

/**
 * Reads the Minecraft terrain texture atlas at runtime and computes
 * the average RGB color for each block's top-face texture.
 * This gives JourneyMap-quality color accuracy on the minimap.
 */
public class TextureColorSampler {
    private static int[] blockColors = null;
    private static boolean initialized = false;
    private static boolean failed = false;

    // Block IDs that should receive foliage biome tinting (multiply mode)
    private static final boolean[] FOLIAGE_TINTED = new boolean[4096];
    // Block IDs that should USE the biome color directly (grayscale textures)
    private static final boolean[] USE_GRASS_DIRECT = new boolean[4096];
    private static final boolean[] USE_FOLIAGE_DIRECT = new boolean[4096];

    static {
        // Grass block, tall grass, ferns, sugar cane — ALL use biome grass color
        // DIRECTLY
        // (their textures are grayscale overlays, designed to be replaced by biome
        // color)
        if (Block.grass != null)
            USE_GRASS_DIRECT[Block.grass.blockID] = true;
        if (Block.tallGrass != null)
            USE_GRASS_DIRECT[Block.tallGrass.blockID] = true;
        if (Block.reed != null)
            USE_GRASS_DIRECT[Block.reed.blockID] = true;

        // Vines — use biome foliage color directly
        if (Block.vine != null)
            USE_FOLIAGE_DIRECT[Block.vine.blockID] = true;

        // Leaves — multiply tint (their base texture has some color variation worth
        // keeping)
        if (Block.leaves != null)
            FOLIAGE_TINTED[Block.leaves.blockID] = true;
    }

    public static boolean isInitialized() {
        return initialized && !failed;
    }

    /**
     * Initialize the sampler by reading the terrain atlas.
     * Should be called lazily on first render when GL context is valid.
     */
    public static void init() {
        if (initialized)
            return;
        initialized = true;

        try {
            blockColors = new int[4096];
            sampleAllBlocks();
            System.out.println("[Minimap CE] Texture color sampling initialized successfully.");
        } catch (Exception e) {
            System.err.println("[Minimap CE] Texture sampling failed, using fallback colors: " + e.getMessage());
            failed = true;
            blockColors = null;
        }
    }

    /**
     * Get the sampled color for a block, with optional biome tinting.
     * Returns 0 if sampling is unavailable for this block.
     */
    public static int getBlockColor(int blockId, BiomeGenBase biome) {
        if (blockColors == null || blockId < 0 || blockId >= blockColors.length) {
            return 0;
        }

        int color = blockColors[blockId];
        if (color == 0)
            return 0;

        // Apply biome tinting
        if (biome != null) {
            if (USE_GRASS_DIRECT[blockId]) {
                return darkenColor(biome.getBiomeGrassColor(), 0.78f);
            } else if (USE_FOLIAGE_DIRECT[blockId]) {
                return darkenColor(biome.getBiomeFoliageColor(), 0.75f);
            } else if (FOLIAGE_TINTED[blockId]) {
                color = darkenColor(multiplyColor(color, biome.getBiomeFoliageColor()), 0.85f);
            }
        }

        return color;
    }

    /**
     * Sample all blocks by reading the terrain texture atlas from OpenGL.
     */
    private static void sampleAllBlocks() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            failed = true;
            return;
        }

        // Get the terrain atlas texture
        // In BTW CE, we need to find the terrain atlas GL texture ID
        int atlasWidth = 0;
        int atlasHeight = 0;
        int[] atlasPixels = null;

        try {
            // Try to read the currently bound block texture atlas
            // The terrain atlas is typically at texture slot for "/terrain.png"
            // or the stitched block atlas
            atlasPixels = readTerrainAtlas();
            if (atlasPixels == null) {
                failed = true;
                return;
            }

            // Get dimensions from GL
            atlasWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            atlasHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        } catch (Exception e) {
            System.err.println("[Minimap CE] Failed to read atlas: " + e.getMessage());
            failed = true;
            return;
        }

        if (atlasWidth <= 0 || atlasHeight <= 0 || atlasPixels == null) {
            failed = true;
            return;
        }

        // Now sample each block's top-face texture
        for (int id = 0; id < Block.blocksList.length && id < blockColors.length; id++) {
            Block block = Block.blocksList[id];
            if (block == null)
                continue;

            try {
                Icon icon = block.getBlockTextureFromSide(1); // 1 = top face
                if (icon == null)
                    continue;

                // Get UV coordinates in the atlas
                float minU = icon.getMinU();
                float maxU = icon.getMaxU();
                float minV = icon.getMinV();
                float maxV = icon.getMaxV();

                // Convert to pixel coordinates
                int x0 = (int) (minU * atlasWidth);
                int y0 = (int) (minV * atlasHeight);
                int x1 = (int) (maxU * atlasWidth);
                int y1 = (int) (maxV * atlasHeight);

                // Clamp
                x0 = Math.max(0, Math.min(x0, atlasWidth - 1));
                y0 = Math.max(0, Math.min(y0, atlasHeight - 1));
                x1 = Math.max(x0 + 1, Math.min(x1, atlasWidth));
                y1 = Math.max(y0 + 1, Math.min(y1, atlasHeight));

                // Average the pixels in this region
                blockColors[id] = averagePixels(atlasPixels, atlasWidth, x0, y0, x1, y1);
            } catch (Exception e) {
                // Skip this block, it'll use fallback colors
            }
        }
    }

    /**
     * Read the terrain texture atlas pixels from OpenGL.
     */
    private static int[] readTerrainAtlas() {
        try {
            // Bind the terrain atlas - in Minecraft, this is typically at "/terrain.png"
            // or the block TextureMap. We try to bind it first.
            Minecraft mc = Minecraft.getMinecraft();

            // Try binding the terrain texture atlas
            try {
                mc.renderEngine.bindTexture(new ResourceLocation("textures/atlas/blocks.png"));
            } catch (Exception e1) {
                try {
                    mc.renderEngine.bindTexture(new ResourceLocation("terrain"));
                } catch (Exception e2) {
                    System.err.println("[Minimap CE] Could not bind terrain atlas: " + e2.getMessage());
                    return null;
                }
            }

            // Read dimensions
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

            if (width <= 0 || height <= 0)
                return null;

            // Read pixels
            IntBuffer buf = BufferUtils.createIntBuffer(width * height);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);

            int[] pixels = new int[width * height];
            buf.get(pixels);
            return pixels;
        } catch (Exception e) {
            System.err.println("[Minimap CE] Failed to read terrain atlas: " + e.getMessage());
            return null;
        }
    }

    /**
     * Average the RGB values of pixels in a region, ignoring fully transparent
     * pixels.
     */
    private static int averagePixels(int[] pixels, int stride, int x0, int y0, int x1, int y1) {
        long totalR = 0, totalG = 0, totalB = 0;
        int count = 0;

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int pixel = pixels[y * stride + x];

                // BGRA format from GL12.GL_BGRA
                int a = (pixel >> 24) & 0xFF;
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                // Skip fully transparent pixels
                if (a < 32)
                    continue;

                totalR += r;
                totalG += g;
                totalB += b;
                count++;
            }
        }

        if (count == 0)
            return 0;

        int avgR = (int) (totalR / count);
        int avgG = (int) (totalG / count);
        int avgB = (int) (totalB / count);

        return (avgR << 16) | (avgG << 8) | avgB;
    }

    /**
     * Multiply two RGB colors together (component-wise, normalized).
     * Used for biome tinting: base texture color × biome color.
     */
    private static int multiplyColor(int base, int tint) {
        int br = (base >> 16) & 0xFF;
        int bg = (base >> 8) & 0xFF;
        int bb = base & 0xFF;

        int tr = (tint >> 16) & 0xFF;
        int tg = (tint >> 8) & 0xFF;
        int tb = tint & 0xFF;

        int r = (br * tr) / 255;
        int g = (bg * tg) / 255;
        int b = (bb * tb) / 255;

        return (r << 16) | (g << 8) | b;
    }

    /**
     * Darken an RGB color by a factor (0.0 = black, 1.0 = unchanged).
     * Used to create JourneyMap-style muted, natural map tones.
     */
    private static int darkenColor(int color, float factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }
}
