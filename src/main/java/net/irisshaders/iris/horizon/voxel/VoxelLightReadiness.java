package net.irisshaders.iris.horizon.voxel;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Whether a chunk's light has settled enough to be captured into the LOD.
 *
 * <p>Shared by the client capture path and the server generator on purpose. The
 * light values baked into a cell are permanent — nothing rewrites them unless
 * the chunk is captured again — so capturing a chunk whose light has not landed
 * writes darkness that survives forever. That failure produced LOD terrain which
 * blackened progressively along the player's route and took a full debugging
 * session to trace, and a second copy of this rule on the server would be free
 * to drift back into it.
 *
 * <p>No client types: the server generator loads this in a JVM without them.
 */
final class VoxelLightReadiness {
	private VoxelLightReadiness() {
	}

	static boolean isReady(LevelChunk chunk) {
		try {
			if (!chunk.getLevel().dimensionType().hasSkyLight()) {
				return true; // nothing to wait for where there is no sky light
			}
			int highest = chunk.getHighestFilledSectionIndex();
			if (highest < 0) {
				return true; // all-air column: nothing to shade
			}
			SectionPos pos = SectionPos.of(chunk.getPos().x, chunk.getMinSection() + highest, chunk.getPos().z);
			LevelLightEngine engine = chunk.getLevel().getLightEngine();
			// lightOnInSection is the engine's own "this column has been lit"
			// flag. A present DataLayer alone is not enough: an unlit column is
			// published as an all-zero layer, which is exactly the data this gate
			// exists to reject.
			return engine.lightOnInSection(pos)
				&& engine.getLayerListener(LightLayer.SKY).getDataLayerData(pos) != null;
		} catch (Throwable t) {
			return true; // never stall capture on an unexpected light-engine shape
		}
	}
}
