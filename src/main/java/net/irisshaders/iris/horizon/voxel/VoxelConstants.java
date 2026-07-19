package net.irisshaders.iris.horizon.voxel;

/**
 * Shared constants for the voxel LOD engine. Single home for every value the
 * data model, storage format, mesher and renderer must agree on; see
 * docs/horizon-voxel/DESIGN.md sections 3 and 6 for the layouts these encode.
 */
public final class VoxelConstants {
	private VoxelConstants() {
	}

	// --- Section geometry (constant at every LOD level) ---
	public static final int SECTION_BITS = 5;
	/** Cells per axis of a section; a level-N section spans 32 * 2^N blocks. */
	public static final int SECTION_SIZE = 1 << SECTION_BITS;
	public static final int SECTION_CELLS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
	/** LOD mip levels 0..4; L0 is one block per cell. */
	public static final int MAX_LEVEL = 4;
	public static final int LEVEL_COUNT = MAX_LEVEL + 1;

	// --- Cell bit layout (64-bit; DESIGN.md section 3, resolution R1) ---
	// bits 0-19 stateId (0 = air), 20-28 biomeId (0 = plains fallback),
	// 29-32 block light, 33-36 sky light, 37-63 reserved-zero in v4.
	public static final int STATE_BITS = 20;
	public static final int BIOME_BITS = 9;
	public static final long STATE_MASK = (1L << STATE_BITS) - 1;
	public static final int BIOME_SHIFT = STATE_BITS;
	public static final long BIOME_MASK = ((1L << BIOME_BITS) - 1) << BIOME_SHIFT;
	public static final int LIGHT_SHIFT = BIOME_SHIFT + BIOME_BITS;
	public static final long LIGHT_MASK = 0xFFL << LIGHT_SHIFT;
	public static final int MAX_STATE_IDS = 1 << STATE_BITS;
	public static final int MAX_BIOME_IDS = 1 << BIOME_BITS;

	// --- Region granularities (resolution R5: never say bare "region") ---
	/** Storage regions: 8x8 sections per .hlod v4 file. */
	public static final int STORAGE_REGION_BITS = 3;
	/** Mesh regions: 4x4 sections per draw unit. */
	public static final int MESH_REGION_SECTIONS = 4;

	// --- Photo atlas (resolution R2: plain GL_TEXTURE_2D) ---
	public static final int ATLAS_INITIAL_SIZE = 2048;
	public static final int ATLAS_MAX_SIZE = 4096;
	/** Pixels per photo slot edge at mip 0; mip k holds 16 >> k px. */
	public static final int SLOT_PX = 16;
	/** Real mip levels on the atlas; mip 4 texel is the face-average color. */
	public static final int ATLAS_MIP_LEVELS = 4;

	// --- Storage format ---
	public static final int STORAGE_VERSION = 4;
}
