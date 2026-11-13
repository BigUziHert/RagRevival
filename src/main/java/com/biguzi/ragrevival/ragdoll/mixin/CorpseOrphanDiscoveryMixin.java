package com.biguzi.ragrevival.ragdoll.mixin;

import com.biguzi.ragrevival.compat.corpse.CorpseOrphanCleanup;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Physics actors have a level and sublevel here; block-entity NBT loading does not guarantee either. */
@Mixin(value = RagdollPartBlockEntity.class, remap = false)
public abstract class CorpseOrphanDiscoveryMixin {
    @Unique private boolean ragrevival$corpseDiscovered;

    @Inject(method = "sable$physicsTick", at = @At("HEAD"))
    private void ragrevival$discoverCorpse(ServerSubLevel subLevel, RigidBodyHandle handle,
            double timeStep, CallbackInfo ci) {
        if (ragrevival$corpseDiscovered) return;
        ragrevival$corpseDiscovered = true;
        RagdollPartBlockEntity part = (RagdollPartBlockEntity) (Object) this;
        if (!part.getPersistentData().getBoolean(CorpseOrphanCleanup.PART_MARKER)) return;
        CorpseOrphanCleanup.discover(part, subLevel);
    }
}
