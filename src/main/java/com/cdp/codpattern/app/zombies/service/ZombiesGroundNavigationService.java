package com.cdp.codpattern.app.zombies.service;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.SwellGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Room-local work limits; entities keep their native combat goals and navigator. */
public final class ZombiesGroundNavigationService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean DEBUG = Boolean.getBoolean("codpattern.zombies.navigationDebug");
    static final int STALL_TICKS = 40;
    static final int RECOVERY_TICKS = 120;
    static final int MOVEMENT_HANDOFF_TICKS = 20;
    static final int MAX_RECOVERY_ATTEMPTS = 4;
    static final int MAX_LOCAL_CANDIDATES = 12;
    static final int EXTRA_SEARCHES_PER_ROOM_TICK = 2;
    private static final int[][] DIRECTIONS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private final Supplier<List<ServerPlayer>> targets;
    private long budgetTick = Long.MIN_VALUE;
    private int searchesThisTick;
    private long searches;
    private long searchNanos;
    private long recoveries;
    private final Map<UUID, Long> waitingForSearch = new LinkedHashMap<>();
    private long snapshotTick = Long.MIN_VALUE;
    private List<ServerPlayer> targetSnapshot = List.of();

    public ZombiesGroundNavigationService(Supplier<List<ServerPlayer>> targets) {
        this.targets = targets == null ? List::of : targets;
    }

    public static boolean supports(Mob mob) {
        if (!(mob instanceof PathfinderMob) || !(mob.getNavigation() instanceof GroundPathNavigation)) {
            return false;
        }
        EntityType<?> type = mob.getType();
        return type == EntityType.ZOMBIE || type == EntityType.HUSK || type == EntityType.WITHER_SKELETON
                || type == EntityType.CREEPER || type == EntityType.WOLF || type == EntityType.SILVERFISH
                || type == EntityType.VINDICATOR;
    }

    public void install(Mob mob, double initialFollowRange) {
        if (!supports(mob) || observer(mob) != null) {
            return;
        }
        PathfinderMob pathfinder = (PathfinderMob) mob;
        State state = new State(pathfinder, initialFollowRange);
        mob.goalSelector.addGoal(0, new ObserverGoal(pathfinder, state));
        mob.goalSelector.addGoal(recoveryPriority(mob), new RecoveryGoal(pathfinder, state));
    }

    public void trackSpawn(Mob mob, ZombiesGroundSpawnPolicy policy, String spawnId) {
        ObserverGoal observer = observer(mob);
        if (observer != null) {
            observer.state.spawnPolicy = policy;
            observer.state.spawnId = spawnId;
        }
    }

    public static void onRecycled(Mob mob) {
        ObserverGoal observer = observer(mob);
        if (observer != null && observer.state.spawnPolicy != null) {
            observer.state.spawnPolicy.recordFailure(observer.state.spawnId, mob, mob.level().getGameTime());
        }
    }

    public static ProgressSnapshot getProgress(Mob mob) {
        if (mob == null || mob.isPassenger()) {
            return null; // Riding uses native movement/reach rules and the existing recycling policy.
        }
        ObserverGoal observer = observer(mob);
        return observer == null ? null : new ProgressSnapshot(observer.state.progress.getLastProgressGameTime(),
                observer.state.lastEngagement, observer.state.actionGraceUntil);
    }

    public record ProgressSnapshot(long lastProgressGameTime, long lastEngagementGameTime,
                                   long actionGraceUntilGameTime) { }

    public record SearchMetrics(long searches, long searchNanos, long recoveries) { }

    public SearchMetrics metrics() {
        return new SearchMetrics(searches, searchNanos, recoveries);
    }

    public void reset() {
        targetSnapshot = List.of();
        snapshotTick = Long.MIN_VALUE;
        budgetTick = Long.MIN_VALUE;
        waitingForSearch.clear();
        searchesThisTick = 0;
        searches = 0;
        searchNanos = 0;
        recoveries = 0;
    }

    public void reportSpawnValidation(Mob mob, String spawnId, ZombiesGroundSpawnPolicy.Validation validation) {
        if (DEBUG && !validation.reason().isEmpty()) {
            LOGGER.info("Zombies ground spawn point={} type={} position={} allowed={} reason={}",
                    spawnId, mob.getType(), mob.position(), validation.allowed(), validation.reason());
        }
    }

    private static ObserverGoal observer(Mob mob) {
        if (mob == null) {
            return null;
        }
        for (WrappedGoal goal : mob.goalSelector.getAvailableGoals()) {
            if (goal.getGoal() instanceof ObserverGoal observer) {
                return observer;
            }
        }
        return null;
    }

    /** The snapshot lasts one server tick, so room exit/downing is observed on the next tick. */
    public List<ServerPlayer> targetsFor(Mob mob) {
        long now = mob.level().getGameTime();
        if (snapshotTick != now) {
            snapshotTick = now;
            try {
                List<ServerPlayer> supplied = targets.get();
                targetSnapshot = supplied == null ? List.of() : supplied.stream().filter(Objects::nonNull).toList();
            } catch (RuntimeException ignored) {
                targetSnapshot = List.of();
            }
        }
        return targetSnapshot;
    }

    private boolean eligible(Mob mob, LivingEntity target) {
        return target instanceof ServerPlayer player && target.isAlive() && !target.isRemoved()
                && !player.isSpectator() && target.level() == mob.level()
                && targetsFor(mob).contains(player)
                && ZombiesMobSpawnService.isEligibleRoomSurvivor(mob, player);
    }

    private boolean takeSearch(Mob mob, long now) {
        if (budgetTick != now) {
            budgetTick = now;
            searchesThisTick = 0;
        }
        waitingForSearch.entrySet().removeIf(entry -> now - entry.getValue() > 20);
        waitingForSearch.put(mob.getUUID(), now);
        if (searchesThisTick >= EXTRA_SEARCHES_PER_ROOM_TICK
                || !waitingForSearch.keySet().iterator().next().equals(mob.getUUID())) {
            return false;
        }
        waitingForSearch.remove(mob.getUUID());
        searchesThisTick++;
        return true;
    }

    private static int recoveryPriority(Mob mob) {
        if (mob.getType() == EntityType.ZOMBIE || mob.getType() == EntityType.HUSK || mob instanceof Creeper) {
            return 1; // Below float; equal to the already-registered optional door goal.
        }
        return mob instanceof Wolf ? 4 : 3;
    }

    private static boolean specialAction(Mob mob) {
        if (mob.isPassenger() || mob.isInWater() || mob.isInLava()) {
            return true;
        }
        if (mob instanceof Creeper creeper) {
            LivingEntity target = mob.getTarget();
            if (creeper.isIgnited() || creeper.getSwellDir() > 0
                    || (target != null && mob.distanceToSqr(target) < 9.0D
                    && mob.getSensing().hasLineOfSight(target))) {
                return true;
            }
        }
        // Inspect ownership rather than replacing goals: skeleton weapon reassessment and
        // species-specific melee subclasses continue to work with the original goal objects.
        return mob.goalSelector.getRunningGoals().anyMatch(wrapped -> {
            Goal goal = wrapped.getGoal();
            return !(goal instanceof RecoveryGoal) && !(goal instanceof MeleeAttackGoal)
                    && !(goal instanceof RangedBowAttackGoal<?>)
                    && !(goal instanceof SwellGoal) // A defused swell behind a floor must allow recovery.
                    && (goal instanceof DoorInteractGoal || goal.getFlags().contains(Goal.Flag.MOVE)
                    || goal.getFlags().contains(Goal.Flag.JUMP));
        });
    }

    private static boolean engaged(Mob mob, LivingEntity target) {
        if (target == null || !mob.getSensing().hasLineOfSight(target)) {
            return false;
        }
        if (mob instanceof Creeper) {
            // A stalled swell goal is not combat progress. Its finite grace is handled separately.
            return false;
        }
        if (mob.getType() == EntityType.WITHER_SKELETON
                && (mob.getMainHandItem().getItem() instanceof BowItem
                || mob.getOffhandItem().getItem() instanceof BowItem)) {
            return mob.distanceToSqr(target) <= 15.0D * 15.0D;
        }
        double width = mob.getBbWidth() * 2.0D;
        return mob.distanceToSqr(target) <= width * width + target.getBbWidth();
    }

    private static boolean blockedCreeperSwell(Mob mob) {
        LivingEntity target = mob.getTarget();
        return mob instanceof Creeper && target != null && mob.distanceToSqr(target) < 9.0D
                && !mob.getSensing().hasLineOfSight(target);
    }

    private static boolean safeRoute(Path path) {
        if (path == null || path.getEndNode() == null) {
            return false;
        }
        // The native evaluator checks full body clearance, support, step height and fall distance.
        // Do not add impulses or authorize a direct move from endpoint clearance alone.
        for (int i = 0; i < path.getNodeCount(); i++) {
            BlockPathTypes type = path.getNode(i).type;
            if (type == BlockPathTypes.LAVA || type == BlockPathTypes.DAMAGE_FIRE
                    || type == BlockPathTypes.DAMAGE_OTHER || type == BlockPathTypes.DANGER_FIRE
                    || type == BlockPathTypes.DANGER_OTHER || type == BlockPathTypes.WATER) {
                return false;
            }
        }
        return true;
    }

    private static final class State {
        private final ZombiesNavigationProgressTracker progress;
        private final int initialNodeBudget;
        private UUID targetId;
        private Vec3 targetAnchor;
        private long lastEngagement;
        private long actionGraceUntil;
        private long nextActionGrace;
        private boolean ignitionProtected;
        private float previousSwelling;
        private boolean inSpecialAction;
        private boolean wasPassenger;
        private long nextRecovery;
        private long nextTargetSwitch;
        private int attempts;
        private ZombiesGroundSpawnPolicy spawnPolicy;
        private String spawnId;
        private Path previousPath;
        private int previousNode;
        private double previousNodeDistance;
        private final Map<BlockPos, Long> failedEndpoints = new LinkedHashMap<>();

        private State(Mob mob, double initialFollowRange) {
            long now = mob.level().getGameTime();
            // PathNavigation fixes this budget during entity construction, before room retention raises range.
            initialNodeBudget = Math.max(16, (int) (initialFollowRange * 16.0D));
            progress = new ZombiesNavigationProgressTracker(now, mob.getX(), mob.getY(), mob.getZ());
            lastEngagement = now;
            nextRecovery = now + STALL_TICKS + Math.floorMod(mob.getId(), 20);
        }

        private void rememberFailure(BlockPos pos, long now) {
            failedEndpoints.entrySet().removeIf(entry -> now - entry.getValue() > 600);
            failedEndpoints.put(pos.immutable(), now);
            if (failedEndpoints.size() > 24) {
                failedEndpoints.remove(failedEndpoints.keySet().iterator().next());
            }
        }

        private boolean failedRecently(BlockPos pos, long now) {
            Long failed = failedEndpoints.get(pos);
            return failed != null && now - failed < 600;
        }
    }

    private final class ObserverGoal extends Goal {
        private final PathfinderMob mob;
        private final State state;

        private ObserverGoal(PathfinderMob mob, State state) {
            this.mob = mob;
            this.state = state;
            setFlags(EnumSet.noneOf(Flag.class));
        }

        @Override public boolean canUse() { return mob.isAlive(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public void tick() {
            long now = mob.level().getGameTime();
            LivingEntity target = mob.getTarget();
            if (mob.isPassenger()) {
                state.wasPassenger = true;
            } else if (state.wasPassenger) {
                state.wasPassenger = false;
                if (now >= state.nextActionGrace) {
                    state.actionGraceUntil = now + STALL_TICKS;
                    state.nextActionGrace = now + 320;
                }
            }
            if (!eligible(mob, target)) {
                state.progress.observe(now, mob.getX(), mob.getY(), mob.getZ(), false);
                return;
            }
            if (!target.getUUID().equals(state.targetId)) {
                state.targetId = target.getUUID();
                state.targetAnchor = target.position();
                state.attempts = 0;
                state.failedEndpoints.clear();
            } else if (state.targetAnchor.distanceToSqr(target.position()) >= 16.0D) {
                state.targetAnchor = target.position();
                state.failedEndpoints.clear();
                state.attempts = 0;
            }
            boolean special = specialAction(mob);
            if (special && !state.inSpecialAction && now >= state.nextActionGrace) {
                // Protect a newly committed action even after a long stall, without letting goal
                // toggles renew grace on every scan. Recovery evidence has its own independent clock.
                state.actionGraceUntil = now + 60;
                state.nextActionGrace = now + 320;
            }
            state.inSpecialAction = special;
            if (mob instanceof Creeper creeper) {
                // Ignition is irreversible for this entity: an unrelated avoidance/idle action
                // must not consume its one-time fuse protection window.
                if (creeper.isIgnited() && !state.ignitionProtected) {
                    state.ignitionProtected = true;
                    state.actionGraceUntil = Math.max(state.actionGraceUntil, now + 60);
                }
                float swelling = creeper.getSwelling(1.0F);
                if (swelling > state.previousSwelling
                        && (creeper.isIgnited() || mob.getSensing().hasLineOfSight(target))) {
                    state.lastEngagement = now;
                }
                state.previousSwelling = swelling;
            }
            Path path = mob.getNavigation().getPath();
            double nodeDistance = path == null || path.isDone() ? Double.POSITIVE_INFINITY
                    : mob.position().distanceToSqr(path.getNextEntityPos(mob));
            boolean routeAdvanced = path != null && !path.isDone() && path == state.previousPath
                    && (path.getNextNodeIndex() > state.previousNode
                    || nodeDistance + 1.0E-5D < state.previousNodeDistance);
            if (path == null || path.isDone() || special) {
                state.progress.observe(now, mob.getX(), mob.getY(), mob.getZ(), false);
            } else if (routeAdvanced) {
                state.progress.observe(now, mob.getX(), mob.getY(), mob.getZ(), true);
            }
            state.previousPath = path;
            state.previousNode = path == null ? 0 : path.getNextNodeIndex();
            state.previousNodeDistance = nodeDistance;
            if (engaged(mob, target)) {
                state.lastEngagement = now;
                state.attempts = 0;
                state.failedEndpoints.clear();
            }
        }
    }

    private final class RecoveryGoal extends Goal {
        private final PathfinderMob mob;
        private final State state;
        private long deadline;
        private long routeStarted;
        private int candidateIndex;
        private int alternativeIndex;
        private Path route;
        private Vec3 origin;
        private UUID routeTarget;
        private boolean finished;
        private boolean targetSearchPending;
        private boolean localRoute;
        private boolean alternativesDone;
        private boolean startedSearching;

        private RecoveryGoal(PathfinderMob mob, State state) {
            this.mob = mob;
            this.state = state;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            long now = mob.level().getGameTime();
            return now >= state.nextRecovery && state.attempts < MAX_RECOVERY_ATTEMPTS
                    && now - Math.max(state.progress.getLastProgressGameTime(), state.lastEngagement) >= STALL_TICKS
                    && mob.onGround() && eligible(mob, mob.getTarget())
                    && !specialAction(mob) && !engaged(mob, mob.getTarget());
        }

        @Override
        public boolean canContinueToUse() {
            long now = mob.level().getGameTime();
            boolean advancing = route != null && !mob.getNavigation().isDone()
                    && now - Math.max(routeStarted, state.progress.getLastProgressGameTime()) < STALL_TICKS;
            // Give equal-priority leap/avoidance goals another selector opportunity during longer
            // recovery routes. A nearby, defused creeper must first escape the swell-start radius.
            boolean handoffDue = route != null && now - routeStarted >= MOVEMENT_HANDOFF_TICKS
                    && !blockedCreeperSwell(mob);
            return !finished && !handoffDue && (!startedSearching || now < deadline || advancing)
                    && eligible(mob, mob.getTarget()) && !specialAction(mob)
                    && !engaged(mob, mob.getTarget()) && mob.getTarget().getUUID().equals(routeTarget);
        }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public void start() {
            long now = mob.level().getGameTime();
            deadline = now + RECOVERY_TICKS;
            candidateIndex = 0;
            alternativeIndex = 0;
            origin = mob.position();
            routeTarget = mob.getTarget().getUUID();
            targetSearchPending = true;
            finished = false;
            localRoute = false;
            startedSearching = false;
            alternativesDone = state.attempts < 1 || now < state.nextTargetSwitch;
            route = null;
            mob.getNavigation().stop();
            diagnose("stalled", now, null);
        }

        @Override
        public void tick() {
            if (!canContinueToUse()) {
                return;
            }
            long now = mob.level().getGameTime();
            LivingEntity target = mob.getTarget();
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            if (route != null) {
                if (route.canReach() && !localRoute && !blockedCreeperSwell(mob)) {
                    finished = true;
                    return;
                }
                if (!mob.getNavigation().isDone()
                        && now - Math.max(routeStarted, state.progress.getLastProgressGameTime()) < STALL_TICKS) {
                    // Navigation itself updates every game tick, including native jumping and descent.
                    return;
                }
                if (mob.getNavigation().isDone() && route.canReach() && !localRoute) {
                    finished = true; // Let the original species attack goal take over.
                    return;
                }
                state.rememberFailure(route.getEndNode().asBlockPos(), now);
                route = null;
                mob.getNavigation().stop();
                if (localRoute) {
                    finished = true;
                    return;
                }
            }
            if (!mob.onGround()) {
                return;
            }
            if (!takeSearch(mob, now)) {
                deadline++; // Waiting for room work capacity is not a failed navigation attempt.
                return;
            }
            if (!startedSearching) {
                startedSearching = true;
                state.attempts++;
                recoveries++;
                deadline = now + RECOVERY_TICKS;
            }
            if (targetSearchPending) {
                targetSearchPending = false;
                Path path = search(target.blockPosition(), searchRange(target), true);
                if (accept(path, now, false)) {
                    return;
                }
            } else if (!alternativesDone) {
                List<ServerPlayer> alternatives = targetsFor(mob).stream()
                        .filter(player -> player != target && eligible(mob, player))
                        .sorted(Comparator.comparingDouble(mob::distanceToSqr)).limit(3).toList();
                if (alternativeIndex < alternatives.size()) {
                    ServerPlayer alternative = alternatives.get(alternativeIndex++);
                    Path path = search(alternative.blockPosition(), searchRange(alternative), true);
                    if (path != null && path.canReach() && accept(path, now, false)) {
                        ZombiesMobSpawnService.setRecoveredRoomTarget(mob, alternative);
                        routeTarget = alternative.getUUID();
                        state.nextTargetSwitch = now + 200;
                        diagnose("target-switch", now, path);
                    }
                } else {
                    alternativesDone = true;
                }
            } else if (candidateIndex < MAX_LOCAL_CANDIDATES) {
                BlockPos candidate = nextCandidate();
                if (mob.level().hasChunkAt(candidate) && !state.failedRecently(candidate, now)) {
                    Path path = search(candidate, 16, false);
                    if (path != null && path.canReach() && accept(path, now, true)) {
                        return;
                    }
                    state.rememberFailure(candidate, now);
                }
            } else {
                finished = true;
            }
        }

        private int searchRange(LivingEntity target) {
            // A nearby player can require a much longer route around the floor/wall below them.
            return Math.min(128, Math.max(64, (int) Math.ceil(mob.distanceTo(target)) + 32));
        }

        private Path search(BlockPos destination, int range, boolean expanded) {
            // Stop under MOVE ownership so createPath cannot silently reuse the failed path.
            mob.getNavigation().stop();
            long started = System.nanoTime();
            try {
                int desiredNodes = expanded ? (state.attempts > 1 ? 2048 : 1024) : state.initialNodeBudget;
                float multiplier = Math.min(12.0F, Math.max(1.0F, (float) desiredNodes / state.initialNodeBudget));
                mob.getNavigation().setMaxVisitedNodesMultiplier(multiplier);
                Path path = mob.getNavigation().createPath(destination, 0, range);
                if (expanded) {
                    diagnose("search-range-" + range + "-nodes-" + desiredNodes,
                            mob.level().getGameTime(), path);
                }
                return path;
            } finally {
                mob.getNavigation().resetMaxVisitedNodesMultiplier();
                searches++;
                searchNanos += System.nanoTime() - started;
            }
        }

        private BlockPos nextCandidate() {
            int index = candidateIndex++;
            int[] direction = DIRECTIONS[Math.floorMod(index + mob.getId(), DIRECTIONS.length)];
            int radius = mob.getType() == EntityType.SILVERFISH ? 2 : (index < 8 ? 2 : 4);
            int elevation = index < 8 ? (index % 3) - 1 : 2;
            return BlockPos.containing(origin.x + direction[0] * radius,
                    origin.y + elevation, origin.z + direction[1] * radius);
        }

        private boolean accept(Path path, long now, boolean local) {
            if (!safeRoute(path) || path.getNodeCount() < 2
                    || state.failedRecently(path.getEndNode().asBlockPos(), now)
                    || path.getEntityPosAtNode(mob, path.getNodeCount() - 1).distanceToSqr(mob.position()) < 0.25D) {
                return false;
            }
            // Preserve the native pursuit speed differences instead of applying one speed to every species.
            double speed = mob.getType() == EntityType.WITHER_SKELETON ? 1.2D : 1.0D;
            if (!mob.getNavigation().moveTo(path, speed)) {
                return false;
            }
            route = path;
            routeStarted = now;
            localRoute = local;
            if (!local && path.canReach() && !blockedCreeperSwell(mob)) {
                finished = true; // Native combat resumes with this useful route on its next selector update.
            }
            diagnose(local ? "local-route" : "target-route", now, path);
            return true;
        }

        @Override
        public void stop() {
            long now = mob.level().getGameTime();
            boolean advancing = route != null && !mob.getNavigation().isDone()
                    && now - Math.max(routeStarted, state.progress.getLastProgressGameTime()) < STALL_TICKS;
            if (route != null && !route.canReach() && !advancing) {
                state.rememberFailure(route.getEndNode().asBlockPos(), now);
            }
            boolean sameValidTarget = eligible(mob, mob.getTarget())
                    && mob.getTarget().getUUID().equals(routeTarget);
            if (!advancing || !sameValidTarget || specialAction(mob)) {
                mob.getNavigation().stop();
            }
            waitingForSearch.remove(mob.getUUID());
            state.nextRecovery = now + 20L * state.attempts + Math.floorMod(mob.getId(), 10);
            diagnose(state.attempts >= MAX_RECOVERY_ATTEMPTS ? "exhausted" : "handoff", now, route);
            route = null;
        }

        private void diagnose(String reason, long now, Path path) {
            if (DEBUG) {
                LOGGER.info("Zombies navigation mob={} type={} tick={} reason={} target={} pos={} endpoint={} reachable={} node={} stalledTicks={} goals={} attempt={}",
                        mob.getUUID(), mob.getType(), now, reason, routeTarget, mob.position(),
                        path == null || path.getEndNode() == null ? null : path.getEndNode().asBlockPos(),
                        path != null && path.canReach(), path == null ? -1 : path.getNextNodeIndex(),
                        now - state.progress.getLastProgressGameTime(),
                        mob.goalSelector.getRunningGoals().map(goal -> goal.getGoal().getClass().getSimpleName()).toList(),
                        state.attempts);
            }
        }
    }
}
