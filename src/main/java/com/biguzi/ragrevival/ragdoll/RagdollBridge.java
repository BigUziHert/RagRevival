package com.biguzi.ragrevival.ragdoll;

import com.biguzi.ragrevival.ragdoll.mixin.PendingLaunchAccessor;
import com.biguzi.ragrevival.ragdoll.mixin.PoseRequestsAccessor;
import dev.leo.sableplayerragdoll.RagdollGrabCallbacks;
import dev.leo.sableplayerragdoll.api.DespawnCondition;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.api.RagdollLaunchOptions;
import dev.leo.sableplayerragdoll.api.RagdollLimbOptions;
import dev.leo.sableplayerragdoll.api.RagdollPoseSnapshot;
import dev.leo.sableplayerragdoll.api.RagdollSession;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.entity.RagdollSeatEntity;
import dev.leo.sableplayerragdoll.physics.RagdollAssemblyHelper;
import dev.leo.sableplayerragdoll.physics.RagdollExpireHelper;
import dev.leo.sableplayerragdoll.physics.RagdollSeatingHelper;
import dev.leo.sableplayerragdoll.physics.RagdollSessionManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The version-pinned Sable adapter. Player coordinates may live inside Sable's plotyard. */
public final class RagdollBridge {
    private static final List<DespawnCondition> NEVER_EXPIRE = List.of(DespawnCondition.never());
    private static final RagdollLaunchOptions DOWNED_OPTIONS = RagdollLaunchOptions.builder()
            .autoSeat(true).lockDismount(true).despawnConditions(NEVER_EXPIRE).build();
    private static final Map<UUID, RagdollSession> PENDING = new HashMap<>();
    private static final Map<UUID, Long> LAST_ATTEMPT = new HashMap<>();
    private static final Set<UUID> CONFIGURED_ROOTS = new HashSet<>();
    private static final Map<UUID, Drag> DRAGS = new HashMap<>();
    private static final Map<UUID, Set<UUID>> CLIENT_PARTS = new HashMap<>();

    private RagdollBridge() {}

    /** Converts an existing ordinary ragdoll in place; returns true after the native seat is mounted. */
    public static boolean ensureDowned(ServerPlayer player) {
        cancelPendingPose(player.getUUID());
        ServerSubLevel root = RagdollSessionManager.activeRagdollForPlayer(player.serverLevel(), player.getUUID());
        if (root != null && !RagdollSessionManager.isExpiring(root)) {
            PENDING.remove(player.getUUID());
            // Reactions may have provided its own expiration rules. Replace those without rebuilding the body.
            RagdollSessionManager.setCustomDespawnConditions(root, NEVER_EXPIRE);
            RagdollSessionManager.setDismountLocked(root, true);
            // In 0.7.2 this flag belongs to the clicked limb, not the whole linked body.
            for (UUID partId : RagdollAssemblyHelper.linkedParts(root.getUniqueId())) {
                RagdollAPI.setGrabDisabled(player.serverLevel(), partId, true);
            }
            if (CONFIGURED_ROOTS.add(root.getUniqueId())) {
                // Remove grips started before this ordinary ragdoll became downed.
                for (Part part : parts(player)) {
                    for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
                        part.blockEntity().stopGrab(other.getUUID());
                    }
                }
            }
            if (!(player.getVehicle() instanceof RagdollSeatEntity)) {
                RagdollSeatingHelper.trySeatEntity(player.serverLevel(), player, root);
            }
            return player.getVehicle() instanceof RagdollSeatEntity;
        }
        long tick = player.serverLevel().getGameTime();
        if (tick - LAST_ATTEMPT.getOrDefault(player.getUUID(), tick - 20) < 20) return false;
        LAST_ATTEMPT.put(player.getUUID(), tick);
        // Supplying a server pose avoids an outstanding client pose response recreating a body after revival.
        RagdollSession created = RagdollAPI.launch(player, player.getDeltaMovement().scale(20), DOWNED_OPTIONS,
                new RagdollPoseSnapshot(RagdollLimbOptions.defaults(), player.yBodyRot));
        if (created != null) PENDING.put(player.getUUID(), created);
        return false; // Sable launches and seats on its next physics tick.
    }

    /** The caller must clear its downed marker before invoking this for a successful revival/death. */
    public static void release(ServerPlayer player) {
        cancelPendingPose(player.getUUID());
        stopDrag(player);
        for (Drag drag : List.copyOf(DRAGS.values())) {
            if (drag.targetId().equals(player.getUUID())) stopDrag(drag.rescuer());
        }
        RagdollSession pending = PENDING.remove(player.getUUID());
        RagdollSession session = RagdollAPI.activeSession(player);
        ServerSubLevel root = RagdollSessionManager.activeRagdollForPlayer(player.serverLevel(), player.getUUID());
        if (root != null) CONFIGURED_ROOTS.remove(root.getUniqueId());
        if (root != null && SubLevelPhysicsSystem.get(player.serverLevel()) != null) {
            // Explicit world placement avoids retaining the destroyed plotyard's coordinates.
            RagdollExpireHelper.expireImmediate(SubLevelPhysicsSystem.get(player.serverLevel()),
                    player.serverLevel(), root, "api revival release", true);
        } else if (session != null) session.release();
        else if (pending != null) pending.release();
        LAST_ATTEMPT.remove(player.getUUID());
    }

    /** Actual world position, including players seated in the off-world Sable plotyard. */
    public static Vec3 worldPosition(Player player) {
        return Sable.HELPER.projectOutOfSubLevel(player.level(), player.position());
    }

    public static Vec3 worldEyePosition(Player player) {
        return Sable.HELPER.projectOutOfSubLevel(player.level(), player.getEyePosition());
    }

    /** The exact topology sent to clients; skin UUID alone also matches playerless dummies. */
    public static List<UUID> bodyPartIds(ServerPlayer player) {
        ServerSubLevel root = RagdollSessionManager.activeRagdollForPlayer(player.serverLevel(), player.getUUID());
        return root == null || root.isRemoved() ? List.of() : List.copyOf(RagdollAssemblyHelper.linkedParts(root.getUniqueId()));
    }

    public static void acceptClientParts(UUID playerId, List<UUID> partIds) {
        if (partIds.isEmpty()) CLIENT_PARTS.remove(playerId);
        else CLIENT_PARTS.put(playerId, Set.copyOf(partIds));
    }

    public static void clearClientParts() {
        CLIENT_PARTS.clear();
    }

    /** Tight world-space bounds of each real limb, useful for reach queries and HUD placement. */
    public static List<AABB> worldBodyBoxes(Player player) {
        List<AABB> boxes = new ArrayList<>();
        for (Part part : parts(player)) {
            for (AABB local : part.localBoxes()) boxes.add(transformBounds(part.pose(), local));
        }
        return boxes;
    }

    /** Clips the ray in each limb's local space, preserving the rotated body's exact outline. */
    public static Optional<Vec3> raycastBody(Player player, Vec3 from, Vec3 to) {
        Vec3 nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Part part : parts(player)) {
            Vec3 localFrom = part.pose().transformPositionInverse(from);
            Vec3 localTo = part.pose().transformPositionInverse(to);
            for (AABB local : part.localBoxes()) {
                Optional<Vec3> hit = local.contains(localFrom) ? Optional.of(localFrom) : local.clip(localFrom, localTo);
                if (hit.isEmpty()) continue;
                Vec3 worldHit = part.pose().transformPosition(hit.get());
                double distance = from.distanceToSqr(worldHit);
                if (distance < nearestDistance) {
                    nearest = worldHit;
                    nearestDistance = distance;
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    /** Server reach always originates at the real rescuer's eyes, never the offset camera. */
    public static boolean canReach(ServerPlayer rescuer, ServerPlayer target) {
        if (rescuer == target || rescuer.level() != target.level()) return false;
        Vec3 eye = worldEyePosition(rescuer);
        double reach = rescuer.entityInteractionRange();
        ServerSubLevel root = RagdollSessionManager.activeRagdollForPlayer(target.serverLevel(), target.getUUID());
        if (root == null) return false;
        Set<UUID> ownParts = Set.copyOf(RagdollAssemblyHelper.linkedParts(root.getUniqueId()));
        for (Part part : parts(target)) {
            Vec3 localEye = part.pose().transformPositionInverse(eye);
            for (AABB box : part.localBoxes()) {
                Vec3 localPoint = new Vec3(Math.clamp(localEye.x, box.minX, box.maxX),
                        Math.clamp(localEye.y, box.minY, box.maxY), Math.clamp(localEye.z, box.minZ, box.maxZ));
                Vec3 point = part.pose().transformPosition(localPoint);
                if (eye.distanceToSqr(point) > reach * reach) continue;
                BlockHitResult hit = rescuer.level().clip(new ClipContext(eye, point,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, rescuer));
                if (hit.getType() == HitResult.Type.MISS) return true;
                // Sable returns local plotyard hit locations. Resolve its sublevel instead of comparing raw distances.
                SubLevel obstacle = Sable.HELPER.getContaining(rescuer.level(), hit.getBlockPos());
                if (obstacle != null && ownParts.contains(obstacle.getUniqueId())) return true;
            }
        }
        return false;
    }

    /** Uses the native spring constraint rather than teleporting either the body or its passenger. */
    public static boolean startDrag(ServerPlayer rescuer, ServerPlayer target) {
        if (rescuer == target || rescuer.level() != target.level() || !rescuer.isShiftKeyDown()) return false;
        for (Drag drag : DRAGS.values()) {
            if (drag.targetId().equals(target.getUUID()) && drag.rescuer() != rescuer) return false;
        }
        Part nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        Vec3 eye = worldEyePosition(rescuer);
        for (Part part : parts(target)) {
            double current = eye.distanceToSqr(part.pose().transformPosition(Vec3.atCenterOf(part.blockEntity().getBlockPos())));
            if (current < distance) {
                nearest = part;
                distance = current;
            }
        }
        if (nearest == null || distance > 16) return false; // Native grip breaks at four blocks.
        stopDrag(rescuer);
        nearest.blockEntity().startGrab(rescuer.getUUID());
        DRAGS.put(rescuer.getUUID(), new Drag(rescuer, target.getUUID(), nearest.blockEntity()));
        RagdollGrabCallbacks.notifyGrabbed(rescuer);
        return true;
    }

    public static boolean tickDrag(ServerPlayer rescuer, ServerPlayer target) {
        Drag drag = DRAGS.get(rescuer.getUUID());
        if (drag == null || !drag.targetId().equals(target.getUUID())) return false;
        if (!rescuer.isShiftKeyDown() || rescuer.isDeadOrDying() || rescuer.isSpectator()
                || rescuer.level() != target.level() || drag.part().isRemoved()
                || RagdollAPI.isRagdolled(rescuer)) {
            stopDrag(rescuer);
            return false;
        }
        Vec3 center = Sable.HELPER.projectOutOfSubLevel(target.level(), Vec3.atCenterOf(drag.part().getBlockPos()));
        if (worldEyePosition(rescuer).distanceToSqr(center) > 16) {
            stopDrag(rescuer);
            return false;
        }
        // An ordinary Sable right-click release may race with our crouch grip; the crouch lease owns it.
        drag.part().startGrab(rescuer.getUUID());
        return true;
    }

    public static void stopDrag(ServerPlayer rescuer) {
        Drag drag = DRAGS.remove(rescuer.getUUID());
        if (drag == null) return;
        drag.part().stopGrab(rescuer.getUUID());
        RagdollGrabCallbacks.notifyReleased(rescuer);
    }

    public static void clear() {
        for (Drag drag : List.copyOf(DRAGS.values())) stopDrag(drag.rescuer());
        PENDING.clear();
        LAST_ATTEMPT.clear();
        CONFIGURED_ROOTS.clear();
    }

    private static void cancelPendingPose(UUID playerId) {
        // Reactions may have requested the old pose before the lethal damage reached our handler.
        // Remove that player's request so its delayed client response cannot create a second body.
        PoseRequestsAccessor.ragrevival$pending().entrySet().removeIf(entry ->
                ((PendingLaunchAccessor) entry.getValue()).ragrevival$playerId().equals(playerId));
        PoseRequestsAccessor.ragrevival$pendingPlayers().remove(playerId);
    }

    private static List<Part> parts(Player player) {
        SubLevelContainer container = SubLevelContainer.getContainer(player.level());
        if (container == null) return List.of();
        List<Part> found = new ArrayList<>();
        Set<UUID> linkedParts = player instanceof ServerPlayer serverPlayer ? Set.copyOf(bodyPartIds(serverPlayer))
                : CLIENT_PARTS.getOrDefault(player.getUUID(), Set.of());
        if (linkedParts.isEmpty()) return List.of();
        for (UUID partId : linkedParts) {
            SubLevel subLevel = container.getSubLevel(partId);
            if (subLevel == null || subLevel.isRemoved() || subLevel.getPlot() == null) continue;
            BlockPos pos = subLevel.getPlot().getCenterBlock();
            if (!(player.level().getBlockEntity(pos) instanceof RagdollPartBlockEntity part)
                    || !player.getUUID().equals(part.skinProfile().getId())) continue;
            Pose3dc pose = subLevel instanceof ClientSubLevelAccess client ? client.renderPose() : subLevel.logicalPose();
            List<AABB> localBoxes = part.getBlockState().getShape(player.level(), pos).toAabbs().stream()
                    .map(box -> box.move(pos)).toList();
            found.add(new Part(part, pose, localBoxes));
        }
        return found;
    }

    private static AABB transformBounds(Pose3dc pose, AABB local) {
        Vec3 first = pose.transformPosition(new Vec3(local.minX, local.minY, local.minZ));
        double minX = first.x, minY = first.y, minZ = first.z;
        double maxX = first.x, maxY = first.y, maxZ = first.z;
        for (int i = 1; i < 8; i++) {
            Vec3 point = pose.transformPosition(new Vec3((i & 1) == 0 ? local.minX : local.maxX,
                    (i & 2) == 0 ? local.minY : local.maxY, (i & 4) == 0 ? local.minZ : local.maxZ));
            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y); minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y); maxZ = Math.max(maxZ, point.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private record Part(RagdollPartBlockEntity blockEntity, Pose3dc pose, List<AABB> localBoxes) {}
    private record Drag(ServerPlayer rescuer, UUID targetId, RagdollPartBlockEntity part) {}
}
