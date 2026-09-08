package com.biguzi.ragrevival.ragdoll.mixin;

import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "dev.leo.sableplayerragdoll.api.RagdollAsyncPoseRequests$PendingLaunch", remap = false)
public interface PendingLaunchAccessor {
    @Accessor("playerId") UUID ragrevival$playerId();
}
