package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.client.RevivalClient;
import com.biguzi.ragrevival.network.InputAction;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.mob.block.entity.MobRagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollGrabClient;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in event-bus regression fixture. Exists only in the separately built test harness. */
public final class DragScrollProbe {
    private long nextPoll;
    private long waitingSince;

    public DragScrollProbe() {
        NeoForge.EVENT_BUS.addListener(this::render);
    }

    private void render(RenderGuiEvent.Post event) {
        long now = System.nanoTime();
        if (now < nextPoll) return;
        nextPoll = now + 500_000_000L;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Path request = mc.gameDirectory.toPath().resolve("ragrevival-scroll.request");
        if (!Files.exists(request)) { waitingSince = 0; return; }
        Path output = mc.gameDirectory.toPath().resolve("ragrevival-scroll.json");
        try {
            String requestedName = Files.readString(request).trim();
            String name = requestedName.isEmpty() ? "ReviveOne" : requestedName;
            Player target = mc.level.players().stream().filter(player ->
                    player.getGameProfile().getName().equals(name) || player.getUUID().toString().equals(name))
                    .findFirst().orElse(null);
            BlockPos part = target == null ? null : findDownedPart(mc, target);
            if (target == null || part == null || mc.screen != null || mc.player.isSpectator()
                    || RevivalClient.isDowned(mc.player) || !RevivalClient.isDowned(target)) {
                if (waitingSince == 0) waitingSince = now;
                if (now - waitingSince < 30_000_000_000L) return;
                throw new IllegalStateException("Need an in-game, conscious rescuer and synchronized downed body for "
                        + name + " within 30 seconds");
            }
            Map<String, Object> report = probe(mc, target, part);
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            Files.delete(request);
            waitingSince = 0;
            LogUtils.getLogger().info("RAGREVIVAL_SCROLL passed={}/{} output={}",
                    report.get("passed"), report.get("total"), output.toAbsolutePath());
        } catch (Exception failure) {
            LogUtils.getLogger().error("RAGREVIVAL_SCROLL failed", failure);
            try {
                Files.writeString(output, new GsonBuilder().setPrettyPrinting().create()
                        .toJson(Map.of("error", failure.toString())));
                Files.deleteIfExists(request);
            } catch (Exception ignored) {}
        }
    }

    private static Map<String, Object> probe(Minecraft mc, Player target, BlockPos downedPart) throws Exception {
        Class<?> camera = Class.forName("com.caleb.unlockedcamera.client.UnlockedCameraClient");
        Class<?> config = Class.forName("com.caleb.unlockedcamera.client.ClientConfig");
        Field cameraActive = field(camera, "active");
        Field cameraDistance = field(camera, "targetDistance");
        Field nativeActive = field(RagdollGrabClient.class, "activePos");
        Field nativeGrace = field(RagdollGrabClient.class, "collisionGraceTicks");
        Field revivalTarget = field(RevivalClient.class, "activeTarget");
        Field revivalAction = field(RevivalClient.class, "activeAction");
        @SuppressWarnings("unchecked")
        Map<Object, Object> states = (Map<Object, Object>) field(RevivalClient.class, "STATES").get(null);
        Object originalTargetSnapshot = states.get(target.getUUID());
        Method targetedPart = method(RagdollGrabClient.class, "targetedPart", Minecraft.class);
        Method nativeTick = method(RagdollGrabClient.class, "onClientTick", ClientTickEvent.Post.class);
        ModConfigSpec.DoubleValue minZoom = (ModConfigSpec.DoubleValue) field(config, "MIN_ZOOM").get(null);
        ModConfigSpec.DoubleValue maxZoom = (ModConfigSpec.DoubleValue) field(config, "MAX_ZOOM").get(null);
        Map<Field, Object> originals = new LinkedHashMap<>();
        for (Field fixture : List.of(cameraActive, cameraDistance, nativeActive, nativeGrace, revivalTarget, revivalAction)) {
            originals.put(fixture, fixture.get(null));
        }
        double originalMin = minZoom.get();
        double originalMax = maxZoom.get();
        HitResult originalHit = mc.hitResult;
        boolean originalUse = mc.options.keyUse.isDown();
        int originalSlot = mc.player.getInventory().selected;
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        report.put("scope", "Synchronous live-client fixture using a real synchronized downed limb, transformed native grab methods, and the actual NeoForge mouse-scroll event bus. Camera/input/private fixture state is restored before the next tick. The native-blocking baseline temporarily removes one client downed snapshot to classify the same physical limb as ordinary. If ordinary bodies exist, takeover checks emit native release packets for seeded local grips, never new grabs or rescue requests. Event cancellation tests the vanilla hotbar gate, not a physical wheel gesture.");
        report.put("rescuer", mc.player.getGameProfile().getName());
        report.put("target", target.getGameProfile().getName());
        report.put("checks", checks);
        report.put("deferred", deferred);
        try {
            minZoom.set(1.0);
            maxZoom.set(12.0);
            nativeActive.set(null, null);
            nativeGrace.setInt(null, 0);
            revivalTarget.set(null, null);
            revivalAction.set(null, null);
            mc.hitResult = hit(downedPart);
            check(checks, "downed_limb_is_exact_synchronized_part", RevivalClient.isDownedPart(downedPart));
            check(checks, "native_target_picker_excludes_downed_limb", targetedPart.invoke(null, mc) == null);

            // Reproduce upstream wheel ownership by temporarily treating the real limb as ordinary.
            // The fixed scroll hook will otherwise clear a recognized downed limb before NORMAL
            // cancellation. Restore the exact snapshot before testing the fixed downed behavior.
            nativeActive.set(null, downedPart);
            cameraActive.setBoolean(null, true);
            cameraDistance.setFloat(null, 4.0f);
            states.remove(target.getUUID());
            try {
                check(checks, "synthetic_ordinary_classification_keeps_native_target_picker", downedPart.equals(targetedPart.invoke(null, mc)));
                InputEvent.MouseScrollingEvent baseline = scroll(mc, 1.0, false);
                check(checks, "ordinary_native_grip_baseline_cancels_scroll", baseline.isCanceled());
                check(checks, "ordinary_native_grip_baseline_blocks_unlocked_camera_zoom", cameraDistance.getFloat(null) == 4.0f);
            } finally {
                states.put(target.getUUID(), originalTargetSnapshot);
            }
            InputEvent.MouseScrollingEvent firstFixedWheel = scroll(mc, 1.0, false);
            check(checks, "first_downed_scroll_clears_phantom_native_grip_before_next_tick", nativeActive.get(null) == null);
            check(checks, "first_downed_scroll_zooms_without_waiting_for_tick", firstFixedWheel.isCanceled() && cameraDistance.getFloat(null) < 4.0f);

            // Simulate the local native grip that predates a downing packet. The injected head must clear it
            // before keyUse=false reaches upstream stopGrab, avoiding an unwanted native release packet.
            nativeActive.set(null, downedPart);
            mc.options.keyUse.setDown(false);
            nativeTick.invoke(null, new ClientTickEvent.Post());
            check(checks, "downed_transition_clears_phantom_native_grip", nativeActive.get(null) == null);
            nativeGrace.setInt(null, 0);
            check(checks, "native_grab_state_inactive_without_drag_or_grace", !RagdollGrabClient.isGrabbing());

            revivalTarget.set(null, target.getUUID());
            revivalAction.set(null, InputAction.DRAG);
            check(checks, "revival_drag_owns_interaction", RevivalClient.hasActiveDrag() && RevivalClient.hasActiveInteraction());
            check(checks, "revival_drag_preserves_local_collision_suppression", RagdollGrabClient.isGrabbing());

            cameraActive.setBoolean(null, true);
            cameraDistance.setFloat(null, 4.0f);
            mc.options.keyUse.setDown(true);
            InputEvent.MouseScrollingEvent zoomIn = scroll(mc, 1.0, false);
            float afterIn = cameraDistance.getFloat(null);
            check(checks, "unlocked_camera_zoom_in_while_dragging_and_use_held", afterIn < 4.0f && afterIn > 1.0f);
            check(checks, "zoom_in_claims_scroll_before_hotbar", zoomIn.isCanceled());
            mc.options.keyUse.setDown(false);
            InputEvent.MouseScrollingEvent zoomOut = scroll(mc, -1.0, false);
            float afterOut = cameraDistance.getFloat(null);
            check(checks, "unlocked_camera_zoom_out_while_dragging_after_use_release", afterOut > afterIn && Math.abs(afterOut - 4.0f) < 0.001f);
            check(checks, "zoom_out_claims_scroll_before_hotbar", zoomOut.isCanceled());
            report.put("zoomDistances", List.of(4.0f, afterIn, afterOut));

            InputEvent.MouseScrollingEvent canceled = scroll(mc, 1.0, true);
            check(checks, "already_claimed_scroll_stays_canceled", canceled.isCanceled());
            check(checks, "already_claimed_scroll_does_not_zoom", cameraDistance.getFloat(null) == afterOut);
            cameraActive.setBoolean(null, false);
            InputEvent.MouseScrollingEvent fallback = scroll(mc, 1.0, false);
            check(checks, "camera_inactive_drag_still_blocks_hotbar_scroll", fallback.isCanceled());
            check(checks, "camera_inactive_drag_does_not_zoom", cameraDistance.getFloat(null) == afterOut);
            check(checks, "probe_keeps_selected_hotbar_slot", mc.player.getInventory().selected == originalSlot);
            InputEvent.MouseScrollingEvent horizontal = new InputEvent.MouseScrollingEvent(1.0, 0.0,
                    false, false, false, mc.mouseHandler.xpos(), mc.mouseHandler.ypos());
            NeoForge.EVENT_BUS.post(horizontal);
            check(checks, "horizontal_only_scroll_remains_unclaimed_while_dragging", !horizontal.isCanceled());

            revivalTarget.set(null, null);
            revivalAction.set(null, null);
            nativeActive.set(null, null);
            nativeGrace.setInt(null, 0);
            InputEvent.MouseScrollingEvent idle = scroll(mc, 1.0, false);
            check(checks, "idle_camera_inactive_scroll_remains_unclaimed", !idle.isCanceled());
            check(checks, "native_collision_suppression_ends_with_drag", !RagdollGrabClient.isGrabbing());

            BlockPos ordinary = findOrdinaryPart(mc, false);
            if (ordinary == null) {
                deferred.add("No ordinary player/dummy ragdoll loaded: native target selection, active-grip wheel cancellation, and existing-grip takeover for a separate ordinary body require another probe with one present. The baseline covers native wheel blocking with a temporary ordinary classification of the actual downed limb.");
            } else {
                probeOrdinary(mc, ordinary, target, nativeActive, nativeGrace, revivalTarget, revivalAction,
                        targetedPart, cameraActive, cameraDistance, checks, "ordinary_player");
            }
            BlockPos mob = findOrdinaryPart(mc, true);
            if (mob == null) {
                deferred.add("No ordinary mob ragdoll loaded: native mob grab preservation requires another probe with one present.");
            } else {
                probeOrdinary(mc, mob, target, nativeActive, nativeGrace, revivalTarget, revivalAction,
                        targetedPart, cameraActive, cameraDistance, checks, "ordinary_mob");
            }
        } finally {
            // Every touched value is restored even when reflection or an event listener throws.
            states.put(target.getUUID(), originalTargetSnapshot);
            for (Map.Entry<Field, Object> entry : originals.entrySet()) entry.getKey().set(null, entry.getValue());
            minZoom.set(originalMin);
            maxZoom.set(originalMax);
            mc.hitResult = originalHit;
            mc.options.keyUse.setDown(originalUse);
            mc.player.getInventory().selected = originalSlot;
        }
        report.put("fixtureRestored", true);
        report.put("passed", checks.stream().filter(check -> Boolean.TRUE.equals(check.get("passed"))).count());
        report.put("total", checks.size());
        report.put("allPassed", checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed"))));
        return report;
    }

    private static void probeOrdinary(Minecraft mc, BlockPos part, Player target, Field nativeActive, Field nativeGrace,
                                      Field revivalTarget, Field revivalAction, Method targetedPart, Field cameraActive,
                                      Field cameraDistance, List<Map<String, Object>> checks, String label) throws Exception {
        nativeActive.set(null, null);
        nativeGrace.setInt(null, 0);
        revivalTarget.set(null, null);
        revivalAction.set(null, null);
        mc.hitResult = hit(part);
        check(checks, label + "_native_target_selection_preserved", part.equals(targetedPart.invoke(null, mc)));
        nativeActive.set(null, part);
        check(checks, label + "_native_grab_state_preserved", RagdollGrabClient.isGrabbing());
        cameraActive.setBoolean(null, true);
        cameraDistance.setFloat(null, 4.0f);
        InputEvent.MouseScrollingEvent nativeClaim = scroll(mc, 1.0, false);
        check(checks, label + "_native_scroll_cancellation_preserved", nativeClaim.isCanceled() && cameraDistance.getFloat(null) == 4.0f);
        revivalTarget.set(null, target.getUUID());
        revivalAction.set(null, InputAction.DRAG);
        InputEvent.MouseScrollingEvent takeover = scroll(mc, 1.0, false);
        check(checks, label + "_existing_grip_yields_to_rescue_on_first_scroll", nativeActive.get(null) == null);
        check(checks, label + "_existing_grip_takeover_allows_camera_zoom", takeover.isCanceled() && cameraDistance.getFloat(null) < 4.0f);
        check(checks, label + "_cannot_start_second_native_grip_during_drag", targetedPart.invoke(null, mc) == null);
        revivalAction.set(null, InputAction.FEED);
        check(checks, label + "_cannot_start_native_grip_during_feeding", targetedPart.invoke(null, mc) == null);
    }

    private static InputEvent.MouseScrollingEvent scroll(Minecraft mc, double vertical, boolean alreadyCanceled) {
        InputEvent.MouseScrollingEvent event = new InputEvent.MouseScrollingEvent(0.0, vertical,
                false, mc.options.keyUse.isDown(), false, mc.mouseHandler.xpos(), mc.mouseHandler.ypos());
        event.setCanceled(alreadyCanceled);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }

    private static BlockPos findDownedPart(Minecraft mc, Player target) {
        var container = SubLevelContainer.getContainer(mc.level);
        if (container == null) return null;
        for (var sublevel : container.getAllSubLevels()) {
            if (sublevel == null || sublevel.isRemoved() || sublevel.getPlot() == null) continue;
            BlockPos pos = sublevel.getPlot().getCenterBlock();
            if (mc.level.getBlockEntity(pos) instanceof RagdollPartBlockEntity part
                    && target.getUUID().equals(part.skinProfile().getId()) && RevivalClient.isDownedPart(part)) return pos;
        }
        return null;
    }

    private static BlockPos findOrdinaryPart(Minecraft mc, boolean mob) {
        var container = SubLevelContainer.getContainer(mc.level);
        if (container == null) return null;
        for (var sublevel : container.getAllSubLevels()) {
            if (sublevel == null || sublevel.isRemoved() || sublevel.getPlot() == null) continue;
            BlockPos pos = sublevel.getPlot().getCenterBlock();
            BlockEntity blockEntity = mc.level.getBlockEntity(pos);
            if (mob && blockEntity instanceof MobRagdollPartBlockEntity) return pos;
            if (!mob && blockEntity instanceof RagdollPartBlockEntity part && !RevivalClient.isDownedPart(part)) return pos;
        }
        return null;
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method result = owner.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static void check(List<Map<String, Object>> checks, String name, boolean passed) {
        checks.add(Map.of("name", name, "passed", passed));
    }
}
