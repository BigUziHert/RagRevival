package com.biguzi.ragrevival.network;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StatePayload(UUID playerId, long remainingMillis, int feedingTicks, int feedingDuration,
                           int giveUpTicks, UUID rescuer, List<UUID> bodyParts) implements CustomPacketPayload {
    public StatePayload {
        bodyParts = List.copyOf(bodyParts);
        if (bodyParts.size() > 6) throw new IllegalArgumentException("A player ragdoll has at most six parts");
    }
    public StatePayload(UUID playerId, long remainingMillis, int feedingTicks, int feedingDuration,
                        int giveUpTicks, UUID rescuer) {
        this(playerId, remainingMillis, feedingTicks, feedingDuration, giveUpTicks, rescuer, List.of());
    }
    public static final UUID NONE = new UUID(0, 0);
    public static final Type<StatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("ragrevival", "state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.of(
            (b,p) -> { b.writeUUID(p.playerId); b.writeLong(p.remainingMillis); b.writeVarInt(p.feedingTicks);
                b.writeVarInt(p.feedingDuration); b.writeVarInt(p.giveUpTicks); b.writeUUID(p.rescuer);
                b.writeVarInt(p.bodyParts.size()); for (UUID part : p.bodyParts) b.writeUUID(part); },
            b -> new StatePayload(b.readUUID(), b.readLong(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUUID(), readParts(b)));
    private static List<UUID> readParts(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 6) throw new DecoderException("Invalid ragdoll part count: " + size);
        List<UUID> parts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) parts.add(buffer.readUUID());
        return parts;
    }
    @Override public Type<StatePayload> type() { return TYPE; }
}
