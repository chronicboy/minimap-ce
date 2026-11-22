package net.fabricmc.example.minimap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.src.Minecraft;
import net.minecraft.src.World;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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

        List<Waypoint> loaded = read(file);
        if (loaded != null) {
            waypoints.addAll(loaded);
            System.out.println("[Minimap CE] Loaded " + waypoints.size() + " waypoints for world: " + currentWorldId);
        }
    }

    /**
     * Reads a waypoint file. An unreadable file is moved aside (".corrupt") and null returned, so the
     * next save cannot quietly replace the waypoints it still holds.
     */
    private List<Waypoint> read(File file) {
        List<Waypoint> loaded;
        try (Reader reader = new InputStreamReader(new FileInputStream(file))) {
            Type listType = new TypeToken<List<Waypoint>>() {
            }.getType();
            loaded = gson.fromJson(reader, listType);
        } catch (Exception e) {
            System.err.println("[Minimap CE] Could not read " + file.getName() + ", keeping it as .corrupt: " + e);
            File aside = new File(file.getPath() + ".corrupt");
            aside.delete();
            file.renameTo(aside);
            return null;
        }
        if (loaded == null)
            return new ArrayList<Waypoint>();
        loaded.removeIf(w -> w == null);
        return loaded;
    }

    /**
     * Writes the list to a temporary file and then moves it over the old one, so a crash or full disk
     * mid-save leaves the previous waypoints intact instead of a half-written file.
     */
    private boolean write(File file, List<Waypoint> list) {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory())
            dir.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp))) {
            gson.toJson(list, writer);
        } catch (IOException e) {
            System.err.println("[Minimap CE] Failed to save waypoints: " + e.getMessage());
            tmp.delete();
            return false;
        }
        try {
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            System.err.println("[Minimap CE] Failed to save waypoints: " + e.getMessage());
            tmp.delete();
            return false;
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

        write(getWaypointFile(), waypoints);
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

        List<Waypoint> loaded = read(file);
        return loaded != null ? loaded : new ArrayList<Waypoint>();
    }

    public boolean hasWorld() {
        return !currentWorldId.isEmpty();
    }

    public void saveWaypointsForDim(int dim, List<Waypoint> list) {
        String worldId = getWorldIdForDim(dim);
        if (worldId.isEmpty())
            return;

        write(new File(WAYPOINT_DIR + "/" + worldId + ".json"), list);
    }
}
