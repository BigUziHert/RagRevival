package com.biguzi.ragrevival.client;

import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

/** Adds the vanilla through-wall silhouette to the real Sable limb geometry. */
public final class DownedOutline {
    private DownedOutline() {}

    public static MultiBufferSource wrap(RagdollPartBlockEntity part, MultiBufferSource original) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.levelRenderer.shouldShowEntityOutlines() || !RevivalClient.shouldOutline(part)) return original;

        // Sable draws its block entities before the vanilla outline flush. This NeoForge hook also
        // requests post-processing when no ordinary glowing entity was rendered during the frame.
        mc.levelRenderer.requestOutlineEffect();
        var outlines = mc.renderBuffers().outlineBufferSource();
        return type -> {
            var normal = original.getBuffer(type);
            var outlineType = type.outline();
            if (outlineType.isEmpty()) return normal;
            outlines.setColor(255, 196, 64, 255);
            // Request the outline-only type so the original model is emitted exactly once. Its
            // native render state ignores scene depth without changing the normal render pass.
            return VertexMultiConsumer.create(outlines.getBuffer(outlineType.get()), normal);
        };
    }
}
