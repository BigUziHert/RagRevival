package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.CorpseCompat;
import com.biguzi.ragrevival.compat.corpse.CorpseDragging;
import de.maxhenkel.corpse.entities.CorpseEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = CorpseEntity.class, remap = false)
public abstract class CorpseInteractionMixin {
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void ragrevival$separateDragAndLoot(Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        CorpseEntity corpse = (CorpseEntity) (Object) this;
        if (player instanceof ServerPlayer serverPlayer && !CorpseDragging.canReachCorpse(serverPlayer, corpse)) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        if (!player.isShiftKeyDown() && player.getMainHandItem().isEmpty()) {
            if (player instanceof ServerPlayer serverPlayer && CorpseDragging.mayDrag(serverPlayer, corpse)) {
                CorpseCompat.ensurePhysics(corpse);
            }
            // The native held-use poll selects the actual limb and starts the ordinary Sable grip.
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }
}
