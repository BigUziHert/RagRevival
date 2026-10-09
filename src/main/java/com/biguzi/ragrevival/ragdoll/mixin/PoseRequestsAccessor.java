package com.biguzi.ragrevival.ragdoll.mixin;

import dev.leo.sableplayerragdoll.api.RagdollAsyncPoseRequests;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to cancellation missing from the upstream public API. */
@Mixin(value = RagdollAsyncPoseRequests.class, remap = false)
public interface PoseRequestsAccessor {
    @Accessor("PENDING")
    static Map<Long, Object> ragrevival$pending() { throw new AssertionError("Mixin not applied"); }
}
