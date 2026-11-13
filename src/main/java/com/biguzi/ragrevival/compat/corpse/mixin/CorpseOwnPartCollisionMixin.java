package com.biguzi.ragrevival.compat.corpse.mixin;

import com.biguzi.ragrevival.compat.corpse.CorpseOrphanCleanup;
import de.maxhenkel.corpse.entities.CorpseEntity;
import dev.leo.sableplayerragdoll.block.RagdollPartBlock;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.api.math.LevelReusedVectors;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A corpse must not collide with its own temporary or restored physics representation. */
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class CorpseOwnPartCollisionMixin {
    @Inject(method = "collide", at = @At("HEAD"))
    private static void ragrevival$clearOwnTracking(Entity entity, Vec3 collisionMotion, Vec3 velocityMotion,
            LevelReusedVectors sink, CallbackInfoReturnable<SubLevelEntityCollision.CollisionInfo> cir) {
        if (!(entity instanceof CorpseEntity) || !(entity instanceof EntityMovementExtension movement)) return;
        if (ragrevival$isOwnPart(entity, movement.sable$getTrackingSubLevel())) {
            movement.sable$setTrackingSubLevel(null);
            movement.sable$setLastTrackingSubLevelID(null);
        }
    }

    @Redirect(method = "collide", at = @At(value = "INVOKE", target =
            "Ldev/ryanhcode/sable/ActiveSableCompanion;getAllIntersecting(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;)Ljava/lang/Iterable;"))
    private static Iterable<SubLevel> ragrevival$excludeOwnParts(ActiveSableCompanion helper, Level level,
            BoundingBox3dc bounds, Entity entity, Vec3 collisionMotion, Vec3 velocityMotion,
            LevelReusedVectors sink) {
        Iterable<SubLevel> candidates = helper.getAllIntersecting(level, bounds);
        if (!(entity instanceof CorpseEntity)) return candidates;
        List<SubLevel> filtered = new ArrayList<>();
        for (SubLevel candidate : candidates) {
            if (!ragrevival$isOwnPart(entity, candidate)) filtered.add(candidate);
        }
        return filtered;
    }

    @Unique private static boolean ragrevival$isOwnPart(Entity corpse, SubLevel subLevel) {
        if (subLevel == null || subLevel.getPlot() == null) return false;
        BlockPos center = subLevel.getPlot().getCenterBlock();
        BlockState state = corpse.level().getBlockState(center);
        if (!state.hasBlockEntity() || !(state.getBlock() instanceof RagdollPartBlock)
                || !(corpse.level().getBlockEntity(center) instanceof RagdollPartBlockEntity part)) return false;
        CompoundTag data = part.getPersistentData();
        return data.getBoolean(CorpseOrphanCleanup.PART_MARKER)
                && data.hasUUID(CorpseOrphanCleanup.OWNER_MARKER)
                && corpse.getUUID().equals(data.getUUID(CorpseOrphanCleanup.OWNER_MARKER));
    }
}
