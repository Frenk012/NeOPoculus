# NeOPoculus

**Shaders for NeoForge, with a world that keeps going past your render distance.**

NeOPoculus is a fork of [NeOculus](https://www.curseforge.com/minecraft/mc-mods/neoculus),
which forks [Oculus](https://www.curseforge.com/minecraft/mc-mods/oculus), which ports
[Iris](https://www.curseforge.com/minecraft/mc-mods/irisshaders) to Forge-family loaders.
Everything those projects do — loading OptiFine/ShadersMod shader packs, the in-game shader
options screen, Embeddium integration — is theirs, and it all still works here.

What this fork adds is **Horizon**: a built-in extended-distance terrain system, plus a set of
compatibility fixes for mods and platforms that previously crashed or rendered nothing.

**[Discord](https://discord.gg/6WADV66Frv)** · **[Source](https://github.com/Frenk012/NeOPoculus)** · **[Report a bug](https://github.com/Frenk012/NeOPoculus/issues)**

---

## Horizon — terrain beyond the view distance

Horizon records the terrain you explore, saves it per world and dimension, and draws it far
past the server's view distance. It needs no server mod and no extra downloads: everything is
captured from chunks your own client already receives, so it works on vanilla servers,
realms and singleplayer alike. Explored terrain is written to disk, so a world keeps its
panorama between sessions.

Detail falls away with distance in five steps, so distant ground stays cheap. The ring closest
to you is drawn at full block resolution and deliberately overlaps your loaded chunks, which is
what makes the join invisible — and it backfills chunks that are still loading, so the world
stops popping in at the edge of your render distance.

**On by default**, and turned off with a single tick box.

### Two engines

| | **Classic** (default) | **Voxel** (experimental) |
|---|---|---|
| Stores | surface height, water height, one colour per column | real blocks, biome and light per cell |
| Caves, overhangs, interiors | not represented | represented |
| Textures | flat colour per column | real block textures |
| Block shapes | full cubes | slabs, snow, carpets, fences, plants keep their shape |
| Water | opaque surface | genuinely translucent |

The Classic engine is a heightmap and is what you get out of the box: lighter, older, and
proven. The Voxel engine is the newer one and is where the work has gone — switch it on under
**Voxel Engine (Experimental)** if you want caves, textures and real block shapes at distance.
It is marked experimental honestly: it is the newer of the two and has had less time in the
wild.

---

## Shader pack support

Distant terrain is drawn **through your shader pack**, so it gets the pack's own lighting,
fog and shadows instead of looking like a flat cut-out pasted onto the horizon.

There are two ways this happens. A pack that supports Distant Horizons has a `dh_terrain`
program, and Horizon feeds its terrain through it. A pack with no such program is drawn
through its ordinary `gbuffers_terrain` instead — which every pack has, and which is also the
only path that shows real block textures.

Of **16 packs tested, 11 render shaded distant terrain**:

- **Through `dh_terrain`** — Bliss, BSL, Complementary Reimagined, Complementary Unbound,
  Sildur's Vibrant Shaders, Solas
- **Through `gbuffers_terrain`** — ARTShade, Aurora's Shaders, Kappa, Lux, The Better Default

**Five packs do not currently load at all** in this mod: iterationT, Noble, PaintBound, photon
and Reverie. These fail during shader compilation in the Embeddium/Iris layer, with errors
like `undefined variable "iris_FogColor"`. That is a defect in the shader stack and has
nothing to do with Horizon — those packs do not work here whether distant terrain is on or
off. It is a known issue, not a supported configuration.

Packs not on this list are untested rather than unsupported.

---

## Multiplayer and pre-generated terrain

A server operator can generate distant terrain ahead of time instead of waiting for players to
walk it, and stream it to players as they join — so a new player sees the whole landscape
immediately rather than a wall of fog that slowly retreats.

The `/horizon lod` commands generate terrain for a zone, a radius or the whole world, and are
restricted to **permission level 4** (server owner). Terrain arriving from a server is treated
as untrusted: block and biome ids are translated into the client's own numbering rather than
adopted, lengths and ids are range-checked, and a disk budget caps how much a session will
accept. The worst a misbehaving server achieves is odd-looking distant scenery.

> Generating LOD from the command requires the **Voxel** engine on a singleplayer world.

### Disk use

Distant terrain is cached on disk and the cache is capped. When the cap is reached you are
told, rather than left wondering why the horizon stopped filling in. Optional **auto-clean**
frees space by deleting the regions farthest from you first — the ground you actually live on
is kept, the far edge of an old expedition goes first. A purge button clears the cache
entirely, behind a confirmation tick.

---

## Settings

All settings live under **Video Settings → Horizon LOD** (Embeddium's screen, or the vanilla
one) and are editable in game.

| Setting | Default | What it does |
|---|---|---|
| Horizon LOD | on | Master switch. |
| Voxel Engine | off | The newer engine: caves, textures, real block shapes. |
| Render with shaders | on | Draw distant terrain through your shader pack. |
| LOD distance | 256 chunks | How far distant terrain reaches (16–4096). |
| Distant vegetation | 33% | How much distant grass and flowers to draw. **Usually the largest single performance gain.** |
| Per-cell LOD textures | on | Block textures at true size under shaders; costs geometry. |
| Textured LOD under shaders | off | Force the textured path even on packs that support Distant Horizons. |
| LOD memory budget | 256 MB | Memory the Voxel engine may hold terrain in before writing it back to disk. |
| Auto-clean / purge | off | Disk cache management. |

Plus detail-ring, upload-rate, worker-thread and save-interval controls for tuning.

Every option is also written to `config/neoculus-horizon.properties`.

**If distant terrain costs you too much:** lower **Distant vegetation** first — plants are many
small see-through quads, which cost far more per pixel than solid ground. Then reduce **LOD
distance**, then turn off **Per-cell LOD textures**.

---

## Compatibility fixes

These are fixes to things that were broken before, not new features:

- **Sodium API shim** — mods written against Sodium's public API (such as Flerovium) load on
  the Embeddium stack instead of failing with `NoClassDefFoundError`.
- **macOS** — the bundled AcceleratedRendering used to crash the game natively while loading a
  world on hardware with no OpenGL compute support, which includes every Mac. It is now
  detected and switched off transparently, falling back to vanilla rendering.
- **Sinytra Connector** — no longer crashes during *Initializing game* when Connector is
  installed.
- **Stricter OpenGL drivers** — the game no longer refuses to launch on drivers reporting a GL
  3.2 core context while exposing 4.x through extensions.
- **LittleTiles** — tiles, including glass and other translucent ones, render correctly under
  Embeddium, with correct face culling when a structure is edited.
- **Entity flicker** — entities, block entities, your held item and your arm no longer blink
  out while moving.

AcceleratedRendering is also updated from 1.0.0 to 1.0.8.

---

## Requirements

- **Minecraft** 1.21.1
- **NeoForge** 21.1.0 or newer
- **[Embeddium](https://www.curseforge.com/minecraft/mc-mods/embeddium)** — required

Not compatible with OptiFine. Do not install alongside Oculus, Iris or NeOculus — this replaces
them.

---

## Known limitations

- **No performance numbers are claimed.** Horizon draws more of the world, so it costs more
  than not drawing it. Whether that is worth it depends on your hardware, and the settings
  above exist to let you decide.
- Five of the sixteen tested shader packs do not load, for a reason unrelated to distant
  terrain (see above).
- Distant terrain is currently drawn two-sided; single-sided drawing is planned and will make
  it cheaper.
- Underground terrain you cannot see is still drawn. Culling it is planned.
- The Classic engine cannot show caves or overhangs — that is what the Voxel engine is for.

---

## Support and bug reports

- **[Discord](https://discord.gg/6WADV66Frv)** — questions, help, shader pack reports
- **[GitHub issues](https://github.com/Frenk012/NeOPoculus/issues)** — bugs and crashes
- **[Source code](https://github.com/Frenk012/NeOPoculus)**

Reporting a rendering problem is far more useful with the **shader pack name and version**,
your **`latest.log`**, and whether the problem still happens with Horizon LOD turned off —
that last one alone separates a Horizon bug from a shader-stack bug.

---

## Credits and licence

This mod stands on other people's work:

- **[Iris](https://github.com/IrisShaders/Iris)** — the shader pipeline everything here is
  built on, by coderbot, IMS and contributors
- **[Oculus](https://www.curseforge.com/minecraft/mc-mods/oculus)** — the Forge port
- **[NeOculus](https://www.curseforge.com/minecraft/mc-mods/neoculus)** — the NeoForge fork
  this one is based on
- **[Embeddium](https://www.curseforge.com/minecraft/mc-mods/embeddium)** — required
  rendering engine
- **[AcceleratedRendering](https://github.com/Argon4W/AcceleratedRendering)** — bundled
- **[Swaying Garden](https://github.com/joe-vettek/SwayingGarden)** — bundled

Horizon is inspired by [Distant Horizons](https://modrinth.com/mod/distanthorizons) and
[Voxy](https://modrinth.com/mod/voxy), and shares no code with either.

Licensed under **LGPL-3.0**, as inherited from Iris.

---

<!--
BEFORE PUBLISHING
=================

1. THE SIDEBAR LINK BUTTONS DO NOT COME FROM THIS FILE.
   Both sites build those blocks from fields in the project settings, not from the
   description text. The links above are for people reading the page; the buttons have to
   be entered separately, or they simply will not appear.

   Modrinth — Settings > Links:
     Source code   https://github.com/Frenk012/NeOPoculus
     Issue tracker https://github.com/Frenk012/NeOPoculus/issues
     Discord       https://discord.gg/6WADV66Frv          (Settings > Links > Discord invite)

   CurseForge — Project > Settings > General, "Links" section:
     Source        https://github.com/Frenk012/NeOPoculus
     Issues        https://github.com/Frenk012/NeOPoculus/issues
     Discord       https://discord.gg/6WADV66Frv
   CurseForge only shows the Issues button once the field is filled AND the repository has
   issues enabled, so check that on GitHub first.

2. MAKE THE REPOSITORY PUBLIC AND ENABLE ISSUES.
   An issue-tracker link to a private or issues-disabled repository is worse than no link.

3. SCREENSHOTS.
   The strongest pair is the same viewpoint with Horizon off and on. A close-up at ground
   level showing the join with loaded chunks is the second most convincing, because that
   seam is the thing people expect a distant-terrain mod to get wrong.
   Both platforms render Markdown images: ![caption](https://url/to/image.png)
   Host them on the project's own gallery where possible — external image hosts are
   restricted on both sites and a blocked image leaves an empty box.

4. CHECK THE FACTS AT RELEASE.
   Version, Minecraft and NeoForge ranges against gradle.properties; the shader pack lists
   against a fresh `./gradlew runShaderTest` sweep. The pack lists are the part most likely
   to go stale, and the part readers will check.

5. FORMAT.
   Modrinth takes this file as-is in the Description tab.
   CurseForge accepts Markdown but strips HTML comments like this one — paste everything
   above the horizontal rule, not this block.
-->
