package net.fabricmc.example.minimap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.src.Minecraft;
import net.minecraft.src.World;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class WaypointManager {
    public static final WaypointManager instance = new WaypointManager();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Waypoint> waypoints = new ArrayList<>();
    private String currentWorldId = "";
    private static final String WAYPOINT_DIR = "config/minimap-ce-waypoints";

    public List<Waypoint> getWaypoints() {
        return waypoints;
    }

    public void addWaypoint(Waypoint wp) {
        waypoints.add(wp);
        save();
    }

    public void removeWaypoint(int index) {
        if (index >= 0 && index < waypoints.size()) {
            waypoints.remove(index);
            save();
        }
    }

    public void removeWaypoint(Waypoint wp) {
        waypoints.remove(wp);
        save();
    }

    public void toggleWaypoint(int index) {
        if (index >= 0 && index < waypoints.size()) {
            Waypoint wp = waypoints.get(index);
            wp.enabled = !wp.enabled;
            save();
        }
    }

    public static String buildWorldId(World world) {
        if (world == null || world.getWorldInfo() == null || world.provider == null)
            return "";

        int dimension = world.provider.dimensionId;
        String prefix = getWorldPrefix(world);

        return prefix + "_dim" + dimension;
    }

    public static String getWorldPrefix(World world) {
        if (world == null) return "";
        Minecraft mc = Minecraft.getMinecraft();

        String prefix;
        if (mc.isSingleplayer() && mc.getIntegratedServer() != null) {
            String folderName = mc.getIntegratedServer().getFolderName();
            // Get seed from the server-side world to ensure it's accurate and available
            long seed = 0;
            try {
                seed = mc.getIntegratedServer().worldServerForDimension(0).getSeed();
            } catch (Exception e) {
                seed = world.getSeed(); // Fallback
            }
            // Include hex seed to differentiate between worlds with the same folder name
            prefix = "sp_" + sanitizeFilename(folderName) + "_" + Long.toHexString(seed);
        } else {
            String serverIP = "unknown";
            if (mc.currentServerData != null && mc.currentServerData.serverIP != null) {
                serverIP = mc.currentServerData.serverIP;
            } else if (world.getWorldInfo() != null) {
                serverIP = world.getWorldInfo().getWorldName();
            }
            prefix = "mp_" + sanitizeFilename(serverIP);
        }
        return prefix;
    }

    public void loadForWorld(World world) {
        if (!currentWorldId.isEmpty() && !waypoints.isEmpty()) {
            save();
        }

        waypoints.clear();
        currentWorldId = buildWorldId(world);

        if (currentWorldId.isEmpty()) {
            return;
        }

        File file = getWaypointFile();
        if (!file.exists()) {
            System.out.println("[Minimap CE] No waypoints file for world: " + currentWorldId);
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            Type listType = new TypeToken<List<Waypoint>>() {
            }.getType();
            List<Waypoint> loaded = gson.fromJson(reader, listType);
            if (loaded != null) {
                waypoints.addAll(loaded);
            }
            System.out.println("[Minimap CE] Loaded " + waypoints.size() + " waypoints for world: " + currentWorldId);
        } catch (Exception e) {
            System.err.println("[Minimap CE] Failed to load waypoints: " + e.getMessage());
        }
    }

    public void clearForWorldLeave() {
        if (!currentWorldId.isEmpty() && !waypoints.isEmpty()) {
            save();
        }
        waypoints.clear();
        currentWorldId = "";
    }

    public void save() {
        if (currentWorldId.isEmpty())
            return;

        File dir = new File(WAYPOINT_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File file = getWaypointFile();
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(waypoints, writer);
        } catch (IOException e) {
            System.err.println("[Minimap CE] Failed to save waypoints: " + e.getMessage());
        }
    }

    private File getWaypointFile() {
        return new File(WAYPOINT_DIR + "/" + currentWorldId + ".json");
    }

    public static String sanitizeFilename(String name) {
        if (name == null || name.isEmpty())
            return "unknown";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Get the world ID for a specific dimension, derived from the current world ID.
     */
    public String getWorldIdForDim(int dim) {
        if (currentWorldId.isEmpty())
            return "";
        // Replace _dim<N> with _dim<target>
        int dimIdx = currentWorldId.lastIndexOf("_dim");
        if (dimIdx < 0)
            return currentWorldId;
        return currentWorldId.substring(0, dimIdx) + "_dim" + dim;
    }

    /**
     * Load waypoints for a specific dimension without changing the active waypoint
     * set.
     * Returns an empty list if no file exists.
     */
    public List<Waypoint> loadWaypointsForDim(int dim) {
        String worldId = getWorldIdForDim(dim);
        if (worldId.isEmpty())
            return new ArrayList<Waypoint>();

        File file = new File(WAYPOINT_DIR + "/" + worldId + ".json");
        if (!file.exists())
            return new ArrayList<Waypoint>();

        try (FileReader reader = new FileReader(file)) {
            Type listType = new TypeToken<List<Waypoint>>() {
            }.getType();
            List<Waypoint> loaded = gson.fromJson(reader, listType);
            return loaded != null ? loaded : new ArrayList<Waypoint>();
        } catch (Exception e) {
            return new ArrayList<Waypoint>();
        }
    }

    public boolean hasWorld() {
        return !currentWorldId.isEmpty();
    }

    public void saveWaypointsForDim(int dim, List<Waypoint> list) {
        String worldId = getWorldIdForDim(dim);
        if (worldId.isEmpty())
            return;

        File dir = new File(WAYPOINT_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File file = new File(WAYPOINT_DIR + "/" + worldId + ".json");
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(list, writer);
        } catch (IOException e) {
            System.err.println("[Minimap CE] Failed to save waypoints: " + e.getMessage());
        }
    }
}
