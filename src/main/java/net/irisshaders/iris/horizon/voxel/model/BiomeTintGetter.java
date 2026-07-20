package net.irisshaders.iris.horizon.voxel.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link BlockAndTintGetter} that pins every biome-color query to one fixed
 * biome, delegating everything else to the real client level. Baking a block
 * face's photo for a specific LOD biome then reuses vanilla's exact tint
 * classification (which of grass / foliage / water a given block wants, per its
 * registered color provider) without needing a real world position in that
 * biome: {@link #getBlockTint} resolves the provider's {@link ColorResolver}
 * against the pinned biome. Render thread only; constructed per bake.
 */
final class BiomeTintGetter implements BlockAndTintGetter {
	private final BlockAndTintGetter delegate;
	private final Biome biome;

	BiomeTintGetter(BlockAndTintGetter delegate, Biome biome) {
		this.delegate = delegate;
		this.biome = biome;
	}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver resolver) {
		return resolver.getColor(biome, pos.getX(), pos.getZ());
	}

	@Override
	public float getShade(Direction direction, boolean shade) {
		return delegate.getShade(direction, shade);
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return delegate.getLightEngine();
	}

	@Nullable
	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return delegate.getBlockEntity(pos);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return delegate.getBlockState(pos);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return delegate.getFluidState(pos);
	}

	@Override
	public int getHeight() {
		return delegate.getHeight();
	}

	@Override
	public int getMinBuildHeight() {
		return delegate.getMinBuildHeight();
	}
}
