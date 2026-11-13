package com.biguzi.ragrevival.ragdoll.mixin;

import dev.leo.sableplayerragdoll.entity.RagdollSeatEntity;
import com.biguzi.ragrevival.ragdoll.NativeRagdollCompat;
import dev.leo.sableplayerragdoll.physics.RagdollExpireHelper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Sable's spatial queries include neighboring bodies; expiration must only remove its own seats. */
@Mixin(value = RagdollExpireHelper.class, remap = false)
public abstract class RagdollSeatCleanupMixin {
    @Redirect(method = "discardSeatEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private static List<RagdollSeatEntity> ragrevival$ownedSeats(ServerLevel level,
            Class<RagdollSeatEntity> seatType, AABB bounds, ServerLevel ownerLevel, ServerSubLevel root) {
        // 0.8 filters its logical session tag itself, including seats in a moved/merged plot.
        if (NativeRagdollCompat.blockIdentityStorage()) return level.getEntitiesOfClass(seatType, bounds);
        return level.getEntitiesOfClass(seatType, bounds).stream().filter(seat -> {
            var containing = Sable.HELPER.getContaining(ownerLevel, seat.blockPosition());
            return containing != null && containing.getUniqueId().equals(root.getUniqueId());
        }).toList();
    }
}
