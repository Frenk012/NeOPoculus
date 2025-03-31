package net.irisshaders.iris.compat.acceleratedrendering.mixin;

import com.github.argon4w.acceleratedrendering.compat.iris.IShadowBufferSourceGetter;
import com.github.argon4w.acceleratedrendering.core.buffers.SimpleCrumblingBufferSource;
import com.github.argon4w.acceleratedrendering.features.blocks.AcceleratedBlockEntityRenderingFeature;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.SortedSet;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import org.apache.commons.lang3.mutable.MutableInt;
import org.embeddedt.embeddium.impl.render.EmbeddiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin({ShadowRenderer.class})
public class ShadowRendererMixin {
    public ShadowRendererMixin() {
    }

    @WrapOperation(
            method = {"renderShadows"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/shadows/ShadowRenderingState;renderBlockEntities(Lnet/irisshaders/iris/shadows/ShadowRenderer;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;DDDFZZ)I"
            )}
    )
    public int wrapRenderBlockEntities(ShadowRenderer shadowRenderer, MultiBufferSource.BufferSource renderBuffers, PoseStack poseStack, Camera camera, double cameraX, double cameraY, double cameraZ, float partialTick, boolean hasEntityFrustum, boolean lightsOnly, Operation<Integer> original) {
        if (!AcceleratedBlockEntityRenderingFeature.isEnabled()) {
            return (Integer)original.call(shadowRenderer, renderBuffers, poseStack, camera, cameraX, cameraY, cameraZ, partialTick, hasEntityFrustum, lightsOnly);
        } else if (!AcceleratedBlockEntityRenderingFeature.shouldUseAcceleratedPipeline()) {
            return (Integer)original.call(shadowRenderer, renderBuffers, poseStack, camera, cameraX, cameraY, cameraZ, partialTick, hasEntityFrustum, lightsOnly);
        } else {
            BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
            Long2ObjectMap<SortedSet<BlockDestructionProgress>> blockBreakingProgressions = ((LevelRendererAccessor)Minecraft.getInstance().levelRenderer).getDestructionProgress();
            MutableInt counter = new MutableInt(0);
            EmbeddiumWorldRenderer.instance().blockEntityIterator().forEachRemaining((blockEntity) -> {
                if (!lightsOnly || blockEntity.getBlockState().getLightEmission() != 0) {
                    BlockPos pos = blockEntity.getBlockPos();
                    MultiBufferSource bufferSource = ((IShadowBufferSourceGetter)shadowRenderer).getShadowBufferSource();
                    SortedSet<BlockDestructionProgress> destructionProgresses = (SortedSet)blockBreakingProgressions.get(pos.asLong());
                    poseStack.pushPose();
                    poseStack.translate((double)pos.getX() - cameraX, (double)pos.getY() - cameraY, (double)pos.getZ() - cameraZ);
                    if (destructionProgresses != null && !destructionProgresses.isEmpty()) {
                        int progress = ((BlockDestructionProgress)destructionProgresses.last()).getProgress();
                        if (progress >= 0) {
                            bufferSource = new SimpleCrumblingBufferSource(bufferSource, progress, poseStack, 1.0F);
                        }

                        dispatcher.render(blockEntity, partialTick, poseStack, bufferSource);
                        poseStack.popPose();
                        counter.increment();
                    } else {
                        dispatcher.render(blockEntity, partialTick, poseStack, bufferSource);
                        poseStack.popPose();
                        counter.increment();
                    }
                }
            });
            return counter.intValue();
        }
    }
}
