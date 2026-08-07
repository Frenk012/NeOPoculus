package net.irisshaders.iris.horizon.voxel;

/**
 * Distant Horizons material ids, as shaderpacks know them (the DH_BLOCK_*
 * macros in {@code StandardMacros}). The mesher writes one per quad into the
 * vertex's material byte, and the patched {@code dh_terrain} hands it to the
 * pack as {@code dhMaterialId}.
 *
 * <p>Only the categories a pack actually branches on are worth deriving. A pack
 * shades water, leaves and light sources differently from plain terrain, and
 * getting those three right is most of the visual difference; guessing at stone
 * versus dirt versus sand from a block state would be a lot of classification
 * for an effect no pack keys off strongly.
 */
public final class DhMaterials {
	public static final int UNKNOWN = 0;
	public static final int LEAVES = 1;
	public static final int LAVA = 6;
	public static final int SNOW = 8;
	public static final int WATER = 12;
	public static final int GRASS = 13;
	public static final int ILLUMINATED = 15;

	private DhMaterials() {
	}

	/**
	 * The material for a cell's block state.
	 *
	 * <p>Order matters: water before emission, because a pack's water shading is
	 * far more distinctive than its treatment of a glowing block, and lava is
	 * both a fluid and emissive but reads as lava to every pack.
	 */
	public static int of(VoxelPalettes palettes, int stateId) {
		if (stateId <= 0) {
			return UNKNOWN;
		}
		if (palettes.fluidOf(stateId) != 0) {
			return palettes.isTranslucent(stateId) ? WATER : LAVA;
		}
		if (palettes.isLeaf(stateId)) {
			return LEAVES;
		}
		if (palettes.emissionOf(stateId) > 0) {
			return ILLUMINATED;
		}
		return UNKNOWN;
	}
}
