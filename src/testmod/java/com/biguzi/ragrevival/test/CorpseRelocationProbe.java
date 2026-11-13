package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.compat.corpse.CorpseCompat;
import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import com.biguzi.ragrevival.ragdoll.NativeRagdollCompat;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import de.maxhenkel.corpse.Main;
import de.maxhenkel.corpse.corelib.death.Death;
import de.maxhenkel.corpse.entities.CorpseEntity;
import de.maxhenkel.corpse.gui.CorpseInventoryContainer;
import dev.leo.ragdollreactions.physics.ReactionSuppressions;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import dev.leo.sableplayerragdoll.neoforge.network.RagdollGrabPacket;
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
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Opt-in 0.8 identity regression using Sable's real block assembly/movement path. */
@EventBusSubscriber(modid = "ragrevival_tests", value = Dist.DEDICATED_SERVER)
public final class CorpseRelocationProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Run current;

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ragrevivaltest").requires(source -> source.hasPermission(4))
                .then(Commands.literal("corpse-relocation").executes(context -> {
                    MinecraftServer server = context.getSource().getServer();
                    if (!ModList.get().isLoaded("corpse") || !NativeRagdollCompat.blockIdentityStorage()
                            || current != null || !server.getPlayerList().getPlayers().isEmpty()) {
                        context.getSource().sendFailure(Component.literal("Requires Corpse, Sable Ragdolls 0.8, no players, and no active relocation probe."));
                        return 0;
                    }
                    current = new Run(server);
                    current.plan();
                    context.getSource().sendSuccess(() -> Component.literal("Real Sable limb relocation probe started; inspect RAGREVIVAL_RELOCATION logs."), false);
                    return 1;
                })));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
        if (current == null || current.server != event.getServer()) return;
        try { current.advance(); }
        catch (Throwable failure) {
            LOGGER.error("RAGREVIVAL_RELOCATION ABORTED tick={}", current.ticks, failure);
            current.failures.add("uncaught " + failure.getClass().getSimpleName());
            current.finish(false);
        }
    }

    private record Step(int tick, Runnable action) {}
    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        final ArrayDeque<Step> steps = new ArrayDeque<>();
        final List<String> failures = new ArrayList<>();
        final List<BlockPos> vesselBlocks = new ArrayList<>();
        final int forcedLifetime;
        ServerPlayer owner;
        CorpseEntity corpse;
        ServerSubLevel vessel;
        GenericConstraintHandle support;
        RagdollPartBlockEntity moved;
        UUID root;
        UUID limbId;
        UUID oldPhysicalId;
        BlockPos destination;
        Map<BodyPart, UUID> originalIds;
        Vector3d supportPoint;
        Quaterniond supportRotation;
        Vec3 beforeTranslation;
        int cursor;
        int ticks;
        int passed;
        int translationStart = -1;

        Run(MinecraftServer server) {
            this.server = server;
            level = server.overworld();
            forcedLifetime = Main.SERVER_CONFIG.corpseForceDespawnTime.get();
        }
        void after(int delay, Runnable action) { cursor += delay; steps.add(new Step(cursor, action)); }

        void plan() {
            after(1, () -> {
                Main.SERVER_CONFIG.corpseForceDespawnTime.set(-1);
                for (int x = 390; x <= 410; x++) for (int z = 390; z <= 410; z++)
                    level.setBlockAndUpdate(new BlockPos(x, 88, z), Blocks.SMOOTH_STONE.defaultBlockState());
                owner = player();
                owner.getInventory().setItem(8, new ItemStack(Items.DIAMOND, 7));
                owner.getInventory().setItem(15, new ItemStack(Items.EMERALD, 13));
                corpse = CorpseEntity.createFromDeath(owner, Death.fromPlayer(owner));
                level.addFreshEntity(corpse);
                owner.getInventory().clearContent();
                owner.teleportTo(404, 91, 404);
            });
            after(80, () -> {
                check(CorpseCompat.hasPhysics(corpse), "fixture owns a real native corpse body before relocation");
                root = CorpseCompat.rootId(corpse);
                originalIds = Map.copyOf(NativeRagdollCompat.savedParts(level, root));
                check(originalIds.size() == 6, "fixture begins with six distinct logical limbs");
                limbId = originalIds.get(BodyPart.LEFT_ARM);
                moved = findOwned(BodyPart.LEFT_ARM);
                if (moved == null) throw new IllegalStateException("Missing source left arm");
                ServerSubLevel source = (ServerSubLevel) Sable.HELPER.getContaining(level, moved.getBlockPos());
                oldPhysicalId = source.getUniqueId();
                Vec3 armCenter = source.logicalPose().transformPosition(Vec3.atCenterOf(moved.getBlockPos()));
                HashSet<BlockPos> blocks = new HashSet<>();
                for (int z = 0; z < 3; z++) {
                    BlockPos pos = new BlockPos(400, 96, 400 + z);
                    blocks.add(pos);
                    level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                }
                // Sable automatically splits disconnected regions. Keep the off-center
                // destination attached to the vessel through a real face-adjacent block.
                BlockPos bridge = new BlockPos(401, 96, 400);
                blocks.add(bridge);
                level.setBlockAndUpdate(bridge, Blocks.STONE.defaultBlockState());
                BlockPos anchor = new BlockPos(400, 96, 400);
                vessel = SubLevelAssemblyHelper.assembleBlocks(level, anchor, blocks, BoundingBox3i.from(blocks));
                if (vessel == null) throw new IllegalStateException("Sable vessel assembly failed");
                BlockPos plotAnchor = vessel.getPlot().getCenterBlock();
                for (int z = 0; z < 3; z++) vesselBlocks.add(plotAnchor.offset(0, 0, z));
                vesselBlocks.add(plotAnchor.offset(1, 0, 0));
                destination = plotAnchor.offset(2, 0, 0);
                supportRotation = new Quaterniond(source.logicalPose().orientation());
                vessel.logicalPose().orientation().set(supportRotation);
                Vec3 currentPoint = vessel.logicalPose().transformPosition(Vec3.atCenterOf(destination));
                Vec3 delta = armCenter.subtract(currentPoint);
                vessel.logicalPose().position().add(delta.x, delta.y, delta.z);
                var physics = SubLevelPhysicsSystem.get(level);
                physics.getPipeline().teleport(vessel, vessel.logicalPose().position(), vessel.logicalPose().orientation());
                vessel.updateLastPose();
                Vec3 anchorWorld = vessel.logicalPose().transformPosition(Vec3.atCenterOf(plotAnchor));
                supportPoint = new Vector3d(anchorWorld.x, anchorWorld.y, anchorWorld.z);
                Vec3 localAnchor = Vec3.atCenterOf(plotAnchor);
                support = physics.getPipeline().addConstraint(null, vessel, new GenericConstraintConfiguration(
                        new Vector3d(supportPoint), new Vector3d(localAnchor.x, localAnchor.y, localAnchor.z),
                        new Quaterniond(supportRotation), new Quaterniond(), EnumSet.allOf(ConstraintJointAxis.class)));
                if (support == null) throw new IllegalStateException("Vessel support constraint failed");
                BlockPos old = moved.getBlockPos();
                CompoundTag identity = moved.saveWithFullMetadata(level.registryAccess()).getCompound("RagdollIdentity").copy();
                SubLevelAssemblyHelper.moveBlocks(level, new SubLevelAssemblyHelper.AssemblyTransform(
                        old, destination, 0, Rotation.NONE, level), List.of(old));
                check(level.getBlockState(old).isAir(), "real Sable move removes source block instead of duplicating it");
                check(level.getBlockEntity(destination) instanceof RagdollPartBlockEntity, "real Sable move recreates the limb at the destination plot position");
                moved = (RagdollPartBlockEntity) level.getBlockEntity(destination);
                check(identity.equals(moved.saveWithFullMetadata(level.registryAccess()).getCompound("RagdollIdentity")),
                        "Sable assembly preserves exact logical identity NBT");
                check(!oldPhysicalId.equals(vessel.getUniqueId()) && !destination.equals(plotAnchor),
                        "logical limb now lives off-center in a different physical vessel");
                check(Sable.HELPER.getContaining(level, destination) == vessel, "destination belongs to the actual native vessel");
            });
            after(40, () -> {
                verifyPose("after relocation");
                check(originalIds.equals(NativeRagdollCompat.savedParts(level, root)), "relocation retains all six logical IDs and original owner");
                check(NativeRagdollCompat.part(level, limbId) == vessel, "logical limb resolves to its new physical vessel");
                check(vesselBlocks.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.STONE)), "relocation preserves all unrelated vessel blocks");
                beforeTranslation = center();
                translationStart = ticks;
            });
            after(40, () -> {
                verifyPose("after native vessel translation");
                check(center().distanceTo(beforeTranslation) > 0.5, "relocated limb moves with the supported vessel");
                placeOwnerNearArm();
                nativeGrab(false);
                check(grabbed(), "ordinary native grab packet accepts the relocated limb");
            });
            after(5, () -> {
                check(grabbed(), "native grip survives ownership validation on a different physical vessel");
                nativeGrab(true);
                check(!grabbed(), "ordinary native release packet ends relocated-limb grip");
                placeOwnerNearArm();
                owner.setShiftKeyDown(true);
                try { corpse.interact(owner, InteractionHand.MAIN_HAND); }
                finally { owner.setShiftKeyDown(false); }
                check(owner.containerMenu instanceof CorpseInventoryContainer, "normal crouch interaction opens authoritative Corpse inventory after relocation");
                if (!(owner.containerMenu instanceof CorpseInventoryContainer menu)) throw new IllegalStateException("Native loot menu missing");
                menu.transferItems();
                check(owner.getInventory().getItem(8).is(Items.DIAMOND) && owner.getInventory().getItem(8).getCount() == 7
                                && owner.getInventory().getItem(15).is(Items.EMERALD) && owner.getInventory().getItem(15).getCount() == 13,
                        "native transfer preserves exact quantities and original inventory slots");
                check(corpse.isEmpty(), "native transfer empties the original corpse inventory exactly once");
                owner.closeContainer();
                corpse.discard();
            });
            after(20, () -> {
                check(!CorpseCompat.hasPhysics(corpse), "removing relocated corpse releases its runtime binding");
                check(!grabbed(), "corpse removal leaves no native grip");
                check(findOwned(BodyPart.LEFT_ARM) == null && countOwned() == 0, "cleanup removes all six owned limbs including the relocated one");
                check(!vessel.isRemoved() && SubLevelContainer.getContainer(level).getSubLevel(vessel.getUniqueId()) == vessel,
                        "corpse cleanup preserves unrelated physical vessel");
                check(vesselBlocks.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.STONE)) && level.getBlockState(destination).isAir(),
                        "cleanup removes only owned limb while retaining every vessel block");
                check(level.getEntitiesOfClass(ItemEntity.class, new AABB(380, 70, 380, 420, 110, 420)).stream()
                                .noneMatch(item -> item.getItem().is(Items.DIAMOND) || item.getItem().is(Items.EMERALD)),
                        "relocation and cleanup do not duplicate inventory into dropped items");
                finish(true);
            });
        }

        void verifyPose(String phase) {
            CompoundTag pose = ((CorpsePoseAccess)corpse).getRagRevivalPose();
            CompoundTag arm = pose.getList("parts", Tag.TAG_COMPOUND).getCompound(BodyPart.LEFT_ARM.ordinal());
            Vec3 rendered = corpse.position().add(CorpsePose.offset(arm));
            check(CorpseCompat.hasPhysics(corpse) && root.equals(CorpseCompat.rootId(corpse)), phase + ": original native body remains active");
            check(rendered.distanceTo(center()) < 0.05, phase + ": synchronized pose follows actual block center, not plot center");
            check(arm.hasUUID("sublevel") && vessel.getUniqueId().equals(arm.getUUID("sublevel")), phase + ": pose references the new physical sublevel");
            check(arm.hasUUID("limb") && limbId.equals(arm.getUUID("limb"))
                            && arm.contains("block", Tag.TAG_LONG) && BlockPos.of(arm.getLong("block")).equals(destination),
                    phase + ": client pose carries the exact logical identity and off-center block position");
            check(corpse.getBoundingBox().contains(center()), phase + ": interaction bounds contain the relocated limb");
            LOGGER.info("RAGREVIVAL_RELOCATION_SAMPLE phase={} logical={} physical={} block={} center={} poseCenter={}", phase, limbId, vessel.getUniqueId(), destination, center(), rendered);
            ServerSubLevel resolved = NativeRagdollCompat.part(level, limbId);
            LOGGER.info("RAGREVIVAL_RELOCATION_LOOKUP phase={} resolvedPhysical={} pose={} destinationCanonical={} movedRemoved={}", phase,
                    resolved == null ? null : resolved.getUniqueId(), arm, level.getBlockEntity(destination) == moved, moved.isRemoved());
            for (RagdollPartBlockEntity block : ownedBlocks()) {
                if (block.bodyPart() != BodyPart.LEFT_ARM) continue;
                var containing = Sable.HELPER.getContaining(level, block.getBlockPos());
                LOGGER.info("RAGREVIVAL_RELOCATION_BLOCK phase={} position={} physical={} canonical={} removed={} identity={}", phase,
                        block.getBlockPos(), containing == null ? null : containing.getUniqueId(),
                        level.getBlockEntity(block.getBlockPos()) == block, block.isRemoved(),
                        block.saveWithFullMetadata(level.registryAccess()).getCompound("RagdollIdentity"));
            }
        }
        Vec3 center() { return vessel.logicalPose().transformPosition(Vec3.atCenterOf(destination)); }
        void placeOwnerNearArm() {
            Vec3 point = center();
            owner.teleportTo(point.x, point.y - 0.6, point.z + 2);
            owner.setYRot(180); owner.setXRot(28);
            owner.getInventory().clearContent();
        }
        void nativeGrab(boolean release) {
            IPayloadContext context = (IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(), new Class<?>[]{IPayloadContext.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("player")) return owner;
                        if (method.getName().equals("enqueueWork")) { ((Runnable)args[0]).run(); return CompletableFuture.completedFuture(null); }
                        throw new UnsupportedOperationException(method.getName());
                    });
            RagdollGrabPacket.handle(new RagdollGrabPacket(destination, release), context);
        }
        boolean grabbed() {
            if (moved == null) return false;
            try { Field field = RagdollPartBlockEntity.class.getDeclaredField("grabbers"); field.setAccessible(true); return ((Map<?, ?>)field.get(moved)).containsKey(owner.getUUID()); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        List<RagdollPartBlockEntity> ownedBlocks() {
            List<RagdollPartBlockEntity> result = new ArrayList<>();
            for (ServerSubLevel part : SubLevelContainer.getContainer(level).getAllSubLevels()) {
                if (part.getPlot() == null) continue;
                for (var holder : part.getPlot().getLoadedChunks()) for (var be : holder.getChunk().getBlockEntities().values()) {
                    if (be instanceof RagdollPartBlockEntity block && block.getPersistentData().hasUUID("ragrevival:corpse_owner")
                            && corpse.getUUID().equals(block.getPersistentData().getUUID("ragrevival:corpse_owner"))) result.add(block);
                }
            }
            return result;
        }
        int countOwned() { return ownedBlocks().size(); }
        RagdollPartBlockEntity findOwned(BodyPart bodyPart) { return ownedBlocks().stream().filter(block -> block.bodyPart() == bodyPart).findFirst().orElse(null); }
        void advance() {
            ticks++;
            if (owner != null && owner.isAlive() && !owner.isRemoved()) {
                ReactionSuppressions.suppress(owner, level.getGameTime(), 5);
                owner.doTick();
            }
            if (translationStart >= 0 && ticks <= translationStart + 30) {
                double fraction = (ticks - translationStart) / 30D;
                support.setFrame1(new Vector3d(supportPoint).add(0, fraction * 0.8, 0), new Quaterniond(supportRotation));
                SubLevelPhysicsSystem.get(level).getPipeline().wakeUp(vessel);
            }
            while (!steps.isEmpty() && steps.peek().tick <= ticks) steps.remove().action.run();
        }
        @SuppressWarnings("unchecked") <T> T playerField(String name) {
            try { Field field = PlayerList.class.getDeclaredField(name); field.setAccessible(true); return (T)field.get(server.getPlayerList()); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        ServerPlayer player() {
            ServerPlayer player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "RelocateOwner"), ClientInformation.createDefault());
            player.connection = new PacketSink(server, player);
            player.setGameMode(GameType.CREATIVE); player.setNoGravity(true);
            player.setPos(400.5, 89.5, 400.5);
            this.<List<ServerPlayer>>playerField("players").add(player);
            this.<Map<UUID, ServerPlayer>>playerField("playersByUUID").put(player.getUUID(), player);
            level.addNewPlayer(player);
            return player;
        }
        void check(boolean pass, String label) {
            if (pass) { passed++; LOGGER.info("RAGREVIVAL_RELOCATION PASS {}", label); }
            else { failures.add(label); LOGGER.error("RAGREVIVAL_RELOCATION FAIL {}", label); }
        }
        void finish(boolean completed) {
            try {
                if (corpse != null && !corpse.isRemoved()) corpse.discard();
                if (owner != null) {
                    owner.closeContainer();
                    DownedManager.logout(new PlayerEvent.PlayerLoggedOutEvent(owner));
                    server.getPlayerList().remove(owner);
                    ((EmbeddedChannel)owner.connection.getConnection().channel()).finishAndReleaseAll();
                }
                if (support != null && support.isValid()) support.remove();
                if (vessel != null && !vessel.isRemoved()) SubLevelContainer.getContainer(level).removeSubLevel(vessel, SubLevelRemovalReason.REMOVED);
            } finally {
                Main.SERVER_CONFIG.corpseForceDespawnTime.set(forcedLifetime);
                LOGGER.info("RAGREVIVAL_RELOCATION {} passed={} failed={} ticks={}", completed && failures.isEmpty() ? "PASSED" : "FAILED", passed, failures.size(), ticks);
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
