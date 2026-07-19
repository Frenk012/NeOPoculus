# Horizon Voxel LOD — Handoff / Resume Notes

> Snapshot for continuing on another machine. Branch: `feature/lod-voxel-rework`.
> Last updated end of the M3 session (2026-07-19).

## How to resume on another PC

1. `git fetch origin && git checkout feature/lod-voxel-rework` (the branch is pushed to `origin`).
2. Build/run: `./gradlew runClient`. In-game, open the Horizon LOD settings and enable
   **"Voxel Engine (Experimental)"** (config key `engine=voxel` in `neoculus-horizon.properties`);
   it applies on world reload. Fly out / lower render distance to see the LOD past loaded chunks.
3. F3 shows two Horizon lines: the data line (`Horizon/voxel: L0.. hot, warm.. palette.. queue`)
   and the render line (`Horizon/voxel render: meshes.. drawn.. built..`).
4. The design is authoritative: `docs/horizon-voxel/DESIGN.md` (unified, resolutions R1–R9) plus the
   three slice docs (`design-data-storage.md`, `design-bakery-atlas.md`, `design-mesh-render.md`).

**Claude Code context note:** the conversation transcript and the auto-memory files are LOCAL to the
original machine (`~/.claude/projects/.../memory/`), they do NOT travel with git. The two relevant
memory notes are reproduced at the bottom of this file so a fresh session on the new PC has them.
Point the new session at this HANDOFF.md and the DESIGN docs.

## Milestone status

- **M0 done** — GL-lifecycle fix (IrisRenderingPipeline.destroy → LodRenderer/VoxelRenderer) + config
  toggle `engine=classic|voxel` + budget keys + scaffold. Classic 2.5D engine untouched behind the toggle.
- **M1 done + verified** — data core: 32³ sections, 64-bit cells (state20/biome9/light8), global
  palettes, client-thread snapshots → worker convert/pyramid/merge into all 5 mip levels, incremental
  remipper. Package `net.irisshaders.iris.horizon.voxel`.
- **M2 done + verified** — persistence: VoxelSectionCodec (UNIFORM/BITPACK/RLE/RAW), VoxelRegionStorage
  (`<worldId>/<dim>/voxel/L<n>/r.*.hlod`, gzip, atomic, quarantine), VoxelStore (HOT/WARM/disk),
  palette.nbt. Adversarial review closed 2 majors (cross-world palette race, flushAll-vs-cycle race).
- **M3 done + verified in-game** — mesher + flat-color render. Voxel terrain renders as solid 3D relief
  with real MapColor colors and real light, fast incremental loading, clean boundary vs loaded chunks.

## M3 open issues (start here next session)

1. **"Light always day" (user report)** — the user says the LOD stays day-lit and doesn't respond to
   real light. Per-cell light IS written now (VoxelMesher `faceLight`), but verify the day/night side:
   `VoxelRenderer.render` sets `skyFactor` from `mc.level.getSkyDarken(1.0f) >= 4 ? 0.3f : 1.0f` — that
   method/threshold may be wrong (always returning day). Check `getSkyDarken` semantics in 1.21.1, and
   confirm block/sky light values actually vary in the cells (they may be captured before the chunk's
   light is computed — async light — and never re-captured; that's the M5 light path). Likely a quick
   skyFactor fix + the M5 re-capture-on-light-update.
2. **Rare black blocks** — mostly shadowed recesses/cave mouths (correct-ish) softened by the 0.2 ambient
   floor; a few may be air cells whose skylight wasn't computed at capture time (async light, see #1).
3. **Shaderpack = white outlines, no colors** — expected: this is **M6**. In voxel mode the LOD always
   uses the no-pack GLSL150 path; with a pack active it writes into the pack's gbuffer which can't
   interpret it. Fix = route the voxel meshes through the pack's `dh_terrain` like the classic
   `HorizonIrisProgram` does. The voxel vertex format v2 already exposes DH-compatible attrs 0/1/2
   (see `VoxelRegionMesh` — it only binds the voxel VAO; add the iris VAO like `LodRegionMesh.drawIris`).
   **User plays with shaders → consider doing M6 before M4.**

## Remaining milestones

- **M4** — model bakery + textured atlas (the "wow": monochromatic → 1:1). Task in the tracker; spec in
  `design-bakery-atlas.md`. Vertex format already carries `atlasSlot`/`biomeId`/`faceMeta` to populate.
- **M5** — translucency + fluids + proper per-face light (fixes issues #1/#2), emissives, leaf darkening.
- **M6** — shaderpack phase-1 (route through `dh_terrain`; real DH material ids, normals, light).
- **M2b** — disk cache size cap + "Clear LOD cache" button (user-approved).
- **M3 polish** — backface culling (winding is consistent; two-sided now, enabling halves fragments),
  perf tuning, block-edit→remesh latency.

## Architecture quick map (voxel package)

- Data: `VoxelCell`, `SectionKey`, `VoxelSection`, `SectionPool`, `VoxelWorld` (HOT map), `VoxelPalettes`.
- Store/persist: `VoxelStore` (HOT/WARM/disk), `VoxelSectionCodec`, `VoxelRegionStorage`.
- Ingest: `ChunkSnapshotter` (client), `ChunkPyramid`, `VoxelIngest`, `VoxelMipper`; `VoxelEngine`
  orchestrates (queues, budgets, save/evict cycle, dirty-mesh-region notify).
- Mesh/render: `SectionSnapshot` (34³), `VoxelMesher` (+`MeshData`), `LodVertexFormatV2`,
  `SharedQuadIndexBuffer`, `VoxelRegionMesh`, `VoxelLodSelector`, `VoxelRegionKey`, `VoxelRenderer`,
  `NoPackVoxelShader`, `VoxelColorTable` (M3 flat MapColor, replaced by the atlas in M4).
- Wiring: `HorizonLod` (engine toggle in every event handler, `scheduleVoxelMeshes` center-out sweep +
  dirty re-mesh, `render` route, coverage-mask lives in `VoxelRenderer`). Mixin
  `MixinClientLevel_Horizon` funnels block updates.

## Hard-won debugging learnings (do not re-discover)

- **`PalettedContainer.getAll(consumer)` enumerates the palette's UNIQUE values, NOT the 4096 cells.**
  This was the root cause of "sparse/striped terrain"; `ChunkPyramid` must iterate cells with
  `states.get(x,y,z)`. (Biggest bug of the session.)
- **Face occlusion:** use `state.canOcclude()`, not `getLightBlock(EmptyBlockGetter,…)` (returns 0 for
  solid blocks → internal faces at every block boundary).
- **Greedy merge key = state+biome only** (light excluded) or surfaces shatter into 1-cell strips; light
  is carried in a parallel `faceLight` array, quad takes its origin cell's value.
- **Scheduler must sweep center-out** (near regions first) or the per-tick budget churns the far empty
  corner forever and the player's own region is never meshed.
- **Dirty-remesh on chunk ingest** (`VoxelEngine.markMeshRegionsDirty`) or newly-explored terrain stays
  floating pillars (regions meshed from partial data, never refreshed).
- **Coverage mask** (per-chunk `hasChunk`, dithered discard, eroded 1 chunk) — render distance alone is
  not enough (loaded radius can exceed client render distance → LOD over loaded chunks).

---

## Auto-memory notes (machine-local originals; copied here for transfer)

### neopoculus-lod-voxel-rework (project)
Full-voxel textured LOD rework of the "Horizon" LOD in the NeOPoculus repo (Iris fork, MC 1.21.1
NeoForge), branch `feature/lod-voxel-rework`. Clean-room from Voxy's *behavior* only (Voxy is
all-rights-reserved — never port its code/identifiers). New engine in
`net.irisshaders.iris.horizon.voxel`, behind `engine=classic|voxel` (default classic, opt-in), classic
deprecated one stable release after voxel becomes default. Milestone/status: see this file's top.

### workflow-vs-direct-preference (feedback)
For coupled, sequential implementation work the user prefers direct implementation (normal edit/compile
loop), NOT chains of separate sub-agents (they give no real parallelism on a dependency chain, drift at
API seams, and background workflows get killed by session limits). Reserve multi-agent Workflow fan-out
for (1) broad initial research and (2) adversarial review (independent lenses in parallel — found 2 real
bugs each on M1/M2). Even under ultracode: implement coupled feature code directly, use workflows for
research + review.

### commit-style (feedback)
Frequent commits; NEVER add Co-Authored-By / Claude as a collaborator in commits or PRs.
