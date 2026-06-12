# Horizon — extended-distance LOD terrain

Horizon is NeOculus's built-in LOD system, inspired by Voxy / Distant Horizons:
chunks you explore are downsampled, persisted to disk, and rendered as
simplified terrain far beyond the server view distance.

## How it works

- **Capture** — when the client receives a chunk (and again when it unloads,
  to catch edits), the surface heightmap, water surface and a map color per
  column are snapshotted into ~2.5 KB of data. No server support needed;
  fully compatible with C2ME and other server-side chunk optimizers since
  nothing server-side is touched.
- **Persistence** — data is saved as gzip region files (32x32 chunks) under
  `horizon-lod/<world>/<dimension>/` in the game directory, so explored
  terrain stays visible across sessions.
- **Meshing** — a single low-priority worker builds column meshes per render
  region (8x8 chunks). Cell size starts at `baseLodScale` blocks and doubles
  every `lodRingWidth` blocks of distance, keeping far geometry cheap.
- **Rendering** — meshes draw after opaque terrain and entities, before the
  translucent layer, with distance fog matching the sky color. The projection
  far plane is extended only while Horizon is active. Regions inside the
  vanilla render distance are skipped.

## Level of detail

Cell size is proportional to distance (constant screen-space error): one
block per cell out to `lodRingWidth`, then doubling with every doubling of
distance, reaching the coarsest level (64 blocks) only around 4000 chunks.
The "collar" around the real render distance is always meshed at one block
per cell and drawn in full overlap with real terrain: polygon offset lets
real blocks win the depth test pixel-for-pixel, so the seam is exact and
still-meshing chunks are backfilled by the LOD. Coarser distant meshes are
cut by a per-chunk coverage mask with a dithered 16-block fade.

Colors come from each block's top-face texture, softened into an 8x8 grid
and sampled per column position, with biome tints applied; slope-based
directional shading makes relief readable at distance. Greedy run-merging
collapses flat areas into few quads; vertices are 12 bytes.

## Configuration (`config/neoculus-horizon.properties`)

| Key | Default | Meaning |
|-----|---------|---------|
| `enabled` | `true` | Master toggle. |
| `lodDistanceChunks` | `256` | LOD render distance in chunks (16–4096). |
| `baseLodScale` | `1` | Minimum blocks per LOD cell (power of two). |
| `lodRingWidth` | `1024` | Distance of the first detail ring; cell size doubles per ring after it. |
| `maxUploadsPerFrame` | `4` | GPU mesh uploads allowed per frame. |
| `saveIntervalSeconds` | `60` | Autosave interval; far chunks are also evicted from memory after saving. |
| `workerThreads` | auto (1–2) | Background meshing/IO threads (1–4, applied at start). |
| `renderWithShaders` | `true` | Render LOD terrain while a shader pack is active. The pass restricts itself to color attachment 0 so G-buffer data is never corrupted; disable per-pack if a specific pack misbehaves. |

All options are editable live from the Embeddium video settings ("Horizon
LOD" page) or the vanilla video settings button.

## Known limitations / roadmap

- Heightmap-based: caves and overhangs are not represented at LOD distance.
- With shader packs the LOD is flat-shaded color + depth: depth-based
  composite effects (fog, DOF, godrays) apply to it, but it does not receive
  shadows or PBR. Full integration means rendering Horizon through the
  shader pack's `dhTerrain` program family (the Iris transform pipeline for
  it already exists in `pipeline/transform`, `Patch.DH_TERRAIN`); that is
  the planned next milestone and removes the per-pack caveats entirely.
- If Distant Horizons is installed, prefer it and set `enabled=false`; the
  existing DH compat in `compat/dh` gives full shader integration.
