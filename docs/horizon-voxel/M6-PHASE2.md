# M6 phase 2 — draw voxel LOD through the pack's `gbuffers_terrain`

## Why

Five of the sixteen test packs ship no `dh_terrain` program at all: **Kappa,
Lux, Aurora, Noble, The Better Default**. Measured with `./gradlew runShaderTest`.
For those packs there is no DH program to drive, so the LOD pass stands down and
they render a correct world with no distant terrain.

Drawing them with Horizon's built-in flat-colour program is not an option and
must never be reinstated: it writes flat colour straight into the pack's
gbuffers, the pack lights it as albedo, and the frame is destroyed — Kappa
rendered the **whole world black** behind a white LOD silhouette.

`gbuffers_terrain` is the systemic answer because every pack has one by
definition. One path, no per-pack special cases, works for packs that do not
exist yet.

## What already works and must not regress

Packs that ship `dh_terrain` render correctly today: Complementary Reimagined,
Complementary Unbound, BSL, Sildur's, Solas, Bliss. The machinery is
`HorizonIrisProgram` + `TransformPatcher.patchDHTerrain` → `DHTerrainTransformer`
+ `IrisRenderingPipeline.createHorizonFramebuffer`.

`createHorizonFramebuffer` is **source-agnostic** — it reads only
`sources.getDirectives().getDrawBuffers()` and attaches
`renderTargets.getDepthTexture()` (IrisRenderingPipeline.java:1379-1384), the
same body as `createGbufferFramebuffer`. It needs no change.

Implement the new flavour as a `textured` branch **inside**
`DHTerrainTransformer`, not a copied class, so the two cannot drift.

## Design

### UVs cannot tile — accept stretching

Quads are greedy-merged up to `MERGE_CAP = 16` cells. The built-in shader
repeats the sprite with a fragment-side `fract(plane / u_cellSize)`
(NoPackVoxelShader.java:101-107). A pack computes its own `texcoord` varying in
its own vertex shader; we can rewrite the input but cannot reach inside the
varying, and linear interpolation cannot produce a `fract`. The atlas is one
packed `GL_TEXTURE_2D` with `CLAMP_TO_EDGE`, so running past a slot edge samples
the neighbouring block's photo.

So map **one slot across one merged quad** (stretched, not tiled). Worst case a
16×16-cell plate shows one smeared sprite, converging to roughly the flat colour
those packs get today — it degrades to today's look, never worse, while gaining
the pack's own shadows, fog, normals and material handling.

The corner index needs **no mesh change**: `SharedQuadIndexBuffer` emits
`4q + {0,1,2,2,3,0}` (SharedQuadIndexBuffer.java:30-39) and every emitter writes
ring order `(0,0) (1,0) (1,1) (0,1)` — `emitQuad` :620-653, shaped-cell :379-405,
cross-plant :458-463. Therefore `uint(gl_VertexID) & 3u` **is** the corner index.

### Samplers

Do **not** flip `hasTexture` to true. That makes `gtexture` an external sampler
pinned to unit 0, forcing a manual bind of the photo atlas over the block atlas
on the shared unit 0 — the exact failure this codebase documents as permanent
black terrain (VoxelRenderer.java:548-555).

Instead register the atlas as a **dynamic** sampler:
`samplers.addDynamicSampler(atlasSupplier, "tex", "texture", "gtexture", "horizon_atlas")`.
`ProgramSamplers.update()` then binds it on a free unit ≥3 and unit 0 is never
touched. Lightmap stays external on unit 2 as it is now.

`normals` and `specular` must be neutralised — `IrisSamplers.addLevelSamplers`
points them at the block atlas's PBR maps, and sampling those with a photo-atlas
UV yields random LabPBR data. Bind 1×1 flat-normal `(128,128,255,255)` and
zero-specular `(0,0,0,0)`.

### Legacy attributes — type-matched replacement, not fixed arity

**This is where the first spec was wrong and it is a hard compile failure.**
Substituting a fixed arity breaks real packs in our own corpus:

- PaintBound `gbuffers_textured.vsh:16` declares `attribute vec4 at_midBlock;`
  and does `emissiveFlag = at_midBlock.w;` — substituting `vec3(0.0)` makes
  `vec3(0.0).w`, an illegal swizzle.
- photon `program/gbuffers_all_solid.vsh:112` and Noble
  `programs/gbuffers/opaque.glsl:78` declare `attribute vec2 mc_midTexCoord;`.

Iris already solves this: `SodiumTransformer.replaceMidTexCoord` (:202-249) and
`EmbeddiumTransformer` (:162-217) read the declared `BuiltinNumericTypeSpecifier`,
delete the declaration, and emit a type-matched replacement. **Use that
machinery.**

Values: `mc_Entity → (0.0, -1.0, 0.0, 1.0)` — block id 0 means "not a waving
block", and `-1` is Iris's `BLOCK_RENDER_TYPE`. Every waving branch read is gated
on `mc_Entity.x` matching a `block.properties` id, so id 0 disables displacement
without touching the pack's maths.

`at_tangent` needs a **per-face** constant, not zero: `normalize(vec3(0))` is NaN
and blows the pack's TBN into black.

Also `glBindAttribLocation` the legacy names to locations 8-12 before linking, so
the linker cannot assign one to 0-3 and read Horizon's position/colour/extra as
garbage. Declarations survive the reference rewrite, so this matters.

### `far`

Inverted versus the DH path. There, `far` is deliberately left at the vanilla
render distance (HorizonIrisProgram.java:308-317) because the pack knows it is
drawing DH terrain and fogs against `dhFarPlane`. A `gbuffers_terrain` program
has no idea it is drawing LOD and fogs against `far`, so leaving it vanilla fogs
every LOD fragment to solid. In terrain mode override `far` to
`HorizonRuntime.farPlane()` **after** `uniforms.update()`, like `dhProjection`.
Separate GL program object, so it cannot leak into the pack's real terrain pass.

## Corrections the adversarial pass found — do not skip these

1. **Keep the `Iris.isPackInUseQuick()` stand-down** in `VoxelRenderer.render()`.
   All three reviewers flagged its removal. `renderIris` returns false on several
   paths — including while `bakery.atlasTexture() == 0` on the first frames of
   every world load, and permanently after any `irisFailed` — and each one falls
   into the built-in program. Deleting the guard makes Kappa black at every spawn.
2. `hasGeometry`/`hasTesselation` live on `GeometryInfoParameters`;
   `DHTerrainTransformer.transform` takes `Parameters`, and the DH flavour passes
   `DHParameters`, which does not extend it. Guard the fragment cutout accordingly
   or it will not compile.
3. Guard program creation with `if (irisTerrain == null)` / `if (irisWater == null)`
   as the existing code does (VoxelRenderer.java:273, :278) — the rebuild block
   re-enters on `irisDepthTex` change alone, so unconditional assignment leaks a
   GL program on every window resize.
4. `irisWater` must also receive `horizon_atlasParams`, or every translucent quad
   computes `floor(slot / 0.0)` → NaN UVs.
5. The "pack ships no terrain program" branch is unreachable:
   `ProgramFallbackResolver.resolveNullable` walks Terrain → TexturedLit →
   Textured → Basic, and every pack ships `gbuffers_basic`. Handle that instead of
   logging a case that cannot happen.
6. `HorizonIrisProgram.bind()` applies `bufferBlendOverrides` (:253-255) which
   leak past the pass. Restore them the way the plain blend override is restored.

## Verification

`./gradlew runShaderTest` — unattended, screenshots every pack into
`run/screenshots/`, quits by itself. Success is Kappa, Lux, Aurora, Noble and The
Better Default showing shaded distant terrain, **and** the six packs that work
today still working. Both halves matter; the sweep shows both in one run.
