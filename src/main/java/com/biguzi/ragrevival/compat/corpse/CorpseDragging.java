package com.biguzi.ragrevival.compat.corpse;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import de.maxhenkel.corpse.Main;
import de.maxhenkel.corpse.entities.CorpseEntity;
import dev.leo.sableplayerragdoll.RagdollGrabCallbacks;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.config.RagdollSettings;
import dev.leo.sableplayerragdoll.physics.RagdollRegistry;
import dev.leo.sableplayerragdoll.physics.RagdollSessionManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Permission and lifetime checks around Sable's unchanged native grab constraints and packets. */
public final class CorpseDragging {
    private static final Map<UUID, Grip> GRIPS = new HashMap<>();

    private CorpseDragging() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CorpseDragging::tick);
        NeoForge.EVENT_BUS.addListener(CorpseDragging::logout);
        NeoForge.EVENT_BUS.addListener(CorpseDragging::dimensionChanged);
        NeoForge.EVENT_BUS.addListener(CorpseDragging::death);
        NeoForge.EVENT_BUS.addListener(CorpseDragging::stopped);
    }

    /** True means this is our packet and the native unvalidated handler must not run. */
    public static boolean handle(ServerPlayer player, BlockPos pos, boolean release) {
        Grip previous = GRIPS.get(player.getUUID());
        if (release && previous != null && previous.part.getBlockPos().equals(pos)) {
            release(player.getUUID(), player);
            return true;
        }
        ServerLevel level = player.serverLevel();
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof RagdollPartBlockEntity part)
                || !part.getPersistentData().getBoolean(CorpseOrphanCleanup.PART_MARKER)) {
            // Native clients keep one grip. Also enforce that limit when switching to an ordinary ragdoll.
            if (!release && previous != null) release(player.getUUID(), player);
            return false;
        }
        if (release) {
            part.stopGrab(player.getUUID());
            RagdollGrabCallbacks.notifyReleased(player);
            return true;
        }
        if (previous != null && previous.part == part) return true;
        release(player.getUUID(), player);
        CompoundTag data = part.getPersistentData();
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (!data.hasUUID(CorpseOrphanCleanup.OWNER_MARKER) || !data.hasUUID(CorpseOrphanCleanup.ROOT_MARKER)
                || subLevel == null
                || !(level.getEntity(data.getUUID(CorpseOrphanCleanup.OWNER_MARKER)) instanceof CorpseEntity corpse)
                || !CorpseCompat.ownsPart(corpse, data.getUUID(CorpseOrphanCleanup.ROOT_MARKER), subLevel.getUniqueId())
                || !mayDrag(player, corpse) || RagdollRegistry.isGrabDisabledAt(level, pos)
                || !canReachPart(player, part, subLevel)) {
            RagdollGrabCallbacks.notifyReleased(player);
            return true;
        }
        GRIPS.put(player.getUUID(), new Grip(corpse, part, subLevel, data.getUUID(CorpseOrphanCleanup.ROOT_MARKER)));
        part.startGrab(player.getUUID());
        RagdollGrabCallbacks.notifyGrabbed(player);
        return true;
    }

    public static boolean mayAccess(ServerPlayer player, CorpseEntity corpse) {
        return !Main.SERVER_CONFIG.onlyOwnerAccess.get()
                || player.hasPermissions(player.server.getOperatorUserPermissionLevel())
                || corpse.getCorpseUUID().isEmpty() || corpse.getCorpseUUID().get().equals(player.getUUID())
                || Main.SERVER_CONFIG.skeletonAccess.get() && corpse.isSkeleton();
    }

    public static boolean mayDrag(ServerPlayer player, CorpseEntity corpse) {
        return !corpse.isRemoved() && player.level() == corpse.level() && player.isAlive()
                && !player.isSpectator() && !player.isPassenger() && !player.isShiftKeyDown()
                && player.getMainHandItem().isEmpty() && !DownedManager.isDowned(player)
                && RagdollSettings.grabEnabled() && mayAccess(player, corpse)
                && RagdollSessionManager.activeRagdollForPlayer(player.serverLevel(), player.getUUID()) == null;
    }

    /** The proxy's broad box cannot grant interaction through a wall or past limb reach. */
    public static boolean canReachCorpse(ServerPlayer player, CorpseEntity corpse) {
        if (!player.isAlive() || player.isSpectator() || DownedManager.isDowned(player)
                || player.level() != corpse.level() || corpse.isRemoved()) return false;
        CompoundTag pose = ((CorpsePoseAccess) corpse).getRagRevivalPose();
        if (!CorpsePose.valid(pose)) return false;
        Vec3 eye = RagdollBridge.worldEyePosition(player);
        var parts = pose.getList("parts", Tag.TAG_COMPOUND);
        for (int i = 0; i < parts.size(); i++) {
            CompoundTag limb = parts.getCompound(i);
            Quaterniond rotation = CorpsePose.rotation(limb);
            Vec3 center = corpse.position().add(CorpsePose.offset(limb));
            Vec3 delta = eye.subtract(center);
            Vector3d localEye = new Quaterniond(rotation).conjugate().transform(new Vector3d(delta.x, delta.y, delta.z));
            Vec3 half = CorpsePose.halfExtents(i);
            Vector3d nearest = rotation.transform(new Vector3d(Math.clamp(localEye.x, -half.x, half.x),
                    Math.clamp(localEye.y, -half.y, half.y), Math.clamp(localEye.z, -half.z, half.z)));
            Vec3 point = center.add(nearest.x, nearest.y, nearest.z);
            if (reachable(player, corpse.getUUID(), eye, point, player.entityInteractionRange())) return true;
        }
        return false;
    }

    private static boolean canReachPart(ServerPlayer player, RagdollPartBlockEntity part, SubLevel subLevel) {
        Vec3 eye = RagdollBridge.worldEyePosition(player);
        Vec3 localEye = subLevel.logicalPose().transformPositionInverse(eye).subtract(Vec3.atCenterOf(part.getBlockPos()));
        Vec3 half = CorpsePose.halfExtents(part.bodyPart().ordinal());
        Vec3 nearest = new Vec3(Math.clamp(localEye.x, -half.x, half.x), Math.clamp(localEye.y, -half.y, half.y),
                Math.clamp(localEye.z, -half.z, half.z));
        Vec3 point = subLevel.logicalPose().transformPosition(Vec3.atCenterOf(part.getBlockPos()).add(nearest));
        return reachable(player, part.getPersistentData().getUUID(CorpseOrphanCleanup.OWNER_MARKER),
                eye, point, player.blockInteractionRange());
    }

    private static boolean reachable(ServerPlayer player, UUID corpseId, Vec3 eye, Vec3 point, double reach) {
        if (eye.distanceToSqr(point) > reach * reach) return false;
        var hit = player.level().clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return true;
        BlockPos pos = hit.getBlockPos();
        if (!player.level().getBlockState(pos).hasBlockEntity()
                || !(player.level().getBlockEntity(pos) instanceof RagdollPartBlockEntity part)) return false;
        CompoundTag data = part.getPersistentData();
        return data.getBoolean(CorpseOrphanCleanup.PART_MARKER) && data.hasUUID(CorpseOrphanCleanup.OWNER_MARKER)
                && corpseId.equals(data.getUUID(CorpseOrphanCleanup.OWNER_MARKER));
    }

    public static void releaseCorpse(UUID corpseId) {
        for (var entry : List.copyOf(GRIPS.entrySet())) {
            if (entry.getValue().corpse.getUUID().equals(corpseId)) release(entry.getKey(), null);
        }
    }

    /** Native distance/death/unload cleanup already removed its constraint. Drop only the matching lease. */
    public static void nativeReleased(RagdollPartBlockEntity part, UUID playerId) {
        Grip grip = GRIPS.get(playerId);
        if (grip != null && grip.part == part) GRIPS.remove(playerId);
    }

    private static void release(UUID playerId, ServerPlayer player) {
        Grip grip = GRIPS.remove(playerId);
        if (grip == null) return;
        grip.part.stopGrab(playerId);
        if (player == null) player = ((ServerLevel) grip.corpse.level()).getServer().getPlayerList().getPlayer(playerId);
        if (player != null) RagdollGrabCallbacks.notifyReleased(player);
    }

    private static void tick(ServerTickEvent.Post event) {
        for (var entry : List.copyOf(GRIPS.entrySet())) {
            Grip grip = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || grip.part.isRemoved() || grip.subLevel.isRemoved()
                    || !CorpseCompat.ownsPart(grip.corpse, grip.rootId, grip.subLevel.getUniqueId())
                    || !mayDrag(player, grip.corpse)) {
                // Once accepted, Sable owns the spring and its native distance-based release.
                release(entry.getKey(), player);
            }
        }
    }

    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) release(player.getUUID(), player);
    }

    private static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) release(player.getUUID(), player);
    }

    private static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) release(player.getUUID(), player);
    }

    private static void stopped(ServerStoppedEvent event) {
        GRIPS.clear();
    }

    private record Grip(CorpseEntity corpse, RagdollPartBlockEntity part, SubLevel subLevel, UUID rootId) {}
}
