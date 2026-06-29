package net.irisshaders.iris.compat.acceleratedrendering.mixin;

import net.irisshaders.iris.compat.acceleratedrendering.ARComputeSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AcceleratedRendering compiles its compute programs in {@code ComputeShaderProgramLoader.apply}, a
 * resource-reload listener that runs unconditionally. On a GPU without compute support that compile
 * throws and crashes the game during resource loading. Skip the whole step when compute is
 * unavailable; the feature gates ({@link MixinAcceleratedFeatureGate}) ensure nothing then tries to
 * use the (absent) programs.
 */
@Pseudo
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.programs.ComputeShaderProgramLoader", remap = false)
public class MixinComputeShaderProgramLoader {

	@Inject(
		method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
		at = @At("HEAD"), cancellable = true, remap = false)
	private void iris$skipComputeCompileWhenUnsupported(CallbackInfo ci) {
		if (ARComputeSupport.isUnsupported()) {
			ci.cancel();
		}
	}
}
