package net.irisshaders.iris.compat.embeddium.mixin.littletiles;

import net.irisshaders.iris.compat.embeddium.littletiles.LittleTilesEmbeddiumBridge;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The meshing task iterates {@code model.getRenderTypes(state,...)} and calls BlockRenderer.renderModel
 * once per declared layer; the LittleTiles bridge (see {@link LittleTilesEmbeddiumBridge}) returns its
 * baked quads only for the layer currently being built ({@code perLayer.get(ctx.renderLayer())}).
 *
 * A BlockTile's vanilla model is empty and declares only solid/cutout layers, so the TRANSLUCENT pass
 * never runs for it — glass (and any translucent) tiles get baked into the translucent bucket but are
 * never requested, so they render invisible while hitbox/collision (server state) stay intact.
 *
 * Force LittleTiles blocks to advertise every chunk render layer so renderModel runs for each, letting
 * the bridge hand back the matching tiles per layer.
 */
@Mixin(ChunkBuilderMeshingTask.class)
public class MixinMeshingTaskRenderTypes {

	@Redirect(method = "execute", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/resources/model/BakedModel;getRenderTypes(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;)Lnet/neoforged/neoforge/client/ChunkRenderTypeSet;"))
	private ChunkRenderTypeSet iris$littleTilesAllLayers(BakedModel model, BlockState state, RandomSource random, ModelData data) {
		if (LittleTilesEmbeddiumBridge.isLittleTilesBlock(state)) {
			return ChunkRenderTypeSet.of(
				RenderType.solid(),
				RenderType.cutoutMipped(),
				RenderType.cutout(),
				RenderType.translucent(),
				RenderType.tripwire());
		}
		return model.getRenderTypes(state, random, data);
	}
}
