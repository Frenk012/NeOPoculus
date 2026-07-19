package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.HorizonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
		budget -= drainCaptures(s, unloadQueue, budget);
		// Unloads always got their shot above; loads additionally respect
		// the in-flight ceiling — the queue itself is the retry set, the
		// chunk stays loaded until we get to it.
		if (budget > 0 && pendingSnapshots.get() < MAX_PENDING_SNAPSHOTS) {
			drainCaptures(s, loadQueue, budget);
		}
	}

	/** Drains up to {@code budget} chunks from one queue; returns how many were captured. */
	private int drainCaptures(VoxelStore s, ConcurrentLinkedQueue<LevelChunk> queue, int budget) {
		int taken = 0;
		while (taken < budget) {
			LevelChunk chunk = queue.poll();
			if (chunk == null) {
				break;
			}
			ResourceKey<Level> dim = worldDimension;
			if (dim != null && chunk.getLevel().dimension() != dim) {
				// Stray cross-dimension event (mods firing out of order during
				// dimension switches) — same guard as the classic engine.
				continue;
			}
			taken++;
			captureChunk(s, chunk);
		}
		return taken;
	}

	/**
	 * Client-thread half of ingestion: copy the chunk's containers
	 * (ChunkSnapshotter), then hand the copy to a worker for
	 * convert/pyramid/merge (VoxelIngest). Only the copy runs here —
	 * PalettedContainer is not safely readable off-thread.
	 */
	private void captureChunk(VoxelStore s, LevelChunk chunk) {
		try {
			var snapshot = ChunkSnapshotter.snapshot(chunk);
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
