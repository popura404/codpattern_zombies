package com.cdp.codpattern.app.zombies.service.navigation;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real slab/head collision changes, including the completed planner proof reuse path. */
@GameTestHolder("codpattern_navigation_arrival")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationArrivalGameTests {
    private ZombiesNavigationArrivalGameTests() { }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_arrival",timeoutTicks=110)
    public static void halfSlabHeadTileChangeDuringFramedArrivalCannotPublishStaleClear(GameTestHelper helper) {
        start(helper,false);
    }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_arrival",timeoutTicks=110)
    public static void completedHalfSlabArrivalProofRechecksChangedHeadTileBeforeReuse(GameTestHelper helper) {
        start(helper,true);
    }

    private static void start(GameTestHelper helper,boolean cached) {
        int baseY=helper.absolutePos(BlockPos.ZERO).getY();
        int supportY=Math.floorDiv(baseY,NavigationTuning.TILE_SIZE)*NavigationTuning.TILE_SIZE+7;
        int relativeSupportY=supportY-baseY;
        for (int x=7;x<=9;x++) for (int z=7;z<=9;z++)
            helper.setBlock(new BlockPos(x,relativeSupportY,z),Blocks.STONE_SLAB);
        Vec3 feet=helper.absoluteVec(new Vec3(8.5,relativeSupportY+.5,8.5));
        Mob mob=EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(mob!=null,"the arrival regression requires a real zombie body");
        mob.moveTo(feet.x,feet.y+.25,feet.z,0,0);mob.setInvulnerable(true);mob.setPersistenceRequired();
        // NoAI also suppresses this mob's normal travel/physics. Keep normal entity ticks,
        // while removing only autonomous intent from this independent geometry context.
        mob.goalSelector.removeAllGoals(goal -> true);mob.targetSelector.removeAllGoals(goal -> true);
        helper.assertTrue(helper.getLevel().addFreshEntity(mob),"the collision-context entity must enter the actual world");
        NavigationContext context=new NavigationContext(RoomId.of("zombies","arrival-proof-"+UUID.randomUUID()),
                helper.getLevel(),new AABB(helper.absoluteVec(Vec3.ZERO),helper.absoluteVec(new Vec3(24,12,24))),
                List::of,candidate -> candidate==mob,UUID.randomUUID());
        var planner=new NavigationPlanner(context);
        var run=new Run(helper,mob,feet,new BlockPos(8,relativeSupportY+2,8),planner,cached);
        helper.onEachTick(run::tick);
    }

    private static final class Run {
        final GameTestHelper helper;
        final Mob mob;
        final Vec3 feet;
        final BlockPos changedRelative,changedAbsolute;
        final NavigationPlanner planner;
        final NavigationGraphCache cache;
        final TraversalValidator validator;
        final boolean cached;
        final MovementProfile profile;
        final NavigationGraphCache.TileKey footTile,headTile;
        TraversalValidator.ValidationCursor cursor;
        SurfaceNode node;
        boolean changed,oldRejected,finished,positionProofRejected;
        long movementAt=-1,movementRevision;
        long footVersion,headVersion,changedAt=-1;
        int directUnits;

        Run(GameTestHelper helper,Mob mob,Vec3 feet,BlockPos changedRelative,NavigationPlanner planner,boolean cached) {
            this.helper=helper;this.mob=mob;this.feet=feet;this.changedRelative=changedRelative;
            changedAbsolute=helper.absolutePos(changedRelative);this.planner=planner;this.cached=cached;
            cache=planner.cache();validator=planner.validator();profile=MovementProfile.from(mob);
            footTile=NavigationGraphCache.TileKey.at(feet);headTile=NavigationGraphCache.TileKey.at(changedAbsolute);
            helper.assertTrue(!footTile.equals(headTile) && Math.floorMod((int)Math.floor(feet.y),8)==7,
                    "the actual fractional foot support must be at tile height 7.5 with the head in the next tile");
        }

        void changeHead() {
            helper.assertTrue(helper.getLevel().getBlockState(changedAbsolute).isAir(),"the previously proved head space must really be empty");
            helper.setBlock(changedRelative,Blocks.STONE);
            planner.invalidate(new AABB(changedAbsolute),"arrival-regression-head-only-change");
            helper.assertTrue(cache.version(footTile)==footVersion && cache.version(headTile)!=headVersion,
                    "the real head collision event must change the body tile while leaving the original support tile version unchanged");
            changed=true;changedAt=helper.getTick();
        }

        void tick() {
            if (finished) return;
            try {
                helper.assertTrue(helper.getTick()<100,"the independent arrival proof deadline cannot be extended");
                helper.assertTrue(mob.isAlive() && helper.getLevel().getEntity(mob.getUUID())==mob,
                        "the same real entity must supply every collision context");
                if (node==null) {
                    if (!mob.onGround()) return;
                    helper.assertTrue(mob.position().distanceToSqr(feet)<1e-8,"normal gravity must settle the entity on the actual half slab");
                    footVersion=cache.version(footTile);headVersion=cache.version(headTile);
                    node=new SurfaceNode(feet,footVersion);
                    if (!cached) cursor=validator.beginStanding(mob,node,profile,.22);
                }
                if (cached) {
                    if (movementAt>=0 && !positionProofRejected) {
                        if (mob.position().distanceToSqr(feet)<=.04) return;
                        helper.assertTrue(mob.onGround() && mob.position().distanceToSqr(feet)<=.45*.45
                                        && cache.revision()==movementRevision,
                                "natural external drift must remain inside arrival tolerance while invalidating the old body's .2-block snapshot");
                        helper.assertTrue(planner.requestArrivalValidation(mob,node,profile,.45)==TraversalValidator.Verdict.UNKNOWN,
                                "without any terrain revision, changed actual standing position must request a new proof instead of reusing CLEAR");
                        positionProofRejected=true;
                    }
                    var verdict=planner.requestArrivalValidation(mob,node,profile,.45);
                    if (!changed && verdict==TraversalValidator.Verdict.CLEAR) {
                        helper.assertTrue(cache.stats().transientEntries()>0,
                                "completed proof dependency ownership must stay charged until its job is canceled");
                        if (movementAt<0) {
                            movementAt=helper.getTick();movementRevision=cache.revision();
                            // One external horizontal impulse; ordinary friction/gravity perform
                            // the actual displacement. No coordinate or vertical-velocity edits.
                            mob.setDeltaMovement(mob.getDeltaMovement().add(.202,0,0));return;
                        }
                        if (!positionProofRejected || Math.abs(mob.getDeltaMovement().x)>.01) return;
                        changeHead();
                        long geometryBefore=planner.stats().geometry();
                        var reused=planner.requestArrivalValidation(mob,node,profile,.45);
                        helper.assertTrue(reused==TraversalValidator.Verdict.UNKNOWN && planner.stats().geometry()==geometryBefore,
                                "a changed completed proof must queue budgeted revalidation, never return stale CLEAR or synchronously rescan");
                        oldRejected=true;
                    } else if (changed) {
                        helper.assertTrue(verdict!=TraversalValidator.Verdict.CLEAR,"head collision cannot reuse the completed arrival CLEAR");
                        if (verdict==TraversalValidator.Verdict.BLOCKED) finish(true,"cached arrival rejected and fresh body proof found the changed head collision");
                    }
                    return;
                }
                var batch=validator.advanceValidation(cursor,1,Long.MAX_VALUE);directUnits+=batch.workUsed();
                helper.assertTrue(batch.workUsed()<=1,"each direct proof step must fit its one-unit geometry slice");
                if (!changed && cursor.verifyingEvidence()) {
                    helper.assertTrue(batch.status()==TraversalValidator.ScanStatus.PENDING,
                            "the test must interrupt after all old geometry was read but before CLEAR dependency publication");
                    changeHead();return;
                }
                if (batch.status()==TraversalValidator.ScanStatus.COMPLETE) {
                    helper.assertTrue(changed,"the collision change must precede publication of the old multi-frame proof");
                    if (!oldRejected) {
                        helper.assertTrue(batch.verdict()==TraversalValidator.Verdict.UNKNOWN,
                                "the changed head-tile dependency must reject the old snapshot before it can become CLEAR");
                        oldRejected=true;cursor.close();cursor=validator.beginStanding(mob,node,profile,.22);
                    } else {
                        helper.assertTrue(batch.verdict()==TraversalValidator.Verdict.BLOCKED,
                                "a subsequent fresh physical shape proof must see the solid head collision");
                        finish(true,"multi-frame arrival rejected its stale body tile and fresh proof found the collision");
                    }
                }
            } catch (RuntimeException | Error failure) {
                if (!finished) finish(false,failure.getMessage());
                throw failure;
            }
        }

        void finish(boolean success,String reason) {
            finished=true;
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("supplementalCoverage",true);report.put("geometryEvidenceOnly",true);
            report.put("uuid",mob.getUUID().toString());report.put("actualFeet",List.of(mob.getX(),mob.getY(),mob.getZ()));
            report.put("footTile",footTile);report.put("changedHeadTile",headTile);report.put("supportTileUnchanged",cache.version(footTile)==footVersion);
            report.put("changedAtTick",changedAt);report.put("oldProofRejected",oldRejected);
            report.put("positionOnlyProofRejected",positionProofRejected);report.put("horizontalImpulseAtTick",movementAt);
            report.put("elapsedTicks",helper.getTick());report.put("directGeometryUnits",directUnits);report.put("planning",planner.stats());
            report.put("success",success);report.put("reason",reason);
            try {
                if (cursor!=null) cursor.close();planner.close();mob.discard();
                helper.assertTrue(cache.stats().transientEntries()==0 && cache.stats().tiles()==0,
                        "canceling either completed or failed proofs must release every retained dependency lease");
                report.put("cleanupCache",cache.stats());
            } catch (RuntimeException | Error failure) {
                report.put("success",false);report.put("cleanupFailure",failure.getMessage());throw failure;
            } finally { ZombiesNavigationTestReport.write(cached?"arrival-cached-head-tile-change":"arrival-framed-head-tile-change",report); }
            helper.assertTrue(success,reason);helper.succeed();
        }
    }
}
