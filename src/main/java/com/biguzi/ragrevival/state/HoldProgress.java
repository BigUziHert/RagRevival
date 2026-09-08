package com.biguzi.ragrevival.state;

/** Server-tick hold accounting. Repeated packets cannot advance progress. */
public final class HoldProgress {
    public static final int MAX_SILENCE_TICKS = 6;
    private long lastHeartbeat;
    private long lastAdvanced;
    private int ticks;
    public HoldProgress(long tick) { lastHeartbeat = tick; lastAdvanced = tick; }
    public void heartbeat(long tick) { lastHeartbeat = tick; }
    public boolean advance(long tick) {
        if (tick - lastHeartbeat > MAX_SILENCE_TICKS || tick < lastAdvanced) return false;
        if (tick > lastAdvanced) { ticks++; lastAdvanced = tick; }
        return true;
    }
    public int ticks() { return ticks; }
}
