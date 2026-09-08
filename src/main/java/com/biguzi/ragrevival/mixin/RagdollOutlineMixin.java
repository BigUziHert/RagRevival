package com.biguzi.ragrevival.mixin;

import com.biguzi.ragrevival.client.DownedOutline;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollPartBlockEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Retains Sable's skin, armor, limb poses and camera behavior while adding an outline stream. */
@Mixin(value = RagdollPartBlockEntityRenderer.class, remap = false)
public abstract class RagdollOutlineMixin {
    @ModifyArg(method = "render(Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE", target = "Ldev/leo/sableplayerragdoll/neoforge/client/RagdollPartBlockEntityRenderer;renderLayers(Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity;Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity$BodyPart;Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V"),
            index = 4)
    private MultiBufferSource ragrevival$outlineDownedBody(RagdollPartBlockEntity part,
            RagdollPartBlockEntity.BodyPart bodyPart, LivingEntity entity, PoseStack poses,
            MultiBufferSource buffer, int light, float partialTick) {
        return DownedOutline.wrap(part, buffer);
    }
}
