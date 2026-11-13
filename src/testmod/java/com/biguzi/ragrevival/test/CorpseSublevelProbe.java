package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.compat.corpse.CorpseCompat;
import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import de.maxhenkel.corpse.Main;
import de.maxhenkel.corpse.entities.CorpseEntity;
import dev.leo.ragdollreactions.physics.ReactionSuppressions;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.api.PlayerlessDespawnRule;
import dev.leo.sableplayerragdoll.api.PlayerlessRagdollSession;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import com.biguzi.ragrevival.ragdoll.NativeRagdollCompat;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Native assembled-platform integration test; use only an empty isolated dedicated test world. */
@EventBusSubscriber(modid = "ragrevival_tests", value = Dist.DEDICATED_SERVER)
public final class CorpseSublevelProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Run current;

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ragrevivaltest").requires(s -> s.hasPermission(4))
                .then(Commands.literal("corpse-sublevel").executes(context -> {
                    MinecraftServer server = context.getSource().getServer();
                    if (!ModList.get().isLoaded("corpse") || current != null || !server.getPlayerList().getPlayers().isEmpty()) {
                        context.getSource().sendFailure(Component.literal("Requires Corpse, no players, and no active sublevel probe."));
                        return 0;
                    }
                    current = new Run(server, false);
                    current.plan();
                    context.getSource().sendSuccess(() -> Component.literal("Native Sable platform probe started; inspect RAGREVIVAL_SUBLEVEL logs."), false);
                    return 1;
                }))
                .then(Commands.literal("corpse-sublevel-moving").executes(context -> {
                    MinecraftServer server = context.getSource().getServer();
                    if (!ModList.get().isLoaded("corpse") || current != null || !server.getPlayerList().getPlayers().isEmpty()) {
                        context.getSource().sendFailure(Component.literal("Requires Corpse, no players, and no active sublevel probe."));
                        return 0;
                    }
                    current = new Run(server, true);
                    current.plan();
                    context.getSource().sendSuccess(() -> Component.literal("Moving-at-death native Sable platform probe started."), false);
                    return 1;
                })));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
        if (current == null || current.server != event.getServer()) return;
        try { current.advance(); }
        catch (Throwable failure) {
            LOGGER.error("RAGREVIVAL_SUBLEVEL ABORTED tick={}", current.ticks, failure);
            current.failures.add("uncaught " + failure.getClass().getSimpleName());
            current.finish(false);
        }
    }

    private record Step(int tick, Runnable action) {}

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        final ArrayDeque<Step> steps = new ArrayDeque<>();
        final List<ServerPlayer> players = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final boolean keepInventory;
        final int forcedLifetime;
        final boolean movingDeath;
        final Vector3d targetCenter = new Vector3d(48.5, 95.5, 0.5);
        final Quaterniond targetRotation = new Quaterniond().rotateY(Math.toRadians(30));
        ServerSubLevel platform;
        GenericConstraintHandle support;
        ServerPlayer owner;
        ServerPlayer observer;
        CorpseEntity corpse;
        PlayerlessRagdollSession nativeControl;
        List<UUID> initialParts;
        List<UUID> downedParts;
        Vec3 armBeforeImpulse;
        Quaterniond armRotationBeforeImpulse;
        Vec3 controlArmBeforeImpulse;
        Quaterniond controlArmRotationBeforeImpulse;
        double corpseBeforeRemovalY;
        double controlBeforeRemovalY;
        int ticks;
        int cursor;
        int passed;
        int motionStart = -1;
        int motionEnd = -1;
        int motionKind;

        Run(MinecraftServer server, boolean movingDeath) {
            this.server = server;
            this.movingDeath = movingDeath;
            level = server.overworld();
            keepInventory = server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
            forcedLifetime = Main.SERVER_CONFIG.corpseForceDespawnTime.get();
        }

        void after(int delay, Runnable action) { cursor += delay; steps.add(new Step(cursor, action)); }

        void plan() {
            after(1, () -> {
                Main.SERVER_CONFIG.corpseForceDespawnTime.set(-1);
                server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
                owner = player("SublevelOwner", new Vec3(48.5, 99, 0.5));
                observer = player("SublevelObserver", new Vec3(48.5, 99, 5.5));
                observer.setGameMode(GameType.CREATIVE);
                observer.setNoGravity(true);
                owner.getInventory().setItem(8, new ItemStack(Items.DIAMOND, 7));
                HashSet<BlockPos> blocks = new HashSet<>();
                int radius = 17;
                for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = new BlockPos(48 + x, 95, z);
                    blocks.add(pos);
                    level.setBlockAndUpdate(pos, Blocks.SMOOTH_STONE.defaultBlockState());
                }
                platform = SubLevelAssemblyHelper.assembleBlocks(level, new BlockPos(48, 95, 0), blocks, BoundingBox3i.from(blocks));
                if (platform == null) throw new IllegalStateException("Platform assembly failed");
                var physics = SubLevelPhysicsSystem.get(level);
                platform.logicalPose().orientation().set(targetRotation);
                Vec3 old = center();
                platform.logicalPose().position().add(targetCenter.x - old.x, targetCenter.y - old.y, targetCenter.z - old.z);
                physics.getPipeline().teleport(platform, platform.logicalPose().position(), platform.logicalPose().orientation());
                platform.updateLastPose();
                Vec3 localCenter = Vec3.atCenterOf(platform.getPlot().getCenterBlock());
                support = physics.getPipeline().addConstraint(null, platform, new GenericConstraintConfiguration(
                        new Vector3d(targetCenter), new Vector3d(localCenter.x, localCenter.y, localCenter.z),
                        new Quaterniond(targetRotation), new Quaterniond(), EnumSet.allOf(ConstraintJointAxis.class)));
                if (support == null) throw new IllegalStateException("Could not constrain platform");
                check(SubLevelContainer.getContainer(level).getSubLevel(platform.getUniqueId()) == platform,
                        "native platform exists in Sable plot container");
            });
            after(80, () -> {
                check(center().distanceToSqr(new Vec3(targetCenter.x, targetCenter.y, targetCenter.z)) < 0.01,
                        "native joint supports yaw-rotated platform at requested world position");
                Vec3 deathPoint = platform.logicalPose().transformPosition(Vec3.atCenterOf(platform.getPlot().getCenterBlock()).add(1.4, 1.8, 0));
                owner.teleportTo(deathPoint.x, deathPoint.y, deathPoint.z);
                owner.setDeltaMovement(Vec3.ZERO);
                owner.fallDistance = 0;
                owner.invulnerableTime = 0;
                owner.hurt(owner.damageSources().generic(), 1000);
                check(DownedManager.isDowned(owner), "lethal damage on native platform creates downed body");
                nativeControl = RagdollAPI.spawnPlayerless(level,
                        platform.logicalPose().transformPosition(Vec3.atCenterOf(platform.getPlot().getCenterBlock()).add(-3, 1.4, 3)),
                        0, Vec3.ZERO, PlayerlessDespawnRule.never());
                if (nativeControl == null) throw new IllegalStateException("Native ragdoll control could not spawn");
                if (movingDeath) {
                    motionStart = ticks;
                    motionEnd = ticks + 280;
                    motionKind = 4;
                    LOGGER.info("RAGREVIVAL_SUBLEVEL Moving-at-death variant: native support translates fourteen blocks over280 ticks (1m/s), continuing beyond the former physics retirement threshold.");
                }
            });
            after(60, () -> {
                Vec3 playerWorld = RagdollBridge.worldPosition(owner);
                check(!RagdollBridge.bodyPartIds(owner).isEmpty(), "downed body uses actual six-part Sable topology");
                check(playerWorld.y > 94 && playerWorld.distanceToSqr(center()) < 64, "downed body remains supported in real world space");
                downedParts = RagdollBridge.bodyPartIds(owner).stream().sorted().toList();
                DownedManager.finishDeath(owner);
                List<CorpseEntity> found = new ArrayList<>();
                for (Entity entity : level.getAllEntities()) if (entity instanceof CorpseEntity candidate
                        && candidate.getCorpseUUID().filter(owner.getUUID()::equals).isPresent()) found.add(candidate);
                check(found.size() == 1, "terminal death on sublevel creates exactly one authoritative Corpse");
                if (found.size() != 1) throw new IllegalStateException("Expected exactly one corpse");
                corpse = found.getFirst();
                checkLoot("native sublevel death preserves inventory");
                check(corpse.getY() > 94 && Math.abs(corpse.getX()) < 1000 && Math.abs(corpse.getZ()) < 1000,
                        "corpse spawns at world death position, never plotyard coordinates");
            });
            after(20, () -> {
                check(CorpseCompat.hasPhysics(corpse), "new corpse receives persistent native physics on sublevel");
                initialParts = corpseParts().stream().map(ServerSubLevel::getUniqueId).sorted().toList();
                check(initialParts.size() == 6, "corpse has exactly six native articulated limbs");
                check(initialParts.equals(downedParts), "terminal death adopts the original six downed limb identities");
                mirrorCorpseIntoControl();
            });
            after(200, () -> {
                checkPersistent("after former ten-second cutoff");
                checkSupported("persistent corpse remains above native platform");
                checkBounds("after former ten-second cutoff");
                checkLoot("persistent native simulation keeps inventory");
                check(controlParts().size() == 6, "ordinary native control remains a six-limb ragdoll");
            });
            after(60, () -> {
                checkPersistent("while resting on native deck");
                check(maxSpeed(corpseParts()) < 0.15 && maxAngularSpeed(corpseParts()) < 0.15,
                        "persistent limbs reach native rest without converting to a static pose");
                check(maxSpeed(controlParts()) < 0.15 && maxAngularSpeed(controlParts()) < 0.15,
                        "ordinary native control reaches comparable rest");
                ServerSubLevel arm = limb(corpseParts(), 2);
                ServerSubLevel controlArm = limb(controlParts(), 2);
                armBeforeImpulse = partCenter(arm);
                armRotationBeforeImpulse = new Quaterniond(arm.logicalPose().orientation());
                controlArmBeforeImpulse = partCenter(controlArm);
                controlArmRotationBeforeImpulse = new Quaterniond(controlArm.logicalPose().orientation());
                kick(arm);
                kick(controlArm);
            });
            after(5, () -> {
                ServerSubLevel arm = limb(corpseParts(), 2);
                double corpseTurn = 2 * Math.acos(Math.min(1, Math.abs(arm.logicalPose().orientation().dot(armRotationBeforeImpulse))));
                double controlTurn = 2 * Math.acos(Math.min(1, Math.abs(limb(controlParts(), 2).logicalPose().orientation().dot(controlArmRotationBeforeImpulse))));
                double corpseShift = partCenter(arm).distanceTo(armBeforeImpulse);
                double controlShift = partCenter(limb(controlParts(), 2)).distanceTo(controlArmBeforeImpulse);
                LOGGER.info("RAGREVIVAL_SUBLEVEL_IMPULSE corpseArmTurnDegrees={} nativeControlArmTurnDegrees={} corpseArmShift={} nativeControlArmShift={}",
                        Math.toDegrees(corpseTurn), Math.toDegrees(controlTurn), corpseShift, controlShift);
                // Contact may turn a limb around its support point without translating its center much.
                check(corpseShift > 0.05 || corpseTurn > Math.toRadians(0.1),
                        "resting corpse arm wakes and moves after a native impulse");
                check(corpseTurn > Math.toRadians(0.1),
                        "corpse limb articulates independently after impulse");
                check(controlTurn > Math.toRadians(0.1), "ordinary native control limb articulates after the same impulse");
                check(controlShift > 0.05 || controlTurn > Math.toRadians(0.1),
                        "ordinary native control responds to the same impulse");
                checkPersistent("after native impulse");
                checkLoot("native impulse preserves authoritative inventory");
            });
            after(195, () -> {
                checkSupported("articulated corpse settles back onto deck");
                checkBounds("after impulse and settling");
                motionStart = ticks;
                motionEnd = ticks + 80;
                motionKind = 1;
            });
            after(110, () -> {
                check(Math.abs(center().x - (movingDeath ? 66.5 : 52.5)) < 0.1, "native joint moved support platform four blocks");
                checkPersistent("during support translation");
                check(controlParts().size() == 6, "native control remains physical during support translation");
                checkSupported("translated large platform still supports articulated corpse");
                checkBounds("after translated sublevel ticks");
                checkLoot("translated platform keeps corpse inventory");
                motionStart = ticks;
                motionEnd = ticks + 80;
                motionKind = 2;
            });
            after(110, () -> {
                double alignment = Math.abs(platform.logicalPose().orientation().dot(new Quaterniond().rotateY(Math.toRadians(70))));
                check(alignment > 0.999, "native support actually rotates forty degrees");
                checkPersistent("during support yaw rotation");
                checkSupported("rotating large deck still supports articulated corpse");
                checkLoot("rotated platform keeps corpse inventory");
                checkBounds("after rotated sublevel ticks");
                motionStart = ticks;
                motionEnd = ticks + 80;
                motionKind = 3;
            });
            after(110, () -> {
                checkSupported("pitched platform still supports articulated corpse");
                checkPersistent("during support pitch");
                check(Math.abs(corpse.getX()) < 1000 && Math.abs(corpse.getZ()) < 1000, "moving and tilted support never exports plot coordinates");
                checkLoot("tilted platform keeps corpse inventory");
                corpseBeforeRemovalY = corpse.getY();
                controlBeforeRemovalY = partCenter(limb(controlParts(), 1)).y;
                LOGGER.info("RAGREVIVAL_SUBLEVEL_SUPPORT_REMOVAL phase=before corpse={} nativeControl={} platform={} nativeControlLocal={}",
                        corpse.position(), partCenter(limb(controlParts(), 1)), center(),
                        platform.logicalPose().transformPositionInverse(partCenter(limb(controlParts(), 1)))
                                .subtract(Vec3.atCenterOf(platform.getPlot().getCenterBlock())));
                support.remove(); support = null;
                SubLevelContainer.getContainer(level).removeSubLevel(platform, SubLevelRemovalReason.REMOVED);
            });
            after(20, () -> {
                check(!corpse.isRemoved(), "removing supporting sublevel preserves authoritative corpse");
                checkLoot("removed support preserves retrievable inventory");
                checkPersistent("after supporting sublevel removal");
                LOGGER.info("RAGREVIVAL_SUBLEVEL_SUPPORT_REMOVAL phase=after corpse={} nativeControl={} corpseBeforeY={} nativeControlBeforeY={}",
                        corpse.position(), partCenter(limb(controlParts(), 1)), corpseBeforeRemovalY, controlBeforeRemovalY);
                check(corpse.getY() < corpseBeforeRemovalY - 0.5, "persistent corpse falls physically when its support is removed");
                check(partCenter(limb(controlParts(), 1)).y < controlBeforeRemovalY - 0.5,
                        "ordinary native control also falls when support is removed");
                corpse.discard();
            });
            after(20, () -> {
                check(!CorpseCompat.hasPhysics(corpse) && markedPartCount() == 0,
                        "deleting authoritative corpse releases all six native limbs");
                check(controlParts().size() == 6, "corpse cleanup preserves the unrelated native control");
                finish(true);
            });
        }

        Vec3 center() { return platform.logicalPose().transformPosition(Vec3.atCenterOf(platform.getPlot().getCenterBlock())); }
        Vec3 localPosition() { return platform.logicalPose().transformPositionInverse(corpse.position()); }
        void checkSupported(String label) {
            Vec3 local = localPosition().subtract(Vec3.atCenterOf(platform.getPlot().getCenterBlock()));
            check(local.y >= 0.05 && local.y < 3 && Math.abs(local.x) < 17 && Math.abs(local.z) < 17, label);
        }
        void checkLoot(String label) {
            int diamonds = corpse.getDeath().getMainInventory().stream().filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
            check(diamonds == 7, label);
        }
        long markedPartCount() {
            return SubLevelContainer.getContainer(level).getAllSubLevels().stream()
                    .filter(part -> !part.isRemoved() && part.getPlot() != null)
                    .filter(part -> level.getBlockEntity(part.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block
                            && block.getPersistentData().getBoolean(CorpsePose.PART_MARKER)).count();
        }
        List<ServerSubLevel> corpseParts() {
            return SubLevelContainer.getContainer(level).getAllSubLevels().stream()
                    .filter(part -> !part.isRemoved() && part.getPlot() != null)
                    .filter(part -> level.getBlockEntity(part.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block
                            && block.getPersistentData().getBoolean(CorpsePose.PART_MARKER)
                            && block.getPersistentData().hasUUID("ragrevival:corpse_owner")
                            && block.getPersistentData().getUUID("ragrevival:corpse_owner").equals(corpse.getUUID()))
                    .map(part -> (ServerSubLevel) part).toList();
        }
        List<ServerSubLevel> controlParts() {
            if (nativeControl == null) return List.of();
            var container = SubLevelContainer.getContainer(level);
            return NativeRagdollCompat.linkedParts(level, nativeControl.id()).stream().map(container::getSubLevel)
                    .filter(part -> part instanceof ServerSubLevel && !part.isRemoved())
                    .map(part -> (ServerSubLevel) part).toList();
        }
        ServerSubLevel limb(List<ServerSubLevel> parts, int ordinal) {
            return parts.stream().filter(part -> level.getBlockEntity(part.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block
                    && block.bodyPart().ordinal() == ordinal).findFirst().orElseThrow();
        }
        Vec3 partCenter(ServerSubLevel part) { return part.logicalPose().transformPosition(Vec3.atCenterOf(part.getPlot().getCenterBlock())); }
        double maxSpeed(List<ServerSubLevel> parts) {
            return parts.stream().mapToDouble(part -> SubLevelPhysicsSystem.get(level).getPhysicsHandle(part).getLinearVelocity(new Vector3d()).length()).max().orElse(Double.POSITIVE_INFINITY);
        }
        double maxAngularSpeed(List<ServerSubLevel> parts) {
            return parts.stream().mapToDouble(part -> SubLevelPhysicsSystem.get(level).getPhysicsHandle(part).getAngularVelocity(new Vector3d()).length()).max().orElse(Double.POSITIVE_INFINITY);
        }
        void kick(ServerSubLevel arm) {
            double mass = arm.getMassTracker().getMass();
            SubLevelPhysicsSystem.get(level).getPhysicsHandle(arm).applyLinearAndAngularImpulse(
                    new Vector3d(0, mass * 3, 0), new Vector3d(mass * 0.6, mass * 0.8, mass), true);
        }
        void mirrorCorpseIntoControl() {
            var physics = SubLevelPhysicsSystem.get(level);
            List<ServerSubLevel> sources = corpseParts();
            List<ServerSubLevel> targets = controlParts();
            for (int ordinal = 0; ordinal < 6; ordinal++) {
                ServerSubLevel source = limb(sources, ordinal);
                ServerSubLevel target = limb(targets, ordinal);
                var sourceHandle = physics.getPhysicsHandle(source);
                var targetHandle = physics.getPhysicsHandle(target);
                Vector3d linear = sourceHandle.getLinearVelocity(new Vector3d());
                Vector3d angular = sourceHandle.getAngularVelocity(new Vector3d());
                target.logicalPose().orientation().set(source.logicalPose().orientation());
                Vec3 desired = partCenter(source).add(0, 0, 6);
                Vec3 delta = desired.subtract(partCenter(target));
                target.logicalPose().position().add(delta.x, delta.y, delta.z);
                targetHandle.teleport(target.logicalPose().position(), target.logicalPose().orientation());
                target.updateLastPose();
                targetHandle.addLinearAndAngularVelocity(linear.sub(targetHandle.getLinearVelocity(new Vector3d())),
                        angular.sub(targetHandle.getAngularVelocity(new Vector3d())));
            }
            LOGGER.info("RAGREVIVAL_SUBLEVEL Native control mirrors exact corpse six-part transforms and momenta with worldZ+6 separation.");
        }
        void checkPersistent(String label) {
            check(CorpseCompat.hasPhysics(corpse), label + ": native corpse session remains active");
            check(corpseParts().stream().map(ServerSubLevel::getUniqueId).sorted().toList().equals(initialParts),
                    label + ": same six articulated native limbs remain allocated");
        }
        boolean boundsContainPose() {
            AABB actual = corpse.getBoundingBox();
            AABB visual = CorpsePose.bounds(corpse, ((CorpsePoseAccess) corpse).getRagRevivalPose());
            return actual.minX <= visual.minX + 1E-5 && actual.minY <= visual.minY + 1E-5 && actual.minZ <= visual.minZ + 1E-5
                    && actual.maxX >= visual.maxX - 1E-5 && actual.maxY >= visual.maxY - 1E-5 && actual.maxZ >= visual.maxZ - 1E-5;
        }
        void checkBounds(String label) {
            check(boundsContainPose(), label + ": movement bbox still contains every saved limb");
            Vec3 center = corpse.getBoundingBox().getCenter();
            check(Math.abs(center.x - corpse.getX()) < 1E-5 && Math.abs(center.z - corpse.getZ()) < 1E-5,
                    label + ": movement bbox remains horizontally centered on entity anchor");
        }

        void advance() {
            ticks++;
            if (support != null && support.isValid() && ticks > motionStart && ticks <= motionEnd) {
                double fraction = (double) (ticks - motionStart) / (motionEnd - motionStart);
                double baseX = movingDeath ? 62.5 : 48.5;
                double x = motionKind == 4 ? 48.5 + 14 * fraction : motionKind == 1 ? baseX + 4 * fraction : baseX + 4;
                double yaw = motionKind == 1 || motionKind == 4 ? 30 : motionKind == 2 ? 30 + 40 * fraction : 70;
                double pitch = motionKind == 3 ? 8 * fraction : 0;
                support.setFrame1(new Vector3d(x, 95.5, 0.5), new Quaterniond().rotateY(Math.toRadians(yaw)).rotateX(Math.toRadians(pitch)));
                SubLevelPhysicsSystem.get(level).getPipeline().wakeUp(platform);
            }
            for (ServerPlayer player : List.copyOf(players)) if (!player.isRemoved() && player.isAlive()) {
                ReactionSuppressions.suppress(player, level.getGameTime(), 5);
                player.doTick();
            }
            if (corpse != null && platform != null && !platform.isRemoved() && ticks % 20 == 0) {
                LOGGER.info("RAGREVIVAL_SUBLEVEL_SAMPLE tick={} corpse={} local={} platform={} tracking={} physics={} parts={} maxSpeed={} maxAngular={} controlParts={} controlSpeed={} controlCenter={} bboxContainsPose={} bboxCenterOffset={}", ticks,
                        corpse.position(), localPosition().subtract(Vec3.atCenterOf(platform.getPlot().getCenterBlock())), center(),
                        Sable.HELPER.getTrackingSubLevel(corpse) == platform, CorpseCompat.hasPhysics(corpse), corpseParts().size(),
                        maxSpeed(corpseParts()), maxAngularSpeed(corpseParts()), controlParts().size(), maxSpeed(controlParts()),
                        controlParts().size() == 6 ? partCenter(limb(controlParts(), 1)) : null,
                        boundsContainPose(), corpse.getBoundingBox().getCenter().subtract(corpse.position()));
            }
            while (!steps.isEmpty() && steps.peek().tick <= ticks) steps.remove().action.run();
        }

        @SuppressWarnings("unchecked") <T> T playerField(String name) {
            try { Field field = PlayerList.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(server.getPlayerList()); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        ServerPlayer player(String name, Vec3 point) {
            ServerPlayer player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
            player.connection = new PacketSink(server, player);
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(point);
            this.<List<ServerPlayer>>playerField("players").add(player);
            this.<Map<UUID, ServerPlayer>>playerField("playersByUUID").put(player.getUUID(), player);
            players.add(player);
            level.addNewPlayer(player);
            return player;
        }
        void check(boolean success, String label) {
            if (success) { passed++; LOGGER.info("RAGREVIVAL_SUBLEVEL PASS {}", label); }
            else { failures.add(label); LOGGER.error("RAGREVIVAL_SUBLEVEL FAIL {}", label); }
        }
        void finish(boolean completed) {
            try {
                if (corpse != null && !corpse.isRemoved()) corpse.discard();
                if (nativeControl != null) nativeControl.release();
                for (ServerPlayer player : players) {
                    if (!player.isRemoved()) {
                        if (DownedManager.isDowned(player)) DownedManager.revive(player);
                        DownedManager.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
                        server.getPlayerList().remove(player);
                    }
                    ((EmbeddedChannel) player.connection.getConnection().channel()).finishAndReleaseAll();
                }
                if (support != null && support.isValid()) support.remove();
                if (platform != null && !platform.isRemoved()) SubLevelContainer.getContainer(level).removeSubLevel(platform, SubLevelRemovalReason.REMOVED);
            } finally {
                server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(keepInventory, server);
                Main.SERVER_CONFIG.corpseForceDespawnTime.set(forcedLifetime);
                LOGGER.info("RAGREVIVAL_SUBLEVEL {} passed={} failed={} ticks={} movingDeath={}", completed && failures.isEmpty() ? "PASSED" : "FAILED", passed, failures.size(), ticks, movingDeath);
                current = null;
            }
        }
    }

    private static final class PacketSink extends ServerGamePacketListenerImpl {
        PacketSink(MinecraftServer server, ServerPlayer player) { super(server, connection(), player, CommonListenerCookie.createInitial(player.getGameProfile(), false)); }
        private static Connection connection() { Connection connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection); return connection; }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, PacketSendListener listener) {}
    }
}
