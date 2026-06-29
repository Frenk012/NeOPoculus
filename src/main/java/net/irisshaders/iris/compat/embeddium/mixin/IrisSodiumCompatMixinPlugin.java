package net.irisshaders.iris.compat.embeddium.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Semi-critical mixin config plugin, disables mixins if Sodium isn't present,
 * since on 1.18+ we have mixins into Iris classes that crash the game instead of just
 * spamming the log if Sodium isn't present.
 */
public class IrisSodiumCompatMixinPlugin implements IMixinConfigPlugin {

	public static boolean isBendyLibLoaded;
	public static boolean isLittleTilesLoaded;

	/**
	 * Apple caps OpenGL at 4.1 over a Metal translation layer with no Direct State Access. The
	 * Iris&lt;-&gt;Embeddium chunk integration (the {@code monocle.*} + {@code oculus.*} mixins) rebuilds
	 * Embeddium's chunk vertex format and shader interface to Iris's extended layout; on Metal that
	 * path crashes natively on the first chunk draw (the game dies right after the
	 * "modifying ...ChunkShaderInterface" taint warning, with no Java stack or hs_err). Removing the
	 * integration — i.e. letting Embeddium render its own vanilla chunks — is confirmed to load fine
	 * on macOS. So on macOS we skip that integration while keeping the format-independent LittleTiles
	 * quad injection. Shaders don't run on Apple Silicon through Iris anyway, so nothing usable is lost.
	 */
	private static final boolean IS_MACOS =
		System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

	@Override
	public void onLoad(String mixinPackage) {
		isBendyLibLoaded = LoadingModList.get().getModFileById("bendylib") != null;
		isLittleTilesLoaded = LoadingModList.get().getModFileById("littletiles") != null;
		if (IS_MACOS) {
			System.out.println("[NeOPoculus] macOS detected — disabling the Iris/Embeddium chunk shader "
				+ "integration (no compute/DSA over Metal) and falling back to vanilla Embeddium chunk "
				+ "rendering to avoid a native crash on world load.");
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".copyEntity.ModelPartMixin") || mixinClassName.endsWith(".copyEntity.CuboidMixin")) {
			return !isBendyLibLoaded;
		}
		if (mixinClassName.endsWith(".littletiles.MixinBERenderManagerInvalidate")) {
			return isLittleTilesLoaded;
		}
		// On macOS, drop only the Iris shader/vertex-format integration into Embeddium (monocle.* +
		// oculus.*): it rebuilds Embeddium's chunk vertex format / shader interface to Iris's extended
		// layout, which Apple's GL-over-Metal (4.1, no DSA) can't take. Shaders don't run on Apple
		// Silicon through Iris anyway, so nothing usable is lost. The LittleTiles quad-injection mixins
		// (littletiles.*) feed standard BakedQuads into Embeddium's own vanilla vertex format, so they
		// are format-independent and kept — LittleTiles blocks still render under Embeddium on macOS.
		if (IS_MACOS && (mixinClassName.contains(".monocle.") || mixinClassName.contains(".oculus."))) {
			return false;
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
