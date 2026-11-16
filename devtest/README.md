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
| `gl` | The region store and map drawing on an offscreen OpenGL surface: every pixel is compared with what the synthetic world says should be there (1:1, zoomed, rotated, circle mask, opacity, live chunk updates, relief across region borders, far-zoom overview, 3.x import, saving). |
| `visual` | Renders the real minimap code over generated terrain to `build/devtest/run-*/visual/minimaps.png` (plus a zoomed `crop.png`) so the look can be checked by eye. |

What these cannot cover, and still needs a look in game: the real block colour
table (needs the game's blocks and texture atlas), the world listener, key
bindings, the fullscreen map screen and the settings screen.
