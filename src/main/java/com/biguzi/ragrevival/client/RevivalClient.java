package com.biguzi.ragrevival.client;

import com.biguzi.ragrevival.FeedingAnimation;
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
        if (mc.player == null || activeTarget == null || activeAction == null) return false;
        Snapshot state = STATES.get(activeTarget);
        return state != null && System.nanoTime() - state.receivedNanos <= 5_000_000_000L;
    }

    /** Server-confirmed use state for other players; local intent also guards the first sync gap. */
    public static boolean isFeeding(Player player) {
        if (player == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.player && activeAction == InputAction.FEED && hasActiveInteraction()) return true;
        long now = System.nanoTime();
        return STATES.values().stream().anyMatch(state -> state.payload.feedingTicks() > 0
                && state.payload.rescuer().equals(player.getUUID()) && now - state.receivedNanos <= 5_000_000_000L);
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
            if (event.isUseItem() && activeTarget == null && !suppressUseUntilRelease) {
                InteractionHand hand = revivalHand(mc.player);
                if (hand != null) beginFeed(mc.player, hand);
            }
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
            beginFeed(target, feedingHand);
            return;
        } else if (mc.player.getMainHandItem().isEmpty()
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

    private static void beginFeed(Player target, InteractionHand hand) {
        activeTarget = target.getUUID();
        activeHand = hand;
        activeAction = InputAction.FEED;
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

        // Mounted Sable players can bypass vanilla's interaction callback; sample the held key too.
        if (activeTarget == null && useHeld && !suppressUseUntilRelease && isDowned(mc.player)) {
            InteractionHand hand = revivalHand(mc.player);
            if (hand != null) beginFeed(mc.player, hand);
        }
        if (activeTarget == null) return;
        Player target = mc.level.getPlayerByUUID(activeTarget);
        boolean selfFeed = target == mc.player && activeAction == InputAction.FEED;
        boolean allowed = inGame && target != null && isDowned(target) && mc.player.isAlive()
                && !mc.player.isSpectator() && !CarryOnCompat.isCarrying(mc.player) && !mc.player.isVehicle()
                && (selfFeed || !isDowned(mc.player) && !mc.player.isPassenger() && inReach(mc.player, target));
        if (activeAction == InputAction.FEED) {
            allowed &= useHeld
                    && mc.player.getItemInHand(activeHand).is(REVIVAL_ITEMS);
        } else {
            allowed &= useHeld && mc.player.getMainHandItem().isEmpty()
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
        if (activeAction == InputAction.FEED && Minecraft.getInstance().player != null) {
            FeedingAnimation.stop(Minecraft.getInstance().player);
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
            renderRescueHint(gui, center, top, own);
            return;
        }
        Player target = activeTarget == null ? pickBody() : mc.level.getPlayerByUUID(activeTarget);
        if (target == null) return;
        Snapshot targetState = STATES.get(target.getUUID());
        if (targetState == null) return;
        renderRescueHint(gui, center, top + 12, targetState);
    }

    private static void renderRescueHint(GuiGraphics gui, int center, int top, Snapshot targetState) {
        Minecraft mc = Minecraft.getInstance();
        Component keys = Component.empty();
        Component label;
        List<ItemStack> icons = List.of();
        int accent = 0xFFA5DAC0;
        InteractionHand hand = revivalHand(mc.player);
        boolean self = mc.player.getUUID().equals(targetState.payload.playerId());
        boolean feeding = targetState.payload.feedingTicks() > 0;
        if (feeding) {
            boolean ownFeed = mc.player.getUUID().equals(targetState.payload.rescuer());
            label = Component.translatable(ownFeed ? (self ? "hud.ragrevival.self_revive_hint"
                    : "hud.ragrevival.revive_hint") : "hud.ragrevival.being_fed");
            if (ownFeed) {
                keys = compactKey(mc.options.keyUse);
                if (hand != null) icons = List.of(mc.player.getItemInHand(hand));
            }
        } else if (activeAction == InputAction.DRAG) {
            keys = compactKey(mc.options.keyUse);
            label = Component.translatable("hud.ragrevival.drag_hint");
            accent = 0xFFE6C985;
        } else if (hand != null) {
            keys = compactKey(mc.options.keyUse);
            icons = List.of(mc.player.getItemInHand(hand));
            label = Component.translatable(self ? "hud.ragrevival.self_revive_hint" : "hud.ragrevival.revive_hint");
        } else if (!self && mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().isEmpty()) {
            keys = compactKey(mc.options.keyUse);
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
        String countdown = time(targetState);
        int labelWidth = mc.font.width(label);
        int contentWidth = icons.size() * 20 + (keyWidth == 0 ? 0 : keyWidth + 6)
                + labelWidth + 15 + mc.font.width(countdown);
        boolean givingUpNow = self && targetState.payload.giveUpTicks() > 0;
        Component giveUpKey = compactKey(GIVE_UP);
        Component giveUpLabel = Component.translatable("hud.ragrevival.give_up_hint");
        int giveUpKeyWidth = mc.font.width(giveUpKey) + 8;
        int giveUpWidth = self ? giveUpKeyWidth + 6 + mc.font.width(giveUpLabel) : 0;
        int width = 14 + Math.max(contentWidth, giveUpWidth);
        int height = self ? 42 : 22;
        int left = center - width / 2;
        // Both downed-player actions share the same card and bottom progress track.
        gui.fill(left + 1, top, left + width - 1, top + height, 0xC9182028);
        gui.fill(left, top + 1, left + width, top + height - 1, 0xC9182028);
        if (feeding || givingUpNow) {
            int barWidth = width - 2;
            int current = givingUpNow ? targetState.payload.giveUpTicks() : targetState.payload.feedingTicks();
            int duration = givingUpNow ? 100 : targetState.payload.feedingDuration();
            int filled = Mth.clamp(Math.round((float) current / Math.max(1, duration) * barWidth), 0, barWidth);
            gui.fill(left + 1, top + height - 2, left + width - 1, top + height, 0xFF33443C);
            gui.fill(left + 1, top + height - 2, left + 1 + filled, top + height, 0xFF79D587);
        } else {
            gui.fill(left + 2, top + height - 1, left + width - 2, top + height, (accent & 0x00FFFFFF) | 0x66000000);
        }
        int x = center - contentWidth / 2;
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
        x += labelWidth + 7;
        gui.fill(x, top + 6, x + 1, top + 16, 0xFF46515A);
        gui.drawString(mc.font, countdown, x + 8, top + 7, 0xFFE6C985, false);
        if (self) {
            gui.fill(left + 7, top + 22, left + width - 7, top + 23, 0x6646515A);
            int giveUpX = center - giveUpWidth / 2;
            gui.fill(giveUpX, top + 25, giveUpX + giveUpKeyWidth, top + 39, 0xFF35424D);
            gui.drawString(mc.font, giveUpKey, giveUpX + 4, top + 28, 0xFFF0F4F5, false);
            gui.drawString(mc.font, giveUpLabel, giveUpX + giveUpKeyWidth + 6, top + 28,
                    0xFFBEC8CE, false);
        }
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

    private record Snapshot(StatePayload payload, long receivedNanos) {}
}
