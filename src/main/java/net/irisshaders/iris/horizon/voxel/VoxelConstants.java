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
	/** Local-coordinate mask: {@code coord & SECTION_MASK} = position within a section. */
	public static final int SECTION_MASK = SECTION_SIZE - 1;
	/** Cells in one 32x32 boundary plane ({@code VoxelSection.copyPlaneInto} buffers). */
	public static final int SECTION_PLANE_CELLS = SECTION_SIZE * SECTION_SIZE;
	/** LOD mip levels 0..4; L0 is one block per cell. */
	public static final int MAX_LEVEL = 4;
	public static final int LEVEL_COUNT = MAX_LEVEL + 1;

	// --- Cell bit layout (64-bit; DESIGN.md section 3, resolution R1) ---
	// bits 0-19 stateId (0 = air), 20-28 biomeId (0 = plains fallback),
	// 29-32 block light, 33-36 sky light, 37-63 reserved-zero in v4.
	/** A fully-zero cell: state id 0 (air), no biome/light. {@link VoxelCell#isAir} matches it. */
	public static final long AIR_CELL = 0L;
	public static final int STATE_BITS = 20;
	public static final int BIOME_BITS = 9;
	public static final long STATE_MASK = (1L << STATE_BITS) - 1;
	public static final int BIOME_SHIFT = STATE_BITS;
	public static final long BIOME_MASK = ((1L << BIOME_BITS) - 1) << BIOME_SHIFT;
	public static final int LIGHT_SHIFT = BIOME_SHIFT + BIOME_BITS;
	public static final long LIGHT_MASK = 0xFFL << LIGHT_SHIFT;
	/** Block-light nibble; the combined 8-bit light field starts here (== LIGHT_SHIFT). */
	public static final int BLOCK_LIGHT_SHIFT = LIGHT_SHIFT;
	/** Sky-light nibble: the high half of the 8-bit light field. */
	public static final int SKY_LIGHT_SHIFT = LIGHT_SHIFT + 4;
	public static final int MAX_STATE_IDS = 1 << STATE_BITS;
	public static final int MAX_BIOME_IDS = 1 << BIOME_BITS;

	// --- SectionKey bit layout (64-bit; DESIGN.md section 3) ---
	// bits 0-25 x (biased +2^25), 26-51 z (same bias), 52-59 y (biased +128),
	// 60-63 level. The bias keeps every field non-negative so packing is mask
	// + shift and unpacking is mask + subtract; with level <= 4 bit 63 stays
	// clear, so packed keys are always non-negative longs.
	public static final int KEY_XZ_BITS = 26;
	public static final long KEY_XZ_MASK = (1L << KEY_XZ_BITS) - 1;
	/** Added to signed section x/z before packing: range +/-2^25 sections. */
	public static final int KEY_XZ_BIAS = 1 << (KEY_XZ_BITS - 1);
	public static final int KEY_Y_BITS = 8;
	public static final long KEY_Y_MASK = (1L << KEY_Y_BITS) - 1;
	/** Added to signed section y: range [-128,128) sections, i.e. world Y down to -4096 at L0. */
	public static final int KEY_Y_BIAS = 1 << (KEY_Y_BITS - 1);
	public static final int KEY_Z_SHIFT = KEY_XZ_BITS;
	public static final int KEY_Y_SHIFT = 2 * KEY_XZ_BITS;
	public static final int KEY_LEVEL_SHIFT = KEY_Y_SHIFT + KEY_Y_BITS;

	// --- Face indices, DH normal order (DESIGN.md contract addition (d)) ---
	// Shared by VoxelSection.copyPlaneInto, the mesher and the shader-side
	// u_faceAxes orientation table; the order is load-bearing and must never
	// change.
	public static final int FACE_NEG_Y = 0;
	public static final int FACE_POS_Y = 1;
	public static final int FACE_NEG_Z = 2;
	public static final int FACE_POS_Z = 3;
	public static final int FACE_NEG_X = 4;
	public static final int FACE_POS_X = 5;
	public static final int FACE_COUNT = 6;

	// --- Ingestion / mip propagation ---
	/** Biome quarts (4x4x4 grid, one per 4x4x4 blocks) in a vanilla 16^3 section. */
	public static final int QUARTS_PER_VANILLA_SECTION = 64;
	/** Ticks a section's pending remips/light refresh sit before a worker pass runs. */
	public static final int MIP_DEBOUNCE_TICKS = 5;
	/** Dirty-section budget per VoxelMipper.processRemips worker pass. */
	public static final int MAX_REMIP_SECTIONS_PER_PASS = 64;

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

	// --- Meshing / rendering (design-mesh-render.md section 0) ---
	/** Draw region = MESH_REGION_SECTIONS^2 sections in XZ, full height in Y. */
	public static final int REGION_SECTIONS_XZ = MESH_REGION_SECTIONS;
	/** Cells across a region in X or Z: 4 sections x 32 cells. */
	public static final int REGION_CELLS_XZ = MESH_REGION_SECTIONS * SECTION_SIZE;
	/** Greedy-quad size cap (cells) on both axes, matching the classic mesher's merge discipline. */
	public static final int MERGE_CAP = 16;
	/** Added to world Y before packing into the u16 vertex position (keeps it non-negative). */
	public static final int Y_BIAS = 512;
	/** Vertex format v2 stride, bytes (LodVertexFormatV2). */
	public static final int VERTEX_STRIDE = 24;
	/** Hard per-region quad emit cap; overflow is logged once and truncated. */
	public static final int MAX_QUADS_PER_REGION = 131_072;

	// --- Two-tier residency (VoxelStore, design-data-storage.md section 5.2) ---
	/**
	 * HOT-tier cap: raw {@code long[]} sections kept resident before the save
	 * cycle packs the coldest ones down to WARM. 256 sections x 256 KB = 64 MB,
	 * matching the design section 5.3 RAM budget. A soft cap: a burst of ingest
	 * may exceed it briefly until the next {@code VoxelStore.runSaveAndEvictCycle}.
	 */
	public static final int HOT_CACHE_SECTIONS = 256;
	/**
	 * Dirty-HOT-section backlog that triggers an early save-only cycle between
	 * the periodic ones (design section 6). Bounds how much unsaved work can
	 * accumulate — and thus be lost on a hard crash — while exploring fast.
	 */
	public static final int UNSAVED_FLUSH_THRESHOLD = 512;
}
