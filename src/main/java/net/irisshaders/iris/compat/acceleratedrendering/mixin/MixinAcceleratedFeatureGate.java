package net.irisshaders.iris.compat.acceleratedrendering.mixin;

import net.irisshaders.iris.compat.acceleratedrendering.ARComputeSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces every AcceleratedRendering feature to report disabled when the GPU has no compute support,
 * so the accelerated pipeline is never entered (it would dereference compute programs that were
 * skipped by {@link MixinComputeShaderProgramLoader}) and rendering falls back to vanilla. Targets
 * all feature toggles at once via their shared {@code static boolean isEnabled()} entry point.
 */
@Pseudo
@Mixin(targets = {
	"com.github.argon4w.acceleratedrendering.features.entities.AcceleratedEntityRenderingFeature",
	"com.github.argon4w.acceleratedrendering.features.items.AcceleratedItemRenderingFeature",
	"com.github.argon4w.acceleratedrendering.features.text.AcceleratedTextRenderingFeature",
	"com.github.argon4w.acceleratedrendering.features.culling.OrientationCullingFeature"
}, remap = false)
public class MixinAcceleratedFeatureGate {

	@Inject(method = "isEnabled()Z", at = @At("HEAD"), cancellable = true, remap = false)
	private static void iris$disableWhenComputeUnsupported(CallbackInfoReturnable<Boolean> cir) {
		if (ARComputeSupport.isUnsupported()) {
			cir.setReturnValue(false);
		}
	}
}
