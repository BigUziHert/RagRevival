package com.biguzi.ragrevival.compat.corpse.client;

import com.biguzi.ragrevival.compat.corpse.CorpsePose;
import com.biguzi.ragrevival.compat.corpse.CorpsePoseAccess;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import de.maxhenkel.corpse.Main;
import de.maxhenkel.corpse.entities.CorpseEntity;
import de.maxhenkel.corpse.entities.DummyPlayer;
import de.maxhenkel.corpse.entities.DummySkeleton;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Renders the Corpse mod's own skin/skeleton and equipment using six Sable transforms. */
public final class CorpseRagdollRenderer {
    private static final ThreadLocal<PartRender> CURRENT = new ThreadLocal<>();
    private static final List<WeakReference<CorpseRagdollRenderer>> RENDERERS = new ArrayList<>();
    // Keys disappear with their client entity, including dimension changes and disconnects.
    private final Map<CorpseEntity, DummyPair> dummies = new WeakHashMap<>();
    private final Map<CorpseEntity, CompoundTag> validPoses = new WeakHashMap<>();

    public CorpseRagdollRenderer() {
        synchronized (RENDERERS) {
            RENDERERS.removeIf(reference -> reference.get() == null);
            RENDERERS.add(new WeakReference<>(this));
        }
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CorpseRagdollRenderer::unload);
    }

    private static void unload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        synchronized (RENDERERS) {
            RENDERERS.removeIf(reference -> {
                CorpseRagdollRenderer renderer = reference.get();
                if (renderer == null) return true;
                renderer.dummies.clear();
                renderer.validPoses.clear();
                return false;
            });
        }
    }

    public boolean render(CorpseEntity corpse, float partialTick, PoseStack poses,
                          MultiBufferSource buffer, int light) {
        CompoundTag pose = ((CorpsePoseAccess) corpse).getRagRevivalPose();
        if (validPoses.get(corpse) != pose) {
            if (!CorpsePose.valid(pose)) return false;
            validPoses.put(corpse, pose);
        }
        DummyPair pair = dummies.computeIfAbsent(corpse, DummyPair::new);
        LivingEntity dummy = corpse.isSkeleton() ? pair.skeleton : pair.player;
        EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(dummy);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)
                || !(living.getModel() instanceof HumanoidModel<?> model)) return false;

        List<SavedPart> saved = modelParts(model).stream().map(SavedPart::new).toList();
        ListTag parts = pose.getList("parts", Tag.TAG_COMPOUND);
        Vec3 renderAnchor = renderAnchor(corpse, partialTick);
        PartRender previous = CURRENT.get();
        try {
            for (BodyPart bodyPart : BodyPart.values()) {
                CompoundTag part = parts.getCompound(bodyPart.ordinal());
                CURRENT.set(new PartRender(dummy, bodyPart, model));
                setEquipment(dummy, corpse, bodyPart);
                poses.pushPose();
                try {
                    applyPartTransform(corpse, pose.getBoolean("active"), part, renderAnchor, partialTick, poses);
                    // PlayerRenderer supplies the matching Sable scale itself; SkeletonRenderer does not.
                    if (corpse.isSkeleton()) poses.scale(bodyPart.renderScale(), bodyPart.renderScale(), bodyPart.renderScale());
                    renderDummy(renderer, dummy, partialTick, poses, buffer, light);
                } finally {
                    poses.popPose();
                    saved.forEach(SavedPart::restore);
                }
            }
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            saved.forEach(SavedPart::restore);
        }
        return true;
    }

    private static void applyPartTransform(CorpseEntity corpse, boolean active, CompoundTag part,
                                          Vec3 anchor, float partialTick, PoseStack poses) {
        var container = SubLevelContainer.getContainer(corpse.level());
        if (active && part.hasUUID("sublevel") && container != null
                && container.getSubLevel(part.getUUID("sublevel")) instanceof ClientSubLevel subLevel
                && !subLevel.isRemoved() && subLevel.getPlot() != null) {
            // Use the same interpolated native transform as Sable's block-entity renderer.
            // The incoming entity pose stack is already translated to the corpse anchor.
            var nativePose = subLevel.renderPose(partialTick);
            var block = part.contains("block", net.minecraft.nbt.Tag.TAG_LONG)
                    ? net.minecraft.core.BlockPos.of(part.getLong("block")) : subLevel.getPlot().getCenterBlock();
            Vec3 center = nativePose.transformPosition(Vec3.atCenterOf(block));
            poses.translate(center.x - anchor.x, center.y - anchor.y, center.z - anchor.z);
            poses.mulPose(new Quaternionf(nativePose.orientation()));
            var scale = nativePose.scale();
            poses.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
            return;
        }
        // Entity data can arrive before a sublevel's packets, and survives chunk unloading.
        poses.translate(part.getDouble("x"), part.getDouble("y"), part.getDouble("z"));
        poses.mulPose(new Quaternionf((float) part.getDouble("qx"), (float) part.getDouble("qy"),
                (float) part.getDouble("qz"), (float) part.getDouble("qw")));
    }

    private static Vec3 renderAnchor(CorpseEntity corpse, float partialTick) {
        // Match Sable's LevelRendererMixin when an ordinary world entity tracks a deck.
        if (!corpse.isPassenger() && Sable.HELPER.getTrackingSubLevel(corpse) instanceof ClientSubLevel support) {
            Vector3d oldLocal = support.lastPose().transformPositionInverse(new Vector3d(corpse.xOld, corpse.yOld, corpse.zOld));
            Vector3d newLocal = support.logicalPose().transformPositionInverse(new Vector3d(corpse.getX(), corpse.getY(), corpse.getZ()));
            Vector3d world = support.renderPose(partialTick).transformPosition(oldLocal.lerp(newLocal, partialTick));
            return new Vec3(world.x, world.y, world.z);
        }
        return new Vec3(Mth.lerp(partialTick, corpse.xOld, corpse.getX()),
                Mth.lerp(partialTick, corpse.yOld, corpse.getY()), Mth.lerp(partialTick, corpse.zOld, corpse.getZ()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderDummy(EntityRenderer renderer, LivingEntity dummy, float partialTick,
                                    PoseStack poses, MultiBufferSource buffer, int light) {
        renderer.render(dummy, 0, partialTick, poses, buffer, light);
    }

    public static boolean isRendering(LivingEntity entity) {
        PartRender current = CURRENT.get();
        return current != null && current.entity == entity;
    }

    public static boolean hideEars(LivingEntity entity) {
        PartRender current = CURRENT.get();
        return current != null && current.entity == entity && current.part != BodyPart.HEAD;
    }

    /** Runs after native animation setup, before the base model and all equipment layers. */
    public static void prepareModel(LivingEntity entity, EntityModel<?> entityModel, PoseStack poses) {
        PartRender current = CURRENT.get();
        if (current == null || current.entity != entity || !(entityModel instanceof HumanoidModel<?> model)) return;
        for (ModelPart part : modelParts(model)) part.resetPose();
        model.crouching = false;
        model.riding = false;
        model.young = false;
        mask(model, current.part);
        ModelPart part = switch (current.part) {
            case HEAD -> model.head;
            case TORSO -> model.body;
            case LEFT_ARM -> model.leftArm;
            case RIGHT_ARM -> model.rightArm;
            case LEFT_LEG -> model.leftLeg;
            case RIGHT_LEG -> model.rightLeg;
        };
        // Cancel the ordinary standing-player origin and use Sable's per-part model origin.
        poses.translate(-part.x / 16F, 1.501F - part.y / 16F, -part.z / 16F);
        switch (current.part) {
            case HEAD -> poses.translate(0, 4F / 16F, 0);
            case TORSO -> poses.translate(0, -6.5F / 16F, 0);
            case LEFT_ARM -> poses.translate(-2F / 16F, -4F / 16F, 0);
            case RIGHT_ARM -> poses.translate(0, -4F / 16F, 0);
            case LEFT_LEG, RIGHT_LEG -> poses.translate(-1F / 16F, -6F / 16F, 0);
        }
    }

    public static void maskArmor(HumanoidModel<?> model, HumanoidModel<?> parentModel) {
        PartRender current = CURRENT.get();
        if (current != null && current.model == parentModel) mask(model, current.part);
    }

    private static void mask(HumanoidModel<?> model, BodyPart part) {
        model.head.visible &= part == BodyPart.HEAD;
        model.hat.visible &= part == BodyPart.HEAD;
        model.body.visible &= part == BodyPart.TORSO;
        model.leftArm.visible &= part == BodyPart.LEFT_ARM;
        model.rightArm.visible &= part == BodyPart.RIGHT_ARM;
        model.leftLeg.visible &= part == BodyPart.LEFT_LEG;
        model.rightLeg.visible &= part == BodyPart.RIGHT_LEG;
        if (model instanceof PlayerModel<?> player) {
            player.jacket.visible &= part == BodyPart.TORSO;
            player.leftSleeve.visible &= part == BodyPart.LEFT_ARM;
            player.rightSleeve.visible &= part == BodyPart.RIGHT_ARM;
            player.leftPants.visible &= part == BodyPart.LEFT_LEG;
            player.rightPants.visible &= part == BodyPart.RIGHT_LEG;
        }
    }

    private static List<ModelPart> modelParts(HumanoidModel<?> model) {
        List<ModelPart> parts = new ArrayList<>(List.of(model.head, model.hat, model.body,
                model.leftArm, model.rightArm, model.leftLeg, model.rightLeg));
        if (model instanceof PlayerModel<?> player) parts.addAll(List.of(player.jacket,
                player.leftSleeve, player.rightSleeve, player.leftPants, player.rightPants));
        return parts;
    }

    private static void setEquipment(LivingEntity dummy, CorpseEntity corpse, BodyPart part) {
        NonNullList<ItemStack> equipment = corpse.getEquipment();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = Main.SERVER_CONFIG.renderEquipment.get() && slot.ordinal() < equipment.size()
                    ? equipment.get(slot.ordinal()) : ItemStack.EMPTY;
            boolean belongs = switch (slot) {
                case HEAD -> part == BodyPart.HEAD;
                case CHEST -> part == BodyPart.TORSO || (stack.getItem() instanceof ArmorItem
                        && (part == BodyPart.LEFT_ARM || part == BodyPart.RIGHT_ARM));
                case LEGS -> part == BodyPart.TORSO || part == BodyPart.LEFT_LEG || part == BodyPart.RIGHT_LEG;
                case FEET -> part == BodyPart.LEFT_LEG || part == BodyPart.RIGHT_LEG;
                case MAINHAND -> part == BodyPart.RIGHT_ARM;
                case OFFHAND -> part == BodyPart.LEFT_ARM;
                default -> false;
            };
            dummy.setItemSlot(slot, belongs ? stack : ItemStack.EMPTY);
        }
    }

    private record PartRender(LivingEntity entity, BodyPart part, HumanoidModel<?> model) {}
    private record SavedPart(ModelPart part, PartPose pose, boolean visible, float sx, float sy, float sz) {
        SavedPart(ModelPart part) { this(part, part.storePose(), part.visible, part.xScale, part.yScale, part.zScale); }
        void restore() {
            part.loadPose(pose); part.visible = visible;
            part.xScale = sx; part.yScale = sy; part.zScale = sz;
        }
    }

    private static final class DummyPair {
        final DummyPlayer player;
        final DummySkeleton skeleton;
        DummyPair(CorpseEntity corpse) {
            player = new DummyPlayer((ClientLevel) corpse.level(), new GameProfile(
                    corpse.getCorpseUUID().orElse(new UUID(0, 0)), corpse.getCorpseName()),
                    corpse.getEquipment(), corpse.getCorpseModel()) {
                @Override public boolean isModelPartShown(PlayerModelPart part) {
                    PartRender current = CURRENT.get();
                    return super.isModelPartShown(part) && (part != PlayerModelPart.CAPE
                            || current == null || current.part == BodyPart.TORSO);
                }
            };
            skeleton = new DummySkeleton(corpse.level(), corpse.getEquipment());
        }
    }
}
