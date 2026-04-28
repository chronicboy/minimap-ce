## 3.3.0

### Features
- Chunk grid is now **off by default**.
- Grid squares become **smaller and more frequent when zooming out** (and larger when zooming in) – fixed the inverse‑scaling bug.

### Bug Fixes
- Waypoint labels now stay at a **consistent pixel size** across all render distances (scale is based on visual distance rather than true distance).

### Technical
- Updated `gradle.properties` → `mod_version = 3.3.0`.
- Restored correct grid‑step calculation (`int chunkStep = (int)(16 * zoomFactor);`).
