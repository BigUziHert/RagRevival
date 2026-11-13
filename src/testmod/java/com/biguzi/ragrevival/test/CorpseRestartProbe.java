package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.RevivalConfig;
import com.biguzi.ragrevival.compat.corpse.CorpseCompat;
import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import de.maxhenkel.corpse.Main;
import de.maxhenkel.corpse.corelib.death.Death;
import de.maxhenkel.corpse.corelib.death.DeathManager;
import de.maxhenkel.corpse.entities.CorpseEntity;
import dev.leo.ragdollreactions.physics.ReactionSuppressions;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import com.biguzi.ragrevival.ragdoll.NativeRagdollCompat;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.Sable;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.joml.Vector3d;

/** Opt-in process-boundary proof. prepare and check must run in different Java processes. */
@EventBusSubscriber(modid = "ragrevival_tests", value = Dist.DEDICATED_SERVER)
public final class CorpseRestartProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MARKER = "ragrevival:restart_fixture";
    private static Run current;
    private static final Map<UUID, InitialState> LOADED_FIXTURES = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void loaded(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof CorpseEntity corpse && event.getLevel() instanceof ServerLevel
                && event.loadedFromDisk() && corpse.getPersistentData().contains(MARKER)) {
            LOADED_FIXTURES.put(corpse.getUUID(), new InitialState(corpse.position(), ((CorpsePoseAccess)corpse).getRagRevivalPose().copy()));
        }
    }

    private record InitialState(Vec3 position, CompoundTag pose) {}

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ragrevivaltest").requires(source -> source.hasPermission(4))
                .then(Commands.literal("corpse-restart-prepare").executes(context -> start(context.getSource().getServer(), false, false)))
                .then(Commands.literal("corpse-crash-prepare").executes(context -> start(context.getSource().getServer(), true, false)))
                .then(Commands.literal("corpse-restart-check").executes(context -> start(context.getSource().getServer(), false, true)))
                .then(Commands.literal("corpse-restart-status").executes(context -> {
                    String status = "pid=" + ProcessHandle.current().pid() + " activeBodies=" + CorpseCompat.activeBodyCount()
                            + " phase=" + (current == null ? "idle" : current.phase);
                    context.getSource().sendSuccess(() -> Component.literal(status), false);
                    return 1;
                })));
    }

    private static int start(MinecraftServer server, boolean crash, boolean checking) {
        if (!ModList.get().isLoaded("corpse") || current != null || !server.getPlayerList().getPlayers().isEmpty()) return 0;
        try {
            current = new Run(server, crash, checking);
            current.begin();
            return 1;
        } catch (Throwable failure) {
            LOGGER.error("RAGREVIVAL_RESTART ABORTED during preparation", failure);
            if (current != null) current.cleanupObserver();
            current = null;
            return 0;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (current == null || current.server != event.getServer()) return;
        try { current.tick(); }
        catch (Throwable failure) {
            LOGGER.error("RAGREVIVAL_RESTART ABORTED phase={} tick={}", current.phase, current.ticks, failure);
            current.failed++;
            current.finish();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopping(ServerStoppingEvent event) {
        if (current == null || current.server != event.getServer() || current.checking) return;
        try {
            current.check(CorpseCompat.activeBodyCount() == 0, "clean shutdown clears runtime bindings before final world save");
            var container = SubLevelContainer.getContainer(current.level);
            current.check(current.originalParts.size() == 12 && current.originalParts.stream().allMatch(part -> {
                var body = container.getSubLevel(part.getUUID("id"));
                return body != null && !body.isRemoved();
            }), "clean shutdown preserves all twelve native parts for ordinary Sable world persistence");
            current.capture("clean-stopping");
            current.restoreSettings();
            current.cleanupObserver();
        } catch (Throwable failure) { LOGGER.error("RAGREVIVAL_RESTART manifest capture failed at stop", failure); }
    }

    private static final class Run {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final boolean crash;
        private final boolean checking;
        private final int originalPhysicsTicks;
        private final int originalMaxBodies;
        private final int originalForceDespawn;
        private final List<CorpseEntity> fixtures = new ArrayList<>();
        private final List<CompoundTag> originalParts = new ArrayList<>();
        private final Path manifest;
        private ServerPlayer observer;
        private CompoundTag expected;
        private UUID runId;
        private int ticks;
        private int passed;
        private int failed;
        private String phase;

        Run(MinecraftServer server, boolean crash, boolean checking) {
            this.server = server;
            level = server.overworld();
            this.crash = crash;
            this.checking = checking;
            manifest = world().resolve("ragrevival-restart-proof.nbt");
            originalPhysicsTicks = RevivalConfig.CORPSE_PHYSICS_TICKS.get();
            originalMaxBodies = RevivalConfig.MAX_CORPSE_RAGDOLLS.get();
            originalForceDespawn = Main.SERVER_CONFIG.corpseForceDespawnTime.get();
        }

        private Path world() { return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(); }

        void begin() throws Exception {
            if (checking) {
                try (var input = new DataInputStream(Files.newInputStream(manifest))) { expected = NbtIo.read(input); }
                if (expected == null) throw new IllegalStateException("Missing restart manifest");
                runId = expected.getUUID("runId");
                phase = "checking-" + expected.getString("phase");
                check(expected.getLong("pid") != ProcessHandle.current().pid(), "check runs in a different Java process");
                check(expected.getString("world").equals(world().toString()), "restart uses the same absolute world directory");
                check(level.getGameTime() >= expected.getLong("gameTime"), "world game time was saved and restored monotonically");
                observer = player(new GameProfile(UUID.randomUUID(), "RestartInspector"), new Vec3(6, 240, 8));
                for (Tag entry : expected.getList("fixtures", Tag.TAG_COMPOUND)) {
                    CompoundTag fixture = (CompoundTag)entry;
                    level.getChunkAt(BlockPos.containing(fixture.getDouble("x"), fixture.getDouble("y"), fixture.getDouble("z")));
                }
                LOGGER.info("RAGREVIVAL_RESTART CHECK_STARTED pid={} priorPid={} run={} world={}",
                        ProcessHandle.current().pid(), expected.getLong("pid"), runId, world());
                return;
            }
            phase = crash ? "preparing-crash" : "preparing-clean";
            runId = UUID.randomUUID();
            // Only remove fixtures explicitly owned by earlier runs of this probe.
            List<Entity> previous = new ArrayList<>();
            level.getAllEntities().forEach(entity -> {
                if (entity instanceof CorpseEntity && entity.getPersistentData().contains(MARKER)) previous.add(entity);
            });
            previous.forEach(entity -> entity.setRemoved(Entity.RemovalReason.DISCARDED));
            RevivalConfig.CORPSE_PHYSICS_TICKS.set(1200);
            RevivalConfig.MAX_CORPSE_RAGDOLLS.set(8);
            Main.SERVER_CONFIG.corpseForceDespawnTime.set(-1);
            for (int x = -8; x <= 20; x++) for (int z = -8; z <= 20; z++)
                level.setBlockAndUpdate(new BlockPos(x, 239, z), Blocks.SMOOTH_STONE.defaultBlockState());
            observer = player(new GameProfile(UUID.randomUUID(), "RestartOwner"), new Vec3(6, 240, 8));
            createFixture("settled-skeleton", new Vec3(2.5, 240, 2.5), true);
            createFixture("active-player", new Vec3(10.5, 240, 2.5), false);
            LOGGER.info("RAGREVIVAL_RESTART PREPARING pid={} run={} world={} crash={}", ProcessHandle.current().pid(), runId, world(), crash);
        }

        private ItemStack item(Item item, int count, String label) {
            ItemStack stack = new ItemStack(item, count);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(runId + ":" + label));
            return stack;
        }

        private void createFixture(String name, Vec3 pos, boolean settled) {
            observer.getInventory().clearContent();
            observer.getInventory().selected = 0;
            observer.getInventory().setItem(settled ? 4 : 0, item(Items.IRON_PICKAXE, 1, name + ":tool"));
            observer.getInventory().setItem(8, item(Items.DIAMOND, 7, name + ":diamonds"));
            observer.getInventory().setItem(15, item(Items.EMERALD, 13, name + ":emeralds"));
            if (!settled) observer.getInventory().armor.set(0, item(Items.DIAMOND_BOOTS, 1, name + ":boots"));
            if (!settled) observer.getInventory().armor.set(1, item(Items.DIAMOND_LEGGINGS, 1, name + ":leggings"));
            observer.getInventory().armor.set(2, item(Items.DIAMOND_CHESTPLATE, 1, name + ":chestplate"));
            if (!settled) observer.getInventory().armor.set(3, item(Items.DIAMOND_HELMET, 1, name + ":helmet"));
            observer.setItemInHand(InteractionHand.OFF_HAND, item(Items.SHIELD, 1, name + ":shield"));
            observer.setPos(pos);
            Death death = Death.fromPlayer(observer);
            death.getAdditionalItems().add(item(Items.GOLD_INGOT, 5, name + ":extra"));
            CorpseEntity corpse = CorpseEntity.createFromDeath(observer, death);
            if (settled) {
                CompoundTag saved = new CompoundTag();
                corpse.saveWithoutId(saved);
                saved.putInt("Age", Main.SERVER_CONFIG.corpseSkeletonTime.get() + 20);
                corpse.load(saved);
                corpse.setEquipment(death.getEquipment());
                ((CorpsePoseAccess)corpse).setRagRevivalPose(CorpsePose.resting(37, false));
            }
            corpse.getPersistentData().putString(MARKER, runId.toString());
            corpse.getPersistentData().putString("restartCase", name);
            level.addFreshEntity(corpse);
            fixtures.add(corpse);
            DeathManager.addDeath(observer, death);
            observer.getInventory().clearContent();
            observer.setPos(6, 240, 8);
        }

        void tick() throws Exception {
            ticks++;
            if (observer != null && !observer.isRemoved()) {
                ReactionSuppressions.suppress(observer, level.getGameTime(), 5);
                observer.doTick();
            }
            if (checking) {
                if (ticks == 1 || ticks == 5 || ticks == 20 || ticks == 40 || ticks == 60 || ticks == 200) {
                    for (Tag tag : expected.getList("fixtures", Tag.TAG_COMPOUND)) {
                        CompoundTag fixture = (CompoundTag)tag;
                        Entity entity = level.getEntity(fixture.getUUID("uuid"));
                        if (entity instanceof CorpseEntity corpse) {
                            var tracking = Sable.HELPER.getTrackingSubLevel(corpse);
                            LOGGER.info("RAGREVIVAL_RESTART SAMPLE tick={} case={} pos={} motion={} tracking={} poseActive={}", ticks,
                                    fixture.getString("case"), corpse.position(), corpse.getDeltaMovement(),
                                    tracking == null ? null : tracking.getUniqueId(), ((CorpsePoseAccess)corpse).getRagRevivalPose().getBoolean("active"));
                        }
                    }
                }
                if (ticks == 200) { verify(); finish(); }
                return;
            }
            if (ticks == 95) {
                for (UUID id : NativeRagdollCompat.linkedParts(level, CorpseCompat.rootId(fixtures.get(1)))) {
                    var part = SubLevelContainer.getContainer(level).getSubLevel(id);
                    if (part instanceof ServerSubLevel nativePart && nativePart.getPlot() != null
                            && level.getBlockEntity(nativePart.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block
                            && block.bodyPart().ordinal() == 2) {
                        RigidBodyHandle.of(nativePart).addLinearAndAngularVelocity(new Vector3d(0.5, 1, 0), new Vector3d(0, 0.6, 0));
                    }
                }
            }
            if (ticks == 100) {
                check(CorpseCompat.hasPhysics(fixtures.get(0)) && fixtures.get(0).isSkeleton(), "rested skeleton fixture retains native physics");
                check(CorpseCompat.hasPhysics(fixtures.get(1)), "second fixture is still actively simulated before shutdown");
                collectParts();
                check(originalParts.size() == 12, "both fixtures own exactly twelve marked Sable parts");
                double savedMotion = 0;
                for (Tag tag : CorpseCompat.poseForSave(fixtures.get(1)).getList("parts", Tag.TAG_COMPOUND)) {
                    CompoundTag limb = (CompoundTag)tag;
                    for (String key : new String[]{"vx", "vy", "vz", "wx", "wy", "wz"}) savedMotion += Math.abs(limb.getDouble(key));
                }
                check(savedMotion > 0.000001, "active fixture has nonzero native limb velocity in its disk snapshot");
                phase = crash ? "crash-ready" : "clean-ready";
                capture(phase);
                server.saveEverything(false, true, true);
                LOGGER.info("RAGREVIVAL_RESTART READY pid={} run={} activeBodies={} markedParts={} world={} manifest={}",
                        ProcessHandle.current().pid(), runId, CorpseCompat.activeBodyCount(), originalParts.size(), world(), manifest);
            }
        }

        private void collectParts() {
            for (var part : SubLevelContainer.getContainer(level).getAllSubLevels()) {
                if (part.isRemoved() || part.getPlot() == null) continue;
                BlockPos pos = part.getPlot().getCenterBlock();
                if (!(level.getBlockEntity(pos) instanceof RagdollPartBlockEntity block)) continue;
                CompoundTag data = block.getPersistentData();
                if (!data.getBoolean(CorpsePose.PART_MARKER) || !data.hasUUID("ragrevival:corpse_owner")
                        || fixtures.stream().noneMatch(corpse -> corpse.getUUID().equals(data.getUUID("ragrevival:corpse_owner")))) continue;
                CompoundTag saved = new CompoundTag();
                saved.putUUID("id", part.getUniqueId());
                saved.putUUID("root", data.getUUID("ragrevival:corpse_root"));
                saved.putInt("plotX", pos.getX()); saved.putInt("plotY", pos.getY()); saved.putInt("plotZ", pos.getZ());
                originalParts.add(saved);
            }
        }

        private void capture(String capturePhase) throws Exception {
            CompoundTag result = new CompoundTag();
            result.putUUID("runId", runId);
            result.putLong("pid", ProcessHandle.current().pid());
            result.putLong("gameTime", level.getGameTime());
            result.putString("world", world().toString());
            result.putString("phase", capturePhase);
            result.putInt("originalPhysicsTicks", originalPhysicsTicks);
            result.putInt("originalMaxBodies", originalMaxBodies);
            result.putInt("originalForceDespawn", originalForceDespawn);
            ListTag entries = new ListTag();
            JsonArray jsonFixtures = new JsonArray();
            for (CorpseEntity corpse : fixtures) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("uuid", corpse.getUUID());
                entry.putUUID("owner", corpse.getCorpseUUID().orElseThrow());
                entry.putUUID("deathId", corpse.getDeath().getId());
                entry.putString("case", corpse.getPersistentData().getString("restartCase"));
                entry.putDouble("x", corpse.getX()); entry.putDouble("y", corpse.getY()); entry.putDouble("z", corpse.getZ());
                entry.putBoolean("skeleton", corpse.isSkeleton());
                entry.put("death", corpse.getDeath().toNBT(level.registryAccess()));
                entry.put("pose", CorpseCompat.poseForSave(corpse));
                entry.put("equipment", equipment(corpse));
                CompoundTag fullEntity = new CompoundTag(); corpse.saveWithoutId(fullEntity); entry.put("entity", fullEntity);
                entries.add(entry);
                JsonObject summary = new JsonObject();
                summary.addProperty("case", entry.getString("case")); summary.addProperty("uuid", corpse.getUUID().toString());
                summary.addProperty("deathId", corpse.getDeath().getId().toString()); summary.addProperty("skeleton", corpse.isSkeleton());
                summary.addProperty("x", corpse.getX()); summary.addProperty("y", corpse.getY()); summary.addProperty("z", corpse.getZ());
                summary.addProperty("pose", entry.getCompound("pose").toString()); summary.addProperty("death", entry.getCompound("death").toString());
                summary.addProperty("equipment", entry.getList("equipment", Tag.TAG_COMPOUND).toString());
                summary.addProperty("entity", fullEntity.toString());
                jsonFixtures.add(summary);
            }
            result.put("fixtures", entries);
            ListTag parts = new ListTag(); originalParts.forEach(part -> parts.add(part.copy())); result.put("parts", parts);
            try (var output = new DataOutputStream(Files.newOutputStream(manifest))) { NbtIo.write(result, output); }
            JsonObject json = new JsonObject();
            json.addProperty("pid", ProcessHandle.current().pid()); json.addProperty("runId", runId.toString());
            json.addProperty("world", world().toString()); json.addProperty("phase", capturePhase);
            json.addProperty("gameTime", level.getGameTime()); json.addProperty("savedNativeParts", parts.size()); json.add("fixtures", jsonFixtures);
            Files.writeString(world().resolve("ragrevival-restart-proof.json"), new GsonBuilder().setPrettyPrinting().create().toJson(json));
            LOGGER.info("RAGREVIVAL_RESTART MANIFEST phase={} fixtures={} nativeParts={} path={}", capturePhase, entries.size(), parts.size(), manifest);
        }

        private ListTag equipment(CorpseEntity corpse) {
            ListTag equipment = new ListTag();
            corpse.getEquipment().forEach(stack -> equipment.add(stack.saveOptional(level.registryAccess())));
            return equipment;
        }

        private void verify() {
            List<CorpseEntity> loaded = new ArrayList<>();
            level.getAllEntities().forEach(entity -> {
                if (entity instanceof CorpseEntity corpse && runId.toString().equals(corpse.getPersistentData().getString(MARKER))) loaded.add(corpse);
            });
            check(loaded.size() == 2, "exactly two persisted fixture corpses are loaded; no missing or duplicate entities");
            for (Tag tag : expected.getList("fixtures", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag)tag;
                String name = entry.getString("case");
                Entity entity = level.getEntity(entry.getUUID("uuid"));
                check(entity instanceof CorpseEntity, name + ": original entity UUID survives the process restart");
                if (!(entity instanceof CorpseEntity corpse)) continue;
                check(corpse.getCorpseUUID().filter(entry.getUUID("owner")::equals).isPresent()
                                && corpse.getDeath().getId().equals(entry.getUUID("deathId")), name + ": owner and death-record UUIDs survive");
                check(entry.getCompound("death").equals(corpse.getDeath().toNBT(level.registryAccess())),
                        name + ": complete main, armor, offhand, extra inventories and item components are byte-for-byte equivalent NBT");
                check(entry.getList("equipment", Tag.TAG_COMPOUND).equals(equipment(corpse)), name + ": all equipment display slots survive");
                LOGGER.info("RAGREVIVAL_RESTART EQUIPMENT case={} expected={} actual={}", name,
                        entry.getList("equipment", Tag.TAG_COMPOUND), equipment(corpse));
                check(entry.getBoolean("skeleton") == corpse.isSkeleton(), name + ": native skeleton stage survives");
                InitialState initial = LOADED_FIXTURES.get(corpse.getUUID());
                CompoundTag expectedPose = entry.getCompound("pose").copy(); expectedPose.remove("active");
                CompoundTag loadedPose = initial == null ? new CompoundTag() : initial.pose.copy(); loadedPose.remove("active");
                check(initial != null && expectedPose.equals(loadedPose),
                        name + ": saved limb transforms and velocities survive disk loading before resumed physics");
                check(CorpsePose.valid(((CorpsePoseAccess)corpse).getRagRevivalPose()), name + ": continued native physics maintains a valid articulated pose");
                Vec3 expectedPos = new Vec3(entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"));
                LOGGER.info("RAGREVIVAL_RESTART POSITION case={} expected={} loaded={} afterPhysics={}", name, expectedPos,
                        initial == null ? null : initial.position, corpse.position());
                check(initial != null && initial.position.distanceTo(expectedPos) < 0.000001,
                        name + ": exact saved world position is restored before physics resumes");
                check(corpse.getY() >= 239.75 && corpse.position().distanceTo(expectedPos) < 0.25,
                        name + ": resumed native body settles within a quarter-block of its saved supporting position");
                check(CorpseCompat.hasPhysics(corpse), name + ": reload resumes an articulated native physics body");
                UUID previousRoot = entry.getCompound("pose").getUUID("root");
                check(previousRoot.equals(CorpseCompat.rootId(corpse)), name + ": restart adopts the original saved native root");
                Death history = DeathManager.getDeath(level, entry.getUUID("owner"), entry.getUUID("deathId"));
                check(history != null && history.getId().equals(corpse.getDeath().getId()), name + ": native death-history record remains available");
            }
            var container = SubLevelContainer.getContainer(level);
            boolean adoptedParts = true;
            boolean adoptedTopology = true;
            for (Tag tag : expected.getList("parts", Tag.TAG_COMPOUND)) {
                CompoundTag part = (CompoundTag)tag;
                var present = container.getSubLevel(part.getUUID("id"));
                adoptedParts &= present != null && !present.isRemoved() && present.getPlot() != null;
                if (present != null && present.getPlot() != null) {
                    var block = level.getBlockEntity(present.getPlot().getCenterBlock());
                    adoptedParts &= block instanceof RagdollPartBlockEntity ragdoll
                            && ragdoll.getPersistentData().hasUUID("ragrevival:corpse_root")
                            && ragdoll.getPersistentData().getUUID("ragrevival:corpse_root").equals(part.getUUID("root"));
                }
                adoptedTopology &= NativeRagdollCompat.savedParts(level, part.getUUID("root")).size() == 6;
            }
            check(adoptedParts, "all twelve saved native parts survive as the two adopted corpse bodies");
            check(adoptedTopology, "each adopted native topology retains exactly six original limbs");
            var liveRoots = new HashSet<UUID>();
            loaded.forEach(corpse -> { if (CorpseCompat.rootId(corpse) != null) liveRoots.add(CorpseCompat.rootId(corpse)); });
            long liveParts = container.getAllSubLevels().stream().filter(part -> !part.isRemoved() && part.getPlot() != null)
                    .filter(part -> level.getBlockEntity(part.getPlot().getCenterBlock()) instanceof RagdollPartBlockEntity block
                            && block.getPersistentData().hasUUID("ragrevival:corpse_root")
                            && liveRoots.contains(block.getPersistentData().getUUID("ragrevival:corpse_root"))).count();
            check(liveRoots.size() == 2 && liveParts == 12, "restart resumes exactly two native bodies with twelve limbs and no duplicate limbs");
            long dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(-16, 230, -16, 24, 250, 24)).stream()
                    .filter(item -> item.getItem().getHoverName().getString().contains(runId.toString())).count();
            check(dropped == 0, "restart produced no duplicate fixture item drops");
        }

        private void check(boolean condition, String label) {
            if (condition) passed++; else failed++;
            LOGGER.info("RAGREVIVAL_RESTART {} {}", condition ? "PASS" : "FAIL", label);
        }

        @SuppressWarnings("unchecked")
        private ServerPlayer player(GameProfile profile, Vec3 pos) throws Exception {
            ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            player.connection = new PacketSink(server, player);
            player.setGameMode(GameType.CREATIVE);
            player.setPos(pos);
            Field players = PlayerList.class.getDeclaredField("players"); players.setAccessible(true);
            ((List<ServerPlayer>)players.get(server.getPlayerList())).add(player);
            Field byId = PlayerList.class.getDeclaredField("playersByUUID"); byId.setAccessible(true);
            ((Map<UUID, ServerPlayer>)byId.get(server.getPlayerList())).put(player.getUUID(), player);
            level.addNewPlayer(player);
            return player;
        }

        void cleanupObserver() {
            if (observer == null) return;
            server.getPlayerList().remove(observer);
            ((EmbeddedChannel)observer.connection.getConnection().channel()).finishAndReleaseAll();
            observer = null;
        }

        void restoreSettings() {
            RevivalConfig.CORPSE_PHYSICS_TICKS.set(checking ? expected.getInt("originalPhysicsTicks") : originalPhysicsTicks);
            RevivalConfig.MAX_CORPSE_RAGDOLLS.set(checking ? expected.getInt("originalMaxBodies") : originalMaxBodies);
            Main.SERVER_CONFIG.corpseForceDespawnTime.set(checking ? expected.getInt("originalForceDespawn") : originalForceDespawn);
        }

        void finish() {
            cleanupObserver(); restoreSettings();
            LOGGER.info("RAGREVIVAL_RESTART {} passed={} failed={} ticks={} pid={} priorPid={} run={} world={}",
                    failed == 0 ? "PASSED" : "FAILED", passed, failed, ticks, ProcessHandle.current().pid(),
                    expected == null ? -1 : expected.getLong("pid"), runId, world());
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
