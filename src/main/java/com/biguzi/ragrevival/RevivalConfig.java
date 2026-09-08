package com.biguzi.ragrevival;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class RevivalConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DOWNED_SECONDS;
    public static final ModConfigSpec.IntValue FEEDING_TICKS;
    public static final ModConfigSpec.DoubleValue RESTORED_HEALTH_FRACTION;
    static {
        var b = new ModConfigSpec.Builder();
        DOWNED_SECONDS = b.comment("Real seconds until death. Time continues while offline and while the server is stopped.")
                .defineInRange("downedSeconds", 120, 1, 86400);
        FEEDING_TICKS = b.comment("Continuous server ticks of feeding (20 ticks = 1 second).")
                .defineInRange("feedingTicks", 32, 1, 1200);
        RESTORED_HEALTH_FRACTION = b.comment("Fraction of maximum health restored on revival. 0.5 = half health (5 hearts at the normal maximum).",
                        "Replaces the old fixed-point restoredHealth setting, which is no longer used.")
                .defineInRange("restoredHealthFraction", 0.5, 0.01, 1.0);
        SPEC = b.build();
    }
    private RevivalConfig() {}
}
