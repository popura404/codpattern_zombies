package com.cdp.codpattern.app.zombies.service;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.LinkedHashMap;
import java.util.Map;

/** Room-local spawn checks and temporary, body-specific penalties for managed ground mobs. */
public final class ZombiesGroundSpawnPolicy {
    static final double MAX_DIAGNOSTIC_DROP = 4.0D;
    static final int FAILURE_EXPIRY_TICKS = 20 * 60;
    static final int MAX_FAILURE_KEYS = 256;
    private static final double DROP_SAMPLE_DISTANCE = 0.25D;
    private final FailureHistory failures = new FailureHistory();

    /** Body-clearance diagnostic only, without landing or hazard scans. */
    public Validation validateBody(Mob mob) {
        if (!ZombiesGroundNavigationService.supports(mob) || !(mob.level() instanceof ServerLevel level)) {
            return new Validation(true, "");
        }
        AABB body = mob.getBoundingBox();
        // Collision shapes may consult adjacent cells. Check the entire read footprint before any world reads.
        if (!hasLoadedChunks(level, body.inflate(1.0D))) {
            return new Validation(true, "spawn.ground_check_chunk_unavailable");
        }
        if (hasBlockCollision(mob, level, body)) {
            return new Validation(false, "spawn.ground_body_blocked");
        }
        return new Validation(true, "");
    }

    /** Full bounded diagnostics for the selected point; this result does not gate spawning. */
    public Validation evaluate(Mob mob) {
        Validation bodyValidation = validateBody(mob);
        if (!bodyValidation.allowed() || !bodyValidation.reason().isEmpty()
                || !ZombiesGroundNavigationService.supports(mob)
                || !(mob.level() instanceof ServerLevel level)) {
            return bodyValidation;
        }
        AABB body = mob.getBoundingBox();
        // The downward probes stay in the same loaded chunk columns verified by validateBody.
        String hazard = hazardReason(level, body);
        if (!hazard.isEmpty()) {
            return new Validation(true, hazard);
        }
        for (double drop = DROP_SAMPLE_DISTANCE; drop <= MAX_DIAGNOSTIC_DROP; drop += DROP_SAMPLE_DISTANCE) {
            AABB lowered = body.move(0.0D, -drop, 0.0D);
            hazard = hazardReason(level, lowered);
            if (!hazard.isEmpty()) {
                return new Validation(true, hazard);
            }
            if (hasBlockCollision(mob, level, lowered)) {
                return new Validation(true, "");
            }
        }
        // Existing maps can deliberately spawn mobs in the air. Hard support rejection requires map validation.
        return new Validation(true, "spawn.ground_landing_unverified");
    }

    public double weightMultiplier(String spawnObjectId, Mob mob, long gameTime) {
        String key = failureKey(spawnObjectId, mob);
        return key.isEmpty() ? 1.0D : failures.multiplier(key, gameTime);
    }

    /** Call only after successful navigation-failure recycling, never for ordinary kills or no-target expiry. */
    public void recordFailure(String spawnObjectId, Mob mob, long gameTime) {
        String key = failureKey(spawnObjectId, mob);
        if (!key.isEmpty()) {
            failures.record(key, gameTime);
        }
    }

    public void reset() {
        failures.clear();
    }

    private static String failureKey(String spawnObjectId, Mob mob) {
        if (spawnObjectId == null || spawnObjectId.isBlank() || !ZombiesGroundNavigationService.supports(mob)) {
            return "";
        }
        AABB body = mob.getBoundingBox();
        return spawnObjectId + "|" + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType())
                + "|" + mob.getBbWidth() + "|" + mob.getBbHeight()
                + "|" + Math.round(body.getXsize() * 1_000.0D)
                + "|" + Math.round(body.getYsize() * 1_000.0D)
                + "|" + Math.round(body.getZsize() * 1_000.0D)
                + "|" + mob.maxUpStep();
    }

    private static boolean hasLoadedChunks(ServerLevel level, AABB footprint) {
        int minChunkX = Mth.floor(footprint.minX) >> 4;
        int maxChunkX = Mth.floor(footprint.maxX) >> 4;
        int minChunkZ = Mth.floor(footprint.minZ) >> 4;
        int maxChunkZ = Mth.floor(footprint.maxZ) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunkAt(new BlockPos(chunkX << 4, level.getMinBuildHeight(), chunkZ << 4))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasBlockCollision(Mob mob, ServerLevel level, AABB body) {
        for (VoxelShape shape : level.getBlockCollisions(mob, body)) {
            if (!shape.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String hazardReason(ServerLevel level, AABB body) {
        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(body.minX), Mth.floor(body.minY), Mth.floor(body.minZ),
                Mth.floor(body.maxX - 1.0E-7D), Mth.floor(body.maxY - 1.0E-7D), Mth.floor(body.maxZ - 1.0E-7D))) {
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return "spawn.ground_fluid_unverified";
            }
            if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                    || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                    || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) {
                return "spawn.ground_hazard_unverified";
            }
        }
        return "";
    }

    public record Validation(boolean allowed, String reason) {
    }

    static final class FailureHistory {
        private final Map<String, Failure> entries = new LinkedHashMap<>();

        void record(String key, long gameTime) {
            expire(gameTime);
            Failure previous = entries.remove(key);
            int count = previous == null ? 1 : Math.min(3, previous.count() + 1);
            entries.put(key, new Failure(count, gameTime + FAILURE_EXPIRY_TICKS));
            while (entries.size() > MAX_FAILURE_KEYS) {
                entries.remove(entries.keySet().iterator().next());
            }
        }

        double multiplier(String key, long gameTime) {
            Failure failure = entries.get(key);
            if (failure == null) {
                return 1.0D;
            }
            if (gameTime >= failure.expiresGameTime()) {
                entries.remove(key);
                return 1.0D;
            }
            return Math.max(0.25D, 1.0D / (failure.count() + 1.0D));
        }

        void clear() {
            entries.clear();
        }

        private void expire(long gameTime) {
            entries.values().removeIf(failure -> gameTime >= failure.expiresGameTime());
        }

        private record Failure(int count, long expiresGameTime) {
        }
    }
}
