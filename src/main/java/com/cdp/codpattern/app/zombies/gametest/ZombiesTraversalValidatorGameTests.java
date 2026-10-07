package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.service.navigation.MovementProfile;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationContext;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationGraphCache;
import com.cdp.codpattern.app.zombies.service.navigation.SurfaceNode;
import com.cdp.codpattern.app.zombies.service.navigation.TraversalEdge;
import com.cdp.codpattern.app.zombies.service.navigation.TraversalValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Geometry evidence tests complement, and do not replace, original-entity arrival tests. */
@GameTestHolder("codpattern_navigation")
@PrefixGameTestTemplate(false)
public final class ZombiesTraversalValidatorGameTests {
    private ZombiesTraversalValidatorGameTests() { }

    @GameTest(template="zombies_navigation",batch="layered_geometry",timeoutTicks=40)
    public static void sameColumnHasDistinctFloorsAndBodyProfiles(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(5,5,5),Blocks.STONE);
        helper.setBlock(new BlockPos(9,2,5),Blocks.STONE);
        try (Fixture fixture=new Fixture(helper,new Vec3(5.5,1,5.5))) {
            SurfaceNode lower=fixture.validator.locate(fixture.mob,fixture.absolute(5.5,1,5.5),fixture.profile);
            SurfaceNode upper=fixture.validator.locate(fixture.mob,fixture.absolute(5.5,6,5.5),fixture.profile);
            helper.assertTrue(lower!=null && upper!=null && !lower.equals(upper),
                    "stacked supports in one column must remain separate nodes");
            Vec3 lowCeiling=fixture.absolute(9.5,1,5.5);
            helper.assertTrue(fixture.validator.locate(fixture.mob,lowCeiling,fixture.profile)==null,
                    "the zombie's entire body must fit under the ceiling");
            MovementProfile small=new MovementProfile(0.4,0.3,1,false,true,true,"small-test-context");
            helper.assertTrue(fixture.validator.locate(fixture.mob,lowCeiling,small)!=null,
                    "a small body can use clearance rejected for the taller profile");
        }
        helper.succeed();
    }

    @GameTest(template="zombies_navigation",batch="layered_geometry",timeoutTicks=400)
    public static void slabStepUsesItsActualFractionalSupportHeight(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(6,1,5),Blocks.STONE_SLAB);
        Fixture fixture=new Fixture(helper,new Vec3(5.5,1,5.5));
        SurfaceNode start=fixture.validator.locate(fixture.mob,fixture.mob.position(),fixture.profile);
        helper.assertTrue(start!=null,"slab fixture requires a supported starting point");
        var neighbours=fixture.validator.beginNeighbours(fixture.mob,start,fixture.profile,start.version());
        List<TraversalEdge> edges=new ArrayList<>();
        TraversalValidator.ValidationCursor[] validation={null};
        boolean[] done={false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            try {
                if (validation[0]==null) {
                    var batch=fixture.validator.advance(neighbours,4,Long.MAX_VALUE);
                    edges.addAll(batch.edges());
                    if (batch.status()!=TraversalValidator.ScanStatus.COMPLETE) return;
                    Vec3 desired=fixture.absolute(6.5,1.5,5.5);
                    TraversalEdge step=edges.stream().filter(edge -> edge.action()==TraversalEdge.Action.STEP
                                    && edge.to().feet().distanceToSqr(desired)<1.0E-8).findFirst()
                            .orElseThrow(() -> new AssertionError("lower slab must expose a half-block STEP"));
                    // Native arrival uses a tolerance, so the next step must accept the actual
                    // supported feet and rerun its sweeps without requiring an exact grid centre.
                    Vec3 actual=fixture.absolute(5.42,1,5.58);
                    fixture.mob.moveTo(actual.x,actual.y,actual.z,0,0); fixture.mob.setOnGround(true);
                    validation[0]=fixture.validator.beginValidation(fixture.mob,step,fixture.profile);
                    return;
                }
                var batch=fixture.validator.advanceValidation(validation[0],4,Long.MAX_VALUE);
                helper.assertTrue(batch.workUsed()<=4,"step execution proof must retain its work cursor");
                if (batch.status()==TraversalValidator.ScanStatus.COMPLETE) {
                    helper.assertTrue(batch.verdict()==TraversalValidator.Verdict.CLEAR,
                            "a supported off-centre arrival must retain a physically clear native STEP");
                    done[0]=true; fixture.close(); helper.succeed();
                }
            } catch (RuntimeException | Error failure) { done[0]=true; fixture.close(); throw failure; }
        });
    }

    @GameTest(template="zombies_navigation",batch="layered_geometry",timeoutTicks=400)
    public static void dropStopsAtTheFirstIntermediatePlatform(GameTestHelper helper) {
        dropFixture(helper,false);
        Fixture fixture=new Fixture(helper,new Vec3(6.5,9,5.5));
        scan(helper,fixture,edges -> {
            Vec3 first=fixture.absolute(7.5,5,5.5), lower=fixture.absolute(7.5,1,5.5);
            helper.assertTrue(edges.stream().anyMatch(edge -> edge.action()==TraversalEdge.Action.DROP
                            && edge.to().feet().distanceToSqr(first)<1.0E-8),
                    "a drop must land on its first intermediate platform");
            helper.assertTrue(edges.stream().noneMatch(edge -> edge.action()==TraversalEdge.Action.DROP
                            && edge.to().feet().distanceToSqr(lower)<1.0E-8),
                    "a drop cannot tunnel through the first platform to the lower floor");
        });
    }

    @GameTest(template="zombies_navigation",batch="layered_geometry",timeoutTicks=400)
    public static void dangerousFirstContactCannotBeSkipped(GameTestHelper helper) {
        dropFixture(helper,true);
        Fixture fixture=new Fixture(helper,new Vec3(6.5,9,5.5));
        scan(helper,fixture,edges -> {
            Vec3 east=fixture.absolute(7.5,1,5.5);
            helper.assertTrue(edges.stream().noneMatch(edge -> edge.action()==TraversalEdge.Action.DROP
                            && Math.abs(edge.to().feet().x-east.x)<1.0E-6
                            && Math.abs(edge.to().feet().z-east.z)<1.0E-6),
                    "a hazardous first contact rejects the entire departure, including any deeper floor");
        });
    }

    @GameTest(template="zombies_navigation",batch="layered_geometry",timeoutTicks=400)
    public static void executionProofReadsFreshShapesAfterPlanningCacheWasFilled(GameTestHelper helper) {
        floor(helper);
        Fixture fixture=new Fixture(helper,new Vec3(5.5,1,5.5));
        SurfaceNode start=fixture.validator.locate(fixture.mob,fixture.mob.position(),fixture.profile);
        helper.assertTrue(start!=null,"cache fixture requires a supported starting point");
        var neighbours=fixture.validator.beginNeighbours(fixture.mob,start,fixture.profile,start.version());
        try (var pressure=fixture.cache.transientLease()) {
            helper.assertTrue(pressure.reserve(fixture.cache.stats().transientLimit()),
                    "the fixture must fill the temporary geometry reference budget");
            var blocked=fixture.validator.advance(neighbours,4,Long.MAX_VALUE);
            helper.assertTrue(blocked.status()==TraversalValidator.ScanStatus.RESOURCE_LIMITED && blocked.edges().isEmpty(),
                    "capacity pressure must pause the cursor without claiming an impossible route");
        }
        List<TraversalEdge> edges=new ArrayList<>();
        TraversalValidator.ValidationCursor[] validation={null};
        boolean[] done={false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            try {
                if (validation[0]==null) {
                    var batch=fixture.validator.advance(neighbours,4,Long.MAX_VALUE);
                    edges.addAll(batch.edges());
                    if (batch.status()!=TraversalValidator.ScanStatus.COMPLETE) return;
                    Vec3 end=fixture.absolute(6.5,1,5.5);
                    TraversalEdge east=edges.stream().filter(edge -> edge.to().feet().distanceToSqr(end)<1.0E-8)
                            .findFirst().orElseThrow(() -> new AssertionError("clear cached edge missing"));
                    for (int y=1;y<=3;y++) helper.setBlock(new BlockPos(6,y,5),Blocks.STONE);
                    // Deliberately do not call cache.invalidate: execution must reread unannounced changes.
                    validation[0]=fixture.validator.beginValidation(fixture.mob,east,fixture.profile);
                    neighbours.close();
                    return;
                }
                var batch=fixture.validator.advanceValidation(validation[0],4,Long.MAX_VALUE);
                helper.assertTrue(batch.workUsed()<=4,"fresh execution proof shares the geometry work budget");
                if (batch.status()==TraversalValidator.ScanStatus.COMPLETE) {
                    helper.assertTrue(batch.verdict()==TraversalValidator.Verdict.BLOCKED,
                            "new wall must invalidate physical execution even when its planning tile is cached");
                    validation[0].close();
                    helper.assertTrue(fixture.cache.stats().transientEntries()==0,
                            "completed geometry cursors must release every temporary reference");
                    done[0]=true; fixture.close(); helper.succeed();
                }
            } catch (RuntimeException | Error failure) { done[0]=true; fixture.close(); throw failure; }
        });
    }

    private static void scan(GameTestHelper helper,Fixture fixture,Consumer<List<TraversalEdge>> assertions) {
        SurfaceNode start=fixture.validator.locate(fixture.mob,fixture.mob.position(),fixture.profile);
        helper.assertTrue(start!=null,"fixture start must have real support");
        var cursor=fixture.validator.beginNeighbours(fixture.mob,start,fixture.profile,start.version());
        Vec3 unchanged=fixture.mob.position();
        List<TraversalEdge> edges=new ArrayList<>();
        boolean[] done={false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            try {
                var batch=fixture.validator.advance(cursor,4,Long.MAX_VALUE);
                helper.assertTrue(batch.workUsed()<=4,"a tick cannot spend more than its geometry budget");
                helper.assertTrue(fixture.mob.position().distanceToSqr(unchanged)<1.0E-8,
                        "hypothetical surface validation must never relocate the actual entity");
                helper.assertTrue(batch.status()!=TraversalValidator.ScanStatus.RESOURCE_LIMITED,
                        "this small fixture must not exhaust shape capacity");
                edges.addAll(batch.edges());
                if (batch.status()==TraversalValidator.ScanStatus.COMPLETE) {
                    assertions.accept(edges); done[0]=true; fixture.close(); helper.succeed();
                }
            } catch (RuntimeException | Error failure) { done[0]=true; fixture.close(); throw failure; }
        });
    }

    private static void floor(GameTestHelper helper) {
        for (int x=2;x<=15;x++) for (int z=2;z<=15;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
    }
    private static void dropFixture(GameTestHelper helper,boolean danger) {
        floor(helper);
        for (int x=4;x<=6;x++) for (int z=4;z<=6;z++) helper.setBlock(new BlockPos(x,8,z),Blocks.STONE);
        helper.setBlock(new BlockPos(7,4,5),danger?Blocks.MAGMA_BLOCK:Blocks.STONE);
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Mob mob;
        final MovementProfile profile;
        final TraversalValidator validator;
        final NavigationGraphCache cache=new NavigationGraphCache();
        Fixture(GameTestHelper helper,Vec3 relative) {
            this.helper=helper;
            mob=EntityType.ZOMBIE.create(helper.getLevel());
            if (mob==null) throw new AssertionError("fixture zombie unavailable");
            Vec3 feet=helper.absoluteVec(relative);
            mob.moveTo(feet.x,feet.y,feet.z,0,0); mob.setNoAi(true); mob.setNoGravity(true); mob.setOnGround(true);
            mob.setPersistenceRequired(); helper.getLevel().addFreshEntity(mob);
            profile=MovementProfile.from(mob);
            Vec3 min=helper.absoluteVec(new Vec3(1,0,1)),max=helper.absoluteVec(new Vec3(23,12,23));
            NavigationContext context=new NavigationContext(RoomId.of("zombies","geometry-"+UUID.randomUUID()),
                    helper.getLevel(),new AABB(min,max),List::of,candidate -> candidate==mob,UUID.randomUUID());
            validator=new TraversalValidator(context,cache);
        }
        Vec3 absolute(double x,double y,double z) { return helper.absoluteVec(new Vec3(x,y,z)); }
        @Override public void close() { mob.discard();cache.reset(); }
    }
}
