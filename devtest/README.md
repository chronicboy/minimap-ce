# Offline checks

Tests for the map engine that run without starting Minecraft. Nothing in this
folder is part of the mod jar.

```
./gradlew.bat build --no-daemon
./gradlew.bat --stop
bash devtest/run-tests.sh
```

| Suite | What it proves |
| --- | --- |
| `pure` | Region files round-trip, are replaced atomically, reject corrupt/truncated data, and read 3.x files. Shading maths. Clipped map pieces tile the map shape exactly. |
| `sampler` | Chunk sampling (surface, water depth, Nether slices, x/z/metadata indexing) against real `Chunk` objects, with a stand-in colour table. |
| `gl` | The region store and map drawing on an offscreen OpenGL surface: every pixel is compared with what the synthetic world says should be there (1:1, zoomed, rotated, in-between zoom and rotation through the sharp shader, circle mask, opacity, live chunk updates, relief across region borders, zoomed-out averaging, far-zoom overview, 3.x import, saving, cave layer merging and persistence). |
| `visual` | Renders the real minimap code over generated terrain to `build/devtest/run-*/visual/minimaps.png` (plus a zoomed `crop.png`) so the look can be checked by eye. It starts from the OpenGL state the game leaves behind (flat shading and so on), which is what catches state-dependent glitches. |

| `heads` | Cuts every mob face out of the game's skin textures with the radar's own table and writes `heads.png` next to the picture above, to check the table by eye. |
| `screens` | Renders every Minimap CE screen (fullscreen map with its menu, waypoint list, waypoint editor, all three settings tabs) at three screen sizes with the game's real font, into `screens/`. Needs `run/config/mcpatcher.conf`, which the first `runClient` creates. |

One more tool is compiled but not run by the script, because it needs to be pointed at a map:
`RealMapView <map folder> <output folder> [centerX centerZ [scale [pointSample [name]]]]` renders a map the game
actually saved (for example a folder under `run/config/minimap-ce-maps-v4/`) to a PNG. It
works on a copy. This is how the real block colour table can be checked without playing.

What these cannot cover, and still needs a look in game: the real block colour
table (needs the game's blocks and texture atlas), the world listener, key
bindings, mob head icons on live entities, and clicking through the screens.
