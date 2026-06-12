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

## Configuration (`config/neoculus-horizon.properties`)

| Key | Default | Meaning |
|-----|---------|---------|
| `enabled` | `true` | Master toggle. |
| `lodDistanceChunks` | `128` | LOD render distance in chunks (16–1024). |
| `baseLodScale` | `4` | Blocks per LOD cell in the nearest ring (power of two). |
| `lodRingWidth` | `1024` | Blocks per detail ring; cell size doubles each ring. |
| `maxUploadsPerFrame` | `4` | GPU mesh uploads allowed per frame. |
| `saveIntervalSeconds` | `60` | Autosave interval for dirty LOD data. |
| `renderWithShaders` | `true` | Render LOD terrain while a shader pack is active (flat-shaded into the pipeline's terrain buffers; disable per-pack if needed). |

## Known limitations (v1)

- Heightmap-based: caves and overhangs are not represented at LOD distance.
- In the 1–2 chunk overlap ring at the edge of the vanilla render distance,
  LOD caps can briefly draw over real terrain until chunks are evicted.
- With shader packs the LOD pass is flat-shaded (no shadows/PBR on LOD
  terrain). Deeper integration via the existing `dhTerrain` program family is
  the planned next step.
- If Distant Horizons is installed, prefer it and set `enabled=false`; the
  existing DH compat in `compat/dh` gives full shader integration.
