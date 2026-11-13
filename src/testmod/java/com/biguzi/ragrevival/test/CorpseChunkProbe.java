package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.compat.corpse.CorpseCompat;
import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import de.maxhenkel.corpse.corelib.death.Death;
import de.maxhenkel.corpse.entities.CorpseEntity;
import dev.leo.ragdollreactions.physics.ReactionSuppressions;
import com.biguzi.ragrevival.ragdoll.NativeRagdollCompat;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Walks a registered native player out of view distance; never invokes entity/chunk unload itself. */
@EventBusSubscriber(modid = "ragrevival_tests", value = Dist.DEDICATED_SERVER)
public final class CorpseChunkProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Run current;

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ragrevivaltest").requires(source -> source.hasPermission(4))
                .then(Commands.literal("corpse-chunk-cycle").executes(context -> {
                    if (current != null || !context.getSource().getServer().getPlayerList().getPlayers().isEmpty()) return 0;
                    try { current = new Run(context.getSource().getServer()); current.begin(); return 1; }
                    catch (Throwable failure) { LOGGER.error("RAGREVIVAL_CHUNK preparation failed", failure); if (current != null) current.finish(false); return 0; }
                })));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void left(EntityLeaveLevelEvent event) {
        if (current != null && event.getEntity().getUUID().equals(current.corpseId)) {
            current.observedLeave = true;
            current.observedRemoval = event.getEntity().getRemovalReason();
            LOGGER.info("RAGREVIVAL_CHUNK LEAVE tick={} reason={}", current.ticks, current.observedRemoval);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (current == null || current.server != event.getServer()) return;
        try { current.tick(); }
        catch (Throwable failure) { LOGGER.error("RAGREVIVAL_CHUNK ABORTED tick={}", current.ticks, failure); current.finish(false); }
    }

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        final Vec3 site = new Vec3(576.5, 128, 576.5);
        ServerPlayer observer;
        CorpseEntity corpse;
        UUID corpseId;
        UUID controlId;
        UUID oldRoot;
        List<UUID> oldParts;
        CompoundTag inventory;
        Vec3 savedPosition;
        Entity.RemovalReason observedRemoval;
        boolean observedLeave;
        int ticks;
        int returnedAt;
        int passed;
        int failed;

        Run(MinecraftServer server) { this.server = server; level = server.overworld(); }

        void begin() throws Exception {
            observer = player();
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++)
                level.setBlockAndUpdate(BlockPos.containing(site).offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
            observer.getInventory().setItem(8, new ItemStack(Items.DIAMOND, 7));
            observer.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
            observer.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
            corpse = CorpseEntity.createFromDeath(observer, Death.fromPlayer(observer));
            corpse.setPos(site);
            level.addFreshEntity(corpse);
            corpseId = corpse.getUUID();
            Vec3 controlPosition = site.add(64, 1, 0);
            level.getChunkAt(BlockPos.containing(controlPosition));
            Entity control = EntityType.ARMOR_STAND.create(level);
            if (control == null) throw new IllegalStateException("Could not create vanilla chunk control");
            control.setPos(controlPosition); control.setNoGravity(true);
            level.addFreshEntity(control);
            controlId = control.getUUID();
            observer.getInventory().clearContent();
            observer.teleportTo(site.x + 3, site.y, site.z + 3);
            level.getChunkSource().move(observer);
            check(!level.getForcedChunks().contains(ChunkPos.asLong(corpse.blockPosition())), "distant fixture chunk has no vanilla force-load ticket");
            LOGGER.info("RAGREVIVAL_CHUNK START pid={} corpse={} site={} viewDistance={}", ProcessHandle.current().pid(), corpseId,
                    site, server.getPlayerList().getViewDistance());
        }

        void tick() {
            ticks++;
            ReactionSuppressions.suppress(observer, level.getGameTime(), 5);
            observer.doTick();
            if (ticks == 100) {
                check(CorpseCompat.hasPhysics(corpse), "distant entity-ticking chunk starts a native corpse body");
                oldRoot = CorpseCompat.rootId(corpse);
                if (oldRoot == null) throw new IllegalStateException("Distant native body never started");
                oldParts = List.copyOf(NativeRagdollCompat.linkedParts(level, oldRoot));
                inventory = corpse.getDeath().toNBT(level.registryAccess());
                savedPosition = corpse.position();
                check(level.getEntity(controlId) != null, "plain vanilla entity in a separate nearby chunk starts loaded");
                observer.teleportTo(0.5, 128, 0.5);
                // A real client's teleport acknowledgement invokes this normal tracker update.
                // Packet-sink fixtures have no client to acknowledge the teleport.
                level.getChunkSource().move(observer);
                LOGGER.info("RAGREVIVAL_CHUNK DEPART tick={} root={} parts={} savedPosition={}", ticks, oldRoot, oldParts.size(), savedPosition);
            }
            if (ticks > 100 && returnedAt == 0 && ticks % 20 == 0) {
                boolean gone = level.getEntity(corpseId) == null;
                boolean unbound = !CorpseCompat.hasPhysics(corpse) && !CorpseCompat.ownsRoot(oldRoot);
                long loadedParts = oldParts.stream().filter(id -> {
                    var part = SubLevelContainer.getContainer(level).getSubLevel(id);
                    return part != null && !part.isRemoved();
                }).count();
                boolean controlGone = level.getEntity(controlId) == null;
                LOGGER.info("RAGREVIVAL_CHUNK AWAY tick={} entityLoaded={} bound={} loadedNativeParts={} chunkLoaded={} plainControlLoaded={} observer={}", ticks,
                        !gone, !unbound, loadedParts, level.hasChunkAt(BlockPos.containing(site)), !controlGone, observer.position());
                if (gone && unbound && loadedParts == 0 && controlGone) {
                    check(controlGone, "plain vanilla control also unloads after the normal player chunk-tracker move");
                    // NeoForge reports tracking-end before an entity necessarily has a removal reason.
                    check(observedLeave && !level.hasChunkAt(BlockPos.containing(site)),
                            "distance alone emits the engine entity-leave event and unloads the actual world chunk");
                    check(gone, "corpse entity leaves active entity storage outside player view distance");
                    check(unbound, "out-of-range chunk eviction releases the corpse runtime binding");
                    check(loadedParts == 0, "all six native parts leave active sublevel storage after distant chunk eviction");
                    returnedAt = ticks;
                    observer.teleportTo(site.x + 3, site.y, site.z + 3);
                    level.getChunkSource().move(observer);
                } else if (ticks >= 700) {
                    check(false, "distant chunk, corpse binding, and native parts unload within thirty seconds");
                    finish(false);
                }
            }
            if (returnedAt != 0 && ticks == returnedAt + 200) {
                Entity loaded = level.getEntity(corpseId);
                check(loaded instanceof CorpseEntity, "returning within view distance restores the original corpse UUID");
                if (loaded instanceof CorpseEntity restored) {
                    corpse = restored;
                    check(inventory.equals(corpse.getDeath().toNBT(level.registryAccess())), "actual chunk reload preserves all inventory compartments exactly");
                    check(CorpseCompat.hasPhysics(corpse), "actual chunk reload resumes native articulated physics");
                    check(CorpsePose.valid(((CorpsePoseAccess)corpse).getRagRevivalPose()), "reloaded native body maintains all six valid limb transforms");
                    check(corpse.position().distanceTo(savedPosition) < 0.25 && corpse.getY() >= site.y - 0.25,
                            "reloaded body settles within a quarter-block of its original supporting position");
                    UUID root = CorpseCompat.rootId(corpse);
                    double speed = 0;
                    int parts = 0;
                    if (root != null) for (UUID id : NativeRagdollCompat.linkedParts(level, root)) {
                        var part = SubLevelContainer.getContainer(level).getSubLevel(id);
                        if (part instanceof ServerSubLevel nativePart && !nativePart.isRemoved()) {
                            parts++;
                            var handle = SubLevelPhysicsSystem.get(level).getPhysicsHandle(nativePart);
                            if (handle != null && handle.isValid()) speed = Math.max(speed, handle.getLinearVelocity().length());
                        }
                    }
                    LOGGER.info("RAGREVIVAL_CHUNK RETURN tick={} originalRoot={} currentRoot={} parts={} maximumLimbSpeed={} before={} after={}",
                            ticks, oldRoot, root, parts, speed, savedPosition, corpse.position());
                    check(parts == 6, "restored corpse owns exactly six active native parts");
                    check(speed < 0.1, "restored native joints settle without sustained artificial motion");
                }
                finish(true);
            }
        }

        void check(boolean condition, String label) {
            if (condition) passed++; else failed++;
            LOGGER.info("RAGREVIVAL_CHUNK {} {}", condition ? "PASS" : "FAIL", label);
        }

        @SuppressWarnings("unchecked")
        ServerPlayer player() throws Exception {
            ServerPlayer player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "ChunkObserver"), ClientInformation.createDefault());
            player.connection = new PacketSink(server, player);
            player.setGameMode(GameType.CREATIVE); player.getAbilities().flying = true;
            player.setPos(site);
            Field players = PlayerList.class.getDeclaredField("players"); players.setAccessible(true);
            ((List<ServerPlayer>)players.get(server.getPlayerList())).add(player);
            Field byId = PlayerList.class.getDeclaredField("playersByUUID"); byId.setAccessible(true);
            ((Map<UUID, ServerPlayer>)byId.get(server.getPlayerList())).put(player.getUUID(), player);
            level.addNewPlayer(player);
            return player;
        }

        void finish(boolean completed) {
            if (corpse != null && !corpse.isRemoved()) corpse.setRemoved(Entity.RemovalReason.DISCARDED);
            if (controlId != null && level.getEntity(controlId) != null) level.getEntity(controlId).setRemoved(Entity.RemovalReason.DISCARDED);
            if (observer != null) {
                server.getPlayerList().remove(observer);
                ((EmbeddedChannel)observer.connection.getConnection().channel()).finishAndReleaseAll();
            }
            if (!completed && failed == 0) failed++;
            LOGGER.info("RAGREVIVAL_CHUNK {} passed={} failed={} ticks={} pid={}", completed && failed == 0 ? "PASSED" : "FAILED",
                    passed, failed, ticks, ProcessHandle.current().pid());
            current = null;
        }
    }

    private static final class PacketSink extends ServerGamePacketListenerImpl {
        PacketSink(MinecraftServer server, ServerPlayer player) { super(server, connection(), player, CommonListenerCookie.createInitial(player.getGameProfile(), false)); }
        private static Connection connection() { Connection connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection); return connection; }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, PacketSendListener listener) {}
    }
}
