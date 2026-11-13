package com.biguzi.ragrevival.compat.corpse;

import dev.leo.sableplayerragdoll.api.PlayerlessDespawnRule;
import dev.leo.sableplayerragdoll.api.PlayerlessRagdollSession;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.api.RagdollWailingOptions;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import dev.leo.sableplayerragdoll.physics.RagdollAssemblyHelper;
import com.biguzi.ragrevival.ragdoll.NativeRagdollCompat;
import dev.leo.sableplayerragdoll.physics.RagdollMotorEffects;
import dev.leo.sableplayerragdoll.physics.RagdollRegistry;
import dev.leo.sableplayerragdoll.physics.RagdollSessionManager;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Adopts a complete native saved body without spawning overlapping replacement limbs. */
public record RestoredCorpseSession(ServerLevel level, ServerSubLevel root, long startGameTime, UUID owner)
        implements PlayerlessRagdollSession {

    /** Returns null while the existing body's chunks, physics handles, or joints are not ready. */
    public static PlayerlessRagdollSession tryRestore(ServerLevel level, UUID corpseId, UUID rootId) {
        return NativeRagdollCompat.withOwnerResult(rootId, () -> restoreOwned(level, corpseId, rootId));
    }

    private static PlayerlessRagdollSession restoreOwned(ServerLevel level, UUID corpseId, UUID rootId) {
        var container = SubLevelContainer.getContainer(level);
        var physics = SubLevelPhysicsSystem.get(level);
        var saved = NativeRagdollCompat.savedParts(level, rootId);
        if (container == null || physics == null || saved.size() != BodyPart.values().length
                || !rootId.equals(saved.get(BodyPart.TORSO)) || new HashSet<>(saved.values()).size() != saved.size()) return null;
        for (BodyPart bodyPart : BodyPart.values()) {
            UUID id = saved.get(bodyPart);
            ServerSubLevel part = id == null ? null : NativeRagdollCompat.part(level, id);
            if (part == null || part.isRemoved() || part.getPlot() == null) return null;
            RagdollPartBlockEntity block = NativeRagdollCompat.block(level, id);
            if (block == null || !level.hasChunkAt(block.getBlockPos()) || block.bodyPart() != bodyPart) return null;
            CompoundTag data = block.getPersistentData();
            if (!data.getBoolean(CorpseOrphanCleanup.PART_MARKER)
                    || !data.hasUUID(CorpseOrphanCleanup.OWNER_MARKER) || !corpseId.equals(data.getUUID(CorpseOrphanCleanup.OWNER_MARKER))
                    || !data.hasUUID(CorpseOrphanCleanup.ROOT_MARKER) || !rootId.equals(data.getUUID(CorpseOrphanCleanup.ROOT_MARKER))) return null;
            NativeRagdollCompat.migrateMarkedPart(block, part);
            var handle = physics.getPhysicsHandle(part);
            if (handle == null || !handle.isValid()) return null;
        }
        ServerSubLevel root = NativeRagdollCompat.root(level, rootId);
        if (root == null) return null;
        RagdollRegistry.tryRestoreOnLoad(level, root);
        var joints = RagdollAssemblyHelper.joints(rootId);
        long expectedJoints = NativeRagdollCompat.limbs(level, rootId).stream()
                .filter(limb -> !limb.subLevel().getUniqueId().equals(root.getUniqueId())).count();
        if (NativeRagdollCompat.limbs(level, rootId).size() != BodyPart.values().length
                || joints.size() != expectedJoints
                || joints.values().stream().anyMatch(joint -> joint.handle() == null || !joint.handle().isValid())) return null;
        CompoundTag tag = NativeRagdollCompat.sessionData(root);
        long started = tag != null && tag.contains("startTick") ? tag.getLong("startTick") : level.getGameTime();
        RagdollSessionManager.registerRagdoll(root, started, null, true);
        RagdollSessionManager.setPlayerlessDespawnRule(root, PlayerlessDespawnRule.never());
        for (UUID id : NativeRagdollCompat.linkedParts(level, rootId)) RagdollAPI.setGrabDisabled(level, id, false);
        return new RestoredCorpseSession(level, root, started, rootId);
    }

    @Override public UUID id() { return owner; }

    @Override public Vec3 currentVelocity() {
        ServerSubLevel root = NativeRagdollCompat.root(level, owner);
        var physics = SubLevelPhysicsSystem.get(level);
        if (physics == null || root == null || root.isRemoved()) return Vec3.ZERO;
        var handle = physics.getPhysicsHandle(root);
        if (handle == null || !handle.isValid()) return Vec3.ZERO;
        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        return new Vec3(velocity.x / 20.0, velocity.y / 20.0, velocity.z / 20.0);
    }

    @Override public long elapsedTicks() { return level.getGameTime() - startGameTime; }

    @Override public void applyWailing(RagdollWailingOptions options) {
        ServerSubLevel root = NativeRagdollCompat.root(level, owner);
        if (root == null || root.isRemoved()) return;
        RagdollWailingOptions resolved = options == null ? RagdollWailingOptions.defaults() : options;
        NativeRagdollCompat.withOwner(owner, () -> RagdollMotorEffects.applyWailing(level, root,
                resolved.stiffness(), resolved.durationTicks(), resolved.intervalTicks(), resolved.startDelayTicks()));
    }

    @Override public void stopWailing() {
        ServerSubLevel root = NativeRagdollCompat.root(level, owner);
        if (root != null && !root.isRemoved()) NativeRagdollCompat.withOwner(owner, () -> RagdollMotorEffects.stopWailing(root));
    }

    @Override public void release() {
        ServerSubLevel root = NativeRagdollCompat.root(level, owner);
        var physics = SubLevelPhysicsSystem.get(level);
        if (physics != null && root != null && !root.isRemoved())
            NativeRagdollCompat.withOwner(owner, () -> NativeRagdollCompat.expireImmediate(physics, level, root, "api restored corpse release"));
    }
}
