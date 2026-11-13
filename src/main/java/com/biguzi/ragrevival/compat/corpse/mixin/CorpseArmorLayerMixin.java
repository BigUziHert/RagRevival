package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.client.CorpseRagdollRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidArmorLayer.class)
public abstract class CorpseArmorLayerMixin {
    @Inject(method = "setPartVisibility", at = @At("TAIL"))
    private void ragrevival$onlyCorpsePart(HumanoidModel<?> model, EquipmentSlot slot, CallbackInfo ci) {
        HumanoidArmorLayer<?, ?, ?> layer = (HumanoidArmorLayer<?, ?, ?>) (Object) this;
        CorpseRagdollRenderer.maskArmor(model, layer.getParentModel());
    }
}
