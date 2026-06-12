package net.irisshaders.iris.mixin;

import net.irisshaders.iris.horizon.HorizonLod;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/**
 * Extends the projection far plane while Horizon LOD terrain is active so
 * distant LOD geometry is not clipped by the vanilla frustum.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_Horizon {
	@ModifyReturnValue(method = "getDepthFar", at = @At("RETURN"))
	private float iris$horizonExtendFarPlane(float original) {
		return HorizonLod.INSTANCE.extendFarPlane(original);
	}
}
