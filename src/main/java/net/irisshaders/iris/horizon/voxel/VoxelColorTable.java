package net.irisshaders.iris.horizon.voxel;

import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lazy per-state-id flat color for the M3 render path: the block's vanilla
 * {@link MapColor} as a packed {@code 0xRRGGBB} int, mirroring the classic
 * engine's MapColor fallback. Throwaway by design — M4 replaces flat color
 * with the photo atlas — so it lives beside the mesher rather than bloating
 * {@link VoxelPalettes}.
 *
 * <p>Thread-safe: the cache array is published through a volatile field and
 * grown under a lock; a miss recomputes (idempotent), so concurrent mesher
 * workers never corrupt it and never block on the common hit.
 */
public final class VoxelColorTable {
	private static final int SENTINEL = 0x8000_0000; // "not computed" (never a valid 0xRRGGBB)
	private static final int FALLBACK = 0x7F7F7F;    // neutral grey, matching LodColors' FALLBACK spirit

	private final VoxelPalettes palettes;
	private volatile int[] colors;

	public VoxelColorTable(VoxelPalettes palettes) {
		this.palettes = palettes;
		int[] init = new int[1024];
		java.util.Arrays.fill(init, SENTINEL);
		this.colors = init;
	}

	/** {@code 0xRRGGBB} flat color for a state id; air is never queried (the mesher skips it). */
	public int colorOf(int stateId) {
		int[] arr = colors;
		if (stateId >= 0 && stateId < arr.length) {
			int c = arr[stateId];
			if (c != SENTINEL) {
				return c;
			}
		}
		return compute(stateId);
	}

	private synchronized int compute(int stateId) {
		if (stateId < 0) {
			return FALLBACK;
		}
		int[] arr = colors;
		if (stateId >= arr.length) {
			int newLen = arr.length;
			while (newLen <= stateId) {
				newLen <<= 1;
			}
			int[] grown = java.util.Arrays.copyOf(arr, newLen);
			java.util.Arrays.fill(grown, arr.length, newLen, SENTINEL);
			arr = grown;
			colors = grown;
		}
		if (arr[stateId] != SENTINEL) {
			return arr[stateId];
		}
		int rgb = FALLBACK;
		try {
			BlockState state = palettes.stateOf(stateId);
			if (state != null) {
				MapColor mc = state.getMapColor(null, null);
				rgb = (mc == MapColor.NONE) ? FALLBACK : mc.col;
			}
		} catch (Throwable t) {
			rgb = FALLBACK;
		}
		arr[stateId] = rgb;
		return rgb;
	}

	/** Drops the cache on resource reload (map colors are resource-scoped). */
	public synchronized void clear() {
		java.util.Arrays.fill(colors, SENTINEL);
	}
}
