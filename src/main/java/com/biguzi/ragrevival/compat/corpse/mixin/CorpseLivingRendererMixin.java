package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.client.CorpseRagdollRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class CorpseLivingRendererMixin {
    @Shadow protected EntityModel<?> model;

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V", shift = At.Shift.AFTER))
    private void ragrevival$renderPart(LivingEntity entity, float yaw, float partialTick, PoseStack poses,
                                      MultiBufferSource buffer, int light, CallbackInfo ci) {
        CorpseRagdollRenderer.prepareModel(entity, model, poses);
    }

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void ragrevival$hideDummyName(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (CorpseRagdollRenderer.isRendering(entity)) cir.setReturnValue(false);
    }

    @Inject(method = "isEntityUpsideDown", at = @At("HEAD"), cancellable = true)
    private static void ragrevival$poseOwnsOrientation(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (CorpseRagdollRenderer.isRendering(entity)) cir.setReturnValue(false);
    }
}
