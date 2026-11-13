package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.CorpseDragging;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RagdollPartBlockEntity.class, remap = false)
public abstract class CorpseGrabReleaseMixin {
    @Inject(method = "notifyReleased", at = @At("HEAD"))
    private void ragrevival$endNativeGrip(UUID playerId, CallbackInfo ci) {
        CorpseDragging.nativeReleased((RagdollPartBlockEntity) (Object) this, playerId);
    }
}
