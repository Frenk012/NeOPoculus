package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.horizon.HorizonConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two-tier residency + disk faulting for one dimension of the voxel LOD engine
 * (design-data-storage.md sections 5.2 and 6). Wraps the M1 {@link VoxelWorld}
 * (which stays the HOT tier — the raw {@code long[]} residency map, the F3
 * counters and the dirty set) with a WARM tier of codec-packed byte rows and a
 * {@link VoxelRegionStorage} disk backend.
 *
 * <h2>Tiers are disjoint</h2>
 * A section key lives in at most one tier at a time. Promotion (WARM&rarr;HOT)
 * <em>removes</em> the warm entry; packing (HOT&rarr;WARM) removes the hot
 * section. This is the locked model ("WARM hit &rarr; inflate + promote to HOT,
 * remove warm entry") and it keeps the section 5.3 RAM budget honest — HOT
 * (64 MB) and WARM ({@code voxelMemoryBudgetMb}) never double-count the same
 * section. A consequence: warm entries are always immutable and always already
 * durable on disk (a section reaches WARM only by being saved-then-packed, or by
 * being faulted from disk), so WARM eviction is a pure RAM drop — it can never
 * lose data. The design's alternative "dirty rows stay dirty in WARM" scheme is
 * deliberately not used; save-before-pack is simpler and equally lossless.
 *
 * <h2>acquire state machine</h2>
 * <ul>
 * <li>{@link #acquireForWrite} (ingest bulk merge): HOT &rarr; WARM inflate+promote
 *     &rarr; disk fault (install the whole region's rows into WARM, then inflate)
 *     &rarr; create a fresh all-air HOT section. Always returns a HOT section.</li>
 * <li>{@link #acquireBlocking} (ingest block-edit / light refresh): same faulting,
 *     but returns {@code null} instead of creating — a lone edit must never
 *     fabricate terrain the population mask says was never captured.</li>
 * <li>{@link #acquire} (M3 async mesh read): HOT or already-WARM only; a true miss
 *     returns {@code null} so the caller enqueues an async load. Never touches disk.</li>
 * </ul>
 *
 * <h2>Fault serialization &amp; the use-after-recycle discipline</h2>
 * Every path that changes a key's residency <em>with new data</em> — WARM/disk
 * inflate, fresh create, and HOT&rarr;WARM pack — runs under a per-storage-region
 * monitor ({@link #regionGuard}). This closes the one dangerous race: two ingest
 * workers touching a shared parent key, where an all-air create could otherwise
 * clobber a disk-loaded section. HOT&rarr;WARM packing recycles a cell array only
 * after (a) the section has left HOT ({@link VoxelWorld#removeResident}, under the
 * region monitor) and (b) it was not touched within {@link #PACK_QUIESCE_NANOS}
 * (so no ingest worker is mid-write on it — every acquire touches the section, so
 * an actively-used section always looks recent). Even if a stalled worker still
 * held the reference, {@link VoxelSection#recycle} nulls the array under the
 * section monitor, so a late write fails fast with an NPE (caught by the worker
 * wrappers) rather than corrupting a pooled array. That is the chosen guard:
 * <em>quiesce window + monitor-nulled hand-off</em>, not a bare "skip if touched
 * this cycle".
 *
 * <p>Threading: all methods here run on worker/IO threads (never the client
 * tick). {@code VoxelEngine} serializes the periodic cycle against itself with an
 * in-flight flag, so at most one {@link #runSaveAndEvictCycle} / {@link #flushAll}
 * is in motion; the region monitors handle the residual ingest-vs-cycle overlap.
 */
public final class VoxelStore {
	/**
	 * A HOT section is not packed to WARM until it has been quiet this long
	 * (2 s). Ingest of one chunk takes 1-3 ms and every acquire bumps the
	 * section's touch clock, so this margin makes "packed while a worker still
	 * writes it" very rare — but not impossible: the ingest fast path acquires a
	 * resident HOT section lock-free (no region monitor), so a pack pass that
	 * already selected the section by its old touch clock can still recycle it in
	 * the narrow window before the caller's write. The monitor-nulled recycle
	 * keeps that safe from corruption (the late write hits a null array and fails
	 * fast with an NPE rather than clobbering a pooled array), and
	 * {@code VoxelIngest} isolates that NPE per section so one recycled section
	 * never aborts the rest of the chunk's ingest.
	 */
	private static final long PACK_QUIESCE_NANOS = 2_000_000_000L;

	/** Rough per-entry heap overhead (headers + refs) so tiny warm rows still count toward the byte budget. */
	private static final int WARM_ENTRY_OVERHEAD = 64;

	/** Storage-region coordinate bias/mask for the region-monitor key (secCoord >> 3 fits 23 signed bits; mirrors VoxelRegionStorage). */
	private static final int REGION_COORD_BIAS = 1 << 22;
	private static final long REGION_COORD_MASK = (1L << 23) - 1;

	private final VoxelWorld hot;
	private final VoxelPalettes palettes;
	private final VoxelRegionStorage disk;
	/** World-shared palette path; flushed first in every save cycle (design section 2 invariant). */
	private final Path paletteFile;

	/** Packed, immutable, already-on-disk section rows. Disjoint from {@link #hot}. */
	private final ConcurrentHashMap<Long, WarmEntry> warm = new ConcurrentHashMap<>(1024);
	/** Live sum of {@link WarmEntry#bytes}; weakly consistent against the budget check. */
	private final AtomicLong warmBytes = new AtomicLong();

	/**
	 * Storage regions whose disk rows have been faulted into WARM. Invariant:
	 * a region here has <em>all</em> of its on-disk rows resident (in WARM or
	 * promoted to HOT), so a WARM/HOT miss for a key in it means the key is
	 * genuinely not on disk and may be created fresh. Any true WARM drop
	 * (eviction, or an emptied section's removal) clears the region's flag so
	 * the next miss re-reads disk — keeping the invariant exact.
	 */
	private final Set<Long> loadedRegions = ConcurrentHashMap.newKeySet();

	/** Per-storage-region monitors serializing fault/create/pack for keys in that region. */
	private final ConcurrentHashMap<Long, Object> regionGuards = new ConcurrentHashMap<>();

	VoxelStore(VoxelWorld hot, VoxelPalettes palettes, VoxelRegionStorage disk, Path paletteFile) {
		this.hot = hot;
		this.palettes = palettes;
		this.disk = disk;
		this.paletteFile = paletteFile;
	}

	// --- Accessors (F3 line, ingest, mipper) ---------------------------------

	/** The HOT residency map; ingest's air-only clears and the mipper's child reads use it directly. */
	VoxelWorld hot() {
		return hot;
	}

	VoxelPalettes palettes() {
		return palettes;
	}

	/** Packed sections currently held in the WARM tier; F3 counter. */
	int warmSectionCount() {
		return warm.size();
	}

	/** Approximate WARM-tier bytes; F3 counter. */
	long warmBytes() {
		return warmBytes.get();
	}

	/** Unsaved HOT sections right now; drives the engine's early-flush trigger. */
	int hotDirtyCount() {
		return hot.dirtyCount();
	}

	/** Flags a HOT section dirty for the next save cycle; every ingest write calls this. */
	void markDirty(long key) {
		hot.markDirty(key);
	}

	// --- Acquire -------------------------------------------------------------

	/**
	 * Get-or-create for the ingest bulk-merge path: returns a HOT section for
	 * {@code key}, faulting it in from WARM or disk if it was evicted, or
	 * creating a fresh all-air one if it exists nowhere. Worker thread; may
	 * block briefly on a region read. Never returns null.
	 */
	VoxelSection acquireForWrite(long key) {
		VoxelSection s = hot.get(key);
		if (s != null) {
			s.touch();
			return s;
		}
		return faultOrCreate(key, true);
	}

	/**
	 * Blocking read for the block-edit and light-refresh paths: HOT / WARM /
	 * disk fault, but returns {@code null} rather than creating when the key
	 * exists nowhere — a sparse edit into a never-captured section would
	 * fabricate terrain the population mask says was never seen. Worker thread.
	 */
	VoxelSection acquireBlocking(long key) {
		VoxelSection s = hot.get(key);
		if (s != null) {
			s.touch();
			return s;
		}
		return faultOrCreate(key, false);
	}

	/**
	 * Non-blocking read for the (M3) async mesher: HOT hit or an already-WARM
	 * entry only; a true miss returns {@code null} so the caller enqueues a
	 * load. Never reads disk. The WARM promote is guarded so it can never lose
	 * an inflated section to a racing fresh-create.
	 */
	VoxelSection acquire(long key) {
		VoxelSection s = hot.get(key);
		if (s != null) {
			s.touch();
			return s;
		}
		if (!warm.containsKey(key)) {
			return null;
		}
		synchronized (regionGuard(regionKeyOf(key))) {
			s = hot.get(key);
			if (s != null) {
				s.touch();
				return s;
			}
			WarmEntry w = takeWarm(key);
			return w != null ? inflateAndInstall(key, w) : null;
		}
	}

	/**
	 * Slow-path residency resolution under the region monitor: recheck HOT,
	 * promote from WARM, else fault the whole region from disk into WARM and
	 * retry, else (optionally) create a fresh all-air HOT section. The monitor
	 * makes the whole hot/warm/disk/create decision atomic per region, so a
	 * concurrent acquire for the same key converges instead of racing an all-air
	 * create against a disk-loaded section.
	 */
	private VoxelSection faultOrCreate(long key, boolean createIfAbsent) {
		long region = regionKeyOf(key);
		synchronized (regionGuard(region)) {
			VoxelSection s = hot.get(key);
			if (s != null) {
				s.touch();
				return s;
			}
			WarmEntry w = takeWarm(key);
			if (w == null && !loadedRegions.contains(region)) {
				faultRegionIntoWarm(key, region);
				loadedRegions.add(region);
				w = takeWarm(key);
			}
			if (w != null) {
				return inflateAndInstall(key, w);
			}
			if (!createIfAbsent) {
				return null;
			}
			VoxelSection fresh = hot.acquireForWrite(key); // creates a fresh all-air resident section
			fresh.touch();
			return fresh;
		}
	}

	/**
	 * Reads a whole storage region and installs its packed rows into WARM
	 * (payloads stay in codec-byte form — inflate happens per requested key).
	 * Skips any row whose key is already resident (HOT or WARM), so a fault can
	 * never shadow newer in-memory data with an older disk row. Caller holds the
	 * region monitor.
	 */
	private void faultRegionIntoWarm(long anyKeyInRegion, long region) {
		int level = SectionKey.level(anyKeyInRegion);
		int rx = SectionKey.x(anyKeyInRegion) >> VoxelConstants.STORAGE_REGION_BITS;
		int rz = SectionKey.z(anyKeyInRegion) >> VoxelConstants.STORAGE_REGION_BITS;
		VoxelRegionStorage.RegionData data = disk.loadRegion(level, rx, rz);
		if (data.rows().isEmpty()) {
			return;
		}
		long now = System.nanoTime();
		for (VoxelRegionStorage.DirtyRow row : data.rows().values()) {
			long k = row.sectionKey();
			if (hot.get(k) != null || warm.containsKey(k)) {
				continue;
			}
			addWarm(k, new WarmEntry(row.nonAirCount(), row.populationMask(), row.payload(), now));
		}
	}

	/**
	 * Inflates a warm row into a pooled cell array and publishes it to HOT.
	 * If a racing writer installed the same key first (only reachable via the
	 * CHM path since we hold the region monitor), the redundant array is
	 * recycled and the winner adopted. Caller holds the region monitor.
	 */
	private VoxelSection inflateAndInstall(long key, WarmEntry w) {
		long[] cells = VoxelSectionCodec.decodeCells(w.payload); // pooled, released on decode failure
		VoxelSection fresh = new VoxelSection(key, cells, w.nonAirCount);
		fresh.restorePopulationMask(w.populationMask);
		VoxelSection installed = hot.installResident(fresh);
		if (installed != fresh) {
			fresh.recycle();
			return installed;
		}
		fresh.touch();
		return fresh;
	}

	// --- Save + evict cycle --------------------------------------------------

	/**
	 * The periodic worker cycle (design section 6): STEP 0 palette-first flush;
	 * STEP 1 save every dirty HOT section; and when {@code fullEvict}, STEP 2
	 * pack the coldest HOT sections down to WARM and STEP 3 evict WARM to the
	 * byte budget. {@code fullEvict == false} is the lighter save-only pass on
	 * the {@code saveIntervalSeconds} cadence; {@code true} is the full sweep.
	 *
	 * @param camBlockX camera block X (WARM phase-1 ring test)
	 * @param camBlockZ camera block Z
	 */
	void runSaveAndEvictCycle(int camBlockX, int camBlockZ, boolean fullEvict) {
		// STEP 0: palette first — every id cited by a row written below is then
		// already durable (design section 2).
		savePalette();

		// STEP 1: flush dirty HOT sections to disk.
		long[] scratch = new long[VoxelConstants.SECTION_CELLS];
		saveDirtyHot(scratch);

		if (!fullEvict) {
			return;
		}

		// STEP 2: pack the coldest HOT sections past the cap down into WARM.
		packHotToWarm(scratch);

		// STEP 3: two-phase WARM eviction to the RAM budget.
		evictWarm(camBlockX, camBlockZ);
	}

	private void savePalette() {
		try {
			palettes.saveIfDirty(paletteFile);
		} catch (Throwable t) {
			// A failed palette flush must not abort the section save below: the
			// codec remaps any unknown id to stone on read, so a lagging palette
			// degrades gracefully rather than corrupting the cycle.
			Iris.logger.error("Horizon: voxel palette save failed", t);
		}
	}

	/**
	 * Encodes every currently-dirty HOT section and read-modify-writes it into
	 * its storage region. Clears each section's dirty flag only on a successful
	 * region commit (and only if unchanged since the snapshot); on failure the
	 * keys are re-marked dirty so nothing is ever lost — the invariant carried
	 * over from {@code LodWorld.evictOutside} / {@code LodStorage.saveRegion}.
	 */
	private void saveDirtyHot(long[] scratch) {
		Set<Long> dirty = hot.drainDirtySections();
		if (dirty.isEmpty()) {
			return;
		}
		Map<Long, RegionBatch> batches = buildBatches(dirty, scratch);
		for (RegionBatch batch : batches.values()) {
			boolean ok = disk.saveRegionRows(batch.level, batch.rx, batch.rz, batch.rows);
			for (Pending p : batch.pending) {
				if (!ok) {
					hot.markDirty(p.key); // re-arm; retried next cycle
					continue;
				}
				if (p.removal) {
					dropEmptyHot(p.key, p.section, p.version);
				} else {
					p.section.clearDirtyIfUnchanged(p.version);
				}
			}
		}
	}

	/**
	 * Snapshots each dirty section under its monitor and frames a content or
	 * removal row, grouped by storage region for one RMW write apiece. A
	 * section that left HOT since the drain is skipped (its data went to WARM or
	 * was already saved). Reuses one {@code scratch} across sections — each
	 * encode consumes it before the next snapshot overwrites it.
	 */
	private Map<Long, RegionBatch> buildBatches(Set<Long> dirty, long[] scratch) {
		Map<Long, RegionBatch> batches = new HashMap<>();
		for (long key : dirty) {
			VoxelSection s = hot.get(key);
			if (s == null) {
				continue;
			}
			int level = SectionKey.level(key);
			long[] mask = new long[VoxelSection.populationMaskLongs(level)];
			// Snapshot cells AND population mask under ONE section-monitor hold,
			// and derive the census from that same snapshot, so the framed
			// nonAirCount / mask can never disagree with the encoded payload. Reading
			// nonAirCount() (or the mask) in a separate monitor acquisition could
			// catch a concurrent writeBatch's newer census against the older cell
			// snapshot and, on a crash after commit, persist a row whose census/mask
			// describe a different array than its payload (a false-empty or a stale
			// census the mesher would mistrust the frontier for).
			int version = s.copyCellsAndMaskInto(scratch, mask);
			int nonAir = VoxelSectionCodec.countNonAir(scratch);
			int rx = SectionKey.x(key) >> VoxelConstants.STORAGE_REGION_BITS;
			int rz = SectionKey.z(key) >> VoxelConstants.STORAGE_REGION_BITS;
			RegionBatch batch = batches.computeIfAbsent(regionKeyOf(key), r -> new RegionBatch(level, rx, rz));
			if (nonAir == 0) {
				batch.rows.add(VoxelRegionStorage.DirtyRow.removal(key));
				batch.pending.add(new Pending(key, s, version, true));
			} else {
				byte[] payload = VoxelSectionCodec.encodeCells(scratch);
				batch.rows.add(new VoxelRegionStorage.DirtyRow(key, nonAir, mask, payload));
				batch.pending.add(new Pending(key, s, version, false));
			}
		}
		return batches;
	}

	/**
	 * Drops an emptied (all-air) section from HOT after its removal row
	 * committed. Under the region monitor so it cannot race a fault: if the
	 * section was re-populated since the snapshot it is re-marked dirty and kept
	 * (the next cycle saves its content), otherwise it is removed and recycled.
	 */
	private void dropEmptyHot(long key, VoxelSection section, int version) {
		synchronized (regionGuard(regionKeyOf(key))) {
			if (hot.get(key) != section) {
				return;
			}
			if (section.nonAirCount() != 0) {
				hot.markDirty(key); // re-populated between snapshot and commit
				return;
			}
			if (hot.removeResident(key, section)) {
				section.recycle();
			}
			WarmEntry w = warm.get(key);
			if (w != null) {
				dropWarm(key, w);
			}
			// version is unused past the nonAirCount recheck, which is the tighter
			// test (a re-populated section always has nonAir > 0). Kept in the
			// signature for symmetry with the content path's clearDirtyIfUnchanged.
		}
	}

	/**
	 * Packs the coldest HOT sections above {@link VoxelConstants#HOT_CACHE_SECTIONS}
	 * down to WARM (global LRU by touch clock), skipping any touched within
	 * {@link #PACK_QUIESCE_NANOS}. Clean sections (the common case after STEP 1)
	 * pack with no disk write; a section re-dirtied since STEP 1 is left HOT and
	 * re-marked so the next cycle saves it. The array is recycled only after the
	 * section leaves HOT and its bytes are safely in the warm payload.
	 */
	private void packHotToWarm(long[] scratch) {
		int over = hot.totalSections() - VoxelConstants.HOT_CACHE_SECTIONS;
		if (over <= 0) {
			return;
		}
		long quiesceCutoff = System.nanoTime() - PACK_QUIESCE_NANOS;
		List<VoxelSection> all = hot.residentSections();
		all.sort(Comparator.comparingLong(VoxelSection::lastTouchedNanos));
		int packed = 0;
		for (VoxelSection s : all) {
			if (packed >= over) {
				break;
			}
			if (s.lastTouchedNanos() >= quiesceCutoff) {
				continue; // actively used by an ingest/mesh worker; leave it HOT
			}
			if (packOne(s, scratch)) {
				packed++;
			}
		}
	}

	/** Packs one HOT section to WARM under the region monitor; see {@link #packHotToWarm}. */
	private boolean packOne(VoxelSection s, long[] scratch) {
		long key = s.key;
		synchronized (regionGuard(regionKeyOf(key))) {
			if (hot.get(key) != s) {
				return false; // swapped since selection
			}
			int version = s.copyCellsInto(scratch);
			int nonAir = s.nonAirCount();
			if (s.isDirty()) {
				// Re-dirtied after STEP 1 drained it (or STEP 1 could not save it):
				// keep HOT, ensure it is queued for the next save.
				hot.markDirty(key);
				return false;
			}
			if (nonAir == 0) {
				// Clean and empty: STEP 1 already wrote its removal row; just drop it.
				if (hot.removeResident(key, s)) {
					s.recycle();
				}
				WarmEntry w0 = warm.get(key);
				if (w0 != null) {
					dropWarm(key, w0);
				}
				return true;
			}
			long[] mask = new long[s.populationMaskLength()];
			s.copyPopulationMaskInto(mask);
			byte[] payload = VoxelSectionCodec.encodeCells(scratch);
			WarmEntry entry = new WarmEntry(nonAir, mask, payload, s.lastTouchedNanos());
			if (hot.removeResident(key, s)) {
				addWarm(key, entry);
				s.recycle();
				return true;
			}
			// version is only meaningful with the isDirty()==false guarantee above,
			// which already establishes the snapshot is the committed-on-disk state.
			return false;
		}
	}

	/**
	 * Two-phase WARM eviction (design section 5.2): phase 1 drops sections
	 * beyond their level's ring x 1.5 (normalized so far L0 goes first, L4
	 * almost never); phase 2 is plain LRU to the byte budget, with L3/L4 exempt
	 * until the budget is exceeded by 10 %. All warm rows are already on disk, so
	 * every drop is a pure RAM reclaim.
	 */
	private void evictWarm(int camBlockX, int camBlockZ) {
		long budget = (long) HorizonConfig.get().getVoxelMemoryBudgetMb() * 1024L * 1024L;
		if (warmBytes.get() <= budget) {
			return;
		}

		int[] phase1Radius = phase1KeepRadiiSections();
		for (Map.Entry<Long, WarmEntry> e : warm.entrySet()) {
			long key = e.getKey();
			int level = SectionKey.level(key);
			int camSecX = camBlockX >> (VoxelConstants.SECTION_BITS + level);
			int camSecZ = camBlockZ >> (VoxelConstants.SECTION_BITS + level);
			int dist = Math.max(Math.abs(SectionKey.x(key) - camSecX), Math.abs(SectionKey.z(key) - camSecZ));
			if (dist > phase1Radius[level]) {
				dropWarm(key, e.getValue());
			}
		}
		if (warmBytes.get() <= budget) {
			return;
		}

		evictWarmLru(budget, false);
		long hardCap = budget + budget / 10;
		if (warmBytes.get() > hardCap) {
			evictWarmLru(hardCap, true);
		}
	}

	/**
	 * Evicts warm rows in touch-clock order until under {@code target}.
	 * {@code includeHighLevels == false} exempts L3/L4 (few, huge-coverage, most
	 * expensive to refault visually); the second pass includes them once the
	 * budget is blown past the hard cap.
	 */
	private void evictWarmLru(long target, boolean includeHighLevels) {
		if (warmBytes.get() <= target) {
			return;
		}
		List<Map.Entry<Long, WarmEntry>> entries = new ArrayList<>(warm.entrySet());
		entries.sort(Comparator.comparingLong(e -> e.getValue().lastTouchedNanos));
		for (Map.Entry<Long, WarmEntry> e : entries) {
			if (warmBytes.get() <= target) {
				break;
			}
			if (!includeHighLevels && SectionKey.level(e.getKey()) >= 3) {
				continue;
			}
			dropWarm(e.getKey(), e.getValue());
		}
	}

	/**
	 * Per-level phase-1 keep radius in sections at that level: the ring outer
	 * edge (design section 5.1) times 1.5, plus a one-section cushion. Mirrors
	 * {@code VoxelEngine.keepRadiiSections}; recomputed each sweep so config
	 * changes apply live.
	 */
	private static int[] phase1KeepRadiiSections() {
		int ringWidth = HorizonConfig.get().getLodRingWidth();
		int maxDistance = HorizonConfig.get().getLodDistanceBlocks();
		int[] radii = new int[VoxelConstants.LEVEL_COUNT];
		for (int level = 0; level < radii.length; level++) {
			int outerBlocks = level == VoxelConstants.MAX_LEVEL
				? maxDistance
				: Math.min(maxDistance, ringWidth << level);
			int spanBlocks = SectionKey.sectionSpanBlocks(level);
			radii[level] = (int) ((long) outerBlocks * 3 / 2 / spanBlocks) + 1;
		}
		return radii;
	}

	// --- Unload flush --------------------------------------------------------

	/**
	 * Palette-only flush. {@code VoxelEngine.onLevelUnload} calls this
	 * synchronously on the client thread so the SHARED palette is durably written
	 * to THIS store's captured path before a later world switch can clear()+load()
	 * it for a different world. Kept out of {@link #flushAll} (which runs on a
	 * worker, after a possible world switch) precisely so the async path can never
	 * read the mutated palette and write another world's mapping into this file.
	 */
	void flushPalette() {
		savePalette();
	}

	/**
	 * Level-unload teardown: save every dirty HOT section (double-retry, like
	 * {@code HorizonLod.onLevelUnload}), then drop both tiers. The palette is
	 * flushed FIRST and separately by {@link #flushPalette} on the client thread
	 * (see its javadoc), so this method deliberately does not touch it — thus the
	 * palette-first invariant still holds cycle-wide. Arrays are not recycled here
	 * — at unload in-flight ingest jobs may still hold section references
	 * anywhere, and handing their arrays to the pool could let a straggler write
	 * corrupt an unrelated section; dropping the object graph to GC is
	 * unconditionally safe (mirrors {@code VoxelWorld.clearAll}). Worker thread.
	 */
	void flushAll() {
		long[] scratch = new long[VoxelConstants.SECTION_CELLS];
		flushDirtyWithRetry(scratch);
		warm.clear();
		warmBytes.set(0);
		loadedRegions.clear();
		regionGuards.clear();
		hot.clearAll();
	}

	private void flushDirtyWithRetry(long[] scratch) {
		Set<Long> dirty = hot.drainDirtySections();
		if (dirty.isEmpty()) {
			return;
		}
		Map<Long, RegionBatch> batches = buildBatches(dirty, scratch);
		for (RegionBatch batch : batches.values()) {
			if (!disk.saveRegionRows(batch.level, batch.rx, batch.rz, batch.rows)
				&& !disk.saveRegionRows(batch.level, batch.rx, batch.rz, batch.rows)) {
				Iris.logger.error("Horizon: lost voxel LOD data for L" + batch.level + " storage region "
					+ batch.rx + "," + batch.rz + " (save failed twice on unload)");
			}
		}
	}

	// --- WARM byte accounting ------------------------------------------------

	private void addWarm(long key, WarmEntry entry) {
		WarmEntry prev = warm.put(key, entry);
		warmBytes.addAndGet(entry.bytes - (prev == null ? 0 : prev.bytes));
	}

	/** Promotion take: the entry leaves WARM to become HOT, so the region stays "loaded". */
	private WarmEntry takeWarm(long key) {
		WarmEntry prev = warm.remove(key);
		if (prev != null) {
			warmBytes.addAndGet(-prev.bytes);
		}
		return prev;
	}

	/** True drop (eviction / removal): the row is gone from RAM, so its region must be re-faultable. */
	private void dropWarm(long key, WarmEntry expected) {
		if (warm.remove(key, expected)) {
			warmBytes.addAndGet(-expected.bytes);
			loadedRegions.remove(regionKeyOf(key));
		}
	}

	// --- Region-monitor keys -------------------------------------------------

	private Object regionGuard(long regionKey) {
		return regionGuards.computeIfAbsent(regionKey, k -> new Object());
	}

	private static long regionKeyOf(long sectionKey) {
		int level = SectionKey.level(sectionKey);
		int rx = SectionKey.x(sectionKey) >> VoxelConstants.STORAGE_REGION_BITS;
		int rz = SectionKey.z(sectionKey) >> VoxelConstants.STORAGE_REGION_BITS;
		long lx = ((long) rx + REGION_COORD_BIAS) & REGION_COORD_MASK;
		long lz = ((long) rz + REGION_COORD_BIAS) & REGION_COORD_MASK;
		return ((long) (level & 0xF) << 46) | (lx << 23) | lz;
	}

	// --- Carriers ------------------------------------------------------------

	/**
	 * One WARM row: the codec payload plus the framed census/mask needed to
	 * re-frame it on save without re-inflating, and a touch clock for LRU.
	 * Immutable — a warm entry is never mutated in place; a re-save replaces it.
	 */
	private static final class WarmEntry {
		final int nonAirCount;
		final long[] populationMask;
		final byte[] payload;
		final long lastTouchedNanos;
		final int bytes;

		WarmEntry(int nonAirCount, long[] populationMask, byte[] payload, long lastTouchedNanos) {
			this.nonAirCount = nonAirCount;
			this.populationMask = populationMask;
			this.payload = payload;
			this.lastTouchedNanos = lastTouchedNanos;
			this.bytes = payload.length + populationMask.length * Long.BYTES + WARM_ENTRY_OVERHEAD;
		}
	}

	/** Rows destined for one storage region, plus the parallel dirty-flag bookkeeping. */
	private static final class RegionBatch {
		final int level;
		final int rx;
		final int rz;
		final List<VoxelRegionStorage.DirtyRow> rows = new ArrayList<>();
		final List<Pending> pending = new ArrayList<>();

		RegionBatch(int level, int rx, int rz) {
			this.level = level;
			this.rx = rx;
			this.rz = rz;
		}
	}

	/** A section awaiting its region commit, so the dirty flag is reconciled only after the write. */
	private record Pending(long key, VoxelSection section, int version, boolean removal) {
	}
}
