package net.fabricmc.example.minimap.engine;

import net.minecraft.src.BiomeGenBase;
import net.minecraft.src.Block;
import net.minecraft.src.BlockFire;
import net.minecraft.src.BlockGrass;
import net.minecraft.src.BlockLeaves;
import net.minecraft.src.BlockLeavesBase;
import net.minecraft.src.BlockLilyPad;
import net.minecraft.src.BlockRedstoneWire;
import net.minecraft.src.BlockTallGrass;
import net.minecraft.src.BlockTorch;
import net.minecraft.src.BlockVine;
import net.minecraft.src.Icon;
import net.minecraft.src.Material;
import net.minecraft.src.Minecraft;
import net.minecraft.src.TextureMap;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * Map colour of every block state (id + metadata), taken from the average
 * colour of its top texture in the current resource pack, plus the biome tint
 * class each state needs. Rebuilt automatically when the texture atlas is
 * re-stitched (resource pack change).
 *
 * Main thread only (reads the atlas back from OpenGL).
 */
public final class BlockColors {
	public static final int TINT_NONE = 0;
	public static final int TINT_GRASS = 1;
	public static final int TINT_FOLIAGE = 2;
	public static final int TINT_WATER = 3;

	private static final int STATES = 4096 * 16;

	/** RGB per (id << 4 | meta); 0 = no colour known. */
	private static final int[] colors = new int[STATES];
	private static final byte[] tint = new byte[STATES];
	/** Blocks that are invisible on the map (the scan continues below them). */
	private static final boolean[] skip = new boolean[4096];
	private static final boolean[] water = new boolean[4096];
	/** Per tint class, per biome id: 0xFF000000 | rgb once computed. */
	private static final int[][] biomeTints = new int[4][256];

	private static boolean ready;
	private static Icon probeIcon;
	private static long lastProbeMs;
	private static int generation;

	private BlockColors() {
	}

	/** Increases every time the colour table is rebuilt. */
	public static int generation() {
		return generation;
	}

	public static int color(int state) {
		return colors[state];
	}

	public static int tintType(int state) {
		return tint[state];
	}

	public static boolean isSkipped(int blockId) {
		return skip[blockId];
	}

	public static boolean isWater(int blockId) {
		return water[blockId];
	}

	/** Biome tint colour for a tint class; biomeId is 0..255. */
	public static int biomeTint(int type, int biomeId) {
		int cached = biomeTints[type][biomeId];
		if (cached != 0)
			return cached & 0xFFFFFF;
		int rgb = 0xFFFFFF;
		try {
			BiomeGenBase biome = BiomeGenBase.biomeList[biomeId];
			if (biome == null)
				biome = BiomeGenBase.plains;
			if (type == TINT_GRASS)
				rgb = biome.getBiomeGrassColor();
			else if (type == TINT_FOLIAGE)
				rgb = biome.getBiomeFoliageColor();
			else if (type == TINT_WATER)
				rgb = biome.waterColorMultiplier;
		} catch (Throwable ignored) {
		}
		rgb &= 0xFFFFFF;
		biomeTints[type][biomeId] = 0xFF000000 | rgb;
		return rgb;
	}

	/**
	 * Makes sure the table matches the current texture atlas. Cheap when nothing
	 * changed (one identity comparison per second).
	 */
	public static boolean ensureReady() {
		long now = System.currentTimeMillis();
		if (ready && now - lastProbeMs < 1000)
			return true;
		lastProbeMs = now;
		Icon probe = null;
		try {
			probe = Block.stone.getIcon(1, 0);
		} catch (Throwable ignored) {
		}
		if (ready && probe == probeIcon)
			return true;
		if (probe == null)
			return ready;
		rebuild();
		probeIcon = probe;
		ready = true;
		generation++;
		return true;
	}

	private static void rebuild() {
		Arrays.fill(colors, 0);
		Arrays.fill(tint, (byte) 0);
		Arrays.fill(skip, false);
		Arrays.fill(water, false);
		for (int[] t : biomeTints)
			Arrays.fill(t, 0);

		int[] atlas = null;
		int atlasW = 0, atlasH = 0;
		try {
			Minecraft.getMinecraft().renderEngine.bindTexture(TextureMap.locationBlocksTexture);
			atlasW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
			atlasH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
			if (atlasW > 0 && atlasH > 0 && (long) atlasW * atlasH <= 8192L * 8192L) {
				IntBuffer buf = BufferUtils.createIntBuffer(atlasW * atlasH);
				GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
				atlas = new int[atlasW * atlasH];
				buf.get(atlas);
			}
		} catch (Throwable t) {
			System.err.println("[Minimap CE] Could not read the block texture atlas, using material colours: " + t);
			atlas = null;
		}

		IdentityHashMap<Icon, Integer> iconColors = new IdentityHashMap<Icon, Integer>();
		int sampled = 0;

		for (int id = 1; id < Block.blocksList.length && id < 4096; id++) {
			Block block = Block.blocksList[id];
			if (block == null) {
				skip[id] = true;
				continue;
			}
			Material mat = block.blockMaterial;
			if (mat == null || mat == Material.air) {
				skip[id] = true;
				continue;
			}
			// Clutter that hides the ground without adding information.
			if (block instanceof BlockTallGrass || block instanceof BlockTorch || block instanceof BlockFire) {
				skip[id] = true;
				continue;
			}
			String className = block.getClass().getSimpleName();
			if (className.contains("Torch")) {
				skip[id] = true;
				continue;
			}

			boolean isWater = mat == Material.water;
			boolean isGrass = block instanceof BlockGrass || className.contains("Grass");
			boolean isLeaves = block instanceof BlockLeavesBase || className.contains("Leaves");
			boolean isBloodWood = isLeaves && className.contains("BloodWood");
			boolean isRedstoneWire = block instanceof BlockRedstoneWire;
			water[id] = isWater;

			int materialColor = 0;
			try {
				if (mat.materialMapColor != null)
					materialColor = mat.materialMapColor.colorValue & 0xFFFFFF;
			} catch (Throwable ignored) {
			}

			int meta0Color = 0;
			boolean any = false;
			for (int meta = 0; meta < 16; meta++) {
				int state = (id << 4) | meta;
				int rgb = 0;
				if (atlas != null) {
					try {
						Icon icon = block.getIcon(1, meta);
						if (icon != null) {
							Integer known = iconColors.get(icon);
							if (known == null) {
								known = averageIcon(atlas, atlasW, atlasH, icon);
								iconColors.put(icon, known);
							}
							rgb = known;
						}
					} catch (Throwable ignored) {
						// Some blocks only accept a subset of metadata values.
					}
				}
				if (rgb == 0)
					rgb = meta0Color;
				if (rgb == 0)
					rgb = materialColor;
				if (meta == 0)
					meta0Color = rgb;
				if (rgb == 0)
					continue;

				int tintType = TINT_NONE;
				if (isWater) {
					tintType = TINT_WATER;
				} else if (isBloodWood) {
					rgb = MapShade.multiply(rgb, 0xD81F1F);
				} else if (isGrass) {
					tintType = TINT_GRASS;
				} else if (block instanceof BlockVine) {
					tintType = TINT_FOLIAGE;
				} else if (block instanceof BlockLilyPad) {
					rgb = MapShade.multiply(rgb, 0x208030);
				} else if (isRedstoneWire) {
					rgb = MapShade.multiply(rgb, 0xA01010);
				} else if (isLeaves) {
					int variant = meta & 3;
					if (block instanceof BlockLeaves && (variant == 1 || variant == 2)) {
						// Spruce and birch use fixed colours.
						rgb = MapShade.multiply(rgb, staticTint(block, meta));
					} else {
						tintType = TINT_FOLIAGE;
					}
				} else {
					int fixed = staticTint(block, meta);
					if (fixed != 0xFFFFFF)
						rgb = MapShade.multiply(rgb, fixed);
				}

				if (rgb == 0)
					rgb = 0x010101;
				colors[state] = rgb;
				tint[state] = (byte) tintType;
				any = true;
				sampled++;
			}
			if (!any)
				skip[id] = true;
		}

		System.out.println("[Minimap CE] Block colours ready: " + sampled + " block states"
				+ (atlas != null ? " from a " + atlasW + "x" + atlasH + " atlas" : " (material colours only)"));
	}

	private static int staticTint(Block block, int meta) {
		try {
			return block.getRenderColor(meta) & 0xFFFFFF;
		} catch (Throwable t) {
			return 0xFFFFFF;
		}
	}

	/** Average colour of an icon's atlas area, ignoring (nearly) transparent texels. */
	private static int averageIcon(int[] atlas, int w, int h, Icon icon) {
		float u0 = Math.min(icon.getMinU(), icon.getMaxU());
		float u1 = Math.max(icon.getMinU(), icon.getMaxU());
		float v0 = Math.min(icon.getMinV(), icon.getMaxV());
		float v1 = Math.max(icon.getMinV(), icon.getMaxV());
		int x0 = clamp(Math.round(u0 * w), 0, w - 1);
		int y0 = clamp(Math.round(v0 * h), 0, h - 1);
		int x1 = clamp(Math.round(u1 * w), x0 + 1, w);
		int y1 = clamp(Math.round(v1 * h), y0 + 1, h);

		long r = 0, g = 0, b = 0, weight = 0;
		for (int y = y0; y < y1; y++) {
			int row = y * w;
			for (int x = x0; x < x1; x++) {
				int p = atlas[row + x];
				int a = p >>> 24;
				if (a < 32)
					continue;
				r += ((p >> 16) & 0xFF) * a;
				g += ((p >> 8) & 0xFF) * a;
				b += (p & 0xFF) * a;
				weight += a;
			}
		}
		if (weight == 0)
			return 0;
		int rgb = ((int) (r / weight) << 16) | ((int) (g / weight) << 8) | (int) (b / weight);
		return rgb == 0 ? 0x010101 : rgb;
	}

	private static int clamp(int v, int min, int max) {
		return v < min ? min : (v > max ? max : v);
	}
}
