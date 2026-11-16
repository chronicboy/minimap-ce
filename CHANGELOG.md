# Changelog

## 4.0.0

A new map engine. The map is no longer redrawn pixel by pixel on the CPU; explored terrain lives in textures on the graphics card, and chunks are only looked at when they actually load or change.

### Features
* **Smooth at any frame rate** - the minimap scrolls and turns with sub-pixel precision, and markers move with the terrain instead of jittering against it.
* **Rotating minimap** - optional "follows player" mode where up is always the way you are facing (compass letters orbit the frame).
* **Square minimap** - the shape setting now actually works.
* **Five minimap zoom levels** - 4x, 2x, 1x, 1/2x and 1/4x (was three).
* **Minimap opacity** - 100% down to 40%.
* **Fullscreen map rebuilt** - drag to pan and scroll to zoom towards the cursor with no stutter, hold WASD/arrow keys to glide, `C` to follow the player again. Zooms from 16 pixels per block out to an overview of everything you have ever explored.
* **Right-click menu on the fullscreen map** - new waypoint here, teleport here, centre on player; on a waypoint: edit, hide/show, teleport, delete.
* **Truer colours** - every block state has its own colour (wool, planks, stained clay and friends no longer all look like the first variant), spruce and birch leaves use their own tints, swamp water is swamp coloured.
* **Water depth** - shallows show the bed through the water, deep water is darker.
* **Smoother relief shading** - based on the slope towards both north and west, scaled by steepness, with a slight brightening at altitude.
* **Nether map** - shows the floor of the level you are on (lava seas, bridges, tunnels) with solid rock dark, and remembers floors you have already seen as you move between levels.
* **Cursor height** - the fullscreen map shows the ground level under the cursor, and waypoints created from the map are placed at that height instead of y 64.
* **New rebindable keys** - zoom in (`=`), zoom out (`-`), new waypoint here (`N`), toggle minimap (`M`). Numpad +/- still zoom.
* **Clock toggle** and shift-click to step backwards through multi-value settings.
* **Resource packs** - map colours update when the resource pack changes.

### Bug Fixes
* Fixed the map freezing the game for a moment every 30 seconds while exploring: region files are now compressed and written on a background thread.
* Fixed map data being written straight over the old file, which could corrupt a region if the game closed mid-save. Saves are now atomic, and an unreadable file is set aside as `.corrupt` instead of being overwritten.
* Fixed the whole explored map of a world being loaded into memory on joining. Regions now load on demand and unload when not needed.
* Fixed X/Z coordinates being off by one at negative positions, and Y showing eye level instead of the block you stand on.
* Fixed pressing Escape on a brand new waypoint leaving an unnamed waypoint behind.
* Fixed waypoints created by double-clicking another dimension's map being saved to the current dimension.
* Fixed one malformed line in `minimap-ce.conf` discarding every setting after it.
* Fixed the waypoint list scroll range when viewing another dimension.
* Fixed the fullscreen map only closing with `J` even after rebinding the key.
* Fixed terrain wobbling against the frame when zoomed out.
* Entity radar no longer shows item frames, paintings, boats and other non-living entities, invisible mobs, or sneaking players.

### Technical
* Chunks are sampled through a world listener (load / block change) within a 1.2 ms per-frame budget, nearest first, straight from chunk storage (a few microseconds per chunk) instead of re-sampling everything in range every 500 ms.
* Terrain is stored in 512x512 region textures and drawn as geometry-clipped polygons: no stencil, framebuffer objects or per-frame pixel work. All markers go out in a single draw call.
* New map data lives in `config/minimap-ce-maps-v4`. Existing 3.x maps in `config/minimap-ce-maps-v3` are imported automatically as you view them and are never modified, so going back to 3.6.0 keeps your old map.
* Previously explored areas keep their 3.x colours until you visit them again.
* Rendering errors are logged with a stack trace and can no longer leave the tessellator in a broken state.

## 3.6.0

### Features
* Modernized entity radar arrows with sleek 3D-faceted chevron geometry, high-contrast dark outlines, and subtle drop shadows for high visibility across all terrain types.
* Enhanced entity classification to use Minecraft's `IMob` interface, accurately identifying all hostile mobs (including Slimes, Ghasts, and Magma Cubes) with distinct crimson styling, player markers with sky cyan, and friendly mobs with emerald green.

### Bug Fixes
* Fixed in-world waypoint beacons rendering directly on top of the player when BetterGameSetting is installed by adapting far plane projection to its custom render distance scaling.

### Technical
* Optimized entity rendering loop by lifting repetitive OpenGL state changes and cull face toggles outside the entity iteration.
* Eliminated per-waypoint trigonometry in `WaypointBeamRenderer` by pre-computing viewer look vectors at frame level.
* Hoisted `maxRender` projection calculation in `WaypointBeamRenderer` to run once per frame instead of per waypoint across multiple passes.
* Statically cached `FabricLoader` mod check for `better_game_setting`.
* Reduced GC pressure in chunk persistence by reusing a persistent 256-integer sampling buffer.

## 3.5.0

### Features
* Added waypoint double-click support for quick editing and management.
* Enhanced texture-sampled color mapping for terrain.

### Bug Fixes
* Fixed biome rendering offsets during map updates.
* Fixed waypoint distance calculation discrepancies.

### Technical
* Optimized core minimap renderer loop for higher frame rates.
* Updated to Better Than Wolves CE 3.1.1.
* Built against Minecraft 1.6.4 with Fabric Loader 0.14.19.
