package com.biguzi.ragrevival;

import com.biguzi.ragrevival.ragdoll.RagdollBridge;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.Vec3;

/** Self-feeding uses native eating; both rescue paths show food effects at the patient's mouth. */
public final class FeedingAnimation {
    private FeedingAnimation() {}

    /** The flag survives a removed client snapshot until native item use has been safely cleared. */
    public interface ManagedUse {
        boolean ragrevival$isManagedUse();
        void ragrevival$setManagedUse(boolean managed);
    }

    public static boolean isManaged(Player player) {
        if (((ManagedUse) player).ragrevival$isManagedUse()) return true;
        return player.level().isClientSide ? ClientState.isFeeding(player) : DownedManager.isFeeding(player);
    }

    public static void start(Player player, InteractionHand hand) {
        if (player.isUsingItem()) return;
        UseAnim animation = player.getItemInHand(hand).getUseAnimation();
        // A datapack may tag shields, bows, or other tools. Do not activate their native actions.
        if (animation != UseAnim.EAT && animation != UseAnim.DRINK) return;
        ManagedUse managed = (ManagedUse) player;
        managed.ragrevival$setManagedUse(true);
        player.startUsingItem(hand);
        if (!player.isUsingItem()) managed.ragrevival$setManagedUse(false);
    }

    /** Call only for an owned feeding interaction, including after its final state packet. */
    public static void stop(Player player) {
        ((ManagedUse) player).ragrevival$setManagedUse(true);
        player.stopUsingItem();
    }

    /** Renderer ratios must stay within the native duration, independently of feeding duration. */
    public static int remaining(ItemStack stack, LivingEntity player, int animationTicks) {
        int nativeDuration = Math.max(1, stack.getUseDuration(player));
        int upper = Math.max(1, nativeDuration * 3 / 4);
        int lower = Math.max(1, nativeDuration / 4);
        return upper - Math.floorMod(animationTicks, upper - lower + 1);
    }

    /** Broadcast crumbs and chewing at the visible patient's mouth, including self-feeding. */
    public static void tick(ServerPlayer actor, ServerPlayer target, InteractionHand hand, int ticks) {
        // Only self-revival raises food to the actor's mouth. A teammate keeps it held while
        // the recipient's mouth effects show who is being fed.
        if (actor == target) start(actor, hand);
        if (ticks < 4 || ticks % 4 != 0) return;
        ItemStack stack = actor.getItemInHand(hand);
        if (stack.isEmpty()) return;
        Vec3 mouth = mouthPosition(target);
        target.serverLevel().sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack.copyWithCount(1)),
                mouth.x, mouth.y, mouth.z, 4, 0.035, 0.035, 0.035, 0.02);
        target.serverLevel().playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.GENERIC_EAT,
                SoundSource.PLAYERS, 0.45F, 0.9F + target.getRandom().nextFloat() * 0.2F);
    }

    private static Vec3 mouthPosition(ServerPlayer player) {
        SubLevelContainer container = SubLevelContainer.getContainer(player.level());
        if (container != null) {
            for (var id : RagdollBridge.bodyPartIds(player)) {
                var part = container.getSubLevel(id);
                if (part == null || part.isRemoved() || part.getPlot() == null) continue;
                BlockPos pos = part.getPlot().getCenterBlock();
                if (!(player.level().getBlockEntity(pos) instanceof RagdollPartBlockEntity block)
                        || block.bodyPart() != RagdollPartBlockEntity.BodyPart.HEAD
                        || !player.getUUID().equals(block.skinProfile().getId())) continue;
                // Sable's head renderer centers the face at +Z with its mouth below the center.
                return part.logicalPose().transformPosition(Vec3.atLowerCornerOf(pos).add(0.5, 0.38, 0.77));
            }
        }
        return RagdollBridge.worldEyePosition(player);
    }

    /** Loaded only on a client; dedicated servers never resolve Minecraft client classes. */
    private static final class ClientState {
        private static boolean isFeeding(Player player) {
            return com.biguzi.ragrevival.client.RevivalClient.isFeeding(player);
        }
    }
}
