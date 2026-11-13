package com.biguzi.ragrevival;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class RevivalConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DOWNED_SECONDS;
    public static final ModConfigSpec.IntValue FEEDING_TICKS;
    public static final ModConfigSpec.DoubleValue RESTORED_HEALTH_FRACTION;
    public static final ModConfigSpec.IntValue MAX_CORPSE_RAGDOLLS;
    public static final ModConfigSpec.IntValue CORPSE_PHYSICS_TICKS;
    public static final ModConfigSpec.IntValue CORPSE_RAGDOLL_RANGE;
    static {
        var b = new ModConfigSpec.Builder();
        DOWNED_SECONDS = b.comment("Real seconds until death, excluding valid feeding. Time continues while offline and while the server is stopped.")
                .defineInRange("downedSeconds", 120, 1, 86400);
        FEEDING_TICKS = b.comment("Continuous server ticks of feeding (20 ticks = 1 second).")
                .defineInRange("feedingTicks", 32, 1, 1200);
        RESTORED_HEALTH_FRACTION = b.comment("Fraction of maximum health restored on revival. 0.5 = half health (5 hearts at the normal maximum).",
                        "Replaces the old fixed-point restoredHealth setting, which is no longer used.")
                .defineInRange("restoredHealthFraction", 0.5, 0.01, 1.0);
        MAX_CORPSE_RAGDOLLS = b.comment("Deprecated and ignored since 1.6.0. Loaded corpses use native Sable physics and automatic sleep; a cap would change their behavior.")
                .defineInRange("maxCorpseRagdolls", 8, 0, 64);
        CORPSE_PHYSICS_TICKS = b.comment("Deprecated and ignored since 1.6.0. Corpses no longer freeze after a timer.")
                .defineInRange("corpsePhysicsTicks", 200, 20, 1200);
        CORPSE_RAGDOLL_RANGE = b.comment("Deprecated and ignored since 1.6.0. Normal chunk ticking controls physics loading; no chunks are force-loaded.")
                .defineInRange("corpseRagdollRange", 48, 8, 128);
        SPEC = b.build();
    }
    private RevivalConfig() {}
}
