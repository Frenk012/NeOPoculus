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
 * thread (throttled). The ground surface is captured as a heightmap; tree
 * foliage and other above-ground features are captured as a per-column
 * voxel span so they can be meshed as real 3D shapes instead of pillars.
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
				lod.featureTop[index] = LodChunk.NO_FEATURE;

				int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				if (top <= minY) {
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
					// Biome-tinted water color (swamp/cold/ocean differ) instead
					// of a fixed blue, blended slightly toward the floor color.
					int waterColor = level.getBiome(pos.set(baseX + x, top, baseZ + z)).value().getWaterColor();
					int floorColor = LodColors.colorOf(state, level, pos.set(baseX + x, y, baseZ + z));
					lod.color[index] = blend(waterColor, floorColor, 0.2f);
				} else if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
					// Tree column: separate the ground (heightmap) from the
					// above-ground foliage (voxel span).
					int canopyTop = y;
					int canopyColor = LodColors.colorOf(state, level, pos.set(baseX + x, y, baseZ + z));

					// Walk down past foliage/air to the ground surface block.
					int groundFloor = Math.max(minY, y - 48);
					int g = y;
					while (g > groundFloor) {
						BlockState below = chunk.getBlockState(pos.set(baseX + x, g - 1, baseZ + z));
						// Stop only at a real full-cube ground block. Leaves,
						// logs and thin tree parts (e.g. Dynamic Trees branches,
						// which aren't in the LOGS tag) are skipped so the walk
						// reaches the actual ground and the trunk stays part of
						// the feature span above it.
						if (!below.is(BlockTags.LEAVES) && !below.is(BlockTags.LOGS) && !below.isAir()
							&& below.isCollisionShapeFullBlock(chunk, pos)) {
							break;
						}
						g--;
					}
					int groundSurface = g - 1; // first solid non-foliage block
					lod.height[index] = (short) (groundSurface + 1);
					lod.color[index] = LodColors.colorOf(
						chunk.getBlockState(pos.set(baseX + x, groundSurface, baseZ + z)), level, pos);
					lod.waterHeight[index] = LodChunk.NO_WATER;

					// Foliage bottom: first non-air block above the ground in
					// this column. Crown-edge columns get a high bottom (the
					// overhang), trunk columns reach near the ground.
					int fb = groundSurface + 1;
					while (fb < canopyTop) {
						if (!chunk.getBlockState(pos.set(baseX + x, fb, baseZ + z)).isAir()) {
							break;
						}
						fb++;
					}
					lod.featureBottom[index] = (short) fb;
					lod.featureTop[index] = (short) (canopyTop + 1);
					lod.featureColor[index] = canopyColor;
				} else {
					lod.height[index] = (short) (y + 1);
					lod.waterHeight[index] = LodChunk.NO_WATER;
					lod.color[index] = LodColors.colorOf(state, level, pos.set(baseX + x, y, baseZ + z));
				}
			}
		}

		return lod;
	}

	/** Blends b into a by t (0..1), per RGB channel. */
	private static int blend(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int r = (int) (ar * (1 - t) + br * t);
		int g = (int) (ag * (1 - t) + bg * t);
		int bl = (int) (ab * (1 - t) + bb * t);
		return (r << 16) | (g << 8) | bl;
	}
}
