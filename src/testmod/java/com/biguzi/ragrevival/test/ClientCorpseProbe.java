package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.google.gson.GsonBuilder;
import de.maxhenkel.corpse.entities.CorpseEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollGrabClient;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollGrabState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Explicit local diagnostic requests only; never included in the release JAR. */
final class ClientCorpseProbe {
    private final ArrayList<Map<String, Object>> trace = new ArrayList<>();
    private int traceRemaining;
    private int holdRemaining;
    private boolean injectedHold;

    ClientCorpseProbe() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::holdInput);
    }

    /** Explicit, bounded test input only; still exercises native targeting, client tick, and networking. */
    private void holdInput(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        Path request = mc.gameDirectory.toPath().resolve("ragrevival-corpse-hold.request");
        try {
            if (Files.exists(request)) {
                holdRemaining = Math.clamp(Integer.parseInt(Files.readString(request).trim()), 1, 200);
                Files.delete(request);
                // Include the press edge used by vanilla item/entity interaction, as well as
                // the held state that native Sable grabbing reads on subsequent ticks.
                net.minecraft.client.KeyMapping.click(mc.options.keyUse.getKey());
            }
            if (holdRemaining > 0 && mc.player != null && mc.level != null && mc.screen == null) {
                mc.options.keyUse.setDown(true);
                injectedHold = true;
                holdRemaining--;
            } else if (injectedHold || holdRemaining > 0) {
                mc.options.keyUse.setDown(false);
                injectedHold = false;
                holdRemaining = 0;
            }
        } catch (Exception error) {
            mc.options.keyUse.setDown(false);
            injectedHold = false;
            holdRemaining = 0;
            throw new IllegalStateException("Explicit client hold test failed", error);
        }
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null && ModList.get().isLoaded("corpse")) menuRequest(mc);
        if (mc.level != null && mc.player != null && ModList.get().isLoaded("corpse")) trace(mc);
        Path request = mc.gameDirectory.toPath().resolve("ragrevival-corpse-inspect.request");
        if (mc.level == null || mc.player == null || !ModList.get().isLoaded("corpse") || !Files.exists(request)) return;
        try {
            double distance = mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) + 2D;
            var search = mc.player.getBoundingBox().inflate(distance);
            var nearby = mc.level.getEntitiesOfClass(CorpseEntity.class, search);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("playerPosition", mc.player.position().toString());
            report.put("playerBox", mc.player.getBoundingBox().toString());
            report.put("searchBox", search.toString());
            report.put("screen", mc.screen == null ? "none" : mc.screen.getClass().getName());
            report.put("useDown", mc.options.keyUse.isDown());
            report.put("shiftDown", mc.player.isShiftKeyDown());
            report.put("mainHand", mc.player.getMainHandItem().toString());
            report.put("selectedSlot", mc.player.getInventory().selected);
            report.put("hitResult", hit(mc));
            report.put("yaw", mc.player.getYRot());
            report.put("pitch", mc.player.getXRot());
            var eye = com.biguzi.ragrevival.ragdoll.RagdollBridge.worldEyePosition(mc.player);
            var traceHit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye,
                    eye.add(mc.player.getLookAngle().scale(mc.player.blockInteractionRange())),
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
            report.put("fallbackBlockTrace", traceHit.getType() + " " + traceHit.getBlockPos()
                    + " " + mc.level.getBlockState(traceHit.getBlockPos()));
            var active = RagdollGrabClient.class.getDeclaredField("activePos");
            active.setAccessible(true);
            report.put("nativeActivePos", String.valueOf(active.get(null)));
            report.put("nativeServerGrabState", RagdollGrabState.isGrabbing(mc.player.getUUID()));
            var entities = new ArrayList<Map<String, Object>>();
            for (var entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof CorpseEntity corpse)) continue;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", corpse.getId());
                data.put("uuid", corpse.getUUID());
                data.put("position", corpse.position().toString());
                data.put("box", corpse.getBoundingBox().toString());
                data.put("distance", corpse.distanceTo(mc.player));
                data.put("inNativeSearch", nearby.contains(corpse));
                data.put("active", ((CorpsePoseAccess) corpse).getRagRevivalPose().getBoolean("active"));
                entities.add(data);
            }
            report.put("corpses", entities);
            var limbs = new ArrayList<Map<String, Object>>();
            var container = SubLevelContainer.getContainer(mc.level);
            if (container != null) for (var part : container.getAllSubLevels()) {
                if (part.getPlot() == null) continue;
                var block = mc.level.getBlockEntity(part.getPlot().getCenterBlock());
                if (block instanceof RagdollPartBlockEntity body) limbs.add(Map.of(
                        "id", part.getUniqueId(), "part", body.bodyPart().name(),
                        "center", part.logicalPose().transformPosition(net.minecraft.world.phys.Vec3.atCenterOf(body.getBlockPos())).toString(),
                        "corpseMarker", body.getPersistentData().getBoolean(CorpsePose.PART_MARKER)));
            }
            report.put("nativeLimbs", limbs);
            Files.writeString(mc.gameDirectory.toPath().resolve("ragrevival-corpse-inspect.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report));
            Files.delete(request);
        } catch (Exception error) { throw new IllegalStateException("Client corpse probe failed", error); }
    }

    /** Explicit requests exercise Corpse's own GUI packets, never mutate inventory directly. */
    private void menuRequest(Minecraft mc) {
        Path request = mc.gameDirectory.toPath().resolve("ragrevival-corpse-menu.request");
        if (!Files.exists(request)) return;
        try {
            String action = Files.readString(request).trim();
            Files.delete(request);
            String menu = mc.player.containerMenu.getClass().getName();
            if (!(mc.player.containerMenu instanceof de.maxhenkel.corpse.gui.ITransferrable))
                throw new IllegalStateException("Expected a native Corpse menu, got " + menu);
            switch (action) {
                case "transfer" -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new de.maxhenkel.corpse.net.MessageTransferItems());
                case "additional" -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new de.maxhenkel.corpse.net.MessageOpenAdditionalItems());
                case "close" -> mc.player.closeContainer();
                default -> throw new IllegalArgumentException("Unknown explicit Corpse menu request: " + action);
            }
            Files.writeString(mc.gameDirectory.toPath().resolve("ragrevival-corpse-menu.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                            "action", action, "menu", menu, "tick", mc.level.getGameTime(),
                            "scope", "Explicit test-only native GUI packet; inventory is handled by Corpse on the server.")));
        } catch (Exception error) {
            throw new IllegalStateException("Explicit native Corpse menu test failed", error);
        }
    }

    /** Captures real input and native network state over time; never changes either. */
    private void trace(Minecraft mc) {
        Path request = mc.gameDirectory.toPath().resolve("ragrevival-corpse-trace.request");
        try {
            if (Files.exists(request)) {
                String ticks = Files.readString(request).trim();
                traceRemaining = ticks.isEmpty() ? 200 : Math.clamp(Integer.parseInt(ticks), 1, 400);
                trace.clear();
                Files.delete(request);
            }
            if (traceRemaining == 0) return;
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("tick", mc.level.getGameTime());
            sample.put("useDown", mc.options.keyUse.isDown());
            sample.put("shiftDown", mc.player.isShiftKeyDown());
            sample.put("screen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
            sample.put("selectedSlot", mc.player.getInventory().selected);
            sample.put("testOnlyHeldInput", injectedHold);
            sample.put("hitResult", hit(mc));
            var active = RagdollGrabClient.class.getDeclaredField("activePos");
            active.setAccessible(true);
            sample.put("nativeActivePos", String.valueOf(active.get(null)));
            sample.put("nativeServerGrabState", RagdollGrabState.isGrabbing(mc.player.getUUID()));
            sample.put("playerPosition", mc.player.position().toString());
            var bodies = new ArrayList<Map<String, Object>>();
            for (var entity : mc.level.entitiesForRendering()) if (entity instanceof CorpseEntity corpse)
                bodies.add(Map.of("uuid", corpse.getUUID(), "position", corpse.position().toString()));
            sample.put("corpses", bodies);
            trace.add(sample);
            if (--traceRemaining == 0) Files.writeString(mc.gameDirectory.toPath().resolve("ragrevival-corpse-trace.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                            "scope", "Read-only native client trace; testOnlyHeldInput identifies the explicit bounded Use-key test hold. Grips use normal native targeting and network packets.",
                            "samples", trace)));
        } catch (Exception error) { throw new IllegalStateException("Client corpse input trace failed", error); }
    }

    private static String hit(Minecraft mc) {
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult block)
            return block.getType() + " block=" + block.getBlockPos() + " location=" + block.getLocation()
                    + " state=" + mc.level.getBlockState(block.getBlockPos());
        if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult entity)
            return "entity=" + entity.getEntity().getType() + " id=" + entity.getEntity().getId()
                    + " location=" + entity.getLocation();
        return "none";
    }
}
