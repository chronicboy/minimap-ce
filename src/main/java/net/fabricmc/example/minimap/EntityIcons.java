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
	/** A face rectangle, in texels of a texture that is {@code unitsWide} texels wide. */
	static final class Head {
		final float unitsWide;
		final int x, y, w, h;
		/** Second layer drawn on top (player hats), or -1. */
		final int overlayX, overlayY;

		Head(float unitsWide, int x, int y, int w, int h, int overlayX, int overlayY) {
			this.unitsWide = unitsWide;
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.overlayX = overlayX;
			this.overlayY = overlayY;
		}

		Head(int x, int y, int w, int h) {
			this(64.0f, x, y, w, h, -1, -1);
		}
	}

	private static final Head NONE = new Head(0, 0, 0, 0);
	private static final Head CUBE_HEAD = new Head(8, 8, 8, 8);
	/** Zombie villagers share EntityZombie but wear a villager-shaped head lower in their skin. */
	private static final Head ZOMBIE_VILLAGER = new Head(8, 40, 8, 10);

	/** First match wins, so subclasses that need different treatment come before their parents. */
	private static final Object[][] RULES = {
			{ EntityPlayer.class, new Head(64.0f, 8, 8, 8, 8, 40, 8) },
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
			{ EntityIronGolem.class, new Head(128.0f, 8, 8, 8, 10, -1, -1) },
			{ EntityGhast.class, new Head(16, 16, 16, 16) },
			// Horses, donkeys and mules: the side of the head (eye and cheek) reads best at this size.
			{ EntityHorse.class, new Head(0, 20, 7, 5) },
			{ DireWolfEntity.class, new Head(4, 4, 6, 6) },
	};

	private static final HashMap<Class<?>, Head> byClass = new HashMap<Class<?>, Head>();
	/** Texture height / width, looked up once per texture. */
	private static final HashMap<ResourceLocation, Float> aspects = new HashMap<ResourceLocation, Float>();

	private EntityIcons() {
	}

	/** Head layout for an entity class, or null if it has none. */
	static Head forClass(Class<?> type) {
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
		aspects.clear();
	}

	/**
	 * Draws an entity's face with its top-left corner at (left, top), at
	 * {@link #iconWidth} x {@link #iconHeight}. Expects texturing and blending
	 * enabled and the colour set to white.
	 *
	 * @param texture the skin from {@link #textureOf}
	 */
	static void drawHead(Minecraft mc, Tessellator t, ResourceLocation texture, Head head, double left, double top) {
		mc.renderEngine.bindTexture(texture);

		Float aspect = aspects.get(texture);
		if (aspect == null) {
			int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
			int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
			aspect = (w > 0 && h > 0) ? (float) h / (float) w : 0.5f;
			aspects.put(texture, aspect);
		}
		// The table is in texels of a texture "unitsWide" across; its height follows the real image's shape.
		double unitsHigh = head.unitsWide * aspect;
		double right = left + iconWidth(head), bottom = top + iconHeight(head);

		t.startDrawingQuads();
		addFace(t, left, top, right, bottom, head.x, head.y, head.w, head.h, head.unitsWide, unitsHigh);
		if (head.overlayX >= 0)
			addFace(t, left, top, right, bottom, head.overlayX, head.overlayY, head.w, head.h, head.unitsWide,
					unitsHigh);
		t.draw();
	}

	/**
	 * GUI pixels per face texel. Every face uses the same density (one texel per GUI
	 * pixel, so it lands exactly on screen pixels at any GUI scale); only unusually
	 * big faces (the ghast) are shrunk.
	 */
	private static double unit(Head head) {
		int largest = Math.max(head.w, head.h);
		return largest <= 10 ? 1.0 : 8.0 / largest;
	}

	static double iconWidth(Head head) {
		return head.w * unit(head);
	}

	static double iconHeight(Head head) {
		return head.h * unit(head);
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
