package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.common.block.CodPatternBlockRegister;
import com.cdp.codpattern.common.block.ZombiesBoxInteractionBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Materializes saved purchase points without replacing unrelated map blocks. */
public final class ZombiesPurchasableBlockService {
    private ZombiesPurchasableBlockService() {
    }

    public record Placement(
            String objectType,
            String objectId,
            ResourceKey<Level> dimension,
            BlockPos pos,
            Block block,
            Direction facing
    ) {
        public Placement {
            Objects.requireNonNull(dimension, "Purchase point dimension");
            pos = Objects.requireNonNull(pos, "Purchase point position").immutable();
            Objects.requireNonNull(block, "Purchase point block");
            facing = facing == null ? Direction.NORTH : facing;
        }
    }

    public static List<Placement> placements(ZombiesMapObjects objects) {
        ZombiesMapObjects resolved = objects == null ? ZombiesMapObjects.EMPTY : objects;
        List<Placement> placements = new ArrayList<>();
        resolved.weaponWalls().forEach(object -> placements.add(new Placement(
                "weapon_wall", object.objectId(), object.dimension(), object.pos(),
                CodPatternBlockRegister.ZOMBIES_WEAPON_WALL_BOX.get(), object.facing())));
        resolved.ammoBoxes().forEach(object -> placements.add(new Placement(
                "ammo_box", object.objectId(), object.dimension(), object.pos(),
                CodPatternBlockRegister.ZOMBIES_AMMO_BOX.get(), object.facing())));
        resolved.armorStations().forEach(object -> placements.add(new Placement(
                "armor_station", object.objectId(), object.dimension(), object.pos(),
                CodPatternBlockRegister.ZOMBIES_ARMOR_STATION_BOX.get(), object.facing())));
        resolved.powerSwitch().ifPresent(object -> placements.add(new Placement(
                "power_switch", object.objectId(), object.dimension(), object.pos(),
                CodPatternBlockRegister.ZOMBIES_POWER_SWITCH.get(), Direction.NORTH)));
        resolved.sodaMachines().forEach(object -> placements.add(new Placement(
                "soda_machine", object.objectId(), object.dimension(), object.pos(),
                CodPatternBlockRegister.ZOMBIES_SODA_MACHINE_BOX.get(), object.facing())));
        resolved.ultimateMachines().forEach(object -> placements.add(new Placement(
                "ultimate_machine", object.objectId(), object.dimension(), object.pos(),
                CodPatternBlockRegister.ZOMBIES_ULTIMATE_MACHINE_BOX.get(), object.facing())));
        resolved.mysteryBoxes().forEach(object -> placements.add(new Placement(
                "mystery_box", object.objectId(), object.dimension(), object.pos(),
                CodPatternBlockRegister.ZOMBIES_MYSTERY_BOX.get(), object.facing())));
        return List.copyOf(placements);
    }

    /**
     * Applies the complete saved state, including missing blocks at unchanged positions.
     * The returned journal also lets the caller undo this operation if persistence fails.
     */
    public static PlacementChanges synchronize(
            ZombiesMapObjects previous,
            ZombiesMapObjects next,
            Function<ResourceKey<Level>, ServerLevel> levels
    ) {
        Objects.requireNonNull(levels, "Level resolver");
        Map<Location, Placement> before = indexedPlacements(previous);
        Map<Location, Placement> after = indexedPlacements(next);
        Map<Location, BlockChange> planned = new LinkedHashMap<>();

        for (Map.Entry<Location, Placement> entry : before.entrySet()) {
            Placement old = entry.getValue();
            Placement replacement = after.get(entry.getKey());
            if (replacement != null && replacement.block() == old.block()) {
                continue;
            }
            ServerLevel level = requireLevel(levels, old);
            BlockState current = level.getBlockState(old.pos());
            if (current.getBlock() == old.block()) {
                planned.put(entry.getKey(), new BlockChange(level, old.pos(), current, Blocks.AIR.defaultBlockState()));
            }
        }

        for (Map.Entry<Location, Placement> entry : after.entrySet()) {
            Placement placement = entry.getValue();
            ServerLevel level = requireLevel(levels, placement);
            BlockState current = level.getBlockState(placement.pos());
            BlockState desired = desiredState(placement, current);
            if (current.equals(desired)) {
                continue;
            }
            BlockChange removal = planned.get(entry.getKey());
            if (removal == null && current.getBlock() != placement.block() && !replaceable(current)) {
                throw new IllegalStateException("Cannot place zombies purchase point over " + current
                        + " at " + placement.dimension().location() + " " + placement.pos());
            }
            planned.put(entry.getKey(), new BlockChange(
                    level, placement.pos(), current, desired));
        }
        return applyChanges(planned.values().stream().toList());
    }

    /** Recreates missing saved objects on startup, leaving solid map construction intact. */
    public static PlacementChanges ensureMissingObjects(
            ZombiesMapObjects objects,
            Function<ResourceKey<Level>, ServerLevel> levels
    ) {
        Objects.requireNonNull(levels, "Level resolver");
        List<BlockChange> planned = new ArrayList<>();
        for (Placement placement : indexedPlacements(objects).values()) {
            ServerLevel level = levels.apply(placement.dimension());
            if (level == null || !level.isInWorldBounds(placement.pos())) {
                continue;
            }
            BlockState current = level.getBlockState(placement.pos());
            if (current.getBlock() != placement.block() && replaceable(current)) {
                planned.add(new BlockChange(level, placement.pos(), current, desiredState(placement, current)));
            }
        }
        return applyChanges(planned);
    }

    private static BlockState desiredState(Placement placement, BlockState current) {
        // Keep other block properties (for example a power switch's powered state).
        BlockState state = current.is(placement.block()) ? current : placement.block().defaultBlockState();
        return state.hasProperty(ZombiesBoxInteractionBlock.FACING)
                ? state.setValue(ZombiesBoxInteractionBlock.FACING, placement.facing()) : state;
    }

    private static boolean replaceable(BlockState state) {
        return state.isAir() || state.canBeReplaced();
    }

    private static Map<Location, Placement> indexedPlacements(ZombiesMapObjects objects) {
        Map<Location, Placement> result = new LinkedHashMap<>();
        for (Placement placement : placements(objects)) {
            Location location = new Location(placement.dimension(), placement.pos());
            Placement existing = result.putIfAbsent(location, placement);
            if (existing != null && (existing.block() != placement.block() || existing.facing() != placement.facing())) {
                throw new IllegalStateException("Conflicting zombies purchase points at "
                        + placement.dimension().location() + " " + placement.pos());
            }
        }
        return result;
    }

    private static ServerLevel requireLevel(
            Function<ResourceKey<Level>, ServerLevel> levels,
            Placement placement
    ) {
        ServerLevel level = levels.apply(placement.dimension());
        if (level == null || !level.dimension().equals(placement.dimension())) {
            throw new IllegalStateException("Missing purchase point dimension " + placement.dimension().location());
        }
        if (!level.isInWorldBounds(placement.pos())) {
            throw new IllegalStateException("Purchase point is outside world bounds: " + placement.pos());
        }
        return level;
    }

    private static PlacementChanges applyChanges(List<BlockChange> planned) {
        PlacementChanges changes = new PlacementChanges();
        try {
            for (BlockChange change : planned) {
                // Neighbour updates from an earlier placement can change a later target.
                BlockState current = change.level().getBlockState(change.pos());
                if (current.equals(change.next())) {
                    continue;
                }
                if (!current.equals(change.previous())) {
                    throw new IllegalStateException("Purchase point target changed at " + change.pos());
                }
                changes.changes.add(change);
                if (!change.level().setBlock(change.pos(), change.next(), Block.UPDATE_ALL)) {
                    throw new IllegalStateException("Failed to update purchase point at " + change.pos());
                }
            }
            return changes;
        } catch (RuntimeException failure) {
            try {
                changes.rollback();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private record Location(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private record BlockChange(ServerLevel level, BlockPos pos, BlockState previous, BlockState next) {
    }

    public static final class PlacementChanges {
        private final List<BlockChange> changes = new ArrayList<>();
        private boolean restored;

        private PlacementChanges() {
        }

        public void rollback() {
            if (restored) {
                return;
            }
            RuntimeException failure = null;
            for (int index = changes.size() - 1; index >= 0; index--) {
                BlockChange change = changes.get(index);
                try {
                    if (!change.level().getBlockState(change.pos()).equals(change.previous())
                            && !change.level().setBlock(change.pos(), change.previous(), Block.UPDATE_ALL)) {
                        throw new IllegalStateException("Failed to restore purchase point at " + change.pos());
                    }
                } catch (RuntimeException rollbackFailure) {
                    if (failure == null) {
                        failure = rollbackFailure;
                    } else {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
            restored = true;
        }
    }
}
