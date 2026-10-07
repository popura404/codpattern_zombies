package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationGameTests;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Independent movement calibration, never a planner correctness or functional arrival test. */
@GameTestHolder("codpattern_navigation_reference")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationReferenceGameTests {
    private static final int COLLECTION_LIMIT=5000;
    private static final double WALK_MODIFIER=1.15;
    private static final double COMMIT_SPEED=.09;
    private static final double ARRIVAL_TOLERANCE=.08;
    private static final int WITNESS_SEGMENT_BLOCKS=8;
    private ZombiesNavigationReferenceGameTests() { }

    @GameTest(setupTicks = 20, template="zombies_navigation_detour",batch="navigation_reference",timeoutTicks=COLLECTION_LIMIT+10)
    public static void independentlyCalibratesKnownStoneDetour(GameTestHelper helper) {
        shell(helper,14,7,96);
        for (int z=1;z<=88;z++) for (int y=1;y<=5;y++) helper.setBlock(new BlockPos(6,y,z),Blocks.STONE);
        start(helper,0,new BlockPos(14,7,96),new BlockPos(3,1,3),new Vec3(10.5,1,3.5));
    }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_reference",timeoutTicks=COLLECTION_LIMIT+10)
    public static void independentlyCalibratesEightBlockNaturalDrop(GameTestHelper helper) { drop(helper,8); }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_reference",timeoutTicks=COLLECTION_LIMIT+10)
    public static void independentlyCalibratesThirtyTwoBlockNaturalDrop(GameTestHelper helper) { drop(helper,32); }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_reference",timeoutTicks=COLLECTION_LIMIT+10)
    public static void independentlyCalibratesSixtyFourBlockNaturalDrop(GameTestHelper helper) { drop(helper,64); }

    private static void drop(GameTestHelper helper,int depth) {
        shell(helper,20,70,20);
        for (int x=3;x<=11;x++) for (int z=3;z<=11;z++) helper.setBlock(new BlockPos(x,depth,z),Blocks.STONE);
        start(helper,depth,new BlockPos(20,70,20),new BlockPos(8,depth+1,8),new Vec3(8.5,1,8.5));
    }

    private static void start(GameTestHelper helper,int depth,BlockPos size,BlockPos spawn,Vec3 target) {
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,target,size);
        ZombiesMap map=new ZombiesMap(helper.getLevel(),fixture.roomId().mapName(),
                new AreaData(helper.absolutePos(BlockPos.ZERO),helper.absolutePos(size)));
        FPSMCore.getInstance().registerMap(map.getGameType(),map);
        try {
            Mob mob=fixture.spawn("zombie",spawn);
            double speed=mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
            // The reference shares spawning attributes and production fall protection only.
            // Detach every production navigation controller before the first measured entity tick.
            fixture.spawnService.resetNavigationRuntime();
            removeMovementGoals(mob.goalSelector);removeMovementGoals(mob.targetSelector);
            mob.setTarget(null);mob.getNavigation().stop();
            helper.assertTrue(LayeredNavigationRuntime.of(mob)==null && !mob.isNoAi() && !mob.isNoGravity(),
                    "the reference must use ordinary entity ticks and gravity without the layered runtime");
            helper.assertTrue(mob.getAttributeValue(Attributes.MOVEMENT_SPEED)==speed,
                    "calibration must preserve the production spawn's movement attribute");
            Run run=new Run(helper,fixture,map,mob,depth,speed);
            helper.onEachTick(run::tick);
        } catch (RuntimeException | Error failure) { close(fixture,map);throw failure; }
    }

    private static void removeMovementGoals(GoalSelector selector) {
        selector.getAvailableGoals().stream().map(wrapped -> wrapped.getGoal())
                .filter(goal -> goal.getFlags().contains(Goal.Flag.MOVE)
                        || goal.getFlags().contains(Goal.Flag.JUMP) || goal.getFlags().contains(Goal.Flag.TARGET))
                .toList().forEach(selector::removeGoal);
    }

    private enum Phase { DETOUR, APPROACH, BRAKE, COMMIT, FALL, RESUME }
    private static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final ZombiesMap map;
        final Mob mob;
        final UUID originalId;
        final int depth;
        final long startedTick;
        final double attributeSpeed;
        final float initialHealth;
        final Vec3 initialFeet,target,edgeApproach,departure;
        final Map<String,Long> phaseTicks=new LinkedHashMap<>();
        final List<Map<String,Object>> trajectory=new ArrayList<>();
        final List<List<Double>> witness=new ArrayList<>();
        final List<Vec3> detourWaypoints=new ArrayList<>();
        final List<Map<String,Object>> nativeCancellations=new ArrayList<>();
        int detourSegment;
        int segmentRestarts;
        Phase phase;
        Vec3 previous;
        Path path;
        boolean launched,landed,finished;
        double travelled;
        long phaseStartedTick;
        Run(GameTestHelper helper,ZombiesNavigationGameTests.Fixture fixture,ZombiesMap map,Mob mob,int depth,double speed) {
            this.helper=helper;this.fixture=fixture;this.map=map;this.mob=mob;this.depth=depth;
            attributeSpeed=speed;initialHealth=mob.getHealth();originalId=mob.getUUID();startedTick=helper.getTick();
            phaseStartedTick=startedTick;initialFeet=mob.position();previous=initialFeet;target=fixture.player.position();
            edgeApproach=helper.absoluteVec(new Vec3(11.5,depth+1,8.5));
            // Clear the platform edge by more than MoveControl's ~0.0005 arrival dead zone.
            departure=helper.absoluteVec(new Vec3(12+mob.getBbWidth()/2.0+.001,depth+1,8.5));
            phase=depth==0?Phase.DETOUR:Phase.APPROACH;
            if (depth==0) {
                witness.add(position(initialFeet));witness.add(position(helper.absoluteVec(new Vec3(3.5,1,90.5))));
                witness.add(position(helper.absoluteVec(new Vec3(10.5,1,90.5))));witness.add(position(target));
                for (int z=3;z<90;) {
                    z=Math.min(90,z+WITNESS_SEGMENT_BLOCKS);
                    detourWaypoints.add(helper.absoluteVec(new Vec3(3.5,1,z+.5)));
                }
                detourWaypoints.add(helper.absoluteVec(new Vec3(10.5,1,90.5)));
                for (int z=90;z>3;) {
                    z=Math.max(3,z-WITNESS_SEGMENT_BLOCKS);
                    detourWaypoints.add(helper.absoluteVec(new Vec3(10.5,1,z+.5)));
                }
            } else {
                witness.add(position(initialFeet));witness.add(position(edgeApproach));witness.add(position(departure));
                witness.add(position(new Vec3(departure.x,target.y,departure.z)));witness.add(position(target));
            }
        }
        void tick() {
            if (finished) return;
            try {
                helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(originalId)==mob,
                        "calibration requires the original living reference UUID");
                helper.assertTrue(LayeredNavigationRuntime.of(mob)==null,
                        "a DUT navigation controller cannot supply the reference traversal");
                helper.assertTrue(mob.getTarget()==null,"the reference follows only its independently specified witness");
                helper.assertTrue(mob.position().distanceTo(previous)<12,"reference motion cannot include a teleport");
                helper.assertTrue(mob.getHealth()==initialHealth,"reference descent must use production fall protection");
                travelled+=mob.position().distanceTo(previous);previous=mob.position();
                if (helper.getTick()%5==0) sample();
                switch (phase) {
                    case DETOUR -> {
                        Vec3 destination=detourWaypoints.get(detourSegment);
                        if (path==null) installLinePath(BlockPos.containing(mob.position()),BlockPos.containing(destination));
                        finishNativeWalk(destination);
                        if (arrived(destination)) {
                            stopInputs();path=null;segmentRestarts=0;detourSegment++;
                            if (detourSegment==detourWaypoints.size())
                                finish(true,"known stone witness traversed by independent fixed native segments");
                        }
                    }
                    case APPROACH -> {
                        if (path==null) installLinePath(BlockPos.containing(initialFeet),BlockPos.containing(edgeApproach));
                        finishNativeWalk(edgeApproach);
                        if (arrived(edgeApproach)) { stopInputs();change(Phase.BRAKE); }
                    }
                    case BRAKE -> {
                        stopInputs();
                        if (mob.onGround() && Math.abs(mob.getDeltaMovement().x)<=.018
                                && Math.abs(mob.getDeltaMovement().z)<=.018) change(Phase.COMMIT);
                    }
                    case COMMIT -> {
                        if (!mob.onGround()) { launched=true;stopInputs();change(Phase.FALL); }
                        else {
                            mob.getNavigation().stop();mob.setXxa(0);
                            mob.getMoveControl().setWantedPosition(departure.x,departure.y,departure.z,
                                    attributeSpeed<=0?0:Math.min(WALK_MODIFIER,COMMIT_SPEED/attributeSpeed));
                        }
                    }
                    case FALL -> {
                        stopInputs();
                        if (mob.onGround()) {
                            helper.assertTrue(Math.abs(mob.getY()-target.y)<1e-6,"reference must contact the known lower stone floor");
                            landed=true;change(Phase.RESUME);
                        }
                    }
                    case RESUME -> {
                        if (path==null) installLinePath(BlockPos.containing(mob.position()),BlockPos.containing(target));
                        finishNativeWalk(target);
                        if (arrived(target)) {
                            helper.assertTrue(launched && landed,"reference DROP must include natural departure and real landing");
                            finish(true,"known edge departure, natural gravity, and native ground continuation completed");
                        }
                    }
                }
                if (!finished && helper.getTick()-startedTick>=COLLECTION_LIMIT)
                    finish(false,"independent collection ceiling reached during "+phase);
            } catch (RuntimeException | Error failure) {
                if (!finished) finish(false,failure.getMessage());
                throw failure;
            }
        }
        boolean arrived(Vec3 feet) {
            return mob.onGround() && Math.abs(mob.getY()-feet.y)<1e-6 && mob.position().distanceToSqr(feet)<ARRIVAL_TOLERANCE*ARRIVAL_TOLERANCE;
        }
        void change(Phase next) {
            phaseTicks.merge(phase.name(),helper.getTick()-phaseStartedTick,Long::sum);
            phase=next;phaseStartedTick=helper.getTick();path=null;sample();
        }
        void finishNativeWalk(Vec3 destination) {
            if (mob.getNavigation().isDone() && !arrived(destination)) {
                if (mob.position().distanceToSqr(destination)<.64) {
                    mob.getMoveControl().setWantedPosition(destination.x,destination.y,destination.z,WALK_MODIFIER);
                    return;
                }
                // Vanilla accumulates timeoutTimer across nodes and moveTo(Path) does not reset it.
                // Its public timeout/stop can therefore cancel a progressing manually supplied witness.
                // Reissue only the remainder of this same known straight ground segment. No planner,
                // private timeout reset, distant MoveControl shortcut, or changed deadline is involved.
                Path actual=mob.getNavigation().getPath();
                nativeCancellations.add(Map.of("tick",helper.getTick()-startedTick,"segment",detourSegment,
                        "feet",position(mob.position()),"destination",position(destination),
                        "pathPresent",actual!=null,"pathNodes",actual==null?0:actual.getNodeCount(),
                        "pathIndex",actual==null?0:actual.getNextNodeIndex(),"stuck",mob.getNavigation().isStuck()));
                helper.assertTrue(++segmentRestarts<=8,"the independent witness may retry a cancelled segment at most eight times");
                Vec3 origin=phase==Phase.DETOUR && detourSegment>0?detourWaypoints.get(detourSegment-1)
                        :phase==Phase.RESUME?new Vec3(departure.x,target.y,departure.z):initialFeet;
                Vec3 delta=destination.subtract(origin);
                boolean xLine=Math.abs(delta.z)<1e-6;
                helper.assertTrue(mob.onGround() && Math.abs(mob.getY()-destination.y)<1e-6
                                && Math.abs(xLine?mob.getZ()-origin.z:mob.getX()-origin.x)<.1,
                        "a native cancellation cannot move the reference off its fixed witness corridor");
                installLinePath(BlockPos.containing(mob.position()),BlockPos.containing(destination));
            }
        }
        void installLinePath(BlockPos from,BlockPos to) {
            helper.assertTrue(from.getY()==to.getY() && (from.getX()==to.getX() || from.getZ()==to.getZ()),
                    "the independent witness may contain only known level cardinal ground segments");
            List<Node> nodes=new ArrayList<>();int x=from.getX(),z=from.getZ();
            nodes.add(node(from));
            while (x!=to.getX() || z!=to.getZ()) {
                x+=Integer.compare(to.getX(),x);z+=Integer.compare(to.getZ(),z);
                nodes.add(new Node(x,to.getY(),z));
            }
            install(nodes,to);
        }
        void install(List<Node> nodes,BlockPos end) {
            path=new Path(nodes,end,true);
            helper.assertTrue(mob.getNavigation().moveTo(path,WALK_MODIFIER),"native navigation must accept the independent witness Path");
        }
        void stopInputs() {
            mob.getNavigation().stop();mob.setXxa(0);mob.setZza(0);
            mob.getMoveControl().setWantedPosition(mob.getX(),mob.getY(),mob.getZ(),0);
        }
        void sample() {
            trajectory.add(Map.of("tick",helper.getTick()-startedTick,"feet",position(mob.position()),
                    "phase",phase.name(),"onGround",mob.onGround()));
        }
        void finish(boolean success,String reason) {
            finished=true;phaseTicks.merge(phase.name(),helper.getTick()-phaseStartedTick,Long::sum);sample();stopInputs();
            long measured=helper.getTick()-startedTick;
            long formula=Math.max(400,(long)Math.ceil(measured*3.0)+200);
            int frozen=depth==0?3200:600;
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("calibration",true);report.put("referenceControlsIndependent",true);
            report.put("functionalArrivalEvidence",false);report.put("referenceMeasuredAfterImplementation",true);
            report.put("referencePlanner","none; manually specified witness Path/MoveControl, ordinary entity ticks and gravity");
            report.put("referenceUuid",originalId.toString());report.put("startFeet",position(initialFeet));report.put("endFeet",position(mob.position()));
            report.put("targetFeet",position(target));report.put("movementAttribute",attributeSpeed);
            report.put("walkSpeedModifier",WALK_MODIFIER);report.put("commitActualSpeed",COMMIT_SPEED);
            report.put("arrivalTolerance",ARRIVAL_TOLERANCE);report.put("initialHealth",initialHealth);report.put("endHealth",mob.getHealth());
            report.put("referenceTraversalTicks",success?measured:null);report.put("observedTicks",measured);
            report.put("formulaDeadlineTicks",success?formula:null);report.put("frozenFunctionalDeadlineTicks",frozen);
            report.put("frozenDeadlineStricterThanFormula",success && frozen<formula);
            report.put("functionalDeadlineChanged",false);report.put("collectionCeilingTicks",COLLECTION_LIMIT);
            report.put("phaseTicks",phaseTicks);report.put("knownWitnessControlPoints",witness);
            report.put("maximumWitnessSegmentBlocks",WITNESS_SEGMENT_BLOCKS);
            report.put("detourWitnessSegments",detourWaypoints.size());report.put("completedDetourSegments",detourSegment);
            report.put("nativeWitnessCancellations",nativeCancellations);
            report.put("maximumCancelledSegmentRetries",8);
            report.put("travelledDistance",travelled);report.put("naturalDepartureObserved",launched);report.put("realLandingObserved",landed);
            report.put("trajectorySampleEveryTicks",5);report.put("trajectory",trajectory);report.put("success",success);report.put("reason",reason);
            try { ZombiesNavigationTestReport.write(depth==0?"reference-detour-over-128":"reference-drop-"+depth,report); }
            finally { close(fixture,map); }
            helper.assertTrue(success,reason);helper.succeed();
        }
    }
    private static Node node(BlockPos pos) { return new Node(pos.getX(),pos.getY(),pos.getZ()); }
    private static List<Double> position(Vec3 feet) { return List.of(feet.x,feet.y,feet.z); }
    private static void close(ZombiesNavigationGameTests.Fixture fixture,ZombiesMap map) {
        fixture.close();
        try { map.resetGame(); }
        finally { FPSMCore.getInstance().unregisterMap(map);map.getMapTeams().retireCreatedScoreboardTeams(); }
    }
    private static void shell(GameTestHelper helper,int width,int height,int depth) {
        for (int x=0;x<width;x++) for (int z=0;z<depth;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);helper.setBlock(new BlockPos(x,height-1,z),Blocks.STONE);
            if (x==0 || x==width-1 || z==0 || z==depth-1)
                for (int y=1;y<height-1;y++) helper.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
    }
}
