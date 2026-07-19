All context absorbed — repo machinery (HorizonLod/LodWorld/LodStorage/LodCapture) verified against source. Here is the design.

# Horizon Voxel Engine — Data Model, Ingestion, Storage (Slice Design)

**Package:** `net.irisshaders.iris.horizon.voxel`
**Coexists with:** classic 2.5D system (untouched, `net.irisshaders.iris.horizon.*`, v3 files) behind `HorizonConfig.engine = classic|voxel`.

---

## 1. Section grid

### 1.1 Cell bit layout (`VoxelCell`, static utility)

64-bit long per cell:

| Bits | Field | Width | Notes |
|---|---|---|---|
| 0–19 | `blockStateId` | 20 | global palette id; **0 = air (reserved, never in palette file)** |
| 20–28 | `biomeId` | 9 | global palette id; **0 = `minecraft:plains` fallback (eagerly registered)** |
| 29–32 | `blockLight` | 4 | nibble |
| 33–36 | `skyLight` | 4 | nibble |
| 37–39 | reserved flags | 3 | must be 0 in format v4 |
| 40–63 | reserved | 24 | must be 0 in format v4 |

```java
final class VoxelCell {
    static final long STATE_MASK = 0xFFFFFL;
    static final int  BIOME_SHIFT = 20, LIGHT_BLOCK_SHIFT = 29, LIGHT_SKY_SHIFT = 33;
    static long pack(int stateId, int biomeId, int blockLight, int skyLight);
    static int  stateId(long cell);   // (int)(cell & STATE_MASK)
    static int  biomeId(long cell);   // (int)(cell >>> 20) & 0x1FF
    static int  blockLight(long cell); static int skyLight(long cell);
    static boolean isAir(long cell);  // (cell & STATE_MASK) == 0
}
```

### 1.2 Section key (`SectionKey`, static utility)

One long key packs `(level, x, y, z)` where x/y/z are section-grid coords at that level (section spans `32 << level` blocks per axis; `secCoord = floorDiv(blockCoord, 32 << level)`):

| Bits | Field | Width | Encoding |
|---|---|---|---|
| 0–25 | x | 26 | biased: `(x + (1<<25)) & 0x3FFFFFF` → range ±33.5M sections (beyond world border at every level) |
| 26–51 | z | 26 | same bias |
| 52–59 | y | 8 | biased +128 → section-y range [-128,127]; at L0 covers world Y [-4096,4064) — beyond any datapack dimension (vanilla floor is minY ≥ -2032) |
| 60–63 | level | 4 | 0–4 |

Overworld (−64..320) y ranges per level (parent = `child >> 1`, floor division — consistent octree):
L0: y ∈ [−2, 9] (12 sections) · L1: [−1, 4] · L2: [−1, 2] · L3: [−1, 1] · L4: [−1, 0].

```java
final class SectionKey {
    static long pack(int level, int x, int y, int z);
    static int level(long key); static int x(long key); static int y(long key); static int z(long key);
    static long parent(long key);            // level+1, coords >> 1 (arithmetic)
    static int  childOctant(long key);       // (x&1) | (z&1)<<1 | (y&1)<<2
}
```

Cell index inside a section: `idx = (y << 10) | (z << 5) | x` (x fastest — matches vanilla `PalettedContainer` convention, so bulk copies are stride-friendly).

### 1.3 `VoxelSection`

```java
public final class VoxelSection {
    public final long key;
    long[] cells;                 // 32768 longs = 256 KB; never null while HOT
    int nonAirCount;              // maintained by every write; ==0 → drop section
    long[] populationMask;        // which chunk columns have been ingested; size max(1, (1<<(2*level+2))/64)
                                  // L0/L1/L2: 1 long (4/16/64 bits used), L3: 4, L4: 16
    volatile int dataVersion;     // ++ per write batch; mesher staleness check
    boolean dirty;                // unsaved; guarded by section monitor
    volatile long lastTouchedNanos;

    // all under synchronized(this):
    public void writeCell(int idx, long cell);
    public void writeBatch(int baseX,int baseY,int baseZ,int nx,int ny,int nz, long[] src,int srcStride...);
    public void copyCellsInto(long[] dst);        // snapshot for mesher/codec (≈20 µs)
    public long cellAt(int x,int y,int z);
}
```

**Pooling decision: pooled, bounded free-list (`SectionPool`).** Rationale:
- A 256 KB `long[]` is *not* G1-humongous (< 1 MB at typical 2 MB regions), but sections are **mid-lifetime objects** — they live seconds-to-minutes (hot cache, dirty backlog), guaranteeing promotion to old gen. At elytra speed (~30 new L0 sections/s) plain alloc creates ~7.5 MB/s of *old-gen* churn: G1 mixed-collection pressure and heap-growth spikes, i.e. frame hitches — the exact class of stutter this mod's recent commits fight.
- Reuse costs nothing: `Arrays.fill(a, 0L)` (~8 µs vectorized) equals the JVM's own zeroing of a fresh array; reuse is never slower and caps steady-state allocation at zero.
- Bounded so it can't leak: `SectionPool.MAX_POOLED = 64` arrays (16 MB retained max). Pool empty → plain `new long[32768]`; pool full → drop to GC.

```java
final class SectionPool {
    static long[] acquire();      // zeroed
    static void release(long[] a);
    static final int MAX_POOLED = 64;
}
```

### 1.4 Residency map

**One** `ConcurrentHashMap<Long, VoxelSection>` (key = packed `SectionKey`) inside `VoxelWorld` — not five per-level maps. The level lives in the key, so lookup is uniform; the only per-level operations (eviction sweep, save grouping) run once per save cycle over `values()` and switch on `SectionKey.level(key)`, which is cheaper than maintaining five maps coherently. Air-only sections are **never stored** (created on first non-air write, removed when `nonAirCount` hits 0 at save time).

---

## 2. Global palettes (`VoxelPalettes`)

Two independent incremental palettes, shared by **all dimensions of one world**, persisted at `horizon-lod/<worldId>/palette.nbt`.

```java
public final class VoxelPalettes {
    public int idFor(BlockState state);          // any thread; registers if new
    public int idFor(Holder<Biome> biome);       // any thread
    public BlockState stateOf(int id);           // lock-free array read; stone fallback for tombstones
    public ResourceLocation biomeOf(int id);
    public int   opacityOf(int stateId);         // cached byte, see §4
    public boolean isLeaf(int stateId);          // cached flag (BlockTags.LEAVES)
    public int paletteVersion();                 // ++ on every registration; bakery/mesher invalidation
    public int stateCount(); public int biomeCount();
    public void load(Path file, HolderGetter<Block> blocks, Registry<Biome> biomes); // worker, once at world open
    public boolean saveIfDirty(Path file);       // worker; atomic tmp+move; MUST run before region flush
}
```

- **Registration:** `ConcurrentHashMap<BlockState,Integer> toId` (BlockState instances are canonical and stable for a session — blocks freeze at startup; asset reload does not recreate them) + `AtomicInteger next`; reverse side is a grow-by-doubling `BlockState[] byId` republished `volatile` under a small lock — readers never lock. Same shape for biomes keyed by `Holder<Biome>` with a parallel `ResourceLocation[]`.
- **Reserved ids:** state 0 = air (implicit, no entry). Biome 0 = `minecraft:plains`, state 1 = `minecraft:stone` — both eagerly registered at world open so fallbacks always resolve.
- **Persistence (NBT):** root `{version:1, states:[{id:int, state:<NbtUtils.writeBlockState>}...], biomes:[{id:int, name:"modid:biome"}...]}` written with `NbtIo.writeCompressed(tag, path)`, read with `NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())`. State resolution on load: `NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), tag)`; biome by `Registry<Biome>.get(ResourceLocation)`.
- **Id stability:** append-only, ids never reassigned or GC'd. An entry that fails to resolve this session (mod removed, server biome set changed) becomes a **tombstone**: renders as stone/plains, but its original NBT blob is retained and re-persisted verbatim, so the id round-trips and stays reserved — re-adding the mod restores correct rendering of old cache data with zero migration.
- **Overflow:** `MAX_STATE_IDS = 1<<20`, `MAX_BIOME_IDS = 1<<9`. Beyond cap, `idFor` inserts the state into an `overflowAlias` map → returns id 1 (stone) / 0 (plains), logs once (with running overflow count). Incremental registration means only states *actually seen in the world* get ids — even huge packs (FramedBlocks etc.) encounter thousands, not millions; the cap is a safety valve, not an expected path.
- **Resource reload:** palette ids are registry-derived, not resource-derived — F3+T / pack switches change **nothing** here. The photo-atlas bakery (other slice) subscribes via `paletteVersion()` and re-bakes textures keyed by state id; palette survives untouched. Server datapack biome sets are per-world by construction (palette file lives under `<worldId>`), so no cross-server pollution.
- **Save-ordering invariant:** palette.nbt is flushed **before** any region file in every save cycle → after any crash, every id cited on disk is defined in the palette. Extra unused ids after a crash are harmless.

---

## 3. Ingestion

### 3.1 Thread ownership per stage

| Stage | Thread | Budget |
|---|---|---|
| ChunkEvent.Load/Unload handlers → enqueue chunk pos | client (event bus) | trivial |
| **Snapshot** (`ChunkSnapshotter.snapshot`) | client tick (`onClientTick` drain) | `MAX_SNAPSHOTS_PER_TICK = 4` (~0.5 ms) |
| Block-update hook → enqueue `(pos, newState)` | client (mixin) | drain-all, cheap |
| Palette registration | any (concurrent) | — |
| Convert + intra-chunk pyramid + merge | worker pool | target 1–3 ms/chunk |
| Incremental remip (§4) | worker | `MAX_REMIP_SECTIONS_PER_PASS = 64` |
| Pack/unpack warm entries, region IO, eviction sweep | worker | save cycle |
| GL anything | none in this slice | — |

Key change vs classic engine: capture leaves the client thread. Only the **copy** stays on-thread (chunk data is not safely readable off-thread — `PalettedContainer` swaps its `data` record on palette resize with no happens-before for foreign readers).

### 3.2 Snapshot (client thread, exact 1.21.1 NeoForge API)

```java
record ChunkSnapshot(int chunkX, int chunkZ, int minSectionY, int sectionCount,
                     PalettedContainer<BlockState>[] states,        // null where hasOnlyAir()
                     PalettedContainer<Holder<Biome>>[] biomes,     // null where states null
                     DataLayer[] blockLight, DataLayer[] skyLight,  // copies; null allowed
                     int highestFilledSectionIndex) {}
```

Per `LevelChunk chunk`:
- `LevelChunkSection[] secs = chunk.getSections();` `chunk.getMinSection()`, `chunk.getSectionsCount()`, `chunk.getSectionYFromSectionIndex(i)`.
- Blocks: `secs[i].hasOnlyAir() ? null : secs[i].getStates().copy()` — `PalettedContainer.copy()` duplicates the packed `BitStorage` + palette (~4 KB/section, ~100 KB/chunk, ≈50 µs).
- Biomes: `secs[i].getBiomes().copy()` — `copy()` is declared on `PalettedContainerRO`, no cast needed.
- Light: `LevelLightEngine le = chunk.getLevel().getLightEngine();` per section y: `DataLayer d = le.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(cx, sy, cz)); blockLight[i] = d == null ? null : d.copy();` same for `LightLayer.SKY`.
- Null-layer semantics at convert time: block null → 0; sky null → 15 if section index > `highestFilledSectionIndex`, else 0.

Backpressure: `MAX_PENDING_SNAPSHOTS = 64` (~13 MB). Above that, the chunk **position** is parked in a retry set and re-attempted next tick(s) while the chunk is still loaded (`SNAPSHOT_RETRY_TICKS = 20`); unload snapshots jump the queue (last chance at the data). The classic `preserveColors` hack dies: biome/tint data comes from the chunk's own containers, so unload snapshots are never degraded.

### 3.3 Convert + intra-chunk mip pyramid (worker)

Per-worker `ThreadLocal` scratch `ChunkPyramid`: five arrays per vanilla section — `P0[4096], P1[512], P2[64], P3[8], P4[1]` (indices `(y<<8|z<<4|x)`, `(y<<6|z<<3|x)`, `(y<<4|z<<2|x)`, `(y<<2|z<<1|x)`, `[0]`) ≈ 37 KB.

1. **Fill P0:** `states.getAll(consumer)` iterates all 4096 entries in index order (x fastest) — the public-API bulk path; a one-entry memo (`lastState → lastId`) makes it near-palette-speed since runs dominate. Biomes: `biomes.getAll(...)` yields 64 values (4³ grid, one per 4×4×4 blocks — replicate over the covered cells). Light: `DataLayer.get(x, y, z)` per cell (or null-heuristic). Compose with `VoxelCell.pack`. (Optional later fast path: a `PalettedContainerAccessor` mixin exposing raw `BitStorage` + palette to pre-translate the local palette once — spec'd as optimization, not baseline.)
2. **Mip P0→P4:** each `P(l+1)` cell = `VoxelMipper.selectRepresentative` over its 2×2×2 `P(l)` children (§4 rule). Because chunk origin (multiple of 16) is a multiple of every cell size ≤ 16, **every mip cell up to L4 lies wholly inside one vanilla section** — pyramid values are exact, no cross-chunk merging ever needed at ingest.

### 3.4 Merge into sections — coordinate math (worker)

For vanilla section `(cx, sy, cz)` and level `l ∈ [0,4]`:
- Target section key: `SectionKey.pack(l, cx >> (l+1), sy >> (l+1), cz >> (l+1))` — for l=0 that is `cx>>1`: an L0 section = 2×2×2 vanilla sections, as locked.
- Base cell offset inside target (Java `&` = floorMod for powers of two, negatives safe): `bx = ((cx & ((2<<l)-1)) << 4) >> l`, same formula with `sy`/`cz` for `by`/`bz`. (l=0: `(cx&1)*16` → 0 or 16; l=4: `cx&31`.)
- Cells written per axis: `n = 16 >> l` (16, 8, 4, 2, 1).
- `VoxelStore.acquireForWrite(key)` (get-or-create; loads from warm/disk if the section already exists) then one `writeBatch` under the section monitor copying from `P(l)`; sets the chunk-column bit(s) in `populationMask`; bumps `dataVersion`, sets `dirty`, adds `(level,region)` to the dirty-save set, and notifies the mesher's dirty tracker (other slice) via callback.
- Ingest writes **all 5 levels unconditionally** — capture only happens within server view distance, so the extra L1–L4 sections touched are a handful; this keeps parents exact without any deferred propagation for the bulk path.

### 3.5 Block updates

Mixin `MixinClientLevel_Horizon` injecting at `ClientLevel.setServerVerifiedBlockState(BlockPos, BlockState, int)` — the single funnel for both `ClientboundBlockUpdatePacket` and `ClientboundSectionBlocksUpdatePacket`. Handler enqueues `(packedPos, state)` into a bounded MPSC queue (`MAX_BLOCK_UPDATES_PER_TICK = 256` drained per tick on a worker kick). Worker: register state id, reuse the cell's existing biome/light bits (light refresh arrives later via the debounced light path: the touched vanilla section id goes into a set; after `MIP_DEBOUNCE_TICKS = 5` a client-tick mini-task re-copies just its two `DataLayer`s and a worker rewrites light bits), write the L0 cell, mark dirty, enqueue **incremental remip** (§4). A burst > 64 updates in one vanilla section flips to full-section re-snapshot instead.

---

## 4. Mip propagation (incremental path)

Needed only for sparse updates (bulk ingest writes all levels directly).

- **Comparator (`VoxelMipper.selectRepresentative(long[] eight)`):** identity from non-air children only, ranked by `(opacity DESC, occurrence-count-among-8 DESC, lowest-child-index)` — deterministic, mode-biased so a 7-stone/1-torch group stays stone. All-air children → air cell (state 0) but with averaged light retained.
- **Opacity source:** computed once at palette registration into `byte opacity[stateId]`: `try { state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) } catch (Throwable) { state.canOcclude() ? 15 : 1 }`; air → 0; **leaves forced to 15** (`BlockTags.LEAVES`) with a parallel `isLeaf` flag bit so the bakery applies the darkened-mip branch — exactly the locked most-opaque/leaf rule. Cached in `VoxelPalettes` (arrays, lock-free reads).
- **Light:** blockLight = floor(mean of 8), skyLight = **ceil**(mean of 8) (ceil keeps distant terrain from edge-darkening), averaged over all 8 children including air.
- **Mechanics:** a parent cell's 8 children are always 2×2×2 adjacent cells **within one child-level section** (child coords `2i, 2i+1` ∈ [0,31]), so a remip reads one section, writes one cell of `SectionKey.parent`. Dirty cells batch per section into `remipQueue` (a `ConcurrentHashMap<Long, LongOpenHashSet cellIdx>`), debounced `MIP_DEBOUNCE_TICKS = 5`, processed levels ascending (L0-dirt → L1 writes enqueue L2 …), `MAX_REMIP_SECTIONS_PER_PASS = 64` per worker pass. Population masks propagate: parent's column bit set iff the corresponding child column bit was set.

---

## 5. Residency + eviction

### 5.1 Mesh-level ring table (drives which level a mesh job reads; `lodRingWidth` default 1024 m)

| Distance from camera | Level | Cell size | Notes |
|---|---|---|---|
| collar (inside renderDistance + 256 m) | 0 | 1 m | forced, seam-exact (carries over existing collar rule) |
| 0 – 1024 m | 0 | 1 m | |
| 1024 – 2048 m | 1 | 2 m | |
| 2048 – 4096 m | 2 | 4 m | default `lodDistanceChunks=256` ends here |
| 4096 – 8192 m | 3 | 8 m | |
| 8192 – 65536 m (max config) | 4 | 16 m | L4 caps out; mesh-side decimation beyond is the render slice's concern |

Hysteresis: a region keeps its current level while within ±25% of the boundary (same intent as today's scale hysteresis).

### 5.2 CPU residency is a bounded two-tier cache, not a ring-resident set

Ring-residency of raw L0 sections is unshippable (radius 1024 m alone = π·34²·12 ≈ 43k sections × 256 KB ≈ 11 GB). Instead (`VoxelStore`):

- **HOT tier:** raw `long[]` sections, LRU by `lastTouchedNanos`, cap `HOT_CACHE_SECTIONS = 256` (= 64 MB). Populated by ingest writes and mesh-job reads (a mesh job touches its section + 6 face neighbors ≈ 1.75 MB; 4 workers ≈ 7 MB working set).
- **WARM tier:** sections packed with the §6 codec (avg ~2–16 KB), byte-budgeted: `voxelMemoryBudgetMb` config, default 256, clamp [64, 2048]. Hot-evicted sections are packed down (dirty ones **stay dirty in warm** — the save path writes warm bytes directly, no re-inflate). Warm covers the entire 4096 m default distance at matched levels with room to spare, so re-mesh after scale flips or edits never touches disk in steady state.
- **DISK:** §6. Warm-evicted dirty sections are saved first (save-then-drop, mirroring today's dirty-region-skip eviction invariant: an unsaved section can never be lost).

**Eviction policy per level:** HOT = global LRU (level-agnostic). WARM two-phase, run in the save cycle: phase 1 evicts sections outside their level's ring × 1.5 (score = `chebyshevBlocks / (32 << level)` — normalizes so far L0 goes first, L4 essentially never); phase 2 plain LRU until under budget; L3/L4 exempt from phase 2 until budget is 110% exceeded (they're few, huge-coverage, most expensive to refault visually).

### 5.3 Worst-case RAM at defaults (show the math)

| Item | Math | Bytes |
|---|---|---|
| HOT | 256 × 256 KB | 64 MB |
| Pool retained (idle) | 64 × 256 KB | 16 MB |
| WARM | config cap | 256 MB |
| Snapshots in flight | 64 × ~200 KB | 13 MB |
| Worker scratch | 4 × (786 KB column + 37 KB pyramid + 64 KB codec) | 3.5 MB |
| Palettes + opacity/flag caches (10k states) | NBT blobs + maps + arrays | ~8 MB |
| **Total (worst)** | | **≈ 360 MB** (typical play ≈ 100–150 MB; RAM stays capped even at 4096-chunk distance — only disk grows) |

Disk estimate: 8192×8192 m explored ≈ 786k L0 sections, ~40% non-trivial × ~8 KB packed + uniform/air rows ≈ 2.4 GB pre-gzip → ≈ 1 GB on disk; L1–L4 add ~15% (÷8 geometric). Comparable to a DH database; note in config docs.

---

## 6. Storage format — `.hlod` v4

**Paths:** `horizon-lod/<worldId>/palette.nbt` + `horizon-lod/<worldId>/<dim>/voxel/L<level>/r.<rx>.<rz>.hlod` where `rx = secX >> 3`, `rz = secZ >> 3` — **region = 8×8 sections in x/z, all y, one level** (`REGION_SECTION_BITS = 3`). L0 region spans 256×256 m; every level has the same row count ceiling (≤ 8·8·12 overworld). The classic engine's v3 files live at `<dim>/r.*.hlod` — disjoint subtree, both engines coexist untouched, per the locked toggle.

**File layout** (whole-file gzip, `DataOutputStream`, atomic tmp+`Files.move(REPLACE_EXISTING)` — the existing `LodStorage` machinery pattern verbatim):

```
gzip(
  int   MAGIC   = 0x484C4F44 ("HLOD")
  int   VERSION = 4
  byte  level
  int   regionX, regionZ
  int   sectionCount
  sectionCount × row:
    long  sectionKey                      // self-describing
    int   nonAirCount
    byte  maskLen                         // longs; then maskLen × long populationMask
    byte  encoding                        // 1|2|3|4, below
    int   payloadLength
    byte[payloadLength] payload
)
```

**Row encodings (`VoxelSectionCodec`), cells always carry global palette ids:**
- `3 UNIFORM` — payload = 1 long: entire section is one cell value (all-stone underground: 8-byte row).
- `1 PALETTIZED_BITPACK` — varint N, then N longs (local palette of distinct cell values), then `32768 × b` bits of indices, `b = max(1, ceil(log2 N))`, packed little-endian into longs, cell order `(y<<10|z<<5|x)`.
- `4 PALETTIZED_RLE` — same local palette, then varint runCount × (varint localIdx, varint runLen) in the same cell order.
- `2 RAW` — 32768 longs, only when local palette > 4096 (pathological).

Encoder does one pass computing distinct-count and run-count, then picks: uniform if N==1; else RLE if `runBytes < bitpackBytes`; else bitpack; RAW as the >4096 escape. Outer gzip mops up residual redundancy. Typical surface L0 section: N≈20–60, b=5–6 → 20–25 KB bitpacked, often beaten by RLE; gzipped on disk ~30–50% of that.

**Dirty-save cycle** (worker, every `saveIntervalSeconds`, plus an early pass when dirty sections > `UNSAVED_FLUSH_THRESHOLD = 512`, plus the level-unload double-retry flush exactly as `HorizonLod.onLevelUnload` does today):
1. `palettes.saveIfDirty()` — **always first** (§2 invariant).
2. Group dirty section keys by `(level, rx, rz)`; per region (per-region lock, mirroring `LodStorage`'s synchronized save/load): read existing file into a row map (absent/corrupt → empty), overwrite rows from warm bytes / freshly packed hot sections, drop rows with `nonAirCount == 0`, write tmp, atomic move, clear dirty. Failure → keys stay dirty, retried next cycle (today's semantics).
3. Run the §5.2 eviction sweep.

**Load-on-demand:** `VoxelStore.acquire(key)` — HOT hit → return; WARM hit → inflate via pool, promote; miss → per-region `loadedRegions` guard (as today) reads the whole file and installs **packed rows straight into WARM** (no inflate), then inflates the requested key. Ingest/mesh workers block on their own load (they are the IO thread); the mesher's async path gets `tryAcquire` + load-enqueued.

**Corruption handling:** gzip's CRC makes truncation/corruption throw on read → log warn, rename file to `*.corrupt` (quarantine so it doesn't re-fail every session), proceed as absent — terrain re-captures on approach. Unknown VERSION → rename `*.old`, proceed as absent (locked: no migration, caches regenerate). Row-level: a row whose `payloadLength` overruns the stream keeps prior rows, drops the rest; a decoded cell with `stateId ≥ paletteSize` remaps to stone id 1, logged once per region.

---

## 7. Class list, API sketch, constants

All in `net.irisshaders.iris.horizon.voxel`:

| Class | Responsibility |
|---|---|
| `VoxelCell` | cell bit pack/unpack (§1.1) |
| `SectionKey` | key bit pack/unpack, parent/octant math (§1.2) |
| `VoxelSection` | 32³ cell grid, per-section monitor, population mask, dirty/version (§1.3) |
| `SectionPool` | bounded `long[32768]` free-list (§1.3) |
| `VoxelWorld` | the section `ConcurrentHashMap`, per-dimension; dirty-section set; remip queue |
| `VoxelStore` | two-tier HOT/WARM cache + disk faulting; acquire/release state machine (§5.2, §6) |
| `VoxelPalettes` | global state/biome palettes + opacity/leaf caches + NBT persistence (§2) |
| `ChunkSnapshotter` | client-thread copies of containers/light; retry/backpressure (§3.2) |
| `VoxelIngest` | worker convert → `ChunkPyramid` → merge; block-update writes (§3.3–3.5) |
| `ChunkPyramid` | thread-local 16³→1 scratch (§3.3) |
| `VoxelMipper` | representative selection, light averaging, incremental remip (§4) |
| `VoxelSectionCodec` | row encode/decode, encoding choice (§6) |
| `VoxelRegionStorage` | region files, RMW save, quarantine, per-region locking (§6) |
| `VoxelEngine` | glue: owns queues/budgets, plugs into `HorizonLod` events/tick/save cycle behind the `engine` toggle; level-lifecycle reset |
| `MixinClientLevel_Horizon` | `setServerVerifiedBlockState` hook (§3.5) |

**Core API sketch:**

```java
public final class VoxelEngine {                       // one per client, mirrors HorizonLod lifecycle
    public void onChunkLoad(LevelChunk c); public void onChunkUnload(LevelChunk c);
    public void onClientTick(Minecraft mc);            // snapshot budget, light-refresh mini-tasks, save cadence
    public void onLevelUnload();                       // flush palette + dirty regions (double-retry), reset
    public void onBlockChanged(BlockPos pos, BlockState newState);
    public VoxelStore store(); public VoxelPalettes palettes();
}
public final class VoxelStore {
    public VoxelSection acquireForWrite(long key);     // get-or-create, faults warm/disk; worker only
    public VoxelSection tryAcquireRead(long key);      // null → load enqueued; mesher path
    public void release(VoxelSection s);               // LRU touch
    public void markDirty(long key);
    public void saveCycle(int camSecX, int camSecZ);   // worker: palette → regions → eviction sweep
}
final class VoxelMipper {
    static long selectRepresentative(long[] children8, VoxelPalettes p);
    static void enqueueRemip(long sectionKey, int cellIdx);
    static void processRemips(VoxelStore store, VoxelPalettes p, int maxSections);
}
final class VoxelSectionCodec {
    static byte[] encode(long[] cells, int nonAirCount);              // picks encoding, returns framed payload
    static int decode(byte[] payload, byte encoding, long[] dstCells); // returns nonAirCount
}
```

**Constants table:**

| Constant | Value | Home |
|---|---|---|
| `SECTION_SIZE` / `SECTION_CELLS` | 32 / 32768 | `VoxelSection` |
| `MAX_LEVEL` | 4 | `SectionKey` |
| `STATE_BITS` / `BIOME_BITS` / `MAX_STATE_IDS` / `MAX_BIOME_IDS` | 20 / 9 / 1<<20 / 512 | `VoxelCell`, `VoxelPalettes` |
| `FALLBACK_STATE_ID` / `FALLBACK_BIOME_ID` | 1 (stone) / 0 (plains) | `VoxelPalettes` |
| `MAX_SNAPSHOTS_PER_TICK` | 4 | `VoxelEngine` |
| `MAX_PENDING_SNAPSHOTS` / `SNAPSHOT_RETRY_TICKS` | 64 / 20 | `ChunkSnapshotter` |
| `MAX_BLOCK_UPDATES_PER_TICK` | 256 | `VoxelEngine` |
| `MIP_DEBOUNCE_TICKS` / `MAX_REMIP_SECTIONS_PER_PASS` | 5 / 64 | `VoxelMipper` |
| `HOT_CACHE_SECTIONS` | 256 (64 MB) | `VoxelStore` |
| `voxelMemoryBudgetMb` (config) | default 256, clamp [64, 2048] | `HorizonConfig` |
| `SectionPool.MAX_POOLED` | 64 | `SectionPool` |
| `REGION_SECTION_BITS` | 3 (8×8 sections/file) | `VoxelRegionStorage` |
| `MAGIC` / `VERSION` | 0x484C4F44 / 4 | `VoxelRegionStorage` |
| `UNSAVED_FLUSH_THRESHOLD` | 512 sections | `VoxelStore` |
| `RAW_ESCAPE_PALETTE_SIZE` | 4096 | `VoxelSectionCodec` |

**Edge cases covered:** stray cross-dimension events (reuse `worldDimension` guard); per-dimension `VoxelWorld` + world-shared palettes; capture frontier holes (population masks let the mesher skip faces bordering never-ingested columns instead of drawing false cliffs); datapack dimensions with exotic minY (absolute signed section-y in the key, no per-dimension bias); light `DataLayer == null` heuristics; unresolvable palette entries (tombstones, verbatim re-persist); palette overflow (alias to stone/plains, log once); crash between palette and region flush (palette-first invariant); corrupt/truncated files (quarantine + partial-row salvage); pool bounded against leak; dirty sections unevictable until saved (invariant carried over from `LodWorld.evictOutside`).

**Clean-room note:** all identifiers, bit layouts (26/26/8/4 key vs Voxy's 24/24/8/4; cell field order; codec) and the two-tier cache design are original to this document; only published technique descriptions (32³ sections, 5 mips, most-opaque-wins, palette persistence as NBT) are reused, as locked.

Key repo files this design plugs into: `C:\Users\franc\IdeaProjects\NeOPoculus\src\main\java\net\irisshaders\iris\horizon\HorizonLod.java` (event/tick/save orchestration reused via `VoxelEngine` delegation), `LodStorage.java` (v3 machinery pattern copied into `VoxelRegionStorage`, v3 untouched), `LodWorld.java` (dirty/evict invariants carried over), `LodCapture.java` (replaced by `ChunkSnapshotter`+`VoxelIngest` on the voxel path), `HorizonConfig.java` (gains `engine` + `voxelMemoryBudgetMb`).