package net.irisshaders.iris.compat.acceleratedrendering.mixin;

import net.irisshaders.iris.compat.acceleratedrendering.ARComputeSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single chokepoint for all of AcceleratedRendering's GPU compute work: {@code ComputeProgram}
 * is the only class issuing {@code glDispatchCompute}. Every AR code path that runs a transform
 * pass — gated or not, core or feature — ultimately calls {@code dispatch} here.
 *
 * On a GPU without compute support (macOS/Metal, capped at OpenGL 4.1) issuing the dispatch on the
 * placeholder program crashes the process natively, and AR reaches this from un-gated core paths
 * (e.g. its core {@code LevelRendererMixin}) that the feature toggles don't cover. Gating the
 * dispatch itself is the catch-all: no compute is ever submitted on such GPUs, so AR can stay bundled
 * (it keeps working normally on Windows, where this guard is a no-op) while being inert on macOS.
 */
@Pseudo
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.backends.programs.ComputeProgram", remap = false)
public class MixinComputeProgram {

	@Inject(method = "dispatch", at = @At("HEAD"), cancellable = true, remap = false)
	private void iris$skipDispatchWhenComputeUnsupported(CallbackInfo ci) {
		if (ARComputeSupport.isUnsupported()) {
			ci.cancel();
		}
	}

	@Inject(method = "useProgram()V", at = @At("HEAD"), cancellable = true, remap = false)
	private void iris$skipUseProgramWhenComputeUnsupported(CallbackInfo ci) {
		if (ARComputeSupport.isUnsupported()) {
			ci.cancel();
		}
	}
}
