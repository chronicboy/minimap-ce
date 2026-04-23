# Minimap CE v3.2.0 Changelog

## 🕒 Clock & Time Synchronization
- **20-Minute Real-Time Cycle**: Refactored the HUD clock to use a 20-minute cycle (00:00 - 19:59), accurately reflecting the real-time duration of a Minecraft day.
- **Improved Day/Night Logic**: Updated the day/night prefix to sync with `world.isDaytime()`, ensuring the labels align with actual game light levels.


## 🌍 World Identity & Persistence
- **Seed-Based Identification**: Fixed the "world collision" bug where map data would persist across deleted worlds. The mod now generates a unique ID using the **World Seed + Save Folder Name** for singleplayer.
- **Spawn Point Uniqueness**: Updated the `SpawnTracker` to use the new unique identification system, ensuring "Spawn Point" waypoints are unique to each world even if the folder name is reused.
- **Multiplayer Identification**: Switched multiplayer world mapping to use the **Server IP** instead of the generic world name for better data partitioning.
- **Centralized Logic**: Consolidated world-prefix generation into `WaypointManager` to ensure consistency across Map Tiles, Waypoints, and Spawn tracking.

## 🖥️ UI & Aesthetic Refinements
- **Smart HUD Stacking**: Improved HUD positioning for bottom-aligned maps. Coordinates and biome info now move **above** the map to prevent them from being cut off by the screen edge.
- **Flush UI Support**: Reduced the gap between the map and info boxes to **2 pixels** when padding is disabled, creating a sleek, premium look.
- **Dynamic Layout**: Info boxes now intelligently stack (Clock above Coords above Map) to maintain visibility regardless of screen position.

> [!NOTE]
> This update ensures that map data is partitioned correctly by world seed. If you recreate a world with the same folder name but a different seed, a new map cache will be created automatically.
