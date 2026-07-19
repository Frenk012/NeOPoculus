package net.irisshaders.iris.horizon.voxel;

import net.irisshaders.iris.horizon.HorizonConfig;
import net.minecraft.client.Minecraft;

/**
 * Maps camera distance to a LOD level and provides the per-level ring bounds
 * the scheduler sweeps (design-mesh-render.md section 4). Mirrors the classic
 * {@code HorizonLod.desiredScale} power-of-two ring scheme, but resolves to a
 * discrete mip level 0..4 instead of a cell scale.
 */
public final class VoxelLodSelector {
	private VoxelLodSelector() {
	}

	/**
	 * LOD level for a region whose nearest point is {@code dist} blocks away.
	 * The collar (within the real render distance + 256) is forced to L0 so it
	 * overlaps loaded chunks block-for-block. Past {@code lodRingWidth} the
	 * level steps up with every doubling of distance.
	 */
	public static int levelFor(double dist, int rdBlocks) {
		if (dist - 91 < rdBlocks + 256) {
			return 0;
		}
		int ringWidth = HorizonConfig.get().getLodRingWidth();
		int scale = Integer.highestOneBit(Math.max(1, (int) (dist / ringWidth)));
		scale = Math.max(HorizonConfig.get().getBaseLodScale(), scale);
		// scale 1,2,4,8,>=16 -> level 0,1,2,3,4
		int level = 31 - Integer.numberOfLeadingZeros(scale);
		return Math.min(level, VoxelConstants.MAX_LEVEL);
	}

	/** Effective render distance in blocks (client render distance × 16). */
	public static int renderDistanceBlocks() {
		return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
	}

	/**
	 * Outer edge of a level's annulus, in blocks: the coarser bound past which
	 * this level yields to the next. L{@link VoxelConstants#MAX_LEVEL} runs to
	 * the full LOD distance.
	 */
	public static int ringOuterBlocks(int level) {
		int maxDistance = HorizonConfig.get().getLodDistanceBlocks();
		if (level >= VoxelConstants.MAX_LEVEL) {
			return maxDistance;
		}
		int ringWidth = HorizonConfig.get().getLodRingWidth();
		return Math.min(maxDistance, ringWidth << level);
	}

	/**
	 * Region keep-radius (in this level's region units) for eviction and the
	 * scheduler sweep: the level's outer ring plus a two-region cushion so the
	 * one-region overlap and hysteresis band never sit on the eviction edge.
	 */
	public static int radiusRegions(int level) {
		int span = VoxelRegionKey.regionSpanBlocks(level);
		return (ringOuterBlocks(level) + span - 1) / span + 2;
	}
}
