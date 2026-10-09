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
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.extensions.IForgeBlock;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recognizes full cubes whose inherited movement behavior is ordinary. The class proof is
 * shared, while state properties and reloadable tags are checked on every use. Unknown
 * collision/path/contact overrides retain the shape-based traversal validator.
 */
public final class OrdinaryBlockClassifier {
    public enum BehaviorKind { UNSUPPORTED, DEFAULT, YUUSHYA_STATE_SHAPE }
    private static final String YUUSHYA_FACTORY = "com.yuushya.block.YuushyaBlockFactory$";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean REPORTED_LOOKUP_FAILURE = new AtomicBoolean();
    private static final List<Method> ORDINARY_METHODS = ordinaryMethods();
    private static final ClassValue<BehaviorKind> ORDINARY_BEHAVIOR = new ClassValue<>() {
        @Override protected BehaviorKind computeValue(Class<?> type) {
            if (ORDINARY_METHODS.isEmpty()) return BehaviorKind.UNSUPPORTED;
            try {
                boolean yuushya = isAuditedYuushyaType(type);
                for (Method ordinary : ORDINARY_METHODS) {
                    Method actual = type.getMethod(ordinary.getName(), ordinary.getParameterTypes());
                    if (actual.getDeclaringClass() != ordinary.getDeclaringClass()
                            && !(yuushya && isAuditedYuushyaOverride(type, ordinary, actual)))
                        return BehaviorKind.UNSUPPORTED;
                }
                return yuushya ? BehaviorKind.YUUSHYA_STATE_SHAPE : BehaviorKind.DEFAULT;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                reportLookupFailure(failure);
                return BehaviorKind.UNSUPPORTED;
            }
        }
    };

    private OrdinaryBlockClassifier() { }

    /** Class-only proof; callers still need {@link #isOrdinarySolid} for a usable floor state. */
    public static boolean hasOrdinaryBehavior(Class<? extends Block> blockClass) {
        return behaviorKind(blockClass) != BehaviorKind.UNSUPPORTED;
    }

    public static BehaviorKind behaviorKind(Class<? extends Block> blockClass) {
        return ORDINARY_BEHAVIOR.get(blockClass);
    }

    public static boolean isOrdinarySolid(BlockState state, BlockGetter level, BlockPos pos) {
        return ordinarySolidKind(state, level, pos) != BehaviorKind.UNSUPPORTED;
    }

    public static BehaviorKind ordinarySolidKind(BlockState state, BlockGetter level, BlockPos pos) {
        BehaviorKind kind = eligibleStateKind(state);
        if (kind == BehaviorKind.UNSUPPORTED) return kind;
        return exactUnitCube(actualShape(state, level, pos, kind)) ? kind : BehaviorKind.UNSUPPORTED;
    }

    private static VoxelShape actualShape(BlockState state, BlockGetter level, BlockPos pos, BehaviorKind kind) {
        // Yuushya initializes its state-indexed shapes after some vanilla state caches have
        // been built. Invoke the audited method, not the no-context state-cache overload.
        return kind == BehaviorKind.YUUSHYA_STATE_SHAPE
                ? state.getCollisionShape(level, pos, CollisionContext.empty())
                : state.getCollisionShape(level, pos);
    }

    /** One synchronous advance call only; never retain this evidence across a yield. */
    static final class Batch {
        private BlockState lastYuushyaState;
        private VoxelShape lastActualShape;
        private long shapeReads,shapeBatchHits;

        long shapeReads() { return shapeReads; }
        long shapeBatchHits() { return shapeBatchHits; }

        BehaviorKind classify(BlockState state, BlockGetter level, BlockPos pos) {
            // Position-independent geometry is the only reused result. Current tags, fluid
            // and movement properties are still checked for every cell, including hits.
            BehaviorKind kind = eligibleStateKind(state);
            if (kind == BehaviorKind.UNSUPPORTED) {
                clear();
                return kind;
            }
            VoxelShape shape;
            if (kind == BehaviorKind.YUUSHYA_STATE_SHAPE) {
                if (state != lastYuushyaState) {
                    VoxelShape observed = actualShape(state, level, pos, kind);
                    shapeReads++;
                    lastYuushyaState = state;
                    lastActualShape = observed;
                } else shapeBatchHits++;
                shape = lastActualShape;
            } else {
                shape = actualShape(state, level, pos, kind);
            }
            return exactUnitCube(shape) ? kind : BehaviorKind.UNSUPPORTED;
        }

        void clear() {
            lastYuushyaState = null;
            lastActualShape = null;
        }
    }

    /** Reuses the actual shape already acquired by ShapeScan; unknown behavior still fails. */
    static BehaviorKind ordinarySolidKind(BlockState state, VoxelShape actualShape) {
        BehaviorKind kind = eligibleStateKind(state);
        return kind != BehaviorKind.UNSUPPORTED && exactUnitCube(actualShape)
                ? kind : BehaviorKind.UNSUPPORTED;
    }

    private static BehaviorKind eligibleStateKind(BlockState state) {
        // Custom BlockState implementations could bypass the proven Block methods.
        if (state.getClass() != BlockState.class || state.isAir()) return BehaviorKind.UNSUPPORTED;
        Block block = state.getBlock();
        BehaviorKind kind = behaviorKind(block.getClass());
        if (kind == BehaviorKind.UNSUPPORTED || block.hasDynamicShape() || state.hasOffsetFunction()
                || !state.getFluidState().isEmpty()
                || block.getFriction() != 0.6F || block.getSpeedFactor() != 1.0F
                || block.getJumpFactor() != 1.0F || hasSpecialPathTags(state)) return BehaviorKind.UNSUPPORTED;
        // The audited override depends only on WATERLOGGED, but read it directly as well:
        // a pre-registration vanilla cache must not turn a waterlogged state into dry support.
        if (kind == BehaviorKind.YUUSHYA_STATE_SHAPE && !block.getFluidState(state).isEmpty())
            return BehaviorKind.UNSUPPORTED;
        return kind;
    }

    private static boolean exactUnitCube(VoxelShape shape) {
        // Vanilla's bounded weak-identity cache uses the same exact NOT_SAME volume test.
        return shape == Shapes.block() || Block.isShapeFullBlock(shape);
    }

    private static boolean isAuditedYuushyaType(Class<?> type) {
        String name = type.getName();
        if (!name.equals(YUUSHYA_FACTORY + "BlockWithClassTypeNormal")
                && !name.equals(YUUSHYA_FACTORY + "BlockWithClassTypeWaterLogged")) return false;
        ModList mods = ModList.get();
        if (mods == null) return false;
        var container = mods.getModContainerById("yuushya");
        if (container.isEmpty()
                || !container.get().getModInfo().getVersion().toString().equals("2.2.3")) return false;
        Object mod = container.get().getMod();
        // An equal class name from another jar is not the reviewed Yuushya implementation.
        return mod != null && type.getClassLoader() == mod.getClass().getClassLoader()
                && sameCodeSource(type, mod.getClass());
    }

    private static boolean isAuditedYuushyaOverride(Class<?> type, Method ordinary, Method actual) {
        Class<?> declaring = actual.getDeclaringClass();
        if (!declaring.getName().equals(YUUSHYA_FACTORY + "BlockWithClassType")
                || !sameCodeSource(type, declaring)) return false;
        // In the reviewed 2.2.3 factory, only these two four-argument shape methods and the
        // state-only fluid method may differ. Path/contact/movement methods keep every guard.
        Class<?>[] parameters = ordinary.getParameterTypes();
        return ordinary.getReturnType() == VoxelShape.class && parameters.length == 4
                && parameters[0] == BlockState.class && parameters[1] == BlockGetter.class
                && parameters[2] == BlockPos.class && parameters[3] == CollisionContext.class
                || ordinary.getReturnType() == FluidState.class && parameters.length == 1
                && parameters[0] == BlockState.class;
    }

    private static boolean sameCodeSource(Class<?> first, Class<?> second) {
        CodeSource firstSource = first.getProtectionDomain().getCodeSource();
        CodeSource secondSource = second.getProtectionDomain().getCodeSource();
        return firstSource != null && secondSource != null && firstSource.getLocation() != null
                && firstSource.getLocation().equals(secondSource.getLocation());
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
