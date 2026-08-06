# M5b Phase B — dedicated server LOD, minimum viable path

> Phase A (integrated server, `/horizon lod generate …`) is DONE and verified.
> This file is the working plan for the dedicated-server half. It supersedes the
> parts of DESIGN.md §10.2 marked below.

## Honest scope

The earlier 3–4 week figure was for the FULL §10 spec: manifest hashing,
incremental re-sync, auto mode, GUI options, fuzz hardening, real-server test
loops. The **minimum viable path is three pieces**, roughly 1000–1200 lines:

| Piece | What | Size |
|---|---|---|
| B1 | Server-side registration + per-dimension server store | ~250 lines |
| B2 | Payload channel: HELLO / SUBSCRIBE / PALETTE | ~250 lines |
| B3 | REGION_DATA streaming + client install with id remap | ~450 lines |
| B4 | Minimum validation of untrusted cells (NOT optional) | ~100 lines |

Deferred to a v2, deliberately: manifest/CRC incremental sync, `generate auto`,
REGION_INVALIDATE on block edits, GUI options, resumable transfers, per-server
disk budget, cross-dimension prefetch, palette delta packets.

Where the time actually goes is the **test loop on a real NeoForge server jar**,
not the code. `gradlew runServer` uses a merged client+server jar and cannot
reproduce client-class absence, so it will not catch the failure mode that
matters.

## The two contracts (get these wrong and everything else is wasted)

### Palette — REPLACES DESIGN.md §10.2's "sent first and merged"

That wording is unsafe and must not be implemented: `VoxelPalettes.load()` is a
destructive wholesale replace, and ids are assigned in first-encounter order per
process, so server id 42 and client id 42 almost never name the same state.
Adopting server ids on a client that already holds local captures silently
reinterprets every stored cell — wrong blocks, no error. We hit exactly this bug
locally when two worlds shared a cache directory.

**Contract: REMAP AT INSTALL. The client never adopts server ids.**

1. Server sends its palette in the existing `palette.nbt` v1 layout (id + state
   NBT, id + biome name), stamped with its `VoxelPalettes.version`.
2. Client parses it with the same `NbtUtils.readBlockState` path `replayStates`
   uses, and builds `int[] stateRemap` / `int[] biomeRemap` where
   `remap[serverId] = clientPalettes.idFor(resolvedState)` — registering genuinely
   new states into ITS OWN palette.
3. Unresolvable state (mod absent client-side) → `FALLBACK_STATE_ID` (stone);
   unresolvable biome → plains; overflow → fallback. Same tombstone semantics the
   persistence path already has.
4. Every received row is decoded, each cell's state/biome fields rewritten through
   the tables (light bits pass through untouched), re-encoded, then installed.
   Worker thread, ~32k cells per section, cheap.
5. A row citing an id past the remap table means the server registered states
   after the last palette send: queue the row, request a palette refresh keyed by
   the version stamp.

Consequence: server-provided and client-captured regions coexist in ONE
client-local id space, and the returning-player corruption case is structurally
impossible.

### Light

Server light is the authoritative source the client mirrors, and the API is
identical on both sides, so a server-side capture yields the same nibbles. The
real risk is **settling**: `ThreadedLevelLightEngine` is async. Reuse the same
`isLightReady` gate the exploration path uses — a chunk whose light has not
settled is retried, never captured. This is not theoretical: baking dark chunks
in permanently cost a full debugging session on the client path.

## Steps

**B1 — server bootstrap + store.**
`Iris` registers `HorizonLodServer` from the mod constructor (common), not from
the client-only `MixinOptions_Entrypoint`, which is why the commands do not exist
on a dedicated server today. New `ServerLodStore`: per-dimension `VoxelStore`
rooted at `<world>/horizon-lod/<dimension>/voxel` plus one server `VoxelPalettes`
at `<world>/horizon-lod/palette.nbt`, its own small worker pool, periodic save,
`flushAll` on `ServerStoppingEvent`. `LodGenerator` targets it instead of the
client engine when running headless.
*Testable:* real server jar loads clean; `/horizon lod generate radius 1024`
produces `.hlod` + `palette.nbt` under the world folder.

**B2 — channel + handshake.**
Versioned OPTIONAL payload registrar `neopoculus:horizon_lod`, so vanilla clients
and vanilla servers are unaffected. On join/dimension change: server sends HELLO
(protocol version, dimension, palette version, region count). Client replies
SUBSCRIBE if the voxel engine is on. Server sends PALETTE.
*Testable:* handshake logged on both sides; a vanilla client still connects.

**B3 — region streaming + install.**
Server keeps a nearest-first queue per subscribed player, re-prioritised on
significant movement, drained per tick under a byte budget, sending raw region
bytes in bounded chunks. Client reassembles, validates, remaps ids, installs into
its own store, marks the covering mesh regions dirty and clears their "empty"
flag — the existing scheduler and renderer do the rest, so no GL code changes.
*Testable:* fresh client joins a pre-generated server and sees the panorama
without exploring.

**B4 — validation.** Server data is untrusted input: bounds-check row counts,
section keys, palette size, cell ids; clear reserved cell bits 37–63 on install;
cap accepted regions per session. A malicious server must at worst waste disk and
draw wrong terrain, never crash or execute anything.

## Gotchas already known

- `emptyVoxelRegions` must be cleared for installed regions, or the data is
  stored and never meshed (this exact bug cost us a debugging round in Phase A).
- `computeTranslucent` had a client-only path that misclassified on a server;
  fluid translucency is now decided from tags. Any new per-state derivation must
  be checked the same way, since it can reach persisted mip cells.
- Air variants collapse to id 0 and pure fluid states collapse to their source
  state. The server runs the same `idFor`, so this holds — but it is a semantic
  convention, and it must stay documented.
