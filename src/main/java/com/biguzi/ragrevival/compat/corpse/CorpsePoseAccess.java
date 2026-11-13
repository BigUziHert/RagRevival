package com.biguzi.ragrevival.compat.corpse;

import net.minecraft.nbt.CompoundTag;

/** A visual pose only: Corpse retains its own inventory, lifetime and death record. */
public interface CorpsePoseAccess {
    CompoundTag getRagRevivalPose();
    void setRagRevivalPose(CompoundTag pose);
}
