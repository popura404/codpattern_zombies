package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Isolated process: remove real world tickets, observe an actual unload, then reload the same terrain. */
@GameTestHolder("codpattern_navigation_chunks")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationChunkGameTests {
    private static final int DEADLINE=4000;
    private ZombiesNavigationChunkGameTests() { }

    @GameTest(setupTicks=20,template="zombies_navigation_chunks",batch="navigation_chunks",timeoutTicks=DEADLINE+10)
    public static void actualChunkUnloadWaitsWithoutReloadingThenOriginalMonsterResumes(GameTestHelper helper) {
        boolean targetMovesMissing=Boolean.getBoolean("codpattern.zombies.navigationChunkTargetMovesMissing");
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,
                new Vec3(targetMovesMissing?11.5:249.5,1,3.5),new BlockPos(256,7,8));
        Run run=new Run(helper,fixture,Boolean.getBoolean("codpattern.zombies.navigationChunkStartsMissing"),targetMovesMissing);
        try { run.prepare();helper.onEachTick(run::tick); }
        catch (RuntimeException | Error failure) { run.close();throw failure; }
    }

    public static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final ChunkPos origin,middle,spawnRegion;
        final boolean startsMissing,targetMovesMissing;
        final List<ChunkPos> addedTickets=new ArrayList<>(),removedTickets=new ArrayList<>();
        Mob mob;
        UUID originalId;
        LayeredNavigationRuntime runtime;
        boolean released,reloaded,finished,startTicketRemoved,listenerRegistered;
        long unloadEventTick=-1,loadEventTick=-1,absentTick=-1,waitingTick=-1,reloadTick=-1;
        long waitsAtRelease;
        long spawnedAtTick=-1;
        long targetMovedAtTick=-1,scheduledHalfServerTick=-1;
        int mobTicksBeforeTargetMove=-1;
        boolean halfTickVerified,bowRunningBeforeTargetMove,switchedToSwordAfterWaiting;
        int absentSamples,absentSamplesAfterTargetMove;
        String lastAbsentReason;
        List<String> unexpectedLoadStack=List.of();
        boolean geometricFailureWhileAbsent;
        Run(GameTestHelper helper,ZombiesNavigationGameTests.Fixture fixture,boolean startsMissing,boolean targetMovesMissing) {
            this.helper=helper;this.fixture=fixture;
            this.startsMissing=startsMissing;
            this.targetMovesMissing=targetMovesMissing;
            origin=new ChunkPos(helper.absolutePos(BlockPos.ZERO));
            middle=new ChunkPos(origin.x+15,origin.z);
            spawnRegion=new ChunkPos(helper.getLevel().getSharedSpawnPos());
        }
        void prepare() {
            // The test owns these tickets. Production navigation may only inspect loaded chunks.
            for (int x=origin.x;x<=origin.x+16;x++) for (int z=origin.z;z<=origin.z+1;z++) {
                ChunkPos chunk=new ChunkPos(x,z);
                if (helper.getLevel().setChunkForced(x,z,true)) addedTickets.add(chunk);
            }
            for (int x=0;x<256;x++) for (int z=0;z<8;z++) for (int y=0;y<7;y++)
                helper.setBlock(new BlockPos(x,y,z),y==0 || y==6 || x==0 || x==255 || z<=1 || z>=5
                        ? Blocks.STONE:Blocks.AIR);
            if (!startsMissing && !targetMovesMissing) spawnOriginal();
            helper.assertTrue(helper.getLevel().hasChunk(middle.x,middle.z),"the middle terrain must initially be loaded");
            MinecraftForge.EVENT_BUS.register(this);listenerRegistered=true;
        }
        void spawnOriginal() {
            if (startsMissing || targetMovesMissing) helper.assertTrue(unloadEventTick>=0
                            && helper.getLevel().getChunkSource().getChunkNow(middle.x,middle.z)==null,
                    "the target chunk must actually unload before the first room entity is created");
            mob=fixture.spawn(targetMovesMissing?"wither_skeleton":"zombie",new BlockPos(3,1,3));originalId=mob.getUUID();
            if (targetMovesMissing) mob.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));
            runtime=LayeredNavigationRuntime.of(mob);spawnedAtTick=helper.getTick();
            helper.assertTrue(runtime!=null,"chunk acceptance requires a layered room controller");
            if (startsMissing || targetMovesMissing) helper.assertTrue(helper.getLevel().getChunkSource().getChunkNow(middle.x,middle.z)==null,
                    "room spawning itself must not load the absent target terrain");
        }
        @SubscribeEvent public void unloaded(ChunkEvent.Unload event) {
            if (event.getLevel()==helper.getLevel() && event.getChunk().getPos().equals(middle)) unloadEventTick=helper.getTick();
        }
        @SubscribeEvent public void loaded(ChunkEvent.Load event) {
            if (event.getLevel()==helper.getLevel() && event.getChunk().getPos().equals(middle)) {
                loadEventTick=helper.getTick();
                if (released && !reloaded && unloadEventTick>=0 && unexpectedLoadStack.isEmpty())
                    unexpectedLoadStack=java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                            .map(StackTraceElement::toString).toList();
            }
        }
        void tick() {
            if (finished) return;
            try {
                if (mob!=null) {
                    helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(originalId)==mob,
                            "actual chunk loss and recovery must preserve the original living room UUID");
                    helper.assertTrue(fixture.activeRoomCount()==1 && fixture.ownedRoomCount()==1
                                    && fixture.waveState().remainingBudget()==0,
                            "chunk waiting cannot duplicate ownership or consumed spawn budget");
                }
                long tick=helper.getTick();
                if (!released && tick>=20) release();
                helper.assertTrue(unexpectedLoadStack.isEmpty(),
                        "no real load event may occur between actual unload and fixture ticket restoration");
                if (released && !reloaded) {
                    boolean absent=!helper.getLevel().hasChunk(middle.x,middle.z)
                            && helper.getLevel().getChunkSource().getChunkNow(middle.x,middle.z)==null;
                    if (absent && unloadEventTick>=0) {
                        if (mob==null) spawnOriginal();
                        helper.assertTrue(helper.getLevel().isPositionEntityTicking(mob.blockPosition()),
                                "the original mob must keep receiving real entity ticks while distant terrain is absent");
                        if (absentTick<0) absentTick=tick;
                        absentSamples++;
                        if (targetMovesMissing) observeMovingTarget(tick);
                        var progress=runtime.progress(mob);
                        geometricFailureWhileAbsent|=progress!=null && progress.geometricFailure();
                        helper.assertTrue(progress!=null && !progress.geometricFailure(),
                                "absent terrain must remain unknown on every observed tick, never conclusively unreachable");
                        lastAbsentReason=progress.planningReason();
                        helper.assertTrue(mob.getBoundingBox().maxX<middle.getMinBlockX(),
                                "the real entity cannot execute a segment through absent terrain");
                        if ((!targetMovesMissing || halfTickVerified)
                                && runtime.planningStats().chunkWaits()>waitsAtRelease && waitingTick<0) waitingTick=tick;
                    } else if (absentTick>=0) {
                        helper.fail("navigation must not force the absent chunk back into memory before the fixture restores its tickets");
                    }
                    if (waitingTick>=0 && tick-waitingTick>=40 && absentSamples>=40
                            && (!targetMovesMissing || halfTickVerified && absentSamplesAfterTargetMove>=40)) reload();
                    if (tick>=700 && !reloaded) helper.fail("actual unload and WAITING_CHUNK must be observed before tick 700; "
                            +description()+", unloaded="+unloadEventTick+", absent="+absentTick);
                }
                if (reloaded && loadEventTick>=reloadTick && mob.onGround()
                        && mob.getTarget()==fixture.player && mob.distanceToSqr(fixture.player)<=4
                        && mob.getSensing().hasLineOfSight(fixture.player))
                    finish(true,"actual unloaded terrain stayed unknown and the original UUID resumed after a real reload");
                if (!finished && tick>=DEADLINE) finish(false,"chunk continuation deadline: "+description());
            } catch (RuntimeException | Error failure) {
                if (!finished) { finished=true;write(false,failure.getMessage());close(); }
                throw failure;
            }
        }
        void observeMovingTarget(long tick) {
            int serverTick=helper.getLevel().getServer().getTickCount();
            if (targetMovedAtTick>=0) {
                if (!halfTickVerified) {
                    // GameTest callbacks follow ServerLevel entity ticks. This sample must
                    // follow exactly the half selector update selected in the previous callback.
                    helper.assertTrue(serverTick==scheduledHalfServerTick && mob.tickCount==mobTicksBeforeTargetMove+1
                                    && ((serverTick+mob.getId())&1)!=0,
                            "the original bow mob must receive the scheduled real tickRunningGoals-only entity tick");
                    helper.assertTrue(mob.getTarget()==fixture.player,
                            "stopping a stale native attack cannot lose the same eligible room target");
                    halfTickVerified=true;
                }
                absentSamplesAfterTargetMove++;
                return;
            }
            boolean runningBow=mob.goalSelector.getRunningGoals()
                    .anyMatch(goal -> goal.getGoal() instanceof RangedBowAttackGoal<?>
                            && goal.requiresUpdateEveryTick());
            int nextServerTick=serverTick+1;
            if (!runningBow || mob.tickCount<1 || ((nextServerTick+mob.getId())&1)==0) return;
            helper.assertTrue(mob.getTarget()==fixture.player && fixture.player.chunkPosition().x==origin.x,
                    "the real native bow goal must first run against the nearby loaded room target");
            bowRunningBeforeTargetMove=true;
            targetMovedAtTick=tick;scheduledHalfServerTick=nextServerTick;mobTicksBeforeTargetMove=mob.tickCount;
            waitsAtRelease=runtime.planningStats().chunkWaits();
            Vec3 distant=helper.absoluteVec(new Vec3(249.5,1,3.5));
            // Only the player moves. The original mob keeps its native goals and physical state.
            fixture.player.moveTo(distant.x,distant.y,distant.z,0,0);
            helper.assertTrue(helper.getLevel().getChunkSource().getChunkNow(middle.x,middle.z)==null,
                    "moving the detached player must not load its destination chunk");
        }
        String description() { return runtime==null?"waiting to create the first room entity":runtime.describe(mob); }
        void release() {
            if (mob!=null) helper.assertTrue(mob.chunkPosition().x<=origin.x
                                && helper.getLevel().isPositionEntityTicking(mob.blockPosition()),
                        "the original mob must remain in the retained entity-ticking column before tickets are removed");
            waitsAtRelease=runtime==null?0:runtime.planningStats().chunkWaits();
            // Minecraft's START ticket otherwise keeps this synthetic overworld loaded indefinitely.
            helper.getLevel().getChunkSource().removeRegionTicket(TicketType.START,spawnRegion,11,Unit.INSTANCE);
            startTicketRemoved=true;
            for (long packed:helper.getLevel().getForcedChunks().toLongArray()) {
                ChunkPos chunk=new ChunkPos(packed);
                // Losing FULL status (level 34) is not an actual ChunkEvent.Unload:
                // generation tickets retain its LevelChunk up to ChunkLevel.MAX_LEVEL.
                // Keep the original mob's column ticking, and remove the distant half
                // beyond all generation influence. The unattached fixture player is
                // allowed to be in the absent target terrain until these tickets return.
                if (chunk.x>origin.x) {
                    removedTickets.add(chunk);helper.getLevel().setChunkForced(chunk.x,chunk.z,false);
                }
            }
            for (long packed:helper.getLevel().getForcedChunks().toLongArray()) {
                ChunkPos remaining=new ChunkPos(packed);
                int distance=Math.max(Math.abs(remaining.x-middle.x),Math.abs(remaining.z-middle.z));
                helper.assertTrue(31+distance>ChunkLevel.MAX_LEVEL,
                        "remaining forced tickets must not retain the observed chunk even at a generation status");
            }
            released=true;
        }
        void reload() {
            reloadTick=helper.getTick();
            // setChunkForced may dispatch ChunkEvent.Load synchronously inside this loop.
            reloaded=true;
            for (ChunkPos chunk:removedTickets) helper.getLevel().setChunkForced(chunk.x,chunk.z,true);
            if (targetMovesMissing) {
                mob.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.STONE_SWORD));
                switchedToSwordAfterWaiting=true;
            }
        }
        void finish(boolean success,String reason) {
            finished=true;write(success,reason);close();
            if (success) helper.succeed();else helper.fail(reason);
        }
        void write(boolean success,String reason) {
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("originalUuid",originalId==null?null:originalId.toString());report.put("middleChunk",List.of(middle.x,middle.z));
            report.put("targetStartsMissing",startsMissing);report.put("spawnedAtTick",spawnedAtTick);
            report.put("targetMovesToMissingChunk",targetMovesMissing);
            report.put("targetMovedAtTick",targetMovedAtTick);report.put("scheduledHalfServerTick",scheduledHalfServerTick);
            report.put("nativeBowRunningBeforeTargetMove",bowRunningBeforeTargetMove);
            report.put("realHalfSelectorTickVerified",halfTickVerified);
            report.put("absentSamplesAfterTargetMove",absentSamplesAfterTargetMove);
            report.put("switchedToSwordAfterWaiting",switchedToSwordAfterWaiting);
            report.put("fixtureMonsterMovementCommands",false);report.put("manualGoalTicks",false);
            report.put("corridorLengthBlocks",256);report.put("chunkGenerationMaxTicketLevel",ChunkLevel.MAX_LEVEL);
            report.put("removedForcedTicketCount",removedTickets.size());
            report.put("actualUnloadEventTick",unloadEventTick);report.put("firstAbsentTick",absentTick);
            report.put("firstChunkWaitingTick",waitingTick);report.put("absentSamples",absentSamples);
            report.put("geometricFailureObservedWhileAbsent",geometricFailureWhileAbsent);report.put("lastAbsentReason",lastAbsentReason);
            report.put("fixtureReloadTick",reloadTick);report.put("actualLoadEventTick",loadEventTick);
            report.put("unexpectedLoadStack",unexpectedLoadStack);
            report.put("elapsedTestTicks",helper.getTick());report.put("frozenDeadlineTicks",DEADLINE);
            report.put("planning",runtime==null?null:runtime.planningStats());
            report.put("success",success);report.put("reason",reason);
            ZombiesNavigationTestReport.write(targetMovesMissing?"actual-chunk-moving-target-missing":
                    startsMissing?"actual-chunk-starts-missing":"actual-chunk-unload-reload",report);
        }
        void close() {
            if (listenerRegistered) { MinecraftForge.EVENT_BUS.unregister(this);listenerRegistered=false; }
            if (targetMovesMissing && mob!=null)
                helper.getLevel().getEntitiesOfClass(AbstractArrow.class,new net.minecraft.world.phys.AABB(
                                helper.absolutePos(BlockPos.ZERO),helper.absolutePos(new BlockPos(256,7,8))),
                        arrow -> arrow.getOwner()==mob).forEach(AbstractArrow::discard);
            fixture.close();
            for (ChunkPos chunk:removedTickets) helper.getLevel().setChunkForced(chunk.x,chunk.z,true);
            for (ChunkPos chunk:addedTickets) helper.getLevel().setChunkForced(chunk.x,chunk.z,false);
            if (startTicketRemoved) {
                helper.getLevel().getChunkSource().addRegionTicket(TicketType.START,spawnRegion,11,Unit.INSTANCE);
                startTicketRemoved=false;
            }
        }
    }
}
