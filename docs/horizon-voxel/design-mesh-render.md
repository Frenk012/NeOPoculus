All context loaded. Below is the implementation-ready design for the meshing / rendering / shader-integration slice.

---

# Horizon Voxel Engine — Meshing, Rendering, Shader Integration (baseline GL 4.1, no compute)

Package: `net.irisshaders.iris.horizon.voxel` (new), cooperating with existing `net.irisshaders.iris.horizon.*`. All file paths relative to `C:\Users\franc\IdeaProjects\NeOPoculus\src\main\java\`.

---

## 0. Coordinate model & shared constants (`VoxelConstants`)

```java
public final class VoxelConstants {
    public static final int SECTION_SIZE      = 32;        // cells per axis, every level
    public static final int SECTION_CELLS     = 32*32*32;  // 32768
    public static final int MAX_LEVEL         = 4;         // mips 0..4; cell edge = 2^level blocks
    public static final int REGION_SECTIONS   = 4;         // draw region = 4x4 sections in XZ, full height in Y
    public static final int REGION_CELLS_XZ   = 128;       // 4*32
    public static final int MERGE_CAP         = 16;        // greedy quad max size (cells), both axes
    public static final int Y_BIAS            = 512;       // reuse LodMesher.Y_BIAS semantics (u16 positions)
    public static final int MAX_QUADS_PER_REGION = 131_072;   // hard emit cap; overflow logged, truncated
    public static final int STRIDE            = 24;        // vertex format v2, bytes
    public static final long AIR              = 0L;        // block-state palette id 0 reserved for air
}
```

- Region **block** span at level L: `REGION_CELLS_XZ << L` = 128 / 256 / 512 / 1024 / 2048.
- Vertical sections per region column (world height 384, y ∈ [−64, 320)): L0:12, L1:6, L2:3, L3:2, L4:1.
- Region key (renderer map + scheduler): `VoxelRegionKey.pack(level, rx, rz)` = `((long)level << 58) | ((rz + (1<<28)) & 0x1FFF_FFFFL) << 29 | ((rx + (1<<28)) & 0x1FFF_FFFFL)`. Unpack helpers mirror `LodStorage.regionKey` style.
- Cell payload (owned by the data slice, consumed here): 64-bit, bits 56–63 light (bits 56–59 sky, 60–63 block), 47–55 biome (9b), 27–46 blockstate (20b), 0–26 reserved-zero. **Merge/equality in the mesher uses the raw long.**

---

## 1. Mesher — `VoxelMesher` + `SectionSnapshot`

### 1.1 Section snapshot API (input contract, consumed from worker threads)

```java
/** Thread-local, reused. 34^3 long grid: 32^3 core + 6 face-neighbor planes (edges/corners unused). */
public final class SectionSnapshot {
    private final long[] cells = new long[34*34*34];      // idx = (x+1) + (z+1)*34 + (y+1)*34*34
    /** @return false when the core section is absent/empty at this level (caller skips). */
    public boolean capture(VoxelWorld world, int level, int sx, int sy, int sz);
    /** x,y,z in -1..32; at most one axis out of 0..31. Missing neighbor => AIR. */
    public long cell(int x, int y, int z);
    public boolean coreUniform();          // fast-skip: all-air or fully-opaque-and-buried
    public long    coreUniformValue();
}
```

Requirements on `VoxelWorld` (data slice): `copySectionInto(level,sx,sy,sz,long[] dst,int base,int strideY,int strideZ)` — a plain bulk copy, no lock held across the whole mesh job; torn reads during concurrent ingest are acceptable because ingest marks the section dirty and a remesh follows. Neighbor planes are pulled with the same call restricted to one boundary plane (32×32×8 B = 8 KB each). **Neighbor lookups cross region boundaries at the same level; they never consult other levels.**

**Missing-neighbor policy: treat as AIR (emit the face).** Consequences: (a) the loading frontier shows temporary walls that disappear on remesh when the neighbor arrives (data slice must dirty-notify the 6 adjacent sections' regions on section-create); (b) at *cross-level* region boundaries the neighbor is never resident at this level, so a full boundary wall is emitted automatically — this is the seam skirt (see §4), by construction, no special code.

### 1.2 Greedy scanline (per face direction)

For each of the 6 face directions `d` (DH normal order `{-Y,+Y,-Z,+Z,-X,+X}`, matching `LodMesher.normalIndex`):

1. Sweep the 32 layers perpendicular to `d`'s axis. For layer `w`, build a 32×32 array `faceKey[u][v]` (thread-local `long[1024]`):
   - `c = cell(cell coords)`; if `c == AIR` → no face.
   - `n = cell(neighbor toward d)`. Face is **visible** iff: `n == AIR`, or `n` is non-opaque (translucent/cutout per `PhotoAtlas.isOpaque(stateId)` CPU table) **and** `stateId(n) != stateId(c)` (same-state translucent faces culled — kills water-internal faces).
   - `faceKey = (c & ~LIGHT_MASK) | (light(n) << 56)` — geometry/biome from the solid cell, **light from the adjacent (air/translucent) cell**, vanilla-style. If the neighbor is a missing plane, fall back to `light(c)`, else sky-15.
2. Scanline merge on `faceKey` (clean-room reimplementation of the well-known technique): walk row `v`, extend run along `u` while keys equal (cap `MERGE_CAP`); then extend the completed rectangle along `v` while the entire next row segment matches (cap `MERGE_CAP`); zero out consumed keys. Emits `Quad(u0, v0, w, sizeU, sizeV, face, key)`.
3. Route to the **opaque** or **translucent** quad list based on `PhotoAtlas.isTranslucent(stateId)`.

Per-level meshing is *level-blind*: the mesher works entirely in cell space; only the vertex writer multiplies by `2^level` when producing block coordinates. One code path for L0–L4.

### 1.3 Region build

```java
public final class VoxelMesher {
    /** Worker thread. Returns null if the whole region is empty. */
    public static MeshData buildRegion(VoxelWorld world, int level, int rx, int rz,
                                       int worldMinY, PhotoAtlasIndex atlas, BiomeTintTable tint);
    public record MeshData(long regionKey, ByteBuffer vertexData,
                           int opaqueQuads, int translucentQuads, float minY, float maxY) {
        public void free() { MemoryUtil.memFree(vertexData); }
    }
}
```

Loops the region's `4×4×sectionsY(level)` sections; `SectionSnapshot.capture` per section, `coreUniform()` fast-skips buried/air sections. Opaque quads are written first, translucent appended after (single VBO, two ranges — see §3). Vertex scratch is a thread-local grow-on-demand off-heap buffer (initial 2 MB, doubling, ceiling `MAX_QUADS_PER_REGION*4*STRIDE` ≈ 12.6 MB); exact-size `memAlloc` copy on completion, identical to today's `LodMesher.build` tail.

Edge cases: emit cap hit → truncate + `Iris.logger.warn` once per region; all-translucent region (ocean at L4) → opaqueQuads=0 is legal; sections fully surrounded by opaque neighbors produce zero faces naturally.

---

## 2. Vertex format v2 — `LodVertexFormatV2`

**Decision: one VBO, widened 24-byte stride, two VAOs reading subsets.** The DH `dh_terrain` contract is attribute *semantics + offsets*, not stride; the iris VAO simply keeps attributes 0/1/2 at the same offsets as today (0/8/12) with `stride=24` and never sees bytes 16–23. No second VBO stream (one allocation, one upload, no lifecycle doubling).

| Offset | Size | Type | Field | Notes |
|---|---|---|---|---|
| 0 | 2 | u16 | posX | region-local blocks, 0..2048 (L4 max) |
| 2 | 2 | u16 | posY | worldY + `Y_BIAS`(512) → 0..832; cell-aligned (512 divisible by 16) |
| 4 | 2 | u16 | posZ | region-local blocks |
| 6 | 2 | u16 | lightMeta | bits 0–3 skyLight, 4–7 blockLight (matches existing `LIGHT_META=0x000F` semantics) |
| 8 | 4 | 4×u8 | colorRGBA | CPU flat color: `slotTopMipRGB(slot) × biomeTint` (pack path + fallback), A=255 (opaque) / 179 (translucent) |
| 12 | 1 | u8 | irisExtra.x | **real DH material id** via `DhMaterialMapper` (§7) |
| 13 | 1 | u8 | irisExtra.y | normal index 0–5 (DH order) |
| 14 | 2 | 2×u8 | irisExtra.zw | 0 |
| 16 | 2 | u16 | atlasSlot | photo-atlas face-slot id (per-face dedup, §2.2) |
| 18 | 2 | u16 | biomeId | 9-bit global biome palette id |
| 20 | 1 | u8 | faceMeta | bits 0–2 face index, bits 3–4 tintType (0 none, 1 grass, 2 foliage, 3 water), bit 5 hasTintMask |
| 21 | 1 | u8 | flags | bit 0 translucent; rest spare (future per-vertex AO) |
| 22 | 2 | u16 | pad | keeps stride 4-aligned; reserved |

4 vertices per quad (indexed, §3) → **96 B/quad — exactly today's 6×16 B/quad.** Memory growth comes only from quad-count increase, not the format.

**VAO layouts (`VoxelRegionMesh`):**

- `voxelVao` (no-pack textured path): `0: IPointer 4×u16 @0` (uvec4 posLight) · `1: Pointer 4×u8 norm @8` (vec4 color) · `2: IPointer 2×u16 @16` (uvec2 slot,biome) · `3: IPointer 2×u8 @20` (uvec2 faceMeta,flags). Shared EBO bound in the VAO.
- `irisVao` (DH-compat, lazy like today): `0: IPointer 4×u16 @0` · `1: Pointer 4×u8 norm @8` · `2: IPointer 4×u8 @12` — byte-compatible with the patched `dh_terrain` expectations; `HorizonIrisProgram`'s `glBindAttribLocation(0 vPosition, 1 iris_color, 2 irisExtra)` unchanged.

### 2.1 In-shader UV derivation (exact)

No UV bytes stored. Per fragment:

```
plane coords p (blocks, region-local, y biased): face ±Y → (x,z); ±Z → (x,y); ±X → (z,y)
cellUV  = p / u_cellSize            // u_cellSize = float(1 << level), per-draw uniform
local   = fract(cellUV)             // tiles the 16x16 photo once per cell across merged quads
slotUV  = (vec2(slot % u_slotsPerRow, slot / u_slotsPerRow) + local) * u_slotScale
sample  = textureGrad(u_atlas, slotUV, dFdx(cellUV)*u_slotScale, dFdy(cellUV)*u_slotScale)
```

`textureGrad` with continuous-coordinate gradients avoids mip seams at the `fract` wrap (GLSL 150-legal). Region origins are cell-aligned and `Y_BIAS=512` is a multiple of 16, so `fract` is phase-correct at every level. V-axis orientation is fixed by the bake convention (bakery renders faces with +U = +X/+Z (east/south), +V = away from face's "up"); the mesher does not need per-face flips because the same convention table is used in `u_faceAxes`.

### 2.2 Photo atlas GL object (consumed here; bake pipeline is the other slice)

- **2D atlas**, `GL_RGBA8`, 5 mip levels (0..4), slots on a 16-texel grid → mip k holds each slot at `16>>k` (mip 4 = 1 texel = the flat-color texel). Default 2048² (16 384 slots ≈ 21 MB + mips ≈ 28 MB); grows by realloc-and-copy to 4096² max (65 536 slots — matches u16 `atlasSlot`).
- Slot = **one face image**, dedup across faces and states (stone → 1 slot for all 6 faces; grass → 3). Mesher resolves `slot = atlasIndex.slotOf(stateId, face)` from a CPU `int[stateId*6+face]` table (`PhotoAtlasIndex`), with slot 0 = magenta/MapColor fallback while a bake is pending.
- Sampler: `GL_NEAREST_MIPMAP_LINEAR`, `GL_TEXTURE_MAX_LOD=4`, wrap `CLAMP_TO_EDGE` (wrap handled by `fract`).
- **Tint-mask atlas**: parallel `GL_R8` texture, identical layout; mip chain generated with **max()** downsample (tinted stays tinted). Sampled only when `faceMeta.hasTintMask`.
- CPU-side per-slot metadata (`PhotoAtlasIndex`): `int topMipRGB[slot]` (for CPU flat color), `byte tintType[stateId]`, `boolean opaque[stateId]`, `boolean translucent[stateId]` — filled by the bakery, read by the mesher.

### 2.3 Biome tint LUT — `BiomeTintLut`

- `GL_TEXTURE_2D`, `GL_RGBA8`, **width 4** (col 0 grass, 1 foliage, 2 water, 3 spare), **height 512** (9-bit biome palette), `GL_NEAREST`, no mips.
- Row content per biome palette id, evaluated once on the client thread when the id registers: `biome.getGrassColor(0,0)`, `biome.getFoliageColor()`, `biome.getWaterColor()` (position-noise variants sampled at origin — accepted simplification).
- Update path: biome palette registration enqueues `(row, rgb×3)` into a `ConcurrentLinkedQueue` drained at render start with one `glTexSubImage2D(row)` each (same pixel-store hardening block as `updateChunkMask`). Full rebuild on world join and resource reload.
- `BiomeTintTable` (CPU mirror, `int[512*3]`) is what the mesher uses for the CPU flat color at offset 8.
- Fragment: `tint = (tintType==0) ? vec3(1) : texelFetch(u_biomeLut, ivec2(tintType-1, biomeId), 0).rgb;` then `rgb = mix(rgb, rgb*tint, maskValue)`.

---

## 3. Draw organization, index buffer, uploads, memory

### 3.1 Draw unit: aggregated region, per level

**Decision: draw unit = `VoxelRegionMesh` = one VBO covering 4×4 sections XZ × full height, per (level, rx, rz).** Per-section draws are untenable without MDI: at 4096 blocks the L0 collar alone is ~3 000 section draws. Region math at 4096 blocks (defaults, `lodRingWidth=1024`, circle residency π/4):

| Level | Ring (blocks) | Region span | Resident regions (circle) |
|---|---|---|---|
| 0 (collar+near) | 0–1024 | 128 | ≈ 201 |
| 1 | 1024–2048 | 256 | ≈ 151 |
| 2 | 2048–4096 | 512 | ≈ 151 |
| **Total** | | | **≈ 500 resident; ~150–250 drawn/frame after frustum** |

(At larger distances L3/L4 rings appear; totals stay ≤ ~700.) Today's system holds ~3 200 region meshes at the same distance and draws ~800–1000 — the new scheme is *fewer* draw calls. Each draw = 1× `glUniform3f` + 1–2× `glDrawElements`.

### 3.2 Shared quad index buffer — `SharedQuadIndexBuffer`

One global static EBO (new — none exists today): u32 indices, pattern `4q+{0,1,2, 2,3,0}`, sized `MAX_QUADS_PER_REGION*6*4` = 3 MB, filled once lazily on the render thread, bound inside every mesh VAO. Draws:

- opaque: `glDrawElements(GL_TRIANGLES, opaqueQuads*6, GL_UNSIGNED_INT, 0)`
- translucent: `glDrawElementsBaseVertex(GL_TRIANGLES, translucentQuads*6, GL_UNSIGNED_INT, 0, opaqueQuads*4)` (GL 3.2 core — macOS-safe).

### 3.3 Upload path & lifecycle

Reuse `LodRenderer`'s exact queue/epoch pattern: worker `submit(regionKey, MeshData, jobEpoch)` under `epochLock`; render-thread `processUploads` bounded by `maxUploadsPerFrame` (default 4) **plus a new byte budget `MAX_UPLOAD_BYTES_PER_FRAME = 8 MB`** (large L0 regions). `GL_STATIC_DRAW`, whole-mesh replacement on change (same as today — no sub-updates). `evictOutside` becomes per-level: keep radius in *that level's* region units (`radiusRegions(level) = ceil(ringOuter(level) / regionSpan(level)) + 2`).

### 3.4 Memory estimates (defaults, 4096 blocks)

Assumptions: typical surface region ≈ 16–24 non-trivial sections; 2D greedy on natural terrain ≈ 250–400 quads/surface-section; coarser levels merge better (mip homogenizes states; oceans/plains hit the 16×16 cap).

| | Per region | Count | Total VRAM |
|---|---|---|---|
| L0 regions | 0.5–0.8 MB | ~200 | 100–160 MB |
| L1 | 0.3–0.5 MB | ~151 | 45–75 MB |
| L2 | 0.2–0.4 MB | ~151 | 30–60 MB |
| Atlas + mask + LUT + EBO | | | ~35 MB |
| **Total** | | | **≈ 210–330 MB** (today est. 60–120 MB → **2–4×**, inside the predicted 2–5×) |

Mitigation knobs: `maxLodVramMb` config (default 512) — renderer tracks summed VBO bytes and clamps effective lodDistance when exceeded (log once).

---

## 4. LOD level selection & seams — `VoxelLodSelector`

Map today's `desiredScale` (HorizonLod.java:454) onto mips:

```java
static int levelFor(double dist, int rdBlocks, HorizonConfig cfg) {
    if (dist - 91 < rdBlocks + 256) return 0;                    // collar, unchanged test
    int s = Integer.highestOneBit(Math.max(1, (int)(dist / cfg.getLodRingWidth())));
    s = Math.max(cfg.getBaseLodScale(), s);
    return Math.min(31 - Integer.numberOfLeadingZeros(s), MAX_LEVEL);   // scale 1,2,4,8,>=16 -> L0..L4
}
```

Scheduler: the existing ring sweep in `HorizonLod.scheduleMeshes` runs once **per level** over that level's region grid, restricted to the level's annulus `[ringInner, ringOuter)` with **one-region overlap outward** (finer ring extends one coarse-region width past its boundary so the coarse wall is always backed). Hysteresis reuses today's rule verbatim with `level ± 1` in place of `scale/2..2×scale`.

**Seam policy (decided): boundary walls, no T-junction stitching, no separate skirt code.** Same-level neighbor regions share the cell grid → zero cracks. Cross-level boundaries: the missing-neighbor=AIR rule (§1.1) emits a full closed wall on both sides; combined with the finer ring's one-region overlap and polygon offset 3/3, coarse geometry sits *behind* fine geometry and any T-junction crack shows backing wall, never sky. Overdraw cost is one 32×`h` wall per boundary section — negligible.

**Collar vs real terrain: the coverage mask survives as-is.** It is XZ-only per-chunk coverage; voxel LOD (including caves) under a loaded chunk is discarded wholesale — correct, real terrain renders there. Keep `MASK_SIZE=160`, linear filter, 0.3–0.7 dither window, `updateChunkMask` untouched, polygon offset 3.0/3.0 in both paths, `isRegionFullyCovered` reused with the level-0 region footprint only (coarser regions are never inside render distance).

---

## 5. Render loop — `VoxelRenderer`

Injection point unchanged: `MixinLevelRenderer_Horizon` at `stringValue=translucent`, priority 999 → `HorizonLod.render` → engine switch (`HorizonConfig.engine`: `classic` routes to old `LodRenderer`, `voxel` here).

Frame sequence (render thread):

1. `processUploads(budget)`; drain `BiomeTintLut` row queue.
2. Pack active? → `renderIris(...)` (§7); on false fall through.
3. No-pack pass: `updateChunkMask(...)` (reused), build `mvp`/frustum (reused), save GL state (same discipline as `LodRenderer.render` incl. the draw-buffers narrowing block for stray multi-attachment FBOs).
4. **Opaque pass**: depth test LEQUAL, depth write on, blend off, cull **on** (v2 quads have consistent winding — front faces outward; the mesher's vertex order per face direction guarantees it, unlike the old two-sided column mesh), polygon offset 3/3. Bind unit 0 atlas, 1 tint-mask atlas, 2 biome LUT, 3 chunk mask. For each mesh (all levels interleaved is fine — depth handles it): coverage-skip (L0 only) → radial reject (`lodDistanceBlocks+192`) → frustum AABB (minY/maxY tracked per mesh) → set `u_offset` (region origin − cam, y −= Y_BIAS), `u_cellSize` → draw opaque range.
5. **Translucent pass** (same injection point, still before vanilla translucent so near water blends over LOD *and* vanilla translucent draws after): blend `SRC_ALPHA, ONE_MINUS_SRC_ALPHA`, **depth write ON** (distant water is essentially a single surface; writing depth prevents underdraw artifacts and matches vanilla translucent terrain), regions CPU-sorted back-to-front by center distance (≤ ~300 entries, `Arrays.sort` on packed dist keys — trivial); intra-region unsorted (coplanar ocean surfaces; accepted at LOD distance). Draw translucent ranges only.
6. Restore GL state (each touched texture unit's binding saved/restored, active texture restored — extend the existing pattern).

Fog & light (no-pack): fog is now real (the flat-color banding objection no longer applies to textured terrain): horizontal-distance linear fog `u_fogStart = 0.8·lodDist`, `u_fogEnd = lodDist`, color from `RenderSystem.getShaderFogColor()`. Per-fragment light: `l = max(blockLight/15, skyLight/15 * u_skyFactor)` where `u_skyFactor` is the existing day/night brightness scalar from `HorizonLod.render` (0.15–1.0); final `rgb *= (0.05 + 0.95*l) * faceShade[normal]` with vanilla face shading `{0.5, 1.0, 0.8, 0.8, 0.6, 0.6}`.

---

## 6. No-pack shader (inline GLSL 150, `NoPackVoxelShader`)

```glsl
// vertex
#version 150 core
in uvec4 aPosLight;            // x, y(+512), z, lightMeta
in vec4  aColor;               // fallback flat color (unused unless atlas dead)
in uvec2 aTexInfo;             // atlasSlot, biomeId
in uvec2 aMeta;                // faceMeta, flags
uniform mat4 u_mvp; uniform vec3 u_offset;
out vec3 vRelPos;  out vec3 vLocalPos;
flat out uvec2 vTexInfo; flat out uint vFaceMeta; flat out uint vLight;
void main() {
    vLocalPos = vec3(aPosLight.xyz);                 // region-local, y biased
    vec3 rel  = vLocalPos + u_offset;                // camera-relative
    vRelPos = rel; vTexInfo = aTexInfo; vFaceMeta = aMeta.x; vLight = aPosLight.w;
    gl_Position = u_mvp * vec4(rel, 1.0);
}

// fragment
#version 150 core
in vec3 vRelPos; in vec3 vLocalPos;
flat in uvec2 vTexInfo; flat in uint vFaceMeta; flat in uint vLight;
uniform sampler2D u_atlas, u_tintMask, u_biomeLut, u_chunkMask;
uniform float u_cellSize, u_slotsPerRow, u_slotScale;    // slotScale = 16.0/atlasSize
uniform vec2 u_maskRel; uniform float u_maskTexels; uniform int u_useMask;
uniform vec4 u_fogColor; uniform float u_fogStart, u_fogEnd, u_skyFactor, u_alpha;
out vec4 fragColor;
const vec2 axesU[6] = vec2[6](...);   // face -> which two components of vLocalPos form the plane
const float faceShade[6] = float[6](0.5,1.0,0.8,0.8,0.6,0.6);
void main() {
    if (u_useMask == 1) { /* identical coverage-mask dither-discard block as LodRenderer.FRAGMENT_SHADER */ }
    uint face = vFaceMeta & 7u; uint tintType = (vFaceMeta >> 3) & 3u;
    vec2 plane = pickPlane(vLocalPos, face);         // per table above
    vec2 cellUV = plane / u_cellSize;
    vec2 local  = fract(cellUV);
    vec2 slotO  = vec2(float(vTexInfo.x % uint(u_slotsPerRow)), float(vTexInfo.x / uint(u_slotsPerRow)));
    vec2 uv     = (slotO + local) * u_slotScale;
    vec2 gx = dFdx(cellUV) * u_slotScale, gy = dFdy(cellUV) * u_slotScale;
    vec4 tex = textureGrad(u_atlas, uv, gx, gy);
    if (tintType != 0u) {
        float m = ((vFaceMeta & 32u) != 0u) ? textureGrad(u_tintMask, uv, gx, gy).r : 1.0;
        vec3 tint = texelFetch(u_biomeLut, ivec2(int(tintType) - 1, int(vTexInfo.y)), 0).rgb;
        tex.rgb = mix(tex.rgb, tex.rgb * tint, m);
    }
    float block = float(vLight >> 4u & 15u) / 15.0, sky = float(vLight & 15u) / 15.0;
    float l = 0.05 + 0.95 * max(block, sky * u_skyFactor);
    vec3 rgb = tex.rgb * l * faceShade[face];
    float f = clamp((length(vRelPos.xz) - u_fogStart) / (u_fogEnd - u_fogStart), 0.0, 1.0);
    fragColor = vec4(mix(rgb, u_fogColor.rgb, f), u_alpha);
}
```

Compile/link via the existing `initShader`/`compile` pattern with `shaderFailed` latch; attribute locations bound 0–3.

---

## 7. Phase-1 shaderpack path (flat color + real materials), Phase-2 hooks

**Decision: pack-path vertex color = CPU-computed at mesh time** = `PhotoAtlasIndex.topMipRGB(slot)` (the bake's mip-4 texel — i.e. the true average of the real face render) `× BiomeTintTable` color for the cell's biome/tintType, written to offset 8. No atlas sample in the pack path in phase 1 (packs' `dh_terrain` is flat-color by design). This is strictly better than today: correct per-block hue + correct biome tint instead of the old capture-time average, and per-face rather than per-column.

- **Material ids (irisExtra.x)**: new `DhMaterialMapper` — `int materialOf(BlockState)` cached as `byte[]` indexed by blockstate palette id, resolving to the DH material constants that `StandardMacros`' `DH_BLOCK_*` defines expose (LEAVES/STONE/WOOD/DIRT/SAND/SNOW/GRASS/WATER/LAVA/DEEPSLATE/NETHER_STONE/TERRACOTTA/ILLUMINATED/AIR) via block tags (`BlockTags.LEAVES`, `LOGS`, `DIRT`…), fluid checks, `state.getLightEmission() > 0` → ILLUMINATED, MapColor fallback. Additionally register the pack's own `WorldRenderingSettings.INSTANCE.getBlockStateIds()` mapping (WorldRenderingSettings.java:53) keyed per state palette id for phase-2 PBR use.
- **Normals**: irisExtra.y = face index — now genuinely per face from the mesher (correct on all 6 directions, unlike the 2.5D top/skirt-only geometry).
- **Light**: lightMeta now carries real captured sky/block light per face instead of constant `0x000F` — packs' `dh_terrain` lightmap path lights caves/overhangs correctly.
- **`HorizonIrisProgram` reuse: confirmed as-is.** Attribute bindings (0/1/2), uniform fills, `TransformPatcher.patchDHTerrain`, `createHorizonFramebuffer` depth-sharing, `HorizonRuntime.setMainDepthTex` publication — all unchanged. Only `renderIris` in the new renderer changes mechanically: iterate `VoxelRegionMesh`es, per-level `setModelPos(ox, relY − Y_BIAS, oz)`, `drawIris()` = bind irisVao + the two `glDrawElements` ranges (translucent range drawn in the same pass — dh_terrain handles DH translucency via blend overrides already wired in `HorizonIrisProgram.bind`; if a pack has a separate `dh_water` program, fetch it via `pipeline.getDHWaterShader()` if present, else reuse terrain — flagged as a small follow-up).
- **Phase-2 hook points (identify only)**: (a) `TransformPatcher.patchDHTerrain` call site in `HorizonIrisProgram.createProgram` — swap for a `patchHorizonTerrain` variant that injects atlas/LUT samplers, the §2.1 UV derivation, and declarations for attributes 3/4 into the patched source; (b) `ProgramSamplers.Builder` in the `HorizonIrisProgram` constructor — `addExternalSampler` for `horizonAtlas`/`horizonTintMask`/`horizonBiomeLut` on reserved units; (c) `VoxelRegionMesh.irisVao` — enable attribs 3/4 (texInfo/meta) when the phase-2 patch is active; (d) `StandardMacros` — add `HORIZON_TEXTURED` define so packs/patcher can branch.

---

## 8. GL lifecycle

**Destroy-notification fix (contract):** add to `IrisRenderingPipeline.destroy()` (repo-owned): `HorizonLod.INSTANCE.onPipelineDestroyed(this)` (null-safe static). It forwards on the render thread (destroy already runs there) to the active renderer:

```java
public void onPipelineDestroyed(IrisRenderingPipeline p) {
    if (irisPipeline == p) {
        if (irisProgram != null)     { irisProgram.free();        irisProgram = null; }
        if (irisFramebuffer != null) { irisFramebuffer.destroy(); irisFramebuffer = null; }
        irisPipeline = null; irisDepthTex = 0;
        irisFailed = false;          // a future pipeline may succeed
    }
}
```

Apply the same method to the classic `LodRenderer` (closing the existing hole for the old engine too).

**Ownership table:**

| Object | Owner | Created | Destroyed |
|---|---|---|---|
| `PhotoAtlas` GL textures (+tint mask) | `PhotoAtlas` singleton | lazily, render thread, first bake flush | resource-reload listener + client shutdown (bakes are resource-scoped, survive world changes) |
| `BiomeTintLut` texture | `VoxelRenderer` | lazily | world unload (`clear()`) — biome palette is per-world |
| `SharedQuadIndexBuffer` | static | lazily | client shutdown only |
| `VoxelRegionMesh` VAO/VBO | `VoxelRenderer.meshes` | `processUploads` | replace/evict/`clear()` |
| `HorizonIrisProgram` + FBO | `VoxelRenderer` | pipeline/depth-tex change (as today) | pipeline change **or `onPipelineDestroyed`** |
| chunk-mask texture | `VoxelRenderer` | lazily | `clear()` (as today) |

State discipline: keep `LodRenderer`'s save/normalize/restore blocks verbatim (program, VAO, per-unit texture bindings, active texture, draw-buffers narrowing, pixel-store hardening for every `glTexSubImage2D`).

---

## 9. Class list & constants summary

New, in `net.irisshaders.iris.horizon.voxel`:

| Class | Role |
|---|---|
| `VoxelConstants` | §0 constants |
| `VoxelRegionKey` | (level,rx,rz) ↔ long |
| `SectionSnapshot` | thread-local 34³ cell view (§1.1) |
| `VoxelMesher` (+ nested `MeshData`, `QuadSink`) | greedy scanline, region build (§1) |
| `LodVertexFormatV2` | offsets/stride constants + static `writeQuad(ByteBuffer, …)` |
| `VoxelRegionMesh` | VAO×2/VBO, opaque+translucent ranges, minY/maxY, coveredEpoch cache (mirrors `LodRegionMesh`) |
| `SharedQuadIndexBuffer` | global u32 EBO (§3.2) |
| `VoxelRenderer` | upload queue/epoch, mask reuse, no-pack + iris passes, `onPipelineDestroyed` (§5, §7, §8) |
| `NoPackVoxelShader` | inline GLSL 150 program holder (§6) |
| `BiomeTintLut` / `BiomeTintTable` | GPU LUT + CPU mirror (§2.3) |
| `PhotoAtlasIndex` | CPU slot/opacity/tint/topMip tables (GL atlas itself in the bake slice) |
| `DhMaterialMapper` | state → DH material byte + pack block-id registry (§7) |
| `VoxelLodSelector` | dist → level + per-level ring scheduling helpers (§4) |

Touched existing files: `HorizonLod.java` (engine switch, per-level scheduler, `onPipelineDestroyed` forward), `IrisRenderingPipeline.java` (destroy hook, unchanged framebuffer/depth API), `LodRenderer.java` (receive the same destroy hook), `HorizonConfig` 4-file pattern (add `engine`, `maxLodVramMb`, `maxUploadBytesPerFrame`). Unchanged: `MixinLevelRenderer_Horizon`, `MixinGameRenderer_Horizon`, `HorizonRuntime`, `DHCompat`, `HorizonIrisProgram`, `TransformPatcher`.

Key risk notes for implementers: (1) winding-order correctness per face direction is what enables backface culling — unit-test `normalIndex` agreement against emitted vertex order; (2) the `fract` UV wrap requires `textureGrad` — plain `texture()` will show one-texel mip seams on every cell border; (3) `irisFailed` must reset in `onPipelineDestroyed` or one bad pack permanently degrades the session; (4) missing-neighbor=AIR means the data slice must dirty-notify neighbor regions on section creation or frontier walls persist.