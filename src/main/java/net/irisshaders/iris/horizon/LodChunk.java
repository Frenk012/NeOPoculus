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
	/** Opaque 0xRRGGBB color per column. */
	public final int[] color = new int[256];
	/** True where the surface is tree foliage/log (rendered as a floating crown). */
	public final boolean[] vegetation = new boolean[256];

	public LodChunk(int chunkX, int chunkZ) {
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
	}

	public static long key(int chunkX, int chunkZ) {
		return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
	}
}
