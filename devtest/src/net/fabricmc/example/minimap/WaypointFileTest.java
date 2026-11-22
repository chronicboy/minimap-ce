package net.fabricmc.example.minimap;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Waypoint files: what is written reads back the same, saving over an existing file replaces it,
 * and an unreadable file is set aside as .corrupt (not overwritten) so its waypoints are not lost.
 *
 *   WaypointFileTest <scratch directory>
 */
public class WaypointFileTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0], "waypoint-files");
        dir.mkdirs();
        Method write = WaypointManager.class.getDeclaredMethod("write", File.class, List.class);
        Method read = WaypointManager.class.getDeclaredMethod("read", File.class);
        write.setAccessible(true);
        read.setAccessible(true);
        WaypointManager m = WaypointManager.instance;
        int failures = 0;

        File f = new File(dir, "w.json");
        List<Waypoint> list = new ArrayList<Waypoint>();
        list.add(new Waypoint("Home", 10, 64, -20, 0xFF5555));
        list.add(new Waypoint("Mine été", -300, 12, 4000, 0x55FF55, false));
        write.invoke(m, f, list);
        list.add(new Waypoint("Third", 1, 2, 3, 0x123456));
        write.invoke(m, f, list); // over the existing file
        @SuppressWarnings("unchecked")
        List<Waypoint> back = (List<Waypoint>) read.invoke(m, f);
        boolean same = back != null && back.size() == 3;
        for (int i = 0; same && i < 3; i++) {
            Waypoint a = list.get(i), b = back.get(i);
            same = a.name.equals(b.name) && a.x == b.x && a.y == b.y && a.z == b.z && a.color == b.color && a.enabled == b.enabled;
        }
        System.out.println("  " + (same ? "ok  " : "FAIL") + " written and read back identically (incl. replacing a file)");
        if (!same) failures++;
        boolean noTmp = !new File(dir, "w.json.tmp").exists();
        System.out.println("  " + (noTmp ? "ok  " : "FAIL") + " no temporary file left behind");
        if (!noTmp) failures++;

        File bad = new File(dir, "bad.json");
        new File(dir, "bad.json.corrupt").delete();
        try (FileWriter w = new FileWriter(bad)) { w.write("[{\"name\": \"Base\", \"x\": 1, "); } // cut off mid-save
        Object r = read.invoke(m, bad);
        boolean kept = r == null && !bad.exists() && new File(dir, "bad.json.corrupt").length() > 0;
        System.out.println("  " + (kept ? "ok  " : "FAIL") + " a damaged file is moved to .corrupt, not overwritten");
        if (!kept) failures++;

        System.out.println(failures == 0 ? "WAYPOINT FILE TEST PASSED" : "WAYPOINT FILE TEST FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
