package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.RevivalConfig;
import com.biguzi.ragrevival.compat.CarryOnCompat;
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
import dev.leo.sableplayerragdoll.physics.RagdollControlHelper;
import dev.leo.sableplayerragdoll.physics.RagdollSessionManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Opt-in dedicated-server integration harness. Never packaged in the distributable mod. */
@Mod(value = "ragrevival_tests", dist = Dist.DEDICATED_SERVER)
public final class RagRevivalTestMod {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Run current;

    public RagRevivalTestMod() {
        NeoForge.EVENT_BUS.addListener(RagRevivalTestMod::commands);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RagRevivalTestMod::tick);
    }

    private static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ragrevivaltest").requires(source -> source.hasPermission(4))
                .then(Commands.literal("revive").then(Commands.argument("target", EntityArgument.player()).executes(context -> {
                    ServerPlayer player = EntityArgument.getPlayer(context, "target");
                    boolean wasDowned = DownedManager.isDowned(player);
                    DownedManager.revive(player);
                    context.getSource().sendSuccess(() -> Component.literal("Revive " + player.getScoreboardName()
                            + ": wasDowned=" + wasDowned), false);
                    return wasDowned ? 1 : 0;
                })))
                .then(Commands.literal("codec").executes(context -> {
                    int[] result = new int[2];
                    StatePayloadProbe.run((passed, name) -> {
                        result[passed ? 0 : 1]++;
                        LOGGER.info("RAGREVIVAL_CODEC {} {}", passed ? "PASS" : "FAIL", name);
                    });
                    context.getSource().sendSuccess(() -> Component.literal("Codec checks: " + result[0] + " passed, " + result[1] + " failed."), false);
                    return result[1] == 0 ? 1 : 0;
                }))
                .then(Commands.literal("run").executes(context -> {
                    if (current != null) { context.getSource().sendFailure(Component.literal("A test run is already active.")); return 0; }
                    MinecraftServer server = context.getSource().getServer();
                    ServerPlayer one = server.getPlayerList().getPlayerByName("ReviveOne");
                    ServerPlayer two = server.getPlayerList().getPlayerByName("ReviveTwo");
                    if (one == null || two == null) {
                        context.getSource().sendFailure(Component.literal("Connect ReviveOne and ReviveTwo first.")); return 0;
                    }
                    current = new Run(server, one, two);
                    current.plan();
                    context.getSource().sendSuccess(() -> Component.literal("RagRevival integration run started; leave both clients idle. See server log."), true);
                    return 1;
                })));
    }

    private static void tick(ServerTickEvent.Post event) {
        if (current == null || current.server != event.getServer()) return;
        try { current.tick(); }
        catch (Throwable failure) {
            LOGGER.error("RAGREVIVAL_TEST ABORTED at tick {}", current.ticks, failure);
            current.finish(false);
        }
    }

    private record Step(int tick, Runnable action) {}

    private static final class Run {
        private final MinecraftServer server;
        private ServerPlayer target;
        private ServerPlayer rescuer;
        private final ArrayDeque<Step> steps = new ArrayDeque<>();
        private final List<String> failures = new ArrayList<>();
        private final boolean originalKeepInventory;
        private final boolean originalImmediateRespawn;
        private final boolean originalMobSpawning;
        private final boolean originalNaturalRegeneration;
        private final double originalRestoredHealthFraction;
        private final double originalMaxHealthBase;
        private Zombie zombie;
        private Vec3 site;
        private InputAction heartbeat;
        private int ticks;
        private int cursor;
        private int passed;
        private long savedDeadline;
        private UUID ordinaryRoot;
        private Vec3 movementStart;
        private boolean moveDowned;

        Run(MinecraftServer server, ServerPlayer target, ServerPlayer rescuer) {
            this.server = server; this.target = target; this.rescuer = rescuer;
            originalKeepInventory = server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
            originalImmediateRespawn = server.getGameRules().getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN);
            originalMobSpawning = server.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            originalNaturalRegeneration = server.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
            originalRestoredHealthFraction = RevivalConfig.RESTORED_HEALTH_FRACTION.get();
            originalMaxHealthBase = target.getAttribute(Attributes.MAX_HEALTH).getBaseValue();
        }

        private void after(int delay, Runnable action) { cursor += delay; steps.add(new Step(cursor, action)); }

        private void plan() {
            after(1, () -> {
                StatePayloadProbe.run(this::check);
                if (!target.isAlive()) target = respawn(target);
                if (!rescuer.isAlive()) rescuer = respawn(rescuer);
                DownedManager.revive(target); DownedManager.revive(rescuer);
                RagdollBridge.release(target); RagdollBridge.release(rescuer);
                CarryOnCompat.releaseForDowning(target); CarryOnCompat.releaseForDowning(rescuer);
                RevivalConfig.RESTORED_HEALTH_FRACTION.set(0.5);
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
                target.setGameMode(GameType.SURVIVAL); rescuer.setGameMode(GameType.SURVIVAL);
                server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(false, server);
                server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, server);
                for (var level : server.getAllLevels()) {
                    List<Entity> ambient = new ArrayList<>();
                    level.getAllEntities().forEach(entity -> { if (entity instanceof Mob) ambient.add(entity); });
                    ambient.forEach(Entity::discard);
                }
                server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
                site = RagdollBridge.worldPosition(rescuer);
                resetPlayer(target); resetPlayer(rescuer);
                target.teleportTo(site.x, site.y, site.z);
                rescuer.teleportTo(site.x + 1.8, site.y, site.z);
                target.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                target.experienceLevel = 7; target.totalExperience = 91; target.experienceProgress = 0;
                rescuer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE, 4));
            });
            // ServerPlayer has a separate 60-tick spawn immunity timer after fixture respawn.
            after(70, () -> {
                zombie = EntityType.ZOMBIE.create(target.serverLevel());
                if (zombie == null) throw new IllegalStateException("Could not create test zombie");
                zombie.setNoAi(true); zombie.setPos(site.x + 8, site.y, site.z);
                target.serverLevel().addFreshEntity(zombie);
                zombie.setTarget(target);
                target.invulnerableTime = 0;
                target.hurt(target.damageSources().generic(), 1000);
                check(DownedManager.isDowned(target), "lethal damage enters downed state");
                check(target.isAlive() && target.getHealth() == 1, "downing prevents normal death");
                check(target.getInventory().countItem(Items.DIAMOND) == 3 && target.experienceLevel == 7,
                        "inventory and XP remain intact when downed");
                check(zombie.getTarget() == null, "existing mob target is cleared");
            });
            after(40, () -> {
                check(target.isPassenger(), "downed player mounted in native Sable ragdoll");
                check(!RagdollBridge.worldBodyBoxes(target).isEmpty(), "world-space physics body bounds available");
                target.invulnerableTime = 0;
                target.hurt(target.damageSources().generic(), 1000);
                check(DownedManager.isDowned(target) && target.getHealth() == 1, "downed player ignores damage");
                zombie.setTarget(target);
                check(zombie.getTarget() == null, "mob cannot acquire downed player as a new target");
                EntityTravelToDimensionEvent travel = new EntityTravelToDimensionEvent(target, net.minecraft.world.level.Level.NETHER);
                NeoForge.EVENT_BUS.post(travel);
                check(travel.isCanceled(), "downed dimension transfer is rejected");
                persistedSnapshot();
                savedDeadline = target.getPersistentData().getCompound("ragrevival:downed").getLong("deadline");
                DownedManager.logout(new PlayerEvent.PlayerLoggedOutEvent(target));
                check(DownedManager.isDowned(target), "logout callback retains persisted downed state");
            });
            after(10, () -> {
                DownedManager.login(new PlayerEvent.PlayerLoggedInEvent(target));
                check(target.getPersistentData().getCompound("ragrevival:downed").getLong("deadline") == savedDeadline,
                        "login callback retains original deadline");
                check(DownedManager.remainingMillis(target) < RevivalConfig.DOWNED_SECONDS.get() * 1000L,
                        "lifecycle callbacks do not reset elapsed time");
            });
            after(40, () -> {
                nearTarget();
                check(RagdollBridge.canReach(rescuer, target), "server can reach real Sable body from nearby player");
                boolean ready = rescuer.isAlive() && !rescuer.isSpectator() && !DownedManager.isDowned(rescuer)
                        && !CarryOnCompat.isCarrying(rescuer) && !rescuer.isPassenger() && !rescuer.isVehicle()
                        && rescuer.getMainHandItem().is(DownedManager.REVIVAL_ITEMS);
                check(ready, "rescuer ready before feeding: alive=" + rescuer.isAlive()
                        + " spectator=" + rescuer.isSpectator() + " downed=" + DownedManager.isDowned(rescuer)
                        + " carrying=" + CarryOnCompat.isCarrying(rescuer) + " passenger=" + rescuer.isPassenger()
                        + " vehicle=" + rescuer.isVehicle() + " tagged=" + rescuer.getMainHandItem().is(DownedManager.REVIVAL_ITEMS));
                send(rescuer, InputAction.FEED);
                check(DownedManager.isBusy(rescuer), "feeding starts for held tagged item");
                for (int i = 0; i < 100; i++) send(rescuer, InputAction.FEED);
                check(DownedManager.isDowned(target) && apples() == 4, "duplicate input packets cannot finish feeding instantly");
                ServerPlayer contender = new ServerPlayer(server, target.serverLevel(),
                        new GameProfile(UUID.fromString("c99df12a-6ee3-4fb1-a03e-372098ed7e80"), "TestContender"),
                        ClientInformation.createDefault());
                contender.setPos(rescuer.position());
                contender.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE, 4));
                send(contender, InputAction.FEED);
                check(!DownedManager.isBusy(contender), "target lock rejects simultaneous second server actor");
                heartbeat = InputAction.FEED;
            });
            after(8, () -> {
                heartbeat = null; send(rescuer, InputAction.RELEASE);
                check(!DownedManager.isBusy(rescuer) && apples() == 4 && DownedManager.isDowned(target),
                        "releasing feeding cancels without consuming");
                send(rescuer, InputAction.FEED);
            });
            after(10, () -> {
                check(!DownedManager.isBusy(rescuer) && apples() == 4, "missing input heartbeat expires feeding lease");
                send(rescuer, InputAction.FEED);
                heartbeat = InputAction.FEED;
            });
            after(3, () -> {
                Vec3 body = RagdollBridge.worldPosition(target);
                rescuer.teleportTo(body.x + 20, site.y, body.z);
            });
            after(3, () -> {
                heartbeat = null;
                check(!DownedManager.isBusy(rescuer) && apples() == 4 && DownedManager.isDowned(target),
                        "moving out of real server reach cancels without consuming");
                nearTarget(); send(rescuer, InputAction.FEED); heartbeat = InputAction.FEED;
            });
            after(3, () -> {
                heartbeat = null;
                rescuer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE, 4));
            });
            after(2, () -> {
                check(!DownedManager.isBusy(rescuer) && apples() == 4, "replacing the held stack cancels feeding");
                nearTarget(); send(rescuer, InputAction.FEED); heartbeat = InputAction.FEED;
            });
            after(RevivalConfig.FEEDING_TICKS.get() + 3, () -> {
                heartbeat = null;
                check(!DownedManager.isDowned(target), "continuous feeding revives target");
                check(apples() == 3, "successful feeding consumes exactly one golden apple");
                check(!DownedManager.isBusy(rescuer), "successful feeding clears rescue lease");
                check(target.getMaxHealth() == 20 && target.getHealth() == 10,
                        "default half-health revival restores 10 HP at 20 maximum HP");
                send(rescuer, InputAction.FEED); send(rescuer, InputAction.FEED);
                check(apples() == 3, "late repeated packets cannot consume again");
            });
            after(12, () -> {
                check(!target.isPassenger(), "revival releases native Sable seat");
                check(Math.abs(target.getX() - site.x) < 20 && Math.abs(target.getZ() - site.z) < 20,
                        "revival returns actual player from plotyard to body world position");
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
                DownedManager.down(target, target.damageSources().generic());
            });
            after(35, () -> {
                nearTarget(); send(rescuer, InputAction.FEED); heartbeat = InputAction.FEED;
            });
            after(RevivalConfig.FEEDING_TICKS.get() + 3, () -> {
                heartbeat = null;
                check(!DownedManager.isDowned(target) && target.getMaxHealth() == 40 && target.getHealth() == 20,
                        "half-health feeding revival scales to 20 HP at 40 maximum HP");
                check(apples() == 2, "scaled-health revival consumes exactly one golden apple");
            });
            after(12, () -> {
                RevivalConfig.RESTORED_HEALTH_FRACTION.set(0.25);
                DownedManager.down(target, target.damageSources().generic());
            });
            after(35, () -> {
                nearTarget(); send(rescuer, InputAction.FEED); heartbeat = InputAction.FEED;
            });
            after(RevivalConfig.FEEDING_TICKS.get() + 3, () -> {
                heartbeat = null;
                check(!DownedManager.isDowned(target) && target.getMaxHealth() == 40 && target.getHealth() == 10,
                        "configured quarter-health feeding revival restores 10 HP at 40 maximum HP");
                check(apples() == 1, "configured-health revival consumes exactly one golden apple");
            });
            after(12, () -> {
                RevivalConfig.RESTORED_HEALTH_FRACTION.set(0.5);
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
                DownedManager.down(target, target.damageSources().generic());
            });
            after(35, () -> {
                nearTarget(); rescuer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                rescuer.setShiftKeyDown(true); send(rescuer, InputAction.DRAG); heartbeat = InputAction.DRAG;
                check(DownedManager.isDragging(rescuer), "crouching empty-handed rescuer acquires native drag");
            });
            after(5, () -> {
                rescuer.setShiftKeyDown(false); heartbeat = null;
            });
            after(3, () -> {
                check(!DownedManager.isDragging(rescuer), "releasing crouch releases drag");
                rescuer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE, 3));
                send(rescuer, InputAction.FEED);
                DownedManager.logout(new PlayerEvent.PlayerLoggedOutEvent(rescuer));
                check(!DownedManager.isBusy(rescuer), "rescuer logout callback cancels feeding");
                DownedManager.login(new PlayerEvent.PlayerLoggedInEvent(rescuer));
                nearTarget(); send(rescuer, InputAction.FEED);
                DownedManager.down(rescuer, rescuer.damageSources().generic());
                check(!DownedManager.isBusy(rescuer), "rescuer becoming downed cancels feeding");
                DownedManager.revive(rescuer);
                heartbeat = InputAction.GIVE_UP;
                send(target, InputAction.GIVE_UP);
            });
            after(20, () -> {
                heartbeat = null; send(target, InputAction.GIVE_UP_RELEASE);
                check(DownedManager.isDowned(target) && target.isAlive(), "releasing G cancels give-up progress");
            });
            after(5, () -> {
                server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
                heartbeat = InputAction.GIVE_UP; send(target, InputAction.GIVE_UP);
            });
            after(98, () -> check(DownedManager.isDowned(target) && target.isAlive(), "give-up does not complete before 100 continuous ticks"));
            after(5, () -> {
                heartbeat = null;
                check(!DownedManager.isDowned(target) && target.isDeadOrDying(), "100-tick give-up performs terminal death once");
                check(target.getInventory().countItem(Items.DIAMOND) == 3 && target.experienceLevel == 7,
                        "terminal death respects keepInventory true for items and XP");
                target = respawn(target);
            });
            after(20, () -> {
                check(!DownedManager.isDowned(target) && target.isAlive(), "death respawn clears downed state");
                server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
                target.teleportTo(site.x, site.y, site.z);
                target.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                target.experienceLevel = 7; target.totalExperience = 91;
                DownedManager.down(target, target.damageSources().generic());
            });
            after(30, () -> {
                target.getPersistentData().getCompound("ragrevival:downed").putLong("deadline", System.currentTimeMillis() - 1);
            });
            after(3, () -> {
                check(target.isDeadOrDying() && !DownedManager.isDowned(target), "expired server deadline performs terminal death without redowning");
                check(target.getInventory().countItem(Items.DIAMOND) == 0, "countdown death drops inventory with keepInventory false");
                target = respawn(target);
            });
            after(15, () -> {
                check(target.experienceLevel == 0, "countdown death loses XP with keepInventory false");
                target.teleportTo(site.x, site.y, site.z);
                RagdollAPI.launch(target, Vec3.ZERO, RagdollLaunchOptions.builder()
                                .autoSeat(true).lockDismount(false).despawnConditions(List.of(DespawnCondition.never())).build(),
                        new RagdollPoseSnapshot(RagdollLimbOptions.defaults(), target.yBodyRot));
            });
            after(dev.leo.sableplayerragdoll.config.RagdollSettings.minDismountTicks() + 10, () -> {
                check(RagdollAPI.isRagdolled(target) && !DownedManager.isDowned(target),
                        "ordinary Sable ragdoll remains distinct from downed state");
                var root = RagdollSessionManager.activeRagdollForPlayer(target.serverLevel(), target.getUUID());
                ordinaryRoot = root == null ? null : root.getUniqueId();
                target.invulnerableTime = 0;
                target.hurt(target.damageSources().generic(), 1000);
                var downedRoot = RagdollSessionManager.activeRagdollForPlayer(target.serverLevel(), target.getUUID());
                check(DownedManager.isDowned(target) && ordinaryRoot != null && downedRoot != null
                                && ordinaryRoot.equals(downedRoot.getUniqueId()),
                        "lethal damage converts ordinary ragdoll in place");
                target.stopRiding();
                check(target.isPassenger() && RagdollAPI.activeSession(target).isDismountLocked(),
                        "downed native seat rejects manual dismount");
                if (ModList.get().isLoaded("carryon")) {
                    var targetPickup = new tschipp.carryon.events.EntityPickupEvent(rescuer, target);
                    NeoForge.EVENT_BUS.post(targetPickup);
                    check(targetPickup.isCanceled(), "Carry On pickup of downed target is canceled");
                    var actorPickup = new tschipp.carryon.events.EntityPickupEvent(target, rescuer);
                    NeoForge.EVENT_BUS.post(actorPickup);
                    check(actorPickup.isCanceled(), "Carry On pickup by downed actor is canceled");
                }
                movementStart = RagdollBridge.worldPosition(target);
                moveDowned = true;
            });
            after(30, () -> {
                moveDowned = false; RagdollControlHelper.clearInput(target.getUUID());
                check(target.isPassenger() && DownedManager.isDowned(target),
                        "native movement input preserves downed seat and state");
                check(RagdollBridge.worldPosition(target).distanceToSqr(movementStart) > 0.0001,
                        "native movement input allows observable body motion while downed");
                DownedManager.revive(target);
            });
            after(15, () -> {
                target.removeAllEffects(); target.setHealth(20); target.invulnerableTime = 0;
                target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
                target.hurt(target.damageSources().generic(), 1000);
                check(target.isAlive() && !DownedManager.isDowned(target) && target.getOffhandItem().isEmpty(),
                        "totem resolves lethal damage before downing");
            });
            after(5, () -> {
                target.removeAllEffects(); target.setHealth(20); target.invulnerableTime = 0;
                target.hurt(target.damageSources().fellOutOfWorld(), 1000);
                check(target.isDeadOrDying() && !DownedManager.isDowned(target),
                        "void damage performs normal terminal death");
                target = respawn(target);
            });
            after(15, () -> {
                target.invulnerableTime = 0;
                target.hurt(target.damageSources().genericKill(), 1000);
                check(target.isDeadOrDying() && !DownedManager.isDowned(target),
                        "administrative kill performs normal terminal death");
                target = respawn(target);
            });
            after(15, () -> finish(true));
        }

        private void tick() {
            ticks++;
            // Fixture teleports must not create incidental impact ragdolls on either actor.
            // The short lease expires naturally after this run; explicit RagdollAPI launches remain enabled.
            suppressFixtureReactions(target); suppressFixtureReactions(rescuer);
            if (moveDowned) RagdollControlHelper.updateInput(target, 1, 1);
            if (heartbeat != null) send(heartbeat == InputAction.GIVE_UP ? target : rescuer, heartbeat);
            while (!steps.isEmpty() && steps.peek().tick <= ticks) steps.remove().action.run();
        }

        private void nearTarget() {
            Vec3 body = RagdollBridge.worldPosition(target);
            suppressFixtureReactions(rescuer);
            rescuer.teleportTo(body.x + 1.4, site.y, body.z);
            rescuer.setDeltaMovement(Vec3.ZERO);
        }

        private void send(ServerPlayer actor, InputAction action) {
            DownedManager.input(actor, new InputPayload(target.getUUID(), InteractionHand.MAIN_HAND, action));
        }

        private int apples() { return rescuer.getMainHandItem().getCount(); }

        private ServerPlayer respawn(ServerPlayer previous) {
            ServerPlayer replacement = server.getPlayerList().respawn(previous, false, Entity.RemovalReason.KILLED);
            // Vanilla's client-command handler performs this assignment after PlayerList.respawn.
            replacement.connection.player = replacement;
            suppressFixtureReactions(replacement);
            return replacement;
        }

        private static void suppressFixtureReactions(ServerPlayer player) {
            ReactionSuppressions.suppress(player, player.serverLevel().getGameTime(), 5);
        }

        private void resetPlayer(ServerPlayer player) {
            if (!player.isAlive()) throw new IllegalStateException(player.getScoreboardName() + " must be alive before testing");
            suppressFixtureReactions(player);
            player.removeAllEffects(); player.setHealth(player.getMaxHealth());
            player.clearFire(); player.setDeltaMovement(Vec3.ZERO); player.setShiftKeyDown(false);
            player.getInventory().clearContent(); player.getFoodData().setFoodLevel(20);
            player.experienceLevel = 0; player.totalExperience = 0; player.experienceProgress = 0;
        }

        private void persistedSnapshot() {
            try {
                CompoundTag data = target.saveWithoutId(new CompoundTag());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.write(data, new DataOutputStream(bytes));
                CompoundTag restored = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
                long deadline = target.getPersistentData().getCompound("ragrevival:downed").getLong("deadline");
                check(deadline > System.currentTimeMillis() && restored.getCompound("NeoForgeData").getCompound("ragrevival:downed").getLong("deadline") == deadline,
                        "normal player save and NBT serialization preserve exact deadline");
            } catch (Exception failure) { throw new IllegalStateException("NBT snapshot failed", failure); }
        }

        private void check(boolean condition, String name) {
            if (condition) { passed++; LOGGER.info("RAGREVIVAL_TEST PASS {}", name); }
            else { failures.add(name); LOGGER.error("RAGREVIVAL_TEST FAIL {}", name); }
        }

        private void finish(boolean completed) {
            heartbeat = null;
            suppressFixtureReactions(target); suppressFixtureReactions(rescuer);
            // Restore fixture overrides before cleanup can fail or revive a surviving player.
            RevivalConfig.RESTORED_HEALTH_FRACTION.set(originalRestoredHealthFraction);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(originalMaxHealthBase);
            server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(originalNaturalRegeneration, server);
            if (zombie != null) zombie.discard();
            for (ServerPlayer player : List.of(target, rescuer)) {
                DownedManager.revive(player);
                DownedManager.input(player, new InputPayload(player.getUUID(), InteractionHand.MAIN_HAND, InputAction.RELEASE));
                player.setShiftKeyDown(false);
                if (player.isAlive()) {
                    player.setHealth(player.getMaxHealth()); player.getFoodData().setFoodLevel(20);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE, 16));
                }
            }
            server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(originalKeepInventory, server);
            server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(originalImmediateRespawn, server);
            server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(originalMobSpawning, server);
            if (target.isAlive() && rescuer.isAlive()) {
                target.teleportTo(site.x, site.y, site.z);
                rescuer.teleportTo(site.x + 2, site.y, site.z);
            }
            String result = "RAGREVIVAL_TEST " + (completed && failures.isEmpty() ? "PASSED" : "FAILED")
                    + " passed=" + passed + " failed=" + failures.size() + " ticks=" + ticks;
            LOGGER.info(result);
            server.getPlayerList().broadcastSystemMessage(Component.literal(result), false);
            current = null;
        }
    }
}
