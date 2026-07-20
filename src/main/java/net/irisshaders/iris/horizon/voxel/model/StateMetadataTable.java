package net.irisshaders.iris.horizon.voxel.model;

import net.irisshaders.iris.horizon.voxel.VoxelConstants;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-blockstate atlas slots for the voxel LOD (design-bakery-atlas.md §2.2),
 * split into two tiers so biome tint is correct without baking every block six
 * times for every biome:
 *
 * <ul>
 *   <li><b>Untinted</b> states (stone, dirt, planks, logs — the overwhelming
 *   majority) are baked once; their slots live in the flat {@code baseSlots}
 *   array and serve every biome.</li>
 *   <li><b>Tinted</b> states (grass, foliage, water) are baked once per biome
 *   that appears; their slots live in {@code tintedSlots}, keyed by
 *   {@code (stateId, biomeId)}, so a swamp's murky grass and a plains' bright
 *   grass get different photos.</li>
 * </ul>
 *
 * <p>Which tier a state uses is discovered by its first bake (a state's model
 * tint-ness is fixed) and recorded in {@code tinted}. The mesher (worker
 * threads) reads {@link #slotOf}/{@link #isBaked}; the bakery (render thread)
 * writes via {@link #setBaked}. The arrays grow under a lock and republish
 * through volatile fields, so id-indexed reads never lock; a momentarily stale
 * read just falls back to flat color for one frame and self-heals on the next
 * remesh.
 */
public final class StateMetadataTable {
	private static final int FACES = VoxelConstants.FACE_COUNT;
	private static final int BIOME_BITS = VoxelConstants.BIOME_BITS;
	private static final int BIOME_MASK = VoxelConstants.MAX_BIOME_IDS - 1;

	private volatile short[] baseSlots;   // untinted: stateId*6 + face -> slot (0 = none)
	private volatile boolean[] baseBaked; // untinted: stateId -> baked, serves all biomes
	private volatile boolean[] tinted;    // stateId -> baked and biome-dependent
	/** Tinted states only: (stateId,biomeId) -> six face slots. */
	private final ConcurrentHashMap<Integer, short[]> tintedSlots = new ConcurrentHashMap<>();
	private final Object grow = new Object();

	public StateMetadataTable() {
		baseSlots = new short[1024 * FACES];
		baseBaked = new boolean[1024];
		tinted = new boolean[1024];
	}

	private static int key(int stateId, int biomeId) {
		return (stateId << BIOME_BITS) | (biomeId & BIOME_MASK);
	}

	/** Atlas slot for a state's face in a biome, or 0 if not baked yet. Any thread. */
	public int slotOf(int stateId, int biomeId, int face) {
		if (stateId < 0) {
			return 0;
		}
		boolean[] t = tinted;
		if (stateId < t.length && t[stateId]) {
			short[] s = tintedSlots.get(key(stateId, biomeId));
			return s == null ? 0 : (s[face] & 0xFFFF);
		}
		short[] bs = baseSlots;
		int i = stateId * FACES + face;
		return i < bs.length ? (bs[i] & 0xFFFF) : 0;
	}

	/** Whether a bake has been attempted for this state+biome (so the mesher stops re-requesting). Any thread. */
	public boolean isBaked(int stateId, int biomeId) {
		if (stateId < 0) {
			return false;
		}
		boolean[] bb = baseBaked;
		if (stateId < bb.length && bb[stateId]) {
			return true; // untinted: one bake serves every biome
		}
		boolean[] t = tinted;
		if (stateId < t.length && t[stateId]) {
			return tintedSlots.containsKey(key(stateId, biomeId));
		}
		return false;
	}

	/**
	 * Publishes a state's six baked face slots and marks it baked. Render thread.
	 * When {@code isTinted}, the slots are stored per biome; otherwise they go in
	 * the shared untinted tier and serve every biome. For tinted states the slot
	 * map entry is written before the {@code tinted} flag flips, so a concurrent
	 * reader never sees "tinted but no slots" as anything worse than a one-frame
	 * flat-color fallback.
	 */
	public void setBaked(int stateId, int biomeId, boolean isTinted, int[] faceSlots) {
		ensureCapacity(stateId);
		if (isTinted) {
			short[] s = new short[FACES];
			for (int f = 0; f < FACES; f++) {
				s[f] = (short) faceSlots[f];
			}
			tintedSlots.put(key(stateId, biomeId), s);
			tinted[stateId] = true;
		} else {
			short[] bs = baseSlots;
			int base = stateId * FACES;
			for (int f = 0; f < FACES; f++) {
				bs[base + f] = (short) faceSlots[f];
			}
			baseBaked[stateId] = true;
		}
	}

	private void ensureCapacity(int stateId) {
		if (stateId < baseBaked.length) {
			return;
		}
		synchronized (grow) {
			if (stateId < baseBaked.length) {
				return;
			}
			int newStates = Integer.highestOneBit(stateId) << 1;
			baseSlots = Arrays.copyOf(baseSlots, newStates * FACES);
			baseBaked = Arrays.copyOf(baseBaked, newStates);
			tinted = Arrays.copyOf(tinted, newStates);
		}
	}

	public void clear() {
		synchronized (grow) {
			Arrays.fill(baseSlots, (short) 0);
			Arrays.fill(baseBaked, false);
			Arrays.fill(tinted, false);
			tintedSlots.clear();
		}
	}
}
