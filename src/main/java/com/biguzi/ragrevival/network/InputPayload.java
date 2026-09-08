package com.biguzi.ragrevival.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

public record InputPayload(UUID target, InteractionHand hand, InputAction action) implements CustomPacketPayload {
    public static final Type<InputPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("ragrevival", "input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, InputPayload> CODEC = StreamCodec.of(
            (b,p) -> { b.writeUUID(p.target); b.writeEnum(p.hand); b.writeEnum(p.action); },
            b -> new InputPayload(b.readUUID(), b.readEnum(InteractionHand.class), b.readEnum(InputAction.class)));
    @Override public Type<InputPayload> type() { return TYPE; }
}
