package net.irisshaders.iris.horizon;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

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
				// MOTION_BLOCKING ignores grass, flowers and other
				// decorations that WORLD_SURFACE counts: those put the LOD
				// surface one block above the real ground and break the
				// seam against loaded chunks. Leaves and water still count.
				int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				if (top <= minY) {
					// Empty column (e.g. void): mark as absent terrain.
					lod.height[index] = (short) minY;
					lod.waterHeight[index] = LodChunk.NO_WATER;
					lod.color[index] = 0;
					continue;
				}

				int y = top;
				BlockState state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
				ClientLevel level = (ClientLevel) chunk.getLevel();

				if (state.getFluidState().is(FluidTags.WATER)) {
					short water = (short) (y + 1);
					// Walk down to the floor under the water for its color.
					int floor = Math.max(minY, y - 64);
					while (y > floor) {
						state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
						if (!state.getFluidState().is(FluidTags.WATER) && !state.isAir()) {
							break;
						}
						y--;
					}
					lod.height[index] = (short) (y + 1);
					lod.waterHeight[index] = water;
					lod.color[index] = LodColors.colorOf(state, level, pos.set(baseX + x, y, baseZ + z));
				} else if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
					// Tree foliage: keep the canopy height but flag it as
					// vegetation. The mesher caps the skirt for vegetation cells
					// so the crown reads as a floating green blob instead of a
					// solid pillar extruded down to the ground.
					lod.height[index] = (short) (y + 1);
					lod.waterHeight[index] = LodChunk.NO_WATER;
					lod.color[index] = LodColors.colorOf(state, level, pos.set(baseX + x, y, baseZ + z));
					lod.vegetation[index] = true;
				} else {
					lod.height[index] = (short) (y + 1);
					lod.waterHeight[index] = LodChunk.NO_WATER;
					lod.color[index] = LodColors.colorOf(state, level, pos.set(baseX + x, y, baseZ + z));
				}
			}
		}

		return lod;
	}
}
