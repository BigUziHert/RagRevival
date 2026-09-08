package com.biguzi.ragrevival.state;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DownedClockTest {
    @Test void offlineTimeCountsAgainstPersistedDeadline() {
        long persisted = DownedClock.deadline(1_000_000, 120);
        assertEquals(120_000, DownedClock.remaining(persisted, 1_000_000));
        assertEquals(30_000, DownedClock.remaining(persisted, 1_090_000));
        assertEquals(0, DownedClock.remaining(persisted, 1_120_000));
        assertEquals(0, DownedClock.remaining(persisted, 2_000_000));
    }
    @Test void longConfigDoesNotOverflowIntegerMillis() {
        assertEquals(86_400_000L, DownedClock.deadline(0, 86_400));
    }
}
