package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.HorizonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
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

	private volatile VoxelWorld world;
	/**
	 * Incremental remipper bound to {@link #world}; created and nulled with it
	 * (both mutated only on the client thread under {@code this}), so whenever
	 * {@code world} is non-null on the tick thread this is the matching mipper.
	 */
	private volatile VoxelMipper mipper;
	/** Dimension the current VoxelWorld belongs to; guards stray events (classic rule). */
	private volatile ResourceKey<Level> worldDimension;

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

	private int tickCounter;
	/** Tick until which the periodic remip kick stays armed; client thread only. */
	private int remipArmedUntilTick;
	private volatile boolean blockUpdateDropLogged;

	public VoxelEngine(ExecutorService worker) {
		this.worker = worker;
	}

	/** True when the voxel engine is selected, enabled, and has a live world. */
	public boolean isActive() {
		return HorizonConfig.get().isEnabled() && HorizonConfig.get().isVoxelEngine() && world != null;
	}

	public VoxelPalettes palettes() {
		return palettes;
	}

	/** Residency + dirty tracking for the current dimension; null between levels. M2's store consumes this seam. */
	public VoxelWorld world() {
		return world;
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
		VoxelWorld w = world;
		if (w == null) {
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
		VoxelWorld w = world;
		if (w == null) {
			// No world means nothing enqueued is worth keeping (chunk loads
			// always create the world first); just drop strays.
			loadQueue.clear();
			unloadQueue.clear();
			return;
		}
		VoxelMipper m = mipper;
		drainSnapshots(w);
		if (m != null) {
			drainBlockUpdates(w, m);
			kickRemips(w, m);
		}
		if (tickCounter % EVICTION_SWEEP_TICKS == 0 && mc.player != null) {
			BlockPos cam = mc.player.blockPosition();
			scheduleEviction(w, cam.getX(), cam.getZ());
		}
	}

	/**
	 * Level unload: forget the world (workers still running write into the
	 * orphaned instance, harmlessly), clear every queue, reset the palettes.
	 * The map teardown itself runs on a worker — clearing tens of thousands
	 * of entries is not client-tick work.
	 */
	public void onLevelUnload() {
		VoxelWorld w;
		synchronized (this) {
			w = world;
			world = null;
			mipper = null;
			worldDimension = null;
		}
		loadQueue.clear();
		unloadQueue.clear();
		blockUpdateQueue.clear();
		blockUpdateQueueSize.set(0);
		blockUpdateDropLogged = false;
		remipArmedUntilTick = 0;
		// New level, new registries: stale BlockState keys must not pin the
		// old world's objects (VoxelPalettes.clear's documented contract).
		palettes.clear();
		if (w != null) {
			worker.submit(w::clearAll);
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
		VoxelWorld w = world;
		if (w == null) {
			return;
		}
		StringBuilder line = new StringBuilder(96).append("Horizon/voxel: ");
		for (int level = 0; level < VoxelConstants.LEVEL_COUNT; level++) {
			line.append('L').append(level).append(' ').append(w.sectionCount(level)).append(' ');
		}
		int queued = loadQueue.size() + unloadQueue.size()
			+ pendingSnapshots.get() + blockUpdateQueueSize.get();
		line.append("sections, palette ").append(palettes.stateCount())
			.append(" states, queue ").append(queued);
		lines.add(line.toString());
	}

	// --- Internals ---

	private void ensureWorld(ClientLevel level) {
		if (world != null) {
			return;
		}
		synchronized (this) {
			if (world != null) {
				return;
			}
			worldDimension = level.dimension();
			VoxelWorld w = new VoxelWorld();
			mipper = new VoxelMipper(w, palettes);
			world = w;
			Iris.logger.info("Horizon: voxel world ready for " + level.dimension().location()
				+ " (M1, in-memory only)");
		}
	}

	private void drainSnapshots(VoxelWorld w) {
		int budget = MAX_SNAPSHOTS_PER_TICK;
		budget -= drainCaptures(w, unloadQueue, budget);
		// Unloads always got their shot above; loads additionally respect
		// the in-flight ceiling — the queue itself is the retry set, the
		// chunk stays loaded until we get to it.
		if (budget > 0 && pendingSnapshots.get() < MAX_PENDING_SNAPSHOTS) {
			drainCaptures(w, loadQueue, budget);
		}
	}

	/** Drains up to {@code budget} chunks from one queue; returns how many were captured. */
	private int drainCaptures(VoxelWorld w, ConcurrentLinkedQueue<LevelChunk> queue, int budget) {
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
			captureChunk(w, chunk);
		}
		return taken;
	}

	/**
	 * Client-thread half of ingestion: copy the chunk's containers
	 * (ChunkSnapshotter), then hand the copy to a worker for
	 * convert/pyramid/merge (VoxelIngest). Only the copy runs here —
	 * PalettedContainer is not safely readable off-thread.
	 */
	private void captureChunk(VoxelWorld w, LevelChunk chunk) {
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
						VoxelIngest.ingest(snapshot, w, palettes);
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

	private void drainBlockUpdates(VoxelWorld w, VoxelMipper m) {
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
					VoxelIngest.applyBlockUpdate(w, palettes, m,
						net.minecraft.core.BlockPos.of(update.packedPos()), update.state());
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
	private void kickRemips(VoxelWorld w, VoxelMipper m) {
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

	private void scheduleEviction(VoxelWorld w, int camBlockX, int camBlockZ) {
		int[] keepRadii = keepRadiiSections();
		int maxSections = maxResidentSections();
		worker.submit(() -> {
			try {
				int evicted = w.evictOutside(camBlockX, camBlockZ, keepRadii, maxSections);
				if (evicted > 0) {
					Iris.logger.debug("Horizon: voxel eviction dropped " + evicted + " sections");
				}
			} catch (Throwable t) {
				Iris.logger.error("Horizon: voxel eviction sweep failed", t);
			}
		});
	}

	/**
	 * Per-level keep radii, in sections at each level, derived from the
	 * mesh-level ring table (design section 5.1): a level is kept out to 1.5x
	 * its ring's outer edge (the M2 warm-tier phase-1 margin), plus a
	 * two-section cushion so the collar and the ring-boundary hysteresis
	 * band never sit right on the eviction edge. Recomputed per sweep so
	 * config changes apply live.
	 */
	private static int[] keepRadiiSections() {
		int ringWidth = HorizonConfig.get().getLodRingWidth();
		int maxDistance = HorizonConfig.get().getLodDistanceBlocks();
		int[] radii = new int[VoxelConstants.LEVEL_COUNT];
		for (int level = 0; level < radii.length; level++) {
			int outerBlocks = level == VoxelConstants.MAX_LEVEL
				? maxDistance
				: Math.min(maxDistance, ringWidth << level);
			int spanBlocks = SectionKey.sectionSpanBlocks(level);
			radii[level] = (outerBlocks * 3 / 2 + spanBlocks - 1) / spanBlocks + 2;
		}
		return radii;
	}

	/**
	 * Hard resident-section ceiling from {@code voxelMemoryBudgetMb}. M1 has no
	 * warm-packing tier yet, so residency is raw 256 KB sections and the keep
	 * radii alone (sized for the M2 warm formula) do not bound RAM — this cap
	 * does. At the 256 MB default that is 1024 sections; the eviction sweep
	 * drops the coldest sections past this count by global LRU. When M2 lands
	 * the warm tier its own byte budget supersedes this raw-section count.
	 */
	private static int maxResidentSections() {
		long budgetBytes = (long) HorizonConfig.get().getVoxelMemoryBudgetMb() * 1024L * 1024L;
		long sectionBytes = (long) VoxelConstants.SECTION_CELLS * Long.BYTES;
		return (int) Math.max(1, budgetBytes / sectionBytes);
	}
}
