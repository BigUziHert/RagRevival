package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.RevivalConfig;
import com.biguzi.ragrevival.RevivalItems;
import com.biguzi.ragrevival.network.InputAction;
import com.biguzi.ragrevival.network.InputPayload;
import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.leo.ragdollreactions.physics.ReactionSuppressions;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/** Opt-in native-server checks. Uses packet sinks, so no client or real player is needed. */
final class RevivalFeatureProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static RevivalFeatureProbe current;
    private final MinecraftServer server;
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private final List<Entity> spawned = new ArrayList<>();
    private final List<ServerPlayer> players = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private ServerPlayer target;
    private ServerPlayer rescuer;
    private Mob zombie;
    private Mob brute;
    private Arrow arrow;
    private Vec3 site;
    private boolean selfHeartbeat;
    private int ticks;
    private int cursor;
    private int passed;

    private RevivalFeatureProbe(MinecraftServer server) { this.server = server; }
    private record Step(int tick, Runnable action) {}

    static boolean active() { return current != null; }

    static String start(MinecraftServer server) {
        if (current != null) return "A revival feature probe is already active.";
        if (!server.getPlayerList().getPlayers().isEmpty())
            return "Disconnect all clients first; this opt-in probe is for the isolated test server.";
        current = new RevivalFeatureProbe(server);
        current.plan();
        return "Revival feature probe started with temporary native server players; see RAGREVIVAL_FEATURE in the server log.";
    }

    static void tick(MinecraftServer server) {
        if (current == null || current.server != server) return;
        try { current.advance(); }
        catch (Throwable failure) {
            LOGGER.error("RAGREVIVAL_FEATURE ABORTED at tick {}", current.ticks, failure);
            current.finish(false);
        }
    }

    private void after(int delay, Runnable action) { cursor += delay; steps.add(new Step(cursor, action)); }

    private void plan() {
        after(1, () -> {
            var spawn = server.overworld().getSharedSpawnPos();
            int y = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
            site = new Vec3(spawn.getX() + 0.5, y + 1, spawn.getZ() + 0.5);
            target = player("RevivalProbe", "6e829f68-bf78-413b-a23d-05b458678de4", site);
            rescuer = player("RescuerProbe", "645609f2-c3aa-4f9b-b2be-ab4286c469c1", site.add(1.4, 0, 0));
            zombie = mob(EntityType.ZOMBIE, site.add(8, 0, 0));
            brute = mob(EntityType.PIGLIN_BRUTE, site.add(10, 0, 0));
            arrow = new Arrow(server.overworld(), zombie, new ItemStack(Items.ARROW), null);
            // The arrow already belongs to a mob before revival; use its real damage source later.
            classification();
        });
        // Native ServerPlayer starts with 60 ticks of separate vanilla spawn immunity.
        after(70, () -> {
            var command = server.getCommands().getDispatcher().getRoot().getChild("revive");
            check(command != null, "production /revive is registered");
            if (command == null) throw new IllegalStateException("Missing /revive command");
            check(!command.canUse(source(0)) && !command.canUse(source(1)) && command.canUse(source(2)),
                    "/revive requires operator permission level 2");
            check(execute("revive RevivalProbe", 2) == 0 && !DownedManager.hasRevivalGrace(target),
                    "reviving a healthy player fails without granting grace");
            down();
            check(execute("revive RevivalProbe", 0) == 0 && DownedManager.isDowned(target),
                    "a non-operator cannot revive a downed player");
            check(execute("revive MissingRevivalProbe", 2) == 0, "unknown target fails safely");
        });
        after(5, () -> {
            nearTarget();
            rescuer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.HONEY_BOTTLE, 2));
            input(rescuer, InputAction.FEED);
            check(DownedManager.isFeeding(rescuer), "teammate honey-bottle feeding starts");
            check(execute("revive RevivalProbe", 2) == 1, "operator /revive succeeds");
            check(!DownedManager.isDowned(target) && !DownedManager.isBusy(rescuer)
                            && !rescuer.isUsingItem() && rescuer.getMainHandItem().getCount() == 2,
                    "command revival cancels teammate feeding without consumption");
            check(target.getHealth() == (float)(target.getMaxHealth() * RevivalConfig.RESTORED_HEALTH_FRACTION.get()),
                    "command revival uses configured restored health");
            check(DownedManager.hasRevivalGrace(target) && DownedManager.isMobProtected(target),
                    "command revival immediately grants mob grace");
            target.invulnerableTime = 0;
            target.setHealth(10);
            graceDamage();
            zombie.setTarget(target);
            check(zombie.getTarget() == null, "mob cannot set revived player as direct target");
            check(!TargetingConditions.forCombat().ignoreLineOfSight().test(zombie, target),
                    "mob target search rejects revived player");
            check(TargetingConditions.forNonCombat().test(rescuer, target),
                    "player target queries still see revived player");
            brute.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
            brute.setLastHurtByMob(target);
        });
        after(1, () -> {
            check(!brute.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                            && brute.getLastHurtByMob() == null,
                    "tick clears brain attack target and retaliation during grace");
            float health = target.getHealth();
            target.invulnerableTime = 0;
            target.hurt(target.damageSources().generic(), 1);
            check(target.getHealth() < health, "environmental damage remains active during grace");
            health = target.getHealth();
            target.invulnerableTime = 0;
            target.hurt(target.damageSources().playerAttack(rescuer), 1);
            check(target.getHealth() < health, "player damage remains active during grace");
        });
        after(198, () -> {
            check(DownedManager.hasRevivalGrace(target), "mob grace remains active at tick 199");
            graceDamage();
        });
        after(1, () -> {
            check(!DownedManager.hasRevivalGrace(target) && !DownedManager.isMobProtected(target),
                    "mob grace expires exactly at tick 200");
            zombie.setTarget(target);
            check(zombie.getTarget() == target, "mob targeting resumes after grace");
            check(TargetingConditions.forCombat().ignoreLineOfSight().test(zombie, target),
                    "mob target search resumes after grace");
            float health = target.getHealth();
            target.invulnerableTime = 0;
            target.hurt(target.damageSources().arrow(arrow, zombie), 1);
            check(target.getHealth() < health, "mob projectile damage resumes after grace");
            zombie.setTarget(null);
        });
        // Exercise the actual configured timed feeding path for every newly added item.
        successfulFeed(new ItemStack(Items.HONEY_BOTTLE, 2), "honey bottle");
        successfulFeed(new ItemStack(Items.GLISTERING_MELON_SLICE, 2), "glistering melon slice");
        successfulFeed(PotionContents.createItemStack(Items.POTION, Potions.HEALING), "healing potion");
        successfulFeed(new ItemStack(Items.TOTEM_OF_UNDYING), "totem of undying");
        successfulFeed(PotionContents.createItemStack(Items.POTION, Potions.REGENERATION), "regeneration potion");
        successfulFeed(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2), "enchanted golden apple");
        after(1, () -> {
            down();
            target.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_CARROT, 2));
            input(target, InputAction.FEED);
            check(DownedManager.isFeeding(target), "self-feeding starts before administrative revival");
            check(execute("revive RevivalProbe", 2) == 1 && !DownedManager.isBusy(target)
                            && target.getMainHandItem().getCount() == 2 && !target.isUsingItem(),
                    "command revival cancels self-feeding without consumption");
            target.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            check(execute("kill RevivalProbe", 4) == 1 && !target.isAlive()
                            && !DownedManager.isDowned(target) && !DownedManager.hasRevivalGrace(target),
                    "/kill stays terminal during grace and clears protection");
            check(execute("revive RevivalProbe", 2) == 0, "/revive does not resurrect a dead player");
            finish(true);
        });
    }

    private void successfulFeed(ItemStack stack, String label) {
        int initialCount = stack.getCount();
        after(1, () -> {
            down();
            target.removeAllEffects();
            target.setItemInHand(InteractionHand.MAIN_HAND, stack);
            target.getFoodData().setFoodLevel(7);
            target.getFoodData().setSaturation(0);
            input(target, InputAction.FEED);
            selfHeartbeat = true;
            check(DownedManager.isFeeding(target), label + " starts self-revival");
        });
        after(Math.max(1, RevivalConfig.FEEDING_TICKS.get() - 1), () -> {
            check(DownedManager.isDowned(target) && stack.getCount() == initialCount,
                    label + " requires full configured hold before consumption");
        });
        after(1, () -> {
            selfHeartbeat = false;
            check(!DownedManager.isDowned(target) && !DownedManager.isBusy(target)
                            && stack.getCount() == initialCount - 1 && DownedManager.hasRevivalGrace(target),
                    label + " timed revival consumes one and grants grace");
            check(target.getActiveEffects().isEmpty() && target.getFoodData().getFoodLevel() == 7,
                    label + " revival does not trigger ordinary item effects");
            input(target, InputAction.FEED);
            check(stack.getCount() == initialCount - 1, label + " late input cannot consume twice");
        });
    }

    private void classification() {
        for (var item : List.of(Items.GOLDEN_APPLE, Items.GOLDEN_CARROT, Items.HONEY_BOTTLE,
                Items.GLISTERING_MELON_SLICE, Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE))
            check(RevivalItems.canRevive(new ItemStack(item)), "eligible item " + item);
        for (var potion : List.of(Potions.HEALING, Potions.STRONG_HEALING, Potions.REGENERATION,
                Potions.LONG_REGENERATION, Potions.STRONG_REGENERATION))
            check(RevivalItems.canRevive(PotionContents.createItemStack(Items.POTION, potion)), "eligible potion " + potion);
        for (var potion : List.of(Potions.WATER, Potions.AWKWARD, Potions.HARMING, Potions.POISON, Potions.SWIFTNESS))
            check(!RevivalItems.canRevive(PotionContents.createItemStack(Items.POTION, potion)), "ineligible potion " + potion);
        check(!RevivalItems.canRevive(ItemStack.EMPTY) && !RevivalItems.canRevive(new ItemStack(Items.APPLE))
                        && !RevivalItems.canRevive(new ItemStack(Items.POTION)), "empty, ordinary food, and empty potion rejected");
        check(!RevivalItems.canRevive(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HEALING))
                        && !RevivalItems.canRevive(PotionContents.createItemStack(Items.LINGERING_POTION, Potions.REGENERATION)),
                "throwable potions are not revival items");
        ItemStack poisoned = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        poisoned.set(DataComponents.POTION_CONTENTS, poisoned.get(DataComponents.POTION_CONTENTS)
                .withEffectAdded(new MobEffectInstance(MobEffects.POISON, 200)));
        check(!RevivalItems.canRevive(poisoned), "healing base with a custom harmful effect is rejected");
        check(RevivalItems.examples().stream().allMatch(RevivalItems::canRevive), "every UI example is an eligible revival stack");
    }

    private void graceDamage() {
        float health = target.getHealth();
        target.invulnerableTime = 0;
        target.hurt(target.damageSources().mobAttack(zombie), 5);
        check(target.getHealth() == health && !DownedManager.isDowned(target), "mob melee damage is blocked throughout grace");
        target.invulnerableTime = 0;
        target.hurt(target.damageSources().arrow(arrow, zombie), 5);
        check(target.getHealth() == health && !DownedManager.isDowned(target), "an existing mob arrow cannot damage during grace");
    }

    private void down() {
        target.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        target.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        target.setHealth(target.getMaxHealth());
        target.invulnerableTime = 0;
        target.hurt(target.damageSources().generic(), 1000);
        check(DownedManager.isDowned(target), "native lethal damage creates the downed fixture");
        if (!DownedManager.isDowned(target)) throw new IllegalStateException("Could not down native fixture player");
    }

    private void nearTarget() {
        Vec3 body = RagdollBridge.worldPosition(target);
        rescuer.teleportTo(body.x + 1.4, body.y, body.z);
        rescuer.setDeltaMovement(Vec3.ZERO);
    }

    private void input(ServerPlayer actor, InputAction action) {
        DownedManager.input(actor, new InputPayload(target.getUUID(), InteractionHand.MAIN_HAND, action));
    }

    private CommandSourceStack source(int level) { return server.createCommandSourceStack().withPermission(level).withSuppressedOutput(); }

    private int execute(String command, int level) {
        try { return server.getCommands().getDispatcher().execute(command, source(level)); }
        catch (CommandSyntaxException expectedRejection) { return 0; }
    }

    private ServerPlayer player(String name, String uuid, Vec3 pos) {
        ServerPlayer player = new ServerPlayer(server, server.overworld(),
                new GameProfile(UUID.fromString(uuid), name), ClientInformation.createDefault());
        player.connection = new PacketSink(server, player);
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(pos.x, pos.y, pos.z);
        // Use native ServerPlayer: NeoForge FakePlayer suppresses damage, death, and riding.
        // Only registration/networking are substituted. Existing real players are never touched.
        field(server.getPlayerList(), "players", List.class).add(player);
        field(server.getPlayerList(), "playersByUUID", Map.class).put(player.getUUID(), player);
        players.add(player);
        player.serverLevel().addNewPlayer(player);
        return player;
    }

    private Mob mob(EntityType<? extends Mob> type, Vec3 pos) {
        Mob mob = type.create(server.overworld());
        if (mob == null) throw new IllegalStateException("Could not create " + type);
        mob.setNoAi(true);
        mob.setInvulnerable(true);
        mob.setPos(pos.x, pos.y, pos.z);
        spawned.add(mob);
        server.overworld().addFreshEntity(mob);
        return mob;
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
        for (ServerPlayer player : players) {
            ReactionSuppressions.suppress(player, player.level().getGameTime(), 5);
            // Normally called by ServerGamePacketListenerImpl.tick. Exercise native item-use
            // completion/effects as well as DownedManager's server-tick feeding transaction.
            if (player.isAlive()) player.doTick();
        }
        if (selfHeartbeat) input(target, InputAction.FEED);
        while (!steps.isEmpty() && steps.peek().tick <= ticks) steps.remove().action.run();
    }

    private void check(boolean condition, String name) {
        if (condition) { passed++; LOGGER.info("RAGREVIVAL_FEATURE PASS {}", name); }
        else { failures.add(name); LOGGER.error("RAGREVIVAL_FEATURE FAIL {}", name); }
    }

    private void finish(boolean completed) {
        selfHeartbeat = false;
        try {
            spawned.forEach(Entity::discard);
            for (ServerPlayer player : players) {
                try {
                    if (DownedManager.isDowned(player)) DownedManager.revive(player);
                    DownedManager.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
                    server.getPlayerList().remove(player);
                    ((EmbeddedChannel)player.connection.getConnection().channel()).finishAndReleaseAll();
                } catch (Throwable failure) {
                    LOGGER.error("RAGREVIVAL_FEATURE cleanup failed for {}", player.getScoreboardName(), failure);
                    failures.add("fixture cleanup");
                }
            }
        } finally {
            LOGGER.info("RAGREVIVAL_FEATURE {} passed={} failed={} ticks={}",
                    completed && failures.isEmpty() ? "PASSED" : "FAILED", passed, failures.size(), ticks);
            current = null;
        }
    }

    private static final class PacketSink extends ServerGamePacketListenerImpl {
        PacketSink(MinecraftServer server, ServerPlayer player) {
            super(server, connection(), player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        private static Connection connection() {
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            // NeoForge time synchronization inspects channel attributes even when sends are sunk.
            new EmbeddedChannel(connection);
            return connection;
        }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, PacketSendListener listener) {}
    }
}
