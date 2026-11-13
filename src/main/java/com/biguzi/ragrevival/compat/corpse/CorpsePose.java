package com.biguzi.ragrevival.compat.corpse;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Six small transforms, retained after all native physics resources are released. */
public final class CorpsePose {
    public static final String PART_MARKER = "ragrevival:corpse_part";
    private CorpsePose() {}

    public static boolean valid(CompoundTag pose) {
        ListTag parts = pose.getList("parts", Tag.TAG_COMPOUND);
        if (parts.size() != 6) return false;
        for (int i = 0; i < 6; i++) {
            CompoundTag part = parts.getCompound(i);
            for (String key : new String[]{"x", "y", "z", "qx", "qy", "qz", "qw"}) {
                double value = part.getDouble(key);
                if (!part.contains(key, Tag.TAG_ANY_NUMERIC) || !Double.isFinite(value) || Math.abs(value) > 16) return false;
            }
            double length = rotation(part).lengthSquared();
            if (Math.abs(length - 1) > 0.01) return false;
        }
        return true;
    }

    public static CompoundTag part(Vec3 offset, Quaterniondc rotation) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", offset.x); tag.putDouble("y", offset.y); tag.putDouble("z", offset.z);
        tag.putDouble("qx", rotation.x()); tag.putDouble("qy", rotation.y());
        tag.putDouble("qz", rotation.z()); tag.putDouble("qw", rotation.w());
        return tag;
    }

    public static Vec3 offset(CompoundTag part) {
        return new Vec3(part.getDouble("x"), part.getDouble("y"), part.getDouble("z"));
    }

    public static Quaterniond rotation(CompoundTag part) {
        return new Quaterniond(part.getDouble("qx"), part.getDouble("qy"), part.getDouble("qz"), part.getDouble("qw"));
    }

    /** Matches Sable's outline boxes, centered on each limb's plot block. */
    public static Vec3 halfExtents(int part) {
        return part == 0 ? new Vec3(0.25, 0.25, 0.25)
                : new Vec3(part == 1 ? 0.25 : 0.125, 0.375, 0.125);
    }

    public static AABB bounds(Entity entity, CompoundTag pose) {
        return bounds(entity.position(), pose);
    }

    public static AABB bounds(Vec3 anchor, CompoundTag pose) {
        ListTag parts = pose.getList("parts", Tag.TAG_COMPOUND);
        AABB result = null;
        for (int i = 0; i < parts.size(); i++) {
            CompoundTag part = parts.getCompound(i);
            Vec3 center = anchor.add(offset(part));
            Quaterniond rotation = rotation(part);
            Vec3 half = halfExtents(i);
            for (int corner = 0; corner < 8; corner++) {
                Vector3d v = rotation.transform(new Vector3d((corner & 1) == 0 ? -half.x : half.x,
                        (corner & 2) == 0 ? -half.y : half.y, (corner & 4) == 0 ? -half.z : half.z));
                Vec3 point = center.add(v.x, v.y, v.z);
                AABB at = new AABB(point, point);
                result = result == null ? at : result.minmax(at);
            }
        }
        return result == null ? new AABB(anchor, anchor) : result;
    }

    /** A lying Sable-shaped pose for old corpses, void/fluid deaths, or an exhausted physics budget. */
    public static CompoundTag resting(float yaw, boolean faceDown) {
        Quaterniond rotation = new Quaterniond().rotateY(Math.toRadians(-yaw))
                .rotateX(faceDown ? Math.PI / 2 : -Math.PI / 2);
        double[][] centers = {{0, 0.625, 0}, {0, 0, 0}, {0.36, 0, 0}, {-0.36, 0, 0},
                {0.12, -0.75, 0}, {-0.12, -0.75, 0}};
        ListTag parts = new ListTag();
        for (double[] center : centers) {
            Vector3d v = rotation.transform(new Vector3d(center));
            parts.add(part(new Vec3(v.x, v.y + 0.25, v.z), rotation));
        }
        CompoundTag result = new CompoundTag();
        result.put("parts", parts);
        result.putBoolean("active", false);
        return result;
    }
}
