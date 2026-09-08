package com.biguzi.ragrevival.mixin;

import com.biguzi.ragrevival.client.RevivalClient;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollGrabClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sable polls raw Use input independently of our canceled interaction event. Avoid a second grip. */
@Mixin(value = RagdollGrabClient.class, remap = false)
public abstract class RagdollGrabInputMixin {
    @Shadow private static BlockPos activePos;
    @Shadow private static void stopGrab() { throw new AssertionError(); }

    @Inject(method = "onClientTick", at = @At("HEAD"))
    private static void ragrevival$yieldGripOnTick(ClientTickEvent.Post event, CallbackInfo ci) {
        ragrevival$yieldNativeGrip();
    }

    @Inject(method = "onScroll", at = @At("HEAD"))
    private static void ragrevival$yieldGripBeforeScroll(InputEvent.MouseScrollingEvent event, CallbackInfo ci) {
        ragrevival$yieldNativeGrip();
    }

    @Unique
    private static void ragrevival$yieldNativeGrip() {
        if (activePos == null) return;
        if (RevivalClient.isDownedPart(activePos)) {
            // The server already released old generic grips on downing. Do not send a native
            // release packet: it could release the valid crouch-drag now owning this same limb.
            RagdollGrabClient.clearActive();
        } else if (RevivalClient.hasActiveInteraction()) {
            // A held use press can move from an ordinary body onto a downed player. Release
            // that ordinary grip before our rescue owns input, including the first wheel event.
            stopGrab();
        }
    }

    @Inject(method = "targetedPart", at = @At("RETURN"), cancellable = true)
    private static void ragrevival$reserveDownedInteraction(Minecraft mc, CallbackInfoReturnable<BlockPos> cir) {
        BlockPos pos = cir.getReturnValue();
        if (pos != null && (RevivalClient.hasActiveInteraction() || RevivalClient.isDownedPart(pos))) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "isGrabbing", at = @At("RETURN"), cancellable = true)
    private static void ragrevival$keepDragCollisionRules(CallbackInfoReturnable<Boolean> cir) {
        // Native isGrabbing supplies Sable's local collision rules; our grip needs them too,
        // including after right-click is released while crouch remains held.
        if (RevivalClient.hasActiveDrag()) cir.setReturnValue(true);
    }
}
