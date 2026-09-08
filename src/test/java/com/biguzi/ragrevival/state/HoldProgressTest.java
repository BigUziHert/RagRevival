package com.biguzi.ragrevival.state;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HoldProgressTest {
    @Test void packetSpamCannotAdvanceTheClock() {
        var hold = new HoldProgress(100);
        for (int i = 0; i < 1000; i++) { hold.heartbeat(100); assertTrue(hold.advance(100)); }
        assertEquals(0, hold.ticks());
        assertTrue(hold.advance(101));
        assertEquals(1, hold.ticks());
    }
    @Test void disconnectOrLostReleaseExpiresTheLease() {
        var hold = new HoldProgress(20);
        for (int tick = 21; tick <= 26; tick++) assertTrue(hold.advance(tick));
        assertFalse(hold.advance(27));
        assertEquals(6, hold.ticks());
    }
    @Test void freshHoldAfterReleaseStartsAtZero() {
        var first = new HoldProgress(0);
        for (int tick = 1; tick < 100; tick++) { first.heartbeat(tick); first.advance(tick); }
        assertEquals(99, first.ticks());
        var next = new HoldProgress(100);
        next.heartbeat(101); next.advance(101);
        assertEquals(1, next.ticks());
    }
    @Test void serverStallCannotCompleteAHoldInOneTick() {
        var hold = new HoldProgress(1);
        hold.heartbeat(100);
        assertTrue(hold.advance(100));
        assertEquals(1, hold.ticks());
    }
}
