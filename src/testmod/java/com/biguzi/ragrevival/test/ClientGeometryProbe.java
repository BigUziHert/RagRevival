package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.client.RevivalClient;
import com.biguzi.ragrevival.network.StatePayload;
import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in, read-only live-client geometry probe. Excluded from the distributable mod. */
@Mod(value = "ragrevival_tests", dist = Dist.CLIENT)
public final class ClientGeometryProbe {
    private long nextPoll;
    private long waitingSince;

    public ClientGeometryProbe() {
        NeoForge.EVENT_BUS.addListener(this::render);
        new DragScrollProbe();
    }

    private void render(RenderGuiEvent.Post event) {
        long now = System.nanoTime();
        if (now < nextPoll) return;
        nextPoll = now + 500_000_000L;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        captureVisual(mc, event.getGuiGraphics());
        Path request = mc.gameDirectory.toPath().resolve("ragrevival-geometry.request");
        if (!Files.exists(request)) { waitingSince = 0; return; }
        try {
            String name = Files.readString(request).trim();
            if (name.isEmpty()) name = "ReviveOne";
            String targetName = name;
            Player target = mc.level.players().stream().filter(player ->
                    player.getGameProfile().getName().equals(targetName)
                            || player.getUUID().toString().equals(targetName)).findFirst().orElse(null);
            if (target == null || !RevivalClient.isDowned(target) || RagdollBridge.worldBodyBoxes(target).isEmpty()) {
                if (waitingSince == 0) waitingSince = now;
                if (now - waitingSince < 30_000_000_000L) return;
                throw new IllegalStateException("No synchronized downed body for " + name + " within 30 seconds");
            }
            Map<String, Object> report = probe(mc, target);
            Path output = mc.gameDirectory.toPath().resolve("ragrevival-geometry.json");
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            Files.delete(request);
            waitingSince = 0;
            Screenshot.grab(mc.gameDirectory, "ragrevival-geometry.png", mc.getMainRenderTarget(),
                    component -> LogUtils.getLogger().info("RAGREVIVAL_GEOMETRY screenshot: {}", component.getString()));
            LogUtils.getLogger().info("RAGREVIVAL_GEOMETRY {}", output.toAbsolutePath());
        } catch (Exception failure) {
            LogUtils.getLogger().error("RAGREVIVAL_GEOMETRY failed", failure);
            try {
                Files.writeString(mc.gameDirectory.toPath().resolve("ragrevival-geometry.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("error", failure.toString())));
                Files.deleteIfExists(request);
            } catch (Exception ignored) {}
        }
    }

    /** Capture the real framebuffer without requiring a downed target or changing the camera. */
    private static void captureVisual(Minecraft mc, GuiGraphics gui) {
        Path request = mc.gameDirectory.toPath().resolve("ragrevival-visual.request");
        if (!Files.exists(request)) return;
        try {
            String label = Files.readString(request).trim().replaceAll("[^a-zA-Z0-9_-]", "_");
            if (label.isEmpty()) label = "capture";
            Files.delete(request);
            if (label.equals("rescue-hud")) renderRescuePreview(mc, gui);
            gui.flush();
            Screenshot.grab(mc.gameDirectory, "ragrevival-" + label + ".png", mc.getMainRenderTarget(),
                    component -> LogUtils.getLogger().info("RAGREVIVAL_VISUAL screenshot: {}", component.getString()));
        } catch (Exception failure) {
            LogUtils.getLogger().error("RAGREVIVAL_VISUAL failed", failure);
        }
    }

    /** Draw the production panel at known progress values without changing input or game state. */
    private static void renderRescuePreview(Minecraft mc, GuiGraphics gui) throws ReflectiveOperationException {
        Class<?> snapshotType = Class.forName("com.biguzi.ragrevival.client.RevivalClient$Snapshot");
        var constructor = snapshotType.getDeclaredConstructor(StatePayload.class, long.class);
        constructor.setAccessible(true);
        Method render = RevivalClient.class.getDeclaredMethod("renderRescueHint",
                GuiGraphics.class, int.class, int.class, snapshotType);
        render.setAccessible(true);
        int center = gui.guiWidth() / 2;
        gui.fill(center - 145, 28, center + 145, 282, 0xDF111820);
        gui.drawCenteredString(mc.font, "Rescue HUD preview (synthetic progress)", center, 36, 0xFFFFFFFF);
        int[] ticks = {0, 8, 16, 32, 16};
        String[] labels = {"Ready", "Reviving 25%", "Reviving 50%", "Reviving 100%", "Another rescuer"};
        for (int i = 0; i < ticks.length; i++) {
            int top = 57 + i * 44;
            gui.drawCenteredString(mc.font, labels[i], center, top, 0xFFAAB7C4);
            StatePayload payload = new StatePayload(StatePayload.NONE, 54_000, ticks[i], 32, 0,
                    i == 4 ? StatePayload.NONE : mc.player.getUUID());
            Object snapshot = constructor.newInstance(payload, System.nanoTime());
            render.invoke(null, gui, center, top + 12, snapshot);
        }
    }

    private static Map<String, Object> probe(Minecraft mc, Player target) throws ReflectiveOperationException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "Read-only rays against a live synchronized Sable ragdoll; synthetic offsets do not validate Unlocked Camera rendering or held mouse input.");
        report.put("player", mc.player.getGameProfile().getName());
        report.put("target", target.getGameProfile().getName());
        report.put("targetDowned", RevivalClient.isDowned(target));
        List<AABB> boxes = RagdollBridge.worldBodyBoxes(target);
        Vec3 eye = RagdollBridge.worldEyePosition(mc.player);
        AABB nearestBox = boxes.stream().min(Comparator.comparingDouble(box -> box.getCenter().distanceToSqr(eye))).orElseThrow();
        Vec3 aim = nearestBox.getCenter();
        Vec3 forward = new Vec3(aim.x - eye.x, 0, aim.z - eye.z).normalize();
        if (forward.lengthSqr() < 0.001) forward = new Vec3(0, 0, 1);
        Vec3 side = new Vec3(-forward.z, 0, forward.x);
        List<Vec3> origins = List.of(eye,
                eye.subtract(forward.scale(3)).add(side.scale(0.75)),
                eye.subtract(forward.scale(3)).subtract(side.scale(0.75)),
                eye.subtract(forward.scale(3)).add(0, 1, 0));
        String[] names = {"actual player eye", "synthetic left shoulder", "synthetic right shoulder", "synthetic raised camera"};
        List<Map<String, Object>> rays = new ArrayList<>();
        int hitCount = 0;
        for (int index = 0; index < origins.size(); index++) {
            Vec3 origin = origins.get(index);
            Vec3 end = aim.add(aim.subtract(origin).normalize().scale(0.5));
            var hit = RagdollBridge.raycastBody(target, origin, end);
            Map<String, Object> ray = new LinkedHashMap<>();
            ray.put("name", names[index]);
            ray.put("origin", vector(origin));
            ray.put("aim", vector(aim));
            ray.put("intersectsRealLimb", hit.isPresent());
            if (hit.isPresent()) {
                hitCount++;
                ray.put("hit", vector(hit.get()));
                ray.put("distanceFromPlayerEye", eye.distanceTo(hit.get()));
                var obstruction = mc.level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, mc.player));
                var containing = Sable.HELPER.getContaining(mc.level, obstruction.getBlockPos());
                Vec3 obstructionPoint = containing instanceof ClientSubLevelAccess client
                        ? client.renderPose().transformPosition(obstruction.getLocation())
                        : Sable.HELPER.projectOutOfSubLevel(mc.level, obstruction.getLocation());
                ray.put("notBehindBlockingSurface", obstruction.getType() == HitResult.Type.MISS
                        || origin.distanceToSqr(hit.get()) <= origin.distanceToSqr(obstructionPoint) + 0.01);
            }
            rays.add(ray);
        }
        report.put("limbBoxCount", boxes.size());
        report.put("playerWorldEye", vector(eye));
        report.put("entityInteractionRange", mc.player.entityInteractionRange());
        report.put("syntheticRays", rays);
        report.put("allSyntheticRaysHit", hitCount == origins.size());
        var camera = mc.gameRenderer.getMainCamera();
        report.put("actualRenderedCameraPosition", vector(camera.getPosition()));
        var look = camera.getLookVector();
        report.put("actualRenderedCameraLook", List.of(look.x(), look.y(), look.z()));
        report.put("vanillaHitResult", mc.hitResult == null ? "none" : mc.hitResult.getType().name());
        // Read the actual production picker without changing the camera, player rotation or input state.
        Method pick = RevivalClient.class.getDeclaredMethod("pickBody");
        pick.setAccessible(true);
        Player actualTarget = (Player) pick.invoke(null);
        report.put("actualCameraPickerTarget", actualTarget == null ? "none" : actualTarget.getGameProfile().getName());
        return report;
    }

    private static List<Double> vector(Vec3 value) {
        return List.of(value.x, value.y, value.z);
    }
}
