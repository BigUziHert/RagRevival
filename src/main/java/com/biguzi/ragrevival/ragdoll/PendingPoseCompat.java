package com.biguzi.ragrevival.ragdoll;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Adapts the extra pose-request index introduced after Ragdolls 0.7.0. */
final class PendingPoseCompat {
    private PendingPoseCompat() {}

    static Consumer<UUID> pendingPlayerRemover(Class<?> requestsClass) {
        Field field;
        try {
            field = requestsClass.getDeclaredField("PENDING_PLAYERS");
        } catch (NoSuchFieldException absentInOlderRagdolls) {
            // 0.6.9 and 0.7.0 track requests only in PENDING, which the bridge still clears.
            return playerId -> {};
        }
        if (!Modifier.isStatic(field.getModifiers()) || !Set.class.isAssignableFrom(field.getType())) {
            throw new IllegalStateException("Unsupported Ragdolls PENDING_PLAYERS field: " + field);
        }
        try {
            field.setAccessible(true);
            if (!(field.get(null) instanceof Set<?> pendingPlayers)) {
                throw new IllegalStateException("Ragdolls PENDING_PLAYERS is not initialized");
            }
            // Resolve once; removals touch the actual upstream set, preserving unrelated players.
            return playerId -> pendingPlayers.remove(playerId);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access Ragdolls PENDING_PLAYERS", e);
        }
    }
}
