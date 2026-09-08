package com.biguzi.ragrevival;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class RevivalConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DOWNED_SECONDS;
    public static final ModConfigSpec.IntValue FEEDING_TICKS;
    public static final ModConfigSpec.DoubleValue RESTORED_HEALTH;
    static {
        var b = new ModConfigSpec.Builder();
        DOWNED_SECONDS = b.comment("Real seconds until death. Time continues while offline and while the server is stopped.")
                .defineInRange("downedSeconds", 120, 1, 86400);
        FEEDING_TICKS = b.comment("Continuous server ticks of feeding (20 ticks = 1 second).")
                .defineInRange("feedingTicks", 32, 1, 1200);
        RESTORED_HEALTH = b.comment("Health points restored on revival; 2 points = 1 heart, capped by max health.")
                .defineInRange("restoredHealth", 6.0, 1.0, 1024.0);
        SPEC = b.build();
    }
    private RevivalConfig() {}
}
