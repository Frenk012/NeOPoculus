package net.irisshaders.iris.horizon.voxel;

/**
 * Static pack/unpack helpers for the 64-bit voxel cell (DESIGN.md section 3,
 * resolution R1): bits 0-19 blockStateId, 20-28 biomeId, 29-32 block light,
 * 33-36 sky light, 37-63 reserved (must stay zero in storage format v4 —
 * {@link #pack} guarantees that by construction).
 *
 * <p>WHY a bare {@code long} instead of an object: a section is then a flat
 * {@code long[32768]} that pools, snapshots and encodes as a single array
 * copy. And because stateId 0 is reserved for air, a freshly zeroed pooled
 * array already <em>is</em> a valid all-air section — no initialization pass.
 *
 * <p>All pack inputs are masked to their field width, so an out-of-range
 * argument can corrupt its own field but never bleed into a neighbor (e.g.
 * biomeId 512 wraps to 0 instead of leaking into the light bits; a negative
 * light value keeps only its low nibble). The palette layer enforces the real
 * id caps ({@link VoxelConstants#MAX_STATE_IDS},
 * {@link VoxelConstants#MAX_BIOME_IDS}); these helpers stay branch-free for
 * the hot ingest loop.
 */
final class VoxelCell {
	/** All-air, plains-fallback biome, unlit: every cell of a zeroed array. */
	static final long EMPTY = 0L;

	private VoxelCell() {
	}

	static long pack(int stateId, int biomeId, int blockLight, int skyLight) {
		return ((long) stateId & VoxelConstants.STATE_MASK)
			| (((long) biomeId & (VoxelConstants.MAX_BIOME_IDS - 1)) << VoxelConstants.BIOME_SHIFT)
			| (((long) blockLight & 0xF) << VoxelConstants.BLOCK_LIGHT_SHIFT)
			| (((long) skyLight & 0xF) << VoxelConstants.SKY_LIGHT_SHIFT);
	}

	static int stateId(long cell) {
		return (int) (cell & VoxelConstants.STATE_MASK);
	}

	static int biomeId(long cell) {
		return (int) ((cell & VoxelConstants.BIOME_MASK) >>> VoxelConstants.BIOME_SHIFT);
	}

	static int blockLight(long cell) {
		return (int) (cell >>> VoxelConstants.BLOCK_LIGHT_SHIFT) & 0xF;
	}

	static int skyLight(long cell) {
		return (int) (cell >>> VoxelConstants.SKY_LIGHT_SHIFT) & 0xF;
	}

	/**
	 * Replaces both light nibbles, keeping state and biome — the debounced
	 * light-refresh path rewrites cells this way after a block update, and
	 * the mipper writes averaged light into otherwise-air parent cells.
	 */
	static long withLight(long cell, int blockLight, int skyLight) {
		return (cell & ~VoxelConstants.LIGHT_MASK)
			| (((long) blockLight & 0xF) << VoxelConstants.BLOCK_LIGHT_SHIFT)
			| (((long) skyLight & 0xF) << VoxelConstants.SKY_LIGHT_SHIFT);
	}

	/** Combined 8-bit light field (sky in the high nibble); see {@link #withLightBits}. */
	static int lightBits(long cell) {
		return (int) ((cell & VoxelConstants.LIGHT_MASK) >>> VoxelConstants.LIGHT_SHIFT);
	}

	/**
	 * The mesher's faceKey composition from resolution R1:
	 * {@code (cell & ~LIGHT_MASK) | (lightBits << LIGHT_SHIFT)} — this cell's
	 * identity with a <em>neighbor's</em> light substituted, so greedy face
	 * merging keys on what the emitted face will actually look like (faces
	 * are lit by the air they touch, not by their own cell).
	 */
	static long withLightBits(long cell, int lightBits) {
		return (cell & ~VoxelConstants.LIGHT_MASK)
			| (((long) lightBits & 0xFF) << VoxelConstants.LIGHT_SHIFT);
	}

	static boolean isAir(long cell) {
		return (cell & VoxelConstants.STATE_MASK) == 0;
	}
}
