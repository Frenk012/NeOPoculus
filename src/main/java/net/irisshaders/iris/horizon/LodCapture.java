package net.irisshaders.iris.horizon;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Builds a LodChunk snapshot from a live client chunk. Runs on the client
 * thread when a chunk arrives; the per-chunk cost is a heightmap walk over
 * 256 columns, cheap enough not to need offloading.
 */
public final class LodCapture {
	private LodCapture() {
	}

	public static LodChunk capture(LevelChunk chunk) {
		LodChunk lod = new LodChunk(chunk.getPos().x, chunk.getPos().z);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minY = chunk.getMinBuildHeight();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();

		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int index = x + z * 16;
				int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				if (top <= minY) {
					// Empty column (e.g. void): mark as absent terrain.
					lod.height[index] = (short) minY;
					lod.waterHeight[index] = LodChunk.NO_WATER;
					lod.color[index] = 0;
					continue;
				}

				int y = top;
				short water = LodChunk.NO_WATER;
				BlockState state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));

				if (state.getFluidState().is(FluidTags.WATER)) {
					water = (short) (y + 1);
					// Walk down to the floor under the water for its color.
					int floor = Math.max(minY, y - 64);
					while (y > floor) {
						state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
						if (!state.getFluidState().is(FluidTags.WATER) && !state.isAir()) {
							break;
						}
						y--;
					}
				}

				MapColor mapColor = state.getMapColor(chunk.getLevel(), pos.set(baseX + x, y, baseZ + z));
				int rgb = mapColor == MapColor.NONE ? 0x7F7F7F : mapColor.col;

				lod.height[index] = (short) (y + 1);
				lod.waterHeight[index] = water;
				lod.color[index] = rgb;
			}
		}

		return lod;
	}
}
