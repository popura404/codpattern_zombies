package com.cdp.codpattern.app.zombies.service.navigation;

import com.cdp.codpattern.app.zombies.service.ZombiesGroundNavigationService;
import com.cdp.codpattern.app.zombies.service.ZombiesGroundSpawnPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Room-scoped controller. Planning and action state survive native Goal scheduling opportunities. */
public final class LayeredNavigationRuntime {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final boolean DEBUG = Boolean.getBoolean("codpattern.zombies.navigationDebug");
    private final NavigationContext context;
    private final NavigationPlanner planner;
    private final Map<UUID, Controller> controllers = new HashMap<>();
    private boolean closed;

    public LayeredNavigationRuntime(NavigationContext context) {
        this.context = context;
        planner = new NavigationPlanner(context, NavigationTuning.DEFAULT, this::close);
    }
    public void install(PathfinderMob mob) {
        if (closed || controllers.containsKey(mob.getUUID())) return;
        Controller controller = new Controller(mob);
        controllers.put(mob.getUUID(), controller);
        mob.goalSelector.addGoal(0, new Observation(controller));
        int priority = mob instanceof Wolf ? 4 : mob.getType() == EntityType.ZOMBIE
                || mob.getType() == EntityType.HUSK || mob instanceof Creeper ? 1 : 3;
        mob.goalSelector.addGoal(priority, controller);
        // GoalSelector visits this public set in insertion order, before considering
        // priority. Vanilla melee canUse itself reads the target's terrain, so the
        // missing-terrain guard must precede that call, not merely win afterwards.
        Set<WrappedGoal> goals = mob.goalSelector.getAvailableGoals();
        List<WrappedGoal> existing = List.copyOf(goals);
        mob.goalSelector.addGoal(Integer.MIN_VALUE, new TerrainWaitGoal(controller));
        WrappedGoal guard = goals.stream().filter(goal -> goal.getGoal() instanceof TerrainWaitGoal wait
                && wait.controller == controller).findFirst().orElseThrow();
        WrappedGoal observer = existing.stream().filter(goal -> goal.getGoal() instanceof Observation observation
                && observation.controller == controller).findFirst().orElseThrow();
        goals.clear();
        goals.add(guard);
        goals.add(observer);
        goals.addAll(existing); // Preserve every other wrapper, priority and relative order.
    }
    public static LayeredNavigationRuntime of(Mob mob) {
        if (mob != null) for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof Observation observation && !observation.owner.closed)
                return observation.owner;
        }
        return null;
    }
    public boolean eligible(Mob mob, LivingEntity target) {
        return !closed && controllers.containsKey(mob.getUUID())
                && target instanceof ServerPlayer player && context.eligible(mob, player);
    }
    public ZombiesGroundNavigationService.ProgressSnapshot progress(Mob mob) {
        Controller c = controllers.get(mob.getUUID());
        if (c == null || !context.owns(mob)) return null;
        boolean waiting = c.validationWaiting || c.result.status() == PlanningResult.Status.PENDING
                || c.result.status() == PlanningResult.Status.WAITING_CHUNK;
        return new ZombiesGroundNavigationService.ProgressSnapshot(c.progress.lastRoute(), c.progress.lastCombat(),
                c.state.graceUntil, c.failures, waiting ? c.progress.waitingUntil() : 0,
                c.validationWaiting ? "VALIDATION" : c.result.reason().name(),
                !waiting && c.result.status() == PlanningResult.Status.UNREACHABLE);
    }
    public void trackSpawn(Mob mob, ZombiesGroundSpawnPolicy policy, String spawnId) {
        Controller c = controllers.get(mob.getUUID());
        if (c != null) { c.spawnPolicy = policy; c.spawnId = spawnId; }
    }
    public Runnable recycleFeedback(Mob mob) {
        Controller c = controllers.get(mob.getUUID());
        if (c == null || c.spawnPolicy == null) return () -> { };
        ZombiesGroundSpawnPolicy policy = c.spawnPolicy;
        String spawnId = c.spawnId;
        return () -> policy.recordFailure(spawnId, mob, mob.level().getGameTime());
    }
    public void cancelMob(UUID id) {
        Controller c = controllers.remove(id);
        if (DEBUG && c != null) LOGGER.info("Zombies navigation cancellation room={} mob={} alive={} removal={} position={}",
                context.roomId().encode(), id, c.mob.isAlive(), c.mob.getRemovalReason(), c.mob.position(),
                new IllegalStateException("Navigation cancellation call site"));
        planner.cancelMob(id);
        if (c != null) {
            c.clearCachedRoute();
            c.mob.goalSelector.removeGoal(c);
            c.mob.goalSelector.getAvailableGoals().stream().map(WrappedGoal::getGoal)
                    .filter(goal -> goal instanceof Observation o && o.controller == c
                            || goal instanceof TerrainWaitGoal wait && wait.controller == c).toList()
                    .forEach(c.mob.goalSelector::removeGoal);
        }
    }
    public void close() {
        closed = true;
        for (UUID id : List.copyOf(controllers.keySet())) cancelMob(id);
        planner.close();
    }
    public void invalidate(net.minecraft.world.phys.AABB bounds, String reason) { planner.invalidate(bounds, reason); }
    public NavigationContext context() { return context; }
    public NavigationPlanner.Stats planningStats() { return planner.stats(); }
    public NavigationGraphCache.Stats cacheStats() { return planner.cache().stats(); }
    public NavigationScheduler.Metrics schedulerMetrics() { return NavigationScheduler.forServer(context.level().getServer()).metrics(); }
    public NavigationScheduler.Timing schedulerTiming() { return NavigationScheduler.forServer(context.level().getServer()).timing(); }
    public int controllerCount() { return controllers.size(); }
    public boolean loopDetected(Mob mob) {
        Controller controller = controllers.get(mob.getUUID());
        return controller != null && controller.progress.loopDetected();
    }
    public Goal movementGoal(Mob mob) { return controllers.get(mob.getUUID()); }
    /** Immutable execution evidence for diagnostics and real-entity acceptance fixtures. */
    public TraversalEdge activeEdge(Mob mob) {
        Controller controller = controllers.get(mob.getUUID());
        return controller == null ? null : controller.state.edge();
    }
    public String describe(Mob mob) {
        Controller c = controllers.get(mob.getUUID());
        if (c == null) return "unmanaged";
        Path nativeRoute = mob.getNavigation().getPath();
        return "engine=layered, phase=" + c.state.phase + ", plan=" + (c.state.plan == null ? null : c.state.plan.id())
                + ", edge=" + c.state.edgeIndex + ":" + c.state.edge() + ", result=" + c.result.status() + "/" + c.result.reason()
                + ", nativeCanReach=" + (nativeRoute == null ? "none" : nativeRoute.canReach())
                + ", nativeDone=" + (nativeRoute == null ? "none" : nativeRoute.isDone())
                + ", loop=" + c.progress.loopDetected() + ", failures=" + c.failures
                + ", cache=" + planner.cache().stats() + ", search=" + planner.describe(mob.getUUID());
    }

    private final class Observation extends Goal {
        private final LayeredNavigationRuntime owner = LayeredNavigationRuntime.this;
        private final Controller controller;
        Observation(Controller controller) { this.controller = controller; setFlags(EnumSet.noneOf(Flag.class)); }
        @Override public boolean canUse() { return !closed && controller.mob.isAlive(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            controller.preventNativeTerrainReads();
            controller.observe();
        }
    }

    /** Blocks native path creation before its target-height lookup can load a chunk. */
    private final class TerrainWaitGoal extends Goal {
        private final Controller controller;
        TerrainWaitGoal(Controller controller) {
            this.controller = controller;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override public boolean canUse() {
            if (closed || !context.owns(controller.mob) || controller.committedDrop()
                    || controller.mob.isPassenger() || controller.nativeLeapInProgress()
                    || controller.mob instanceof Creeper creeper && creeper.isIgnited()) return false;
            LivingEntity target = controller.mob.getTarget();
            if (!eligible(controller.mob, target) || controller.nativeTerrainAvailable(target)) return false;
            controller.reservedTarget = (ServerPlayer) target;
            return true;
        }
        @Override public boolean canContinueToUse() {
            // If a managed loaded-route detour left its ledge while this guard was
            // active, retain its existing owner through landing. Releasing MOVE
            // first would expose native canUse before Controller can reacquire it.
            if (!closed && context.owns(controller.mob) && controller.committedDrop()) return true;
            return canUse();
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() {
            controller.start();
            controller.hold();
        }
        @Override public void stop() { controller.stop(); }
        @Override public void tick() {
            // Retain budgeted planning and execution: an absent off-route LOS chunk
            // must not prevent a proven managed detour through loaded terrain.
            controller.committedDrop();
            controller.tick();
        }
    }

    private final class Controller extends Goal {
        private final PathfinderMob mob;
        private final RouteExecutionState state = new RouteExecutionState();
        private final RouteProgress progress;
        private MovementProfile profile;
        private PlanningResult result = PlanningResult.pending(PlanningResult.Reason.BUILD);
        private ServerPlayer reservedTarget;
        private ServerPlayer planningAlternative;
        private final Set<UUID> unreachableTargets = new HashSet<>();
        private int failures;
        private boolean specialLast;
        private boolean loopHandled;
        private long nextCollisionProbe;
        private boolean validationWaiting;
        private float previousSwell;
        private ZombiesGroundSpawnPolicy spawnPolicy;
        private String spawnId;
        private Path nativePath;
        private int nativeNode;
        private double nativeNodeDistance;
        private Vec3 nativeOrigin;
        private String nativeEdgeKey;
        private NavigationGraphCache.TileKey nativePortalFrom, nativePortalTo;
        private boolean ignitionProtected;

        Controller(PathfinderMob mob) {
            this.mob = mob;
            long now = now();
            profile = MovementProfile.from(mob);
            progress = new RouteProgress(now, mob.getX(), mob.getY(), mob.getZ());
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        private long now() { return mob.level().getGameTime(); }
        private boolean nativeLeapInProgress() {
            return !mob.onGround() && mob.goalSelector.getRunningGoals()
                    .anyMatch(wrapped -> wrapped.getGoal() instanceof LeapAtTargetGoal);
        }
        private void preventNativeTerrainReads() {
            if (closed || !context.owns(mob) || committedDrop()) return;
            LivingEntity target = mob.getTarget();
            if (!eligible(mob, target) || nativeTerrainAvailable(target)) return;
            // On alternate entity ticks vanilla only ticks existing goals; it does
            // not ask TerrainWaitGoal.canUse. Stop just the two native target readers
            // before their tick, without removing/replacing any native goal object.
            for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
                Goal goal = wrapped.getGoal();
                if (wrapped.isRunning() && (goal instanceof MeleeAttackGoal || goal instanceof RangedBowAttackGoal<?>))
                    wrapped.stop();
            }
            if (!eligible(mob, mob.getTarget()) && eligible(mob, target)) mob.setTarget(target);
            // A controlled mount has its own movement ownership; getNavigation and
            // getMoveControl delegate to that vehicle, so never issue our hold to it.
            if (mob.isPassenger()) return;
            NativeNavigationGuard.follow(context, mob);
            boolean managed = mob.goalSelector.getRunningGoals().anyMatch(wrapped -> wrapped.getGoal() == this
                    || wrapped.getGoal() instanceof TerrainWaitGoal wait && wait.controller == this);
            if (!managed && !specialAction()) {
                hold();
                progress.waiting(now());
            }
        }
        private void observe() {
            long now = now();
            if (state.phase == RouteExecutionState.Phase.COMMIT && !mob.onGround()) {
                state.phase = RouteExecutionState.Phase.FALL;
                state.previousY = mob.getY();
            }
            progress.sample(now, mob.getX(), mob.getY(), mob.getZ());
            if (!context.owns(mob)) { clearCachedRoute(); planner.cancelMob(mob.getUUID()); return; }
            LivingEntity target = mob.getTarget();
            if (eligible(mob, target)) {
                // A coordinate tile boundary is not evidence of a new standing region.
                // Keep intent through tiny boundary crossings and airborne target jitter.
                progress.target(target.getUUID().toString(), target.getX(), target.getY(), target.getZ());
                if (engaged(target)) progress.engagement(now);
            } else if (state.committedDrop()) {
                state.clearAfterLanding = true;
            } else {
                clearCachedRoute(); planner.cancelMob(mob.getUUID());
            }
            boolean special = specialAction();
            if (special && !specialLast && now >= state.nextGrace) {
                state.graceUntil = now + 60; state.nextGrace = now + 320;
            }
            specialLast = special;
            if (mob instanceof Creeper creeper) {
                if (creeper.isIgnited() && !ignitionProtected) {
                    ignitionProtected = true; state.graceUntil = Math.max(state.graceUntil, now + 60);
                }
                float swelling = creeper.getSwelling(1.0F);
                if (swelling > previousSwell && (creeper.isIgnited()
                        || target != null && nativeTerrainAvailable(target)
                        && mob.getSensing().hasLineOfSight(target))) progress.engagement(now);
                previousSwell = swelling;
            }
            if (state.edge() != null) sampleRoute(now);
            else if (eligible(mob, target) && !special) sampleNativeRoute(now);
            if (DEBUG && Math.floorMod(now + mob.getId(), 20) == 0)
                LOGGER.info("Zombies navigation room={} mob={} target={} position={} {}", context.roomId().encode(),
                        mob.getUUID(), target == null ? null : target.getUUID(), mob.position(), describe(mob));
        }
        private void sampleNativeRoute(long now) {
            Path path = mob.getNavigation().getPath();
            if (path == null || path.isDone()) { nativePath = null; return; }
            int index = path.getNextNodeIndex();
            Vec3 next = path.getNextEntityPos(mob);
            double distance = mob.position().distanceToSqr(next);
            boolean advanced = path == nativePath && (index > nativeNode || distance + 1.0E-5 < nativeNodeDistance);
            if (path != nativePath || index != nativeNode || nativeOrigin == null) {
                if (path == nativePath && index > nativeNode && nativeEdgeKey != null) {
                    progress.completed(now, nativeEdgeKey);
                    if (nativePortalFrom != null && !nativePortalFrom.equals(nativePortalTo))
                        progress.portal("portal:" + nativeEdgeKey);
                }
                // The same directed edge must use the same origin after every native replan.
                // A temporary retreat followed by a return to an old endpoint is not progress.
                nativeOrigin = path.getEntityPosAtNode(mob, Math.max(0, index - 1));
                nativePortalFrom = NavigationGraphCache.TileKey.at(nativeOrigin);
                nativePortalTo = NavigationGraphCache.TileKey.at(next);
                nativeEdgeKey = "native:" + path.getNode(Math.max(0, index-1)).asBlockPos() + ">"
                        + path.getNode(index).asBlockPos();
            } else if (advanced) {
                double projection = NativeRouteProgress.projection(nativeOrigin.x, nativeOrigin.y, nativeOrigin.z,
                        next.x, next.y, next.z, mob.getX(), mob.getY(), mob.getZ());
                progress.advance(now, nativeEdgeKey, projection,
                        mob.getX(), mob.getY(), mob.getZ());
            }
            nativePath = path; nativeNode = index; nativeNodeDistance = distance;
        }
        private void sampleRoute(long now) {
            TraversalEdge edge = state.edge();
            Vec3 endpoint = edge.action() == TraversalEdge.Action.DROP && !state.committedDrop()
                    ? edge.controlPoints().get(1) : edge.to().feet();
            Vec3 delta = endpoint.subtract(edge.from().feet());
            double length = delta.length();
            if (length < 1.0E-6) return;
            double projection = mob.position().subtract(edge.from().feet()).dot(delta) / length;
            // A route never credits displacement outside its short corridor.
            Vec3 nearest = edge.from().feet().add(delta.scale(Math.max(0, Math.min(1, projection / length))));
            if (mob.position().distanceToSqr(nearest) <= 1.0 || state.committedDrop()
                    && mob.getY() < state.previousY - 0.01) {
                if (projection >= state.furthestCommandProjection + 0.02) {
                    state.furthestCommandProjection = projection;
                    state.lastCommandMotion = now;
                }
                progress.advance(now, state.edgeKey, projection, mob.getX(), mob.getY(), mob.getZ());
            }
            state.previousY = mob.getY();
        }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        private boolean committedDrop() {
            if (state.phase == RouteExecutionState.Phase.COMMIT && !mob.onGround())
                state.phase = RouteExecutionState.Phase.FALL;
            return state.committedDrop();
        }
        @Override public boolean canUse() {
            if (closed || !context.owns(mob)) return false;
            if (committedDrop()) return true;
            LivingEntity target = mob.getTarget();
            if (!eligible(mob, target) || (!waitingForChunk() && now() < state.nativeUntil)
                    || specialAction() || engaged(target)) return false;
            Path nativeRoute = mob.getNavigation().getPath();
            // Vanilla has already proved this particular path ends short of its target.
            // Begin the budgeted complete search now instead of spending the whole partial
            // route's travel time before recovery; reaching native routes retain their grace.
            boolean partialRoute = nativeRoute != null && !nativeRoute.canReach();
            if (state.plan == null && !progress.loopDetected()
                    && !partialRoute
                    && now() - Math.max(progress.lastRoute(), progress.lastCombat()) < 40) return false;
            reservedTarget = (ServerPlayer) target;
            return mob.onGround();
        }
        @Override public boolean canContinueToUse() {
            if (closed || !context.owns(mob)) return false;
            if (committedDrop()) return true;
            return (waitingForChunk() || now() >= state.nativeUntil) && eligible(mob, mob.getTarget())
                    && !specialAction() && !engaged(mob.getTarget());
        }
        @Override public void start() {
            // Conflicting native Goal.stop may clear the previously selected room target.
            if (!eligible(mob, mob.getTarget()) && eligible(mob, reservedTarget)) mob.setTarget(reservedTarget);
            state.controlledSince = now();
            state.path = null;
            if (!committedDrop()) planner.cancelValidation(mob.getUUID());
            if (state.phase == RouteExecutionState.Phase.NATIVE) state.phase = RouteExecutionState.Phase.WAIT;
        }
        @Override public void stop() {
            // GoalSelector pause is not cancellation of a plan or of a committed drop.
            boolean doorHandoff = state.phase == RouteExecutionState.Phase.NATIVE && state.edge() != null
                    && state.edge().action() == TraversalEdge.Action.NATIVE_INTERACTION;
            if (!committedDrop() && !doorHandoff) mob.getNavigation().stop();
            state.path = null;
        }
        @Override public void tick() {
            long now = now();
            if (state.committedDrop()) { drop(now); return; }
            validationWaiting = false;
            LivingEntity target = mob.getTarget();
            if (!eligible(mob, target)) { clearCachedRoute(); planner.cancelMob(mob.getUUID()); return; }
            MovementProfile actualProfile = MovementProfile.from(mob);
            if (!actualProfile.equals(profile)) {
                profile = actualProfile; replan(now, 0);
            }
            if (progress.loopDetected() && !loopHandled) {
                // No visited-position blacklist: the current plan is discarded, and finite progress clocks remain.
                TraversalEdge looping = state.edge();
                if (looping != null) planner.avoidEdge(mob.getUUID(), looping, now + 320);
                replan(now, 20); failures++; loopHandled = true;
            }
            if (!progress.loopDetected()) loopHandled = false;
            if (state.plan != null) {
                boolean sameTarget = state.plan.targetId().equals(target.getUUID());
                boolean sameTargetTile = NavigationGraphCache.TileKey.at(state.plan.targetSnapshot())
                        .equals(NavigationGraphCache.TileKey.at(target.position()))
                        && Math.abs(state.plan.targetSnapshot().y - target.getY()) <= 0.25;
                boolean moved = state.plan.targetSnapshot().distanceToSqr(target.position()) >= 4.0;
                boolean nearTail = state.edgeIndex >= state.plan.edges().size() - 2;
                if (!sameTarget || moved && (!sameTargetTile || nearTail)) replan(now, 0);
            }
            if (state.plan == null) {
                hold();
                if (!waitingForChunk() && now - state.controlledSince >= 20 && nativeTerrainAvailable(target)) {
                    state.nativeUntil = now + 4; state.phase = RouteExecutionState.Phase.NATIVE; return;
                }
                if (now < state.nextRequest) return;
                if (planningAlternative != null && !eligible(mob, planningAlternative)) planningAlternative = null;
                LivingEntity destination = planningAlternative == null ? target : planningAlternative;
                result = planner.requestPlan(mob, destination.position(), destination.getUUID(), profile);
                if (result.status() != PlanningResult.Status.READY) {
                    if (result.status() != PlanningResult.Status.UNREACHABLE) progress.waiting(now);
                    else {
                        state.nextRequest = now + 40;
                        chooseAlternative(destination);
                    }
                    return;
                }
                if (planningAlternative != null) {
                    mob.setTarget(planningAlternative); planningAlternative = null;
                }
                unreachableTargets.clear();
                state.usePlan(result.plan());
                // Native scheduling opportunities may have traversed part of the prefix
                // while this cold plan was being built. Rejoin a nearby short segment;
                // the ordinary execution validation still proves the actual approach.
                state.edgeIndex = routeEntryIndex(state.plan.edges(), mob.position());
                startEdge(now);
            }
            TraversalEdge edge = state.edge();
            if (edge == null) { replan(now, 0); return; }
            Vec3 commandEnd = edge.action() == TraversalEdge.Action.DROP
                    ? edge.controlPoints().get(1) : edge.to().feet();
            // Native opportunities and external forces can leave the previous short segment.
            // Such displacement needs a new start node, not an indefinitely pending proof.
            if (mob.position().distanceToSqr(commandEnd) > 9) { replan(now, 0); return; }
            if (edge.action() == TraversalEdge.Action.DROP) { drop(now); return; }
            if (mob.horizontalCollision && now >= nextCollisionProbe
                    && (edge.action() != TraversalEdge.Action.STEP || state.commandStarted && now - state.edgeStarted > 20)) {
                planner.cancelValidation(mob.getUUID()); nextCollisionProbe = now + 40;
            }
            if (planner.validator().isStanding(mob, edge.to(), profile, 0.22)) {
                arrive(edge, now, 0.22); return;
            }
            TraversalValidator.Verdict verdict = planner.requestValidation(mob, edge, profile);
            if (verdict == TraversalValidator.Verdict.STALE) { replan(now, 0); return; }
            if (verdict == TraversalValidator.Verdict.NATIVE_INTERACTION) {
                if (!nativeTerrainAvailable(target)) {
                    hold(); validationWaiting = true; progress.waiting(now); return;
                }
                // Supply the verified approach path so the existing OpenDoorGoal can discover its door.
                follow(edge); planner.cancelValidation(mob.getUUID());
                state.nativeUntil = now + 4; state.phase = RouteExecutionState.Phase.NATIVE; return;
            }
            if (verdict == TraversalValidator.Verdict.UNKNOWN || verdict == TraversalValidator.Verdict.WAITING_CHUNK) {
                hold(); validationWaiting = true; progress.waiting(now); return;
            }
            if (verdict != TraversalValidator.Verdict.CLEAR) {
                fail(edge, verdict == TraversalValidator.Verdict.BLOCKED
                        ? NavigationGraphCache.FailureEvidence.STATIC_COLLISION
                        : NavigationGraphCache.FailureEvidence.EXTERNAL_DISPLACEMENT, now);
                return;
            }
            beginCommand(now);
            if (commandStalled(now)) {
                boolean crowded = !mob.level().getEntities(mob, mob.getBoundingBox().inflate(0.25),
                        entity -> entity instanceof Mob && entity.isAlive()).isEmpty();
                fail(edge, crowded ? NavigationGraphCache.FailureEvidence.DYNAMIC_CONGESTION
                        : NavigationGraphCache.FailureEvidence.EXTERNAL_DISPLACEMENT, now); return;
            }
            switch (edge.action()) {
                case WALK, STEP -> follow(edge);
                case PRECISE_MOVE -> {
                    state.phase = RouteExecutionState.Phase.PRECISE;
                    NativeNavigationGuard.controlledMove(context, mob);
                    move(edge.to().feet());
                }
                case NATIVE_INTERACTION -> follow(edge);
                default -> { }
            }
        }
        private void follow(TraversalEdge edge) {
            state.phase = RouteExecutionState.Phase.FOLLOW;
            NativeNavigationGuard.follow(context, mob);
            Path actual = mob.getNavigation().getPath();
            if (actual != null && actual.isDone() && sameSegment(actual, edge)
                    && mob.position().distanceToSqr(edge.to().feet()) < 0.64 && mob.onGround()) {
                // Vanilla's waypoint tolerance is wider than physical portal arrival tolerance.
                // Finish the same proven edge without treating its Path cursor as actual arrival.
                state.phase = RouteExecutionState.Phase.PRECISE;
                NativeNavigationGuard.controlledMove(context, mob); move(edge.to().feet()); return;
            }
            if (state.path == null || actual == null || actual.isDone() || !sameSegment(actual, edge)) {
                mob.getNavigation().moveTo(shortPath(edge), 1.15);
                actual = mob.getNavigation().getPath();
                if (!sameSegment(actual, edge)) { replan(now(), 1); return; }
            }
            state.path = actual; // moveTo may have retained an equivalent old Path instance.
        }
        private void drop(long now) {
            TraversalEdge edge = state.edge();
            if (edge == null) { clearCachedRoute(); return; }
            NativeNavigationGuard.controlledMove(context, mob);
            mob.setXxa(0);
            Vec3 landing = edge.to().feet();
            if (state.phase == RouteExecutionState.Phase.FALL) {
                // A changed revision can affect a deep shaft. Finish falling naturally,
                // then establish a new proof from the actual landing; do not scan the
                // entire dependency map synchronously on every airborne tick.
                if (state.dropProofRevision != planner.cache().revision()) state.clearAfterLanding = true;
                if (mob.onGround()) {
                    state.phase = RouteExecutionState.Phase.LAND;
                } else {
                    // Hold the validated landing column; never aim at a moving target in the air.
                    mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
                    mob.setZza(0);
                    if (!planner.validator().validateDropDrift(mob, edge, profile)) state.clearAfterLanding = true;
                    return;
                }
            }
            if (state.phase == RouteExecutionState.Phase.LAND) {
                if (planner.validator().isStanding(mob, edge.to(), profile, 0.45) && !state.clearAfterLanding) {
                    arrive(edge, now, 0.45);
                } else replan(now, 1);
                state.clearAfterLanding = false;
                return;
            }
            if (!mob.onGround()) { state.phase = RouteExecutionState.Phase.FALL; state.previousY = mob.getY(); return; }
            TraversalValidator.Verdict verdict = planner.requestValidation(mob, edge, profile);
            if (verdict == TraversalValidator.Verdict.STALE) { replan(now, 0); return; }
            if (verdict == TraversalValidator.Verdict.UNKNOWN || verdict == TraversalValidator.Verdict.WAITING_CHUNK) {
                hold(); validationWaiting = true; progress.waiting(now); return;
            }
            if (verdict != TraversalValidator.Verdict.CLEAR) {
                fail(edge, NavigationGraphCache.FailureEvidence.EXTERNAL_DISPLACEMENT, now); return;
            }
            state.dropProofRevision = planner.cache().revision();
            beginCommand(now);
            Vec3 start = edge.from().feet();
            if (state.phase != RouteExecutionState.Phase.COMMIT && mob.position().distanceToSqr(start) > 0.12) {
                state.phase = RouteExecutionState.Phase.APPROACH; move(start); return;
            }
            if (state.phase != RouteExecutionState.Phase.COMMIT) {
                // Approach may take several ticks. Read the complete shaft again from the
                // settled departure position before issuing the first command over the edge.
                hold(); state.phase = RouteExecutionState.Phase.COMMIT;
                state.commandStarted = false;
                planner.cancelValidation(mob.getUUID()); return;
            }
            if (!planner.validator().validateDropDrift(mob, edge, profile)) {
                // Let native friction remove residual approach velocity before crossing the edge.
                mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
                return;
            }
            state.phase = RouteExecutionState.Phase.COMMIT;
            Vec3 offEdge = edge.controlPoints().size() < 2 ? new Vec3(landing.x, start.y, landing.z)
                    : edge.controlPoints().get(1);
            double attributeSpeed = mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            double speed = attributeSpeed <= 0 ? 0 : Math.min(1.15, 0.09 / attributeSpeed);
            mob.getMoveControl().setWantedPosition(offEdge.x, offEdge.y, offEdge.z, speed);
            if (commandStalled(now))
                fail(edge, NavigationGraphCache.FailureEvidence.EXTERNAL_DISPLACEMENT, now);
        }
        private boolean commandStalled(long now) {
            // Slow support surfaces can require more than 40 ticks for 0.2 blocks.
            // Monotonic local movement keeps the command alive without renewing the
            // independent route/combat/waiting clocks or crediting repeated oscillation.
            return now - state.edgeStarted > 80 && now - progress.lastRoute() > 40
                    && now - state.lastCommandMotion > 40;
        }
        private void move(Vec3 point) { mob.getMoveControl().setWantedPosition(point.x, point.y, point.z, 1.15); }
        private void hold() {
            NativeNavigationGuard.controlledMove(context, mob);
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
            mob.setXxa(0); mob.setZza(0);
        }
        private boolean waitingForChunk() { return result.status() == PlanningResult.Status.WAITING_CHUNK; }
        private void beginCommand(long now) {
            if (!state.commandStarted) {
                state.commandStarted = true; state.edgeStarted = now; state.lastCommandMotion = now;
            }
        }
        private void arrive(TraversalEdge edge, long now, double tolerance) {
            hold();
            TraversalValidator.Verdict verdict = planner.requestArrivalValidation(mob, edge.to(), profile, tolerance);
            if (verdict == TraversalValidator.Verdict.CLEAR) complete(now);
            else if (verdict == TraversalValidator.Verdict.BLOCKED || verdict == TraversalValidator.Verdict.STALE) replan(now, 1);
            else { validationWaiting = true; progress.waiting(now); }
        }
        private void startEdge(long now) {
            planner.cancelValidation(mob.getUUID());
            state.edgeStarted = now; state.commandStarted = false; state.path = null; state.previousY = mob.getY();
            state.dropProofRevision = Long.MIN_VALUE;
            state.furthestCommandProjection = 0; state.lastCommandMotion = now;
            state.edgeKey = state.edge() == null ? null : key(state.edge());
            state.phase = state.edge() != null && state.edge().action() == TraversalEdge.Action.DROP
                    ? RouteExecutionState.Phase.APPROACH : RouteExecutionState.Phase.FOLLOW;
            if (state.plan != null) planner.cache().pin(mob.getUUID(), state.plan.edges(), state.edgeIndex);
        }
        private void complete(long now) {
            TraversalEdge edge = state.edge();
            if (edge != null) {
                progress.completed(now, state.edgeKey);
                if (!edge.from().tile().equals(edge.to().tile()))
                    progress.portal(state.edgeKey);
            }
            state.edgeIndex++; startEdge(now);
            if (!waitingForChunk() && now - state.controlledSince >= 20
                    && nativeTerrainAvailable(mob.getTarget())) {
                mob.getNavigation().stop(); state.nativeUntil = now + 4;
                state.phase = RouteExecutionState.Phase.NATIVE;
            }
        }
        private void replan(long now, int delay) {
            LivingEntity destination = planningAlternative == null ? mob.getTarget() : planningAlternative;
            boolean repair = eligible(mob, destination) && planner.prepareRepair(mob, state.plan, state.edgeIndex,
                    destination.getUUID(), destination.position(), profile);
            clearCachedRoute();
            if (!repair) planner.cancelRequest(mob.getUUID());
            state.nextRequest = now + delay;
            validationWaiting = false;
            // A rejected precise/drop edge may still own MoveControl's previous command.
            // Stop that command in this tick, before native movement applies it again.
            hold();
        }
        private void clearCachedRoute() {
            result = PlanningResult.pending(PlanningResult.Reason.BUILD);
            state.clearRoute();
        }
        private void fail(TraversalEdge edge, NavigationGraphCache.FailureEvidence evidence, long now) {
            planner.reportExecutionFailure(edge, profile, evidence); failures++;
            replan(now, evidence == NavigationGraphCache.FailureEvidence.DYNAMIC_CONGESTION ? 20 : 1);
        }
        private void chooseAlternative(LivingEntity failed) {
            List<ServerPlayer> candidates;
            try {
                candidates = context.validPlayers().get();
                if (candidates == null) candidates = List.of();
            } catch (RuntimeException unavailable) { candidates = List.of(); }
            unreachableTargets.add(failed.getUUID());
            planningAlternative = candidates.stream().filter(Objects::nonNull).filter(player -> !unreachableTargets.contains(player.getUUID())
                            && eligible(mob, player)).min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
            if (planningAlternative != null) replan(now(), 0);
            else {
                // All eligible candidates were conclusively exhausted. A later terrain
                // recheck must not revive the completed round's computation allowance.
                progress.retireWaiting();
                unreachableTargets.clear(); replan(now(), 40); result = PlanningResult.unreachable();
            }
        }
        private boolean engaged(LivingEntity target) {
            if (target == null || mob instanceof Creeper) return false;
            double reach;
            if (mob.getType() == EntityType.WITHER_SKELETON &&
                    (mob.getMainHandItem().getItem() instanceof BowItem || mob.getOffhandItem().getItem() instanceof BowItem))
                reach = 225;
            else {
                double width = mob.getBbWidth() * 2;
                reach = width * width + target.getBbWidth();
            }
            return mob.distanceToSqr(target) <= reach && nativeTerrainAvailable(target)
                    && mob.getSensing().hasLineOfSight(target);
        }
        private boolean nativeTerrainAvailable(LivingEntity target) {
            if (target == null || target.level() != context.level()) return false;
            BlockPos targetPos = target.blockPosition();
            if (context.level().getChunkSource().getChunkNow(targetPos.getX() >> 4, targetPos.getZ() >> 4) == null)
                return false;
            // LivingEntity.hasLineOfSight returns without reading terrain beyond 128.
            Vec3 eye = mob.getEyePosition(), targetEye = target.getEyePosition();
            if (eye.distanceToSqr(targetEye) > 128 * 128) return true;
            // Match BlockGetter.traverseBlocks' endpoint extension so an eye exactly
            // on a chunk boundary cannot read the unchecked side of that boundary.
            double startX = net.minecraft.util.Mth.lerp(-1.0E-7, eye.x, targetEye.x);
            double endX = net.minecraft.util.Mth.lerp(-1.0E-7, targetEye.x, eye.x);
            double startZ = net.minecraft.util.Mth.lerp(-1.0E-7, eye.z, targetEye.z);
            double endZ = net.minecraft.util.Mth.lerp(-1.0E-7, targetEye.z, eye.z);
            int minX = net.minecraft.util.Mth.floor(Math.min(startX, endX)) >> 4;
            int maxX = net.minecraft.util.Mth.floor(Math.max(startX, endX)) >> 4;
            int minZ = net.minecraft.util.Mth.floor(Math.min(startZ, endZ)) >> 4;
            int maxZ = net.minecraft.util.Mth.floor(Math.max(startZ, endZ)) >> 4;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                if (context.level().getChunkSource().getChunkNow(x, z) == null) return false;
            return true;
        }
        private boolean specialAction() {
            if (mob.isPassenger() || mob.isInWaterOrBubble() || mob.isInLava()) return true;
            if (mob instanceof Creeper creeper && (creeper.isIgnited() || creeper.getSwellDir() > 0
                    || mob.getTarget() != null && mob.distanceToSqr(mob.getTarget()) < 9
                    && nativeTerrainAvailable(mob.getTarget())
                    && mob.getSensing().hasLineOfSight(mob.getTarget()))) return true;
            return mob.goalSelector.getRunningGoals().anyMatch(wrapped -> {
                Goal goal = wrapped.getGoal();
                return goal != this && !(goal instanceof TerrainWaitGoal)
                        && !(goal instanceof MeleeAttackGoal) && !(goal instanceof RangedBowAttackGoal<?>)
                        && !(goal instanceof SwellGoal) && (goal instanceof DoorInteractGoal
                        || goal.getFlags().contains(Flag.MOVE) || goal.getFlags().contains(Flag.JUMP));
            });
        }
    }
    /** Bounded local adoption; a distant start still falls back to the budgeted planner. */
    static int routeEntryIndex(List<TraversalEdge> edges, Vec3 position) {
        int nearest = 0;
        double best = 0.75 * 0.75;
        // This is an execution cursor adjustment, never a reachability or route-length cap.
        for (int index = 0; index < Math.min(64, edges.size()); index++) {
            TraversalEdge edge = edges.get(index);
            Vec3 from = edge.from().feet(), end = edge.to().feet();
            Vec3 delta = end.subtract(from);
            boolean local = edge.action() != TraversalEdge.Action.DROP
                    && edge.action() != TraversalEdge.Action.NATIVE_INTERACTION;
            double fraction = !local || delta.lengthSqr() < 1.0E-12 ? 0
                    : Math.max(0, Math.min(1, position.subtract(from).dot(delta) / delta.lengthSqr()));
            double distance = position.distanceToSqr(from.add(delta.scale(fraction)));
            if (distance < best - 1.0E-9) { nearest = index; best = distance; }
        }
        return nearest;
    }
    /** Immutable edge evidence is reusable; each native consumer owns its mutable path and nodes. */
    static Path shortPath(TraversalEdge edge) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(node(edge.from().feet())); nodes.add(node(edge.to().feet()));
        return new Path(nodes, nodePosition(edge.to().feet()), true);
    }
    static boolean sameSegment(Path path, TraversalEdge edge) {
        if (path == null || path.getNodeCount() == 0 || path.getNodeCount() > 2) return false;
        BlockPos from = nodePosition(edge.from().feet()), to = nodePosition(edge.to().feet());
        for (int i = 0; i < path.getNodeCount(); i++) {
            BlockPos pos = path.getNode(i).asBlockPos();
            if (!pos.equals(from) && !pos.equals(to)) return false;
        }
        return path.getEndNode().asBlockPos().equals(to);
    }
    private static BlockPos nodePosition(Vec3 feet) {
        return new BlockPos(net.minecraft.util.Mth.floor(feet.x), net.minecraft.util.Mth.ceil(feet.y), net.minecraft.util.Mth.floor(feet.z));
    }
    private static Node node(Vec3 feet) { BlockPos pos = nodePosition(feet); return new Node(pos.getX(), pos.getY(), pos.getZ()); }
    private static String key(TraversalEdge edge) {
        return edge.from().feet() + ">" + edge.to().feet() + ":" + edge.action() + ":" + edge.dependencies();
    }
}
