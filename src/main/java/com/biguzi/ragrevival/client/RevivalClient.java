package com.biguzi.ragrevival.client;

import com.biguzi.ragrevival.compat.CarryOnCompat;
import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import com.biguzi.ragrevival.network.InputAction;
import com.biguzi.ragrevival.network.RevivalNetwork;
import com.biguzi.ragrevival.network.StatePayload;
import com.mojang.blaze3d.platform.InputConstants;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Input intent and presentation only. Health, item consumption and all clocks belong to the server. */
public final class RevivalClient {
    private static final TagKey<Item> REVIVAL_ITEMS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("ragrevival", "revival_items"));
    private static final KeyMapping GIVE_UP = new KeyMapping("key.ragrevival.give_up",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
            "key.categories.ragrevival");
    private static final Map<UUID, Snapshot> STATES = new HashMap<>();
    private static UUID activeTarget;
    private static InteractionHand activeHand = InteractionHand.MAIN_HAND;
    private static InputAction activeAction;
    private static boolean givingUp;
    private static boolean suppressUseUntilRelease;
    private static ResourceKey<Level> dimension;

    private RevivalClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(RevivalClient::registerKeys);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, RevivalClient::onInteraction);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RevivalClient::onScroll);
        NeoForge.EVENT_BUS.addListener(RevivalClient::tick);
        NeoForge.EVENT_BUS.addListener(RevivalClient::render);
        NeoForge.EVENT_BUS.addListener(RevivalClient::logout);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(GIVE_UP);
    }

    public static void accept(StatePayload payload) {
        RagdollBridge.acceptClientParts(payload.playerId(), payload.remainingMillis() <= 0 ? List.of() : payload.bodyParts());
        if (payload.remainingMillis() <= 0) {
            STATES.remove(payload.playerId());
            if (payload.playerId().equals(activeTarget)) {
                clearInteraction(false);
                // Keeping the same physical click held after success must not eat a second apple.
                suppressUseUntilRelease = true;
            }
        } else {
            STATES.put(payload.playerId(), new Snapshot(payload, System.nanoTime()));
        }
    }

    public static boolean isDowned(Player player) {
        return player != null && STATES.containsKey(player.getUUID());
    }

    /** Only the exact server-synchronized limbs qualify, never another ragdoll using the same skin. */
    public static boolean shouldOutline(RagdollPartBlockEntity part) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && !mc.player.getUUID().equals(part.skinProfile().getId()) && isDownedPart(part);
    }

    public static boolean isDownedPart(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockEntity(pos) instanceof RagdollPartBlockEntity part
                && isDownedPart(part);
    }

    public static boolean isDownedPart(RagdollPartBlockEntity part) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || part.getLevel() != mc.level) return false;
        Snapshot state = STATES.get(part.skinProfile().getId());
        if (state == null || System.nanoTime() - state.receivedNanos > 5_000_000_000L) return false;
        var subLevel = Sable.HELPER.getContainingClient(part);
        return subLevel != null && !subLevel.isRemoved()
                && state.payload.bodyParts().contains(subLevel.getUniqueId());
    }

    public static boolean hasActiveInteraction() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || isDowned(mc.player) || activeTarget == null || activeAction == null) return false;
        Snapshot state = STATES.get(activeTarget);
        return state != null && System.nanoTime() - state.receivedNanos <= 5_000_000_000L;
    }

    public static boolean hasActiveDrag() {
        return activeAction == InputAction.DRAG && hasActiveInteraction();
    }

    private static void onScroll(InputEvent.MouseScrollingEvent event) {
        // Camera mods (including Unlocked Camera at LOW) receive the wheel first. Only consume
        // an unclaimed vertical scroll, so vanilla cannot change the empty hotbar slot mid-drag.
        if (hasActiveDrag() && event.getScrollDeltaY() != 0) event.setCanceled(true);
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private static void reset() {
        STATES.clear();
        RagdollBridge.clearClientParts();
        clearInteraction(false);
        givingUp = false;
        suppressUseUntilRelease = false;
        dimension = null;
    }

    private static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (isDowned(mc.player)) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (!event.isUseItem()) return;
        if (activeTarget != null || suppressUseUntilRelease) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        Player target = pickBody();
        if (target == null) return;
        // Reserve downed-player interactions before Carry On/vanilla tries its own action.
        event.setCanceled(true);
        event.setSwingHand(false);
        if (CarryOnCompat.isCarrying(mc.player) || mc.player.isPassenger() || mc.player.isVehicle()) return;
        InteractionHand feedingHand = revivalHand(mc.player);
        if (feedingHand != null) {
            activeHand = feedingHand;
            activeAction = InputAction.FEED;
        } else if (mc.player.isShiftKeyDown() && mc.player.getMainHandItem().isEmpty()
                && mc.player.getOffhandItem().isEmpty()) {
            activeHand = InteractionHand.MAIN_HAND;
            activeAction = InputAction.DRAG;
        } else {
            return;
        }
        activeTarget = target.getUUID();
        suppressUseUntilRelease = true;
        RevivalNetwork.sendInput(activeTarget, activeHand, activeAction);
    }

    private static InteractionHand revivalHand(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).is(REVIVAL_ITEMS)) return hand;
        }
        return null;
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (dimension != null && dimension != mc.level.dimension()) {
            clearInteraction(true);
            STATES.clear();
            RagdollBridge.clearClientParts();
            givingUp = false;
        }
        dimension = mc.level.dimension();
        long now = System.nanoTime();
        STATES.entrySet().removeIf(entry -> {
            if (now - entry.getValue().receivedNanos <= 5_000_000_000L) return false;
            RagdollBridge.acceptClientParts(entry.getKey(), List.of());
            return true;
        });
        boolean inGame = mc.screen == null && mc.isWindowActive();
        boolean useHeld = inGame && mc.options.keyUse.isDown();
        if (!useHeld) suppressUseUntilRelease = false;

        boolean giveUpHeld = inGame && isDowned(mc.player) && GIVE_UP.isDown();
        if (giveUpHeld) {
            RevivalNetwork.sendInput(mc.player.getUUID(), InteractionHand.MAIN_HAND, InputAction.GIVE_UP);
        } else if (givingUp) {
            RevivalNetwork.sendInput(mc.player.getUUID(), InteractionHand.MAIN_HAND, InputAction.GIVE_UP_RELEASE);
        }
        givingUp = giveUpHeld;

        if (activeTarget == null) return;
        Player target = mc.level.getPlayerByUUID(activeTarget);
        boolean allowed = inGame && !isDowned(mc.player) && target != null && isDowned(target)
                && !CarryOnCompat.isCarrying(mc.player) && !mc.player.isPassenger() && !mc.player.isVehicle()
                && inReach(mc.player, target);
        if (activeAction == InputAction.FEED) {
            allowed &= useHeld && mc.player.getItemInHand(activeHand).is(REVIVAL_ITEMS);
        } else {
            allowed &= mc.player.isShiftKeyDown() && mc.player.getMainHandItem().isEmpty()
                    && mc.player.getOffhandItem().isEmpty();
        }
        if (!allowed) {
            clearInteraction(true);
            // Do not fall through into eating while still holding the same use press.
            suppressUseUntilRelease = useHeld;
        } else {
            RevivalNetwork.sendInput(activeTarget, activeHand, activeAction);
        }
    }

    private static void clearInteraction(boolean notifyServer) {
        if (notifyServer && activeTarget != null && Minecraft.getInstance().getConnection() != null) {
            RevivalNetwork.sendInput(activeTarget, activeHand, InputAction.RELEASE);
        }
        activeTarget = null;
        activeAction = null;
    }

    /** Select the rendered physics body along the existing camera, without changing any camera state. */
    private static Player pickBody() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return null;
        Vec3 origin = camera.getPosition();
        var look = camera.getLookVector();
        Vec3 direction = new Vec3(look.x(), look.y(), look.z()).normalize();
        double length = origin.distanceTo(RagdollBridge.worldEyePosition(mc.player))
                + mc.player.entityInteractionRange() + 2.0;
        Vec3 end = origin.add(direction.scale(length));
        var obstruction = mc.level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mc.player));
        var containing = Sable.HELPER.getContaining(mc.level, obstruction.getBlockPos());
        Vec3 obstructionPoint = containing instanceof ClientSubLevelAccess client
                ? client.renderPose().transformPosition(obstruction.getLocation())
                : Sable.HELPER.projectOutOfSubLevel(mc.level, obstruction.getLocation());
        double nearestDistance = obstruction.getType() == HitResult.Type.MISS
                ? length * length : origin.distanceToSqr(obstructionPoint);
        Player nearest = null;
        for (Player target : mc.level.players()) {
            if (target == mc.player || !isDowned(target) || !inReach(mc.player, target)) continue;
            var hit = RagdollBridge.raycastBody(target, origin, end);
            if (hit.isPresent()) {
                double distance = hit.get().distanceToSqr(origin);
                if (distance <= nearestDistance + 0.01) {
                    nearestDistance = distance;
                    nearest = target;
                }
            }
        }
        return nearest;
    }

    private static boolean inReach(Player rescuer, Player target) {
        Vec3 eye = RagdollBridge.worldEyePosition(rescuer);
        double reach = rescuer.entityInteractionRange();
        for (AABB box : RagdollBridge.worldBodyBoxes(target)) {
            Vec3 closest = new Vec3(Mth.clamp(eye.x, box.minX, box.maxX),
                    Mth.clamp(eye.y, box.minY, box.maxY), Mth.clamp(eye.z, box.minZ, box.maxZ));
            if (eye.distanceToSqr(closest) <= reach * reach) return true;
        }
        return false;
    }

    private static void render(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        GuiGraphics gui = event.getGuiGraphics();
        Snapshot own = STATES.get(mc.player.getUUID());
        int center = gui.guiWidth() / 2;
        int top = gui.guiHeight() - 105;
        if (own != null) {
            gui.drawCenteredString(mc.font, Component.translatable("hud.ragrevival.downed", time(own)), center, top, 0xFFFF8080);
            gui.drawCenteredString(mc.font, Component.translatable("hud.ragrevival.give_up_hint",
                    GIVE_UP.getTranslatedKeyMessage()), center, top + 12, 0xFFFFFFFF);
            if (own.payload.giveUpTicks() > 0) {
                progress(gui, center, top + 26, own.payload.giveUpTicks(), 100, 0xFFE27858,
                        Component.translatable("hud.ragrevival.giving_up"));
            } else if (own.payload.feedingTicks() > 0) {
                progress(gui, center, top + 26, own.payload.feedingTicks(), own.payload.feedingDuration(),
                        0xFF79D587, Component.translatable("hud.ragrevival.being_fed"));
            }
            return;
        }
        Player target = activeTarget == null ? pickBody() : mc.level.getPlayerByUUID(activeTarget);
        if (target == null) return;
        Snapshot targetState = STATES.get(target.getUUID());
        if (targetState == null) return;
        gui.drawCenteredString(mc.font, Component.translatable("hud.ragrevival.target",
                target.getDisplayName(), time(targetState)), center, top, 0xFFFFDC9D);
        if (targetState.payload.feedingTicks() > 0) {
            Component text = mc.player.getUUID().equals(targetState.payload.rescuer())
                    ? Component.translatable("hud.ragrevival.feeding")
                    : Component.translatable("hud.ragrevival.being_fed");
            progress(gui, center, top + 16, targetState.payload.feedingTicks(), targetState.payload.feedingDuration(),
                    0xFF79D587, text);
        } else {
            renderRescueHint(gui, center, top + 12);
        }
    }

    private static void renderRescueHint(GuiGraphics gui, int center, int top) {
        Minecraft mc = Minecraft.getInstance();
        Component keys = Component.empty();
        Component label;
        List<ItemStack> icons = List.of();
        int accent = 0xFFA5DAC0;
        InteractionHand hand = revivalHand(mc.player);
        if (activeAction == InputAction.DRAG) {
            keys = compactKey(mc.options.keyShift);
            label = Component.translatable("hud.ragrevival.release_drag_hint");
            accent = 0xFFE6C985;
        } else if (hand != null) {
            keys = compactKey(mc.options.keyUse);
            icons = List.of(mc.player.getItemInHand(hand));
            label = Component.translatable("hud.ragrevival.revive_hint");
        } else if (mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().isEmpty()) {
            keys = mc.player.isShiftKeyDown() ? compactKey(mc.options.keyUse)
                    : Component.translatable("hud.ragrevival.drag_keys", compactKey(mc.options.keyShift), compactKey(mc.options.keyUse));
            label = Component.translatable("hud.ragrevival.drag_hint");
            accent = 0xFFE6C985;
        } else {
            // Derive the suggested icons from the same tag, so datapack overrides stay accurate.
            icons = BuiltInRegistries.ITEM.getTag(REVIVAL_ITEMS)
                    .map(items -> items.stream().limit(2).map(item -> new ItemStack(item.value())).toList())
                    .orElse(List.of());
            label = Component.translatable("hud.ragrevival.equip_hint");
        }
        int keyTextWidth = mc.font.width(keys);
        int keyWidth = keyTextWidth == 0 ? 0 : keyTextWidth + 8;
        int width = 14 + icons.size() * 20 + (keyWidth == 0 ? 0 : keyWidth + 6) + mc.font.width(label);
        int left = center - width / 2;
        // A compact, softly outlined panel with a distinct keycap; the name/timer above stays unchanged.
        gui.fill(left + 1, top, left + width - 1, top + 22, 0xC9182028);
        gui.fill(left, top + 1, left + width, top + 21, 0xC9182028);
        gui.fill(left + 2, top + 21, left + width - 2, top + 22, (accent & 0x00FFFFFF) | 0x66000000);
        int x = left + 7;
        for (ItemStack icon : icons) {
            gui.renderItem(icon, x, top + 3);
            x += 20;
        }
        if (keyWidth > 0) {
            gui.fill(x, top + 4, x + keyWidth, top + 18, 0xFF35424D);
            gui.drawString(mc.font, keys, x + 4, top + 7, 0xFFF0F4F5, false);
            x += keyWidth + 6;
        }
        gui.drawString(mc.font, label, x, top + 7, accent, false);
    }

    private static Component compactKey(KeyMapping mapping) {
        var key = mapping.getKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            String translation = switch (key.getValue()) {
                case 0 -> "hud.ragrevival.mouse_left";
                case 1 -> "hud.ragrevival.mouse_right";
                case 2 -> "hud.ragrevival.mouse_middle";
                default -> null;
            };
            if (translation != null) return Component.translatable(translation);
        }
        return mapping.getTranslatedKeyMessage();
    }

    private static String time(Snapshot snapshot) {
        long elapsedMillis = snapshot.payload.feedingTicks() > 0 ? 0
                : (System.nanoTime() - snapshot.receivedNanos) / 1_000_000L;
        long seconds = Math.max(0, (snapshot.payload.remainingMillis() - elapsedMillis + 999) / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static void progress(GuiGraphics gui, int center, int top, int current, int duration, int color,
                                 Component label) {
        Minecraft mc = Minecraft.getInstance();
        int width = 160;
        gui.drawCenteredString(mc.font, label, center, top, 0xFFFFFFFF);
        gui.fill(center - width / 2, top + 12, center + width / 2, top + 18, 0xAA161616);
        int filled = Mth.clamp(Math.round((float) current / Math.max(1, duration) * width), 0, width);
        gui.fill(center - width / 2, top + 12, center - width / 2 + filled, top + 18, color);
    }

    private record Snapshot(StatePayload payload, long receivedNanos) {}
}
