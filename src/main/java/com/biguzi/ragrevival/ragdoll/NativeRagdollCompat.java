package com.biguzi.ragrevival.ragdoll;

import dev.leo.sableplayerragdoll.api.PlayerlessRagdollSession;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import dev.leo.sableplayerragdoll.physics.RagdollAssemblyHelper;
import dev.leo.sableplayerragdoll.physics.RagdollExpireHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.block.entity.BlockEntity;
import dev.ryanhcode.sable.Sable;

/** Version boundary for the 0.8 move from world SavedData to per-block ownership. */
public final class NativeRagdollCompat {
    private static final String PHYSICS = "dev.leo.sableplayerragdoll.physics.";
    private static final Api API = new Api();
    private static final Map<ServerLevel, Index> INDICES = new HashMap<>();
    private static final Map<ServerLevel, java.util.Set<UUID>> REMOVED = new HashMap<>();

    private NativeRagdollCompat() {}

    public static boolean blockIdentityStorage() { return API.modern; }
    public static void withOwner(UUID owner, Runnable action) {
        if (API.modern) invoke(API.withOwner, null, owner, action);
        else action.run();
    }
    public static <T> T withOwnerResult(UUID owner, java.util.function.Supplier<T> action) {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        withOwner(owner, () -> result.set(action.get()));
        return result.get();
    }
    public static UUID ownerForPlayer(ServerPlayer player, ServerSubLevel root) {
        UUID owner = API.modern ? invoke(API.ownerForPlayer, null, player.serverLevel(), player.getUUID()) : null;
        return owner == null ? sessionId(root) : owner;
    }
    public static PlayerlessRagdollSession retainIdentity(PlayerlessRagdollSession session, UUID owner) {
        if (session == null || !API.modern && owner.equals(session.id())) return session;
        return new PlayerlessRagdollSession() {
            @Override public UUID id() { return owner; }
            @Override public net.minecraft.world.phys.Vec3 currentVelocity() { return session.currentVelocity(); }
            @Override public long elapsedTicks() { return session.elapsedTicks(); }
            @Override public void applyWailing(dev.leo.sableplayerragdoll.api.RagdollWailingOptions options) {
                withOwner(owner, () -> session.applyWailing(options));
            }
            @Override public void stopWailing() { withOwner(owner, session::stopWailing); }
            @Override public void release() { withOwner(owner, session::release); }
        };
    }

    public record Limb(UUID id, ServerSubLevel subLevel, RagdollPartBlockEntity block) {}

    public static UUID limbId(RagdollPartBlockEntity block, ServerSubLevel physical) {
        UUID id = API.modern ? invoke(API.identityLimb, invoke(API.identity, block)) : null;
        return id == null ? physical.getUniqueId() : id;
    }

    public static void invalidate(ServerLevel level) { INDICES.remove(level); }
    public static void reset() { INDICES.clear(); REMOVED.clear(); }
    public static boolean wasRemoved(ServerLevel level, UUID root) {
        return REMOVED.getOrDefault(level, java.util.Set.of()).contains(root);
    }
    private static void removed(ServerLevel level, UUID root) {
        REMOVED.computeIfAbsent(level, ignored -> new HashSet<>()).add(root);
        invalidate(level);
    }

    /** The block, logical limb and containing physical body remain distinct after relocation. */
    public static List<Limb> limbs(ServerLevel level, UUID owner) {
        if (API.modern) return List.copyOf(index(level).owners.getOrDefault(owner, Map.of()).values());
        List<Limb> result = new ArrayList<>();
        List<UUID> ids = invoke(API.linked, null, owner);
        for (UUID id : ids) {
            ServerSubLevel part = part(level, id);
            if (part != null && part.getPlot() != null
                    && level.getBlockEntity(part.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block)
                result.add(new Limb(id, part, block));
        }
        return result;
    }

    public static RagdollPartBlockEntity block(ServerLevel level, UUID limb) {
        if (API.modern) {
            Limb found = index(level).limbs.get(limb);
            if (found != null) return found.block();
        }
        ServerSubLevel part = part(level, limb);
        return part != null && part.getPlot() != null
                && level.getBlockEntity(part.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block ? block : null;
    }

    /** Physical sublevel IDs, suitable for rendering, collision and physics handles. */
    public static List<UUID> linkedParts(ServerLevel level, UUID root) {
        if (!API.modern) return List.copyOf(invoke(API.linked, null, root));
        List<UUID> result = new ArrayList<>();
        for (Limb limb : limbs(level, root)) {
            ServerSubLevel part = limb.subLevel();
            if (!part.isRemoved() && !result.contains(part.getUniqueId())) result.add(part.getUniqueId());
        }
        return result;
    }

    /** Logical limb IDs; 0.7 IDs also happen to be the original physical sublevel IDs. */
    public static Map<BodyPart, UUID> savedParts(ServerLevel level, UUID root) {
        if (!API.modern) return Map.copyOf(invoke(API.savedParts, invoke(API.savedGet, null, level), root));
        EnumMap<BodyPart, UUID> result = new EnumMap<>(BodyPart.class);
        for (Limb limb : limbs(level, root)) result.put(limb.block().bodyPart(), limb.id());
        // Once any modern identities exist, they are authoritative (including severing).
        // Otherwise the old record proves a saved body exists before its plot chunks load.
        if (result.isEmpty()) result.putAll(legacy(level).parts(root));
        return result;
    }

    public static ServerSubLevel part(ServerLevel level, UUID limb) {
        if (API.modern) {
            Limb resolved = index(level).limbs.get(limb);
            if (resolved != null) return resolved.subLevel();
        }
        var container = SubLevelContainer.getContainer(level);
        return container != null && container.getSubLevel(limb) instanceof ServerSubLevel part ? part : null;
    }

    public static ServerSubLevel root(ServerLevel level, UUID owner) {
        if (API.modern) {
            Limb resolved = index(level).limbs.get(owner);
            if (resolved != null) return resolved.subLevel();
        }
        return part(level, owner);
    }

    public static UUID sessionId(ServerSubLevel root) {
        return API.modern ? invoke(API.sessionId, null, root) : root.getUniqueId();
    }

    public static CompoundTag sessionData(ServerSubLevel root) {
        CompoundTag tag = API.modern ? invoke(API.sessionData, null, root) : root.getUserDataTag();
        return tag == null ? new CompoundTag() : tag;
    }

    public static void removeSaved(ServerLevel level, UUID root) {
        if (!API.modern) invoke(API.savedRemove, invoke(API.savedGet, null, level), root);
        else {
            invoke(API.clearRuntime, null, root);
            legacy(level).remove(root);
        }
        removed(level, root);
    }

    public static void expire(ServerLevel level, ServerSubLevel root, String reason) {
        if (API.modern) invoke(API.expire, null, level, root, reason);
        else invoke(API.expire, null, SubLevelPhysicsSystem.get(level), level, root, reason);
    }

    public static void expireImmediate(SubLevelPhysicsSystem physics, ServerLevel level, ServerSubLevel root, String reason) {
        expireImmediate(physics, level, root, reason, false);
    }

    public static void expireImmediate(SubLevelPhysicsSystem physics, ServerLevel level, ServerSubLevel root,
            String reason, boolean forceTeleport) {
        if (!API.modern) {
            invoke(API.immediate, null, physics, level, root, reason, forceTeleport);
            removed(level, root.getUniqueId());
            return;
        }
        UUID owner = sessionId(root);
        var limbs = new HashSet<>(savedParts(level, owner).values());
        if (limbs.isEmpty()) limbs.add(owner);
        // New expire already unseats, world-teleports, restores visibility and emits end events.
        // It only marks the body, however: immediate callers must also remove its own blocks.
        expire(level, root, reason);
        invoke(API.clearRuntime, null, owner);
        invoke(API.removeLimbs, null, level, limbs);
        legacy(level).remove(owner);
        removed(level, owner);
    }

    public static void release(ServerLevel level, PlayerlessRagdollSession session) {
        ServerSubLevel root = root(level, session.id());
        if (root != null && !root.isRemoved()) {
            withOwner(session.id(), () -> expireImmediate(SubLevelPhysicsSystem.get(level), level, root, "api corpse release"));
        } else session.release();
    }

    /** Removes only a ragdoll block on 0.8, never an unrelated vessel merged into its plot. */
    public static void removePart(ServerLevel level, ServerSubLevel part, UUID limb) {
        if (API.modern) invoke(API.removeLimbs, null, level, java.util.Set.of(limb));
        else SubLevelContainer.getContainer(level).removeSubLevel(part, SubLevelRemovalReason.REMOVED);
        invalidate(level);
    }

    /** Called before native physics restoration; old marked bodies keep their exact UUIDs/poses. */
    public static void migrateMarkedPart(RagdollPartBlockEntity block, ServerSubLevel part) {
        if (!API.modern) return;
        CompoundTag marker = block.getPersistentData();
        if (!marker.getBoolean("ragrevival:corpse_part") || !marker.hasUUID("ragrevival:corpse_root")) return;
        Object identity = invoke(API.identity, block);
        if (Boolean.TRUE.equals(invoke(API.identityKnown, identity))) return;
        UUID owner = marker.getUUID("ragrevival:corpse_root");
        UUID limb = part.getUniqueId();
        LegacyData saved = legacy(part.getLevel());
        CompoundTag record = saved.record(owner);
        UUID expected = saved.parts(owner).get(block.bodyPart());
        // A partially available old record must never assign another body's limb to this owner.
        if (expected != null && !expected.equals(limb)) return;
        invoke(API.identityAssign, identity, owner, limb, block.bodyPart().getSerializedName());
        invoke(API.identityParent, identity, owner.equals(limb) ? null : owner);
        invoke(API.identityAssembly, identity, record);
        CompoundTag session = new CompoundTag();
        session.putBoolean("sable_player_ragdoll", true);
        session.putBoolean("nonPlayer", true);
        session.putString("despawnMode", "NEVER");
        session.putLong("startTick", part.getLevel().getGameTime());
        invoke(API.identitySession, identity, session);
        invoke(API.identityLifetime, identity, part.getLevel().getGameTime(), "PERMANENT", null, -1L);
        block.setChanged();
        invalidate(part.getLevel());
    }

    /** One native block scan per level/tick, not six global scans for every corpse query. */
    private static Index index(ServerLevel level) {
        Index existing = INDICES.get(level);
        if (existing != null && existing.tick == level.getGameTime()) return existing;
        Index current = new Index(level.getGameTime());
        List<BlockEntity> blocks = invoke(API.loadedBlocks, null, level);
        for (BlockEntity candidate : blocks) {
            if (!(candidate instanceof RagdollPartBlockEntity block) || block.isRemoved()) continue;
            var containing = Sable.HELPER.getContaining(level, block.getBlockPos());
            if (!(containing instanceof ServerSubLevel physical) || physical.isRemoved()) continue;
            migrateMarkedPart(block, physical);
            Object identity = invoke(API.identity, block);
            UUID owner = invoke(API.identityOwner, identity);
            UUID id = invoke(API.identityLimb, identity);
            if (owner == null || id == null) continue;
            Limb limb = new Limb(id, physical, block);
            current.limbs.put(id, limb);
            current.owners.computeIfAbsent(owner, ignored -> new EnumMap<>(BodyPart.class)).put(block.bodyPart(), limb);
        }
        INDICES.put(level, current);
        return current;
    }

    private static final class Index {
        final long tick;
        final Map<UUID, Limb> limbs = new HashMap<>();
        final Map<UUID, Map<BodyPart, Limb>> owners = new HashMap<>();
        Index(long tick) { this.tick = tick; }
    }

    private static LegacyData legacy(ServerLevel level) {
        LegacyData data = level.getDataStorage().get(LegacyData.FACTORY, "sable_player_ragdoll");
        return data == null ? LegacyData.EMPTY : data;
    }

    private static final class LegacyData extends SavedData {
        static final LegacyData EMPTY = new LegacyData(new CompoundTag());
        static final SavedData.Factory<LegacyData> FACTORY = new SavedData.Factory<>(
                () -> new LegacyData(new CompoundTag()), (tag, registries) -> new LegacyData(tag));
        private final CompoundTag data;
        LegacyData(CompoundTag data) { this.data = data.copy(); }
        CompoundTag record(UUID root) {
            ListTag records = data.getList("Ragdolls", Tag.TAG_COMPOUND);
            for (int i = 0; i < records.size(); i++) {
                CompoundTag record = records.getCompound(i);
                if (record.hasUUID("Root") && root.equals(record.getUUID("Root"))) return record.copy();
            }
            return new CompoundTag();
        }
        Map<BodyPart, UUID> parts(UUID root) {
            EnumMap<BodyPart, UUID> result = new EnumMap<>(BodyPart.class);
            ListTag parts = record(root).getList("Parts", Tag.TAG_COMPOUND);
            for (int i = 0; i < parts.size(); i++) {
                CompoundTag part = parts.getCompound(i);
                if (part.hasUUID("SubLevel")) result.put(BodyPart.byName(part.getString("BodyPart")), part.getUUID("SubLevel"));
            }
            return result;
        }
        void remove(UUID root) {
            ListTag records = data.getList("Ragdolls", Tag.TAG_COMPOUND);
            if (records.removeIf(tag -> tag instanceof CompoundTag entry && entry.hasUUID("Root") && root.equals(entry.getUUID("Root")))) setDirty();
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) { return data.copy(); }
    }

    /** Resolve once: no reflective class/member searches on physics or render ticks. */
    private static final class Api {
        final boolean modern;
        Method linked, savedGet, savedParts, savedRemove, immediate, expire;
        Method part, root, sessionId, sessionData, clearRuntime, removeLimbs;
        Method identity, identityKnown, identityAssign, identityParent, identityAssembly, identitySession, identityLifetime;
        Method loadedBlocks, identityOwner, identityLimb, withOwner, ownerForPlayer;
        Api() {
            try {
                Class<?> oldData;
                try { oldData = Class.forName(PHYSICS + "RagdollSavedData"); }
                catch (ClassNotFoundException removed) { oldData = null; }
                modern = oldData == null;
                if (!modern) {
                    linked = RagdollAssemblyHelper.class.getMethod("linkedParts", UUID.class);
                    savedGet = oldData.getMethod("get", ServerLevel.class);
                    savedParts = oldData.getMethod("ragdoll", UUID.class);
                    savedRemove = oldData.getMethod("removeRagdoll", UUID.class);
                    immediate = RagdollExpireHelper.class.getMethod("expireImmediate", SubLevelPhysicsSystem.class, ServerLevel.class, ServerSubLevel.class, String.class, boolean.class);
                    expire = RagdollExpireHelper.class.getMethod("expire", SubLevelPhysicsSystem.class, ServerLevel.class, ServerSubLevel.class, String.class);
                } else {
                    Class<?> ownership = Class.forName(PHYSICS + "RagdollBlockOwnership");
                    Class<?> identityClass = Class.forName(PHYSICS + "RagdollBlockIdentity");
                    savedParts = Class.forName(PHYSICS + "RagdollAssemblyData").getMethod("parts", ServerLevel.class, UUID.class);
                    part = ownership.getMethod("partForLimb", ServerLevel.class, UUID.class);
                    root = ownership.getMethod("root", ServerLevel.class, UUID.class);
                    sessionId = ownership.getMethod("sessionId", ServerSubLevel.class);
                    sessionData = ownership.getMethod("session", ServerSubLevel.class);
                    loadedBlocks = ownership.getMethod("loadedBlocks", ServerLevel.class);
                    withOwner = ownership.getMethod("withOwner", UUID.class, Runnable.class);
                    ownerForPlayer = ownership.getMethod("ownerForPlayer", ServerLevel.class, UUID.class);
                    clearRuntime = RagdollAssemblyHelper.class.getMethod("clearRuntime", UUID.class);
                    removeLimbs = Class.forName(PHYSICS + "RagdollCleanup").getMethod("removeLimbs", ServerLevel.class, java.util.Set.class);
                    expire = RagdollExpireHelper.class.getMethod("expire", ServerLevel.class, ServerSubLevel.class, String.class);
                    identity = RagdollPartBlockEntity.class.getMethod("ragdollIdentity");
                    identityKnown = identityClass.getMethod("known");
                    identityOwner = identityClass.getMethod("owner");
                    identityLimb = identityClass.getMethod("limb");
                    identityAssign = identityClass.getMethod("assign", UUID.class, UUID.class, String.class);
                    identityParent = identityClass.getMethod("parent", UUID.class);
                    identityAssembly = identityClass.getMethod("assembly", CompoundTag.class);
                    identitySession = identityClass.getMethod("session", CompoundTag.class);
                    identityLifetime = identityClass.getMethod("lifetime", long.class, String.class, UUID.class, long.class);
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported Sable Ragdolls lifecycle API", failure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Method method, Object receiver, Object... args) {
        try { return (T) method.invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Sable Ragdolls lifecycle call failed: " + method.getName(), cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot call Sable Ragdolls lifecycle API: " + method.getName(), failure);
        }
    }
}
