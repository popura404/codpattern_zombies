package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Equipment changes only: the entity's normal GoalSelector performs both attacks. */
@GameTestHolder("codpattern_navigation_weapon")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationWeaponGameTests {
    private ZombiesNavigationWeaponGameTests() { }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_weapon",timeoutTicks=250)
    public static void witherSkeletonShootsThenNaturallyRestartsMeleeAfterSwitchingToSword(GameTestHelper helper) {
        for (int x=2;x<=18;x++) for (int y=0;y<=4;y++) for (int z=6;z<=10;z++)
            helper.setBlock(new BlockPos(x,y,z),y==0 || y==4 || z==6 || z==10 || x==2 || x==18
                    ? Blocks.STONE : Blocks.AIR);
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,new Vec3(15.5,1,8.5),new BlockPos(24,12,24));
        try {
            // Real level ticks expire ServerPlayer's 60-tick login protection naturally.
            // An unregistered collision-only target never ticks and stays invulnerable forever.
            helper.getLevel().addNewPlayer(fixture.player);
            helper.assertTrue(helper.getLevel().getEntity(fixture.player.getUUID())==fixture.player,
                    "the attack target must be a real ticking level player");
            var skeleton=(WitherSkeleton)fixture.spawn("wither_skeleton",new BlockPos(4,1,8));
            var run=new Run(helper,fixture,skeleton);
            run.install();
            skeleton.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));
            helper.onEachTick(run::tick);
        } catch (RuntimeException | Error failure) { fixture.close();throw failure; }
    }

    private static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final WitherSkeleton mob;
        final UUID uuid;
        final LayeredNavigationRuntime runtime;
        final Vec3 target;
        final List<UUID> arrows=new ArrayList<>();
        final List<Map<String,Object>> trajectory=new ArrayList<>();
        final Consumer<EntityJoinLevelEvent> arrowListener;
        final Consumer<LivingDamageEvent> meleeListener;
        Vec3 previous,switchedFeet;
        boolean finished,bowStarted,bowUsing,meleeStarted,bowStopped;
        long switchedAt=-1,meleeHitAt=-1;
        float healthBeforeMelee;

        Run(GameTestHelper helper,ZombiesNavigationGameTests.Fixture fixture,WitherSkeleton mob) {
            this.helper=helper;this.fixture=fixture;this.mob=mob;uuid=mob.getUUID();
            runtime=LayeredNavigationRuntime.of(mob);target=fixture.player.position();previous=mob.position();
            helper.assertTrue(runtime!=null,"the weapon fixture must keep the installed room navigation runtime");
            arrowListener=event -> {
                if (event.getLevel()==helper.getLevel() && event.getEntity() instanceof AbstractArrow arrow
                        && arrow.getOwner()==mob) arrows.add(arrow.getUUID());
            };
            meleeListener=event -> {
                if (switchedAt>=0 && event.getEntity()==fixture.player && event.getSource().getDirectEntity()==mob
                        && event.getAmount()>0 && meleeHitAt<0) {
                    meleeHitAt=helper.getTick();healthBeforeMelee=fixture.player.getHealth();
                }
            };
        }

        void install() {
            MinecraftForge.EVENT_BUS.addListener(arrowListener);
            MinecraftForge.EVENT_BUS.addListener(meleeListener);
        }

        void tick() {
            if (finished) return;
            try {
                helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(uuid)==mob
                                && LayeredNavigationRuntime.of(mob)==runtime && runtime.controllerCount()==1,
                        "equipment reassessment must preserve the original world entity and room controller");
                helper.assertTrue(fixture.player.isAlive() && helper.getLevel().getEntity(fixture.player.getUUID())==fixture.player,
                        "the original living target must remain in the level and receive normal player ticks");
                helper.assertTrue(mob.position().distanceTo(previous)<1,"normal entity movement must perform the approach");
                long registeredBow=mob.goalSelector.getAvailableGoals().stream()
                        .filter(goal -> goal.getGoal() instanceof RangedBowAttackGoal<?>).count();
                long registeredMelee=mob.goalSelector.getAvailableGoals().stream()
                        .filter(goal -> goal.getGoal() instanceof MeleeAttackGoal).count();
                boolean runningBow=mob.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() instanceof RangedBowAttackGoal<?>);
                boolean runningMelee=mob.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() instanceof MeleeAttackGoal);
                if (switchedAt<0) {
                    helper.assertTrue(registeredBow==1 && registeredMelee==0,"the bow must select exactly one real native combat goal");
                    bowStarted|=runningBow;bowUsing|=runningBow && mob.isUsingItem();
                    if (bowStarted && bowUsing && !arrows.isEmpty()) {
                        helper.assertTrue(mob.getTarget()==fixture.player,"the shot must use the actual room survivor target");
                        switchedAt=helper.getTick();switchedFeet=mob.position();
                        mob.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.STONE_SWORD));
                        helper.assertTrue(mob.getTarget()==fixture.player,"native equipment reassessment cannot drop the room target");
                    }
                } else {
                    helper.assertTrue(registeredBow==0 && registeredMelee==1 && mob.getTarget()==fixture.player,
                            "the same live target must remain selected while one native melee goal replaces the bow goal");
                    meleeStarted|=runningMelee;bowStopped|=!runningBow;
                    double reach=mob.getBbWidth()*2;
                    if (meleeHitAt>=0 && fixture.player.getHealth()<healthBeforeMelee && mob.onGround()
                            && mob.distanceToSqr(fixture.player)<=reach*reach+fixture.player.getBbWidth()
                            && mob.getSensing().hasLineOfSight(fixture.player)) {
                        helper.assertTrue(meleeStarted && bowStopped && switchedFeet.distanceTo(mob.position())>1,
                                "after shooting, the original native melee goal must restart, physically approach, and actually damage the survivor");
                        finish(true,"one original room entity naturally shot an arrow, switched weapons, and dealt native melee damage");return;
                    }
                }
                if (helper.getTick()%5==0) trajectory.add(Map.of("tick",helper.getTick(),"feet",position(mob.position()),
                        "bowRunning",runningBow,"meleeRunning",runningMelee,"playerHealth",fixture.player.getHealth(),
                        "execution",runtime.describe(mob)));
                previous=mob.position();
                if (helper.getTick()>=240) finish(false,"fixed 240-tick natural weapon handoff deadline exceeded: "+runtime.describe(mob));
            } catch (RuntimeException | Error failure) {
                if (!finished) finish(false,failure.getMessage());
                throw failure;
            }
        }

        void finish(boolean success,String reason) {
            finished=true;
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("supplementalCoverage",true);report.put("preImplementationBaseline",false);
            report.put("uuid",uuid.toString());report.put("targetUuid",fixture.player.getUUID().toString());
            report.put("manualGoalTicks",false);report.put("fixtureMovementCommands",false);
            report.put("playerAddedThrough","ServerLevel.addNewPlayer");report.put("playerTicks",fixture.player.tickCount);
            report.put("playerDisplacement",fixture.player.position().distanceTo(target));
            report.put("nativeBowStarted",bowStarted);report.put("nativeBowUsed",bowUsing);report.put("shotArrowUuids",arrows);
            report.put("nativeBowStopped",bowStopped);report.put("nativeMeleeStarted",meleeStarted);
            report.put("switchedAtTick",switchedAt);report.put("nativeMeleeDamageAtTick",meleeHitAt);
            report.put("elapsedTicks",helper.getTick());report.put("endFeet",position(mob.position()));
            report.put("trajectory",trajectory);report.put("success",success);report.put("reason",reason);
            try {
                MinecraftForge.EVENT_BUS.unregister(arrowListener);MinecraftForge.EVENT_BUS.unregister(meleeListener);
                for (UUID arrowId:arrows) {
                    var arrow=helper.getLevel().getEntity(arrowId);
                    if (arrow instanceof AbstractArrow projectile && projectile.getOwner()==mob) projectile.discard();
                }
                fixture.close();
                var cache=runtime.cacheStats();
                helper.assertTrue(runtime.controllerCount()==0 && runtime.planningStats().activeRequests()==0
                                && runtime.planningStats().searchRecords()==0 && cache.tiles()==0 && cache.transientEntries()==0
                                && fixture.activeRoomCount()==0 && fixture.ownedRoomCount()==0,
                        "the independent weapon fixture must leave no room navigation or ownership state");
                report.put("cleanupControllers",runtime.controllerCount());report.put("cleanupCache",cache);
            } catch (RuntimeException | Error failure) {
                report.put("success",false);report.put("cleanupFailure",failure.getMessage());throw failure;
            } finally { ZombiesNavigationTestReport.write("native-wither-weapon-handoff",report); }
            helper.assertTrue(success,reason);helper.succeed();
        }
    }
    private static List<Double> position(Vec3 point) { return List.of(point.x,point.y,point.z); }
}
