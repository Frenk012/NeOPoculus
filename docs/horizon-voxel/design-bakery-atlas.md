# Model Bakery + Photo Atlas â€” Design (voxel Horizon, Phase 1)

Package: `net.irisshaders.iris.horizon.voxel.model` (sibling of the voxel engine core in `net.irisshaders.iris.horizon.voxel`). Java 21, NeoForge 1.21.1, GL 4.1 baseline (no compute, no SSBO, no DSA, no `glTexStorage3D`, no `glCopyImageSubData`).

---

## 1. Bake pipeline â€” stages, threads, triggers

### 1.1 Trigger
The global incremental state palette (capture slice) calls `VoxelModelBakery.requestBake(stateId, blockState)` **at palette-registration time** â€” i.e. the first time a state id is minted during ingestion. This runs on ingestion workers, well before that section reaches the mesher, so photos are usually ready by mesh time. `requestBake` is idempotent: an atomic `putIfAbsent` of a **provisional** `PhotoMetadata` into `StateMetadataTable` decides whether to enqueue a `BakeJob`.

Meshers never block. `metadataFor(stateId)` always returns a record; if `PROVISIONAL` is set, the mesher uses the record's MapColor constant-color values (computed synchronously, CPU-only, no GL â€” reuse the `mapColorOf` discipline from `LodColors.java:162`) and stamps the built mesh "contains provisional states". When a real bake lands, the bakery notifies the voxel engine (`VoxelModelBakery.Listener.onStateReady(stateId)`); the engine re-queues affected sections at low priority (it already tracks which state ids a mesh used via the mesh's palette-usage set â€” mesh slice contract).

### 1.2 Stage split (thread ownership)

| Stage | Thread | Work |
|---|---|---|
| S0 snapshot | **Render thread** | Lazy one-time CPU copy of the blocks atlas (`AtlasSnapshot.capture()`), retaken after resource reload. Gate: no worker job runs until a valid snapshot exists for the current `atlasEpoch`. |
| S1 rasterize | **Bakery worker** (1 dedicated thread, `"NeOPoculus Horizon Bakery"`, daemon, `MIN_PRIORITY+1` â€” same pattern as `HorizonLod.java:34`) | `getQuads` fetch (thread-safe in vanilla â€” chunk meshing calls it off-thread; modded exceptions handled in Â§7), software rasterization, post-process (dilation, mips), face hashing, dedup, slot allocation. Produces `SlotUpload`s. |
| S2 upload | **Render thread** (`renderTick()` called from the voxel engine's per-frame hook, before LOD draw) | Budgeted `glTexSubImage3D` of finished faces into the two atlas arrays, `StateMetadataTable.markReady`, atlas grow/realloc, bounded render-thread retry bakes (Â§7). |

Rationale: rasterization is pure CPU math over the CPU snapshot â€” zero GL â€” so it belongs off-thread; only texture upload and `glGetTexImage` are GL and stay on the render thread. One bakery thread suffices (a bake is ~50â€“200 Âµs; 10K states â‰ˆ 1â€“2 s total, amortized over exploration) and makes the allocator/dedup map single-writer.

Every `BakeJob` and `SlotUpload` carries `atlasEpoch`; stale-epoch results are dropped (mirror of `HorizonLod`'s epoch scheme).

### 1.3 CPU atlas snapshot (`AtlasSnapshot`)
- Source texture: `Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS)`, GL id via `atlas.getId()`.
- GL4.1 path (no DSA): save current binding, `glBindTexture(GL_TEXTURE_2D, id)`, query `glGetTexLevelParameteriv(GL_TEXTURE_WIDTH/HEIGHT, level 0)`, `glPixelStorei(GL_PACK_ALIGNMENT, 1)`, `glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, ptr)` into a `MemoryUtil.memAlloc` direct buffer (off-heap; 8K modded atlas = 256 MB â€” do **not** put on heap), restore binding.
- Also capture `maxMipLevel` = `Minecraft.getInstance().options.mipmapLevels().get()` (used for sampler `MAX_LOD`, Â§5).
- Animated sprites: the GPU atlas holds the currently-ticked frame, so the snapshot inherently freezes one frame per animated sprite â€” exactly the locked "freeze first frame, cache forever" behavior. No animation hooks ever.
- Refresh: NeoForge resource-reload listener (register next to the existing `LodColors.clearCache()` hookup in `Iris.java:108`) â†’ `onResourceReload()` (Â§7). Additionally the snapshot is **dropped when idle** (`SNAPSHOT_IDLE_DROP_MS = 30_000` with an empty bake queue) to reclaim up to 256 MB, and lazily recaptured on the next `requestBake`.

### 1.4 Dedup
Two levels:
1. **Face-level (primary):** each post-processed face (16Ã—16 RGBA + tint-mask bits, hashed together, 64-bit xxHash-style + full-pixel verify against the CPU shadow on hash hit) maps to one atlas slot via `PhotoSlotAllocator.internFace()`. Stone's 6 identical faces = 1 slot; thousands of modded states share slots. Metadata stores 6 independent slot ids, so this is invisible to the renderer â€” the mesher always resolves per-face anyway.
2. **State-level (implicit):** identical whole bakes collapse to identical 6-slot tuples for free.

---

## 2. Software rasterizer (`PhotoRasterizer`)

### 2.1 Quad acquisition (1.21.1 NeoForge)
```java
BakedModel model = mc.getBlockRenderer().getBlockModel(state);
if (model == mc.getModelManager().getMissingModel()) return fallback(state);   // LodColors.java:77 discipline
RandomSource rand = RandomSource.create(RANDOM_SEED /* 42L */);
ChunkRenderTypeSet types = model.getRenderTypes(state, rand, ModelData.EMPTY);
for (RenderType rt : types) {
  for (Direction cull : DIRECTIONS_AND_NULL) {   // all 6 + null
    rand.setSeed(RANDOM_SEED);
    quads.addAll(model.getQuads(state, cull, rand, ModelData.EMPTY, rt));  // tagged with rt
  }
}
```
- All cull faces included: the block is photographed in isolation (no neighbors â†’ nothing culled).
- Fixed seed â‡’ deterministic variant for randomized models. `ModelData.EMPTY` â‡’ CTM/connected models bake their default look (accepted).
- Foreign-atlas guard: skip any quad whose `quad.getSprite().atlasLocation() != TextureAtlas.LOCATION_BLOCKS` (its UVs would index the wrong snapshot). If **all** quads are skipped/absent â†’ MapColor fallback.

### 2.2 Vertex decode
`quad.getVertices()` is `int[]`, 8 ints per vertex Ã— 4 vertices (`DefaultVertexFormat.BLOCK`, 32-byte stride): `[0..2]` position `Float.intBitsToFloat`, `[3]` vertex color packed ABGR (usually `-1`), `[4..5]` UV floats (already normalized into the blocks atlas), `[6]` lightmap UV2, `[7]` packed normal. We use position, color, UV; lightmap/normal ignored (emissive handled per-state, Â§2.6).

### 2.3 Six orthographic views
Fixed per-direction affine maps from model space `[0,1]Â³` to photo space `16Ã—16` + depth, oriented so each photo matches vanilla's on-face texture orientation (contract with the mesh slice: **UV in the photo == the UV vanilla would use on that face of a standard full cube**). Nominal maps (UP: `px=xÂ·16, py=zÂ·16, d=1âˆ’y`; NORTH: `px=(1âˆ’x)Â·16, py=(1âˆ’y)Â·16, d=z`; etc. for the other four by symmetry). **Acceptance test locks orientation, not the sign table:** baking `minecraft:furnace` must reproduce the front sprite un-mirrored/un-rotated on its NORTH photo, and `grass_block` top must match its world top face.

Depth volume normalized over `[-1.0, +2.0]` block units along the view axis (captures slightly-oversized modded models); XY outside `[0,1]` clips at the 16Ã—16 viewport.

### 2.4 Rasterization
Per view: `float[256]` depth (init `+âˆž`), `int[256]` RGBA out, `boolean[256]` tint mask.
- Two triangles per quad (`v0v1v2`, `v0v2v3`), edge-function rasterization at texel centers, barycentric-interpolated UV, vertex color, depth.
- Backface cull per view: skip quads with `dot(faceNormalOfTri, viewDir) â‰¥ 0`.
- Texel fetch: snapshot **nearest** (a 16Ã—16 photo of a 16Ã—16 sprite at 1:1 reproduces the texture exactly on axis-aligned faces).
- Shade (vanilla directional, applied when `quad.isShade()`, keyed on `quad.getDirection()`): DOWN `0.5`, UP `1.0`, N/S `0.8`, E/W `0.6`. No neighbor AO exists for an isolated block (AO term = 1.0), so directional shade + the model's own baked vertex colors are the complete, correct vanilla lighting for a lone block.
- Output RGB = texel.rgb Ã— vertexColor.rgb Ã— shade. If `quad.isTinted()` â†’ **do not** apply any tint here; set the tint-mask bit for covered texels (unless `TintClassifier` baked a constant tint, Â§3).
- Two passes per view:
  - **Pass 1 (solid + cutout render types):** depth test `<`, depth write. Cutout: discard `alpha < CUTOUT_ALPHA_THRESHOLD (26 â‰ˆ 0.1Â·255)`; solid: force alpha 255.
  - **Pass 2 (translucent):** collect covering fragments, sort back-to-front by depth per texel (a 16Ã—16 target makes this trivial â€” per-texel insertion into a small list), alpha-composite over pass-1 result, no depth write. Stained glass etc. keep real alpha.

### 2.5 Fluids â€” direct still-sprite copy (not `LiquidBlockRenderer`)
For a cell whose state is a fluid (`!state.getFluidState().isEmpty()` and the block itself is the fluid block, e.g. `LiquidBlock`):
- Sprite: `IClientFluidTypeExtensions.of(fluidState.getType()).getStillTexture(fluidState)` â†’ `mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(loc)` â†’ copy that sprite's 16Ã—16 region (frame frozen in the snapshot) directly into all 6 face photos.
- Tint: try `IClientFluidTypeExtensions.getTintColor(fluidState, null, null)` inside the Throwable catch; water (`FluidTags.WATER`) â†’ tint mask fully set, `tintKind=WATER`; constant tints (modded dyes) â†’ bake into pixels; lava â†’ none. NPE from null level â†’ water-tag check decides.
- **Justification:** `LiquidBlockRenderer` needs a `BlockAndTintGetter` neighborhood (heights, flow vectors, face culling) that is meaningless for an isolated full cell and is thread-hostile; a full-cell source fluid renders as exactly its still sprite on every face, so the direct copy is both correct and worker-thread-safe.
- Waterlogged non-fluid blocks bake the **block** model; water occupancy is the capture slice's concern.

### 2.6 Flags detected during bake
- `translucent`: any quad on `RenderType.translucent()` chain (or fluid with alpha < 255).
- `emissive`: `state.getLightEmission() > 0`; 4-bit level stored. (Per-pixel emissive masks deferred â€” metadata-level is sufficient for Phase 1 flat-shading and DH material semantics.)
- `fullCube`: `Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))` in a Throwable catch, default false.

---

## 3. Tint mask + tint classification

**Storage decision: a second parallel `GL_TEXTURE_2D_ARRAY`, format `GL_R8`**, identical slot layout and mip count to the color atlas. Rejected alternatives: the alpha-channel trick collides with real translucency alpha (water, glass) and would fork shader logic; there is no SSBO/metadata-texture indirection available at the GL4.1 floor worth the complexity. Cost is +25% atlas memory. Mask mips are averaged like color, giving correct partial tint at distance (e.g. `grass_block` side overlay fades properly). Fragment shading: `rgb = photo.rgb * mix(vec3(1.0), tintColor, mask.r)`.

`TintClassifier` (bake time, worker thread, all in Throwable catch):
1. If no quad tinted â†’ `tintKind = NONE`, mask empty.
2. Category tags decide the shade-time colormap: `BlockTags.LEAVES` or vines â†’ `FOLIAGE`; grass block / short+tall grass / fern / sugar cane â†’ `GRASS`; water fluid / `FluidTags.WATER` â†’ `WATER`.
3. Otherwise try `mc.getBlockColors().getColor(state, null, null, tintIndex)`; a non-`-1` result with null level is position-independent (redstone wire, stems) â†’ **bake it into the pixels**, `tintKind = BAKED`, mask stays empty.
4. Provider throws on null level (biome-dependent modded tint) â†’ assume `GRASS` kind, mask set â€” the same "assume grassy" bias that fixed untinted grey grass in `LodColors.java:59`.

Shade-time tint colors come from the biome id in the cell (voxel-engine shader slice): a small biomeâ†’(grassRGB, foliageRGB, waterRGB) LUT texture. `tintKind` travels to the shader **baked into vertex data at mesh time** (locked decision 6).

---

## 4. Post-process (`PhotoPostProcessor`)

Order per face: **dilate â†’ (leaves branch) â†’ mip chain**.

1. **Edge dilation** (kills black/zero-alpha bleed in linear mips): texels with `alpha == 0` are "empty". Iterative flood: up to `DILATE_MAX_PASSES = 16` passes (worst-case 16Ã—16 diagonal), each pass gives every empty texel with â‰¥ 1 non-empty 4-neighbor the average **RGB** of those neighbors; **alpha stays 0** (coverage is semantic). Early-out when a pass changes nothing. Fully-empty face after rasterization â†’ state falls back to MapColor (Â§7).
2. **Mip chain**: 5 levels total, 16â†’8â†’4â†’2â†’1 (`MIP_LEVELS = 5`, complete GL chain, `GL_TEXTURE_MAX_LEVEL = 4`). 2Ã—2 box in **linear space**: `ColorSpace` 256-entry sRGBâ†’linear float LUT (+ float `linearToSrgb`); per child `i`: `lin += toLinear(c_i) * a_i`, result RGB `= toSrgb(lin / Î£a)` (if `Î£a == 0`, plain average of dilated RGB), `alpha = Î£a/4` rounded. Mask mip: plain 2Ã—2 average (it is coverage, already linear).
3. **Leaves / darkened-mip branch.** Criterion: `LeafLikePredicate.test(state)` = `state.is(BlockTags.LEAVES)` **or** the capture slice's forced-opaque classification â€” this predicate lives in this package and is the **single shared source of truth** for both the mapper's "leaves forced opaque" rule and the bakery, so occlusion and visuals can never disagree. Branch behavior: after dilation, base-level alpha forced to 255 (holes take dilated colors); each mip level `L â‰¥ 1` gets RGB Ã— `LEAF_MIP_DARKEN^L` (`0.85f`, linear-space multiply) â€” approximating the self-shadowed canopy interior so distant foliage converges to a solid, slightly dark tinted mass instead of alpha-punching holes.

Per-face `faceAvgColor` = the final 1Ã—1 mip texel (level 4), with tint **not** applied â€” this is the constant-color fast path, the far-ring color, and the Phase-1 shaderpack flat color.

---

## 5. Photo atlas (`PhotoAtlasTexture` + `PhotoSlotAllocator`)

**Layout decision: `GL_TEXTURE_2D_ARRAY` with real mip levels, where each layer is a 256Ã—256 grid of 16Ã—16 photo cells** (16Ã—16 cells = `PHOTOS_PER_LAYER = 256` slots/layer). Slot math: `layer = slot >>> 8`, `cellX = slot & 15`, `cellY = (slot >>> 4) & 15`.
- Why not one-photo-per-layer: `GL_MAX_ARRAY_TEXTURE_LAYERS` is only guaranteed 2048 on GL4.x â€” too few face slots for 10K-state modpacks. The grid layout reaches 24K+ slots in â‰¤ 96 layers.
- Why not a single 2D atlas with manual mip regions: real array mips let plain `textureLod`/auto-LOD work; core since GL 3.0, fine on macOS 4.1.
- Grid-in-layer mip aliasing is safe because the sampler is **`GL_NEAREST_MIPMAP_LINEAR` (min) / `GL_NEAREST` (mag)** â€” nearest-within-level never crosses a cell boundary when the shader clamps photo-UV strictly inside the cell (Îµ = half a texel at the current level; at level 4 a cell is exactly 1 texel). This is also the sampler that preserves the crisp "real texture" look. `GL_TEXTURE_MAX_LEVEL = 4`, `GL_TEXTURE_MAX_LOD = min(4, options.mipmapLevels)` (respect the user's vanilla mip setting, as Voxy's research notes), wrap `GL_CLAMP_TO_EDGE`, LOD bias 0.
- Internal format `GL_RGBA8` storing sRGB bytes (mips were hand-generated gamma-correctly; `GL_SRGB8_ALPHA8` would double-convert against the existing flat-color pipeline). Mask atlas: `GL_R8`, same geometry.
- **GL4.1 allocation:** `glTexImage3D` per mip level (mutable storage; `glTexStorage3D` needs 4.2), levels 0..4 with `NULL` data, `GL_UNPACK_ALIGNMENT = 1` on uploads.

**Capacity/growth/eviction:** start `INITIAL_LAYERS = 16` (4096 slots â‰ˆ 6.8 MB for color+mask; per-layer cost = 341 KB color + 85 KB mask), grow Ã—2 when full up to `MAX_LAYERS = 96` (24,576 slots â‰ˆ 41 MB). Growth on GL4.1 (no `glCopyImageSubData`) = allocate new arrays, re-upload every live face from the **CPU shadow store** (the allocator keeps each unique face's post-processed mips on heap, ~1.8 KB/face compressed into one `byte[]`; 24K faces â‰ˆ 43 MB worst case â€” also what makes reload cheap), destroy old arrays; done in one render-thread `renderTick` (a one-off ~40 ms hitch at worst, in practice growth happens early and small). Beyond `MAX_LAYERS`: **no LRU eviction in v1** (eviction forces remeshing every referencing section â€” churn for a case face-dedup makes near-impossible; 24K unique *faces* exceeds any observed modpack); overflow states get the shared MapColor-fallback slot and a rate-limited warn.

**Upload batching:** `pendingUploads: ArrayDeque<SlotUpload>` drained by `renderTick`, budget `MAX_FACE_UPLOADS_PER_FRAME = 32` faces/frame (32 Ã— 5 mips Ã— 2 textures = 320 `glTexSubImage3D` of â‰¤ 1 KB â€” negligible). A face's metadata is only marked ready **after** its upload executes, so meshes never reference unwritten slots.

Binding contract (render slice): `bind(int colorUnit, int maskUnit)`; Phase-1 no-pack shader declares `uniform sampler2DArray u_photoAtlas, u_tintAtlas;`.

---

## 6. Per-state metadata (`StateMetadataTable`, `PhotoMetadata`)

No SSBOs at the floor â‡’ **all metadata is CPU-side and consumed at mesh time**, baked into vertex data. Lookup structure: `stateId`-indexed `PhotoMetadata[]` behind a `volatile` array reference (copy-on-write growth in palette-id order; readers lock-free â€” same access pattern the mesh workers already use for palettes).

```java
public final class PhotoMetadata {
    public final int stateId;
    public final short[] faceSlot;     // [6] atlas slot per face; provisional/fallback â†’ shared MapColor slot
    public final int[]   faceAvgColor; // [6] 0xAARRGGBB, level-4 texel, untinted â€” const-color fast path,
                                       //     far-ring flat color, Phase-1 shaderpack color
    public final byte tintKind;        // 0 NONE, 1 GRASS, 2 FOLIAGE, 3 WATER, 4 BAKED(already in pixels)
    public final byte faceTintBits;    // bit d set = face d has â‰¥1 masked texel (skip tint math when clear)
    public final byte flags;           // bit0 TRANSLUCENT, bit1 EMISSIVE, bit2 LEAF_DARKENED,
                                       // bit3 PROVISIONAL (bake pending), bit4 FALLBACK (MapColor final),
                                       // bit5 FULL_CUBE, bit6 CONSTANT_COLOR (all 6 faces uniform 1-texel)
    public final byte lightEmission;   // 0..15, state.getLightEmission()
    public final byte dhMaterialId;    // DH material semantics for the shaderpack path (DhMaterials)
}
```
- `CONSTANT_COLOR` set when every face's 16Ã—16 is a single color â†’ mesher can skip atlas UVs entirely for that quad (vertex-color path), a big win for concrete/planks-heavy scenes.
- `dhMaterialId`: static classification in `DhMaterials` mapping block tags/classes onto the same `DH_BLOCK_*` constants Iris's `StandardMacros` already defines (water, leaves, wood, stone, lava, snow, grassâ€¦), fixing the dead byte0 of `irisExtra` that `LodMesher.vertex()` currently hardcodes to 0. Phase-1 shaderpack path = `faceAvgColor` + `dhMaterialId` + real normal index + real light â€” no atlas sampling under packs.
- Mesh-time consumption contract (mesh slice): per quad the mesher reads the metadata of the winning cell's state and writes atlas slot (16 bits), `tintKind` (3 bits), `TRANSLUCENT` routing (separate draw bucket), `EMISSIVE`/`lightEmission` into the light channel, `dhMaterialId` into irisExtra byte0.

---

## 7. Failure discipline + lifecycle

- **Per-bake Throwable catch** around the entire S1 job (`getQuads` included). First failure of a state â†’ enqueue **one render-thread retry** (`retryQueue`, budget `MAX_RENDER_THREAD_RETRIES_PER_FRAME = 4`) â€” a meaningful share of modded models only misbehave off-thread. Second Throwable â†’ **final MapColor fallback**: a uniform 16Ã—16 photo of `state.getMapColor()` (`MapColor.NONE` â†’ `0x7F7F7F`, exactly `LodColors.mapColorOf`), interned like any face (all MapColor fallbacks share â‰¤ 62 slots), `FALLBACK` flag set, `tintKind = NONE`.
- **Log-once:** `LongOpenHashSet loggedStates`; first failure per state logs at WARN with state + exception class only (no stack at WARN; stack at DEBUG); every 100th aggregate failure logs a count summary.
- **Empty results** (no quads, all-foreign-atlas, fully-transparent raster) â†’ same MapColor fallback, no retry.
- **Resource reload** (`onResourceReload()`, render thread, registered beside the `LodColors.clearCache()` listener in `Iris.java:108`): `atlasEpoch++`; clear bake queue + retry queue + pending uploads; free `AtlasSnapshot`; reset `PhotoSlotAllocator` (dedup map + CPU shadows) and `StateMetadataTable`; destroy and lazily recreate both GL arrays (handles atlas-size/mipmap-setting changes). The voxel engine observes the epoch bump and rebuilds meshes lazily; every state re-bakes on next sighting. In-flight worker results with the old epoch are dropped at the S2 gate.
- **Engine/pipeline destroy:** `destroy()` (render thread) frees GL arrays, snapshot, queues; wired into the voxel engine's explicit destroy hook â€” which per the locked decisions must also close the existing `LodRenderer` GL-lifecycle hole (pipeline destroy must notify the renderer/bakery, not rely on reload-detection).

---

## 8. Class list, API sketch, constants

```
net.irisshaders.iris.horizon.voxel.model/
  VoxelModelBakery      â€“ subsystem facade (owned by the voxel engine)
      void   requestBake(int stateId, BlockState state)      // any thread; idempotent
      PhotoMetadata metadataFor(int stateId)                 // any thread; never null, never blocks
      void   renderTick()            // render thread: snapshot, retries, uploads, growth
      void   onResourceReload()      // render thread
      void   destroy()               // render thread
      int    atlasEpoch()
      void   bindAtlases(int colorUnit, int maskUnit)
      interface Listener { void onStateReady(int stateId); } // engine remesh hook
  AtlasSnapshot         â€“ off-heap CPU copy of blocks atlas; capture()/free(); sampleNearest(u,v); copySpriteRegion(sprite, dst)
  BakeWorker            â€“ single thread + LinkedBlockingQueue<BakeJob>; epoch-stamped
  PhotoRasterizer       â€“ RasterResult bake(BlockState, AtlasSnapshot)   // worker; pure CPU
  RasterResult          â€“ FacePhoto[6] + detected flags
  FacePhoto             â€“ int[256] rgba, byte[256] mask, float[256] depth (scratch)
  PhotoPostProcessor    â€“ dilate(), buildMips(), leavesDarken(); static
  ColorSpace            â€“ static sRGB<->linear LUTs
  TintClassifier        â€“ tintKind + constant-tint evaluation; static
  LeafLikePredicate     â€“ shared leaves/forced-opaque predicate (bakery + capture mapper)
  DhMaterials           â€“ BlockState -> DH material byte (StandardMacros DH_BLOCK_* values)
  PhotoSlotAllocator    â€“ face intern/dedup (long-hash map + pixel verify), slot alloc, CPU shadow store
  PhotoAtlasTexture     â€“ GL color+mask GL_TEXTURE_2D_ARRAYs, upload queue, grow/realloc, sampler params
  StateMetadataTable    â€“ volatile PhotoMetadata[] by stateId; putProvisional/markReady
  PhotoMetadata         â€“ Â§6
```

**Constants** (`VoxelModelConstants`): `PHOTO_SIZE=16`; `MIP_LEVELS=5` (max level 4); `LAYER_SIZE=256`; `PHOTOS_PER_LAYER=256`; `INITIAL_LAYERS=16`; `MAX_LAYERS=96`; `MAX_FACE_UPLOADS_PER_FRAME=32`; `MAX_RENDER_THREAD_RETRIES_PER_FRAME=4`; `CUTOUT_ALPHA_THRESHOLD=26`; `LEAF_MIP_DARKEN=0.85f`; `DILATE_MAX_PASSES=16`; `RANDOM_SEED=42L`; `SNAPSHOT_IDLE_DROP_MS=30_000`; `FALLBACK_RGB=0x7F7F7F`; depth volume `[-1.0, 2.0]`; shade `{DOWN .5, UP 1, N/S .8, E/W .6}`; sampler `NEAREST_MIPMAP_LINEAR/NEAREST`, `CLAMP_TO_EDGE`, `MAX_LEVEL=4`, `MAX_LOD=min(4, mipmapLevels)`.

**Budgets/memory:** GPU â‰ˆ 426 KB/layer (color+mask), 6.8 MB initial â†’ 41 MB hard cap; CPU shadow â‰¤ ~43 MB worst case; snapshot â‰¤ 256 MB transient (idle-dropped). Nothing here is persisted â€” photos are resource-pack-dependent and rebake in seconds; only the stateIdâ†”BlockState palette persists (storage slice).

**Acceptance tests:** furnace NORTH-face orientation; grass_block top vs world; leaves mip-4 = solid darkened green with mask=1; water photo = still sprite with `tintKind=WATER`; a state whose model throws off-thread lands MapColor after one render-thread retry with exactly one WARN line; resource-pack switch rebuilds every photo with no stale-slot sampling (epoch gate).

Key existing files this design touches or reuses patterns from: `C:\Users\franc\IdeaProjects\NeOPoculus\src\main\java\net\irisshaders\iris\horizon\LodColors.java` (fallback + reload discipline, superseded by this subsystem in voxel mode), `C:\Users\franc\IdeaProjects\NeOPoculus\src\main\java\net\irisshaders\iris\Iris.java` (reload-listener registration site, line 108), `C:\Users\franc\IdeaProjects\NeOPoculus\src\main\java\net\irisshaders\iris\horizon\HorizonLod.java` (worker-thread + epoch pattern), `C:\Users\franc\IdeaProjects\NeOPoculus\src\main\java\net\irisshaders\iris\gl\IrisRenderSystem.java` (capability checks; no compute used here).