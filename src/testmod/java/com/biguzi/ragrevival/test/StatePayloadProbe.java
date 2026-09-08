package com.biguzi.ragrevival.test;

import com.biguzi.ragrevival.network.StatePayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Codec checks run inside the optional live test harness, never bundled in the production JAR. */
public final class StatePayloadProbe {
    private StatePayloadProbe() {}

    public static void run(BiConsumer<Boolean, String> check) {
        List<UUID> parts = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        StatePayload expected = new StatePayload(UUID.randomUUID(), 90_000, 8, 32, 12, UUID.randomUUID(), parts);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            StatePayload.CODEC.encode(buffer, expected);
            StatePayload actual = StatePayload.CODEC.decode(buffer);
            check.accept(expected.equals(actual) && buffer.readableBytes() == 0,
                    "state packet roundtrip preserves exact six-part topology");
        } finally { buffer.release(); }

        StatePayload clear = new StatePayload(UUID.randomUUID(), 0, 0, 32, 0, StatePayload.NONE);
        buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            StatePayload.CODEC.encode(buffer, clear);
            check.accept(StatePayload.CODEC.decode(buffer).bodyParts().isEmpty(),
                    "clear state packet carries no stale body topology");
        } finally { buffer.release(); }

        boolean rejectedAll = true;
        for (int count : new int[] {-1, 7, Integer.MAX_VALUE}) {
            buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                buffer.writeUUID(UUID.randomUUID()); buffer.writeLong(1000);
                buffer.writeVarInt(0); buffer.writeVarInt(32); buffer.writeVarInt(0);
                buffer.writeUUID(StatePayload.NONE); buffer.writeVarInt(count);
                try { StatePayload.CODEC.decode(buffer); rejectedAll = false; }
                catch (DecoderException expectedException) { /* Bounded before reading or allocating IDs. */ }
            } finally { buffer.release(); }
        }
        check.accept(rejectedAll, "state packet rejects negative and oversized topology counts");
    }
}
