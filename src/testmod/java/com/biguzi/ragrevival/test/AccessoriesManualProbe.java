package com.biguzi.ragrevival.test;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.maxhenkel.corpse.entities.CorpseEntity;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Explicit manual-test fixtures only. No production dependency on Accessories or Artifacts. */
@EventBusSubscriber(modid = "ragrevival_tests", value = Dist.DEDICATED_SERVER)
public final class AccessoriesManualProbe {
    private static final String MARKER = "ragrevival:manual_accessories";
    private static final List<String> ITEMS = List.of("artifacts:plastic_drinking_hat", "artifacts:lucky_scarf");
    private static final Map<UUID, Run> RUNS = new HashMap<>();

    private AccessoriesManualProbe() {}

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(commandRoot("ragrevivaltest", 4));
        event.getDispatcher().register(commandRoot("ragrevivalmanual", 2));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> commandRoot(String root, int permission) {
        return Commands.literal(root).requires(source -> source.hasPermission(permission))
                .then(Commands.literal("accessories-equip").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("label", StringArgumentType.word()).executes(context -> equip(
                                context.getSource(), EntityArgument.getPlayer(context, "player"),
                                StringArgumentType.getString(context, "label"))))))
                .then(Commands.literal("accessories-check").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("stage", StringArgumentType.word()).executes(context -> check(
                                context.getSource(), EntityArgument.getPlayer(context, "player"),
                                StringArgumentType.getString(context, "stage"))))));
    }

    private static int equip(CommandSourceStack source, ServerPlayer player, String label) {
        List<Placement> placed = new ArrayList<>();
        try {
            requireMods();
            requireLabel(label);
            if (!player.isAlive()) throw new IllegalStateException("Player must be alive before equipping test items");
            Api api = new Api(player);
            UUID runId = UUID.randomUUID();
            List<ItemStack> expected = new ArrayList<>();
            Map<String, String> originalSlots = new LinkedHashMap<>();
            for (String itemId : ITEMS) {
                var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
                if (item == Items.AIR) throw new IllegalStateException("Missing real test item " + itemId);
                ItemStack stack = new ItemStack(item);
                CompoundTag marker = new CompoundTag();
                marker.putUUID("run", runId);
                marker.putString("label", label);
                CompoundTag data = new CompoundTag();
                data.put(MARKER, marker);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("[" + label + "] " + stack.getHoverName().getString()));
                Placement placement = api.equipEmpty(stack);
                placed.add(placement);
                expected.add(stack.copy());
                originalSlots.put(itemId, placement.name);
            }
            Run run = new Run(runId, label, expected, originalSlots);
            Map<String, Object> report = snapshot(player, api, run, "equipped");
            Path output = write(player, run, "equipped", report);
            RUNS.put(player.getUUID(), run);
            source.sendSuccess(() -> Component.literal("Equipped real Accessories fixtures " + originalSlots
                    + "; run=" + runId + "; no fire/void protection; report=" + output), false);
            player.sendSystemMessage(Component.literal("Test accessories equipped: " + slotSummary(originalSlots)
                    + ". Ready for a death test; neither item protects against lava or the void."));
            return 1;
        } catch (Exception failure) {
            // Roll back only this command's newly created stacks, never existing equipment.
            for (Placement placement : placed) {
                try { placement.inventory.setItem(placement.index, ItemStack.EMPTY); }
                catch (Exception ignored) { }
            }
            source.sendFailure(Component.literal("Accessories fixture failed: " + failure));
            player.sendSystemMessage(Component.literal("Could not prepare test accessories: " + failure.getMessage()));
            return 0;
        }
    }

    private static int check(CommandSourceStack source, ServerPlayer player, String stage) {
        try {
            requireMods();
            requireLabel(stage);
            Run run = RUNS.get(player.getUUID());
            if (run == null) throw new IllegalStateException("Run accessories-equip first in this server process");
            Map<String, Object> report = snapshot(player, new Api(player), run, stage);
            Path output = write(player, run, stage, report);
            boolean conserved = Boolean.TRUE.equals(report.get("exactlyOneOfEachWithComponents"));
            source.sendSuccess(() -> Component.literal("Accessories " + stage + ": exact=" + conserved
                    + " counts=" + report.get("countsByLocation") + "; report=" + output), false);
            player.sendSystemMessage(Component.literal("Accessory check " + stage + ": "
                    + (conserved ? "PASS — one of each, item data intact. " : "CHECK FAILED — missing, duplicated, or changed items. ")
                    + locationSummary(report, run)));
            return conserved ? 1 : 0;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Accessories inspection failed: " + failure));
            player.sendSystemMessage(Component.literal("Could not check test accessories: " + failure.getMessage()));
            return 0;
        }
    }

    private static String slotSummary(Map<String, String> slots) {
        return slots.entrySet().stream().map(entry -> itemName(entry.getKey()) + " in " + entry.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String itemName(String item) {
        return item.substring(item.indexOf(':') + 1).replace('_', ' ');
    }

    @SuppressWarnings("unchecked")
    private static String locationSummary(Map<String, Object> report, Run run) {
        Map<String, Map<String, Integer>> counts = (Map<String, Map<String, Integer>>) report.get("countsByLocation");
        boolean exact = Boolean.TRUE.equals(report.get("exactlyOneOfEachWithComponents"));
        boolean allInCorpse = exact && ITEMS.stream().allMatch(item -> counts.get(item).getOrDefault("corpse_additional", 0) == 1);
        boolean allInInventory = exact && ITEMS.stream().allMatch(item -> counts.get(item).getOrDefault("player_inventory", 0) == 1);
        if (allInCorpse) return "Both are in the corpse's Additional Items. Use Transfer Items to recover them.";
        if (allInInventory) return "Both are in your normal inventory. Accessory slots were not automatically restored; "
                + "original slots: " + slotSummary(run.originalSlots) + ".";
        return ITEMS.stream().map(item -> itemName(item) + "=" + counts.get(item))
                .collect(java.util.stream.Collectors.joining("; ")) + ". Original slots: " + slotSummary(run.originalSlots) + ".";
    }

    private static Map<String, Object> snapshot(ServerPlayer player, Api api, Run run, String stage) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        Map<String, Integer> componentMatches = new LinkedHashMap<>();
        for (String item : ITEMS) { counts.put(item, new LinkedHashMap<>()); componentMatches.put(item, 0); }
        List<Map<String, Object>> slots = new ArrayList<>();
        for (var entry : api.containers.entrySet()) {
            Object container = entry.getValue();
            Container active = (Container) api.containerClass.getMethod("getAccessories").invoke(container);
            Container cosmetic = (Container) api.containerClass.getMethod("getCosmeticAccessories").invoke(container);
            slots.add(Map.of("name", entry.getKey(), "size", active.getContainerSize(), "cosmeticSize", cosmetic.getContainerSize()));
            scan(player, run, active, "accessories", entry.getKey(), rows, counts, componentMatches);
            scan(player, run, cosmetic, "cosmetic_accessories", entry.getKey(), rows, counts, componentMatches);
        }
        scan(player, run, player.getInventory(), "player_inventory", "vanilla", rows, counts, componentMatches);
        List<Map<String, Object>> corpseDetails = new ArrayList<>();
        for (var level : player.server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof CorpseEntity corpse) {
                    String location = level.dimension().location() + "/" + corpse.getUUID();
                    int before = rows.size();
                    scanList(player, run, corpse.getDeath().getMainInventory(), "corpse_main", location, rows, counts, componentMatches);
                    scanList(player, run, corpse.getDeath().getArmorInventory(), "corpse_armor", location, rows, counts, componentMatches);
                    scanList(player, run, corpse.getDeath().getOffHandInventory(), "corpse_offhand", location, rows, counts, componentMatches);
                    scanList(player, run, corpse.getDeath().getAdditionalItems(), "corpse_additional", location, rows, counts, componentMatches);
                    if (rows.size() != before) corpseDetails.add(Map.of("uuid", corpse.getUUID(), "dimension", level.dimension().location(),
                            "position", corpse.position().toString(), "owner", corpse.getCorpseUUID().map(UUID::toString).orElse("none")));
                } else if (entity instanceof ItemEntity item) {
                    record(player, run, item.getItem(), "world_drop", level.dimension().location() + "/" + item.getUUID(),
                            -1, rows, counts, componentMatches);
                }
            }
        }
        boolean exact = true;
        for (String item : ITEMS) {
            int count = counts.get(item).values().stream().mapToInt(Integer::intValue).sum();
            exact &= count == 1 && componentMatches.get(item) == 1;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "Read-only count of uniquely tagged fixtures in loaded worlds; never forces chunks or transfers items. Cosmetic corpse render copies are deliberately excluded.");
        report.put("run", run.id); report.put("label", run.label); report.put("stage", stage);
        report.put("player", player.getGameProfile().getName()); report.put("playerUUID", player.getUUID());
        report.put("playerPosition", player.position().toString()); report.put("dimension", player.level().dimension().location());
        report.put("gameTime", player.level().getGameTime()); report.put("originalAccessorySlots", run.originalSlots);
        report.put("availableSlots", slots); report.put("items", rows); report.put("corpses", corpseDetails);
        report.put("countsByLocation", counts); report.put("componentMatches", componentMatches);
        report.put("exactlyOneOfEachWithComponents", exact);
        report.put("note", "Main inventory and Additional Items are distinct from equipped Accessories slots; this report makes no auto-reequip assumption.");
        return report;
    }

    private static void scan(ServerPlayer player, Run run, Container inventory, String kind, String container,
            List<Map<String, Object>> rows, Map<String, Map<String, Integer>> counts, Map<String, Integer> matches) {
        for (int i = 0; i < inventory.getContainerSize(); i++) record(player, run, inventory.getItem(i), kind, container, i, rows, counts, matches);
    }

    private static void scanList(ServerPlayer player, Run run, List<ItemStack> inventory, String kind, String container,
            List<Map<String, Object>> rows, Map<String, Map<String, Integer>> counts, Map<String, Integer> matches) {
        for (int i = 0; i < inventory.size(); i++) record(player, run, inventory.get(i), kind, container, i, rows, counts, matches);
    }

    private static void record(ServerPlayer player, Run run, ItemStack stack, String kind, String container, int index,
            List<Map<String, Object>> rows, Map<String, Map<String, Integer>> counts, Map<String, Integer> matches) {
        if (stack.isEmpty()) return;
        CompoundTag marker = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(MARKER);
        if (!marker.hasUUID("run") || !run.id.equals(marker.getUUID("run"))) return;
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        counts.computeIfAbsent(item, ignored -> new LinkedHashMap<>()).merge(kind, stack.getCount(), Integer::sum);
        boolean same = run.expected.stream().anyMatch(expected -> ItemStack.isSameItemSameComponents(expected, stack));
        if (same) matches.merge(item, stack.getCount(), Integer::sum);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("item", item); row.put("count", stack.getCount()); row.put("location", kind);
        row.put("container", container); row.put("slot", index); row.put("sameComponentsAsEquipped", same);
        row.put("stackNBT", stack.save(player.registryAccess()).toString());
        rows.add(row);
    }

    private static Path write(ServerPlayer player, Run run, String stage, Map<String, Object> report) throws Exception {
        Path directory = player.server.getWorldPath(LevelResource.ROOT).resolve("ragrevival-accessories-manual");
        Files.createDirectories(directory);
        Path path = directory.resolve(run.label + "-" + run.id + "-" + stage + ".json");
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        return path.toAbsolutePath();
    }

    private static void requireMods() {
        if (!ModList.get().isLoaded("accessories") || !ModList.get().isLoaded("artifacts") || !ModList.get().isLoaded("corpse"))
            throw new IllegalStateException("This manual fixture requires Accessories, Artifacts, and Corpse");
    }

    private static void requireLabel(String label) {
        if (!label.matches("[a-zA-Z0-9_-]{1,40}")) throw new IllegalArgumentException("Use a short letters/digits/underscore label");
    }

    private record Run(UUID id, String label, List<ItemStack> expected, Map<String, String> originalSlots) {}
    private record Placement(Container inventory, int index, String name) {}

    /** Public API reflection keeps optional mod classes completely out of the test harness's linkage. */
    private static final class Api {
        final Class<?> containerClass = Class.forName("io.wispforest.accessories.api.AccessoriesContainer");
        final Class<?> referenceClass = Class.forName("io.wispforest.accessories.api.slot.SlotReference");
        final Class<?> apiClass = Class.forName("io.wispforest.accessories.api.AccessoriesAPI");
        final Map<String, Object> containers = new TreeMap<>();

        Api(ServerPlayer player) throws Exception {
            Class<?> capabilityClass = Class.forName("io.wispforest.accessories.api.AccessoriesCapability");
            Object capability = capabilityClass.getMethod("get", LivingEntity.class).invoke(null, player);
            if (capability == null) throw new IllegalStateException("Player Accessories capability unavailable");
            Map<?, ?> raw = (Map<?, ?>) capabilityClass.getMethod("getContainers").invoke(capability);
            raw.forEach((key, value) -> containers.put(String.valueOf(key), value));
            if (containers.isEmpty()) throw new IllegalStateException("No Accessories slots loaded for player");
        }

        Placement equipEmpty(ItemStack stack) throws Exception {
            Method valid = apiClass.getMethod("canInsertIntoSlot", ItemStack.class, referenceClass);
            Method equip = apiClass.getMethod("canEquip", ItemStack.class, referenceClass);
            for (var entry : containers.entrySet()) {
                Container inventory = (Container) containerClass.getMethod("getAccessories").invoke(entry.getValue());
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    if (!inventory.getItem(slot).isEmpty()) continue;
                    Object reference = containerClass.getMethod("createReference", int.class).invoke(entry.getValue(), slot);
                    if (!Boolean.TRUE.equals(valid.invoke(null, stack, reference)) || !Boolean.TRUE.equals(equip.invoke(null, stack, reference))) continue;
                    if (!Boolean.TRUE.equals(referenceClass.getMethod("setStack", ItemStack.class).invoke(reference, stack.copy())))
                        throw new IllegalStateException("Accessories rejected validated empty slot " + entry.getKey());
                    containerClass.getMethod("markChanged").invoke(entry.getValue());
                    return new Placement(inventory, slot, entry.getKey() + "[" + slot + "]");
                }
            }
            throw new IllegalStateException("No valid empty Accessories slot for " + BuiltInRegistries.ITEM.getKey(stack.getItem())
                    + "; loaded slots=" + containers.keySet());
        }
    }
}
