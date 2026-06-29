package net.irisshaders.iris.compat.acceleratedrendering.mixin;

import net.irisshaders.iris.compat.acceleratedrendering.ARComputeSupport;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AcceleratedRendering compiles its compute programs from {@code ComputeShaderProgramLoader}. On a
 * GPU without compute support that compile throws and crashes the game. The work is deferred:
 * {@code apply} (a resource-reload listener) only queues a {@code RenderCall} via
 * {@code RenderSystem.recordRenderCall}; the actual compile runs later inside {@code lambda$apply$0}
 * during {@code RenderSystem.replayQueue} (first frames / terrain load). Guard both ends when compute
 * is unavailable: skip queuing in {@code apply}, and — belt and suspenders for any other path that
 * queues it — skip the compile itself in {@code lambda$apply$0}. The feature gates
 * ({@link MixinAcceleratedFeatureGate}) ensure nothing then tries to use the (absent) programs.
 */
@Pseudo
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.programs.ComputeShaderProgramLoader", remap = false)
public class MixinComputeShaderProgramLoader {

	@Inject(
		method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
		at = @At("HEAD"), cancellable = true, remap = false)
	private void iris$skipComputeQueueWhenUnsupported(CallbackInfo ci) {
		if (ARComputeSupport.isUnsupported()) {
			ci.cancel();
		}
	}

	// Descriptor uses only java.util.Map, so it matches regardless of mappings — this is the
	// deferred compile that actually crashes, and the surgical catch for the terrain-load crash.
	@Inject(method = "lambda$apply$0(Ljava/util/Map;)V", at = @At("HEAD"), cancellable = true, remap = false)
	private static void iris$skipComputeCompileWhenUnsupported(java.util.Map<?, ?> shaders, CallbackInfo ci) {
		if (ARComputeSupport.isUnsupported()) {
			ci.cancel();
		}
	}

	// AR's core static holders (CoreBuffers.<clinit>) fetch programs here while building their
	// dispatchers, before anything is loaded — getProgram would throw "too early!" and crash terrain
	// load. Hand back a harmless placeholder so those constructors complete; the dispatchers are never
	// actually used because every feature is gated off.
	@Inject(method = "getProgram(Lnet/minecraft/resources/ResourceLocation;)Lcom/github/argon4w/acceleratedrendering/core/backends/programs/ComputeProgram;",
		at = @At("HEAD"), cancellable = true, remap = false)
	private static void iris$dummyProgramWhenUnsupported(ResourceLocation location, CallbackInfoReturnable<Object> cir) {
		if (ARComputeSupport.isUnsupported()) {
			cir.setReturnValue(ARComputeSupport.dummyProgram());
		}
	}
}
