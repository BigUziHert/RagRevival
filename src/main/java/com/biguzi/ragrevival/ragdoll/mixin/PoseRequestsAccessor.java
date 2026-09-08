package com.biguzi.ragrevival.ragdoll.mixin;

import dev.leo.sableplayerragdoll.api.RagdollAsyncPoseRequests;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Version-pinned access to cancellation missing from the upstream public API. */
@Mixin(value = RagdollAsyncPoseRequests.class, remap = false)
public interface PoseRequestsAccessor {
    @Accessor("PENDING")
    static Map<Long, Object> ragrevival$pending() { throw new AssertionError("Mixin not applied"); }

    @Accessor("PENDING_PLAYERS")
    static Set<UUID> ragrevival$pendingPlayers() { throw new AssertionError("Mixin not applied"); }
}
