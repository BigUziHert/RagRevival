package com.biguzi.ragrevival.state;

/** A persisted absolute deadline cannot be reset by logout or a normal restart. */
public final class DownedClock {
    public static long deadline(long nowMillis, int seconds) { return Math.addExact(nowMillis, seconds * 1000L); }
    public static long remaining(long deadline, long nowMillis) { return Math.max(0, deadline - nowMillis); }
    private DownedClock() {}
}
