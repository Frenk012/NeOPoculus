package net.irisshaders.iris.compat.acceleratedrendering.mixin;

import net.irisshaders.iris.compat.acceleratedrendering.ARComputeSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The real terrain-load crash on compute-less GPUs (macOS/Metal). AR's <em>core</em>
 * {@code LevelRendererMixin} (its mixin config has no plugin, so it always applies and is NOT gated by
 * the per-feature toggles) calls {@code CoreBuffers.*.drawBuffers()} every frame. {@code drawBuffers}
 * runs the compute pass — {@code selectTransformProgramDispatcher().dispatch(...)} →
 * {@code glDispatchCompute} on the (placeholder) program — which natively crashes where compute isn't
 * available, regardless of whether any feature is enabled.
 *
 * Cancel the draw (and therefore the dispatch) when compute is unsupported. With every feature gated
 * off these buffers are empty anyway, and {@code clearBuffers()} still runs separately, so nothing
 * accumulates. This is the catch-all that makes sure no compute is ever dispatched on such GPUs.
 */
@Pseudo
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedBufferSource", remap = false)
public class MixinAcceleratedBufferSource {

	@Inject(method = "drawBuffers()V", at = @At("HEAD"), cancellable = true, remap = false)
	private void iris$skipDispatchWhenComputeUnsupported(CallbackInfo ci) {
		if (ARComputeSupport.isUnsupported()) {
			ci.cancel();
		}
	}
}
