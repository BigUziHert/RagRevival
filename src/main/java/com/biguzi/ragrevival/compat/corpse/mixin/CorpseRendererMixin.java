package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.client.CorpseRagdollRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import de.maxhenkel.corpse.entities.CorpseEntity;
import de.maxhenkel.corpse.entities.CorpseRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = CorpseRenderer.class, remap = false)
public abstract class CorpseRendererMixin {
    @Unique private final CorpseRagdollRenderer ragrevival$renderer = new CorpseRagdollRenderer();

    @Inject(method = "render(Lde/maxhenkel/corpse/entities/CorpseEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void ragrevival$renderPose(CorpseEntity corpse, float yaw, float partialTick, PoseStack poses,
                                      MultiBufferSource buffer, int light, CallbackInfo ci) {
        if (ragrevival$renderer.render(corpse, partialTick, poses, buffer, light)) ci.cancel();
    }
}
