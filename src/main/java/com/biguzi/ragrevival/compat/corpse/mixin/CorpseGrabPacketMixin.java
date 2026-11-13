package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.CorpseDragging;
import dev.leo.sableplayerragdoll.neoforge.network.RagdollGrabPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RagdollGrabPacket.class, remap = false)
public abstract class CorpseGrabPacketMixin {
    // The lambda runs through IPayloadContext.enqueueWork, so all world and lease access is on the server thread.
    @Inject(method = "lambda$handle$0", at = @At("HEAD"), cancellable = true)
    private static void ragrevival$validateCorpseGrip(IPayloadContext context, RagdollGrabPacket packet, CallbackInfo ci) {
        if (context.player() instanceof ServerPlayer player && CorpseDragging.handle(player, packet.pos(), packet.release())) {
            ci.cancel();
        }
    }
}
