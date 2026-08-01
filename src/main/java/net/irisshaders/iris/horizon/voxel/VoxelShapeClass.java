package net.irisshaders.iris.horizon.voxel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * How a block state occupies its cell, decided once per state at palette
 * registration and stored in a per-state int (see {@link VoxelPalettes}).
 *
 * <p>Shape is a pure function of the block state, so it lives beside the state
 * id rather than in the cell: no bits are taken from the persisted cell format,
 * and the mesher's greedy merge key (state + biome) stays geometrically sound —
 * equal state implies equal box, so a merged run is still one flat rectangle.
 *
 * <p>The packed int is {@code class} in bits 24-25 plus the state's axis-aligned
 * bounds quantised to sixteenths in bits 0-23, six nibbles in the order
 * {@code minX minY minZ maxX maxY maxZ}, with each max stored as {@code max-1}
 * so a full 16 fits a nibble.
 */
public final class VoxelShapeClass {
	private VoxelShapeClass() {
	}

	/** Fills its cell (within a sixteenth on every axis): the fast path, meshed exactly as before. */
	public static final int FULL_CUBE = 0;
	/** A partial axis-aligned box: slab, snow layer, carpet, fence, wall, pane, door, lily pad, vine. */
	public static final int BOX = 1;
	/** A cross model (grass, flowers, crops, saplings): two diagonal quads with the alpha cutout. */
	public static final int CROSS = 2;
	/** Too small to read at LOD distance (torch, button, lever): meshed as air. */
	public static final int DROP = 3;

	private static final int CLASS_SHIFT = 24;
	/** Default for anything unresolvable: behave exactly as the engine did before shapes existed. */
	public static final int DEFAULT = FULL_CUBE << CLASS_SHIFT | fullBounds();

	public static int classOf(int packed) {
		return (packed >>> CLASS_SHIFT) & 3;
	}

	/** Lower bound on an axis (0..15) in sixteenths of the cell; axis 0=X, 1=Y, 2=Z. */
	public static int min(int packed, int axis) {
		return (packed >>> (axis * 4)) & 0xF;
	}

	/** Upper bound on an axis (1..16) in sixteenths of the cell; axis 0=X, 1=Y, 2=Z. */
	public static int max(int packed, int axis) {
		return ((packed >>> (12 + axis * 4)) & 0xF) + 1;
	}

	/** Whether the box covers its cell fully along the two axes perpendicular to {@code axis}. */
	public static boolean spansCrossSection(int packed, int axis) {
		for (int a = 0; a < 3; a++) {
			if (a != axis && (min(packed, a) != 0 || max(packed, a) != 16)) {
				return false;
			}
		}
		return true;
	}

	private static int fullBounds() {
		int packed = 0;
		for (int a = 0; a < 3; a++) {
			packed |= 15 << (12 + a * 4); // max 16, stored as 15
		}
		return packed;
	}

	/**
	 * Classifies a state from its render shape. Guarded like
	 * {@code VoxelPalettes.computeOpacity}: modded shape providers may assume a
	 * real level and throw, and a state we cannot measure must behave exactly as
	 * it did before shapes existed rather than vanish.
	 */
	public static int classify(BlockState state) {
		try {
			if (state.isAir()) {
				return DEFAULT;
			}
			AABB bounds;
			try {
				var shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
				if (shape.isEmpty()) {
					// No collision at all: a cross plant still wants rendering, but a
					// torch-like decoration is not worth a cell at LOD distance.
					return state.getBlock() instanceof BushBlock
						? (CROSS << CLASS_SHIFT) | fullBounds()
						: (DROP << CLASS_SHIFT) | fullBounds();
				}
				bounds = shape.bounds();
			} catch (Throwable t) {
				return DEFAULT;
			}
			if (state.getBlock() instanceof BushBlock) {
				return (CROSS << CLASS_SHIFT) | fullBounds();
			}
			int minX = quantMin(bounds.minX), minY = quantMin(bounds.minY), minZ = quantMin(bounds.minZ);
			int maxX = quantMax(bounds.maxX), maxY = quantMax(bounds.maxY), maxZ = quantMax(bounds.maxZ);
			if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
				return DEFAULT;
			}
			// Within a sixteenth of full on every axis: treat as a plain cube so
			// farmland, dirt paths and the like keep the untouched fast path.
			if (minX <= 1 && minY <= 1 && minZ <= 1 && maxX >= 15 && maxY >= 15 && maxZ >= 15) {
				return DEFAULT;
			}
			// A small, non-flat trinket reads as noise at LOD distance.
			int spanX = maxX - minX, spanY = maxY - minY, spanZ = maxZ - minZ;
			boolean flat = spanX >= 14 || spanZ >= 14;
			if (!flat && spanX <= 6 && spanZ <= 6 && spanY <= 10) {
				return (DROP << CLASS_SHIFT) | fullBounds();
			}
			int packed = (BOX << CLASS_SHIFT)
				| minX | (minY << 4) | (minZ << 8)
				| ((maxX - 1) << 12) | ((maxY - 1) << 16) | ((maxZ - 1) << 20);
			return packed;
		} catch (Throwable t) {
			return DEFAULT;
		}
	}

	private static int quantMin(double v) {
		return Math.max(0, Math.min(15, (int) Math.floor(v * 16.0)));
	}

	private static int quantMax(double v) {
		return Math.max(1, Math.min(16, (int) Math.ceil(v * 16.0)));
	}
}
