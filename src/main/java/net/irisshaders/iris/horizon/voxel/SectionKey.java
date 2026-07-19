package net.irisshaders.iris.horizon.voxel;

/**
 * Static pack/unpack helpers for the 64-bit section key (DESIGN.md section
 * 3): bits 0-25 x (biased +2^25), 26-51 z (same bias), 52-59 y (biased
 * +128), 60-63 level. Coordinates are section-grid coordinates <em>at that
 * level</em>: a level-N section spans {@code 32 << N} blocks per axis, so
 * {@code secCoord = floorDiv(blockCoord, 32 << N)}.
 *
 * <p>WHY biased fields instead of raw two's complement: every field is
 * non-negative inside the key, so packing is mask+shift, unpacking is
 * mask+subtract, and no sign-extension bugs can hide in the boundaries.
 * With level &le; 4 bit 63 stays clear, so keys are always non-negative.
 *
 * <p>Edge cases (unit-test grade):
 * <ul>
 * <li>Negative coordinates: x = -1 packs to biased 0x1FFFFFF and round-trips
 * exactly; the full valid domain is x/z in [-2^25, 2^25), y in [-128, 128)
 * — beyond the world border and any datapack dimension height at every
 * level. Out-of-range inputs wrap silently (masked), they do not throw.</li>
 * <li>Level bounds: the field holds 0-15 but the engine only uses
 * 0..{@link VoxelConstants#MAX_LEVEL}; {@link #parentOf} at MAX_LEVEL and
 * {@link #childOf} at level 0 are caller errors (they produce a key at a
 * level the engine never stores, not an exception).</li>
 * <li>Octree consistency across zero: sections -2 and -1 share parent -1
 * exactly as 0 and 1 share parent 0 (arithmetic shift = floor division).</li>
 * </ul>
 */
final class SectionKey {
	private SectionKey() {
	}

	static long pack(int level, int x, int y, int z) {
		return ((long) level << VoxelConstants.KEY_LEVEL_SHIFT)
			| ((((long) y + VoxelConstants.KEY_Y_BIAS) & VoxelConstants.KEY_Y_MASK) << VoxelConstants.KEY_Y_SHIFT)
			| ((((long) z + VoxelConstants.KEY_XZ_BIAS) & VoxelConstants.KEY_XZ_MASK) << VoxelConstants.KEY_Z_SHIFT)
			| (((long) x + VoxelConstants.KEY_XZ_BIAS) & VoxelConstants.KEY_XZ_MASK);
	}

	static int level(long key) {
		return (int) (key >>> VoxelConstants.KEY_LEVEL_SHIFT) & 0xF;
	}

	static int x(long key) {
		return (int) (key & VoxelConstants.KEY_XZ_MASK) - VoxelConstants.KEY_XZ_BIAS;
	}

	static int y(long key) {
		return (int) ((key >>> VoxelConstants.KEY_Y_SHIFT) & VoxelConstants.KEY_Y_MASK) - VoxelConstants.KEY_Y_BIAS;
	}

	static int z(long key) {
		return (int) ((key >>> VoxelConstants.KEY_Z_SHIFT) & VoxelConstants.KEY_XZ_MASK) - VoxelConstants.KEY_XZ_BIAS;
	}

	/**
	 * Key of the enclosing parent section: level + 1, every coordinate
	 * floor-halved (arithmetic {@code >> 1} on the <em>unbiased</em> value).
	 *
	 * <p>WHY unpack-shift-repack instead of shifting the packed fields in
	 * place: {@code floor((x + BIAS) / 2) == floor(x / 2) + BIAS / 2}, so a
	 * plain shift of a biased field halves the bias along with the
	 * coordinate and silently rebases it — verified wrong for x = -3
	 * (biased shift then unbias yields -16777218 instead of -2). The
	 * unpack/repack costs a handful of ALU ops and is unambiguous.
	 */
	static long parentOf(long key) {
		return pack(level(key) + 1, x(key) >> 1, y(key) >> 1, z(key) >> 1);
	}

	/**
	 * Key of one of the eight children (level - 1). Octant bit assignment
	 * matches {@link #octantInParent}: bit 0 = x, bit 1 = z, bit 2 = y.
	 * {@code (coord << 1) | bit} is exact for negative coordinates too:
	 * doubling any int leaves bit 0 clear in two's complement.
	 */
	static long childOf(long key, int octant) {
		return pack(level(key) - 1,
			(x(key) << 1) | (octant & 1),
			(y(key) << 1) | ((octant >> 2) & 1),
			(z(key) << 1) | ((octant >> 1) & 1));
	}

	/**
	 * Which of its parent's eight children this section is:
	 * {@code (x&1) | (z&1)<<1 | (y&1)<<2}. Java's {@code & 1} yields 0/1
	 * for negatives as well (-3 & 1 == 1, and indeed -3 = 2*(-2) + 1).
	 */
	static int octantInParent(long key) {
		return (x(key) & 1) | ((z(key) & 1) << 1) | ((y(key) & 1) << 2);
	}

	/** Key of the same-level section displaced by (dx, dy, dz) section steps. */
	static long offset(long key, int dx, int dy, int dz) {
		return pack(level(key), x(key) + dx, y(key) + dy, z(key) + dz);
	}

	/**
	 * Face-adjacent neighbor at the same level, {@code face} in DH normal
	 * order ({@link VoxelConstants#FACE_NEG_Y}...). Used by the ingest
	 * create-notification contract (DESIGN.md addition (b)) and later by the
	 * mesher's neighbor-plane pulls.
	 */
	static long faceNeighbor(long key, int face) {
		return switch (face) {
			case VoxelConstants.FACE_NEG_Y -> offset(key, 0, -1, 0);
			case VoxelConstants.FACE_POS_Y -> offset(key, 0, 1, 0);
			case VoxelConstants.FACE_NEG_Z -> offset(key, 0, 0, -1);
			case VoxelConstants.FACE_POS_Z -> offset(key, 0, 0, 1);
			case VoxelConstants.FACE_NEG_X -> offset(key, -1, 0, 0);
			case VoxelConstants.FACE_POS_X -> offset(key, 1, 0, 0);
			default -> throw new IllegalArgumentException("bad face " + face);
		};
	}

	// --- Section <-> block coordinate math ---

	/** Blocks covered per axis by a section at this level: 32, 64, 128, 256, 512. */
	static int sectionSpanBlocks(int level) {
		return VoxelConstants.SECTION_SIZE << level;
	}

	/**
	 * Section coordinate containing a block coordinate. Arithmetic shift is
	 * exact floor division for powers of two, so negatives are correct:
	 * block -1 is in section -1, not section 0.
	 */
	static int blockToSection(int blockCoord, int level) {
		return blockCoord >> (VoxelConstants.SECTION_BITS + level);
	}

	/** Smallest block coordinate covered by a section coordinate at this level. */
	static int sectionMinBlock(int secCoord, int level) {
		return secCoord << (VoxelConstants.SECTION_BITS + level);
	}

	/** Key of the level-{@code level} section containing a block position. */
	static long ofBlock(int level, int blockX, int blockY, int blockZ) {
		int shift = VoxelConstants.SECTION_BITS + level;
		return pack(level, blockX >> shift, blockY >> shift, blockZ >> shift);
	}

	/** Human-readable form for log lines: {@code "L2 (5, -1, -7)"}. */
	static String describe(long key) {
		return "L" + level(key) + " (" + x(key) + ", " + y(key) + ", " + z(key) + ")";
	}
}
