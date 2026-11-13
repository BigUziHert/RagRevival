package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollPartBlockEntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RagdollPartBlockEntityRenderer.class, remap = false)
public abstract class CorpseNativePartRendererMixin {
    @Inject(method = "render(Ldev/leo/sableplayerragdoll/block/entity/RagdollPartBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), cancellable = true)
    private void ragrevival$hideDuplicate(RagdollPartBlockEntity part, float partialTick, PoseStack poses,
                                         MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (part.getPersistentData().getBoolean(CorpsePose.PART_MARKER)) ci.cancel();
    }
}
