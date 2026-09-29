package com.cdp.codpattern.app.zombies.service;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Bounded block geometry checks for recovery; native navigation still decides movement. */
public final class ZombiesNavigationGeometry {
    private static final double EPSILON = 1.0E-6D;
    private static final double SUPPORT_DEPTH = 0.125D;
    private static final double LOCAL_SUPPORT_RANGE = 2.0D;
    private static final double MAX_SEGMENT_LENGTH_SQUARED = 4.0D * 4.0D;
    private static final int MAX_COLLISION_BOXES = 512;

    private ZombiesNavigationGeometry() { }

    public enum SegmentStatus { CLEAR, BLOCKED, UNKNOWN }

    /**
     * Keep the requested node first. Extra heights must describe the same physical support surface,
     * rather than a different floor in the target's column. Returned nodes are still search candidates.
     */
    public static List<BlockPos> targetCandidates(Mob mob, Vec3 targetFeet) {
        if (!finite(targetFeet)) {
            return List.of();
        }
        BlockPos original = BlockPos.containing(targetFeet);
        List<BlockPos> result = new ArrayList<>(3);
        result.add(original);
        AABB targetBody = bodyAt(mob, targetFeet);
        if (!loaded(mob.level(), targetBody.inflate(1.0D))
                || hasBlockCollision(mob, targetBody.deflate(EPSILON))
                || !hasSupportAt(mob, targetBody, targetFeet.y)) {
            return List.copyOf(result);
        }
        double offset = (int) (mob.getBbWidth() + 1.0F) * 0.5D;
        for (BlockPos candidate : List.of(original.above(), original.below())) {
            Vec3 feet = groundFeet(mob, new Vec3(candidate.getX() + offset,
                    candidate.getY(), candidate.getZ() + offset));
            if (feet == null || Math.abs(feet.y - targetFeet.y) > EPSILON) {
                continue;
            }
            AABB candidateBody = bodyAt(mob, feet);
            if (loaded(mob.level(), candidateBody.inflate(1.0D))
                    && !hasBlockCollision(mob, candidateBody.deflate(EPSILON))
                    && hasSupportAt(mob, candidateBody, feet.y)) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Resolve a local relay onto the nearest clear support within two vertical blocks. This is only a
     * destination candidate: native pathfinding must still prove a route using its step/fall limits.
     * Player targets deliberately use the stricter same-surface contract of targetCandidates instead.
     */
    @Nullable
    public static Vec3 localTargetFeet(Mob mob, Vec3 approximateFeet) {
        if (!finite(approximateFeet)) {
            return null;
        }
        AABB body = bodyAt(mob, approximateFeet);
        AABB readArea = body.move(0.0D, -LOCAL_SUPPORT_RANGE, 0.0D)
                .minmax(body.move(0.0D, LOCAL_SUPPORT_RANGE, 0.0D)).inflate(1.0D);
        if (!loaded(mob.level(), readArea)) {
            return null;
        }
        AABB probe = new AABB(body.minX + EPSILON,
                approximateFeet.y - LOCAL_SUPPORT_RANGE - SUPPORT_DEPTH, body.minZ + EPSILON,
                body.maxX - EPSILON, approximateFeet.y + LOCAL_SUPPORT_RANGE + EPSILON, body.maxZ - EPSILON);
        Vec3 best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int boxes = 0;
        for (VoxelShape shape : mob.level().getBlockCollisions(mob, probe)) {
            for (AABB support : shape.toAabbs()) {
                if (++boxes > MAX_COLLISION_BOXES) {
                    return null;
                }
                double distance = Math.abs(support.maxY - approximateFeet.y);
                if (distance > LOCAL_SUPPORT_RANGE + EPSILON || distance >= bestDistance
                        || support.maxX <= probe.minX || support.minX >= probe.maxX
                        || support.maxZ <= probe.minZ || support.minZ >= probe.maxZ) {
                    continue;
                }
                Vec3 feet = new Vec3(approximateFeet.x, support.maxY, approximateFeet.z);
                if (!hasBlockCollision(mob, bodyAt(mob, feet).deflate(EPSILON))) {
                    best = feet;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** Resolve the Y used by PathNavigation.getGroundY, or defer if its read area is unavailable. */
    @Nullable
    public static Vec3 nodeFeet(Mob mob, Path path, int index) {
        if (path == null || index < 0 || index >= path.getNodeCount()) {
            return null;
        }
        return groundFeet(mob, path.getEntityPosAtNode(mob, index));
    }

    /**
     * Sweep the current body through a short level segment against blocks only. Height changes remain
     * UNKNOWN: a straight diagonal sweep is not the native step/jump/descent trajectory. A low obstacle
     * within native step height is also UNKNOWN, since a level sweep cannot prove the step trajectory. CLEAR only
     * describes this segment's block clearance, not support, hazards, or the remainder of a path.
     */
    public static SegmentStatus checkSegment(Mob mob, @Nullable Vec3 fromFeet, @Nullable Vec3 toFeet) {
        if (!finite(fromFeet) || !finite(toFeet)
                || Math.abs(fromFeet.y - toFeet.y) > EPSILON
                || fromFeet.distanceToSqr(toFeet) > MAX_SEGMENT_LENGTH_SQUARED) {
            return SegmentStatus.UNKNOWN;
        }
        AABB start = bodyAt(mob, fromFeet).deflate(EPSILON);
        AABB end = bodyAt(mob, toFeet).deflate(EPSILON);
        AABB sweptBounds = start.minmax(end);
        if (!loaded(mob.level(), sweptBounds.inflate(1.0D))) {
            return SegmentStatus.UNKNOWN;
        }
        Vec3 fromCenter = start.getCenter();
        Vec3 toCenter = end.getCenter();
        double halfX = start.getXsize() * 0.5D;
        double halfY = start.getYsize() * 0.5D;
        double halfZ = start.getZsize() * 0.5D;
        int boxes = 0;
        boolean needsStep = false;
        double stepTop = fromFeet.y + mob.maxUpStep() + EPSILON;
        for (VoxelShape shape : mob.level().getBlockCollisions(mob, sweptBounds)) {
            for (AABB obstacle : shape.toAabbs()) {
                if (++boxes > MAX_COLLISION_BOXES) {
                    return SegmentStatus.UNKNOWN;
                }
                // Minkowski expansion gives an exact translation sweep for each axis-aligned shape box;
                // testing the bounding union alone would incorrectly reject clear diagonal routes.
                AABB expanded = obstacle.inflate(halfX, halfY, halfZ);
                if (start.intersects(obstacle) || end.intersects(obstacle)
                        || expanded.clip(fromCenter, toCenter).isPresent()) {
                    if (obstacle.maxY <= stepTop) {
                        needsStep = true;
                        continue;
                    }
                    return SegmentStatus.BLOCKED;
                }
            }
        }
        return needsStep ? SegmentStatus.UNKNOWN : SegmentStatus.CLEAR;
    }

    /**
     * Fingerprint current block collision geometry in one short segment, including dynamic shapes.
     * Entity positions are deliberately absent. An unreadable or excessively complex area has no signature.
     */
    @Nullable
    public static Long collisionSignature(Mob mob, @Nullable Vec3 fromFeet, @Nullable Vec3 toFeet) {
        if (!finite(fromFeet) || !finite(toFeet)
                || fromFeet.distanceToSqr(toFeet) > MAX_SEGMENT_LENGTH_SQUARED) {
            return null;
        }
        AABB bounds = bodyAt(mob, fromFeet).deflate(EPSILON)
                .minmax(bodyAt(mob, toFeet).deflate(EPSILON));
        if (!loaded(mob.level(), bounds.inflate(1.0D))) {
            return null;
        }
        // Hash world shape coordinates only; translating the moving body's AABB can introduce
        // insignificant rounding differences in this fixed segment's read bounds.
        long signature = 0xcbf29ce484222325L;
        int boxes = 0;
        for (VoxelShape shape : mob.level().getBlockCollisions(mob, bounds)) {
            for (AABB box : shape.toAabbs()) {
                if (++boxes > MAX_COLLISION_BOXES) {
                    return null;
                }
                signature = hashBox(signature, box);
            }
        }
        return (signature ^ boxes) * 0x100000001b3L;
    }

    private static long hashBox(long signature, AABB box) {
        signature = (signature ^ Double.doubleToLongBits(box.minX)) * 0x100000001b3L;
        signature = (signature ^ Double.doubleToLongBits(box.minY)) * 0x100000001b3L;
        signature = (signature ^ Double.doubleToLongBits(box.minZ)) * 0x100000001b3L;
        signature = (signature ^ Double.doubleToLongBits(box.maxX)) * 0x100000001b3L;
        signature = (signature ^ Double.doubleToLongBits(box.maxY)) * 0x100000001b3L;
        return (signature ^ Double.doubleToLongBits(box.maxZ)) * 0x100000001b3L;
    }

    @Nullable
    private static Vec3 groundFeet(Mob mob, Vec3 node) {
        if (!finite(node)) {
            return null;
        }
        BlockPos pos = BlockPos.containing(node);
        if (pos.getY() <= mob.level().getMinBuildHeight()
                || pos.getY() >= mob.level().getMaxBuildHeight()
                || !loaded(mob.level(), new AABB(pos).inflate(1.0D))) {
            return null;
        }
        double y = mob.level().getBlockState(pos.below()).isAir() ? node.y
                : WalkNodeEvaluator.getFloorLevel(mob.level(), pos);
        return new Vec3(node.x, y, node.z);
    }

    private static AABB bodyAt(Mob mob, Vec3 feet) {
        AABB body = mob.getBoundingBox();
        return body.move(feet.x - mob.getX(), feet.y - body.minY, feet.z - mob.getZ());
    }

    private static boolean hasSupportAt(Mob mob, AABB body, double feetY) {
        AABB probe = new AABB(body.minX + EPSILON, feetY - SUPPORT_DEPTH, body.minZ + EPSILON,
                body.maxX - EPSILON, feetY + EPSILON, body.maxZ - EPSILON);
        int boxes = 0;
        for (VoxelShape shape : mob.level().getBlockCollisions(mob, probe)) {
            for (AABB support : shape.toAabbs()) {
                if (++boxes > MAX_COLLISION_BOXES) {
                    return false;
                }
                if (Math.abs(support.maxY - feetY) <= EPSILON
                        && support.maxX > probe.minX && support.minX < probe.maxX
                        && support.maxZ > probe.minZ && support.minZ < probe.maxZ) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasBlockCollision(Mob mob, AABB body) {
        for (VoxelShape shape : mob.level().getBlockCollisions(mob, body)) {
            if (!shape.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Native collision shapes may inspect adjacent cells; callers include that margin before reading. */
    private static boolean loaded(Level level, AABB footprint) {
        for (int x = Mth.floor(footprint.minX) >> 4; x <= Mth.floor(footprint.maxX) >> 4; x++) {
            for (int z = Mth.floor(footprint.minZ) >> 4; z <= Mth.floor(footprint.maxZ) >> 4; z++) {
                if (!level.hasChunkAt(new BlockPos(x << 4, level.getMinBuildHeight(), z << 4))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean finite(@Nullable Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
