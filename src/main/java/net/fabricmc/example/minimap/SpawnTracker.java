package net.fabricmc.example.minimap;

import net.minecraft.src.Minecraft;
import net.minecraft.src.WorldInfo;

import java.io.*;
import java.util.Properties;

/**
 * Saves and retrieves the overworld spawn point once per world.
 * The spawn is captured on first overworld load and never changes.
 */
public class SpawnTracker {
    public static final SpawnTracker instance = new SpawnTracker();

    private static final String SPAWN_DIR = "config/minimap-ce-waypoints";

    private int spawnX, spawnY, spawnZ;
    private boolean hasSpawn = false;
    private String currentWorldPrefix = "";

    /**
     * Called when entering a world. Loads saved spawn or captures it from
     * overworld.
     */
    public void onWorldLoad(Minecraft mc) {
        String prefix = getWorldPrefix(mc);
        if (prefix.isEmpty())
            return;

        // Only re-initialize if the world changed
        if (prefix.equals(currentWorldPrefix) && hasSpawn)
            return;

        currentWorldPrefix = prefix;
        hasSpawn = false;

        // Try loading saved spawn
        File file = getSpawnFile();
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                Properties props = new Properties();
                props.load(fis);
                spawnX = Integer.parseInt(props.getProperty("spawnX", "0"));
                spawnY = Integer.parseInt(props.getProperty("spawnY", "64"));
                spawnZ = Integer.parseInt(props.getProperty("spawnZ", "0"));
                hasSpawn = true;
                System.out.println("[Minimap CE] Loaded saved spawn: " + spawnX + ", " + spawnY + ", " + spawnZ);
                return;
            } catch (Exception e) {
                System.err.println("[Minimap CE] Failed to load spawn: " + e.getMessage());
            }
        }

        // No saved spawn — capture from overworld
        captureSpawn(mc);
    }

    /**
     * Captures the overworld spawn and saves it. Called once.
     */
    private void captureSpawn(Minecraft mc) {
        WorldInfo overworldInfo = null;
        if (mc.getIntegratedServer() != null && mc.getIntegratedServer().worldServers != null
                && mc.getIntegratedServer().worldServers.length > 0) {
            overworldInfo = mc.getIntegratedServer().worldServers[0].getWorldInfo();
        }
        if (overworldInfo == null && mc.theWorld != null) {
            overworldInfo = mc.theWorld.getWorldInfo();
        }
        if (overworldInfo == null)
            return;

        spawnX = overworldInfo.getSpawnX();
        spawnZ = overworldInfo.getSpawnZ();

        // Get surface Y from overworld
        if (mc.getIntegratedServer() != null && mc.getIntegratedServer().worldServers != null
                && mc.getIntegratedServer().worldServers.length > 0) {
            net.minecraft.src.WorldServer overworld = mc.getIntegratedServer().worldServers[0];
            int h = overworld.getHeightValue(spawnX, spawnZ);
            // Search down from heightmap to find solid ground (skip leaves, air, plants)
            spawnY = h > 0 ? h - 1 : overworldInfo.getSpawnY();
            for (int y = h; y > 0; y--) {
                int id = overworld.getBlockId(spawnX, y, spawnZ);
                if (id == 0) continue;
                net.minecraft.src.Block b = net.minecraft.src.Block.blocksList[id];
                if (b != null && b.blockMaterial != net.minecraft.src.Material.air 
                    && b.blockMaterial != net.minecraft.src.Material.leaves 
                    && b.blockMaterial != net.minecraft.src.Material.plants) {
                    spawnY = y;
                    break;
                }
            }
        } else {
            spawnY = overworldInfo.getSpawnY();
        }

        hasSpawn = true;
        save();
        System.out.println("[Minimap CE] Captured spawn point: " + spawnX + ", " + spawnY + ", " + spawnZ);
    }

    public void onWorldLeave() {
        currentWorldPrefix = "";
        hasSpawn = false;
    }

    public int getSpawnX() {
        return spawnX;
    }

    public int getSpawnY() {
        return spawnY;
    }

    public int getSpawnZ() {
        return spawnZ;
    }

    public boolean hasSpawn() {
        return hasSpawn;
    }

    /** Get nether-scaled X. */
    public int getNetherSpawnX() {
        return spawnX / 8;
    }

    /** Get nether-scaled Z. */
    public int getNetherSpawnZ() {
        return spawnZ / 8;
    }

    private void save() {
        File dir = new File(SPAWN_DIR);
        if (!dir.exists())
            dir.mkdirs();

        Properties props = new Properties();
        props.setProperty("spawnX", String.valueOf(spawnX));
        props.setProperty("spawnY", String.valueOf(spawnY));
        props.setProperty("spawnZ", String.valueOf(spawnZ));

        try (FileOutputStream fos = new FileOutputStream(getSpawnFile())) {
            props.store(fos, "Minimap CE Spawn Point");
        } catch (IOException e) {
            System.err.println("[Minimap CE] Failed to save spawn: " + e.getMessage());
        }
    }

    private File getSpawnFile() {
        return new File(SPAWN_DIR + "/" + currentWorldPrefix + "_spawn.properties");
    }

    private String getWorldPrefix(Minecraft mc) {
        if (mc.theWorld == null)
            return "";
        return WaypointManager.getWorldPrefix(mc.theWorld);
    }

}
