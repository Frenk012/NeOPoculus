package net.irisshaders.iris.horizon.voxel.model;

import net.irisshaders.iris.horizon.voxel.VoxelConstants;

import java.util.Arrays;

/**
 * Per-blockstate, per-face atlas slot for the voxel LOD (design-bakery-atlas.md
 * §2.2). The mesher (worker threads) reads {@link #slotOf}; the bakery (render
 * thread) writes via {@link #setBaked}. Arrays are grown under a lock and
 * republished through a volatile field, so id-indexed reads never lock.
 *
 * <p>Slot 0 means "not baked yet" — the mesher emits the flat MapColor and
 * requests a bake, and the shader falls back to the vertex color.
 */
public final class StateMetadataTable {
	private static final int FACES = VoxelConstants.FACE_COUNT;

	private volatile short[] slots;   // stateId*6 + face -> atlas slot (0 = none)
	private volatile boolean[] baked; // stateId -> a bake was attempted (success or fallback)
	private final Object grow = new Object();

	public StateMetadataTable() {
		slots = new short[1024 * FACES];
		baked = new boolean[1024];
	}

	/** Atlas slot for a state's face, or 0 if not baked yet. Any thread. */
	public int slotOf(int stateId, int face) {
		short[] s = slots;
		int i = stateId * FACES + face;
		return (stateId >= 0 && i < s.length) ? (s[i] & 0xFFFF) : 0;
	}

	/** Whether a bake has been attempted for this state (so the mesher stops re-requesting). Any thread. */
	public boolean isBaked(int stateId) {
		boolean[] b = baked;
		return stateId >= 0 && stateId < b.length && b[stateId];
	}

	/** Publishes the six baked face slots for a state and marks it baked. Render thread. */
	public void setBaked(int stateId, int[] faceSlots) {
		ensureCapacity(stateId);
		short[] s = slots;
		int base = stateId * FACES;
		for (int f = 0; f < FACES; f++) {
			s[base + f] = (short) faceSlots[f];
		}
		baked[stateId] = true;
	}

	private void ensureCapacity(int stateId) {
		if (stateId < baked.length) {
			return;
		}
		synchronized (grow) {
			if (stateId < baked.length) {
				return;
			}
			int newStates = Integer.highestOneBit(stateId) << 1;
			slots = Arrays.copyOf(slots, newStates * FACES);
			baked = Arrays.copyOf(baked, newStates);
		}
	}

	public void clear() {
		synchronized (grow) {
			Arrays.fill(slots, (short) 0);
			Arrays.fill(baked, false);
		}
	}
}
