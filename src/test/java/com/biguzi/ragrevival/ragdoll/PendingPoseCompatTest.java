package com.biguzi.ragrevival.ragdoll;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PendingPoseCompatTest {
    @Test void olderRagdollsWithoutTheExtraIndexCanCancelRequests() {
        var remover = PendingPoseCompat.pendingPlayerRemover(OlderRequests.class);
        assertDoesNotThrow(() -> remover.accept(UUID.randomUUID()));
    }

    @Test void newerRagdollsClearsOnlyTheRequestedPlayerFromTheLiveIndex() {
        var remover = PendingPoseCompat.pendingPlayerRemover(NewerRequests.class);
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        NewerRequests.PENDING_PLAYERS.clear();
        // Requests can arrive after the remover is cached.
        NewerRequests.PENDING_PLAYERS.addAll(Set.of(target, other));
        remover.accept(target);
        assertEquals(Set.of(other), NewerRequests.PENDING_PLAYERS);
        assertDoesNotThrow(() -> remover.accept(target));
        NewerRequests.PENDING_PLAYERS.clear();
    }

    @Test void incompatibleExistingIndexDoesNotSilentlySkipCancellation() {
        assertThrows(IllegalStateException.class,
                () -> PendingPoseCompat.pendingPlayerRemover(IncompatibleRequests.class));
    }

    private static final class OlderRequests {}
    private static final class NewerRequests {
        private static final Set<UUID> PENDING_PLAYERS = new HashSet<>();
    }
    private static final class IncompatibleRequests {
        private static final Object PENDING_PLAYERS = new Object();
    }
}
