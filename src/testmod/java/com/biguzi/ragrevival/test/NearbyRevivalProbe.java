package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.RevivalConfig;
import com.biguzi.ragrevival.network.InputAction;
import com.biguzi.ragrevival.network.InputPayload;
import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.leo.ragdollreactions.physics.ReactionSuppressions;
import dev.leo.sableplayerragdoll.api.DespawnCondition;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.api.RagdollLaunchOptions;
import dev.leo.sableplayerragdoll.api.RagdollLimbOptions;
import dev.leo.sableplayerragdoll.api.RagdollPoseSnapshot;
import dev.leo.sableplayerragdoll.entity.RagdollSeatEntity;
import dev.leo.sableplayerragdoll.physics.RagdollExpireHelper;
import dev.leo.sableplayerragdoll.physics.RagdollSessionManager;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/** Nearby-body cleanup regression, using native players and physics without client windows. */
final class NearbyRevivalProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static NearbyRevivalProbe current;
    private final MinecraftServer server;
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private final List<ServerPlayer> players = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private ServerPlayer first;
    private ServerPlayer second;
    private ServerPlayer heartbeatActor;
    private ServerPlayer heartbeatTarget;
    private Entity firstSeat;
    private ServerSubLevel firstRoot;
    private Entity secondSeat;
    private UUID secondRoot;
    private Set<UUID> secondParts;
    private Vec3 site;
    private String scenario;
    private boolean watchSelfRevival;
    private int ticks;
    private int cursor;
    private int passed;

    private NearbyRevivalProbe(MinecraftServer server) { this.server = server; }
    private record Step(int tick, Runnable action) {}

    static boolean active() { return current != null; }

    static String start(MinecraftServer server) {
        if (current != null) return "A nearby revival probe is already active.";
        if (!server.getPlayerList().getPlayers().isEmpty())
            return "Disconnect all clients first; this probe is for the isolated test server.";
        current = new NearbyRevivalProbe(server);
        current.plan();
        return "Nearby revival probe started; see RAGREVIVAL_NEARBY in the server log.";
    }

    static void tick(MinecraftServer server) {
        if (current == null || current.server != server) return;
        try { current.advance(); }
        catch (Throwable failure) {
            LOGGER.error("RAGREVIVAL_NEARBY ABORTED at tick {}", current.ticks, failure);
            current.finish(false);
        }
    }

    private void after(int delay, Runnable action) { cursor += delay; steps.add(new Step(cursor, action)); }

    private void plan() {
        selfRevival("overlapping self-revival", 0);
        selfRevival("adjacent self-revival", 1.4);
        cleanupScenario("command revival", () -> {
            try {
                int result = server.getCommands().getDispatcher().execute("revive NearbyFirst",
                        server.createCommandSourceStack().withPermission(4).withSuppressedOutput());
                check(result == 1 && !DownedManager.isDowned(first), "command revives only its named player");
            } catch (Exception failure) { throw new IllegalStateException("Revive command failed", failure); }
        });
        cleanupScenario("terminal death", () -> {
            DownedManager.finishDeath(first);
            check(!first.isAlive() && !DownedManager.isDowned(first), "terminal death clears only its own state");
        });
        cleanupScenario("logout", () -> {
            DownedManager.logout(new PlayerEvent.PlayerLoggedOutEvent(first));
            server.getPlayerList().remove(first);
            check(DownedManager.isDowned(first), "logout retains its own persisted downed state");
        });
        pair("ordinary ragdoll expiry", 0, true);
        after(1, () -> {
            var root = RagdollSessionManager.activeRagdollForPlayer(first.serverLevel(), first.getUUID());
            if (root == null) throw new IllegalStateException("Missing ordinary ragdoll fixture");
            RagdollExpireHelper.expire(SubLevelPhysicsSystem.get(first.serverLevel()), first.serverLevel(),
                    root, "ordinary regression expiry");
            check(!DownedManager.isDowned(first) && first.isAlive() && !first.isPassenger() && firstSeat.isRemoved(),
                    "ordinary expiration releases its healthy rider");
            check(RagdollSessionManager.activeRagdollForPlayer(first.serverLevel(), first.getUUID()) == null,
                    "ordinary expiration unregisters its own body");
            neighborUnchanged("immediately after ordinary expiration");
        });
        after(3, () -> neighborUnchanged("three ticks after ordinary expiration"));
        after(1, () -> finish(true));
    }

    private void pair(String name, double offset) {
        pair(name, offset, false);
    }

    private void pair(String name, double offset, boolean ordinaryFirst) {
        after(1, () -> {
            cleanupPlayers();
            scenario = name;
            var spawn = server.overworld().getSharedSpawnPos();
            int y = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
            site = new Vec3(spawn.getX() + 0.5, y + 1, spawn.getZ() + 0.5);
            first = player("NearbyFirst", site);
            second = player("NearbySecond", site.add(offset, 0, 0));
        });
        // Native players have vanilla spawn immunity independent of invulnerableTime.
        after(70, () -> {
            // Undo any player collision/falling during the spawn-immunity wait.
            first.teleportTo(site.x, site.y, site.z);
            second.teleportTo(site.x + offset, site.y, site.z);
            first.setDeltaMovement(Vec3.ZERO);
            second.setDeltaMovement(Vec3.ZERO);
            if (ordinaryFirst) {
                RagdollAPI.launch(first, Vec3.ZERO, RagdollLaunchOptions.builder()
                                .autoSeat(true).lockDismount(false).despawnConditions(List.of(DespawnCondition.never())).build(),
                        new RagdollPoseSnapshot(RagdollLimbOptions.defaults(), first.yBodyRot));
                check(!DownedManager.isDowned(first), "ordinary ragdoll rider has no downed state");
            } else down(first);
            down(second);
        });
        after(10, () -> {
            check(first.getVehicle() instanceof RagdollSeatEntity && second.getVehicle() instanceof RagdollSeatEntity,
                    "both players are mounted before cleanup");
            firstSeat = first.getVehicle();
            secondSeat = second.getVehicle();
            var root = RagdollSessionManager.activeRagdollForPlayer(second.serverLevel(), second.getUUID());
            if (secondSeat == null || root == null) throw new IllegalStateException("Missing neighboring ragdoll fixture");
            secondRoot = root.getUniqueId();
            secondParts = Set.copyOf(RagdollBridge.bodyPartIds(second));
            check(secondParts.size() == 6 && first.getVehicle() != secondSeat,
                    "neighbor has a distinct six-part body and seat");
            firstRoot = RagdollSessionManager.activeRagdollForPlayer(first.serverLevel(), first.getUUID());
            if (firstRoot == null || firstRoot.getPlot() == null) throw new IllegalStateException("Missing first ragdoll plot");
            var bounds = firstRoot.getPlot().getBoundingBox();
            AABB query = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1);
            var candidates = first.serverLevel().getEntitiesOfClass(RagdollSeatEntity.class, query);
            check(candidates.contains(firstSeat), "native cleanup query includes its own seat");
            if (offset == 0) check(candidates.contains(secondSeat),
                    "native unfiltered cleanup query includes the neighboring seat");
            else LOGGER.info("RAGREVIVAL_NEARBY geometry {}: neighboringSeatInCleanupQuery={}",
                    scenario, candidates.contains(secondSeat));
        });
    }

    private void selfRevival(String name, double offset) {
        pair(name, offset);
        after(1, () -> {
            first.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE, 2));
            heartbeatActor = first;
            heartbeatTarget = first;
            feed();
            watchSelfRevival = true;
            check(DownedManager.isFeeding(first), "self-feed starts while both players are downed");
        });
        after(RevivalConfig.FEEDING_TICKS.get() + 3, () -> {
            heartbeatActor = null;
            heartbeatTarget = null;
            check(!watchSelfRevival && !DownedManager.isDowned(first) && !first.isPassenger() && firstSeat.isRemoved(),
                    "self-revival completes and releases its own seat");
            check(first.getMainHandItem().getCount() == 1, "self-revival consumes exactly one item");
            ownBodyRemoved();
            neighborUnchanged("after self-revival settles");
            Vec3 body = RagdollBridge.worldPosition(second);
            first.teleportTo(body.x + 1.4, body.y, body.z);
            first.setDeltaMovement(Vec3.ZERO);
        });
        after(2, () -> {
            heartbeatActor = first;
            heartbeatTarget = second;
            feed();
            check(DownedManager.isFeeding(first), "revived player can start feeding the remaining neighbor");
        });
        after(RevivalConfig.FEEDING_TICKS.get() + 3, () -> {
            heartbeatActor = null;
            heartbeatTarget = null;
            check(!DownedManager.isDowned(second) && !second.isPassenger(), "neighbor revives normally when actually fed");
            check(first.getMainHandItem().isEmpty(), "teammate revival consumes exactly one additional item");
        });
    }

    private void cleanupScenario(String name, Runnable action) {
        pair(name, 0);
        after(1, () -> {
            action.run();
            check(firstSeat.isRemoved(), "cleanup removes its own original seat");
            ownBodyRemoved();
            neighborUnchanged("immediately after cleanup");
        });
        after(3, () -> neighborUnchanged("three ticks after cleanup"));
    }

    private void neighborUnchanged(String phase) {
        check(DownedManager.isDowned(second) && second.isAlive(), phase + ": neighbor stays downed and alive");
        check(secondSeat != null && !secondSeat.isRemoved() && second.getVehicle() == secondSeat,
                phase + ": original neighboring seat survives with its passenger");
        var root = RagdollSessionManager.activeRagdollForPlayer(second.serverLevel(), second.getUUID());
        check(root != null && secondRoot.equals(root.getUniqueId())
                        && secondParts.equals(Set.copyOf(RagdollBridge.bodyPartIds(second))),
                phase + ": original neighboring body remains registered");
    }

    private void ownBodyRemoved() {
        check(firstRoot.isRemoved()
                        && RagdollSessionManager.activeRagdollForPlayer(first.serverLevel(), first.getUUID()) == null,
                "cleanup removes and unregisters its own original body");
    }

    private void down(ServerPlayer player) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.setHealth(player.getMaxHealth());
        player.invulnerableTime = 0;
        player.hurt(player.damageSources().generic(), 1000);
        check(DownedManager.isDowned(player), "lethal damage downs " + player.getScoreboardName());
        if (!DownedManager.isDowned(player)) throw new IllegalStateException("Could not down fixture player");
    }

    private void feed() {
        if (heartbeatActor != null) DownedManager.input(heartbeatActor,
                new InputPayload(heartbeatTarget.getUUID(), InteractionHand.MAIN_HAND, InputAction.FEED));
    }

    @SuppressWarnings("unchecked")
    private ServerPlayer player(String name, Vec3 pos) {
        ServerPlayer player = new ServerPlayer(server, server.overworld(),
                new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        player.connection = new PacketSink(server, player);
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(pos.x, pos.y, pos.z);
        field(server.getPlayerList(), "players", List.class).add(player);
        field(server.getPlayerList(), "playersByUUID", Map.class).put(player.getUUID(), player);
        players.add(player);
        player.serverLevel().addNewPlayer(player);
        return player;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(PlayerList list, String name, Class<T> type) {
        try {
            Field field = PlayerList.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T)field.get(list);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Fixture registration failed", failure); }
    }

    private void advance() {
        ticks++;
        // The manager runs before this LOWEST-priority probe. Check the same tick that
        // self-revival removes a body, before ensureDowned can repair its neighbor next tick.
        if (watchSelfRevival && !DownedManager.isDowned(first)) {
            neighborUnchanged("on the self-revival tick");
            watchSelfRevival = false;
        }
        for (ServerPlayer player : players) {
            if (player.isRemoved()) continue;
            ReactionSuppressions.suppress(player, player.level().getGameTime(), 5);
            if (player.isAlive()) player.doTick();
        }
        feed();
        while (!steps.isEmpty() && steps.peek().tick <= ticks) steps.remove().action.run();
    }

    private void check(boolean condition, String name) {
        String label = scenario + ": " + name;
        if (condition) { passed++; LOGGER.info("RAGREVIVAL_NEARBY PASS {}", label); }
        else { failures.add(label); LOGGER.error("RAGREVIVAL_NEARBY FAIL {}", label); }
    }

    private void cleanupPlayers() {
        heartbeatActor = null;
        heartbeatTarget = null;
        watchSelfRevival = false;
        for (ServerPlayer player : players) {
            try {
                if (!player.isRemoved()) {
                    if (DownedManager.isDowned(player)) DownedManager.revive(player);
                    DownedManager.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
                    server.getPlayerList().remove(player);
                }
                ((EmbeddedChannel)player.connection.getConnection().channel()).finishAndReleaseAll();
            } catch (Throwable failure) {
                LOGGER.error("RAGREVIVAL_NEARBY cleanup failed for {}", player.getScoreboardName(), failure);
                failures.add("fixture cleanup");
            }
        }
        players.clear();
    }

    private void finish(boolean completed) {
        try { cleanupPlayers(); }
        finally {
            LOGGER.info("RAGREVIVAL_NEARBY {} passed={} failed={} ticks={}",
                    completed && failures.isEmpty() ? "PASSED" : "FAILED", passed, failures.size(), ticks);
            current = null;
        }
    }

    private static final class PacketSink extends ServerGamePacketListenerImpl {
        PacketSink(MinecraftServer server, ServerPlayer player) {
            super(server, connection(), player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        private static Connection connection() {
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            return connection;
        }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, PacketSendListener listener) {}
    }
}
