package net.irisshaders.iris.horizon.voxel;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mip propagation (design-data-storage.md section 4): the representative
 * selection shared with {@link ChunkPyramid}'s bulk path, and the debounced
 * incremental remip that keeps L1..L4 exact after sparse block updates.
 *
 * <p>One instance per {@code VoxelWorld} (DESIGN.md section 2 puts the remip
 * queue with the per-dimension world state): binding the queue to the world
 * it feeds means a dimension switch drops both together via {@link #clear()}
 * and no stale keys can ever cross dimensions.
 *
 * <p>Mechanics: a parent cell's 8 children are always 2x2x2 adjacent cells
 * within one child-level section (child coords 2i, 2i+1 stay inside [0,31]),
 * so a remip reads one section and writes one cell of its
 * {@code SectionKey.parentOf} — never a cross-section gather. Dirty cells
 * batch per section; {@code VoxelEngine} debounces by
 * {@link VoxelConstants#MIP_DEBOUNCE_TICKS} before kicking a pass.
 *
 * <p>Thread model: {@link #enqueueRemip} from any thread;
 * {@link #processRemips} from a worker. Levels are processed ascending, so
 * an L0 change ripples to L4 within a single pass while budget lasts.
 */
final class VoxelMipper {
	/** State + biome bits: cell identity for ranking, light excluded (averaged separately). */
	private static final long IDENTITY_MASK = VoxelConstants.STATE_MASK | VoxelConstants.BIOME_MASK;

	/**
	 * M2: the mipper faults through the store so an incremental remip can write
	 * a parent cell into a section that was packed down to WARM (or evicted to
	 * disk) since it was last meshed — otherwise a block edit's mip would be
	 * silently dropped for any non-HOT parent. Child reads stay HOT-only:
	 * remips are only ever enqueued for HOT children (bulk ingest writes all
	 * levels directly and never enqueues), so a child that left HOT is stale by
	 * definition and correctly skipped.
	 */
	private final VoxelStore store;
	private final VoxelPalettes palettes;

	/**
	 * Dirty child cells per section key. The {@code drained} flag closes the
	 * enqueue/drain race: a producer that fetched a set the worker is about
	 * to discard sees the flag under the set's own monitor and retries
	 * against a fresh map entry — no update can vanish between remove and
	 * drain.
	 */
	private final ConcurrentHashMap<Long, CellSet> remipQueue = new ConcurrentHashMap<>();

	private static final class CellSet {
		final IntOpenHashSet cells = new IntOpenHashSet();
		boolean drained;
	}

	VoxelMipper(VoxelStore store, VoxelPalettes palettes) {
		this.store = store;
		this.palettes = palettes;
	}

	/**
	 * Records that a cell of a section changed and its parent cell needs
	 * recomputing. Any thread. Callers only enqueue levels below
	 * {@link VoxelConstants#MAX_LEVEL} (an L4 cell has no parent).
	 */
	void enqueueRemip(long sectionKey, int cellIdx) {
		while (true) {
			CellSet set = remipQueue.computeIfAbsent(sectionKey, k -> new CellSet());
			synchronized (set) {
				if (!set.drained) {
					set.cells.add(cellIdx);
					return;
				}
			}
			// The worker drained this set between our map read and lock;
			// its map entry is already gone, so the retry creates a new one.
		}
	}

	/** Sections with pending remips; drives the engine's debounce/kick decision. */
	int pendingSections() {
		return remipQueue.size();
	}

	/** Drops all pending work; level unload only (paired with the world reset). */
	void clear() {
		remipQueue.clear();
	}

	/**
	 * Processes up to {@code maxSections} dirty sections, levels ascending
	 * so same-pass cascades reach L4. Returns the keys of parent sections
	 * whose cells actually changed — the caller forwards them to the mesh
	 * dirty tracker exactly like {@code VoxelIngest.IngestResult} touches.
	 *
	 * <p>A child section evicted between enqueue and processing is simply
	 * skipped: its whole subtree left residency and will be re-ingested
	 * (bulk path, all levels) on re-approach, so nothing is stale.
	 */
	LongSet processRemips(int maxSections) {
		LongOpenHashSet touched = new LongOpenHashSet();
		long[] children = new long[8];
		int processed = 0;

		outer:
		for (int level = 0; level < VoxelConstants.MAX_LEVEL; level++) {
			// Weakly-consistent iteration is fine: keys enqueued during the
			// pass at higher levels are seen either now or next pass; both
			// are correct, the latter merely a one-pass delay.
			Iterator<Long> it = remipQueue.keySet().iterator();
			while (it.hasNext()) {
				long key = it.next();
				if (SectionKey.level(key) != level) {
					continue;
				}
				CellSet set = remipQueue.remove(key);
				if (set == null) {
					continue; // another pass raced it away
				}
				int[] cells;
				synchronized (set) {
					set.drained = true;
					cells = set.cells.toIntArray();
				}
				remipSection(key, cells, children, touched);
				if (++processed >= maxSections) {
					break outer;
				}
			}
		}
		return touched;
	}

	/**
	 * Recomputes the parent cells covering the given dirty child cells.
	 * Multiple dirty children collapsing onto one parent cell are deduped;
	 * an unchanged parent cell stops the upward cascade, which is what keeps
	 * a torch toggle from remipping four levels of stone.
	 */
	private void remipSection(long childKey, int[] cellIdxs, long[] children, LongOpenHashSet touched) {
		VoxelSection child = store.hot().get(childKey);
		if (child == null) {
			return;
		}
		long parentKey = SectionKey.parentOf(childKey);
		VoxelSection parent = store.acquireForWrite(parentKey);
		// Parent-local base of this child's octant: child section coord
		// parity selects which 16-cell half of the parent it maps into.
		int ox = (SectionKey.x(childKey) & 1) << 4;
		int oy = (SectionKey.y(childKey) & 1) << 4;
		int oz = (SectionKey.z(childKey) & 1) << 4;
		boolean parentIsLast = SectionKey.level(parentKey) >= VoxelConstants.MAX_LEVEL;

		IntOpenHashSet doneParentCells = new IntOpenHashSet(cellIdxs.length);
		boolean changed = false;
		for (int idx : cellIdxs) {
			int x = idx & VoxelConstants.SECTION_MASK;
			int z = (idx >>> VoxelConstants.SECTION_BITS) & VoxelConstants.SECTION_MASK;
			int y = idx >>> (2 * VoxelConstants.SECTION_BITS);
			int px = ox | (x >> 1);
			int py = oy | (y >> 1);
			int pz = oz | (z >> 1);
			int parentIdx = VoxelSection.cellIndex(px, py, pz);
			if (!doneParentCells.add(parentIdx)) {
				continue;
			}
			// Gather the 8 children in the shared (dy<<2)|(dz<<1)|dx order —
			// must match ChunkPyramid.mip for a deterministic index tiebreak.
			int cx = x & ~1;
			int cy = y & ~1;
			int cz = z & ~1;
			for (int dy = 0; dy <= 1; dy++) {
				for (int dz = 0; dz <= 1; dz++) {
					children[(dy << 2) | (dz << 1)] = child.cellAt(cx, cy + dy, cz + dz);
					children[(dy << 2) | (dz << 1) | 1] = child.cellAt(cx + 1, cy + dy, cz + dz);
				}
			}
			long next = selectRepresentative(children, palettes);
			if (parent.cellAt(px, py, pz) != next) {
				parent.writeCell(parentIdx, next);
				changed = true;
				if (!parentIsLast) {
					enqueueRemip(parentKey, parentIdx);
				}
			}
		}
		if (changed) {
			touched.add(parentKey);
			// The parent cell(s) changed but writeCell only flips the per-section
			// dirty flag; enrol the key in the world dirty set too so the M2 save
			// cycle actually persists this remip (bulk ingest enrols its own
			// writes the same way).
			store.markDirty(parentKey);
		}
	}

	/**
	 * The one representative-selection rule (design section 4), shared by
	 * the bulk pyramid and the incremental path so they can never disagree:
	 * identity (state + biome, light excluded) from non-air children only,
	 * ranked by opacity DESC, then occurrence-count-among-8 DESC, then
	 * lowest child index — deterministic and mode-biased, so a 7-stone/
	 * 1-torch group stays stone. All-air children yield an air cell.
	 *
	 * <p>Light is averaged over all 8 children including air: block light
	 * floors (a lone glowstone should not brighten a whole parent cell),
	 * sky light ceils (rounding down would edge-darken distant terrain).
	 */
	static long selectRepresentative(long[] children8, VoxelPalettes palettes) {
		int blockSum = 0;
		int skySum = 0;
		for (int i = 0; i < 8; i++) {
			blockSum += VoxelCell.blockLight(children8[i]);
			skySum += VoxelCell.skyLight(children8[i]);
		}
		int blockAvg = blockSum >> 3;
		int skyAvg = (skySum + 7) >> 3;

		long bestIdentity = 0L;
		int bestOpacity = -1;
		int bestCount = 0;
		for (int i = 0; i < 8; i++) {
			long identity = children8[i] & IDENTITY_MASK;
			if (VoxelCell.isAir(identity)) {
				continue;
			}
			// Count occurrences; skip identities already ranked at a lower
			// index (first occurrence wins the index tiebreak by iteration
			// order, so strict comparisons below suffice).
			int count = 0;
			boolean seenEarlier = false;
			for (int j = 0; j < 8; j++) {
				if ((children8[j] & IDENTITY_MASK) == identity) {
					if (j < i) {
						seenEarlier = true;
						break;
					}
					count++;
				}
			}
			if (seenEarlier) {
				continue;
			}
			int opacity = palettes.opacityOf(VoxelCell.stateId(identity));
			if (opacity > bestOpacity || (opacity == bestOpacity && count > bestCount)) {
				bestOpacity = opacity;
				bestCount = count;
				bestIdentity = identity;
			}
		}
		return VoxelCell.withLight(bestIdentity, blockAvg, skyAvg);
	}
}
