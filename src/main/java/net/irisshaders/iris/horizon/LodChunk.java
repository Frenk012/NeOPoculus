package net.irisshaders.iris.horizon;

/**
 * Downsampled snapshot of a single 16x16 chunk column: per-column surface
 * height, water surface height (or Short.MIN_VALUE when dry) and an opaque
 * RGB color. Roughly 2.5 KB per chunk, which lets hundreds of thousands of
 * chunks stay resident without pressure.
 */
public final class LodChunk {
	public static final short NO_WATER = Short.MIN_VALUE;

	public final int chunkX;
	public final int chunkZ;
	/** Y of the top opaque surface block, per column (x + z * 16). */
	public final short[] height = new short[256];
	/** Y of the water surface per column, or NO_WATER. */
	public final short[] waterHeight = new short[256];
	/** Opaque 0xRRGGBB color per column (ground surface). */
	public final int[] color = new int[256];

	public static final short NO_FEATURE = Short.MIN_VALUE;
	/**
	 * Above-ground feature (tree foliage/logs) as a per-column voxel span:
	 * the foliage occupies [featureBottom, featureTop). Columns on a crown's
	 * edge have a high featureBottom (floating overhang), trunk columns reach
	 * the ground — together they mesh into a real 3D tree shape. NO_FEATURE
	 * when the column has nothing above the terrain surface.
	 */
	public final short[] featureTop = new short[256];
	public final short[] featureBottom = new short[256];
	public final int[] featureColor = new int[256];

	public LodChunk(int chunkX, int chunkZ) {
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
	}

	public static long key(int chunkX, int chunkZ) {
		return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
	}
}
