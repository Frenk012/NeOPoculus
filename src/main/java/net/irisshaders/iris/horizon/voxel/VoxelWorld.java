package net.irisshaders.iris.horizon.voxel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory section residency for one dimension of the voxel LOD engine.
 * In M1 this is the whole storage story; M2 layers the two-tier VoxelStore
 * (HOT/WARM/disk) on top, consuming the residency map and the dirty set
 * exposed here without changing the write paths.
 *
 * <p>Thread-safe the same way {@code LodWorld} is: written by ingest
 * workers, swept by the eviction worker, counted from the client thread for
 * the F3 line.
 */
public final class VoxelWorld {
	/**
	 * The single residency map (design-data-storage.md section 1.4): one map
	 * for all five levels, keyed by packed {@link SectionKey}. WHY not five
	 * per-level maps: the level lives in the key so lookups stay uniform,
	 * and the only per-level operations (the eviction sweep, the F3
	 * counters) switch on {@code SectionKey.level}, which is cheaper than
	 * keeping five maps coherent under concurrent creation.
	 */
	private final ConcurrentHashMap<Long, VoxelSection> sections = new ConcurrentHashMap<>(1024);

	/**
	 * Section keys with unsaved changes, populated by the ingest paths. M1
	 * only accumulates and drops these (there is no disk yet); M2's save
	 * cycle drains them exactly like {@code LodWorld.drainDirtyRegions} —
	 * the seam is kept identical on purpose.
	 */
	private final Set<Long> dirtySections = ConcurrentHashMap.newKeySet();

	/**
	 * Per-level resident-section counts for the F3 debug line. LongAdder
	 * because ingest workers create sections concurrently and the counter
	 * write must never contend; the read (sum) happens once per debug frame
	 * and is allowed to be weakly consistent.
	 */
	private final LongAdder[] sectionsPerLevel = new LongAdder[VoxelConstants.LEVEL_COUNT];

	public VoxelWorld() {
		for (int level = 0; level < sectionsPerLevel.length; level++) {
			sectionsPerLevel[level] = new LongAdder();
		}
	}

	/** Resident section for a packed key, or null. Any thread. */
	public VoxelSection get(long key) {
		return sections.get(key);
	}

	/**
	 * Resident section for a key, creating an all-air one (pooled, zeroed
	 * array) if absent — the ingest merge path. The pre-check keeps the
	 * common hit a plain lock-free get; only genuine creation pays the
	 * computeIfAbsent bucket lock (which also makes the counter increment
	 * exactly-once).
	 */
	public VoxelSection acquireForWrite(long key) {
		VoxelSection existing = sections.get(key);
		if (existing != null) {
			return existing;
		}
		return sections.computeIfAbsent(key, k -> {
			sectionsPerLevel[SectionKey.level(k)].increment();
			return new VoxelSection(k);
		});
	}

	/** Flags a section as having unsaved changes; ingest calls this after every write batch. */
	public void markDirty(long key) {
		dirtySections.add(key);
	}

	/**
	 * Takes the current dirty set, leaving concurrently-added keys behind
	 * for the next drain — same contract as {@code LodWorld.drainDirtyRegions}.
	 * M2's save cycle is the intended consumer; M1 has none.
	 */
	public Set<Long> drainDirtySections() {
		Set<Long> copy = new HashSet<>(dirtySections);
		dirtySections.removeAll(copy);
		return copy;
	}

	/** Resident sections at one level; the F3 counters. Weakly consistent. */
	public int sectionCount(int level) {
		return (int) sectionsPerLevel[level].sum();
	}

	/** Total resident sections across all levels. */
	public int totalSections() {
		return sections.size();
	}

	/**
	 * Drops every section whose Chebyshev distance from the camera (in
	 * sections at its own level) exceeds that level's keep radius, returning
	 * the count evicted. Mirrors {@code LodWorld.evictOutside} in shape, with
	 * two deliberate M1 differences:
	 *
	 * <ul>
	 * <li>Dirty sections are NOT spared. With no disk tier every section is
	 * permanently dirty, so sparing them would disable eviction entirely;
	 * evicted terrain is simply recaptured on re-approach. M2 restores the
	 * classic save-then-drop invariant when there is somewhere to save to.</li>
	 * <li>Evicted cell arrays go back to {@link SectionPool} via
	 * {@code recycle()}. The two-arg remove keeps that safe against a racing
	 * re-create; a worker still holding the section reference would fail
	 * fast (NPE, caught by the worker wrappers) rather than corrupt a pooled
	 * array — and in practice never does, because ingest only touches
	 * sections near the player, far inside every keep radius.</li>
	 * </ul>
	 *
	 * Runs on a worker; the values() iteration is weakly consistent, which
	 * is fine — a section missed this sweep is caught by the next.
	 *
	 * <p>{@code maxResidentSections} is a hard memory ceiling enforced after
	 * the radius pass: in M1 there is no warm packing tier, so every resident
	 * section is a raw 256 KB {@code long[]} and the keep radii (sized for the
	 * M2 warm-tier 1.5x-ring formula) would otherwise let residency grow into
	 * the tens of GB. When the radius pass leaves more than the cap resident,
	 * the least-recently-touched sections are dropped until under it — a
	 * global LRU, level-agnostic, matching the design's HOT-tier policy.
	 */
	public int evictOutside(int camBlockX, int camBlockZ, int[] keepRadiusSectionsByLevel, int maxResidentSections) {
		int evicted = 0;
		for (VoxelSection section : sections.values()) {
			long key = section.key;
			int level = SectionKey.level(key);
			int camSecX = camBlockX >> (VoxelConstants.SECTION_BITS + level);
			int camSecZ = camBlockZ >> (VoxelConstants.SECTION_BITS + level);
			int dist = Math.max(Math.abs(SectionKey.x(key) - camSecX), Math.abs(SectionKey.z(key) - camSecZ));
			if (dist <= keepRadiusSectionsByLevel[level]) {
				continue;
			}
			if (evictSection(section)) {
				evicted++;
			}
		}
		return evicted + evictToCap(maxResidentSections);
	}

	/**
	 * Drops the least-recently-touched sections until residency is at or below
	 * {@code maxResidentSections}, returning the count evicted. The
	 * {@code lastTouchedNanos} clock is bumped by every write and bulk read, so
	 * the oldest sections are the coldest — furthest from the camera's activity
	 * — exactly the M2 HOT-tier global-LRU choice.
	 */
	private int evictToCap(int maxResidentSections) {
		int over = sections.size() - maxResidentSections;
		if (over <= 0) {
			return 0;
		}
		ArrayList<VoxelSection> all = new ArrayList<>(sections.values());
		all.sort(Comparator.comparingLong(VoxelSection::lastTouchedNanos));
		int evicted = 0;
		for (VoxelSection section : all) {
			if (evicted >= over) {
				break;
			}
			if (evictSection(section)) {
				evicted++;
			}
		}
		return evicted;
	}

	/**
	 * Removes one section from residency and recycles its array. The two-arg
	 * remove keeps this safe against a racing re-create; a worker still holding
	 * the reference fails fast (NPE, caught by the worker wrappers) rather than
	 * corrupting a pooled array.
	 */
	private boolean evictSection(VoxelSection section) {
		long key = section.key;
		if (sections.remove(key, section)) {
			sectionsPerLevel[SectionKey.level(key)].decrement();
			dirtySections.remove(key);
			section.recycle();
			return true;
		}
		return false;
	}

	/**
	 * Level-unload reset: forgets every section WITHOUT recycling their
	 * arrays. WHY no recycle here, unlike the eviction sweep: at unload time
	 * in-flight ingest jobs may still hold section references anywhere in
	 * the world, and handing their arrays to the pool while a straggler
	 * could still write would let two sections silently share one array.
	 * Dropping the whole object graph to GC is unconditionally safe; the
	 * pool refills within seconds of the next world's capture.
	 */
	public void clearAll() {
		sections.clear();
		dirtySections.clear();
		for (LongAdder adder : sectionsPerLevel) {
			adder.reset();
		}
	}
}
