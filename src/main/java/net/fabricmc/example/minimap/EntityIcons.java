package net.fabricmc.example.minimap;

import com.prupe.mcpatcher.mob.MobRandomizer;
import net.minecraft.src.AbstractClientPlayer;
import net.minecraft.src.Entity;
import net.minecraft.src.EntityBlaze;
import net.minecraft.src.EntityChicken;
import net.minecraft.src.EntityCow;
import net.minecraft.src.EntityCreeper;
import net.minecraft.src.EntityEnderman;
import net.minecraft.src.EntityGhast;
import net.minecraft.src.EntityHorse;
import net.minecraft.src.EntityIronGolem;
import net.minecraft.src.EntityLivingBase;
import net.minecraft.src.EntityMagmaCube;
import net.minecraft.src.EntityOcelot;
import net.minecraft.src.EntityPig;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntitySheep;
import net.minecraft.src.EntitySkeleton;
import net.minecraft.src.EntitySlime;
import net.minecraft.src.EntitySnowman;
import net.minecraft.src.EntitySpider;
import net.minecraft.src.EntityVillager;
import net.minecraft.src.EntityWitch;
import net.minecraft.src.EntityWolf;
import net.minecraft.src.EntityZombie;
import net.minecraft.src.Minecraft;
import net.minecraft.src.ModelBiped;
import net.minecraft.src.Render;
import net.minecraft.src.RendererLivingEntity;
import net.minecraft.src.RenderManager;
import net.minecraft.src.ResourceLocation;
import net.minecraft.src.Tessellator;
import btw.entity.mob.DireWolfEntity;
import net.fabricmc.example.minimap.engine.SharpShader;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;

/**
 * Mob head icons for the entity radar: the face is cut straight out of the
 * mob's own skin texture, so it follows resource packs and random-mob skins.
 *
 * Where the face sits depends on the mob's model, so there is a table of known
 * layouts (every vanilla and BTW mob the radar shows). Unknown mobs drawn with
 * a humanoid model use the standard head; anything else gets a coloured dot.
 * The layouts are checked against the game's real skins by devtest's HeadSheet.
 */
final class EntityIcons {
	/**
	 * An icon cut from a skin: one or more texture rectangles laid out on a small
	 * canvas. Coordinates are texels of a texture {@code unitsWide} texels across.
	 */
	static final class Head {
		final float unitsWide;
		/** Icon size in texels. */
		final int w, h;
		/** One entry per piece, drawn in order: {texX, texY, width, height, iconX, iconY}. */
		final int[][] parts;
		/** Drawn from model boxes instead (null for flat faces). */
		final ModelIcon model;

		Head(float unitsWide, int w, int h, int[]... parts) {
			this.unitsWide = unitsWide;
			this.w = w;
			this.h = h;
			this.parts = parts;
			this.model = null;
		}

		/** An icon drawn from model boxes. */
		Head(ModelIcon model) {
			this.unitsWide = 0;
			this.w = 0;
			this.h = 0;
			this.parts = new int[0][];
			this.model = model;
		}

		/** A single face rectangle of a 64-texel-wide skin. */
		Head(int x, int y, int w, int h) {
			this(64.0f, w, h, new int[] { x, y, w, h, 0, 0 });
		}
	}

	private static final Head NONE = new Head(0, 0, 0, 0);
	private static final Head CUBE_HEAD = new Head(8, 8, 8, 8);
	/** Zombie villagers share EntityZombie but wear a villager-shaped head lower in their skin. */
	private static final Head ZOMBIE_VILLAGER = new Head(8, 40, 8, 10);

	/** First match wins, so subclasses that need different treatment come before their parents. */
	private static final Object[][] RULES = {
			// Face plus the hat layer of the skin.
			{ EntityPlayer.class, new Head(64.0f, 8, 8, new int[] { 8, 8, 8, 8, 0, 0 }, new int[] { 40, 8, 8, 8, 0, 0 }) },
			{ EntityMagmaCube.class, NONE }, // body is built from slices, no single face
			{ EntitySlime.class, CUBE_HEAD },
			{ EntityZombie.class, CUBE_HEAD }, // includes zombie pigmen
			{ EntitySkeleton.class, CUBE_HEAD },
			{ EntityCreeper.class, CUBE_HEAD },
			{ EntityEnderman.class, CUBE_HEAD },
			{ EntityBlaze.class, CUBE_HEAD },
			{ EntitySnowman.class, CUBE_HEAD },
			{ EntityPig.class, CUBE_HEAD },
			{ EntitySpider.class, new Head(40, 12, 8, 8) }, // includes cave spiders
			{ EntityCow.class, new Head(6, 6, 8, 8) }, // includes mooshrooms
			{ EntitySheep.class, new Head(8, 8, 6, 6) },
			{ EntityChicken.class, new Head(3, 3, 4, 6) },
			{ EntityWolf.class, new Head(4, 4, 6, 6) },
			{ EntityOcelot.class, new Head(5, 5, 5, 4) },
			{ EntityVillager.class, new Head(8, 8, 8, 10) },
			{ EntityWitch.class, new Head(8, 8, 8, 10) },
			{ EntityIronGolem.class, new Head(128.0f, 8, 10, new int[] { 8, 8, 8, 10, 0, 0 }) },
			{ EntityGhast.class, new Head(16, 16, 16, 16) },
			{ DireWolfEntity.class, new Head(4, 4, 6, 6) },
	};

	private static Head horseHead;
	private static Head donkeyHead;

	/** View of the horse icon: the long face tilted towards the viewer (blaze and muzzle showing), turned a little to show one eye. */
	static double horseYaw = Math.toRadians(20), horsePitch = 0.0, horseTilt = Math.toRadians(70);
	/** Model icons are scaled to this many GUI pixels at their widest. */
	static double modelIconSize = 12.0;

	/**
	 * Head, muzzle, the top of the mane and the ears of BTW's ModelHorse (64 x 64 texture), tilted
	 * as the model carries it. Donkeys and mules have the long ears.
	 */
	static Head horseHead(boolean donkey) {
		if (donkey ? donkeyHead == null : horseHead == null) {
			double[] head = { 0, 13, -3, -11, -2, 6, 5, 7, 0, 0 };
			double[] mouthTop = { 0, 25, -2, -11, -7, 4, 3, 5, 0, 0 };
			double[] mouthBottom = { 0, 28, -2, -8, -7, 4, 2, 5, 0, 0 };
			double[] mane = { 56, 36, -1, -11, 5.01, 2, 5, 2, 0, 0 };
			// The face is turned towards the viewer, which would point the ears at the viewer too; each ear
			// is stood back up about its own base, a little apart and splayed like the model's.
			double earX = -(horseTilt + Math.toRadians(10));
			double[] leftEar = donkey ? new double[] { 0, 12, 0.15, -17, 5, 2, 7, 1, earX, 0.20, 1.15, -10, 5.5 }
					: new double[] { 19, 16, 0.6, -13, 5, 2, 3, 1, earX, 0.15, 1.6, -10, 5.5 };
			double[] rightEar = donkey ? new double[] { 0, 12, -2.15, -17, 5, 2, 7, 1, earX, -0.20, -1.15, -10, 5.5 }
					: new double[] { 19, 16, -2.6, -13, 5, 2, 3, 1, earX, -0.15, -1.6, -10, 5.5 };
			Head h = new Head(new ModelIcon(64, 64, horseTilt, horseYaw, horsePitch, mane, leftEar, rightEar, head,
					mouthTop, mouthBottom));
			if (donkey)
				donkeyHead = h;
			else
				horseHead = h;
		}
		return donkey ? donkeyHead : horseHead;
	}

	/** For devtest: forget the built model icons so a changed view takes effect. */
	static void rebuildModelIcons() {
		horseHead = null;
		donkeyHead = null;
	}

	private static final HashMap<Class<?>, Head> byClass = new HashMap<Class<?>, Head>();
	/** Texture size {width, height} in texels, looked up once per texture. */
	private static final HashMap<ResourceLocation, int[]> sizes = new HashMap<ResourceLocation, int[]>();

	private EntityIcons() {
	}

	/** Head layout for an entity class, or null if it has none. */
	static Head forClass(Class<?> type) {
		if (EntityHorse.class.isAssignableFrom(type))
			return horseHead(false); // no flat face (the eyes are on the sides of a long head): drawn from the model
		Head head = byClass.get(type);
		if (head == null) {
			head = NONE;
			for (Object[] rule : RULES) {
				if (((Class<?>) rule[0]).isAssignableFrom(type)) {
					head = (Head) rule[1];
					break;
				}
			}
			byClass.put(type, head);
		}
		return head == NONE ? null : head;
	}

	/** For devtest's HeadSheet. */
	static Head zombieVillagerHead() {
		return ZOMBIE_VILLAGER;
	}

	static Head forEntity(Entity entity) {
		if (entity instanceof EntityZombie && ((EntityZombie) entity).isVillager())
			return ZOMBIE_VILLAGER;
		if (entity instanceof EntityHorse) {
			int type = ((EntityHorse) entity).getHorseType();
			return horseHead(type == 1 || type == 2); // donkeys and mules
		}
		Class<?> type = entity.getClass();
		if (!byClass.containsKey(type) && forClass(type) == null) {
			// Not in the table: a mob drawn as a humanoid has the standard head layout.
			try {
				Render render = RenderManager.instance.getEntityRenderObject(entity);
				if (render instanceof RendererLivingEntity
						&& ((RendererLivingEntity) render).mainModel instanceof ModelBiped)
					byClass.put(type, CUBE_HEAD);
			} catch (Throwable ignored) {
			}
		}
		return forClass(type);
	}

	/** The skin the game would render this entity with, or null. */
	static ResourceLocation textureOf(Entity entity) {
		try {
			if (entity instanceof AbstractClientPlayer)
				return ((AbstractClientPlayer) entity).getLocationSkin();
			Render render = RenderManager.instance.getEntityRenderObject(entity);
			if (render == null)
				return null;
			ResourceLocation texture = render.getEntityTexture(entity);
			if (texture != null && entity instanceof EntityLivingBase)
				texture = MobRandomizer.randomTexture((EntityLivingBase) entity, texture);
			return texture;
		} catch (Throwable t) {
			return null;
		}
	}

	/** Forget cached texture shapes (resource pack change). */
	static void clearCache() {
		sizes.clear();
	}

	/**
	 * Draws an entity's face with its top-left corner at (left, top), at {@link #iconWidth} x
	 * {@link #iconHeight}, framed by a dark outline one physical pixel wide. Expects texturing and
	 * blending enabled. The position may be fractional: with the sharp shader every texel stays a
	 * solid square and only its edges blend, so icons glide across the map instead of jumping
	 * from pixel to pixel.
	 *
	 * @param texture the skin from {@link #textureOf}
	 * @param shade   brightness (1 = normal)
	 * @param pixel   one physical pixel in GUI units
	 * @param sharp   true when the sharp shader is active (the texture is then filtered linearly)
	 */
	static void drawHead(Minecraft mc, Tessellator t, ResourceLocation texture, Head head, double left, double top,
			float shade, double pixel, boolean sharp) {
		mc.renderEngine.bindTexture(texture);
		int[] size = sizes.get(texture);
		if (size == null) {
			int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
			int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
			size = new int[] { w > 0 ? w : 64, h > 0 ? h : 32 };
			sizes.put(texture, size);
		}
		drawBound(t, head, size[0], size[1], left, top, shade, pixel, sharp);
	}

	/** {@link #drawHead} for a texture that is already bound, of the given size in texels. */
	static void drawBound(Tessellator t, Head head, int texW, int texH, double left, double top, float shade,
			double pixel, boolean sharp) {
		int minFilter = 0, magFilter = 0;
		if (sharp) {
			minFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
			magFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			SharpShader.setTextureSize(texW, texH);
		}
		try {
			if (head.model != null) {
				head.model.draw(t, left, top, modelScale(head), shade, pixel);
				return;
			}
			// The table is in texels of a texture "unitsWide" across; its height follows the real image's shape.
			double unitsHigh = head.unitsWide * texH / (double) texW;
			double u = unit(head);
			int[] base = head.parts[0];
			t.startDrawingQuads();
			// Outline: the base face stretched one pixel further each way and drawn dark.
			t.setColorRGBA_F(0.02f, 0.02f, 0.03f, 1.0f);
			addFace(t, left - pixel, top - pixel, left + head.w * u + pixel, top + head.h * u + pixel, base[0], base[1],
					base[2], base[3], head.unitsWide, unitsHigh);
			t.setColorRGBA_F(shade, shade, shade, 1.0f);
			for (int[] part : head.parts) {
				double x0 = left + part[4] * u, y0 = top + part[5] * u;
				addFace(t, x0, y0, x0 + part[2] * u, y0 + part[3] * u, part[0], part[1], part[2], part[3],
						head.unitsWide, unitsHigh);
			}
			t.draw();
		} finally {
			if (sharp) {
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter);
			}
		}
	}

	/**
	 * GUI pixels per face texel. Every face uses the same density (one texel per GUI
	 * pixel, so it lands exactly on screen pixels at any GUI scale); only unusually
	 * big faces (the ghast) are shrunk.
	 */
	private static double unit(Head head) {
		int largest = Math.max(head.w, head.h);
		return largest <= 12 ? 1.0 : 8.0 / largest;
	}

	private static double modelScale(Head head) {
		return modelIconSize / Math.max(head.model.width, head.model.height);
	}

	static double iconWidth(Head head) {
		return head.model != null ? head.model.width * modelScale(head) : head.w * unit(head);
	}

	static double iconHeight(Head head) {
		return head.model != null ? head.model.height * modelScale(head) : head.h * unit(head);
	}

	private static void addFace(Tessellator t, double x0, double y0, double x1, double y1, int tx, int ty, int tw,
			int th, double unitsWide, double unitsHigh) {
		double u0 = tx / unitsWide, u1 = (tx + tw) / unitsWide;
		double v0 = ty / unitsHigh, v1 = (ty + th) / unitsHigh;
		t.addVertexWithUV(x0, y1, 0.0, u0, v1);
		t.addVertexWithUV(x1, y1, 0.0, u1, v1);
		t.addVertexWithUV(x1, y0, 0.0, u1, v0);
		t.addVertexWithUV(x0, y0, 0.0, u0, v0);
	}
}
