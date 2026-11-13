package com.biguzi.ragrevival.compat.corpse;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import com.biguzi.ragrevival.ragdoll.NativeRagdollCompat;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import de.maxhenkel.corpse.Main;
import de.maxhenkel.corpse.entities.CorpseEntity;
import dev.leo.sableplayerragdoll.api.PlayerlessDespawnRule;
import dev.leo.sableplayerragdoll.api.PlayerlessRagdollSession;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.api.RagdollInteractEvent;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.physics.RagdollSessionManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Optional boundary: only Loaded resolves Corpse classes on a server without the mod. */
public final class CorpseCompat {
    private CorpseCompat() {}

    public static void register() {
        CorpseOrphanCleanup.register();
        if (ModList.get().isLoaded("corpse")) Loaded.register();
    }

    public static int activeBodyCount() {
        return ModList.get().isLoaded("corpse") ? Loaded.BODIES.size() : 0;
    }

    public static boolean hasPhysics(Entity corpse) {
        return ModList.get().isLoaded("corpse") && Loaded.BODIES.containsKey(corpse.getUUID());
    }

    public static boolean controlsMotion(Entity corpse) {
        return ModList.get().isLoaded("corpse") && (Loaded.BODIES.containsKey(corpse.getUUID())
                || Loaded.PENDING.containsKey(corpse.getUUID()) || Loaded.RECOVERY_ROOTS.containsKey(corpse.getUUID()));
    }

    public static boolean ownsRoot(UUID root) {
        return ModList.get().isLoaded("corpse") && (Loaded.ROOTS.containsKey(root) || Loaded.RESTORING.containsKey(root));
    }

    public static UUID rootId(Entity corpse) {
        if (!ModList.get().isLoaded("corpse")) return null;
        Loaded.Body body = Loaded.BODIES.get(corpse.getUUID());
        return body == null ? null : body.session.id();
    }

    public static boolean ownsPart(Entity corpse, UUID root, UUID part) {
        return corpse.level() instanceof ServerLevel level && root != null && root.equals(rootId(corpse))
                && NativeRagdollCompat.linkedParts(level, root).contains(part);
    }

    public static boolean ensurePhysics(Entity entity) {
        if (!ModList.get().isLoaded("corpse")) return false;
        return Loaded.ensurePhysics(entity);
    }

    /** Disk snapshots include velocities without broadcasting them in every pose packet. */
    public static CompoundTag poseForSave(Entity entity) {
        if (!ModList.get().isLoaded("corpse") || !(entity instanceof CorpsePoseAccess access)) return new CompoundTag();
        Loaded.Body body = Loaded.BODIES.get(entity.getUUID());
        if (body != null && entity.level() instanceof ServerLevel level) {
            Loaded.Snapshot snapshot = Loaded.snapshot(level, body.session.id(), true);
            if (snapshot != null) return snapshot.pose;
        }
        return access.getRagRevivalPose().copy();
    }

    public static void captureDyingBody(ServerPlayer player) {
        if (ModList.get().isLoaded("corpse")) Loaded.captureDeath(player);
    }

    private static final class Loaded {
        private static final Map<UUID, Body> BODIES = new LinkedHashMap<>();
        private static final Map<UUID, Body> ROOTS = new HashMap<>();
        private static final Map<UUID, PendingCorpse> PENDING = new LinkedHashMap<>();
        private static final Map<UUID, CorpseEntity> LOADED = new LinkedHashMap<>();
        private static final Map<UUID, Recovery> RESTORING = new LinkedHashMap<>();
        private static final Map<UUID, UUID> RECOVERY_ROOTS = new HashMap<>();
        private static final Map<UUID, DeathPose> DEATH_POSES = new HashMap<>();

        private static void register() {
            CorpseDragging.register();
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, Loaded::join);
            NeoForge.EVENT_BUS.addListener(Loaded::leave);
            NeoForge.EVENT_BUS.addListener(Loaded::tick);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, Loaded::interact);
            NeoForge.EVENT_BUS.addListener(Loaded::stop);
        }

        private static void join(EntityJoinLevelEvent event) {
            if (!(event.getEntity() instanceof CorpseEntity corpse) || !(event.getLevel() instanceof ServerLevel)) return;
            CorpsePoseAccess access = (CorpsePoseAccess) corpse;
            DeathPose dying = corpse.getCorpseUUID().map(DEATH_POSES::get).orElse(null);
            if (dying != null && dying.level == corpse.level()) {
                corpse.setPos(dying.snapshot.anchor);
                access.setRagRevivalPose(dying.snapshot.pose);
            }
            boolean saved = CorpsePose.valid(access.getRagRevivalPose());
            if (!saved) access.setRagRevivalPose(CorpsePose.resting(corpse.getYRot(), Main.SERVER_CONFIG.spawnCorpseOnFace.get()));
            // Defer native assembly until chunk loading completes. Saved corpses regain
            // articulated physics; the pose is only a persistence/packet-arrival fallback.
            LOADED.put(corpse.getUUID(), corpse);
            CompoundTag pose = access.getRagRevivalPose();
            if (saved && dying == null && pose.hasUUID("root")
                    && !NativeRagdollCompat.wasRemoved((ServerLevel) corpse.level(), pose.getUUID("root"))) {
                UUID root = pose.getUUID("root");
                RESTORING.put(root, new Recovery(corpse, corpse.level().getGameTime()));
                RECOVERY_ROOTS.put(corpse.getUUID(), root);
            }
            PENDING.put(corpse.getUUID(), new PendingCorpse(corpse, saved));
        }

        private static void leave(EntityLeaveLevelEvent event) {
            if (!(event.getEntity() instanceof CorpseEntity corpse) || event.getLevel().isClientSide()) return;
            PENDING.remove(corpse.getUUID());
            LOADED.remove(corpse.getUUID());
            UUID recovering = RECOVERY_ROOTS.remove(corpse.getUUID());
            if (recovering != null) RESTORING.remove(recovering);
            retire(BODIES.get(corpse.getUUID()));
        }

        private static void captureDeath(ServerPlayer player) {
            ServerSubLevel root = RagdollSessionManager.activeRagdollForPlayer(player.serverLevel(), player.getUUID());
            if (root == null) return;
            Snapshot snapshot = snapshot(player.serverLevel(), NativeRagdollCompat.ownerForPlayer(player, root), true);
            if (snapshot != null) {
                PlayerlessRagdollSession session = RagdollBridge.detachForCorpse(player);
                DEATH_POSES.put(player.getUUID(), new DeathPose(player.serverLevel(), snapshot, player.serverLevel().getGameTime(), session));
            }
        }

        private static void tick(ServerTickEvent.Post event) {
            for (var entry : List.copyOf(RESTORING.entrySet())) {
                Recovery recovery = entry.getValue();
                CorpseEntity corpse = recovery.corpse;
                ServerLevel level = (ServerLevel) corpse.level();
                if (!physicsChunksReady(corpse)) continue;
                PlayerlessRagdollSession session = RestoredCorpseSession.tryRestore(level, corpse.getUUID(), entry.getKey());
                if (session != null) {
                    Body body = new Body(corpse, session, level.getGameTime(), null, corpse.position());
                    BODIES.put(corpse.getUUID(), body);
                    ROOTS.put(session.id(), body);
                    RESTORING.remove(entry.getKey());
                    RECOVERY_ROOTS.remove(corpse.getUUID());
                } else if (NativeRagdollCompat.wasRemoved(level, entry.getKey())
                        || (!expectsNativeBody(corpse)
                        && NativeRagdollCompat.savedParts(level, entry.getKey()).isEmpty()
                        && !hasStoredParts(level, ((CorpsePoseAccess) corpse).getRagRevivalPose()))) {
                    RESTORING.remove(entry.getKey());
                    RECOVERY_ROOTS.remove(corpse.getUUID());
                    PENDING.put(corpse.getUUID(), new PendingCorpse(corpse, true));
                } else if (!recovery.warned && level.getGameTime() - recovery.started > 200) {
                    recovery.warned = true;
                    LogUtils.getLogger().warn("Waiting for all saved Sable limbs of corpse {}; retaining its inventory and pose", corpse.getUUID());
                }
            }
            for (Body body : List.copyOf(BODIES.values())) {
                CorpseEntity corpse = body.corpse;
                ServerLevel level = (ServerLevel) corpse.level();
                if (corpse.isRemoved() || !physicsChunksReady(corpse)) {
                    retire(body);
                    continue;
                }
                if (body.initial != null && level.getGameTime() > body.started) {
                    // Native deferred launch runs on the first physics tick. Restore exact
                    // per-limb momenta afterward, once, so the launch cannot overwrite them.
                    if (restoreBody(body)) body.initial = null;
                    else if (level.getGameTime() - body.started > 40) { retire(body); continue; }
                }
                Snapshot snapshot = snapshot(level, body.session.id(), false);
                if (snapshot == null) { retire(body); continue; }
                if (!Main.SERVER_CONFIG.fallIntoVoid.get() && snapshot.anchor.y < level.getMinBuildHeight()) {
                    liftFromVoid(body, level.getMinBuildHeight() - snapshot.anchor.y);
                    snapshot = snapshot(level, body.session.id(), false);
                    if (snapshot == null) { retire(body); continue; }
                }
                corpse.setPos(snapshot.anchor);
                corpse.setDeltaMovement(Vec3.ZERO);
                snapshot.pose.putBoolean("active", true);
                ((CorpsePoseAccess) corpse).setRagRevivalPose(snapshot.pose);
            }
            for (PendingCorpse pending : new ArrayList<>(PENDING.values())) {
                CorpseEntity corpse = pending.corpse;
                PENDING.remove(corpse.getUUID());
                if (corpse.isRemoved()) continue;
                start(corpse, pending.saved);
                if (!hasPhysics(corpse) && !RECOVERY_ROOTS.containsKey(corpse.getUUID())) {
                    CompoundTag pose = ((CorpsePoseAccess) corpse).getRagRevivalPose().copy();
                    pose.putBoolean("active", false);
                    ((CorpsePoseAccess) corpse).setRagRevivalPose(pose);
                    clearFloorPenetration(corpse);
                }
            }
            // Retry only loaded, ticking chunks. This also recovers a native body that
            // unloaded at a chunk boundary or could not safely assemble under a roof.
            if (event.getServer().getTickCount() % 20 == 0) {
                for (CorpseEntity corpse : List.copyOf(LOADED.values())) {
                    if (!corpse.isRemoved() && !hasPhysics(corpse)) start(corpse, true);
                }
            }
            DEATH_POSES.entrySet().removeIf(entry -> {
                DeathPose death = entry.getValue();
                if (death.level.getGameTime() - death.tick <= 20) return false;
                if (death.session != null) NativeRagdollCompat.release(death.level, death.session);
                return true;
            });
        }

        private static boolean ensurePhysics(Entity entity) {
            if (!(entity instanceof CorpseEntity corpse) || !(corpse.level() instanceof ServerLevel)) return false;
            if (!hasPhysics(corpse) && !corpse.isRemoved()) start(corpse, true);
            return hasPhysics(corpse);
        }

        private static void start(CorpseEntity corpse, boolean saved) {
            if (hasPhysics(corpse) || RECOVERY_ROOTS.containsKey(corpse.getUUID()) || !physicsChunksReady(corpse)) return;
            ServerLevel level = (ServerLevel) corpse.level();
            DeathPose death = corpse.getCorpseUUID().map(DEATH_POSES::remove).orElse(null);
            CompoundTag initial = saved ? ((CorpsePoseAccess) corpse).getRagRevivalPose().copy() : null;
            if (death != null && death.level == level) {
                initial = death.snapshot.pose.copy();
                corpse.setPos(death.snapshot.anchor);
                ((CorpsePoseAccess) corpse).setRagRevivalPose(initial);
            }
            if (!Main.SERVER_CONFIG.fallIntoVoid.get() && corpse.getY() < level.getMinBuildHeight())
                corpse.setPos(corpse.getX(), level.getMinBuildHeight(), corpse.getZ());
            // Sable assembles each limb in these cells before moving it into its plotyard.
            // Its assembly clears them; never overwrite a player's roof or build-limit blocks.
            if (death == null || death.session == null) {
                for (int i = 1; i <= 6; i++) {
                    if (!level.isEmptyBlock(new BlockPos(corpse.blockPosition().getX(), level.getMaxBuildHeight() - i,
                            corpse.blockPosition().getZ()))) return;
                }
            }
            GameProfile profile = new GameProfile(corpse.getCorpseUUID().orElse(corpse.getUUID()), corpse.getCorpseName());
            PlayerlessRagdollSession session = null;
            try {
                Vec3 forward = Vec3.directionFromRotation(0, corpse.getYRot());
                boolean transferred = death != null && death.session != null;
                session = transferred ? death.session : RagdollAPI.spawnPlayerless(level, corpse.position(), corpse.getYRot(), profile,
                        initial == null ? forward.scale(Main.SERVER_CONFIG.spawnCorpseOnFace.get() ? 0.5 : -0.5) : Vec3.ZERO, PlayerlessDespawnRule.never());
                if (session == null) return;
                NativeRagdollCompat.invalidate(level);
                if (transferred) initial = null;
                Body body = new Body(corpse, session, level.getGameTime(), initial, corpse.position());
                BODIES.put(corpse.getUUID(), body);
                ROOTS.put(session.id(), body);
                CompoundTag activePose = ((CorpsePoseAccess) corpse).getRagRevivalPose().copy();
                activePose.putBoolean("active", true);
                ((CorpsePoseAccess) corpse).setRagRevivalPose(activePose);
                UUID owner = session.id();
                for (NativeRagdollCompat.Limb nativeLimb : NativeRagdollCompat.limbs(level, owner)) {
                    ServerSubLevel serverPart = nativeLimb.subLevel();
                    RagdollPartBlockEntity block = nativeLimb.block();
                    BlockPos pos = block.getBlockPos();
                    block.getPersistentData().putBoolean(CorpsePose.PART_MARKER, true);
                    block.getPersistentData().putUUID("ragrevival:corpse_owner", corpse.getUUID());
                    block.getPersistentData().putUUID("ragrevival:corpse_root", session.id());
                    block.setChanged();
                    level.sendBlockUpdated(pos, block.getBlockState(), block.getBlockState(), 3);
                    NativeRagdollCompat.withOwner(owner, () -> RagdollAPI.setGrabDisabled(level, serverPart.getUniqueId(), false));
                    if (initial != null) {
                        CompoundTag limb = initial.getList("parts", Tag.TAG_COMPOUND).getCompound(block.bodyPart().ordinal());
                        movePart(level, serverPart, corpse.position().add(CorpsePose.offset(limb)), limb);
                    }
                }
            } catch (RuntimeException | LinkageError failure) {
                // Optional visual failure must never destroy or take ownership of the inventory.
                LogUtils.getLogger().error("Corpse ragdoll setup failed; retaining the original corpse and its saved pose", failure);
                Body body = BODIES.get(corpse.getUUID());
                if (body != null) retire(body);
                else if (session != null) NativeRagdollCompat.release(level, session);
            }
        }

        private static void movePart(ServerLevel level, ServerSubLevel part, Vec3 center, CompoundTag limb) {
            part.logicalPose().orientation().set(CorpsePose.rotation(limb));
            Vec3 current = part.logicalPose().transformPosition(Vec3.atCenterOf(part.getPlot().getCenterBlock()));
            Vec3 delta = center.subtract(current);
            part.logicalPose().position().add(delta.x, delta.y, delta.z);
            SubLevelPhysicsSystem.get(level).getPipeline().teleport(part, part.logicalPose().position(), part.logicalPose().orientation());
            part.updateLastPose();
        }

        private static boolean restoreBody(Body body) {
            ServerLevel level = (ServerLevel) body.corpse.level();
            var system = SubLevelPhysicsSystem.get(level);
            var container = SubLevelContainer.getContainer(level);
            List<ServerSubLevel> parts = new ArrayList<>();
            for (UUID id : NativeRagdollCompat.linkedParts((ServerLevel) body.corpse.level(), body.session.id())) {
                if (!(container.getSubLevel(id) instanceof ServerSubLevel part) || part.getPlot() == null) return false;
                var handle = system.getPhysicsHandle(part);
                if (handle == null || !handle.isValid()) return false;
                parts.add(part);
            }
            if (parts.size() != 6) return false;
            for (ServerSubLevel part : parts) {
                if (!(level.getBlockEntity(part.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block)) return false;
                CompoundTag limb = body.initial.getList("parts", Tag.TAG_COMPOUND).getCompound(block.bodyPart().ordinal());
                movePart(level, part, body.restoreAnchor.add(CorpsePose.offset(limb)), limb);
                var handle = system.getPhysicsHandle(part);
                Vector3d linear = vector(limb, "v").sub(handle.getLinearVelocity(new Vector3d()));
                Vector3d angular = vector(limb, "w").sub(handle.getAngularVelocity(new Vector3d()));
                handle.addLinearAndAngularVelocity(linear, angular);
            }
            return true;
        }

        private static void liftFromVoid(Body body, double lift) {
            ServerLevel level = (ServerLevel) body.corpse.level();
            var system = SubLevelPhysicsSystem.get(level);
            var container = SubLevelContainer.getContainer(level);
            for (UUID id : NativeRagdollCompat.linkedParts((ServerLevel) body.corpse.level(), body.session.id())) {
                if (!(container.getSubLevel(id) instanceof ServerSubLevel part) || part.getPlot() == null) continue;
                Vec3 center = part.logicalPose().transformPosition(Vec3.atCenterOf(part.getPlot().getCenterBlock()));
                CompoundTag limb = CorpsePose.part(Vec3.ZERO, part.logicalPose().orientation());
                movePart(level, part, center.add(0, lift, 0), limb);
                var handle = system.getPhysicsHandle(part);
                if (handle != null && handle.isValid()) {
                    double y = handle.getLinearVelocity(new Vector3d()).y;
                    if (y < 0) handle.addLinearAndAngularVelocity(new Vector3d(0, -y, 0), new Vector3d());
                }
            }
        }

        private static void putVector(CompoundTag tag, String prefix, Vector3d value) {
            tag.putDouble(prefix + "x", value.x); tag.putDouble(prefix + "y", value.y); tag.putDouble(prefix + "z", value.z);
        }

        private static Vector3d vector(CompoundTag tag, String prefix) {
            Vector3d value = new Vector3d(tag.getDouble(prefix + "x"), tag.getDouble(prefix + "y"), tag.getDouble(prefix + "z"));
            return value.isFinite() && value.lengthSquared() < 1.0E8 ? value : new Vector3d();
        }

        private static boolean physicsChunksReady(CorpseEntity corpse) {
            ServerLevel level = (ServerLevel) corpse.level();
            AABB box = corpse.getBoundingBox().inflate(1);
            for (int x = ((int) Math.floor(box.minX)) >> 4; x <= ((int) Math.floor(box.maxX)) >> 4; x++) {
                for (int z = ((int) Math.floor(box.minZ)) >> 4; z <= ((int) Math.floor(box.maxZ)) >> 4; z++) {
                    if (!PhysicsChunkTicketManager.isChunkLoadedEnough(level, x, z)) return false;
                }
            }
            return true;
        }

        private static boolean expectsNativeBody(CorpseEntity corpse) {
            CompoundTag pose = ((CorpsePoseAccess) corpse).getRagRevivalPose();
            return pose.getBoolean("active") || pose.getBoolean("nativeExpected");
        }

        private static boolean hasStoredParts(ServerLevel level, CompoundTag pose) {
            var container = SubLevelContainer.getContainer(level);
            if (container == null) return false;
            ListTag parts = pose.getList("parts", Tag.TAG_COMPOUND);
            for (int i = 0; i < parts.size(); i++) {
                CompoundTag part = parts.getCompound(i);
                if (!part.hasUUID("sublevel")) continue;
                UUID id = part.getUUID("sublevel");
                if (container.getSubLevel(id) != null || container.getHoldingChunkMap().getHoldingSubLevel(id) != null) return true;
            }
            return false;
        }

        private static Snapshot snapshot(ServerLevel level, UUID root, boolean motion) {
            var container = SubLevelContainer.getContainer(level);
            if (container == null) return null;
            CompoundTag[] limbs = new CompoundTag[6];
            Vec3 torso = null;
            for (NativeRagdollCompat.Limb nativeLimb : NativeRagdollCompat.limbs(level, root)) {
                ServerSubLevel part = nativeLimb.subLevel();
                if (part == null || part.isRemoved() || part.getPlot() == null) return null;
                RagdollPartBlockEntity block = nativeLimb.block();
                BlockPos pos = block.getBlockPos();
                Vec3 center = part.logicalPose().transformPosition(Vec3.atCenterOf(pos));
                if (block.bodyPart().ordinal() == 1) torso = center;
                CompoundTag limb = CorpsePose.part(center, part.logicalPose().orientation());
                limb.putUUID("sublevel", part.getUniqueId());
                limb.putUUID("limb", nativeLimb.id());
                limb.putLong("block", pos.asLong());
                if (motion) {
                    var handle = SubLevelPhysicsSystem.get(level).getPhysicsHandle(part);
                    if (handle != null && handle.isValid()) {
                        putVector(limb, "v", handle.getLinearVelocity(new Vector3d()));
                        putVector(limb, "w", handle.getAngularVelocity(new Vector3d()));
                    }
                }
                limbs[block.bodyPart().ordinal()] = limb;
            }
            if (torso == null) return null;
            ListTag parts = new ListTag();
            for (CompoundTag limb : limbs) {
                if (limb == null) return null;
                Vec3 relative = CorpsePose.offset(limb).subtract(torso);
                limb.putDouble("x", relative.x); limb.putDouble("y", relative.y); limb.putDouble("z", relative.z);
                parts.add(limb);
            }
            CompoundTag pose = new CompoundTag();
            pose.put("parts", parts);
            pose.putUUID("root", root);
            pose.putBoolean("active", true);
            if (!CorpsePose.valid(pose)) return null;
            double minY = CorpsePose.bounds(torso, pose).minY;
            Vec3 anchor = new Vec3(torso.x, minY, torso.z);
            for (int i = 0; i < 6; i++) {
                CompoundTag limb = parts.getCompound(i);
                limb.putDouble("y", limb.getDouble("y") + torso.y - minY);
            }
            return new Snapshot(anchor, pose);
        }

        private static void retire(Body body) {
            if (body == null) return;
            CorpseDragging.releaseCorpse(body.corpse.getUUID());
            Snapshot saved = snapshot((ServerLevel) body.corpse.level(), body.session.id(), true);
            BODIES.remove(body.corpse.getUUID());
            ROOTS.remove(body.session.id());
            CompoundTag pose = saved == null ? ((CorpsePoseAccess) body.corpse).getRagRevivalPose().copy() : saved.pose;
            pose.putBoolean("active", false);
            ((CorpsePoseAccess) body.corpse).setRagRevivalPose(pose);
            if (body.corpse instanceof EntityMovementExtension movement) {
                SubLevel tracking = movement.sable$getTrackingSubLevel();
                if (tracking != null && NativeRagdollCompat.linkedParts((ServerLevel) body.corpse.level(), body.session.id()).contains(tracking.getUniqueId())) {
                    movement.sable$setTrackingSubLevel(null);
                    movement.sable$setLastTrackingSubLevelID(null);
                }
            }
            NativeRagdollCompat.release((ServerLevel) body.corpse.level(), body.session);
            // No loaded native root means ordinary Sable unload, not proof of deletion.
            pose.putBoolean("nativeExpected", !NativeRagdollCompat.wasRemoved((ServerLevel) body.corpse.level(), body.session.id()));
            ((CorpsePoseAccess) body.corpse).setRagRevivalPose(pose);
            // A disk snapshot retains the exact native pose; floor clearance is only
            // for the temporary vanilla fallback while the native chunks are unavailable.
        }

        private static void clearFloorPenetration(CorpseEntity corpse) {
            // Sable's collision limbs are thinner than its visible outline. That outline
            // becomes the vanilla corpse box after release: lift small floor penetrations
            // now, otherwise Entity.move starts inside the floor and can fall through it.
            // Release our own limbs first so they cannot be mistaken for support.
            AABB bounds = corpse.getBoundingBox();
            double lift = 0;
            for (var shape : corpse.level().getBlockCollisions(corpse, bounds)) {
                for (AABB obstacle : shape.toAabbs()) {
                    double penetration = obstacle.maxY - bounds.minY;
                    if (obstacle.intersects(bounds) && penetration > 0 && penetration <= 0.5) {
                        lift = Math.max(lift, penetration + 0.001);
                    }
                }
            }
            if (lift > 0) corpse.setPos(corpse.getX(), corpse.getY() + lift, corpse.getZ());
            corpse.setDeltaMovement(Vec3.ZERO);
        }

        private static void interact(RagdollInteractEvent event) {
            Body body = ROOTS.get(event.rootId());
            if (body == null) return;
            event.setCanceled(true);
            ServerPlayer player = event.player();
            if (body.corpse.isRemoved() || player.level() != body.corpse.level() || DownedManager.isDowned(player)
                    || !player.isAlive() || player.isSpectator()) return;
            SubLevel part = NativeRagdollCompat.part(event.level(), event.partId());
            if (part == null || part.getPlot() == null) return;
            Vec3 eye = RagdollBridge.worldEyePosition(player);
            RagdollPartBlockEntity block = NativeRagdollCompat.block(event.level(), event.partId());
            if (block == null) return;
            BlockPos pos = block.getBlockPos();
            Vec3 localEye = part.logicalPose().transformPositionInverse(eye).subtract(Vec3.atCenterOf(pos));
            Vec3 half = CorpsePose.halfExtents(block.bodyPart().ordinal());
            Vec3 nearest = new Vec3(Math.clamp(localEye.x, -half.x, half.x), Math.clamp(localEye.y, -half.y, half.y),
                    Math.clamp(localEye.z, -half.z, half.z));
            Vec3 point = part.logicalPose().transformPosition(Vec3.atCenterOf(pos).add(nearest));
            double reach = player.entityInteractionRange();
            if (eye.distanceToSqr(point) > reach * reach) return;
            var hit = event.level().clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            SubLevel obstacle = hit.getType() == HitResult.Type.MISS ? null : Sable.HELPER.getContaining(event.level(), hit.getBlockPos());
            if (hit.getType() != HitResult.Type.MISS && (obstacle == null || !NativeRagdollCompat.linkedParts(event.level(), event.rootId()).contains(obstacle.getUniqueId()))) return;
            if (player.isShiftKeyDown() || !player.getMainHandItem().isEmpty())
                body.corpse.interact(player, InteractionHand.MAIN_HAND);
        }

        private static void stop(ServerStoppingEvent event) {
            // Native bodies and joints are normal world data. Save them intact and adopt
            // the same IDs on restart; rebuilding overlapping limbs would cause a shove.
            for (Body body : List.copyOf(BODIES.values())) {
                CorpseDragging.releaseCorpse(body.corpse.getUUID());
                ((CorpsePoseAccess) body.corpse).setRagRevivalPose(poseForSave(body.corpse));
            }
            for (DeathPose death : DEATH_POSES.values()) if (death.session != null) NativeRagdollCompat.release(death.level, death.session);
            BODIES.clear();
            ROOTS.clear();
            PENDING.clear();
            LOADED.clear();
            RESTORING.clear();
            RECOVERY_ROOTS.clear();
            DEATH_POSES.clear();
        }

        private static final class Body {
            final CorpseEntity corpse;
            final PlayerlessRagdollSession session;
            final long started;
            final Vec3 restoreAnchor;
            CompoundTag initial;
            Body(CorpseEntity corpse, PlayerlessRagdollSession session, long started, CompoundTag initial, Vec3 restoreAnchor) {
                this.corpse = corpse; this.session = session; this.started = started;
                this.initial = initial; this.restoreAnchor = restoreAnchor;
            }
        }
        private record PendingCorpse(CorpseEntity corpse, boolean saved) {}
        private record Snapshot(Vec3 anchor, CompoundTag pose) {}
        private record DeathPose(ServerLevel level, Snapshot snapshot, long tick, PlayerlessRagdollSession session) {}
        private static final class Recovery {
            final CorpseEntity corpse;
            final long started;
            boolean warned;
            Recovery(CorpseEntity corpse, long started) { this.corpse = corpse; this.started = started; }
        }
    }
}
