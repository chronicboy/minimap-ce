# Changelog

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
