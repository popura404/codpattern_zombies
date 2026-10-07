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
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Supplemental N05 collision-shape evidence only; these are not entity-arrival tests. */
@GameTestHolder("codpattern_navigation_drop_shapes")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationDropShapeGameTests {
    private ZombiesNavigationDropShapeGameTests() { }

    @GameTest(template="zombies_navigation",batch="navigation_drop_shapes",timeoutTicks=400)
    public static void oneBlockShaftFitsZombieBodyAndValidatedDriftEnvelope(GameTestHelper helper) {
        terrain(helper);
        for (int y=1;y<=7;y++) {
            helper.setBlock(new BlockPos(6,y,5),Blocks.STONE);
            helper.setBlock(new BlockPos(8,y,5),Blocks.STONE);
            helper.setBlock(new BlockPos(7,y,4),Blocks.STONE);
            helper.setBlock(new BlockPos(7,y,6),Blocks.STONE);
        }
        scan(helper,"drop-shape-one-block-shaft",edges -> helper.assertTrue(hasDrop(helper,edges,1),
                "a one-block empty shaft must fit the zombie and the verified horizontal drift envelope"));
    }

    @GameTest(template="zombies_navigation",batch="navigation_drop_shapes",timeoutTicks=400)
    public static void slabIsTheFirstLandingAtItsActualHalfBlockHeight(GameTestHelper helper) {
        terrain(helper);
        helper.setBlock(new BlockPos(7,4,5),Blocks.STONE_SLAB);
        scan(helper,"drop-shape-slab-first-contact",edges -> {
            helper.assertTrue(hasDrop(helper,edges,4.5),"the first slab contact must retain its true fractional support height");
            helper.assertTrue(!hasDrop(helper,edges,1),"a slab cannot be skipped in favor of the deeper full-block floor");
        });
    }

    @GameTest(template="zombies_navigation",batch="navigation_drop_shapes",timeoutTicks=400)
    public static void lowDepartureCeilingBlocksTheWholeBodyBeforeFalling(GameTestHelper helper) {
        terrain(helper);
        helper.setBlock(new BlockPos(7,10,5),Blocks.STONE);
        scan(helper,"drop-shape-low-departure-ceiling",edges -> {
            Vec3 east=helper.absoluteVec(new Vec3(7.5,1,5.5));
            helper.assertTrue(edges.stream().noneMatch(edge -> edge.action()==TraversalEdge.Action.DROP
                            && Math.abs(edge.to().feet().x-east.x)<1e-6 && Math.abs(edge.to().feet().z-east.z)<1e-6),
                    "head collision at departure must reject every lower landing in that column");
        });
    }

    @GameTest(template="zombies_navigation_drop",batch="navigation_drop_shapes",timeoutTicks=400)
    public static void sixtyFourBlockShaftReusesOverlappingContextIndependentCells(GameTestHelper helper) {
        for (int x=2;x<=15;x++) for (int z=2;z<=15;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        for (int x=4;x<=6;x++) for (int z=4;z<=6;z++) helper.setBlock(new BlockPos(x,64,z),Blocks.STONE);
        scan(helper,"drop-shape-64-budget",new Vec3(6.5,65,5.5),new Vec3(19,70,19),600,
                edges -> helper.assertTrue(hasDrop(helper,edges,1),
                        "the bounded scan must still prove the actual first floor through all 64 shaft layers"));
    }

    @GameTest(template="zombies_navigation",batch="navigation_drop_shapes",timeoutTicks=400)
    public static void replacedFullBlockCannotAliasContextSensitiveScaffoldingAcrossFeetHeights(GameTestHelper helper) {
        for (int x=2;x<=15;x++) for (int z=2;z<=15;z++) helper.setBlock(new BlockPos(x,3,z),Blocks.STONE);
        BlockPos changed=new BlockPos(7,4,5);
        helper.setBlock(changed,Blocks.STONE);
        helper.setBlock(new BlockPos(6,4,5),Blocks.STONE);
        Vec3 lower=helper.absoluteVec(new Vec3(8.5,4,5.5));
        Vec3 upper=helper.absoluteVec(new Vec3(6.5,5,5.5));
        Vec3 lowerTarget=helper.absoluteVec(new Vec3(7.5,4,5.5));
        Vec3 upperTarget=helper.absoluteVec(new Vec3(7.5,5,5.5));
        BlockPos changedAbsolute=helper.absolutePos(changed);
        Mob mob=EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(mob!=null,"the cache fixture requires a zombie collision context");
        mob.moveTo(lower.x,lower.y,lower.z,0,0);mob.setNoAi(true);mob.setNoGravity(true);mob.setOnGround(true);
        mob.setPersistenceRequired();helper.getLevel().addFreshEntity(mob);
        NavigationGraphCache cache=new NavigationGraphCache();
        NavigationContext context=new NavigationContext(RoomId.of("zombies","drop-shape-cache-"+UUID.randomUUID()),
                helper.getLevel(),new AABB(helper.absoluteVec(new Vec3(1,0,1)),helper.absoluteVec(new Vec3(23,12,23))),
                List::of,candidate -> candidate==mob,UUID.randomUUID());
        TraversalValidator validator=new TraversalValidator(context,cache);
        MovementProfile actual=MovementProfile.from(mob);
        MovementProfile profile=new MovementProfile(actual.width(),actual.height(),actual.stepHeight(),
                actual.canOpenDoors(),actual.canPassDoors(),false,actual.collisionContext());
        SurfaceNode from=new SurfaceNode(lower,cache.version(NavigationGraphCache.TileKey.at(lower)));
        TraversalValidator.NeighborCursor[] cursor={validator.beginNeighbours(mob,from,profile,from.version())};
        List<TraversalEdge> edges=new ArrayList<>();
        int[] stage={0},units={0};boolean[] done={false},isolatedContextsObserved={false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            try {
                cache.updateTime(helper.getLevel().getGameTime());
                var batch=validator.advance(cursor[0],16,Long.MAX_VALUE);
                units[0]+=batch.workUsed();edges.addAll(batch.edges());
                if (stage[0]==2 && !isolatedContextsObserved[0]) {
                    Object lowerCell=cache.geometryCell(profile,lower.y,changedAbsolute);
                    Object upperCell=cache.geometryCell(profile,upper.y,changedAbsolute);
                    isolatedContextsObserved[0]=lowerCell!=null && upperCell!=null && lowerCell!=upperCell;
                }
                helper.assertTrue(batch.workUsed()<=16,"cache regression scans must retain their bounded geometry slices");
                helper.assertTrue(batch.status()!=TraversalValidator.ScanStatus.RESOURCE_LIMITED,
                        "the cache regression must finish without exhausting temporary storage");
                helper.assertTrue(helper.getTick()<395,"the fixed cache observation window cannot be extended");
                helper.assertTrue(mob.position().distanceToSqr(lower)<1e-8,"projected feet must never move the context entity");
                if (batch.status()!=TraversalValidator.ScanStatus.COMPLETE) return;
                boolean reached=edges.stream().anyMatch(edge -> edge.to().feet().distanceToSqr(stage[0]==2?upperTarget:lowerTarget)<1e-8);
                if (stage[0]==0) {
                    helper.assertTrue(!reached,"the initial full stone body must block the lower destination");
                    helper.assertTrue(cache.geometryCell(profile,Double.NEGATIVE_INFINITY,changedAbsolute)!=null,
                            "the first scan must actually populate the context-independent full-block cache");
                    cursor[0].close();edges.clear();
                    helper.setBlock(changed,Blocks.SCAFFOLDING.defaultBlockState()
                            .setValue(ScaffoldingBlock.DISTANCE,0).setValue(ScaffoldingBlock.BOTTOM,false));
                    cache.invalidate(new AABB(changedAbsolute),"test-full-block-replaced-by-context-sensitive-shape");
                    helper.assertTrue(cache.geometryCell(profile,Double.NEGATIVE_INFINITY,changedAbsolute)==null,
                            "tile invalidation must remove the old full-block alias before the new state is read");
                } else {
                    helper.assertTrue(reached,stage[0]==1
                            ?"feet below the scaffolding top must retain the real empty collision and floor-level walk"
                            :"feet above the scaffolding top must retain its solid support instead of reusing the lower empty shape");
                    helper.assertTrue(cache.geometryCell(profile,Double.NEGATIVE_INFINITY,changedAbsolute)==null,
                            "a context-sensitive block subclass must never enter the context-independent cache");
                    cursor[0].close();
                    if (stage[0]==2) {
                        helper.assertTrue(isolatedContextsObserved[0],
                                "both projected contexts must coexist as distinct entries before the normal cache TTL expires");
                        helper.assertTrue(cache.stats().transientEntries()==0,"every cache regression cursor must release its temporary references");
                        report("drop-shape-context-cache-invalidation",mob,edges,units[0],helper.getTick(),true,
                                "stone alias invalidated; lower and upper scaffolding collision contexts verified independently");
                        done[0]=true;mob.discard();cache.reset();helper.succeed();return;
                    }
                    helper.assertTrue(cache.geometryCell(profile,lower.y,changedAbsolute)!=null,
                            "the second scan must start while the lower projected collision context is still cached");
                    edges.clear();
                }
                stage[0]++;
                Vec3 source=stage[0]==2?upper:lower;
                SurfaceNode next=new SurfaceNode(source,cache.version(NavigationGraphCache.TileKey.at(source)));
                cursor[0]=validator.beginNeighbours(mob,next,profile,next.version());
            } catch (RuntimeException | Error failure) {
                done[0]=true;
                try { report("drop-shape-context-cache-invalidation",mob,edges,units[0],helper.getTick(),false,
                        "stage="+stage[0]+": "+failure.getMessage()); }
                finally { cursor[0].close();mob.discard();cache.reset(); }
                throw failure;
            }
        });
    }

    private static boolean hasDrop(GameTestHelper helper,List<TraversalEdge> edges,double feetY) {
        Vec3 expected=helper.absoluteVec(new Vec3(7.5,feetY,5.5));
        return edges.stream().anyMatch(edge -> edge.action()==TraversalEdge.Action.DROP && edge.to().feet().distanceToSqr(expected)<1e-8);
    }
    private static void terrain(GameTestHelper helper) {
        for (int x=2;x<=15;x++) for (int z=2;z<=15;z++) helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
        for (int x=4;x<=6;x++) for (int z=4;z<=6;z++) helper.setBlock(new BlockPos(x,8,z),Blocks.STONE);
    }
    private static void scan(GameTestHelper helper,String scenario,Consumer<List<TraversalEdge>> assertions) {
        scan(helper,scenario,new Vec3(6.5,9,5.5),new Vec3(23,12,23),Integer.MAX_VALUE,assertions);
    }
    private static void scan(GameTestHelper helper,String scenario,Vec3 relativeFeet,Vec3 relativeMaximum,
            int maximumGeometryUnits,Consumer<List<TraversalEdge>> assertions) {
        Mob mob=EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(mob!=null,"geometry fixture requires a zombie body context");
        Vec3 feet=helper.absoluteVec(relativeFeet);
        mob.moveTo(feet.x,feet.y,feet.z,0,0);mob.setNoAi(true);mob.setNoGravity(true);mob.setOnGround(true);
        mob.setPersistenceRequired();helper.getLevel().addFreshEntity(mob);
        NavigationGraphCache cache=new NavigationGraphCache();
        NavigationContext context=new NavigationContext(RoomId.of("zombies","drop-shape-"+UUID.randomUUID()),
                helper.getLevel(),new AABB(helper.absoluteVec(new Vec3(1,0,1)),helper.absoluteVec(relativeMaximum)),
                List::of,candidate -> candidate==mob,UUID.randomUUID());
        TraversalValidator validator=new TraversalValidator(context,cache);
        MovementProfile profile=MovementProfile.from(mob);
        var from=validator.locate(mob,feet,profile);
        helper.assertTrue(from!=null,"the known departure must have physical support and body clearance");
        var cursor=validator.beginNeighbours(mob,from,profile,from.version());
        List<TraversalEdge> edges=new ArrayList<>();
        boolean[] done={false};
        int[] geometryUnits={0};
        helper.onEachTick(() -> {
            if (done[0]) return;
            try {
                cache.updateTime(helper.getLevel().getGameTime());
                var batch=validator.advance(cursor,4,Long.MAX_VALUE);
                geometryUnits[0]+=batch.workUsed();
                helper.assertTrue(geometryUnits[0]<=maximumGeometryUnits,
                        "repeated overlap reads must fit the fixed geometry workload ceiling of "+maximumGeometryUnits);
                helper.assertTrue(batch.workUsed()<=4,"shape discovery must respect its per-tick geometry budget");
                helper.assertTrue(mob.position().distanceToSqr(feet)<1e-8,"hypothetical geometry may not move the context entity");
                helper.assertTrue(batch.status()!=TraversalValidator.ScanStatus.RESOURCE_LIMITED,
                        "the small shape fixture cannot silently exhaust its resource budget");
                helper.assertTrue(helper.getTick()<395,"the fixed geometry observation window cannot be extended");
                edges.addAll(batch.edges());
                if (batch.status()==TraversalValidator.ScanStatus.COMPLETE) {
                    assertions.accept(edges);
                    cursor.close();
                    helper.assertTrue(cache.stats().transientEntries()==0,"completed shape cursors must release all temporary references");
                    report(scenario,mob,edges,geometryUnits[0],helper.getTick(),true,"shape assertions passed");
                    done[0]=true;mob.discard();cache.reset();helper.succeed();
                }
            } catch (RuntimeException | Error failure) {
                done[0]=true;
                try { report(scenario,mob,edges,geometryUnits[0],helper.getTick(),false,failure.getMessage()); }
                finally { cursor.close();mob.discard();cache.reset(); }
                throw failure;
            }
        });
    }
    private static void report(String scenario,Mob mob,List<TraversalEdge> edges,int units,long ticks,boolean success,String reason) {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("supplementalCoverage",true);report.put("geometryEvidenceOnly",true);report.put("functionalArrivalEvidence",false);
        report.put("preImplementationBaseline",false);report.put("uuid",mob.getUUID().toString());
        report.put("elapsedTicks",ticks);report.put("geometryUnits",units);
        report.put("dropLandings",edges.stream().filter(edge -> edge.action()==TraversalEdge.Action.DROP)
                .map(edge -> List.of(edge.to().feet().x,edge.to().feet().y,edge.to().feet().z)).toList());
        report.put("success",success);report.put("reason",reason);ZombiesNavigationTestReport.write(scenario,report);
    }
}
