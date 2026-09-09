package com.biguzi.ragrevival.network;

import java.util.UUID;
import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.client.RevivalClient;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class RevivalNetwork {
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playToServer(InputPayload.TYPE, InputPayload.CODEC, (p,c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) DownedManager.input(player, p);
        }));
        registrar.playToClient(StatePayload.TYPE, StatePayload.CODEC,
                (p,c) -> c.enqueueWork(() -> RevivalClient.accept(p)));
    }
    public static void sendInput(UUID target, InteractionHand hand, InputAction action) {
        PacketDistributor.sendToServer(new InputPayload(target, hand, action));
    }
    private RevivalNetwork() {}
}
