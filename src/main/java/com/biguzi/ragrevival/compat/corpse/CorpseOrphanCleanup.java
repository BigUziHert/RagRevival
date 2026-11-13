package com.biguzi.ragrevival.compat.corpse;

import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import com.biguzi.ragrevival.ragdoll.NativeRagdollCompat;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Reclaims saved physics after an interrupted shutdown, even if Corpse was since uninstalled. */
public final class CorpseOrphanCleanup {
    public static final String PART_MARKER = "ragrevival:corpse_part";
    public static final String ROOT_MARKER = "ragrevival:corpse_root";
    public static final String OWNER_MARKER = "ragrevival:corpse_owner";
    private static final int LOAD_GRACE_TICKS = 40;
    private static final Map<PartKey, Candidate> CANDIDATES = new HashMap<>();

    private CorpseOrphanCleanup() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CorpseOrphanCleanup::tick);
        NeoForge.EVENT_BUS.addListener(CorpseOrphanCleanup::stopped);
    }

    /** Called once after a marked part has a real server sublevel, not while NBT is being decoded. */
    public static void discover(RagdollPartBlockEntity part, ServerSubLevel subLevel) {
        NativeRagdollCompat.migrateMarkedPart(part, subLevel);
        CompoundTag data = part.getPersistentData();
        if (!data.getBoolean(PART_MARKER) || !data.hasUUID(ROOT_MARKER) || !data.hasUUID(OWNER_MARKER)) return;
        ServerLevel level = subLevel.getLevel();
        CANDIDATES.putIfAbsent(new PartKey(level, subLevel.getUniqueId(), part.getBlockPos().immutable(),
                        NativeRagdollCompat.limbId(part, subLevel)),
                new Candidate(data.getUUID(ROOT_MARKER), data.getUUID(OWNER_MARKER), level.getGameTime()));
    }

    private static void tick(ServerTickEvent.Post event) {
        if (CANDIDATES.isEmpty() || event.getServer().getTickCount() % 20 != 0) return;
        for (var entry : List.copyOf(CANDIDATES.entrySet())) {
            PartKey key = entry.getKey();
            Candidate candidate = entry.getValue();
            SubLevelContainer container = SubLevelContainer.getContainer(key.level());
            if (container == null || !(container.getSubLevel(key.part()) instanceof ServerSubLevel part)
                    || part.isRemoved()) {
                CANDIDATES.remove(key);
                continue;
            }
            if (key.level().getGameTime() - candidate.discoveredAt() < LOAD_GRACE_TICKS
                    || CorpseCompat.ownsRoot(candidate.root())) continue;
            if (!(key.level().getBlockEntity(key.position()) instanceof RagdollPartBlockEntity block)
                    || !matches(block, candidate)) {
                CANDIDATES.remove(key);
                continue;
            }

            // Native cleanup also removes restored constraints, session state, and saved topology.
            // The linked map may not yet be restored when only some limbs have loaded.
            ServerSubLevel root = NativeRagdollCompat.root(key.level(), candidate.root());
            RagdollPartBlockEntity rootBlock = NativeRagdollCompat.block(key.level(), candidate.root());
            if (root != null && !root.isRemoved() && rootBlock != null && matches(rootBlock, candidate)) {
                SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(key.level());
                if (physics != null) {
                    NativeRagdollCompat.withOwner(candidate.root(), () -> NativeRagdollCompat.expireImmediate(
                            physics, key.level(), root, "api orphan corpse cleanup"));
                }
            }
            // Each marked limb is independently discovered. This handles partial loads and
            // orphan limbs whose root was removed before their chunks became available.
            if (!part.isRemoved()) NativeRagdollCompat.removePart(key.level(), part, key.limb());
            NativeRagdollCompat.removeSaved(key.level(), candidate.root());
            CANDIDATES.remove(key);
        }
    }

    private static boolean matches(RagdollPartBlockEntity part, Candidate candidate) {
        CompoundTag data = part.getPersistentData();
        return data.getBoolean(PART_MARKER) && data.hasUUID(ROOT_MARKER) && data.hasUUID(OWNER_MARKER)
                && candidate.root().equals(data.getUUID(ROOT_MARKER))
                && candidate.owner().equals(data.getUUID(OWNER_MARKER));
    }

    private static void stopped(ServerStoppedEvent event) {
        CANDIDATES.clear();
        NativeRagdollCompat.reset();
    }

    private record PartKey(ServerLevel level, UUID part, BlockPos position, UUID limb) {}
    private record Candidate(UUID root, UUID owner, long discoveredAt) {}
}
