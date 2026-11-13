package com.biguzi.ragrevival.compat;

import com.biguzi.ragrevival.DownedManager;
import com.biguzi.ragrevival.compat.corpse.CorpseOrphanCleanup;
import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.leo.sableplayerragdoll.entity.RagdollSeatEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/** All optional classes are isolated so a server without Carry On never resolves them. */
public final class CarryOnCompat {
    private CarryOnCompat() {}

    public static void register() {
        if (ModList.get().isLoaded("carryon")) Loaded.register();
    }

    public static boolean isCarrying(Player player) {
        return ModList.get().isLoaded("carryon") && Loaded.isCarrying(player);
    }

    public static void releaseForDowning(ServerPlayer player) {
        if (ModList.get().isLoaded("carryon")) Loaded.release(player);
        // Dismounting an ordinary Sable seat schedules expiry; conversion must retain that body.
        if (!(player.getVehicle() instanceof RagdollSeatEntity)) player.stopRiding();
        player.ejectPassengers();
    }

    private static final class Loaded {
        private static void register() {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, Loaded::onPickup);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, Loaded::onBlockPickup);
        }

        private static boolean isCarrying(Player player) {
            return tschipp.carryon.common.carry.CarryOnDataManager.getCarryData(player).isCarrying();
        }

        private static void release(ServerPlayer player) {
            // Release the carrier's data before vanilla dismount clears the vehicle reference.
            tschipp.carryon.CarryOnCommon.onRiderDisconnected(player);
            if (isCarrying(player)) {
                tschipp.carryon.common.carry.PlacementHandler.placeCarried(player);
            }
        }

        private static void onPickup(tschipp.carryon.events.EntityPickupEvent event) {
            if (DownedManager.isDowned(event.player) || DownedManager.isBusy(event.player)
                    || DownedManager.isDragging(event.player)
                    // Carry On copies entity NBT before removal; Corpse drops its inventory on removal.
                    // Rejecting pickup preserves its authoritative inventory and lets crouch-use loot it.
                    || event.target instanceof CorpsePoseAccess
                    || event.target instanceof Player target && DownedManager.isDowned(target)) {
                event.setCanceled(true);
            }
        }

        private static void onBlockPickup(BlockEvent.BreakEvent event) {
            // Carry On uses the normal break callback before serializing a block entity. Native corpse
            // limbs belong to the inventory-bearing corpse and must never be detached into carried NBT.
            if (event.getState().hasBlockEntity()
                    && tschipp.carryon.common.carry.CarryOnDataManager.getCarryData(event.getPlayer()).isKeyPressed()
                    && event.getLevel().getBlockEntity(event.getPos()) instanceof RagdollPartBlockEntity part
                    && part.getPersistentData().getBoolean(CorpseOrphanCleanup.PART_MARKER)) {
                event.setCanceled(true);
            }
        }
    }
}
