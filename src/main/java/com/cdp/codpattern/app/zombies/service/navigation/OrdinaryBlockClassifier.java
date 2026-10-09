package com.cdp.codpattern.app.zombies.service.navigation;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.extensions.IForgeBlock;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recognizes full cubes whose inherited movement behavior is ordinary. The class proof is
 * shared, while state properties and reloadable tags are checked on every use. Unknown
 * collision/path/contact overrides retain the shape-based traversal validator.
 */
public final class OrdinaryBlockClassifier {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean REPORTED_LOOKUP_FAILURE = new AtomicBoolean();
    private static final List<Method> ORDINARY_METHODS = ordinaryMethods();
    private static final ClassValue<Boolean> ORDINARY_BEHAVIOR = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if (ORDINARY_METHODS.isEmpty()) return false;
            try {
                for (Method ordinary : ORDINARY_METHODS) {
                    Method actual = type.getMethod(ordinary.getName(), ordinary.getParameterTypes());
                    if (actual.getDeclaringClass() != ordinary.getDeclaringClass()) return false;
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                reportLookupFailure(failure);
                return false;
            }
        }
    };

    private OrdinaryBlockClassifier() { }

    /** Class-only proof; callers still need {@link #isOrdinarySolid} for a usable floor state. */
    public static boolean hasOrdinaryBehavior(Class<? extends Block> blockClass) {
        return ORDINARY_BEHAVIOR.get(blockClass);
    }

    public static boolean isOrdinarySolid(BlockState state, BlockGetter level, BlockPos pos) {
        // Custom BlockState implementations could bypass the proven Block methods.
        if (state.getClass() != BlockState.class || state.isAir()) return false;
        Block block = state.getBlock();
        if (!ORDINARY_BEHAVIOR.get(block.getClass()) || block.hasDynamicShape()
                || state.hasOffsetFunction() || !state.getFluidState().isEmpty()
                || block.getFriction() != 0.6F || block.getSpeedFactor() != 1.0F
                || block.getJumpFactor() != 1.0F || hasSpecialPathTags(state)) return false;

        // Default getShape/getCollisionShape are context independent. This overload reuses
        // Minecraft's per-state cache; no world shape callback or shape-list allocation is
        // needed for accepted states. Compare the exact volume, not merely its bounding box.
        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape == Shapes.block()
                || !Shapes.joinIsNotEmpty(shape, Shapes.block(), BooleanOp.NOT_SAME);
    }

    static boolean hasSpecialPathTags(BlockState state) {
        // Do not cache these in the class proof: a data-pack reload may change membership.
        return WalkNodeEvaluator.isBurningBlock(state) || state.is(BlockTags.FENCES)
                || state.is(BlockTags.WALLS) || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.CLIMBABLE) || state.is(BlockTags.SOUL_SPEED_BLOCKS);
    }

    private static List<Method> ordinaryMethods() {
        try {
            // SRG names are resolved by Forge in both the development and reobfuscated jar.
            // Requiring both methods also excludes shape overrides delegated to by the
            // default collision method. Render, drops and placement behavior are irrelevant.
            return List.of(
                    vanilla(BlockBehaviour.class, "m_5940_", BlockState.class, BlockGetter.class,
                            BlockPos.class, CollisionContext.class), // getShape
                    vanilla(BlockBehaviour.class, "m_5939_", BlockState.class, BlockGetter.class,
                            BlockPos.class, CollisionContext.class), // getCollisionShape
                    vanilla(BlockBehaviour.class, "m_7357_", BlockState.class, BlockGetter.class,
                            BlockPos.class, PathComputationType.class), // isPathfindable
                    vanilla(BlockBehaviour.class, "m_7947_", BlockState.class, BlockGetter.class,
                            BlockPos.class), // getBlockSupportShape (including leaves)
                    vanilla(BlockBehaviour.class, "m_5888_", BlockState.class), // getFluidState
                    vanilla(BlockBehaviour.class, "m_7892_", BlockState.class, Level.class,
                            BlockPos.class, Entity.class), // entityInside
                    vanilla(Block.class, "m_141947_", Level.class, BlockPos.class,
                            BlockState.class, Entity.class), // stepOn
                    vanilla(Block.class, "m_142072_", Level.class, BlockState.class,
                            BlockPos.class, Entity.class, float.class), // fallOn
                    vanilla(Block.class, "m_5548_", BlockGetter.class, Entity.class), // updateEntityAfterFallOn
                    vanilla(Block.class, "m_49958_"), // getFriction
                    vanilla(Block.class, "m_49961_"), // getSpeedFactor
                    vanilla(Block.class, "m_49964_"), // getJumpFactor
                    vanilla(Block.class, "m_49967_"), // hasDynamicShape
                    forge("getBlockPathType", BlockState.class, BlockGetter.class, BlockPos.class, Mob.class),
                    forge("getAdjacentBlockPathType", BlockState.class, BlockGetter.class,
                            BlockPos.class, Mob.class, BlockPathTypes.class),
                    forge("isBurning", BlockState.class, BlockGetter.class, BlockPos.class),
                    forge("getFriction", BlockState.class, LevelReader.class, BlockPos.class, Entity.class),
                    forge("isLadder", BlockState.class, LevelReader.class, BlockPos.class, LivingEntity.class));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reportLookupFailure(failure);
            return List.of();
        }
    }

    private static Method vanilla(Class<?> owner, String srgName, Class<?>... arguments) {
        return ObfuscationReflectionHelper.findMethod(owner, srgName, arguments);
    }

    private static Method forge(String name, Class<?>... arguments) throws NoSuchMethodException {
        return IForgeBlock.class.getMethod(name, arguments);
    }

    private static void reportLookupFailure(Exception failure) {
        if (REPORTED_LOOKUP_FAILURE.compareAndSet(false, true)) {
            LOGGER.warn("Unable to verify ordinary block behavior; using shape-based navigation", failure);
        }
    }
}
