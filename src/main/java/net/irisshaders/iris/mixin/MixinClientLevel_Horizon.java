package net.irisshaders.iris.mixin;

import net.irisshaders.iris.horizon.HorizonConfig;
import net.irisshaders.iris.horizon.HorizonLod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds server-verified block changes to the voxel LOD engine.
 *
 * <p>setServerVerifiedBlockState is the single funnel both
 * ClientboundBlockUpdatePacket and ClientboundSectionBlocksUpdatePacket flow
 * through (docs/horizon-voxel design section 3.5), so one TAIL hook sees
 * every authoritative block change exactly once — no double counting from
 * hooking the packet handlers individually. TAIL so the engine only learns
 * of states the level actually accepted.
 */
@Mixin(ClientLevel.class)
public class MixinClientLevel_Horizon {
	@Inject(method = "setServerVerifiedBlockState", at = @At("TAIL"))
	private void iris$horizonVoxelBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
		// Cheap early-out on the packet-handling hot path: two config reads
		// when the voxel engine is off; the engine itself no-ops until it
		// has a world for this dimension.
		HorizonConfig config = HorizonConfig.get();
		if (!config.isEnabled() || !config.isVoxelEngine()) {
			return;
		}
		HorizonLod.INSTANCE.voxel().onBlockChanged((ClientLevel) (Object) this, pos, state);
	}
}
