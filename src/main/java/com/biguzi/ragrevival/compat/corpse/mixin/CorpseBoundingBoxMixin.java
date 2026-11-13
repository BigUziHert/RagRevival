package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import de.maxhenkel.corpse.entities.CorpseBoundingBoxBase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = CorpseBoundingBoxBase.class, remap = false)
public abstract class CorpseBoundingBoxMixin extends Entity {
    @Unique private CompoundTag ragrevival$lastPose;
    @Unique private AABB ragrevival$relativeBounds;

    protected CorpseBoundingBoxMixin(EntityType<?> type, Level level) { super(type, level); }

    @Override protected AABB makeBoundingBox() {
        AABB relative = ragrevival$getRelativeBounds();
        // Sable's raw position setter bypasses Corpse's recalculateBoundingBox.
        return relative == null ? super.makeBoundingBox() : relative.move(position());
    }

    @Inject(method = "recalculateBoundingBox", at = @At("TAIL"))
    private void ragrevival$poseBounds(CallbackInfo ci) {
        AABB relative = ragrevival$getRelativeBounds();
        if (relative != null) setBoundingBox(relative.move(position()));
    }

    @Unique private AABB ragrevival$getRelativeBounds() {
        if ((Object) this instanceof CorpsePoseAccess access) {
            CompoundTag pose = access.getRagRevivalPose();
            if (pose != ragrevival$lastPose) {
                ragrevival$lastPose = pose;
                ragrevival$relativeBounds = null;
                if (CorpsePose.valid(pose)) {
                    AABB tight = CorpsePose.bounds(Vec3.ZERO, pose);
                    // Sable reconstructs entity position from the box's bottom center.
                    // Preserve the saved torso anchor while enclosing every posed limb.
                    double x = Math.max(Math.abs(tight.minX), Math.abs(tight.maxX));
                    double z = Math.max(Math.abs(tight.minZ), Math.abs(tight.maxZ));
                    double floor = Math.abs(tight.minY) < 1.0E-8 ? 0 : tight.minY;
                    ragrevival$relativeBounds = new AABB(-x, floor, -z, x, tight.maxY, z);
                }
            }
        }
        return ragrevival$relativeBounds;
    }
}
