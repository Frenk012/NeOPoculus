package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.HorizonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side orchestrator of the voxel LOD engine — the glue HorizonLod
 * delegates to when {@code HorizonConfig.isVoxelEngine()} is on. Owns the
 * capture and block-update queues and their per-tick budgets, hands all
 * heavy work (convert, pyramid, merge, remip, eviction) to the worker pool
 * it shares with the classic engine, and carries the level lifecycle
 * (ensure/unload/config-change). No GL anywhere: M1 renders nothing, and
 * even in later milestones this class stays render-free by design.
 *
 * <p>Thread model (DESIGN.md section 4): every public {@code on*} method is
 * called on the client thread (NeoForge event bus / the block-update mixin);
 * the client thread only copies and enqueues. Workers get immutable
 * snapshots plus the {@code VoxelWorld}/{@code VoxelPalettes} references
 * captured at submit time, so a level switch mid-job writes into an orphaned
 * world that GC reclaims instead of polluting the new one.
 */
public final class VoxelEngine {
	// Per-tick budgets. Kept private here rather than in VoxelConstants,
	// following the wave-1 precedent of owner-local tuning values
	// (SectionPool.MAX_POOLED, VoxelPalettes.FALLBACK_*): nothing outside
	// this class may depend on them.

	/** Design section 3.1: snapshot copies budgeted at ~0.5 ms of client tick. */
	private static final int MAX_SNAPSHOTS_PER_TICK = 4;
	/** Design section 3.5: block updates drained per tick into one worker batch. */
	private static final int MAX_BLOCK_UPDATES_PER_TICK = 256;
	/** Sections whose LOD light is rewritten per tick (one 4096-cell pass each, on a worker). */
	private static final int MAX_LIGHT_REFRESHES_PER_TICK = 16;
	/** How long after a block edit the light engine is assumed settled (0.5 s). */
	private static final int LIGHT_REFRESH_DELAY_TICKS = 10;
	/**
	 * Backpressure ceiling on snapshots alive between capture and ingest
	 * (~13 MB worst case). While at the ceiling only unload captures run —
	 * they are the last chance at their chunk's data; load captures just
	 * wait in the queue, their chunk stays loaded.
	 */
	private static final int MAX_PENDING_SNAPSHOTS = 64;
	/** Design section 4: remip pass cap per worker kick (MAX_REMIP_SECTIONS_PER_PASS). */
	private static final int MAX_REMIP_SECTIONS_PER_PASS = 64;
	/** Design section 4: cadence of remip kicks while armed (MIP_DEBOUNCE_TICKS). */
	private static final int MIP_DEBOUNCE_TICKS = 5;
	/**
	 * How long remip kicks keep firing after the last block-update batch.
	 * WHY a window instead of asking the mipper whether work remains: a
	 * cascade (L0 dirt causes L1 writes causes L2...) needs several passes
	 * of unknowable count, and a handful of no-op worker calls over ten
	 * seconds is cheaper than a cross-thread "any work left?" protocol.
	 */
	private static final int REMIP_ARMED_TICKS = 200;
	/** Eviction sweep cadence (15 s) — periodic like the classic save-cycle sweep. */
	private static final int EVICTION_SWEEP_TICKS = 300;
	/**
	 * Hard cap on queued block updates (16 full drain ticks). A runaway
	 * redstone contraption must not grow an unbounded backlog; dropped
	 * updates self-heal through the chunk's next full capture (unload
	 * snapshot or recapture on approach).
	 */
	private static final int MAX_QUEUED_BLOCK_UPDATES = 4096;

	/** Shared with the classic engine (DESIGN.md section 4: one worker pool). */
	private final ExecutorService worker;
	/**
	 * Global palettes, one instance for the whole session. Per the design
	 * they are world-scoped (shared across dimensions); M1 resets them on
	 * every level unload because with no persistence there is nothing an id
	 * could outlive — M2 moves the reset to world (not dimension) switches.
	 */
	private final VoxelPalettes palettes = new VoxelPalettes();

	/**
	 * The two-tier store (HOT/WARM/disk) for the current world; null between
	 * levels. Replaces M1's bare VoxelWorld — the HOT tier lives inside it
	 * ({@link VoxelStore#hot()}). Created/nulled only on the client thread.
	 */
	private volatile VoxelStore store;
	/**
	 * Incremental remipper bound to {@link #store}; created and nulled with it
	 * (both mutated only on the client thread under {@code this}), so whenever
	 * {@code store} is non-null on the tick thread this is the matching mipper.
	 */
	private volatile VoxelMipper mipper;
	/** Dimension the current store belongs to; guards stray events (classic rule). */
	private volatile ResourceKey<Level> worldDimension;
	/**
	 * World id ({@code local_<name>} / {@code server_<ip>} / ...) the shared
	 * {@link #palettes} were loaded for. A change means a genuinely different
	 * world, so the palette is cleared and reloaded; a mere dimension switch
	 * keeps it (palettes are world-scoped, shared across dimensions).
	 */
	private volatile String currentWorldId;
	/** At most one save/evict cycle in flight at a time (design: engine serializes cycles). */
	private final AtomicBoolean cycleInFlight = new AtomicBoolean();

	/** Chunks awaiting snapshot; drained a few per tick to avoid client-thread spikes. */
	private final ConcurrentLinkedQueue<LevelChunk> loadQueue = new ConcurrentLinkedQueue<>();
	/** Unloading chunks jump the load queue: last chance at their data. */
	private final ConcurrentLinkedQueue<LevelChunk> unloadQueue = new ConcurrentLinkedQueue<>();

	private record BlockUpdate(long packedPos, BlockState state) {
	}

	private final ConcurrentLinkedQueue<BlockUpdate> blockUpdateQueue = new ConcurrentLinkedQueue<>();
	/** Tracked separately because ConcurrentLinkedQueue.size() is O(n). */
	private final AtomicInteger blockUpdateQueueSize = new AtomicInteger();
	/** Snapshots submitted to workers and not yet ingested; the backpressure gauge. */
	private final AtomicInteger pendingSnapshots = new AtomicInteger();
	/**
	 * Chunk positions already captured this session WITH trustworthy light. A
	 * chunk must have been loaded to be unloaded, so anything in here already
	 * holds good cells and its unload re-capture — whose light the client has
	 * since thrown away — must be skipped entirely rather than allowed to write
	 * sky=0 over them. A chunk that is absent here was never captured (deferred
	 * waiting for light, or dropped under backpressure), so its unload capture
	 * is still the genuine last chance at its data. Client thread only.
	 */
	private final Set<Long> capturedChunks = new HashSet<>();
	/**
	 * Vanilla sections touched by a block update, mapped to the tick it happened
	 * (client thread only). {@link VoxelIngest#applyBlockUpdate} deliberately
	 * keeps the cell's OLD light bits, because the real light is only known once
	 * the client's light engine has propagated the change — so a cell that
	 * became air keeps the solid block's zero light and shades every face beside
	 * it black until someone rewrites it. That rewrite is
	 * {@link VoxelIngest#applySectionLight}, which had no callers at all; this
	 * map is what finally drives it, a few ticks after the edit so the light
	 * engine has settled.
	 */
	private final Map<Long, Integer> lightRefreshSections = new HashMap<>();
	/** Mesh regions (all levels) whose data changed since the last drain; the renderer re-meshes them. */
	private final java.util.Set<Long> dirtyMeshRegions = ConcurrentHashMap.newKeySet();

	private int tickCounter;
	/** Tick until which the periodic remip kick stays armed; client thread only. */
	private int remipArmedUntilTick;
	private volatile boolean blockUpdateDropLogged;

	public VoxelEngine(ExecutorService worker) {
		this.worker = worker;
	}

	/** True when the voxel engine is selected, enabled, and has a live world. */
	public boolean isActive() {
		return HorizonConfig.get().isEnabled() && HorizonConfig.get().isVoxelEngine() && store != null;
	}

	public VoxelPalettes palettes() {
		return palettes;
	}

	/** Two-tier store for the current dimension; null between levels. The M3 mesher reads through it. */
	public VoxelStore store() {
		return store;
	}

	/** Marks every level's mesh region covering a chunk as needing a re-mesh. Worker thread. */
	private void markMeshRegionsDirty(int chunkX, int chunkZ) {
		int bx = chunkX << 4;
		int bz = chunkZ << 4;
		for (int lvl = 0; lvl <= VoxelConstants.MAX_LEVEL; lvl++) {
			int span = VoxelRegionKey.regionSpanBlocks(lvl);
			dirtyMeshRegions.add(VoxelRegionKey.pack(lvl, Math.floorDiv(bx, span), Math.floorDiv(bz, span)));
		}
	}

	/** Takes the mesh regions dirtied since the last call; the scheduler re-meshes them. Client thread. */
	public Set<Long> drainDirtyMeshRegions() {
		if (dirtyMeshRegions.isEmpty()) {
			return Set.of();
		}
		Set<Long> copy = new HashSet<>(dirtyMeshRegions);
		dirtyMeshRegions.removeAll(copy);
		return copy;
	}

	// --- Event entry points (client thread, via HorizonLod) ---

	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		ensureWorld(level);
		loadQueue.add(chunk);
	}

	public void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		unloadQueue.add(chunk);
	}

	/**
	 * Mixin funnel for server-verified block changes (design section 3.5).
	 * Client thread; must stay cheap — one dimension check and one queue
	 * offer. The state rides along because the level is not safely readable
	 * from the worker that will apply the update.
	 */
	public void onBlockChanged(ClientLevel level, BlockPos pos, BlockState newState) {
		if (store == null) {
			return;
		}
		ResourceKey<Level> dim = worldDimension;
		if (dim != null && level.dimension() != dim) {
			return;
		}
		if (blockUpdateQueueSize.get() >= MAX_QUEUED_BLOCK_UPDATES) {
			if (!blockUpdateDropLogged) {
				blockUpdateDropLogged = true;
				Iris.logger.warn("Horizon: voxel block-update queue full, dropping updates"
					+ " (self-heals via recapture; logged once)");
			}
			return;
		}
		blockUpdateQueue.add(new BlockUpdate(pos.asLong(), newState));
		blockUpdateQueueSize.incrementAndGet();
		// Queue this section for a light refresh once the light engine settles.
		lightRefreshSections.put(SectionPos.asLong(pos), tickCounter);
	}

	/** Per-tick pump: snapshot budget, block-update drain, remip kicks, eviction cadence. */
	public void onClientTick(Minecraft mc) {
		if (mc.level == null) {
			return;
		}
		tickCounter++;
		VoxelStore s = store;
		if (s == null) {
			// No store means nothing enqueued is worth keeping (chunk loads
			// always create the store first); just drop strays.
			loadQueue.clear();
			unloadQueue.clear();
			return;
		}
		VoxelMipper m = mipper;
		drainSnapshots(s);
		if (m != null) {
			drainBlockUpdates(s, m);
			drainLightRefreshes(mc, s, m);
			kickRemips(m);
		}
		// Persistence + eviction: full save+evict on the sweep cadence, a lighter
		// save-only pass on the configured save interval, and an early save when
		// unsaved HOT sections pile up (bounds crash-loss while exploring fast).
		if (mc.player != null) {
			boolean fullEvict = tickCounter % EVICTION_SWEEP_TICKS == 0;
			boolean saveOnly = tickCounter % saveIntervalTicks() == 0
				|| s.hotDirtyCount() >= VoxelConstants.UNSAVED_FLUSH_THRESHOLD;
			if (fullEvict || saveOnly) {
				BlockPos cam = mc.player.blockPosition();
				scheduleCycle(s, cam.getX(), cam.getZ(), fullEvict);
			}
		}
	}

	private static int saveIntervalTicks() {
		return Math.max(20, HorizonConfig.get().getSaveIntervalSeconds() * 20);
	}

	/**
	 * Level unload: forget the world (workers still running write into the
	 * orphaned instance, harmlessly), clear every queue, reset the palettes.
	 * The map teardown itself runs on a worker — clearing tens of thousands
	 * of entries is not client-tick work.
	 */
	public void onLevelUnload() {
		VoxelStore s;
		synchronized (this) {
			s = store;
			store = null;
			mipper = null;
			worldDimension = null;
		}
		loadQueue.clear();
		unloadQueue.clear();
		capturedChunks.clear();
		blockUpdateQueue.clear();
		blockUpdateQueueSize.set(0);
		blockUpdateDropLogged = false;
		remipArmedUntilTick = 0;
		dirtyMeshRegions.clear();
		// The shared palette is NOT cleared here: it is world-scoped and kept
		// across dimension switches; it is cleared and reloaded only when the
		// world id actually changes (ensureWorld).
		if (s != null) {
			// Flush the palette SYNCHRONOUSLY on the client thread, before this
			// method returns and hence before any later ensureWorld() can
			// clear()+load() the shared, mutable palette for a different world.
			// Deferring this to the worker (as the section flush is) would let a
			// world switch that runs first write the NEW world's palette content
			// into THIS world's palette.nbt — silent cross-world corruption. The
			// async section flush below never reads palette content (ids are baked
			// into cells already), so only the palette save must run here.
			try {
				s.flushPalette();
			} catch (Throwable t) {
				Iris.logger.error("Horizon: voxel unload palette flush failed", t);
			}
			worker.submit(() -> {
				try {
					// Gate against any still-in-flight periodic save/evict cycle on
					// this same store: flushAll drains the shared dirty set and clears
					// both tiers plus the region monitors, none of which may run while
					// a cycle is packing/recycling sections. No NEW cycle can start
					// (store is already null, so onClientTick no longer schedules one),
					// so this only waits out the current cycle, then takes the latch
					// itself and releases it when done.
					while (!cycleInFlight.compareAndSet(false, true)) {
						java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
					}
					try {
						s.flushAll();
					} finally {
						cycleInFlight.set(false);
					}
				} catch (Throwable t) {
					Iris.logger.error("Horizon: voxel unload flush failed", t);
				}
			});
		}
	}

	/**
	 * Settings-screen callback. Budgets and eviction radii are read from
	 * HorizonConfig at each use, so an engine-on change needs no action;
	 * only switching the engine OFF matters — the voxel residency (up to
	 * hundreds of MB) must not linger under a classic session.
	 */
	public void onConfigChanged() {
		if (!HorizonConfig.get().isVoxelEngine()) {
			onLevelUnload();
		}
	}

	/**
	 * The M1 acceptance surface: one F3 line with live per-level section
	 * counts, palette size and queue depth, e.g.
	 * {@code Horizon/voxel: L0 123 L1 45 L2 12 L3 4 L4 1 sections, palette 456 states, queue 3}.
	 * Only called while the debug overlay is open, so the O(n) queue sizes
	 * are fine.
	 */
	public void addDebugText(List<String> lines) {
		VoxelStore s = store;
		if (s == null) {
			return;
		}
		VoxelWorld hot = s.hot();
		StringBuilder line = new StringBuilder(128).append("Horizon/voxel: ");
		for (int level = 0; level < VoxelConstants.LEVEL_COUNT; level++) {
			line.append('L').append(level).append(' ').append(hot.sectionCount(level)).append(' ');
		}
		int queued = loadQueue.size() + unloadQueue.size()
			+ pendingSnapshots.get() + blockUpdateQueueSize.get();
		line.append("hot, warm ").append(s.warmSectionCount())
			.append(" (").append(s.warmBytes() >> 20).append("MB), palette ").append(palettes.stateCount())
			.append(" states, queue ").append(queued);
		lines.add(line.toString());
	}

	// --- Internals ---

	private void ensureWorld(ClientLevel level) {
		if (store != null) {
			return;
		}
		synchronized (this) {
			if (store != null) {
				return;
			}
			String worldId = resolveWorldId();
			String dimensionId = level.dimension().location().toString();
			Path gameDir = FMLPaths.GAMEDIR.get();
			VoxelRegionStorage disk = new VoxelRegionStorage(gameDir, worldId, dimensionId);
			Path paletteFile = VoxelRegionStorage.paletteFile(gameDir, worldId);

			// Palette is world-scoped (shared across dimensions). Clear+reload
			// only when the world id changes — a dimension switch within one
			// world keeps the live palette (and its unsaved ids).
			if (!worldId.equals(currentWorldId)) {
				palettes.clear();
				try {
					RegistryAccess registries = level.registryAccess();
					HolderGetter<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
					Registry<Biome> biomes = registries.registryOrThrow(Registries.BIOME);
					palettes.load(paletteFile, blocks, biomes);
				} catch (Throwable t) {
					// A palette that fails to load leaves ids starting fresh; old
					// section files citing higher ids resolve to stone on read
					// (codec tombstone) rather than crashing.
					Iris.logger.error("Horizon: voxel palette load failed for " + worldId, t);
				}
				currentWorldId = worldId;
			}

			VoxelWorld hot = new VoxelWorld();
			VoxelStore s = new VoxelStore(hot, palettes, disk, paletteFile);
			mipper = new VoxelMipper(s, palettes);
			store = s;
			Iris.logger.info("Horizon: voxel store ready for " + worldId + " / " + dimensionId);
		}
	}

	/** Same world-id scheme as the classic engine so both keep disjoint per-world caches. */
	private static String resolveWorldId() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() != null) {
			return "local_" + mc.getSingleplayerServer().getWorldData().getLevelName();
		}
		if (mc.getCurrentServer() != null) {
			return "server_" + mc.getCurrentServer().ip;
		}
		if (mc.getConnection() != null && mc.getConnection().getConnection() != null
			&& mc.getConnection().getConnection().getRemoteAddress() != null) {
			return "remote_" + mc.getConnection().getConnection().getRemoteAddress();
		}
		return "unknown";
	}

	private void drainSnapshots(VoxelStore s) {
		int budget = MAX_SNAPSHOTS_PER_TICK;
		// Unloads are last-chance: capture regardless of light readiness, but
		// flag the snapshot light-untrusted (the client wipes a chunk's light
		// layers immediately after posting the unload event, so what we read
		// here is gone, not dark).
		budget -= drainCaptures(s, unloadQueue, budget, false);
		// Unloads always got their shot above; loads additionally respect
		// the in-flight ceiling — the queue itself is the retry set, the
		// chunk stays loaded until we get to it — and wait for light to land.
		if (budget > 0 && pendingSnapshots.get() < MAX_PENDING_SNAPSHOTS) {
			drainCaptures(s, loadQueue, budget, true);
		}
	}

	/**
	 * Drains up to {@code budget} chunks from one queue; returns how many were
	 * captured. When {@code requireLight}, a chunk whose client skylight has not
	 * landed yet (see {@link #isLightReady}) is deferred back onto the queue
	 * instead of captured — capturing it now would bake sky=0 and shade its
	 * surface pitch-black. {@code examined} is bounded so a burst of not-yet-lit
	 * chunks cannot spin the whole queue in one tick.
	 */
	private int drainCaptures(VoxelStore s, ConcurrentLinkedQueue<LevelChunk> queue, int budget, boolean requireLight) {
		int taken = 0;
		int examined = 0;
		int maxExamine = budget * 8;
		List<LevelChunk> deferred = null;
		while (taken < budget && examined < maxExamine) {
			LevelChunk chunk = queue.poll();
			if (chunk == null) {
				break;
			}
			examined++;
			ResourceKey<Level> dim = worldDimension;
			if (dim != null && chunk.getLevel().dimension() != dim) {
				// Stray cross-dimension event (mods firing out of order during
				// dimension switches) — same guard as the classic engine.
				continue;
			}
			long chunkPos = chunk.getPos().toLong();
			if (!requireLight && capturedChunks.remove(chunkPos)) {
				// Unload capture of a chunk we already captured with real light:
				// the client wiped its light layers right after posting the unload
				// event, so re-ingesting now would write sky=0 over good cells and
				// blacken this chunk permanently. Nothing to gain, everything to
				// lose — skip it (block edits since load came through their own path).
				continue;
			}
			// Trust is a property of the LIGHT, never of which queue the chunk came
			// from. A chunk can sit in the load queue until after it unloads (the
			// queue holds a strong reference, so its states stay readable while the
			// client wipes its light layers); capturing that as trusted would write
			// sky=0 over correct cells with the untrusted guard disabled — the exact
			// damage that guard exists to stop.
			boolean lightReady = requireLight && isLightReady(chunk);
			// Defer only while the chunk is still loaded; a chunk that unloaded
			// while waiting for light gets captured now (last chance) rather than
			// looping forever in the queue — but as UNTRUSTED.
			if (requireLight && !lightReady && chunkStillLoaded(chunk)) {
				if (deferred == null) {
					deferred = new ArrayList<>();
				}
				deferred.add(chunk);
				continue;
			}
			taken++;
			if (lightReady) {
				capturedChunks.add(chunkPos);
			}
			captureChunk(s, chunk, lightReady);
		}
		if (deferred != null) {
			queue.addAll(deferred); // retry next tick, once light has propagated
		}
		return taken;
	}

	/**
	 * Whether the client has applied this chunk's skylight yet. The chunk-load
	 * event fires before {@code ClientPacketListener} runs its queued
	 * {@code applyLightData}, so right after load the sky {@link DataLayer} for
	 * the surface section is still absent; capturing then bakes sky=0 (black
	 * surfaces). Probing the highest non-air section's sky layer tells us the
	 * light packet has been applied.
	 */
	private static boolean isLightReady(LevelChunk chunk) {
		if (!chunk.getLevel().dimensionType().hasSkyLight()) {
			return true; // Nether/End have no skylight to wait for; never stall them
		}
		// Only trust light in the INTERIOR of the loaded area. Unloading a chunk
		// makes the client mark all its sections empty
		// (ClientPacketListener.queueLightRemoval -> updateSectionStatus(pos, true)),
		// which re-propagates sky light into the still-loaded neighbours. Those
		// queued updates drain in bulk once the player stops moving, so a chunk on
		// the border is regularly mid-recalculation — capturing it then freezes
		// transient darkness into the LOD forever. An edge chunk simply waits; it
		// becomes interior as the player approaches, and its unload capture is
		// still the last-chance path if they never do.
		if (!neighborsLoaded(chunk)) {
			return false;
		}
		int highest = chunk.getHighestFilledSectionIndex();
		if (highest < 0) {
			return true; // all-air column: nothing to shade, don't stall it
		}
		int sectionY = chunk.getMinSection() + highest;
		var sky = chunk.getLevel().getLightEngine().getLayerListener(LightLayer.SKY);
		return sky.getDataLayerData(SectionPos.of(chunk.getPos().x, sectionY, chunk.getPos().z)) != null;
	}

	/** Whether this chunk position is still resident (a deferred capture of an unloaded chunk must not linger). */
	private static boolean chunkStillLoaded(LevelChunk chunk) {
		return chunk.getLevel().getChunkSource().hasChunk(chunk.getPos().x, chunk.getPos().z);
	}

	/** Whether all four cardinal neighbours of this chunk are loaded, i.e. its light is not being re-propagated. */
	private static boolean neighborsLoaded(LevelChunk chunk) {
		var source = chunk.getLevel().getChunkSource();
		int cx = chunk.getPos().x;
		int cz = chunk.getPos().z;
		return source.hasChunk(cx - 1, cz) && source.hasChunk(cx + 1, cz)
			&& source.hasChunk(cx, cz - 1) && source.hasChunk(cx, cz + 1);
	}

	/**
	 * Client-thread half of ingestion: copy the chunk's containers
	 * (ChunkSnapshotter), then hand the copy to a worker for
	 * convert/pyramid/merge (VoxelIngest). Only the copy runs here —
	 * PalettedContainer is not safely readable off-thread.
	 */
	private void captureChunk(VoxelStore s, LevelChunk chunk, boolean lightTrusted) {
		try {
			var snapshot = ChunkSnapshotter.snapshot(chunk, lightTrusted);
			if (snapshot == null) {
				return; // nothing captureable
			}
			var pos = chunk.getPos();
			pendingSnapshots.incrementAndGet();
			try {
				worker.submit(() -> {
					try {
						VoxelIngest.ingest(snapshot, s, palettes);
						// New data landed: flag the mesh regions covering this
						// chunk so the scheduler re-meshes them with the fuller
						// data. Without this, a region meshed from a partial
						// ingest stays a floating fragment (the exploration
						// pillars) until it is evicted and re-approached.
						markMeshRegionsDirty(pos.x, pos.z);
					} catch (Throwable t) {
						// An ingest failure (exotic modded state mid-convert)
						// must never kill the worker or the queue.
						Iris.logger.error("Horizon: voxel ingest failed for chunk " + pos, t);
					} finally {
						pendingSnapshots.decrementAndGet();
					}
				});
			} catch (Throwable t) {
				pendingSnapshots.decrementAndGet();
				throw t;
			}
		} catch (Throwable t) {
			// Capture failures must never crash the tick loop (classic rule).
			Iris.logger.error("Horizon: voxel snapshot failed for chunk " + chunk.getPos(), t);
		}
	}

	private void drainBlockUpdates(VoxelStore s, VoxelMipper m) {
		if (blockUpdateQueueSize.get() == 0) {
			return;
		}
		List<BlockUpdate> batch = new ArrayList<>(Math.min(MAX_BLOCK_UPDATES_PER_TICK, blockUpdateQueueSize.get()));
		for (int i = 0; i < MAX_BLOCK_UPDATES_PER_TICK; i++) {
			BlockUpdate update = blockUpdateQueue.poll();
			if (update == null) {
				break;
			}
			blockUpdateQueueSize.decrementAndGet();
			batch.add(update);
		}
		if (batch.isEmpty()) {
			return;
		}
		worker.submit(() -> {
			try {
				for (BlockUpdate update : batch) {
					VoxelIngest.applyBlockUpdate(s, palettes, m,
						BlockPos.of(update.packedPos()), update.state());
				}
			} catch (Throwable t) {
				Iris.logger.error("Horizon: voxel block-update batch failed", t);
			}
		});
		remipArmedUntilTick = tickCounter + REMIP_ARMED_TICKS;
	}

	/**
	 * Rewrites LOD light for sections whose blocks changed, a few ticks after the
	 * edit so the client light engine has finished propagating. Without this the
	 * light bits written at capture time are the only ones a cell ever gets: a
	 * cell that a block update turned into air keeps the solid block's zero
	 * light and blackens every face beside it, permanently and cumulatively.
	 *
	 * <p>The layers are copied here (client thread — the light engine is not
	 * safely readable off-thread) and handed to a worker. A section whose sky
	 * layer is missing is skipped rather than refreshed: its light is unknown,
	 * not zero, and writing the null-sky fallback is exactly the mistake the
	 * unload capture used to make.
	 */
	private void drainLightRefreshes(Minecraft mc, VoxelStore s, VoxelMipper m) {
		if (lightRefreshSections.isEmpty() || mc.level == null) {
			return;
		}
		LevelLightEngine lightEngine = mc.level.getLightEngine();
		LayerLightEventListener blockLayer = lightEngine.getLayerListener(LightLayer.BLOCK);
		LayerLightEventListener skyLayer = lightEngine.getLayerListener(LightLayer.SKY);
		boolean hasSkyLight = mc.level.dimensionType().hasSkyLight();
		int done = 0;
		for (Iterator<Map.Entry<Long, Integer>> it = lightRefreshSections.entrySet().iterator();
			 it.hasNext() && done < MAX_LIGHT_REFRESHES_PER_TICK; ) {
			Map.Entry<Long, Integer> e = it.next();
			if (tickCounter - e.getValue() < LIGHT_REFRESH_DELAY_TICKS) {
				continue; // still settling
			}
			long sectionKey = e.getKey();
			it.remove();
			int cx = SectionPos.x(sectionKey);
			int sy = SectionPos.y(sectionKey);
			int cz = SectionPos.z(sectionKey);
			LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
			if (chunk == null || !neighborsLoaded(chunk)) {
				continue; // unloaded, or on the border where light is being re-propagated
			}
			SectionPos pos = SectionPos.of(cx, sy, cz);
			DataLayer sky = skyLayer.getDataLayerData(pos);
			boolean above = sy > chunk.getMinSection() + chunk.getHighestFilledSectionIndex();
			if (hasSkyLight && sky == null && !above) {
				continue; // unknown, not dark — never write the fallback over real cells
			}
			DataLayer block = blockLayer.getDataLayerData(pos);
			DataLayer blockCopy = block == null ? null : block.copy();
			DataLayer skyCopy = sky == null ? null : sky.copy();
			done++;
			worker.submit(() -> {
				try {
					int changed = VoxelIngest.applySectionLight(s, m, cx, sy, cz, blockCopy, skyCopy, above);
					if (changed > 0) {
						markMeshRegionsDirty(cx, cz);
					}
				} catch (Throwable t) {
					Iris.logger.error("Horizon: voxel light refresh failed for section "
						+ cx + "," + sy + "," + cz, t);
				}
			});
		}
	}

	/**
	 * Periodic remip pump: while armed by a recent block-update batch, kick
	 * a bounded VoxelMipper pass every MIP_DEBOUNCE_TICKS so edits propagate
	 * up the mip chain without ever monopolizing a worker. Bulk ingest never
	 * arms this — it writes all five levels directly.
	 */
	private void kickRemips(VoxelMipper m) {
		if (tickCounter > remipArmedUntilTick || tickCounter % MIP_DEBOUNCE_TICKS != 0) {
			return;
		}
		worker.submit(() -> {
			try {
				m.processRemips(MAX_REMIP_SECTIONS_PER_PASS);
			} catch (Throwable t) {
				Iris.logger.error("Horizon: voxel remip pass failed", t);
			}
		});
	}

	/**
	 * Submits one save/evict cycle to the worker pool, but only if none is
	 * already running — the store's per-region monitors handle ingest overlap,
	 * but two full cycles at once would double the work for nothing. {@code
	 * fullEvict} runs the whole HOT→WARM pack + WARM eviction; otherwise it is a
	 * lighter save-only pass. The cycle operates on the captured {@code s}, so a
	 * level unload that nulls the field mid-cycle is harmless.
	 */
	private void scheduleCycle(VoxelStore s, int camBlockX, int camBlockZ, boolean fullEvict) {
		if (!cycleInFlight.compareAndSet(false, true)) {
			return;
		}
		worker.submit(() -> {
			try {
				s.runSaveAndEvictCycle(camBlockX, camBlockZ, fullEvict);
			} catch (Throwable t) {
				Iris.logger.error("Horizon: voxel save/evict cycle failed", t);
			} finally {
				cycleInFlight.set(false);
			}
		});
	}
}
