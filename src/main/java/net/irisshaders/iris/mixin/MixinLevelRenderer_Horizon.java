package net.irisshaders.iris.mixin;

import net.irisshaders.iris.horizon.HorizonLod;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Horizon LOD terrain right before the translucent terrain layer:
 * after all opaque geometry and entities (which win the depth test), but
 * before water so it can blend over the distant horizon. Priority 999 so
 * this runs before Iris's own translucent-phase hook at the same point.
 */
@Mixin(value = LevelRenderer.class, priority = 999)
public class MixinLevelRenderer_Horizon {
	@Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=translucent"))
	private void iris$horizonRenderLod(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
									   GameRenderer gameRenderer, LightTexture lightTexture,
									   Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		HorizonLod.INSTANCE.render(modelView, projection);
	}
}
