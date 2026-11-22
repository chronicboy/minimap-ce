# Changelog

## 4.0.0

A new map engine. The map is no longer redrawn pixel by pixel on the CPU; explored terrain lives in textures on the graphics card, and chunks are only looked at when they actually load or change.

### Features
* **Smooth at any frame rate** - the minimap scrolls and turns smoothly, and markers move with the terrain instead of jittering against it.
* **Crisp at every zoom** - map blocks line up exactly with screen pixels. Whole-number zooms are pixel-perfect; in-between zooms and the rotating minimap use a small shader that keeps every block solid and only softens the one pixel on a block edge (falls back to plain sampling without OpenGL 2.0, or switch it off under World Map > Map Pixels). Zoomed out, each pixel is the clean average of the blocks it covers instead of a blur.
* **Cave mode** - when there is rock above you, the minimap switches to a live slice of the caves at your level (walls dark, floors in their own colours) and back to the surface when you come out, like Xaero's. A tunnel just under the grass counts; trees, plank roofs and thin roofs do not. The biome line shows "(Caves)" while it is on. Can be turned off.
* **Underground world map** - caves you explore are saved in 16-block height layers. When you open the fullscreen map underground it shows the layer you are in; a small panel (or PgUp/PgDn) moves between the surface and every layer you have explored, and clicking its name goes back to following your level.
* **Rotating minimap** - optional "follows player" mode where up is always the way you are facing (compass letters orbit the frame).
* **Square minimap** - the shape setting now actually works.
* **Five minimap zoom levels** - 4x, 2x, 1x, 1/2x and 1/4x (was three).
* **Minimap opacity** - 100% down to 25%.
* **Xaero-style entity radar** - mobs are small color-coded dots, and holding the player list key (Tab) shows each mob's face instead. Faces are cut from the mob's own skin (follows resource packs and BTW's varied animal skins), pixel-aligned with a thin dark outline; horses, donkeys and mules are drawn from their 3D model (a face looking at you, ears up), and dire wolves, zombie villagers and humanoid mobs from other mods are covered too. Mobs well above or below you are drawn slightly darker (within 8 blocks of your level they are at full brightness). Always-faces, the 3.x arrows and plain dots are available as alternative styles.
* **Lettered waypoint tiles** - waypoints are colored tiles with their initial on both maps. The small 3.x squares are still available.
* **New look for every screen** - one consistent dark theme with flat buttons, cards and a blue accent.
  * Settings: three tabs, sliders for size, zoom and opacity, the minimap drawn live behind (the card moves aside when there is room), and an explanation of whatever option you hover.
  * Waypoint list: distance to every waypoint, click a row to edit, Hide/Show All, a scrollbar, and delete asks for a second click.
  * Waypoint editor: 16 colors, a live tile preview, a Here button (converted for the Nether when editing the other dimension), invalid coordinates are outlined in red instead of silently ignored, and Delete.
  * Fullscreen map: toolbar with dimension tabs, a distance ruler, hover cards with distance, and a restyled right-click menu. Teleport there also works for the other dimension where possible.
* **Gliding minimap** - while you move, terrain and mob faces move smoothly below the pixel instead of jumping a whole pixel at a time; standing still, they settle exactly onto the pixel grid.
* **3D faces for more mobs** - pigs (snout), cows and mooshrooms (horns), chickens (beak and wattle), wolves and dire wolves (ears and snout), ocelots (ears and nose), villagers, witches (with the hat brim), zombie villagers and iron golems (noses) are built from their model boxes and seen straight on, so they stay as pixel-sharp as the flat faces.
* **Tidy crowds** - identical faces on top of each other are drawn once with a small count, and faces at the edge of the minimap slide out under the frame instead of popping in and out.
* **Smooth minimap zoom** - zooming the minimap animates between levels, like the fullscreen map.
* **Waypoints in the world, JourneyMap style** - beacon beams in the waypoint's colour (the game's beacon texture, turning and scrolling, with a soft glow); far beams are widened so they stay visible instead of thinning to a hairline, and a beam fades out as you reach its waypoint. At the waypoint, a smooth-edged diamond with "Name (distance)" above it while you look towards it, sharp and the same size at any distance. Under Markers & Info > In World: On Look, Always or Beams Only.
* **Export the map as a picture** - the fullscreen map's Export button (also in the right-click menu) saves the map you are viewing, surface, cave layer or another dimension, as a PNG in `screenshots/`: one pixel per block with no seams between regions, unexplored ground transparent, scaled down for very large maps. Saving runs in the background and never touches a file while the map is writing it.
* **You stay on top** - the player arrow and waypoints are always drawn above mob faces, and mobs at your own level are drawn above those far above or below.
* **Gloom countdown** - the clock shows the days until the next gloom (new moon) night in brackets, and (Gloom) on the day itself.
* **Coordinates as plain numbers** - x, y, z without labels, on the minimap and the fullscreen map.
* **Refined minimap frame** - lit metal band, crisp outline, inner shadow and Xaero-style compass letters just inside the edge; a minimal white player arrow, and every arrow now has smooth (anti-aliased) edges; unexplored ground is faintly hatched instead of looking empty.
* **Snow, ice and trees at the edge of the explored area** - the outermost ring of loaded chunks arrives before the world generator has decorated it, so the map used to keep a bare grass strip along every edge. A chunk is now only mapped once all its neighbours have loaded, by which point it is finished.
* **Smooth biome borders** - grass, leaf and water colors are blended over a 5x5 area, across chunk borders, instead of changing in hard blocky lines.
* **Fullscreen map rebuilt** - drag to pan and scroll to zoom towards the cursor with no stutter, hold WASD/arrow keys to glide, `C` to follow the player again. Zoom steps are chosen so the map is always pixel-sharp, from 64 screen pixels per block out to an overview of everything you have ever explored.
* **Right-click menu on the fullscreen map** - new waypoint here, teleport here, centre on player; on a waypoint: edit, hide/show, teleport, delete.
* **Truer colours** - every block state has its own colour (wool, planks, stained clay and friends no longer all look like the first variant), spruce and birch leaves use their own tints, swamp water is swamp coloured.
* **Water depth** - shallows hint at the bed through the water, deep water is darker.
* **Smoother relief shading** - based on the slope towards both north and west, scaled by steepness, with a slight brightening at altitude.
* **Nether map** - shows the floor of the level you are on (lava seas, bridges, tunnels) with solid rock dark, and remembers floors you have already seen as you move between levels.
* **Cursor height** - the fullscreen map shows the ground level under the cursor, and waypoints created from the map are placed at that height instead of y 64.
* **New rebindable keys** - zoom in (`=`), zoom out (`-`), new waypoint here (`N`), toggle minimap (`M`). Numpad +/- still zoom.
* **Clock toggle**, and shift-click to step backwards through multi-value settings.
* **Resource packs** - map colours update when the resource pack changes.

### Bug Fixes
* Fixed waypoints that could all be lost if the game crashed while saving them: waypoint files (and the settings file) are now written safely, and a damaged waypoint file is kept as `.corrupt` instead of being replaced by an empty list.
* Fixed the spawn point on servers being saved as 0, 0 when it was read before the server sent it; it now corrects itself, including spawns saved that way by older versions.
* Fixed the Nether spawn marker being one block off at negative coordinates.
* An error while drawing waypoint beams can no longer crash the game.
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
