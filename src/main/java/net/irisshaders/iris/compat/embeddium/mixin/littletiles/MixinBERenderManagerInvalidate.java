package net.irisshaders.iris.compat.embeddium.mixin.littletiles;

import net.irisshaders.iris.compat.embeddium.littletiles.LittleTilesEmbeddiumBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires when a LittleTiles block's render data changes. Lets the bridge mark the surrounding
 * sections dirty so Embeddium rebuilds them — neighbour tile faces culled against a now-broken
 * bit get rebuilt immediately instead of lingering invisible. No caching involved.
 * Gated on LittleTiles being present (see IrisSodiumCompatMixinPlugin).
 */
@Mixin(targets = "team.creative.littletiles.client.render.block.BERenderManager", remap = false)
public class MixinBERenderManagerInvalidate {

	@Inject(method = "sectionUpdate(J)V", at = @At("TAIL"), remap = false)
	private void iris$onTileChange(long section, CallbackInfo ci) {
		LittleTilesEmbeddiumBridge.onTileChange(this);
	}
}
