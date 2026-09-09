package com.biguzi.ragrevival;

import com.biguzi.ragrevival.compat.CarryOnCompat;
import com.biguzi.ragrevival.network.*;
import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import com.biguzi.ragrevival.state.DownedClock;
import com.biguzi.ragrevival.state.HoldProgress;
import com.mojang.logging.LogUtils;
import dev.leo.sableplayerragdoll.entity.RagdollSeatEntity;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** All mutation runs on the logical server thread. The persisted deadline is the authority. */
public final class DownedManager {
    public static final TagKey<Item> REVIVAL_ITEMS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(RagRevival.MOD_ID, "revival_items"));
    private static final String DATA_KEY = "ragrevival:downed";
    private static final Map<UUID, DamageSource> SOURCES = new HashMap<>();
    private static final Map<UUID, Rescue> RESCUES = new HashMap<>();
    private static final Map<UUID, UUID> TARGET_LOCKS = new HashMap<>();
    private static final Map<UUID, HoldProgress> GIVE_UP = new HashMap<>();
    private static final Set<UUID> TERMINAL = new HashSet<>();
    private static final Map<UUID, Integer> SETUP_TICKS = new HashMap<>();
    private static long tick;

    public static boolean isDowned(Player player) {
        return player.getPersistentData().contains(DATA_KEY) && !TERMINAL.contains(player.getUUID());
    }
    public static boolean isBusy(Player player) { return RESCUES.containsKey(player.getUUID()); }
    public static boolean isFeeding(Player player) {
        Rescue rescue = RESCUES.get(player.getUUID());
        return rescue != null && rescue.action == InputAction.FEED;
    }
    /** The server-owned feeding hand, or null when the player has no active feeding interaction. */
    public static InteractionHand feedingHand(Player player) {
        Rescue rescue = RESCUES.get(player.getUUID());
        return rescue != null && rescue.action == InputAction.FEED ? rescue.hand : null;
    }
    public static boolean isDragging(Player player) {
        Rescue rescue = RESCUES.get(player.getUUID());
        return rescue != null && rescue.action == InputAction.DRAG;
    }
    public static long remainingMillis(Player player) {
        if (!isDowned(player)) return 0;
        UUID rescuer = TARGET_LOCKS.get(player.getUUID());
        Rescue rescue = rescuer == null ? null : RESCUES.get(rescuer);
        long now = rescue != null && rescue.action == InputAction.FEED && rescue.hold.isActive(tick)
                ? rescue.pauseAccountedAt : System.currentTimeMillis();
        return DownedClock.remaining(player.getPersistentData().getCompound(DATA_KEY).getLong("deadline"), now);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || TERMINAL.contains(player.getUUID())) return;
        // Totems already ran before LivingDeathEvent. Administrative kill and the void stay terminal.
        if (event.getSource().is(DamageTypes.GENERIC_KILL) || event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) {
            if (isDowned(player)) clear(player);
            return;
        }
        if (isDowned(player)) { event.setCanceled(true); player.setHealth(1); return; }
        if (player.isSpectator() || player.isCreative()) return;
        event.setCanceled(true);
        down(player, event.getSource());
    }

    public static void down(ServerPlayer player, DamageSource source) {
        if (isDowned(player)) return;
        cancelInvolving(player);
        CarryOnCompat.releaseForDowning(player);
        player.stopUsingItem();
        player.stopFallFlying();
        player.setHealth(1);
        player.clearFire();
        player.fallDistance = 0;
        CompoundTag data = new CompoundTag();
        data.putLong("deadline", DownedClock.deadline(System.currentTimeMillis(), RevivalConfig.DOWNED_SECONDS.get()));
        source.typeHolder().unwrapKey().ifPresent(key -> data.putString("damageType", key.location().toString()));
        player.getPersistentData().put(DATA_KEY, data);
        checkpointPosition(player);
        SOURCES.put(player.getUUID(), source);
        RagdollBridge.ensureDowned(player);
        player.server.getPlayerList().saveAll();
        clearAggro(player.server);
        sync(player);
        player.server.getPlayerList().broadcastSystemMessage(Component.translatable(
                "chat.ragrevival.knocked", player.getDisplayName()).withStyle(ChatFormatting.GOLD), false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void damage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isDowned(player)
                && !event.getSource().is(DamageTypes.GENERIC_KILL)) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void target(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof Player player && isDowned(player))
            event.setNewAboutToBeSetTarget(null);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void travel(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            cancelInvolving(player);
            if (isDowned(player)) event.setCanceled(true);
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void attack(AttackEntityEvent event) {
        if (isDowned(event.getEntity()) || isBusy(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void useItem(PlayerInteractEvent.RightClickItem event) {
        if (isDowned(event.getEntity()) || isBusy(event.getEntity())) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void useBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isDowned(event.getEntity()) || isBusy(event.getEntity())) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (isDowned(event.getEntity()) || isBusy(event.getEntity()) || event.getTarget() instanceof Player p && isDowned(p)) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interactAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (isDowned(event.getEntity()) || isBusy(event.getEntity()) || event.getTarget() instanceof Player p && isDowned(p)) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    public static void input(ServerPlayer actor, InputPayload input) {
        UUID id = actor.getUUID();
        if (input.action() == InputAction.RELEASE) { cancelRescue(id); return; }
        if (input.action() == InputAction.GIVE_UP_RELEASE) { GIVE_UP.remove(id); return; }
        if (input.action() == InputAction.GIVE_UP) {
            if (isDowned(actor) && actor.isAlive()) GIVE_UP.computeIfAbsent(id, ignored -> new HoldProgress(tick)).heartbeat(tick);
            return;
        }
        ServerPlayer target = actor.server.getPlayerList().getPlayer(input.target());
        boolean selfFeed = target == actor && input.action() == InputAction.FEED;
        if (!actor.isAlive() || actor.isSpectator() || isDowned(actor) && !selfFeed || CarryOnCompat.isCarrying(actor)) {
            cancelRescue(id); return;
        }
        if (target == null || target == actor && !selfFeed || !validTarget(actor, target)) { cancelRescue(id); return; }
        // Feeding a teammate requires crouch for the whole interaction; self-feeding does not.
        if (input.action() == InputAction.FEED && (!actor.getItemInHand(input.hand()).is(REVIVAL_ITEMS)
                || !selfFeed && !actor.isShiftKeyDown())) { cancelRescue(id); return; }
        Rescue rescue = RESCUES.get(id);
        if (rescue != null && (rescue.target != target || rescue.hand != input.hand() || rescue.action != input.action())) {
            cancelRescue(id); rescue = null;
        }
        if (rescue != null) { rescue.hold.heartbeat(tick); return; }
        if (TARGET_LOCKS.containsKey(target.getUUID())) return;
        long now = System.currentTimeMillis();
        // A request already at/past the deadline cannot turn an expired player into a paused rescue.
        if (DownedClock.remaining(target.getPersistentData().getCompound(DATA_KEY).getLong("deadline"), now) == 0) return;
        if (input.action() == InputAction.DRAG && (!actor.isShiftKeyDown() || !actor.getMainHandItem().isEmpty() || !actor.getOffhandItem().isEmpty())) return;
        if (input.action() != InputAction.FEED && input.action() != InputAction.DRAG) return;
        if (input.action() == InputAction.DRAG && !RagdollBridge.startDrag(actor, target)) return;
        actor.stopUsingItem();
        RESCUES.put(id, new Rescue(actor, target, input.action(), input.hand(), actor.getItemInHand(input.hand()),
                new HoldProgress(tick), RevivalConfig.FEEDING_TICKS.get(), now));
        TARGET_LOCKS.put(target.getUUID(), id);
        if (selfFeed) FeedingAnimation.start(actor, input.hand());
    }

    private static boolean validTarget(ServerPlayer actor, ServerPlayer target) {
        if (actor.level() != target.level() || !isDowned(target) || !target.isAlive()
                || target.connection == null || target.hasDisconnected() || actor.isVehicle() || target.isVehicle()) return false;
        // A downed player's actual entity rides Sable's seat. Self-feeding needs no world ray/reach
        // test, but must not grant the same exception to another passenger or an ordinary ragdoll.
        if (actor == target) return !actor.isPassenger() || actor.getVehicle() instanceof RagdollSeatEntity;
        return !actor.isPassenger() && RagdollBridge.canReach(actor, target);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        tick++;
        MinecraftServer server = event.getServer();
        // Validate and credit feeding before expiry: even a last-second feed must get its pause.
        // Completing the interaction waits until after terminal conditions, including give-up.
        for (Rescue rescue : List.copyOf(RESCUES.values())) {
            if (RESCUES.get(rescue.actor.getUUID()) != rescue) continue;
            ServerPlayer actor = rescue.actor;
            boolean selfFeed = rescue.action == InputAction.FEED && actor == rescue.target;
            boolean valid = actor.connection != null && !actor.hasDisconnected() && actor.isAlive() && !actor.isSpectator()
                    && (!isDowned(actor) || selfFeed) && !CarryOnCompat.isCarrying(actor) && validTarget(actor, rescue.target)
                    && rescue.hold.advance(tick);
            if (rescue.action == InputAction.FEED) {
                valid &= (selfFeed || actor.isShiftKeyDown())
                        && actor.getItemInHand(rescue.hand) == rescue.stack && rescue.stack.is(REVIVAL_ITEMS)
                        && !rescue.stack.isEmpty();
            } else {
                valid &= actor.isShiftKeyDown() && actor.getMainHandItem().isEmpty() && actor.getOffhandItem().isEmpty();
                if (valid) valid = RagdollBridge.tickDrag(actor, rescue.target);
            }
            if (!valid) { cancelRescue(actor.getUUID()); continue; }
            creditFeedingTime(rescue, System.currentTimeMillis());
            if (rescue.action == InputAction.FEED)
                FeedingAnimation.tick(actor, rescue.target, rescue.hand, rescue.hold.ticks());
        }
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (!isDowned(player)) continue;
            if (remainingMillis(player) == 0) { finishDeath(player); continue; }
            if (RagdollBridge.worldPosition(player).y < player.level().getMinBuildHeight() - 64) {
                SOURCES.put(player.getUUID(), player.damageSources().fellOutOfWorld());
                finishDeath(player); continue;
            }
            // External dimension/admin teleports cannot erase the persisted deadline.
            if (RagdollBridge.ensureDowned(player)) SETUP_TICKS.remove(player.getUUID());
            else if (SETUP_TICKS.merge(player.getUUID(), 1, Integer::sum) >= 100) {
                LogUtils.getLogger().error("Cannot create a downed Sable ragdoll for {}; completing normal death. Check Sable Ragdolls is enabled.", player.getScoreboardName());
                finishDeath(player); continue;
            }
            if (tick % 20 == 0) checkpointPosition(player);
            player.setHealth(1);
            player.clearFire();
            player.setAirSupply(player.getMaxAirSupply());
            player.fallDistance = 0;
            HoldProgress hold = GIVE_UP.get(player.getUUID());
            if (hold != null) {
                if (!hold.advance(tick)) GIVE_UP.remove(player.getUUID());
                else if (hold.ticks() >= 100) { finishDeath(player); continue; }
            }
        }
        for (Rescue rescue : List.copyOf(RESCUES.values())) {
            if (RESCUES.get(rescue.actor.getUUID()) != rescue) continue;
            ServerPlayer actor = rescue.actor;
            if (rescue.action == InputAction.FEED && rescue.hold.ticks() >= rescue.duration) {
                // Single server-thread transaction: ownership + target still valid, clear lock, consume exactly once.
                ServerPlayer target = rescue.target;
                cancelRescue(actor.getUUID());
                if (isDowned(target)) {
                    rescue.stack.shrink(1);
                    revive(target);
                    actor.server.getPlayerList().saveAll();
                }
            }
        }
        clearAggro(server);
        if (tick % 2 == 0) for (ServerPlayer player : server.getPlayerList().getPlayers()) if (isDowned(player)) sync(player);
    }

    private static void clearAggro(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().stream().noneMatch(DownedManager::isDowned)) return;
        for (var level : server.getAllLevels()) for (var entity : level.getAllEntities()) if (entity instanceof Mob mob) {
            if (mob.getTarget() instanceof Player p && isDowned(p)) { mob.setTarget(null); mob.getNavigation().stop(); }
            var brain = mob.getBrain();
            if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                    && brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) instanceof Player p && isDowned(p)) {
                brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
            }
            if (mob.getLastHurtByMob() instanceof Player p && isDowned(p)) mob.setLastHurtByMob(null);
        }
    }

    public static void revive(ServerPlayer player) {
        if (!isDowned(player)) return;
        clear(player);
        player.setHealth((float)(player.getMaxHealth() * RevivalConfig.RESTORED_HEALTH_FRACTION.get()));
        player.invulnerableTime = 20;
        player.fallDistance = 0;
        player.server.getPlayerList().saveAll();
    }
    public static void finishDeath(ServerPlayer player) {
        if (!isDowned(player)) return;
        DamageSource source = SOURCES.get(player.getUUID());
        if (source == null) {
            ResourceLocation type = ResourceLocation.tryParse(player.getPersistentData().getCompound(DATA_KEY).getString("damageType"));
            source = type == null ? player.damageSources().generic() : player.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, type)).map(DamageSource::new).orElse(player.damageSources().generic());
        }
        TERMINAL.add(player.getUUID());
        try {
            clear(player);
            player.setHealth(0);
            player.die(source);
            player.server.getPlayerList().saveAll();
        } finally { TERMINAL.remove(player.getUUID()); }
    }
    private static void clear(ServerPlayer player) {
        cancelInvolving(player);
        GIVE_UP.remove(player.getUUID());
        SETUP_TICKS.remove(player.getUUID());
        player.getPersistentData().remove(DATA_KEY);
        SOURCES.remove(player.getUUID());
        RagdollBridge.release(player);
        PacketDistributor.sendToAllPlayers(new StatePayload(player.getUUID(), 0, 0, RevivalConfig.FEEDING_TICKS.get(), 0, StatePayload.NONE));
    }
    private static void cancelInvolving(ServerPlayer player) {
        cancelRescue(player.getUUID());
        UUID rescuer = TARGET_LOCKS.get(player.getUUID());
        if (rescuer != null) cancelRescue(rescuer);
    }
    private static void cancelRescue(UUID rescuer) {
        Rescue removed = RESCUES.get(rescuer);
        if (removed != null) {
            // Keep ownership present while clearing native use so its item callbacks remain guarded.
            if (removed.action == InputAction.FEED) FeedingAnimation.stop(removed.actor);
            RESCUES.remove(rescuer);
            // Settle the final fraction of a live hold on release/logout/shutdown. A stale lease
            // earns no extra time; there is never a persisted pause flag that can survive offline.
            creditFeedingTime(removed, System.currentTimeMillis());
            TARGET_LOCKS.remove(removed.target.getUUID(), rescuer);
            if (removed.action == InputAction.DRAG) RagdollBridge.stopDrag(removed.actor);
        }
    }
    private static void creditFeedingTime(Rescue rescue, long now) {
        if (rescue.action != InputAction.FEED || !rescue.hold.isActive(tick) || !isDowned(rescue.target)) return;
        long elapsed = Math.max(0, now - rescue.pauseAccountedAt);
        CompoundTag data = rescue.target.getPersistentData().getCompound(DATA_KEY);
        data.putLong("deadline", Math.addExact(data.getLong("deadline"), elapsed));
        rescue.pauseAccountedAt = Math.max(rescue.pauseAccountedAt, now);
    }
    private static void sync(ServerPlayer target) {
        UUID rescuerId = TARGET_LOCKS.get(target.getUUID());
        Rescue rescue = rescuerId == null ? null : RESCUES.get(rescuerId);
        HoldProgress giveUp = GIVE_UP.get(target.getUUID());
        StatePayload payload = new StatePayload(target.getUUID(), remainingMillis(target),
                rescue != null && rescue.action == InputAction.FEED ? rescue.hold.ticks() : 0,
                rescue == null ? RevivalConfig.FEEDING_TICKS.get() : rescue.duration,
                giveUp == null ? 0 : giveUp.ticks(), rescuerId == null ? StatePayload.NONE : rescuerId,
                RagdollBridge.bodyPartIds(target));
        for (ServerPlayer viewer : target.server.getPlayerList().getPlayers())
            if (viewer.level() == target.level()) PacketDistributor.sendToPlayer(viewer, payload);
    }
    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (isDowned(player)) checkpointPosition(player);
            cancelInvolving(player); GIVE_UP.remove(player.getUUID());
            SETUP_TICKS.remove(player.getUUID());
            RagdollBridge.release(player);
            player.server.getPlayerList().saveAll();
            PacketDistributor.sendToAllPlayers(new StatePayload(player.getUUID(), 0, 0, 32, 0, StatePayload.NONE));
        }
    }
    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isDowned(player)) {
            restorePosition(player);
            if (remainingMillis(player) == 0) finishDeath(player);
            else { RagdollBridge.ensureDowned(player); sync(player); }
        }
    }
    @SubscribeEvent
    public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) cancelInvolving(player);
    }
    @SubscribeEvent
    public static void clone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() && event.getOriginal().getPersistentData().contains(DATA_KEY))
            event.getEntity().getPersistentData().put(DATA_KEY, event.getOriginal().getPersistentData().getCompound(DATA_KEY).copy());
        else event.getEntity().getPersistentData().remove(DATA_KEY);
    }
    private static void checkpointPosition(ServerPlayer player) {
        var pos = RagdollBridge.worldPosition(player);
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        data.putDouble("worldX", pos.x); data.putDouble("worldY", pos.y); data.putDouble("worldZ", pos.z);
        data.putString("dimension", player.level().dimension().location().toString());
    }
    private static void restorePosition(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        if (data.contains("worldX") && data.getString("dimension").equals(player.level().dimension().location().toString())) {
            RagdollBridge.release(player);
            player.teleportTo(data.getDouble("worldX"), data.getDouble("worldY"), data.getDouble("worldZ"));
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void stopping(ServerStoppingEvent event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) if (isDowned(player)) {
            checkpointPosition(player);
            cancelInvolving(player);
            RagdollBridge.release(player);
        }
        event.getServer().getPlayerList().saveAll();
    }
    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        RESCUES.clear(); TARGET_LOCKS.clear(); GIVE_UP.clear(); SOURCES.clear(); TERMINAL.clear(); SETUP_TICKS.clear(); tick = 0;
        RagdollBridge.clear();
    }
    private static final class Rescue {
        final ServerPlayer actor;
        final ServerPlayer target;
        final InputAction action;
        final InteractionHand hand;
        final ItemStack stack;
        final HoldProgress hold;
        final int duration;
        long pauseAccountedAt;

        Rescue(ServerPlayer actor, ServerPlayer target, InputAction action, InteractionHand hand,
               ItemStack stack, HoldProgress hold, int duration, long now) {
            this.actor = actor;
            this.target = target;
            this.action = action;
            this.hand = hand;
            this.stack = stack;
            this.hold = hold;
            this.duration = duration;
            this.pauseAccountedAt = now;
        }
    }
    private DownedManager() {}
}
