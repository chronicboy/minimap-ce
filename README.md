# Minimap CE 2.0.1

A JourneyMap-inspired minimap mod for Better Than Wolves CE 3.0.0 (client-side only).

## Features

### Minimap
- **Texture-sampled block colors** — reads MC's terrain atlas for accurate per-block colors
- **Biome tinting** — grass, leaves, and foliage adapt to biome colors
- **Topographic shading** — height-based lighting for terrain depth
- **Circular map** with premium beveled frame and compass labels (N/E/S/W)
- **Zoom** — scroll to zoom in/out, entities and waypoints track at all zoom levels
- **Chunk grid** overlay (toggleable)

### Waypoints
- **Add/Edit/Delete** with color picker (10 preset colors)
- **In-world beacons** — translucent beam + diamond icon visible from any distance
- **Focused labels** — name + distance shown when looking toward the waypoint
- **Map markers** — diamond icons on minimap, edge-clamped when out of range
- **Teleport** — TP to any waypoint (lands safely above ground)
- **Per-world saving** — separate waypoint files for each world/server

### Entity Tracking
- **Mob arrows** on minimap — Red (hostile), Green (passive), Yellow (neutral)
- **Player arrow** — chevron with cyan center dot
- Visible at all zoom levels

### Info Display
- **Time** — Day count, HH:MM, Day/Night indicator
- **Coordinates** — x, z, y with dimension ID
- **Biome name** display

## Controls

| Key | Action |
|-----|--------|
| `B` | Open waypoint manager |
| `F` | Toggle coordinate display |
| `]` | Increase minimap size |
| `[` | Decrease minimap size |
| Scroll | Zoom in/out |

## Building

1. Run `install.bat` (Windows) or `install.sh` (Linux/Mac)
2. Run `.\gradlew.bat build` (Windows) or `./gradlew build` (Linux/Mac)
3. JAR output: `build/libs/minimap-ce-2.0.1.jar`

## Installation

Copy `minimap-ce-2.0.1.jar` to your BTW CE `mods` folder.

## License

CC0-1.0
