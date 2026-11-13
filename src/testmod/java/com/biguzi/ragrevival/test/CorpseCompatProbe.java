package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.RevivalConfig;
import com.biguzi.ragrevival.compat.corpse.CorpseCompat;
import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import de.maxhenkel.corpse.Main;
import de.maxhenkel.corpse.corelib.death.Death;
import de.maxhenkel.corpse.corelib.death.DeathManager;
import de.maxhenkel.corpse.entities.CorpseEntity;
import de.maxhenkel.corpse.gui.CorpseInventoryContainer;
import de.maxhenkel.corpse.gui.Guis;
import dev.leo.ragdollreactions.physics.ReactionSuppressions;
import dev.leo.sableplayerragdoll.api.PlayerlessDespawnRule;
import dev.leo.sableplayerragdoll.api.PlayerlessRagdollSession;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import com.biguzi.ragrevival.ragdoll.NativeRagdollCompat;
import dev.leo.sableplayerragdoll.neoforge.network.RagdollGrabPacket;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
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
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Opt-in real Corpse/Sable server checks; never included in the release JAR. */
@EventBusSubscriber(modid = "ragrevival_tests", value = Dist.DEDICATED_SERVER)
public final class CorpseCompatProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Run current;

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ragrevivaltest")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("corpse-visual").then(Commands.argument("player", EntityArgument.player()).executes(context -> {
                    if (!ModList.get().isLoaded("corpse")) return 0;
                    ServerPlayer player = EntityArgument.getPlayer(context, "player");
                    ServerPlayer fixture = new ServerPlayer(player.server, player.serverLevel(), player.getGameProfile(), ClientInformation.createDefault());
                    fixture.connection = player.connection;
                    // The constructor reuses UUID-cached advancement state; keep its real player owner.
                    player.getAdvancements().setPlayer(player);
                    fixture.getInventory().setItem(8, new ItemStack(Items.DIAMOND, 7));
                    fixture.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
                    fixture.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
                    for (int i = 0; i < 2; i++) {
                        fixture.setPos(player.getX() + 3 + i * 3, player.getY(), player.getZ());
                        CorpseEntity body = CorpseEntity.createFromDeath(fixture, Death.fromPlayer(fixture));
                        if (i == 1) {
                            CompoundTag saved = new CompoundTag();
                            body.saveWithoutId(saved);
                            saved.putInt("Age", Main.SERVER_CONFIG.corpseSkeletonTime.get() + 1);
                            body.load(saved);
                            body.setEquipment(Death.fromPlayer(fixture).getEquipment());
                        }
                        player.serverLevel().addFreshEntity(body);
                    }
                    context.getSource().sendSuccess(() -> Component.literal("Created two lootable visual fixtures at x+3 and x+6; the second is a skeleton."), false);
                    return 1;
                })))
                .then(Commands.literal("corpse-floor").executes(context -> {
                    var server = context.getSource().getServer();
                    if (!ModList.get().isLoaded("corpse") || current != null || !server.getPlayerList().getPlayers().isEmpty()) return 0;
                    current = new Run(server);
                    current.planFloorOnly();
                    context.getSource().sendSuccess(() -> Component.literal("Corpse floor settling probe started."), false);
                    return 1;
                }))
                .then(Commands.literal("corpse-compat").executes(context -> {
                    var server = context.getSource().getServer();
                    if (!ModList.get().isLoaded("corpse")) {
                        context.getSource().sendFailure(Component.literal("Install Corpse in the isolated test server first."));
                        return 0;
                    }
                    if (current != null || !server.getPlayerList().getPlayers().isEmpty()) {
                        context.getSource().sendFailure(Component.literal("Disconnect clients and finish other probes first."));
                        return 0;
                    }
                    current = new Run(server);
                    current.plan();
                    context.getSource().sendSuccess(() -> Component.literal("Corpse compatibility probe started; see RAGREVIVAL_CORPSE in the server log."), false);
                    return 1;
                })));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (current == null || current.server != event.getServer()) return;
        try { current.advance(); }
        catch (Throwable failure) {
            LOGGER.error("RAGREVIVAL_CORPSE ABORTED at tick {}", current.ticks, failure);
            current.failures.add("uncaught probe failure: " + failure.getClass().getSimpleName());
            current.finish(false);
        }
    }

    private record Step(int tick, Runnable action) {}

    private static final class Run {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final ArrayDeque<Step> steps = new ArrayDeque<>();
        private final List<ServerPlayer> players = new ArrayList<>();
        private final List<CorpseEntity> corpses = new ArrayList<>();
        private final List<CorpseEntity> stressCorpses = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final boolean keepInventory;
        private final boolean ownerAccess;
        private final boolean skeletonAccess;
        private final boolean lavaDamage;
        private final boolean fallIntoVoid;
        private final int skeletonTime;
        private final int despawnTime;
        private final int forceDespawnTime;
        private final int maxBodies;
        private final int physicsTicks;
        private ServerPlayer owner;
        private ServerPlayer visitor;
        private CorpseEntity corpse;
        private Vec3 site;
        private Vec3 lavaSite;
        private String scenario;
        private int cursor;
        private int ticks;
        private int passed;
        private int peakBodies;
        private CompoundTag savedPose;
        private PlayerlessRagdollSession orphan;
        private PlayerlessRagdollSession ordinary;
        private List<UUID> orphanParts = List.of();
        private final List<BlockPos> stagingBlocks = new ArrayList<>();
        private CorpseEntity floorCorpse;
        private CorpseEntity floorReloadCorpse;
        private PlayerlessRagdollSession floorControl;
        private Vec3 floorSite;
        private int floorStarted;
        private double lowestFloorY;
        private UUID floorReloadRoot;
        private List<UUID> floorReloadParts = List.of();
        private ServerSubLevel nudgedPart;
        private Vec3 nudgedBefore;
        private CompoundTag nudgedPose;
        private RagdollPartBlockEntity grabbedPart;
        private ServerSubLevel grabbedSublevel;
        private Vec3 grabbedBefore;
        private ServerPlayer gripPlayer;

        Run(MinecraftServer server) {
            this.server = server;
            level = server.overworld();
            keepInventory = server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
            ownerAccess = Main.SERVER_CONFIG.onlyOwnerAccess.get();
            skeletonAccess = Main.SERVER_CONFIG.skeletonAccess.get();
            lavaDamage = Main.SERVER_CONFIG.lavaDamage.get();
            fallIntoVoid = Main.SERVER_CONFIG.fallIntoVoid.get();
            skeletonTime = Main.SERVER_CONFIG.corpseSkeletonTime.get();
            despawnTime = Main.SERVER_CONFIG.corpseDespawnTime.get();
            forceDespawnTime = Main.SERVER_CONFIG.corpseForceDespawnTime.get();
            maxBodies = RevivalConfig.MAX_CORPSE_RAGDOLLS.get();
            physicsTicks = RevivalConfig.CORPSE_PHYSICS_TICKS.get();
        }

        private void after(int delay, Runnable action) { cursor += delay; steps.add(new Step(cursor, action)); }

        void plan() {
            after(1, () -> {
                poseMath();
                scenario = "terminal death";
                var spawn = level.getSharedSpawnPos();
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
                site = new Vec3(spawn.getX() + 0.5, y + 2, spawn.getZ() + 0.5);
                clearFixtureDrops();
                owner = player("CorpseOwner", site);
                visitor = player("CorpseVisitor", site.add(3, 0, 0));
                server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
                Main.SERVER_CONFIG.onlyOwnerAccess.set(true);
                Main.SERVER_CONFIG.skeletonAccess.set(true);
                Main.SERVER_CONFIG.lavaDamage.set(false);
                Main.SERVER_CONFIG.fallIntoVoid.set(false);
                Main.SERVER_CONFIG.corpseSkeletonTime.set(72000);
                Main.SERVER_CONFIG.corpseDespawnTime.set(5);
                Main.SERVER_CONFIG.corpseForceDespawnTime.set(-1);
                seedInventory(owner);
            });
            after(70, () -> {
                owner.invulnerableTime = 0;
                owner.hurt(owner.damageSources().generic(), 1000);
                check(DownedManager.isDowned(owner), "lethal damage creates a downed player");
                check(corpsesFor(owner).isEmpty(), "downing does not create an inventory corpse");
            });
            after(15, () -> {
                DownedManager.finishDeath(owner);
                check(!owner.isAlive() && !DownedManager.isDowned(owner), "terminal death completes once");
                corpse = requireCorpse(owner);
                checkInventory(corpse, "all native inventory compartments survive");
                check(DeathManager.getDeaths(level, owner.getUUID()).size() == 1, "one native death-history record exists");
                owner = respawn(owner);
                owner.teleportTo(corpse.getX(), corpse.getY() + 1, corpse.getZ());
            });
            after(15, () -> {
                check(corpsesFor(owner).size() == 1, "exactly one native Corpse entity exists");
                check(CorpseCompat.hasPhysics(corpse), "new nearby corpse has native Sable physics");
                check(CorpsePose.valid(((CorpsePoseAccess)corpse).getRagRevivalPose()), "corpse synchronizes a valid six-limb ragdoll pose");
                visitor.closeContainer();
                openLoot(corpse, visitor);
                check(visitor.containerMenu == visitor.inventoryMenu, "owner-only access rejects another player");
                openLoot(corpse, owner);
                check(owner.containerMenu instanceof CorpseInventoryContainer, "owner opens Corpse's native inventory menu");
                owner.closeContainer();
                Main.SERVER_CONFIG.corpseSkeletonTime.set(0);
            });
            after(3, () -> {
                check(corpse.isSkeleton(), "native age rule changes the corpse to skeleton stage");
                visitor.teleportTo(corpse.getX(), corpse.getY() + 1, corpse.getZ());
                openLoot(corpse, visitor);
                check(visitor.containerMenu instanceof CorpseInventoryContainer, "skeleton access permits another player");
                visitor.closeContainer();
                Main.SERVER_CONFIG.corpseSkeletonTime.set(72000);
                saveReload();
            });
            after(15, () -> {
                checkInventory(corpse, "entity save/reload preserves inventories");
                check(CorpseCompat.hasPhysics(corpse) && CorpsePose.valid(((CorpsePoseAccess)corpse).getRagRevivalPose()),
                        "entity save/reload resumes a valid native articulated body");
                owner.getInventory().clearContent();
                var menu = new CorpseInventoryContainer(1, owner.getInventory(), corpse, true, false);
                menu.transferItems();
                check(owner.getInventory().getItem(8).is(Items.DIAMOND)
                                && owner.getInventory().getItem(8).getCount() == 7
                                && owner.getInventory().armor.get(2).is(Items.DIAMOND_CHESTPLATE)
                                && owner.getOffhandItem().is(Items.SHIELD),
                        "native Transfer Items restores original inventory, armor, and offhand slots");
                check(corpse.isEmpty(), "transfer empties the authoritative inventory");
                owner.closeContainer();
            });
            after(10, () -> {
                check(corpse.isRemoved(), "empty corpse obeys native despawn timing");
                check(!CorpseCompat.hasPhysics(corpse), "empty-corpse removal releases temporary physics");
                var death = DeathManager.getDeaths(level, owner.getUUID()).getFirst();
                owner.setGameMode(GameType.CREATIVE);
                Guis.openCorpseGUI(owner, owner.getUUID(), death.getId());
                check(owner.containerMenu instanceof CorpseInventoryContainer history && history.isHistory() && history.isEditable(),
                        "creative death-history recovery remains available");
                owner.closeContainer();
                owner.setGameMode(GameType.SURVIVAL);
                beginVoidFall();
            });
            after(130, () -> {
                scenario = "falling into void";
                check(!owner.isAlive() && !DownedManager.isDowned(owner), "physical fall below the void boundary causes terminal death");
                corpse = requireCorpse(owner);
                checkInventory(corpse, "void death preserves the native inventory");
                check(corpse.getY() >= level.getMinBuildHeight() - 0.1, "corpse is recoverable at the minimum build height");
                check(CorpseCompat.hasPhysics(corpse), "void protection retains the native articulated body");
                owner = respawn(owner);
                owner.teleportTo(site.x, site.y, site.z);
            });
            after(30, () -> {
                check(!corpse.isRemoved() && corpse.getY() >= level.getMinBuildHeight() - 0.1,
                        "void protection remains stable across subsequent physics ticks");
                corpse.discard();
                beginLavaFall();
            });
            after(90, () -> {
                scenario = "falling into lava";
                check(DownedManager.isDowned(owner), "falling into real lava triggers knockdown");
                check(corpsesFor(owner).isEmpty(), "lava knockdown does not prematurely duplicate inventory");
                DownedManager.finishDeath(owner);
                corpse = requireCorpse(owner);
                checkInventory(corpse, "terminal lava death preserves inventory");
                owner = respawn(owner);
                owner.teleportTo(site.x, site.y, site.z);
            });
            after(80, () -> {
                check(!corpse.isRemoved() && !corpse.isEmpty(), "corpse survives eighty ticks in the lava basin");
                check(CorpseCompat.hasPhysics(corpse), "lava retains native Sable physics and articulation");
                check(corpse.getY() > lavaSite.y - 1, "lava body stays above the basin floor");
                corpse.hurt(level.damageSources().lava(), 4);
                check(!corpse.isRemoved(), "default lavaDamage=false preserves the corpse");
                Main.SERVER_CONFIG.lavaDamage.set(true);
                corpse.hurt(level.damageSources().lava(), 4);
                check(corpse.isRemoved(), "opt-in lavaDamage=true retains native destructive behavior");
                Main.SERVER_CONFIG.lavaDamage.set(false);
            });
            after(5, this::keepInventory);
            after(5, this::nativeLifetime);
            after(5, () -> {
                check(corpse.isRemoved(), "configured forced lifetime still removes a full corpse");
                Main.SERVER_CONFIG.corpseForceDespawnTime.set(-1);
                startStress();
            });
            after(10, () -> {
                check(stressCorpses.size() == 12 && stressCorpses.stream().allMatch(CorpseCompat::hasPhysics),
                        "all twelve nearby corpses simulate despite the obsolete two-body setting");
                check(stressCorpses.stream().allMatch(entity -> NativeRagdollCompat.linkedParts(level, CorpseCompat.rootId(entity)).size() == 6),
                        "twelve native bodies retain all seventy-two articulated parts");
                check(stressCorpses.stream().allMatch(entity -> !entity.isRemoved() && !entity.isEmpty()
                                && CorpsePose.valid(((CorpsePoseAccess)entity).getRagRevivalPose())),
                        "all twelve corpses retain loot and a six-limb pose");
            });
            after(250, () -> {
                check(peakBodies >= 12, "native simulation is not restricted to the obsolete eight-body cap");
                check(stressCorpses.stream().allMatch(CorpseCompat::hasPhysics),
                        "all twelve bodies remain articulated after thirteen seconds despite the obsolete timer");
                check(stressCorpses.stream().allMatch(entity -> !entity.isRemoved() && !entity.isEmpty()),
                        "continued native simulation preserves all twelve loot inventories");
                stressCorpses.forEach(Entity::discard);
            });
            after(3, () -> {
                check(CorpseCompat.activeBodyCount() == 0, "removing stress corpses leaves no tracked physics bodies");
                spawnOrphan();
            });
            after(65, () -> {
                var container = SubLevelContainer.getContainer(level);
                check(orphanParts.size() == 6 && orphanParts.stream().allMatch(id ->
                                container.getSubLevel(id) == null || container.getSubLevel(id).isRemoved()),
                        "discovered orphan markers remove all six abandoned Sable parts");
                var ordinaryRoot = container.getSubLevel(ordinary.id());
                check(ordinaryRoot != null && !ordinaryRoot.isRemoved(), "orphan cleanup preserves unrelated ordinary ragdolls");
                stagingCollision();
            });
            after(5, () -> {
                check(!CorpseCompat.hasPhysics(corpse), "occupied build-limit staging cells skip optional physics");
                check(stagingBlocks.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK)),
                        "all six preexisting build-limit blocks remain unchanged");
                check(!corpse.isEmpty() && CorpsePose.valid(((CorpsePoseAccess)corpse).getRagRevivalPose()),
                        "blocked assembly still leaves a lootable posed corpse");
                floorSetup();
            });
            floorChecks();
        }

        void planFloorOnly() {
            after(1, () -> {
                var spawn = level.getSharedSpawnPos();
                site = new Vec3(spawn.getX() + 0.5, 80, spawn.getZ() + 0.5);
                owner = player("FloorOwner", site);
                visitor = player("FloorVisitor", site.add(4, 0, 0));
                floorSetup();
            });
            floorChecks();
        }

        private void floorChecks() {
            after(10, () -> {
                check(CorpseCompat.hasPhysics(floorCorpse), "elevated solid-floor fixture starts native corpse physics");
            });
            after(90, () -> {
                check(CorpseCompat.hasPhysics(floorReloadCorpse), "reload fixture has an active native body before serialization");
                floorReloadRoot = CorpseCompat.rootId(floorReloadCorpse);
                floorReloadParts = List.copyOf(NativeRagdollCompat.linkedParts(level, floorReloadRoot));
                floorReloadCorpse.setPos(floorReloadCorpse.getX(), floorSite.y - 0.02, floorReloadCorpse.getZ());
                CompoundTag saved = new CompoundTag();
                floorReloadCorpse.save(saved);
                floorReloadCorpse.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                check(!CorpseCompat.hasPhysics(floorReloadCorpse), "actual entity unload immediately releases its native body");
                floorReloadCorpse = new CorpseEntity(level);
                floorReloadCorpse.load(saved);
                level.addFreshEntity(floorReloadCorpse);
                corpses.add(floorReloadCorpse);
            });
            after(100, () -> {
                check(floorCorpse.getY() >= floorSite.y - 0.25 && lowestFloorY >= floorSite.y - 0.25,
                        "native simulation stays within its expected quarter-block surface overlap");
                check(CorpseCompat.hasPhysics(floorReloadCorpse) && !floorReloadRoot.equals(CorpseCompat.rootId(floorReloadCorpse)),
                        "reloaded corpse creates one fresh articulated native body");
                check(partsAbsent(floorReloadParts), "all six old unloaded parts are gone after body recreation");
                check(floorReloadCorpse.getY() >= floorSite.y - 0.25,
                        "saved active pose remains supported through one hundred resumed physics ticks");
                checkInventory(floorReloadCorpse, "reloaded active pose preserves every inventory compartment");
            });
            after(220, () -> {
                check(CorpseCompat.hasPhysics(floorCorpse), "rested floor corpse remains a native body beyond twenty seconds");
                check(floorCorpse.getY() >= floorSite.y - 0.25,
                        "continued native simulation remains supported on the solid platform");
                checkInventory(floorCorpse, "floor-settled corpse retains its complete inventory");
                var control = SubLevelContainer.getContainer(level).getSubLevel(floorControl.id());
                Vec3 center = control.logicalPose().transformPosition(Vec3.atCenterOf(control.getPlot().getCenterBlock()));
                check(center.y >= floorSite.y, "ordinary native playerless control also collides with the platform");
                carryOnSafety();
                nudgedPart = nativePart(floorCorpse, 2);
                nudgedBefore = nativeCenter(nudgedPart);
                nudgedPose = ((CorpsePoseAccess)floorCorpse).getRagRevivalPose().copy();
                RigidBodyHandle.of(nudgedPart).addLinearAndAngularVelocity(new Vector3d(2, 3, 0), new Vector3d(3, 0, 1));
            });
            after(20, () -> {
                check(CorpseCompat.hasPhysics(floorCorpse) && nativeCenter(nudgedPart).distanceTo(nudgedBefore) > 0.05,
                        "native impulse wakes and moves a limb after twenty seconds of rest");
                check(!nudgedPose.getList("parts", Tag.TAG_COMPOUND).equals(((CorpsePoseAccess)floorCorpse).getRagRevivalPose().getList("parts", Tag.TAG_COMPOUND)),
                        "rested corpse changes its articulated limb transforms after a native impulse");
                grabbedSublevel = nativePart(floorCorpse, 1);
                grabbedPart = (RagdollPartBlockEntity)level.getBlockEntity(grabbedSublevel.getPlot().getCenterBlock());
                grabbedBefore = nativeCenter(grabbedSublevel);
                visitor.getInventory().clearContent();
                visitor.teleportTo(grabbedBefore.x, floorSite.y + 0.5, grabbedBefore.z + 2);
                visitor.setYRot(180); visitor.setXRot(40);
                gripPlayer = visitor;
                nativeGrab(false);
                check(!nativeGrabbed(), "native grab packet rejects another player under Corpse owner-only access");
                floorCorpse.setIsSkeleton(true);
                nativeGrab(false);
                check(nativeGrabbed(), "native skeleton access also permits another player to drag");
                nativeGrab(true);
                floorCorpse.setIsSkeleton(false);
                owner.setGameMode(GameType.CREATIVE);
                owner.getAbilities().flying = true;
                owner.getInventory().clearContent();
                owner.teleportTo(grabbedBefore.x, floorSite.y + 0.5, grabbedBefore.z + 2);
                owner.setYRot(180); owner.setXRot(40);
                visitor.teleportTo(floorSite.x + 8, floorSite.y, floorSite.z + 8);
                gripPlayer = owner;
                owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                nativeGrab(false);
                check(!nativeGrabbed(), "nonempty main hand reserves Use for native loot instead of dragging");
                owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                Vec3 near = owner.position();
                owner.teleportTo(near.x + 20, near.y, near.z);
                nativeGrab(false);
                check(!nativeGrabbed(), "native corpse grip rejects an out-of-reach packet");
                owner.teleportTo(near.x, near.y, near.z);
                nativeGrab(false);
                check(nativeGrabbed(), "ordinary Sable grab packet accepts the owner's rested corpse limb");
            });
            after(20, () -> {
                owner.teleportTo(owner.getX() + 1, owner.getY(), owner.getZ());
            });
            after(20, () -> {
                check(nativeGrabbed() && nativeCenter(grabbedSublevel).distanceTo(grabbedBefore) > 0.1,
                        "native spring dragging moves the corpse without teleporting its body");
                nativeGrab(true);
                check(!nativeGrabbed(), "ordinary Sable release packet ends the corpse grip");
                nativeGrab(false);
                check(nativeGrabbed(), "released corpse can be grabbed again without rebuilding its body");
                floorCorpse.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                check(!nativeGrabbed() && !CorpseCompat.hasPhysics(floorCorpse), "unloading a held corpse releases the native grip and runtime binding");
                owner.setGameMode(GameType.SURVIVAL);
                owner.getAbilities().flying = false;
                floorCorpse = null;
                floorControl.release();
                floorControl = null;
                for (CorpseEntity previous : corpses) if (!previous.isRemoved()) previous.setRemoved(Entity.RemovalReason.DISCARDED);
                scenario = "captured pose ignores obsolete zero-body setting";
                RevivalConfig.MAX_CORPSE_RAGDOLLS.set(0);
                seedInventory(owner);
                owner.teleportTo(floorSite.x, floorSite.y, floorSite.z);
                DownedManager.down(owner, owner.damageSources().generic());
                check(DownedManager.isDowned(owner), "native downed body starts on the raised platform");
            });
            after(40, () -> {
                DownedManager.finishDeath(owner);
                corpse = requireCorpse(owner);
            });
            after(60, () -> {
                check(CorpseCompat.hasPhysics(corpse), "obsolete zero-body setting does not freeze the captured corpse");
                check(corpse.getY() >= floorSite.y - 0.25, "captured downed pose continues native physics above its supporting floor");
                checkInventory(corpse, "captured articulated corpse keeps all inventory compartments");
                finish(true);
            });
        }

        private boolean partsAbsent(List<UUID> ids) {
            var container = SubLevelContainer.getContainer(level);
            return ids.size() == 6 && ids.stream().allMatch(id ->
                    (container.getSubLevel(id) == null || container.getSubLevel(id).isRemoved())
                            && container.getHoldingChunkMap().getHoldingSubLevel(id) == null);
        }

        private ServerSubLevel nativePart(CorpseEntity body, int ordinal) {
            UUID root = CorpseCompat.rootId(body);
            if (root == null) throw new IllegalStateException("Expected a native corpse root");
            for (UUID id : NativeRagdollCompat.linkedParts(level, root)) {
                var sublevel = SubLevelContainer.getContainer(level).getSubLevel(id);
                if (sublevel instanceof ServerSubLevel part && part.getPlot() != null
                        && level.getBlockEntity(part.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block
                        && block.bodyPart().ordinal() == ordinal) return part;
            }
            throw new IllegalStateException("Missing native corpse limb " + ordinal);
        }

        private Vec3 nativeCenter(ServerSubLevel part) {
            return part.logicalPose().transformPosition(Vec3.atCenterOf(part.getPlot().getCenterBlock()));
        }

        private void nativeGrab(boolean release) {
            IPayloadContext context = (IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                    new Class<?>[]{IPayloadContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("player")) return gripPlayer;
                        if (method.getName().equals("enqueueWork")) { ((Runnable)args[0]).run(); return CompletableFuture.completedFuture(null); }
                        throw new UnsupportedOperationException("Unexpected native payload context method: " + method.getName());
                    });
            RagdollGrabPacket.handle(new RagdollGrabPacket(grabbedPart.getBlockPos(), release), context);
        }

        private boolean nativeGrabbed() {
            try {
                Field grabbers = RagdollPartBlockEntity.class.getDeclaredField("grabbers");
                grabbers.setAccessible(true);
                return ((Map<?, ?>)grabbers.get(grabbedPart)).containsKey(gripPlayer.getUUID());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }

        private void openLoot(CorpseEntity body, ServerPlayer player) {
            boolean shifted = player.isShiftKeyDown();
            player.setShiftKeyDown(true);
            try { body.interact(player, InteractionHand.MAIN_HAND); }
            finally { player.setShiftKeyDown(shifted); }
        }

        private void carryOnSafety() {
            if (!ModList.get().isLoaded("carryon")) return;
            var originalCarry = tschipp.carryon.common.carry.CarryOnDataManager.getCarryData(owner).clone();
            Vec3 position = owner.position();
            GameType originalMode = owner.gameMode.getGameModeForPlayer();
            // Creative mode bypasses Carry On's size restriction so its real pickup callback is exercised.
            owner.setGameMode(GameType.CREATIVE);
            owner.getInventory().clearContent();
            owner.teleportTo(floorCorpse.getX() + 1, floorCorpse.getY() + 1, floorCorpse.getZ());
            var carry = tschipp.carryon.common.carry.CarryOnDataManager.getCarryData(owner);
            carry.setKeyPressed(true); carry.setTick(owner.tickCount - 1);
            CompoundTag inventory = floorCorpse.getDeath().toNBT(level.registryAccess());
            AABB area = floorCorpse.getBoundingBox().inflate(6);
            int dropsBefore = level.getEntitiesOfClass(ItemEntity.class, area).size();
            boolean[] callback = new boolean[2];
            try {
                LOGGER.info("RAGREVIVAL_CARRYON_PRECONDITIONS general={} permitted={} distance={} width={} maxWidth={} height={} invulnerableTime={} mode={}",
                        tschipp.carryon.common.carry.PickupHandler.canCarryGeneral(owner),
                        tschipp.carryon.common.config.ListHandler.isPermitted(floorCorpse), owner.distanceTo(floorCorpse),
                        tschipp.carryon.utils.SizeHelper.getRelativeEntityWidth(owner, floorCorpse),
                        tschipp.carryon.Constants.COMMON_CONFIG.settings.maxEntityWidth,
                        tschipp.carryon.utils.SizeHelper.getRelativeEntityHeight(owner, floorCorpse), floorCorpse.invulnerableTime,
                        owner.gameMode.getGameModeForPlayer());
                boolean picked = tschipp.carryon.common.carry.PickupHandler.tryPickupEntity(owner, floorCorpse, target -> {
                    callback[0] = true;
                    var event = new tschipp.carryon.events.EntityPickupEvent(owner, target);
                    NeoForge.EVENT_BUS.post(event);
                    callback[1] = event.isCanceled();
                    return !event.isCanceled();
                });
                check(callback[0] && callback[1], "real Carry On entity-pickup handler reaches the canceled compatibility event");
                check(!picked && !carry.isCarrying() && !floorCorpse.isRemoved() && level.getEntity(floorCorpse.getUUID()) == floorCorpse,
                        "Carry On cannot replace the authoritative corpse with a carried entity copy");
                check(inventory.equals(floorCorpse.getDeath().toNBT(level.registryAccess()))
                                && dropsBefore == level.getEntitiesOfClass(ItemEntity.class, area).size(),
                        "Carry On pickup denial preserves every inventory item and creates no drops");
                var part = nativePart(floorCorpse, 1);
                BlockPos blockPos = part.getPlot().getCenterBlock();
                var blockState = level.getBlockState(blockPos);
                // Carry On's block handler posts this callback after its distance/eligibility checks.
                // Plotyard coordinates cannot be approached by the world-space player fixture.
                var breakEvent = new BlockEvent.BreakEvent(level, blockPos, blockState, owner);
                NeoForge.EVENT_BUS.post(breakEvent);
                check(breakEvent.isCanceled() && level.getBlockState(blockPos).equals(blockState) && !part.isRemoved(),
                        "Carry On's marked-limb break callback is canceled without removing the native part");
            } finally {
                tschipp.carryon.common.carry.CarryOnDataManager.setCarryData(owner, originalCarry);
                owner.setGameMode(originalMode);
                owner.teleportTo(position.x, position.y, position.z);
            }
        }

        private void seedInventory(ServerPlayer player) {
            player.getInventory().clearContent();
            player.getInventory().setItem(8, new ItemStack(Items.DIAMOND, 7));
            player.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        }

        private void poseMath() {
            scenario = "pose geometry";
            boolean valid = true;
            boolean grounded = true;
            for (int yaw = 0; yaw < 360; yaw += 15) for (boolean faceDown : new boolean[]{false, true}) {
                CompoundTag pose = CorpsePose.resting(yaw, faceDown);
                valid &= CorpsePose.valid(pose);
                var bounds = CorpsePose.bounds(new Vec3(10, -64, 20), pose);
                grounded &= Math.abs(bounds.minY + 64) < 1E-8 && Math.abs(bounds.maxY + 63.5) < 1E-8;
            }
            check(valid, "48 yaw/face resting poses contain six finite normalized transforms");
            check(grounded, "all 48 resting poses sit on their anchor with half-block total height");
            CompoundTag corrupt = CorpsePose.resting(0, false);
            corrupt.getList("parts", Tag.TAG_COMPOUND).getCompound(0).putDouble("x", Double.NaN);
            check(!CorpsePose.valid(corrupt), "NaN limb coordinates are rejected");
            corrupt = CorpsePose.resting(0, false);
            corrupt.getList("parts", Tag.TAG_COMPOUND).getCompound(0).putDouble("qw", 5);
            check(!CorpsePose.valid(corrupt), "unnormalized limb quaternions are rejected");
            corrupt = CorpsePose.resting(0, false);
            corrupt.getList("parts", Tag.TAG_COMPOUND).remove(0);
            check(!CorpsePose.valid(corrupt), "incomplete limb lists are rejected");
        }

        private void checkInventory(CorpseEntity entity, String message) {
            Death death = entity.getDeath();
            boolean matches = death != null && death.getMainInventory().get(8).is(Items.DIAMOND)
                            && death.getMainInventory().get(8).getCount() == 7
                            && death.getArmorInventory().get(2).is(Items.DIAMOND_CHESTPLATE)
                            && death.getOffHandInventory().getFirst().is(Items.SHIELD);
            if (!matches && death != null) LOGGER.error("RAGREVIVAL_CORPSE inventory mismatch: main={} armor={} offhand={}",
                    death.getMainInventory(), death.getArmorInventory(), death.getOffHandInventory());
            check(matches, message);
        }

        private List<CorpseEntity> corpsesFor(ServerPlayer player) {
            List<CorpseEntity> found = new ArrayList<>();
            level.getAllEntities().forEach(entity -> {
                if (entity instanceof CorpseEntity body && !body.isRemoved()
                        && body.getCorpseUUID().filter(player.getUUID()::equals).isPresent()) found.add(body);
            });
            return found;
        }

        private CorpseEntity requireCorpse(ServerPlayer player) {
            var found = corpsesFor(player);
            check(found.size() == 1, "one native corpse was created for this death");
            if (found.size() != 1) throw new IllegalStateException("Expected one corpse; found " + found.size());
            var result = found.getFirst();
            corpses.add(result);
            return result;
        }

        private void saveReload() {
            CompoundTag saved = new CompoundTag();
            corpse.save(saved);
            // PersistentEntitySectionManager unloads with setRemoved; Corpse.remove is a
            // destructive gameplay operation which drops its loot even for an unload reason.
            corpse.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            savedPose = ((CorpsePoseAccess)corpse).getRagRevivalPose().copy();
            // Save after retiring the temporary body: real chunk unload invokes the same cleanup.
            corpse.saveWithoutId(saved);
            corpse = new CorpseEntity(level);
            corpse.load(saved);
            check(savedPose.getList("parts", Tag.TAG_COMPOUND).equals(((CorpsePoseAccess)corpse).getRagRevivalPose().getList("parts", Tag.TAG_COMPOUND)),
                    "entity deserialization restores all six saved transforms before physics resumes");
            level.addFreshEntity(corpse);
            corpses.add(corpse);
        }

        private void beginVoidFall() {
            scenario = "falling into void";
            seedInventory(owner);
            owner.teleportTo(site.x, level.getMinBuildHeight() - 1, site.z);
            owner.setDeltaMovement(0, -0.25, 0);
            owner.invulnerableTime = 0;
        }

        private void beginLavaFall() {
            scenario = "falling into lava";
            lavaSite = site.add(12, 0, 0);
            BlockPos center = BlockPos.containing(lavaSite);
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 2; y++) level.setBlockAndUpdate(center.offset(x, y, z),
                        Math.abs(x) == 3 || Math.abs(z) == 3 ? Blocks.STONE.defaultBlockState()
                                : y < 2 ? Blocks.LAVA.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            seedInventory(owner);
            owner.teleportTo(lavaSite.x, lavaSite.y + 4, lavaSite.z);
            owner.setHealth(1);
            owner.setDeltaMovement(0, -0.2, 0);
            owner.invulnerableTime = 0;
        }

        private void keepInventory() {
            scenario = "keepInventory";
            server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
            seedInventory(owner);
            owner.hurt(owner.damageSources().genericKill(), 1000);
            var nativeBodies = corpsesFor(owner);
            corpses.addAll(nativeBodies);
            check(!owner.isAlive() && nativeBodies.size() <= 1 && nativeBodies.stream().allMatch(CorpseEntity::isEmpty),
                    "keepInventory death creates no duplicate loot (native empty corpse is allowed)");
            check(owner.getInventory().getItem(8).getCount() == 7, "keepInventory keeps items on the player");
            owner = respawn(owner);
            owner.teleportTo(site.x, site.y, site.z);
            server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
        }

        private void nativeLifetime() {
            scenario = "forced lifetime";
            Main.SERVER_CONFIG.corpseForceDespawnTime.set(2);
            seedInventory(owner);
            corpse = CorpseEntity.createFromDeath(owner, Death.fromPlayer(owner));
            level.addFreshEntity(corpse);
            corpses.add(corpse);
        }

        private void startStress() {
            scenario = "twelve persistent native bodies";
            RevivalConfig.MAX_CORPSE_RAGDOLLS.set(2);
            RevivalConfig.CORPSE_PHYSICS_TICKS.set(20);
            peakBodies = 0;
            seedInventory(owner);
            for (int i = 0; i < 12; i++) {
                CorpseEntity entity = CorpseEntity.createFromDeath(owner, Death.fromPlayer(owner));
                entity.setPos(site.x + i % 4 * 3, site.y, site.z + i / 4 * 3);
                level.addFreshEntity(entity);
                corpses.add(entity);
                stressCorpses.add(entity);
            }
        }

        private void spawnOrphan() {
            scenario = "orphan cleanup";
            orphan = RagdollAPI.spawnPlayerless(level, site.add(2, 0, 5), 0, owner.getGameProfile(), Vec3.ZERO, PlayerlessDespawnRule.never());
            ordinary = RagdollAPI.spawnPlayerless(level, site.add(5, 0, 5), 0, owner.getGameProfile(), Vec3.ZERO, PlayerlessDespawnRule.never());
            if (orphan == null || ordinary == null) throw new IllegalStateException("Could not create orphan fixture bodies");
            orphanParts = List.copyOf(NativeRagdollCompat.linkedParts(level, orphan.id()));
            var container = SubLevelContainer.getContainer(level);
            UUID absentCorpse = UUID.randomUUID();
            for (UUID id : orphanParts) {
                var part = container.getSubLevel(id);
                if (part == null || part.getPlot() == null) throw new IllegalStateException("Missing orphan fixture part");
                if (!(level.getBlockEntity(part.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block))
                    throw new IllegalStateException("Missing orphan fixture block entity");
                block.getPersistentData().putBoolean(CorpsePose.PART_MARKER, true);
                block.getPersistentData().putUUID("ragrevival:corpse_root", orphan.id());
                block.getPersistentData().putUUID("ragrevival:corpse_owner", absentCorpse);
                block.setChanged();
            }
        }

        private void stagingCollision() {
            scenario = "safe assembly staging";
            for (int i = 1; i <= 6; i++) {
                BlockPos pos = new BlockPos((int)Math.floor(site.x), level.getMaxBuildHeight() - i, (int)Math.floor(site.z));
                if (!level.isEmptyBlock(pos)) throw new IllegalStateException("Staging fixture expected empty flat-world ceiling");
                stagingBlocks.add(pos);
                level.setBlockAndUpdate(pos, Blocks.DIAMOND_BLOCK.defaultBlockState());
            }
            seedInventory(owner);
            corpse = CorpseEntity.createFromDeath(owner, Death.fromPlayer(owner));
            corpse.setPos(site);
            level.addFreshEntity(corpse);
            corpses.add(corpse);
        }

        private void clearFixtureDrops() {
            if (site == null) return;
            // Corpse's native destructive removal drops contents. Prevent a subsequent fixture
            // from picking up leftovers and changing its carefully seeded inventory counts.
            level.getEntitiesOfClass(ItemEntity.class, new AABB(site, site).inflate(40)).forEach(Entity::discard);
        }

        private void floorSetup() {
            scenario = "solid floor settling";
            for (BlockPos pos : stagingBlocks) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            stagingBlocks.clear();
            if (orphan != null) { orphan.release(); orphan = null; }
            if (ordinary != null) { ordinary.release(); ordinary = null; }
            RevivalConfig.MAX_CORPSE_RAGDOLLS.set(8);
            RevivalConfig.CORPSE_PHYSICS_TICKS.set(200);
            floorSite = new Vec3(site.x, Math.max(96, site.y + 16), site.z);
            BlockPos center = BlockPos.containing(floorSite).below();
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++)
                level.setBlockAndUpdate(center.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
            owner.teleportTo(floorSite.x - 4, floorSite.y, floorSite.z + 4);
            visitor.teleportTo(floorSite.x + 4, floorSite.y, floorSite.z + 4);
            seedInventory(owner);
            floorCorpse = CorpseEntity.createFromDeath(owner, Death.fromPlayer(owner));
            floorCorpse.setPos(floorSite.add(-2, 0, 0));
            level.addFreshEntity(floorCorpse);
            corpses.add(floorCorpse);
            floorReloadCorpse = CorpseEntity.createFromDeath(owner, Death.fromPlayer(owner));
            floorReloadCorpse.setPos(floorSite.add(6, 0, 0));
            level.addFreshEntity(floorReloadCorpse);
            corpses.add(floorReloadCorpse);
            floorControl = RagdollAPI.spawnPlayerless(level, floorSite.add(2, 0, 0), 0,
                    owner.getGameProfile(), new Vec3(0, 0, -0.5), PlayerlessDespawnRule.never());
            if (floorControl == null) throw new IllegalStateException("Could not create solid-floor control body");
            floorStarted = ticks;
            lowestFloorY = floorCorpse.getY();
        }

        @SuppressWarnings("unchecked")
        private ServerPlayer player(String name, Vec3 pos) {
            ServerPlayer player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
            player.connection = new PacketSink(server, player);
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(pos.x, pos.y, pos.z);
            playerListField("players", List.class).add(player);
            playerListField("playersByUUID", Map.class).put(player.getUUID(), player);
            players.add(player);
            level.addNewPlayer(player);
            return player;
        }

        @SuppressWarnings("unchecked")
        private <T> T playerListField(String name, Class<T> type) {
            try {
                Field field = PlayerList.class.getDeclaredField(name);
                field.setAccessible(true);
                return (T)field.get(server.getPlayerList());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Fixture registration failed", failure); }
        }

        private ServerPlayer respawn(ServerPlayer previous) {
            ServerPlayer replacement = server.getPlayerList().respawn(previous, false, Entity.RemovalReason.KILLED);
            replacement.connection.player = replacement;
            players.remove(previous);
            players.add(replacement);
            return replacement;
        }

        void advance() {
            ticks++;
            if (floorCorpse != null) {
                lowestFloorY = Math.min(lowestFloorY, floorCorpse.getY());
                int elapsed = ticks - floorStarted;
                if (elapsed % 20 == 0 || elapsed >= 198 && elapsed <= 203) {
                    var control = SubLevelContainer.getContainer(level).getSubLevel(floorControl.id());
                    Vec3 center = control == null || control.getPlot() == null ? Vec3.ZERO
                            : control.logicalPose().transformPosition(Vec3.atCenterOf(control.getPlot().getCenterBlock()));
                    LOGGER.info("RAGREVIVAL_CORPSE_FLOOR tick={} corpseY={} bboxMinY={} active={} nativeControlTorsoY={} platformTop={}",
                            elapsed, floorCorpse.getY(), floorCorpse.getBoundingBox().minY,
                            CorpseCompat.hasPhysics(floorCorpse), center.y, floorSite.y);
                }
            }
            if (!stressCorpses.isEmpty()) peakBodies = Math.max(peakBodies, CorpseCompat.activeBodyCount());
            for (ServerPlayer player : List.copyOf(players)) {
                if (player.isRemoved()) continue;
                ReactionSuppressions.suppress(player, level.getGameTime(), 5);
                if (player.isAlive()) player.doTick();
            }
            while (!steps.isEmpty() && steps.peek().tick <= ticks) steps.remove().action.run();
        }

        private void check(boolean condition, String name) {
            String label = scenario + ": " + name;
            if (condition) { passed++; LOGGER.info("RAGREVIVAL_CORPSE PASS {}", label); }
            else { failures.add(label); LOGGER.error("RAGREVIVAL_CORPSE FAIL {}", label); }
        }

        void finish(boolean completed) {
            try {
                if (orphan != null) orphan.release();
                if (ordinary != null) ordinary.release();
                if (floorControl != null) floorControl.release();
                for (BlockPos pos : stagingBlocks) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                for (CorpseEntity entity : corpses) if (!entity.isRemoved()) entity.discard();
                clearFixtureDrops();
                for (ServerPlayer player : players) {
                    try {
                        if (!player.isRemoved()) {
                            if (DownedManager.isDowned(player)) DownedManager.revive(player);
                            DownedManager.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
                            server.getPlayerList().remove(player);
                        }
                        ((EmbeddedChannel)player.connection.getConnection().channel()).finishAndReleaseAll();
                    } catch (Throwable failure) {
                        LOGGER.error("RAGREVIVAL_CORPSE fixture cleanup failed", failure);
                        failures.add("fixture cleanup");
                    }
                }
            } finally {
                server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(keepInventory, server);
                Main.SERVER_CONFIG.onlyOwnerAccess.set(ownerAccess);
                Main.SERVER_CONFIG.skeletonAccess.set(skeletonAccess);
                Main.SERVER_CONFIG.lavaDamage.set(lavaDamage);
                Main.SERVER_CONFIG.fallIntoVoid.set(fallIntoVoid);
                Main.SERVER_CONFIG.corpseSkeletonTime.set(skeletonTime);
                Main.SERVER_CONFIG.corpseDespawnTime.set(despawnTime);
                Main.SERVER_CONFIG.corpseForceDespawnTime.set(forceDespawnTime);
                RevivalConfig.MAX_CORPSE_RAGDOLLS.set(maxBodies);
                RevivalConfig.CORPSE_PHYSICS_TICKS.set(physicsTicks);
                LOGGER.info("RAGREVIVAL_CORPSE {} passed={} failed={} ticks={}",
                        completed && failures.isEmpty() ? "PASSED" : "FAILED", passed, failures.size(), ticks);
                current = null;
            }
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
