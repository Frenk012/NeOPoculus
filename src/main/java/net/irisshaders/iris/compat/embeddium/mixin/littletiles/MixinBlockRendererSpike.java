package net.irisshaders.iris.compat.embeddium.mixin.littletiles;

import net.irisshaders.iris.compat.embeddium.littletiles.LittleTilesEmbeddiumBridge;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import org.embeddedt.embeddium.api.render.chunk.BlockRenderContext;
import org.embeddedt.embeddium.impl.render.chunk.compile.pipeline.BlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * LittleTiles ⇄ Embeddium render bridge (milestone 1).
 * LittleTiles blocks carry an empty vanilla model — their geometry lives in the BlockEntity.
 * Under Embeddium, LittleTiles' own chunk-build hooks are dead, so tiles never render.
 * Here we inject the tile BakedQuads (sourced via {@link LittleTilesEmbeddiumBridge}) into
 * Embeddium's own geometry list, so Embeddium bakes, lights, AO-shades, uploads and applies
 * shaders to them natively — the path validated by the two feasibility spikes.
 */
@Mixin(BlockRenderer.class)
public class MixinBlockRendererSpike {

	@Inject(method = "getGeometry", at = @At("RETURN"), cancellable = true)
	private void iris$littleTilesGeometry(BlockRenderContext ctx, Direction face,
										  CallbackInfoReturnable<List<BakedQuad>> cir) {
		// Unculled pass only; tile faces carry their own cull state via the quads themselves.
		if (face != null || !LittleTilesEmbeddiumBridge.isLittleTilesBlock(ctx.state())) {
			return;
		}

		List<BakedQuad> tiles = LittleTilesEmbeddiumBridge.collectQuads(ctx);
		if (tiles == null || tiles.isEmpty()) {
			return;
		}

		List<BakedQuad> original = cir.getReturnValue();
		List<BakedQuad> out = new ArrayList<>(original.size() + tiles.size());
		out.addAll(original);
		out.addAll(tiles);
		cir.setReturnValue(out);
	}
}
