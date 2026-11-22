package net.fabricmc.example.minimap;

import net.fabricmc.example.minimap.engine.CaveLayers;
import net.fabricmc.example.minimap.engine.MapEngine;
import net.fabricmc.example.minimap.engine.MapIO;
import net.fabricmc.example.minimap.engine.MapShade;
import net.fabricmc.example.minimap.engine.MapStorage;
import net.minecraft.src.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Renders the mod's real screens offscreen with the game's real font and
 * textures, in a stand-in world, and saves them as PNGs to look at.
 *
 *   GuiShots <output folder>
 */
public class GuiShots {
    static Minecraft mc;
    static sun.misc.Unsafe unsafe;

    static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> type) throws Exception {
        return (T) unsafe.allocateInstance(type);
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        out.mkdirs();
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        unsafe = (sun.misc.Unsafe) uf.get(null);

        // MCPatcher (built into BTW) reads its config through Fabric Loader: give the loader a game folder.
        File gameDir = new File(out, "game");
        new File(gameDir, "config").mkdirs();
        Class<?> loaderImpl = Class.forName("net.fabricmc.loader.impl.FabricLoaderImpl");
        Object loader = loaderImpl.getField("INSTANCE").get(null);
        Method setGameDir = loaderImpl.getDeclaredMethod("setGameDir", java.nio.file.Path.class);
        setGameDir.setAccessible(true);
        setGameDir.invoke(loader, gameDir.toPath().toAbsolutePath());

        Class<?> launcherBase = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
        Class<?> launcherType = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher");
        Object launcher = java.lang.reflect.Proxy.newProxyInstance(GuiShots.class.getClassLoader(), new Class<?>[] { launcherType },
                (proxy, method, margs) -> {
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) return method.getName().equals("isDevelopment");
                    if (r == int.class) return 0;
                    if (r == long.class) return 0L;
                    if (r == String.class) return "devtest";
                    if (r == net.fabricmc.api.EnvType.class) return net.fabricmc.api.EnvType.CLIENT;
                    return null;
                });
        Field launcherField = launcherBase.getDeclaredField("launcher");
        launcherField.setAccessible(true);
        launcherField.set(null, launcher);
        com.prupe.mcpatcher.MCPatcherUtils.setMinecraft(gameDir, null, "1.6.4", "devtest");

        int maxW = 1280, maxH = 720;
        Pbuffer pb = new Pbuffer(maxW, maxH, new PixelFormat(8, 24, 0), null);
        pb.makeCurrent();

        // ---- a minimal game: resources, textures, font
        mc = alloc(Minecraft.class);
        set(null, Minecraft.class, "theMinecraft", mc);
        set(mc, Minecraft.class, "mcLogAgent", new LogAgent("GuiShots", " [GuiShots]", new File(out, "gui-shots.log").getPath()));
        MetadataSerializer ms = new MetadataSerializer();
        ms.registerMetadataSectionType(new TextureMetadataSectionSerializer(), TextureMetadataSection.class);
        ms.registerMetadataSectionType(new FontMetadataSectionSerializer(), FontMetadataSection.class);
        ms.registerMetadataSectionType(new AnimationMetadataSectionSerializer(), AnimationMetadataSection.class);
        ms.registerMetadataSectionType(new PackMetadataSectionSerializer(), PackMetadataSection.class);
        SimpleReloadableResourceManager rm = new SimpleReloadableResourceManager(ms);
        ArrayList<Object> packs = new ArrayList<Object>();
        packs.add(new DefaultResourcePack(new File(out, "no-assets")));
        rm.reloadResources(packs);
        set(mc, Minecraft.class, "mcResourceManager", rm);
        TextureManager tm = new TextureManager(rm);
        mc.renderEngine = tm;
        GameSettings gs = new GameSettings();
        mc.gameSettings = gs;
        FontRenderer fr = new FontRenderer(gs, new ResourceLocation("textures/font/ascii.png"), tm, false);
        fr.onResourceManagerReload(rm);
        mc.fontRenderer = fr;

        // ---- a stand-in world and player at y 40 (inside a cave layer)
        WorldClient world = alloc(WorldClient.class);
        WorldProvider provider = alloc(WorldProviderSurface.class);
        provider.dimensionId = 0;
        set(world, World.class, "provider", provider);
        set(world, World.class, "loadedEntityList", new ArrayList<Object>());
        WorldInfo info = alloc(WorldInfo.class);
        set(world, World.class, "worldInfo", info);
        mc.theWorld = world;
        EntityClientPlayerMP player = alloc(EntityClientPlayerMP.class);
        player.posX = player.prevPosX = 40.3;
        player.posZ = player.prevPosZ = -12.6;
        player.posY = player.prevPosY = 41.6;
        player.rotationYaw = player.prevRotationYaw = 200f;
        set(player, Entity.class, "boundingBox", AxisAlignedBB.getBoundingBox(40, 40, -13, 40.6, 41.8, -12.4));
        mc.thePlayer = player;

        // ---- map data around the player
        MapStorage surface = new MapStorage(new File(out, "map"), null);
        int[] buf = new int[256];
        for (int cz = -30; cz <= 30; cz++) for (int cx = -40; cx <= 40; cx++) {
            if (cx * cx + cz * cz * 2 > 34 * 34 + VisualTestTerrain.hash(cx, cz) * 300) continue;
            for (int i = 0; i < 256; i++) buf[i] = VisualTestTerrain.tile(cx * 16 + (i & 15), cz * 16 + (i >> 4));
            surface.putChunk(cx, cz, buf);
        }
        set(MapEngine.INSTANCE, MapEngine.class, "storage", surface);
        CaveLayers layers = new CaveLayers(new File(out, "caves"));
        int[] cave = new int[256];
        for (int cz = -6; cz <= 6; cz++) for (int cx = -6; cx <= 6; cx++) {
            for (int i = 0; i < 256; i++) {
                int x = cx * 16 + (i & 15), z = cz * 16 + (i >> 4);
                boolean tunnel = Math.abs(Math.sin(x / 9.0) * 14 - z % 40) < 3 || Math.abs(x % 37) < 2 || VisualTestTerrain.fbm(x / 12.0, z / 12.0) > 0.62;
                cave[i] = tunnel ? MapShade.pack(0x7D7D7D, 38 + (int) (VisualTestTerrain.fbm(x / 7.0, z / 7.0) * 4)) : MapShade.pack(0x2A2A2A, 0);
            }
            layers.record(cx, cz, 40, cave);
        }
        layers.record(0, 0, 70, cave);
        for (int i = 0; i < 30; i++) { MapIO.flush(1000); layers.tick(); }
        set(MapEngine.INSTANCE, MapEngine.class, "caveLayers", layers);
        set(MapEngine.INSTANCE, MapEngine.class, "caveStorage", MapStorage.memoryOnly());
        set(MapEngine.INSTANCE, MapEngine.class, "caveActive", Boolean.TRUE);

        WaypointManager.instance.getWaypoints().clear();
        String[] names = { "Home", "Iron Mine", "Village", "Nether Portal", "Sheep Farm", "Ravine", "Desert Temple", "Lookout", "Wheat", "Ocean Monument Spot With A Very Long Name" };
        int[] colors = { 0xFF5555, 0xA8A8B3, 0x3DBF5A, 0x9966FF, 0xF2F2F2, 0xFF9933, 0xFFE14D, 0x4DD2FF, 0x9BE34B, 0x3F5BFF };
        for (int i = 0; i < names.length; i++) {
            Waypoint wp = new Waypoint(names[i], (int) (Math.cos(i * 1.7) * (60 + i * 25)), 64 + i, (int) (Math.sin(i * 1.7) * (60 + i * 25)), colors[i]);
            wp.enabled = i != 5;
            WaypointManager.instance.getWaypoints().add(wp);
        }
        set(SpawnTracker.instance, SpawnTracker.class, "hasSpawn", Boolean.TRUE);
        set(SpawnTracker.instance, SpawnTracker.class, "spawnX", 8);
        set(SpawnTracker.instance, SpawnTracker.class, "spawnY", 66);
        set(SpawnTracker.instance, SpawnTracker.class, "spawnZ", -20);

        MapConfig cfg = MapConfig.instance;
        cfg.showBiome = false; // needs real chunks
        cfg.showEntities = false;

        int[][] sizes = { { 1280, 720, 2 }, { 854, 480, 2 }, { 1280, 720, 3 } };
        for (int[] size : sizes) {
            String tag = size[0] + "x" + size[1] + "s" + size[2];
            gs.guiScale = size[2];
            mc.displayWidth = size[0];
            mc.displayHeight = size[1];

            shoot(new GuiFullscreenMap(), size, new File(out, "fullscreen-" + tag + ".png"), 300, 200, null);
            GuiFullscreenMap menuShot = new GuiFullscreenMap();
            shoot(menuShot, size, new File(out, "fullscreen-menu-" + tag + ".png"), 300, 200, menuShot);
            shoot(new GuiWaypoints(), size, new File(out, "waypoints-" + tag + ".png"), 120, 92, null);
            shoot(new GuiWaypointEdit(null, WaypointManager.instance.getWaypoints().get(1), false), size,
                    new File(out, "waypoint-edit-" + tag + ".png"), 0, 0, null);
            for (int page = 0; page < 3; page++) {
                set(null, GuiMapSettings.class, "lastPage", page);
                shoot(new GuiMapSettings(null), size, new File(out, "settings" + page + "-" + tag + ".png"), -1, -1, null);
            }
        }
        // ---- the in-game HUD: minimap with mob faces (a pen of pigs, cows at the edge) and waypoint labels
        set(MapEngine.INSTANCE, MapEngine.class, "caveActive", Boolean.FALSE);
        cfg.showEntities = true;
        cfg.entityIconStyle = MapConfig.ENTITY_HEADS;
        // Stand-in renderers that only name the plain skin (BTW's own pick skins by hunger, which a fake mob lacks).
        Field mapField = RenderManager.class.getDeclaredField("entityRenderMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Class<?>, Render> renderMap = (java.util.Map<Class<?>, Render>) mapField.get(RenderManager.instance);
        renderMap.put(EntityPig.class, skin("textures/entity/pig/pig.png"));
        renderMap.put(EntityCow.class, skin("textures/entity/cow/cow.png"));
        renderMap.put(EntityChicken.class, skin("textures/entity/chicken.png"));
        java.util.List<Object> entities = (java.util.List<Object>) world.loadedEntityList;
        for (int i = 0; i < 9; i++) entities.add(mob(EntityPig.class, 52 + (i % 3) * 1.1, -20 + (i / 3) * 1.2, 41)); // a pen
        entities.add(mob(EntityPig.class, 62, -2, 41));
        entities.add(mob(EntityCow.class, 40.3 + 61, -12.6, 41));      // on the east edge
        entities.add(mob(EntityCow.class, 40.3, -12.6 - 63.5, 41));    // on the north edge
        entities.add(mob(EntityCow.class, 40.3 - 30, -12.6 + 24, 41));
        entities.add(mob(EntityChicken.class, 30, -30, 41));
        entities.add(mob(EntityChicken.class, 31, -29.5, 41));
        entities.add(mob(EntityChicken.class, 22, -6, 60));            // far above: darker
        // MCPatcher's random mob skins look at the biome under the mob: give the stand-in world plains chunks.
        final Chunk plains = new Chunk(world, 0, 0);
        Arrays.fill(plains.getBiomeArray(), (byte) BiomeGenBase.plains.biomeID);
        IChunkProvider chunks = (IChunkProvider) java.lang.reflect.Proxy.newProxyInstance(GuiShots.class.getClassLoader(),
                new Class<?>[] { IChunkProvider.class }, (proxy, method, margs) -> {
                    Class<?> r = method.getReturnType();
                    if (r == Chunk.class) return plains;
                    if (r == boolean.class) return method.getName().equals("chunkExists");
                    if (r == int.class) return 0;
                    if (r == String.class) return "devtest";
                    return null;
                });
        set(world, World.class, "chunkProvider", chunks);
        Entity probe = (Entity) entities.get(0);
        try {
            Render r = RenderManager.instance.getEntityRenderObject(probe);
            ResourceLocation base = r.getEntityTexture(probe);
            System.out.println("radar skin for a pig: " + base + " -> "
                    + com.prupe.mcpatcher.mob.MobRandomizer.randomTexture((EntityLivingBase) probe, base));
        } catch (Throwable t) {
            System.out.println("radar skin lookup failed: " + t);
            t.printStackTrace(System.out);
        }
        for (int[] size : new int[][] { { 1280, 720, 2 }, { 1280, 720, 3 } }) {
            gs.guiScale = size[2];
            mc.displayWidth = size[0];
            mc.displayHeight = size[1];
            // The second shot shows names on every label (the "Full" setting).
            cfg.worldWaypointLabels = size[2] == 3 ? MapConfig.WORLD_LABELS_FULL : MapConfig.WORLD_LABELS_ICONS;
            hudShot(size, new File(out, "hud-" + size[0] + "x" + size[1] + "s" + size[2] + ".png"), world, player);
        }

        System.out.println("GL error: " + GL11.glGetError());
        pb.destroy();
        System.exit(0);
    }

    static Render skin(final String path) {
        final ResourceLocation texture = new ResourceLocation(path);
        return new Render() {
            @Override
            public void doRender(Entity e, double x, double y, double z, float yaw, float partialTicks) {
            }

            @Override
            public ResourceLocation getEntityTexture(Entity e) {
                return texture;
            }
        };
    }

    static Entity mob(Class<? extends Entity> type, double x, double z, double y) throws Exception {
        Entity e = alloc(type);
        e.posX = e.prevPosX = x;
        e.posZ = e.prevPosZ = z;
        e.posY = e.prevPosY = y;
        DataWatcher dw = new DataWatcher();
        dw.addObject(0, Byte.valueOf((byte) 0));
        set(e, Entity.class, "dataWatcher", dw);
        return e;
    }

    /** The world behind (sky, grass), waypoint labels from a real perspective camera, then the minimap. */
    static void hudShot(int[] size, File file, World world, EntityPlayer player) throws Exception {
        int W = size[0], H = size[1];
        ScaledResolution sr = new ScaledResolution(mc.gameSettings, W, H);
        int gw = sr.getScaledWidth(), gh = sr.getScaledHeight();
        MinimapRenderer renderer = new MinimapRenderer();
        for (int frame = 0; frame < 8; frame++) {
            GL11.glViewport(0, 0, W, H);
            GL11.glClearColor(0.42f, 0.58f, 0.80f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            // A dusk sky and dark ground behind everything, like the world at night.
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0D, sr.getScaledWidth_double(), sr.getScaledHeight_double(), 0.0D, 1000.0D, 3000.0D);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
            Gui.drawRect(0, 0, gw, gh, 0xFF141826);
            Gui.drawRect(0, gh * 3 / 5, gw, gh, 0xFF2C3A26);
            // The camera of the world pass: the player's eyes, looking along their yaw, a little up.
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            double near = 0.05, top = near * Math.tan(Math.toRadians(70) / 2), right = top * W / H;
            GL11.glFrustum(-right, right, -top, top, near, 512);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glRotatef(-12f, 1, 0, 0);
            GL11.glRotatef(player.rotationYaw + 180f, 0, 1, 0);
            // The real world pass: beams drawn, camera remembered.
            RenderManager.renderPosX = player.posX;
            RenderManager.renderPosY = player.posY + 1.62;
            RenderManager.renderPosZ = player.posZ;
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            WaypointBeamRenderer.renderAll(1.0f);

            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0D, sr.getScaledWidth_double(), sr.getScaledHeight_double(), 0.0D, 1000.0D, 3000.0D);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glShadeModel(GL11.GL_FLAT);
            MapEngine.INSTANCE.storage().beginFrame();
            WaypointBeamRenderer.renderHud(mc, gw, gh, sr.getScaleFactor());
            renderer.renderMinimap(mc, world, player, gw, gh, sr.getScaleFactor(), 1.0f);
            MapIO.flush(500);
            MapEngine.INSTANCE.storage().tick();
        }
        GL11.glFinish();
        IntBuffer buf = BufferUtils.createIntBuffer(W * H);
        GL11.glReadPixels(0, 0, W, H, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[W];
        for (int y = 0; y < H; y++) { buf.position((H - 1 - y) * W); buf.get(row); img.setRGB(0, y, W, 1, row, 0, W); }
        ImageIO.write(img, "png", file);
        int err = GL11.glGetError();
        System.out.println("wrote " + file.getName() + (err != 0 ? "  GL ERROR " + err : ""));
    }

    static void shoot(GuiScreen screen, int[] size, File file, int mouseX, int mouseY, GuiFullscreenMap openMenuOn) throws Exception {
        int W = size[0], H = size[1];
        ScaledResolution sr = new ScaledResolution(mc.gameSettings, W, H);
        int gw = sr.getScaledWidth(), gh = sr.getScaledHeight();
        screen.setWorldAndResolution(mc, gw, gh);
        if (screen instanceof GuiMapSettings && mouseX < 0) {
            // hover the second option of the page so its help text shows
            Field bl = GuiScreen.class.getDeclaredField("buttonList");
            bl.setAccessible(true);
            java.util.List<?> buttons = (java.util.List<?>) bl.get(screen);
            GuiButton b = (GuiButton) buttons.get(4);
            mouseX = b.xPosition + 5;
            mouseY = b.yPosition + 5;
        }
        for (int frame = 0; frame < 8; frame++) {
            GL11.glViewport(0, 0, W, H);
            GL11.glClearColor(0.42f, 0.58f, 0.80f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            // A fake world view behind the GUI: sky and grass.
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0D, sr.getScaledWidth_double(), sr.getScaledHeight_double(), 0.0D, 1000.0D, 3000.0D);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
            Gui.drawRect(0, gh * 3 / 5, gw, gh, 0xFF4F7A33);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glShadeModel(GL11.GL_FLAT);
            if (frame == 6 && openMenuOn != null) {
                Method open = GuiFullscreenMap.class.getDeclaredMethod("openMenu", int.class, int.class);
                open.setAccessible(true);
                open.invoke(openMenuOn, gw / 2 + 10, gh / 2 - 20);
            }
            screen.drawScreen(mouseX, mouseY, 1.0f);
            screen.updateScreen();
            MapIO.flush(500);
            MapEngine.INSTANCE.storage().tick();
        }
        GL11.glFinish();
        IntBuffer buf = BufferUtils.createIntBuffer(W * H);
        GL11.glReadPixels(0, 0, W, H, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[W];
        for (int y = 0; y < H; y++) { buf.position((H - 1 - y) * W); buf.get(row); img.setRGB(0, y, W, 1, row, 0, W); }
        ImageIO.write(img, "png", file);
        int err = GL11.glGetError();
        System.out.println("wrote " + file.getName() + (err != 0 ? "  GL ERROR " + err : ""));
    }
}
