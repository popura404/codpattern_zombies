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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    static final int GEOMETRY_CHECKS_PER_ROOM_TICK = 32;
    private static final int[][] DIRECTIONS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private final Supplier<List<ServerPlayer>> targets;
    private long budgetTick = Long.MIN_VALUE;
    private int searchesThisTick;
    private long searches;
    private long searchNanos;
    private long recoveries;
    private long geometryChecks;
    private long localRoutes;
    private long checkBudgetTick = Long.MIN_VALUE;
    private int checksThisTick;
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
                observer.state.lastEngagement, observer.state.actionGraceUntil, observer.state.attempts);
    }

    public record ProgressSnapshot(long lastProgressGameTime, long lastEngagementGameTime,
                                   long actionGraceUntilGameTime, int recoveryAttempts) { }

    public record SearchMetrics(long searches, long searchNanos, long recoveries,
                                long geometryChecks, long localRoutes) { }

    public SearchMetrics metrics() {
        return new SearchMetrics(searches, searchNanos, recoveries, geometryChecks, localRoutes);
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
        geometryChecks = 0;
        localRoutes = 0;
        checkBudgetTick = Long.MIN_VALUE;
        checksThisTick = 0;
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

    private boolean takeGeometryCheck(long now) {
        if (checkBudgetTick != now) {
            checkBudgetTick = now;
            checksThisTick = 0;
        }
        if (checksThisTick >= GEOMETRY_CHECKS_PER_ROOM_TICK) {
            return false;
        }
        checksThisTick++;
        geometryChecks++;
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
        // Preserve native hazards; physical clearance is checked separately during recovery.
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
        private long nativeProgressSince;
        private Vec3 nativeProgressOrigin;
        private final Map<BlockPos, Long> failedEndpoints = new LinkedHashMap<>();
        private final Map<BlockPos, Blockage> blockages = new LinkedHashMap<>();
        private final Set<BlockPos> visitedWaypoints = new LinkedHashSet<>();

        private State(Mob mob, double initialFollowRange) {
            long now = mob.level().getGameTime();
            // PathNavigation fixes this budget during entity construction, before room retention raises range.
            initialNodeBudget = Math.max(16, (int) (initialFollowRange * 16.0D));
            progress = new ZombiesNavigationProgressTracker(now, mob.getX(), mob.getY(), mob.getZ());
            lastEngagement = now;
            nextRecovery = now + STALL_TICKS + Math.floorMod(mob.getId(), 20);
        }

        private void rememberFailure(BlockPos endpoint, long now) {
            if (endpoint == null) {
                return;
            }
            failedEndpoints.entrySet().removeIf(entry -> now >= entry.getValue());
            // Failure lookup happens after native pathfinding, so use its node coordinate consistently.
            failedEndpoints.put(endpoint.immutable(), now + STALL_TICKS);
            if (failedEndpoints.size() > 24) {
                failedEndpoints.remove(failedEndpoints.keySet().iterator().next());
            }
        }

        private void recovered() {
            attempts = 0;
            failedEndpoints.clear();
            blockages.clear();
            visitedWaypoints.clear();
            nativeProgressSince = 0;
            nativeProgressOrigin = null;
        }

        private void rememberWaypoint(BlockPos pos) {
            visitedWaypoints.add(pos.immutable());
            if (visitedWaypoints.size() > 64) {
                visitedWaypoints.remove(visitedWaypoints.iterator().next());
            }
        }

        private boolean transientFailure(BlockPos pos, long now) {
            Long until = failedEndpoints.get(pos);
            // Only execution failures are cached. Block geometry is read live, including opened doors.
            return until != null && now < until;
        }
    }

    private record Blockage(Vec3 from, Vec3 to, long signature) { }

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
            if (state.attempts > 0) {
                if (now - state.progress.getLastProgressGameTime() >= STALL_TICKS) {
                    state.nativeProgressSince = 0;
                    state.nativeProgressOrigin = null;
                } else if (state.nativeProgressSince == 0 && routeAdvanced && !special) {
                    state.nativeProgressSince = now;
                    state.nativeProgressOrigin = mob.position();
                }
            }
            if (engaged(mob, target)) {
                state.lastEngagement = now;
                state.recovered();
            }
        }
    }

    private enum Phase { IDLE, TARGET, LOCAL, ALTERNATIVE, SEARCH, VALIDATE, FOLLOW, DONE }
    private enum RouteKind { TARGET, LOCAL, ALTERNATIVE }

    private final class RecoveryGoal extends Goal {
        private final PathfinderMob mob;
        private final State state;
        private Phase phase = Phase.IDLE;
        private long deadline;
        private long handoffAt;
        private long routeStarted;
        private long nextRouteCheck;
        private int candidateIndex;
        private int alternativeIndex;
        private int validationIndex;
        private int unverifiedThrough;
        private int blockageIndex;
        private Path route;
        private RouteKind routeKind;
        private Vec3 origin;
        private Vec3 routeOrigin;
        private Vec3 heading = Vec3.ZERO;
        private UUID routeTarget;
        private SearchQuery query;
        private List<Vec3> candidates = List.of();
        private int pointIndex;
        private boolean countedAttempt;
        private boolean probeOnly;
        private boolean alternativesDone;
        private boolean resumeValidation;

        private RecoveryGoal(PathfinderMob mob, State state) {
            this.mob = mob;
            this.state = state;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private boolean active() {
            return phase != Phase.IDLE && phase != Phase.DONE;
        }

        @Override
        public boolean canUse() {
            long now = mob.level().getGameTime();
            if (now < state.nextRecovery || !mob.onGround() || !eligible(mob, mob.getTarget())
                    || specialAction(mob) || engaged(mob, mob.getTarget())) {
                return false;
            }
            if (active() && now < deadline) {
                return true; // Resume the same attempt, including its fourth round, after a native action.
            }
            if (nativeProgressReady(now)) {
                return true; // Verify a native escape too; its next obstacle must not inherit old attempts.
            }
            return now - Math.max(state.progress.getLastProgressGameTime(), state.lastEngagement) >= STALL_TICKS;
        }

        private boolean nativeProgressReady(long now) {
            Path path = mob.getNavigation().getPath();
            return state.attempts > 0 && state.nativeProgressOrigin != null
                    && now - state.nativeProgressSince >= MOVEMENT_HANDOFF_TICKS
                    && now - state.progress.getLastProgressGameTime() < STALL_TICKS
                    && mob.position().distanceToSqr(state.nativeProgressOrigin) >= 4.0D
                    && path != null && path.canReach() && !path.isDone()
                    && path.getTarget().getX() == mob.getTarget().getBlockX()
                    && path.getTarget().getZ() == mob.getTarget().getBlockZ()
                    && Math.abs(path.getTarget().getY() - mob.getTarget().getBlockY()) <= 1;
        }

        @Override
        public boolean canContinueToUse() {
            long now = mob.level().getGameTime();
            return active() && now < deadline && eligible(mob, mob.getTarget())
                    && mob.getTarget().getUUID().equals(routeTarget)
                    && !specialAction(mob) && !engaged(mob, mob.getTarget())
                    && !(now >= handoffAt && !blockedCreeperSwell(mob));
        }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public void start() {
            long now = mob.level().getGameTime();
            UUID target = mob.getTarget().getUUID();
            if (active() && now < deadline) {
                if (!target.equals(routeTarget)) {
                    routeTarget = target;
                    route = null;
                    query = null;
                    phase = Phase.TARGET;
                    blockageIndex = 0;
                } else if (phase == Phase.FOLLOW) {
                    // A native goal may have changed the path while it had movement ownership.
                    if (route.isDone()) {
                        finishRoute(now);
                    } else {
                        validationIndex = route.getNextNodeIndex();
                        resumeValidation = true;
                        phase = Phase.VALIDATE;
                    }
                }
                mob.getNavigation().stop();
                handoffAt = now + MOVEMENT_HANDOFF_TICKS;
                diagnose("resume", now, route);
                return;
            }
            Path nativeRoute = nativeProgressReady(now) ? mob.getNavigation().getPath() : null;
            phase = Phase.TARGET;
            deadline = now + RECOVERY_TICKS;
            handoffAt = now + MOVEMENT_HANDOFF_TICKS;
            candidateIndex = 0;
            blockageIndex = 0;
            alternativeIndex = 0;
            alternativesDone = now < state.nextTargetSwitch;
            countedAttempt = false;
            probeOnly = state.attempts >= MAX_RECOVERY_ATTEMPTS;
            origin = mob.position();
            if (!probeOnly) {
                state.rememberWaypoint(mob.blockPosition());
            }
            candidates = List.of();
            pointIndex = 0;
            if (state.attempts == 0) {
                heading = Vec3.ZERO;
            }
            routeTarget = target;
            route = null;
            query = null;
            mob.getNavigation().stop();
            if (nativeRoute != null) {
                prepareRoute(nativeRoute, RouteKind.TARGET, now);
                routeStarted = state.nativeProgressSince;
                routeOrigin = state.nativeProgressOrigin;
                if (phase == Phase.VALIDATE) {
                    // Confirm the current physical target surface inside the shared geometry budget.
                    query = new SearchQuery(mob.getTarget().position(), RouteKind.TARGET, mob.getTarget());
                }
            }
            diagnose(probeOnly ? "recheck" : "stalled", now, null);
        }

        @Override
        public void tick() {
            if (!canContinueToUse()) {
                return;
            }
            long now = mob.level().getGameTime();
            LivingEntity target = mob.getTarget();
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            switch (phase) {
                case TARGET -> {
                    if (refreshTerrain(now)) {
                        beginQuery(target.position(), RouteKind.TARGET, target);
                    }
                }
                case LOCAL -> nextLocalQuery();
                case ALTERNATIVE -> nextAlternativeQuery(now);
                case SEARCH -> searchNext(now);
                case VALIDATE -> validateRoute(now);
                case FOLLOW -> followRoute(now);
                default -> { }
            }
        }

        private boolean refreshTerrain(long now) {
            List<Blockage> previous = new ArrayList<>(state.blockages.values());
            while (blockageIndex < previous.size()) {
                if (!takeGeometryCheck(now)) {
                    deadline++;
                    return false;
                }
                Blockage blockage = previous.get(blockageIndex++);
                Long signature = ZombiesNavigationGeometry.collisionSignature(mob, blockage.from(), blockage.to());
                if (signature != null && signature.longValue() != blockage.signature()) {
                    // A newly opened route may require retracing an old relay. This is permission to
                    // re-evaluate the terrain, not evidence of progress or a fresh attempt allowance.
                    state.blockages.clear();
                    state.visitedWaypoints.clear();
                    state.failedEndpoints.clear();
                    heading = Vec3.ZERO;
                    origin = mob.position();
                    candidates = List.of();
                    pointIndex = 0;
                    blockageIndex = 0;
                    diagnose("terrain-changed", now, null);
                    return true;
                }
            }
            blockageIndex = 0;
            return true;
        }

        private void rememberBlockage(Vec3 from, Vec3 to, long now) {
            if (from == null || to == null || !takeGeometryCheck(now)) {
                return;
            }
            Long signature = ZombiesNavigationGeometry.collisionSignature(mob, from, to);
            if (signature == null) {
                return;
            }
            state.blockages.put(BlockPos.containing(to), new Blockage(from, to, signature));
            if (state.blockages.size() > 8) {
                // Keep the original obstruction plus recent ones while advancing along an obstacle.
                var keys = state.blockages.keySet().iterator();
                keys.next();
                keys.next();
                keys.remove();
            }
        }

        private void beginQuery(Vec3 feet, RouteKind kind, LivingEntity target) {
            query = new SearchQuery(feet, kind, target);
            phase = Phase.SEARCH;
        }

        private void nextLocalQuery() {
            if (probeOnly || candidateIndex >= MAX_LOCAL_CANDIDATES) {
                phase = probeOnly ? Phase.DONE : Phase.ALTERNATIVE;
                return;
            }
            if (pointIndex >= candidates.size()) {
                candidates = localCandidates();
                pointIndex = 0;
            }
            if (candidates.isEmpty()) {
                phase = Phase.ALTERNATIVE;
                return;
            }
            if (!takeGeometryCheck(mob.level().getGameTime())) {
                deadline++;
                return;
            }
            Vec3 approximate = candidates.get(pointIndex++);
            candidateIndex++;
            Vec3 feet = ZombiesNavigationGeometry.localTargetFeet(mob, approximate);
            if (feet == null || state.visitedWaypoints.contains(BlockPos.containing(feet))) {
                return;
            }
            beginQuery(feet, RouteKind.LOCAL, null);
        }

        private List<Vec3> localCandidates() {
            List<Vec3> points = new ArrayList<>(DIRECTIONS.length);
            Vec3 towards = mob.getTarget().position().subtract(origin).multiply(1, 0, 1).normalize();
            int radius = mob.getType() == EntityType.SILVERFISH ? 2 : (state.attempts > 1 ? 4 : 2);
            for (int i = 0; i < DIRECTIONS.length; i++) {
                int[] direction = DIRECTIONS[Math.floorMod(i + mob.getId(), DIRECTIONS.length)];
                points.add(origin.add(direction[0] * radius, 0, direction[1] * radius));
            }
            points.sort(Comparator.comparingDouble(point -> {
                Vec3 direction = point.subtract(origin).normalize();
                return -direction.dot(towards) - 2.0D * direction.dot(heading);
            }));
            return points;
        }

        private void nextAlternativeQuery(long now) {
            if (probeOnly || alternativesDone) {
                phase = candidateIndex < MAX_LOCAL_CANDIDATES && !probeOnly ? Phase.LOCAL : Phase.DONE;
                return;
            }
            List<ServerPlayer> alternatives = targetsFor(mob).stream()
                    .filter(player -> player != mob.getTarget() && eligible(mob, player))
                    .sorted(Comparator.comparingDouble(mob::distanceToSqr)).limit(3).toList();
            if (alternativeIndex >= alternatives.size()) {
                alternativesDone = true;
                phase = candidateIndex < MAX_LOCAL_CANDIDATES ? Phase.LOCAL : Phase.DONE;
                return;
            }
            ServerPlayer alternative = alternatives.get(alternativeIndex++);
            beginQuery(alternative.position(), RouteKind.ALTERNATIVE, alternative);
        }

        private void searchNext(long now) {
            if (!takeSearch(mob, now)) {
                deadline++; // Budget queues do not consume an attempt or alter the recycle clock.
                return;
            }
            if (query.destinations == null) {
                if (!takeGeometryCheck(now)) {
                    deadline++;
                    return;
                }
                query.destinations = ZombiesNavigationGeometry.targetCandidates(mob, query.feet);
                if (query.destinations.isEmpty()) {
                    failedRoute(query.kind);
                    return;
                }
            }
            if (!countedAttempt) {
                countedAttempt = true;
                if (!probeOnly) {
                    state.attempts++;
                }
                recoveries++;
            }
            BlockPos destination = query.destinations.get(query.index++);
            Path path = search(destination, query.kind != RouteKind.LOCAL);
            if (safeRoute(path)) {
                if (path.canReach()) {
                    prepareRoute(path, query.kind, now);
                    return;
                }
                if (query.partial == null || path.getEndNode().asBlockPos().distSqr(BlockPos.containing(query.feet))
                        < query.partial.getEndNode().asBlockPos().distSqr(BlockPos.containing(query.feet))) {
                    query.partial = path;
                }
            }
            if (query.index >= query.destinations.size()) {
                if (query.kind == RouteKind.TARGET && query.partial != null) {
                    prepareRoute(query.partial, query.kind, now);
                } else {
                    failedRoute(query.kind);
                }
            }
        }

        private Path search(BlockPos destination, boolean expanded) {
            mob.getNavigation().stop();
            long started = System.nanoTime();
            try {
                int desiredNodes = expanded ? (state.attempts > 1 ? 2048 : 1024) : state.initialNodeBudget;
                float multiplier = Math.min(12.0F, Math.max(1.0F, (float) desiredNodes / state.initialNodeBudget));
                mob.getNavigation().setMaxVisitedNodesMultiplier(multiplier);
                int range = expanded ? Math.min(128, Math.max(64,
                        (int) Math.ceil(mob.position().distanceTo(query.feet)) + 32)) : 16;
                return mob.getNavigation().createPath(destination, 0, range);
            } finally {
                mob.getNavigation().resetMaxVisitedNodesMultiplier();
                searches++;
                searchNanos += System.nanoTime() - started;
            }
        }

        private void prepareRoute(Path path, RouteKind kind, long now) {
            if (path.getNodeCount() < 2 || state.transientFailure(path.getEndNode().asBlockPos(), now)
                    || path.getEntityPosAtNode(mob, path.getNodeCount() - 1).distanceToSqr(mob.position()) < 0.25D) {
                failedRoute(kind);
                return;
            }
            route = path;
            routeKind = kind;
            validationIndex = path.getNextNodeIndex();
            unverifiedThrough = -1;
            resumeValidation = false;
            routeStarted = now;
            routeOrigin = mob.position();
            phase = Phase.VALIDATE;
        }

        private void validateRoute(long now) {
            if (query != null && query.index == 0) {
                if (!takeGeometryCheck(now)) {
                    deadline++;
                    return;
                }
                query.destinations = ZombiesNavigationGeometry.targetCandidates(mob, query.feet);
                if (!query.destinations.contains(route.getTarget())) {
                    failedRoute(routeKind);
                    return;
                }
                query.index = query.destinations.size();
            }
            // Bound collision work independently of path searches; longer paths are checked over several ticks.
            for (int count = 0; count < 4 && validationIndex < route.getNodeCount(); count++) {
                if (!takeGeometryCheck(now)) {
                    deadline++;
                    return;
                }
                Vec3 from = validationIndex == route.getNextNodeIndex() ? mob.position()
                        : ZombiesNavigationGeometry.nodeFeet(mob, route, validationIndex - 1);
                Vec3 to = ZombiesNavigationGeometry.nodeFeet(mob, route, validationIndex);
                var status = checkRouteSegment(validationIndex, from, to);
                if (status == ZombiesNavigationGeometry.SegmentStatus.BLOCKED) {
                    rememberBlockage(from, to, now);
                    diagnose("blocked-segment " + from + " -> " + to, now, route);
                    failedRoute(routeKind);
                    return;
                }
                if (status == ZombiesNavigationGeometry.SegmentStatus.UNKNOWN) {
                    unverifiedThrough = Math.max(unverifiedThrough, validationIndex);
                }
                validationIndex++;
                if (resumeValidation) {
                    validationIndex = route.getNodeCount();
                    resumeValidation = false;
                }
            }
            if (validationIndex < route.getNodeCount()) {
                return;
            }
            if (routeKind == RouteKind.ALTERNATIVE && query != null) {
                if (!(query.target instanceof ServerPlayer player) || !eligible(mob, player)) {
                    failedRoute(routeKind);
                    return;
                }
                ZombiesMobSpawnService.setRecoveredRoomTarget(mob, player);
                routeTarget = query.target.getUUID();
                state.nextTargetSwitch = now + 200;
            }
            double speed = mob.getType() == EntityType.WITHER_SKELETON ? 1.2D : 1.0D;
            if (!mob.getNavigation().moveTo(route, speed)) {
                failedRoute(routeKind);
                return;
            }
            if (routeKind == RouteKind.LOCAL && query != null) {
                localRoutes++;
            }
            query = null;
            phase = Phase.FOLLOW;
            nextRouteCheck = now;
            diagnose(routeKind == RouteKind.LOCAL ? "local-route" : "target-route", now, route);
        }

        private void followRoute(long now) {
            if (route.isDone()) {
                finishRoute(now);
                return;
            }
            if (mob.getNavigation().getPath() != route) {
                validationIndex = route.getNextNodeIndex();
                resumeValidation = true;
                phase = Phase.VALIDATE;
                mob.getNavigation().stop();
                return;
            }
            if (!route.isDone() && now >= nextRouteCheck && takeGeometryCheck(now)) {
                Vec3 to = ZombiesNavigationGeometry.nodeFeet(mob, route, route.getNextNodeIndex());
                var status = checkRouteSegment(route.getNextNodeIndex(), mob.position(), to);
                nextRouteCheck = now + 5;
                if (status == ZombiesNavigationGeometry.SegmentStatus.BLOCKED) {
                    rememberBlockage(mob.position(), to, now);
                    diagnose("blocked-segment " + mob.position() + " -> " + to, now, route);
                    failedRoute(routeKind);
                    return;
                } else if (status == ZombiesNavigationGeometry.SegmentStatus.UNKNOWN) {
                    unverifiedThrough = Math.max(unverifiedThrough, route.getNextNodeIndex());
                }
            }
            boolean moved = now - routeStarted >= MOVEMENT_HANDOFF_TICKS
                    && mob.position().distanceToSqr(routeOrigin) >= 4.0D
                    && state.progress.getLastProgressGameTime() > routeStarted;
            if (routeKind != RouteKind.LOCAL && route.canReach() && moved
                    && route.getNextNodeIndex() > unverifiedThrough && !blockedCreeperSwell(mob)) {
                if (!takeGeometryCheck(now)) {
                    return;
                }
                if (!ZombiesNavigationGeometry.targetCandidates(mob, mob.getTarget().position()).contains(route.getTarget())) {
                    route = null;
                    phase = Phase.TARGET;
                    mob.getNavigation().stop();
                    return;
                }
                state.recovered();
                phase = Phase.DONE;
                diagnose("recovered", now, route);
                return;
            }
            if (!mob.getNavigation().isDone()
                    && now - Math.max(routeStarted, state.progress.getLastProgressGameTime()) < STALL_TICKS) {
                return;
            }
            finishRoute(now);
        }

        private void finishRoute(long now) {
            Vec3 endpoint = ZombiesNavigationGeometry.nodeFeet(mob, route, route.getNodeCount() - 1);
            boolean arrived = endpoint != null && mob.position().distanceToSqr(endpoint) < 1.0D;
            if (arrived && routeKind == RouteKind.LOCAL) {
                state.rememberWaypoint(BlockPos.containing(endpoint));
                heading = mob.position().subtract(origin).multiply(1, 0, 1).normalize();
                origin = mob.position();
                candidates = List.of();
                pointIndex = 0;
                route = null;
                mob.getNavigation().stop();
                phase = Phase.TARGET; // An intermediate arrival is not a recovered pursuit.
                return;
            }
            // Physical failure is remembered for complete paths too. Crowding only gets a short execution cooldown.
            state.rememberFailure(route.getEndNode().asBlockPos(), now);
            failedRoute(routeKind);
        }

        private ZombiesNavigationGeometry.SegmentStatus checkRouteSegment(int index, Vec3 from, Vec3 to) {
            // Special native nodes may encode a door interaction or a floor inside their integer node.
            // They must be proven by execution, not rejected using a level translation at that node's Y.
            for (int i = Math.max(0, index - 1); i <= index; i++) {
                BlockPathTypes type = route.getNode(i).type;
                if (type != BlockPathTypes.OPEN && type != BlockPathTypes.WALKABLE && type != BlockPathTypes.DOOR_OPEN) {
                    return ZombiesNavigationGeometry.SegmentStatus.UNKNOWN;
                }
            }
            return ZombiesNavigationGeometry.checkSegment(mob, from, to);
        }

        private void failedRoute(RouteKind kind) {
            mob.getNavigation().stop();
            route = null;
            if (query != null && query.destinations != null && query.index < query.destinations.size()) {
                phase = Phase.SEARCH;
                return;
            }
            query = null;
            if (probeOnly) {
                phase = Phase.DONE;
            } else if (kind == RouteKind.ALTERNATIVE) {
                phase = Phase.ALTERNATIVE;
            } else if (kind == RouteKind.TARGET && state.attempts > 1 && !alternativesDone) {
                phase = Phase.ALTERNATIVE;
            } else {
                phase = Phase.LOCAL;
            }
        }

        @Override
        public void stop() {
            long now = mob.level().getGameTime();
            waitingForSearch.remove(mob.getUUID());
            if (engaged(mob, mob.getTarget())) {
                state.recovered();
                phase = Phase.DONE;
            }
            if (active() && now < deadline && eligible(mob, mob.getTarget())) {
                mob.getNavigation().stop();
                state.nextRecovery = now + 1;
                diagnose("yield", now, route);
                return;
            }
            boolean advancing = phase == Phase.FOLLOW && route != null && !mob.getNavigation().isDone()
                    && now - state.progress.getLastProgressGameTime() < STALL_TICKS
                    && eligible(mob, mob.getTarget()) && mob.getTarget().getUUID().equals(routeTarget)
                    && !specialAction(mob);
            boolean successful = state.attempts == 0 && routeKind != RouteKind.LOCAL && route != null;
            if (!successful && !advancing) {
                mob.getNavigation().stop();
            }
            phase = Phase.IDLE;
            route = null;
            query = null;
            state.nextRecovery = now + Math.max(20, 20L * state.attempts) + Math.floorMod(mob.getId(), 10);
            diagnose(state.attempts >= MAX_RECOVERY_ATTEMPTS ? "exhausted" : "finished", now, null);
        }

        private void diagnose(String reason, long now, Path path) {
            if (DEBUG) {
                LOGGER.info("Zombies navigation mob={} type={} tick={} reason={} phase={} target={} pos={} endpoint={} reachable={} node={} stalledTicks={} goals={} attempt={} candidates={}",
                        mob.getUUID(), mob.getType(), now, reason, phase, routeTarget, mob.position(),
                        path == null || path.getEndNode() == null ? null : path.getEndNode().asBlockPos(),
                        path != null && path.canReach(), path == null ? -1 : path.getNextNodeIndex(),
                        now - state.progress.getLastProgressGameTime(),
                        mob.goalSelector.getRunningGoals().map(goal -> goal.getGoal().getClass().getSimpleName()).toList(),
                        state.attempts, candidateIndex);
            }
        }
    }

    private static final class SearchQuery {
        private final Vec3 feet;
        private final RouteKind kind;
        private final LivingEntity target;
        private List<BlockPos> destinations;
        private int index;
        private Path partial;

        private SearchQuery(Vec3 feet, RouteKind kind, LivingEntity target) {
            this.feet = feet;
            this.kind = kind;
            this.target = target;
        }
    }
}
