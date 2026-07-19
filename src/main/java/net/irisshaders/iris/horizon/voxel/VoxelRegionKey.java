package net.irisshaders.iris.horizon.voxel;

/**
 * Packs a draw-region address {@code (level, rx, rz)} into one long for the
 * renderer's mesh map and the scheduler. A draw region is
 * {@link VoxelConstants#MESH_REGION_SECTIONS}² sections in XZ and full height
 * in Y, so a region at level L spans {@link VoxelConstants#REGION_CELLS_XZ}
 * {@code << L} blocks per horizontal axis.
 *
 * <p>Layout: bits 60–63 level, 30–59 rz (biased), 0–29 rx (biased). The
 * ±2²⁹ bias keeps both coordinates non-negative so packing is mask+shift; the
 * ranges dwarf any reachable region coordinate.
 */
public final class VoxelRegionKey {
	private VoxelRegionKey() {
	}

	private static final int COORD_BITS = 30;
	private static final long COORD_MASK = (1L << COORD_BITS) - 1;
	private static final int COORD_BIAS = 1 << (COORD_BITS - 1);
	private static final int RZ_SHIFT = COORD_BITS;
	private static final int LEVEL_SHIFT = 2 * COORD_BITS;

	public static long pack(int level, int rx, int rz) {
		return ((long) (level & 0xF) << LEVEL_SHIFT)
			| (((long) rz + COORD_BIAS) & COORD_MASK) << RZ_SHIFT
			| (((long) rx + COORD_BIAS) & COORD_MASK);
	}

	public static int level(long key) {
		return (int) ((key >>> LEVEL_SHIFT) & 0xF);
	}

	public static int rx(long key) {
		return (int) ((key & COORD_MASK) - COORD_BIAS);
	}

	public static int rz(long key) {
		return (int) (((key >>> RZ_SHIFT) & COORD_MASK) - COORD_BIAS);
	}

	/** Region span in blocks per horizontal axis at the given level. */
	public static int regionSpanBlocks(int level) {
		return VoxelConstants.REGION_CELLS_XZ << level;
	}
}
