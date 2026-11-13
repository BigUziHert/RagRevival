package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.client.RevivalClient;
import com.biguzi.ragrevival.compat.corpse.CorpseOrphanCleanup;
import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import de.maxhenkel.corpse.entities.CorpseEntity;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollGrabClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RagdollGrabClient.class, remap = false)
public abstract class CorpseGrabClientMixin {
    @Shadow private static BlockPos activePos;
    @Shadow private static void stopGrab() { throw new AssertionError(); }
    @Unique private static boolean ragrevival$corpseGrip;

    @Inject(method = "onClientTick", at = @At("HEAD"), cancellable = true)
    private static void ragrevival$releaseForLoot(ClientTickEvent.Post event, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (activePos == null) {
            ragrevival$corpseGrip = false;
            return;
        }
        if (!ragrevival$corpseGrip && ragrevival$part(mc, activePos) == null) return;
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            RagdollGrabClient.clearActive();
            ragrevival$corpseGrip = false;
            ci.cancel();
        } else if (mc.player.isShiftKeyDown() || mc.screen != null) {
            stopGrab();
            ragrevival$corpseGrip = false;
            ci.cancel();
        }
    }

    @Inject(method = "targetedPart", at = @At("RETURN"), cancellable = true)
    private static void ragrevival$targetActualLimb(Minecraft mc, CallbackInfoReturnable<BlockPos> cir) {
        BlockPos target = cir.getReturnValue();
        RagdollPartBlockEntity part = target == null ? null : ragrevival$part(mc, target);
        if (part == null && target == null && mc.player != null && mc.level != null
                && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof CorpseEntity corpse) {
            Vec3 eye = RagdollBridge.worldEyePosition(mc.player);
            Vec3 end = eye.add(mc.player.getLookAngle().scale(mc.player.blockInteractionRange()));
            // Sable's block trace already selects the nearest visible oriented limb, including moving sublevels.
            var blockHit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (blockHit.getType() != HitResult.Type.MISS) {
                RagdollPartBlockEntity candidate = ragrevival$part(mc, blockHit.getBlockPos());
                if (candidate != null && candidate.getPersistentData().hasUUID(CorpseOrphanCleanup.OWNER_MARKER)
                        && corpse.getUUID().equals(candidate.getPersistentData().getUUID(CorpseOrphanCleanup.OWNER_MARKER))) {
                    target = blockHit.getBlockPos();
                    part = candidate;
                }
            }
        }
        if (part == null) return;
        if (mc.player == null || mc.screen != null || mc.player.isShiftKeyDown() || RevivalClient.hasActiveInteraction()) {
            cir.setReturnValue(null);
        } else {
            ragrevival$corpseGrip = true;
            cir.setReturnValue(target);
        }
    }

    @Unique private static RagdollPartBlockEntity ragrevival$part(Minecraft mc, BlockPos pos) {
        return mc.level != null && mc.level.getBlockEntity(pos) instanceof RagdollPartBlockEntity part
                && part.getPersistentData().getBoolean(CorpseOrphanCleanup.PART_MARKER) ? part : null;
    }
}
