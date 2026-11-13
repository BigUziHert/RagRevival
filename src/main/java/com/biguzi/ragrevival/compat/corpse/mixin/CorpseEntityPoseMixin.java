package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import com.biguzi.ragrevival.compat.corpse.CorpseCompat;
import de.maxhenkel.corpse.entities.CorpseBoundingBoxBase;
import de.maxhenkel.corpse.entities.CorpseEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.NonNullList;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = CorpseEntity.class, remap = false)
public abstract class CorpseEntityPoseMixin extends CorpseBoundingBoxBase implements CorpsePoseAccess {
    @Unique private static final String ragrevival$POSE_KEY = "ragrevival:corpse_pose";
    @Unique private static final String ragrevival$EQUIPMENT_KEY = "ragrevival:corpse_equipment";
    @Unique private static final EntityDataAccessor<CompoundTag> ragrevival$POSE =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.COMPOUND_TAG);

    protected CorpseEntityPoseMixin(EntityType<?> type, Level level) { super(type, level); }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lde/maxhenkel/corpse/entities/CorpseEntity;isNoGravity()Z"))
    private boolean ragrevival$usePhysicsMotion(CorpseEntity corpse) {
        // Sable already supplies the authoritative transform. Applying Corpse's gravity
        // again lets a client body drift through its own limbs and out of GUI lookup range.
        // Keep baseTick, aging, skeleton conversion and native despawn behavior running.
        return corpse.isNoGravity() || (corpse.level().isClientSide()
                ? getRagRevivalPose().getBoolean("active") : CorpseCompat.controlsMotion(corpse));
    }

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void ragrevival$definePose(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(ragrevival$POSE, new CompoundTag());
    }

    @Override public CompoundTag getRagRevivalPose() {
        return entityData == null ? new CompoundTag() : entityData.get(ragrevival$POSE);
    }

    @Override public void setRagRevivalPose(CompoundTag pose) {
        entityData.set(ragrevival$POSE, CorpsePose.valid(pose) ? pose.copy() : new CompoundTag());
        recalculateBoundingBox();
    }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (ragrevival$POSE.equals(accessor)) recalculateBoundingBox();
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void ragrevival$savePose(CompoundTag tag, CallbackInfo ci) {
        CompoundTag pose = CorpseCompat.poseForSave((CorpseEntity) (Object) this);
        if (CorpsePose.valid(pose)) tag.put(ragrevival$POSE_KEY, pose.copy());
        // Corpse's death serializer compacts empty cosmetic slots. Keep their indices
        // separately; the authoritative death inventory and history remain untouched.
        ListTag equipment = new ListTag();
        var slots = ((CorpseEntity) (Object) this).getEquipment();
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", i);
            entry.put("Item", slots.get(i).save(registryAccess()));
            equipment.add(entry);
        }
        tag.put(ragrevival$EQUIPMENT_KEY, equipment);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void ragrevival$loadPose(CompoundTag tag, CallbackInfo ci) {
        setRagRevivalPose(tag.getCompound(ragrevival$POSE_KEY));
        if (tag.contains(ragrevival$EQUIPMENT_KEY, Tag.TAG_LIST)) {
            NonNullList<ItemStack> equipment = NonNullList.withSize(EquipmentSlot.values().length, ItemStack.EMPTY);
            ListTag saved = tag.getList(ragrevival$EQUIPMENT_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < saved.size(); i++) {
                CompoundTag entry = saved.getCompound(i);
                int slot = entry.getInt("Slot");
                if (slot >= 0 && slot < equipment.size()) {
                    equipment.set(slot, ItemStack.parse(registryAccess(), entry.getCompound("Item")).orElse(ItemStack.EMPTY));
                }
            }
            ((CorpseEntity) (Object) this).setEquipment(equipment);
        }
    }
}
