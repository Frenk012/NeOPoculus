package net.irisshaders.iris.horizon.voxel.model;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The single source of truth for "leaf-like" block states (DESIGN.md R8).
 *
 * <p>Two subsystems must agree on this classification or the LOD terrain
 * visibly breaks: the capture side ({@code VoxelPalettes}) forces leaf-like
 * states to opacity 15 so distant canopies occlude like solid mass instead
 * of showing see-through trees, and the bakery
 * (design-bakery-atlas.md section 4) applies the darkened-mip branch so the
 * same states converge to a solid, slightly dark tinted mass at distance
 * instead of alpha-punching holes. Keeping the predicate here — and having
 * both consumers call it — means occlusion and visuals can never drift
 * apart.
 *
 * <p>Deliberately pure and uncached: it runs once per unique state at
 * palette registration (where {@code VoxelPalettes} caches the answer in
 * its opacity byte and leaf flag) and once per bake, so a cache here would
 * only add a second place for the truth to go stale on tag reload.
 */
public final class LeafLikePredicate {
	private LeafLikePredicate() {
	}

	/**
	 * True for states that render as a translucent canopy but must be
	 * treated as fully opaque by the LOD engine.
	 *
	 * <p>Checked both ways because modded coverage is inconsistent:
	 * {@code BlockTags.LEAVES} catches well-behaved packs (and non-block
	 * canopies tagged in), while the {@code instanceof LeavesBlock} test
	 * catches mods that subclass vanilla leaves without tagging them.
	 */
	public static boolean test(BlockState state) {
		return state.getBlock() instanceof LeavesBlock || state.is(BlockTags.LEAVES);
	}
}
