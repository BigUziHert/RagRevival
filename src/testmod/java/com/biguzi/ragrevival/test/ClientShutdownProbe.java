package com.biguzi.ragrevival.test;

import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Explicit test-only shutdown request, so unattended client checks can close cleanly. */
@EventBusSubscriber(modid = "ragrevival_tests", value = Dist.CLIENT)
public final class ClientShutdownProbe {
    private static long nextPoll;

    private ClientShutdownProbe() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        long now = System.nanoTime();
        if (now < nextPoll) return;
        nextPoll = now + 500_000_000L;
        Minecraft client = Minecraft.getInstance();
        var request = client.gameDirectory.toPath().resolve("ragrevival-client-exit.request");
        if (!Files.exists(request)) return;
        try {
            Files.delete(request);
            Files.writeString(client.gameDirectory.toPath().resolve("ragrevival-client-exit.txt"),
                    "Explicit isolated test client shutdown requested.\n");
            client.stop();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not process test client shutdown request", failure);
        }
    }
}
