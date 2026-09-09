package com.biguzi.ragrevival.mixin;

import com.biguzi.ragrevival.FeedingAnimation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native use is cosmetic during feeding: no food, finish, release or item-stop callbacks. */
@Mixin(LivingEntity.class)
public abstract class FeedingUseMixin implements FeedingAnimation.ManagedUse {
    @Shadow protected int useItemRemaining;
    @Unique private boolean ragrevival$managedUse;
    @Unique private int ragrevival$animationTicks;

    @Override
    public boolean ragrevival$isManagedUse() {
        return ragrevival$managedUse;
    }

    @Override
    public void ragrevival$setManagedUse(boolean managed) {
        ragrevival$managedUse = managed;
        ragrevival$animationTicks = 0;
    }

    @Unique
    private boolean ragrevival$isFeeding() {
        return (Object) this instanceof Player player && FeedingAnimation.isManaged(player);
    }

    @Inject(method = "startUsingItem", at = @At("HEAD"))
    private void ragrevival$rememberFeeding(InteractionHand hand, CallbackInfo ci) {
        if (ragrevival$isFeeding()) ragrevival$setManagedUse(true);
    }

    @Inject(method = "updateUsingItem", at = @At("HEAD"), cancellable = true)
    private void ragrevival$animateWithoutConsuming(ItemStack stack, CallbackInfo ci) {
        if (!ragrevival$isFeeding()) return;
        ragrevival$managedUse = true;
        useItemRemaining = FeedingAnimation.remaining(stack, (LivingEntity) (Object) this, ragrevival$animationTicks++);
        // Cancel the entire pipeline: the NeoForge Tick/Finish events cannot safely undo effects.
        ci.cancel();
    }

    @Inject(method = "completeUsingItem", at = @At("HEAD"), cancellable = true)
    private void ragrevival$neverFinishVanillaFood(CallbackInfo ci) {
        if (ragrevival$isFeeding()) ci.cancel();
    }

    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
    private void ragrevival$releaseWithoutItemEffects(CallbackInfo ci) {
        if (!ragrevival$isFeeding()) return;
        ((LivingEntity) (Object) this).stopUsingItem();
        ci.cancel();
    }

    @Redirect(method = "stopUsingItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;onStopUsing(Lnet/minecraft/world/entity/LivingEntity;I)V"))
    private void ragrevival$skipManagedItemStop(ItemStack stack, LivingEntity entity, int remaining) {
        if (!ragrevival$isFeeding()) stack.onStopUsing(entity, remaining);
    }

    @Inject(method = "stopUsingItem", at = @At("TAIL"))
    private void ragrevival$clearManagedUse(CallbackInfo ci) {
        ragrevival$setManagedUse(false);
    }
}
