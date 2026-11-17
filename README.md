# Minimap CE
A faithful, lightweight minimap and world map for Better Than Wolves CE 3.0.0 and above (Minecraft 1.6.4).

Minimap CE provides all the classic minimap functionality you need, themed to feel right at home with old-school Minecraft and BTW. It runs client-side only.

## Features

### Minimap
- **Texture-sampled block colors** — reads Minecraft's terrain atlas for accurate colors per block variant, and follows your resource pack.
- **Topographic shading** — slope-based lighting for terrain depth, with water that darkens as it gets deeper.
- **Biome tinting** for grass, leaves, vines and water, blended smoothly across biome borders like the game itself does.
- **Circular or square map** with a lit metal frame, a soft inner shadow, and compass letters just inside the edge like Xaero's. You are a minimal white arrow with a thin outline. Unexplored ground is faintly hatched, so it never looks like a hole in the map.
- **North-up or rotating** — optionally keep the way you are facing at the top.
- **Zoom** — five levels from 4x to 1/4x.
- **Adjustable size, corner and opacity.**
- **Chunk grid overlay** (toggleable).
- **Crisp at every zoom** — blocks line up with screen pixels; in-between zooms and the rotating map keep blocks solid with clean edges.
- **Smooth and light** — terrain is drawn from GPU textures, so the map scrolls smoothly at any frame rate and costs next to nothing. Chunks are only read when they load or change.
- **Cave mode** — underground, the minimap shows the caves at your level instead of the surface, and switches back when you come out.
- **Nether support** — shows the floor of the level you are on and remembers what you have seen as you move between levels.

### Full-Screen Map
- **Explore everything you have seen** — drag to pan, scroll to zoom towards the cursor, from 16 pixels per block out to an overview of your whole explored world.
- **Persistent terrain** — explored areas are saved per world and dimension, in the background, and stay on the map when you are far away.
- **Underground layers** — caves you explore are saved in 16-block height layers. Underground, the map opens on your layer; the panel at the top right (or PgUp/PgDn) moves between the surface and every explored layer.
- **Cross-dimension viewing** — browse the Overworld, Nether and End maps from tabs without travelling there.
- **Right-click menu** — create, edit, hide or delete waypoints, teleport, or re-center on yourself.
- **Cursor readout** — coordinates and ground height under the mouse, plus a distance ruler in the corner.
- **Unpaused journeys** — optionally leave the game running while the map is open.

### Waypoints
- **Lettered tiles** — each waypoint is a colored tile with its initial, on both maps. A small plain-square style is available if you prefer less clutter.
- **Hoverable labels** — name, coordinates and distance appear when you hover a waypoint on the full-screen map.
- **Waypoint list** — every waypoint with its distance from you; click a row to edit it, hide or show one or all, and deleting asks for a second click.
- **Waypoint editor** — 16 colors, a live preview of the tile, a Here button that fills in where you stand (converted for the Nether), and it will not save coordinates that are not numbers.
- **Quick waypoint** — one key drops a waypoint where you stand.
- **Cross-dimension waypoint list** — manage waypoints for any dimension from the waypoint screen.
- **Death waypoints** — saved automatically and numbered (Death, Death 2, ...).
- **Spawn point** — tracked per world, with its own toggle.
- **In-world beacons** — translucent beams with an icon, and a name and distance label when you look at them.
- **Teleport** to a waypoint or any explored spot on the map. This sends a chat command, so it needs cheats or operator rights; the command can be changed (see Configuration).
- **Per-world saving** — separate lists for every singleplayer world and multiplayer server.

### Entity Radar
- **Dots, faces on Tab** — like Xaero's: nearby mobs are small color-coded dots (red hostile, green passive animals and villagers, blue players, yellow anything else), and holding the player list key (Tab) shows each one's face.
- **Mob faces** — cut from the mob's own skin, so they follow resource packs and BTW's varied animal skins. Every vanilla and BTW mob the radar shows has one; horses, donkeys and mules get a side profile.
- **Depth at a glance** — mobs well above or below you are drawn darker.
- **Other styles** — always faces, arrows that point where each mob is looking, or plain dots.
- Only shows living things within 20 blocks of your height; invisible mobs and sneaking players stay hidden.

### Info HUD
- **Clock** — day number, day or night, minutes:seconds into the current day, and in brackets the days until the next gloom night (BTW's pitch-black new moon: days 5, 13, 21, ...). It says (Gloom) on the day itself.
- **Coordinates** — the block you are standing on, as plain numbers in x, y, z order like Xaero's.
- **Biome** name.
- Each can be turned off, and the text shrinks to match small minimaps.

## Controls
| Key | Action |
| --- | --- |
| `J` | Open full-screen map |
| `O` | Open map settings |
| `B` | Open waypoint manager |
| `N` | New waypoint where you stand |
| `M` | Toggle minimap |
| `Tab` (hold) | Show mob faces on the minimap instead of dots |
| `F` | Toggle entity radar |
| `=` / `-` | Zoom minimap in / out (numpad +/- also work) |
| `]` | Increase minimap size |
| `[` | Decrease minimap size |

All of these can be rebound under Options > Controls; they start with `[Minimap]`.

On the full-screen map:

| Input | Action |
| --- | --- |
| `Drag` | Pan |
| `Scroll` or `=` / `-` | Zoom |
| `WASD` / arrow keys | Pan |
| `C` | Follow the player again |
| `PgUp` / `PgDn` | Cave layer up / down (towards the surface / deeper) |
| `Double-click` | New waypoint |
| `Right-click` | Context menu |
| `Esc` or `J` | Close |

## Settings
Press `O` in game. The minimap stays visible behind the screen and updates as you change things; hover an option for an explanation.

- **Minimap** — on/off, corner, shape, rotation, size, zoom, opacity, padding, cave mode.
- **Markers & Info** — entity radar and its icon style (Dots + Tab Faces, Faces, Arrows or Dots), waypoints and their icon style, chunk grid, compass, clock, coordinates, biome.
- **World Map** — whether the full-screen map pauses the game, whether teleport options are shown, and map pixel scaling (Sharp, or Nearest if your graphics driver has trouble with Sharp).

## Configuration
Everything lives in your game folder under `config/`:

| Path | Contents |
| --- | --- |
| `minimap-ce.conf` | All settings. Safe to edit while the game is closed. |
| `minimap-ce-waypoints/` | Waypoints (one JSON file per world and dimension) and the recorded spawn point. |
| `minimap-ce-maps-v4/` | Explored map data, one folder per world and dimension, with cave layers in its `caves/` subfolder. Delete a folder to reset that map. |

The teleport command is the one setting without a button. In `minimap-ce.conf`:

```
teleportCommand="/tp {x} {y} {z}"
```

`{x}`, `{y}`, `{z}`, `{dim}` (dimension id) and `{name}` (waypoint name) are filled in. A command that uses `{dim}` is needed to teleport to a waypoint in another dimension on a server.

## Upgrading from 3.x
- Your waypoints, settings and explored maps carry over. Old map data in `config/minimap-ce-maps-v3/` is imported as you look at it and is never changed, so you can go back to a 3.x jar and still have your old map.
- Areas explored with 3.x keep their old colors until you visit them again.
- The entity radar shows dots, with faces while Tab is held, and waypoints show lettered tiles by default; the 3.x look (arrows, small squares) is a few clicks away in the settings.

## Installation
1. Install **Better Than Wolves CE 3.0.0 or later** with Fabric Loader 0.14.19 or later.
2. Download the .jar file from Modrinth https://modrinth.com/mod/minimap-ce
3. Drop the `.jar` into your `.minecraft/mods/` folder.
4. Launch the game!

## Building
Requires Java 17.

```
./gradlew.bat build --no-daemon
./gradlew.bat --stop
```

The jar is written to `build/libs/`. On Windows, do not run `gradlew clean`.

The map engine has tests that run without starting the game, including rendering on an offscreen OpenGL surface; see [devtest/README.md](devtest/README.md).
