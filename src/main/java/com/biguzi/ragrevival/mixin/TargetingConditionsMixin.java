package com.biguzi.ragrevival.mixin;

import com.biguzi.ragrevival.DownedManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TargetingConditions.class)
public abstract class TargetingConditionsMixin {
    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    private void ragrevival$ignoreProtected(LivingEntity source, LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (target instanceof Player player && (DownedManager.isDowned(player)
                || (source == null || source instanceof Mob) && DownedManager.hasRevivalGrace(player)))
            cir.setReturnValue(false);
    }
}
