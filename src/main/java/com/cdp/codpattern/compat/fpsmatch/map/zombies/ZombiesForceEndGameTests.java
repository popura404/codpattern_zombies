package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.match.runtime.termination.ForceEndCoordinator;
import com.cdp.codpattern.app.match.runtime.termination.RoomTerminationService;
import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.service.ZombiesBarrierBlockRuntimeService;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.UUID;

@GameTestHolder("codpattern_termination")
@PrefixGameTestTemplate(false)
public final class ZombiesForceEndGameTests {
    private ZombiesForceEndGameTests() { }

    @GameTest(template = "empty", batch = "zombies_termination", timeoutTicks = 200)
    public static void allRoomPhasesCanBeForceEnded(GameTestHelper helper) {
        var level = helper.getLevel();
        var service = RoomTerminationService.get(level.getServer());
        for (var phase : ZombiesGamePhase.values()) {
            var map = new ZombiesMap(level, "force-"+phase.key()+"-"+UUID.randomUUID().toString().substring(0,8),
                    new AreaData(BlockPos.ZERO,new BlockPos(8,8,8)));
            FPSMCore.getInstance().registerMap(map.getGameType(), map);
            var room = map.roomId();
            try {
                service.begin(room,List.of(),null);
                map.runtimeState().transitionTo(phase);
                map.isStart = phase.isRoundRunning();
                var mob = EntityType.ZOMBIE.create(level);
                mob.moveTo(helper.absolutePos(BlockPos.ZERO).getCenter());
                com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry.instance().register(room,mob);
                level.addFreshEntity(mob);
                var generation=service.generation(room);
                var result=service.forceEnd(level.getServer().createCommandSourceStack(),room,generation);
                helper.assertTrue(result.outcome()==ForceEndCoordinator.Outcome.COMPLETED, phase+" failed: "+result.failures());
                helper.assertTrue(map.runtimeState().phase()==ZombiesGamePhase.WAITING && !map.isStart, phase+" must reset mode state");
                helper.assertTrue(mob.isRemoved() && !service.hasLease(room), phase+" must reclaim entities and occupancy");
                var duplicate=service.forceEnd(level.getServer().createCommandSourceStack(),room,generation);
                helper.assertTrue(result.operationId().equals(duplicate.operationId()), "duplicate must preserve operation identity");
                service.begin(room,List.of(),null);
                helper.assertTrue(!generation.equals(service.generation(room)), "clean room must allow another generation");
                service.forceEnd(level.getServer().createCommandSourceStack(),room,service.generation(room));
            } finally { FPSMCore.getInstance().unregisterMap(map); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "zombies_barrier_recovery", timeoutTicks = 100)
    public static void barrierFailureRetainsDurableFootprint(GameTestHelper helper) {
        var level=helper.getLevel();
        var room=com.cdp.codpattern.app.match.model.RoomId.of("zombies","barrier-recovery-"+UUID.randomUUID());
        var pos=helper.absolutePos(new BlockPos(0,2,0));
        level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        var barrier=new ZombiesBarrierData("recovery-wall",1,0,true,level.dimension(),pos,pos.above(),pos);
        var original=ZombiesBarrierBlockRuntimeService.instance();
        original.placeActiveBarriers(room,List.of(barrier),ignored->false,dimension->level);
        try {
            try {
                original.clearRoom(room,dimension->null);
                throw new AssertionError("Missing dimension cannot confirm barrier deletion");
            } catch (IllegalStateException expected) { }
            helper.assertTrue(original.cellAt(room,level,pos).isPresent(),"failed cleanup must retain the cell");
            var restored=new ZombiesBarrierBlockRuntimeService();
            restored.clearRoom(room,dimension->level);
            helper.assertTrue(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir(),
                    "a recreated service must recover the saved footprint and delete the barrier");
            helper.assertTrue(restored.cellAt(room,level,pos).isEmpty(),"only confirmed removal clears tracking");
            helper.succeed();
        } finally { original.clearRoom(room,dimension->level); }
    }
}
