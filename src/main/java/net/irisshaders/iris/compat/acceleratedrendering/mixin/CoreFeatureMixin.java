package net.irisshaders.iris.compat.acceleratedrendering.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * AcceleratedRendering's {@code CoreFeature#isDebugContextEnabled} reads a NeoForge
 * {@code ModConfigSpec.ConfigValue} from a mixin injected into {@code Minecraft.<init>}.
 * <p>
 * Under Sinytra Connector the early mod loading is moved inside the Minecraft constructor,
 * so the backing config store is not yet populated when that mixin fires. The
 * {@code ConfigValue.get()} call then throws {@code IllegalStateException: Cannot get config
 * value before config is loaded.} and crashes the game during "Initializing game".
 * <p>
 * Guard the read: if the config isn't loaded yet, fall back to {@code false} (debug context
 * disabled) instead of crashing. Once the config is loaded later the original value is returned.
 */
@Pseudo
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.CoreFeature", remap = false)
public class CoreFeatureMixin {

	@WrapMethod(method = "isDebugContextEnabled", remap = false)
	private static boolean iris$guardConfigNotLoaded(Operation<Boolean> original) {
		try {
			return original.call();
		} catch (IllegalStateException e) {
			// Config not loaded yet (e.g. Connector lifecycle reordering); default to disabled.
			return false;
		}
	}
}
