package net.irisshaders.iris.horizon.voxel;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Bounded free-list of {@code long[32768]} cell arrays (256 KB each).
 *
 * <p>WHY pool at all: sections are mid-lifetime objects — they sit in the
 * residency map for seconds to minutes, which guarantees promotion to the
 * old generation. At elytra speed (~30 new L0 sections/s) plain allocation
 * would create ~7.5 MB/s of old-gen churn: G1 mixed-collection pressure and
 * heap-growth spikes, exactly the frame-hitch class this mod's recent
 * commits fight. Reuse costs nothing — an {@code Arrays.fill} (~8 us
 * vectorized) equals the JVM's own zeroing of a fresh array — and caps
 * steady-state allocation at zero.
 *
 * <p>WHY bounded: so the pool can never become a leak. At most
 * {@link #MAX_POOLED} arrays (16 MB) are retained; releasing into a full
 * pool drops the array to GC, acquiring from an empty pool plainly
 * allocates. LIFO order (addFirst/pollFirst) hands back the most recently
 * used array, whose pages are most likely still cache- and TLB-warm.
 */
final class SectionPool {
	static final int MAX_POOLED = 64;

	private static final ArrayDeque<long[]> POOL = new ArrayDeque<>(MAX_POOLED);

	private SectionPool() {
	}

	/**
	 * Returns an all-zero cell array — zeroed means all-air/unlit, a valid
	 * section body with no further initialization (see {@link VoxelCell}).
	 * The fill of a recycled array runs <em>outside</em> the lock so
	 * concurrent workers only contend on the queue poll, never on the fill;
	 * the synchronized hand-off also gives the release-to-acquire
	 * happens-before edge, so no stale contents are ever observable.
	 */
	static long[] acquire() {
		long[] recycled;
		synchronized (POOL) {
			recycled = POOL.pollFirst();
		}
		if (recycled == null) {
			return new long[VoxelConstants.SECTION_CELLS];
		}
		Arrays.fill(recycled, 0L);
		return recycled;
	}

	/**
	 * Offers an array back to the pool. The caller must have dropped every
	 * reference first — the array will be handed to an unrelated section
	 * (VoxelSection.recycle nulls its field under the monitor for exactly
	 * this reason). Wrong-length arrays are rejected defensively rather
	 * than poisoning every future acquire.
	 */
	static void release(long[] cells) {
		if (cells == null || cells.length != VoxelConstants.SECTION_CELLS) {
			return;
		}
		synchronized (POOL) {
			if (POOL.size() < MAX_POOLED) {
				POOL.addFirst(cells);
			}
		}
	}

	/** Currently retained arrays; debug/F3 counters only. */
	static int pooledCount() {
		synchronized (POOL) {
			return POOL.size();
		}
	}
}
