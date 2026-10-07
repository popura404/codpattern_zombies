package com.cdp.codpattern.app.zombies.service.navigation;

import com.cdp.codpattern.app.match.model.RoomId;
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real locate/request reuse regressions; these test planning, not entity arrival. */
@GameTestHolder("codpattern_navigation_retarget")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationRetargetGameTests {
    private ZombiesNavigationRetargetGameTests() { }

    @GameTest(template = "zombies_navigation", batch = "navigation_retarget", timeoutTicks = 80)
    public static void movingRawTargetAcrossLocatedTileRestartsWithoutThrowing(GameTestHelper helper) {
        retargetLocatedSlab(helper, true);
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_retarget", timeoutTicks = 80)
    public static void identicalRawTargetStillChecksTheLocatedSearchTile(GameTestHelper helper) {
        retargetLocatedSlab(helper, false);
    }

    private static void retargetLocatedSlab(GameTestHelper helper, boolean moveTarget) {
        int baseY = helper.absolutePos(BlockPos.ZERO).getY();
        int supportY = Math.floorDiv(baseY, NavigationTuning.TILE_SIZE) * NavigationTuning.TILE_SIZE + 7;
        int relativeSupportY = supportY - baseY;
        for (int x = 2; x <= 14; x++) for (int z = 4; z <= 6; z++) {
            helper.setBlock(new BlockPos(x, relativeSupportY, z), Blocks.STONE_SLAB);
            for (int y = 1; y <= 4; y++)
                helper.setBlock(new BlockPos(x, relativeSupportY + y, z), Blocks.AIR);
        }
        Vec3 start = helper.absoluteVec(new Vec3(3.5, relativeSupportY + .5, 5.5));
        Vec3 rawTarget = helper.absoluteVec(new Vec3(11.5, relativeSupportY + 1, 5.5));
        Mob mob = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(mob != null, "the retarget regression requires a real collision-context entity");
        mob.moveTo(start.x, start.y, start.z, 0, 0);
        mob.setNoAi(true); mob.setOnGround(true); mob.setInvulnerable(true); mob.setPersistenceRequired();
        helper.assertTrue(helper.getLevel().addFreshEntity(mob), "the real mob must enter the test world");
        NavigationContext context = new NavigationContext(RoomId.of("zombies", "retarget-" + UUID.randomUUID()),
                helper.getLevel(), new AABB(helper.absoluteVec(Vec3.ZERO), helper.absoluteVec(new Vec3(24, 12, 24))),
                List::of, candidate -> candidate == mob, UUID.randomUUID());
        NavigationPlanner planner = new NavigationPlanner(context);
        try {
            UUID targetId = UUID.randomUUID();
            MovementProfile profile = MovementProfile.from(mob);
            planner.requestPlan(mob, rawTarget, targetId, profile);
            NavigationScheduler.Work work = job(planner, mob.getUUID());
            NavigationPlanner.Search original = null;
            // The public scheduler does not expose individual work items. Read its actual Job
            // without changing its state, then grant locate work but no search expansions. This
            // makes the acquisition/reuse boundary deterministic regardless of host tick speed.
            for (int slice = 0; slice < 4096 && original == null; slice++) {
                work.advance(new NavigationScheduler.Budget(Long.MAX_VALUE, 0, 1, helper.getLevel().getGameTime()));
                original = search(work);
            }
            helper.assertTrue(original != null && !original.finished() && !original.completionInProgress(),
                    "real locate must create an unfinished, unfrozen search before retargeting");
            helper.assertTrue(Math.abs(original.target().y - (supportY + .5)) < 1.0E-8
                            && !NavigationGraphCache.TileKey.at(original.target()).equals(NavigationGraphCache.TileKey.at(rawTarget)),
                    "locate must snap the raw target to the actual lower slab tile");
            helper.assertTrue(planner.searchRecordCount() > 0 && planner.cache().stats().transientEntries() > 0,
                    "the old search must own records and dependency leases before reuse");

            Vec3 updated = moveTarget ? rawTarget.add(.125, 0, 0) : rawTarget;
            helper.assertTrue(NavigationGraphCache.TileKey.at(updated).equals(NavigationGraphCache.TileKey.at(rawTarget)),
                    "both raw requests must remain in the same tile to exercise the old reuse branch");
            PlanningResult retargeted = planner.requestPlan(mob, updated, targetId, profile);
            helper.assertTrue(retargeted.status() == PlanningResult.Status.PENDING
                            && retargeted.reason() == PlanningResult.Reason.BUILD,
                    "an incompatible located search must restart as pending BUILD without throwing");
            helper.assertTrue(job(planner, mob.getUUID()) == work && search(work) == null,
                    "retarget must restart the retained job and reacquire its standing nodes");
            helper.assertTrue(original.finished() && original.recordCount() == 0 && planner.searchRecordCount() == 0
                            && planner.cache().stats().transientEntries() == 0,
                    "restart must release every old search record, cursor, and dependency lease");

            Vec3 standingTarget = new Vec3(updated.x, supportY + .5, updated.z);
            planner.requestPlan(mob, standingTarget, targetId, profile);
            work = job(planner, mob.getUUID());
            for (int slice = 0; slice < 16384 && !work.finished(); slice++)
                work.advance(new NavigationScheduler.Budget(Long.MAX_VALUE, 16, 16, helper.getLevel().getGameTime()));
            PlanningResult ready = planner.result(mob.getUUID());
            helper.assertTrue(ready.status() == PlanningResult.Status.READY && ready.plan() != null
                            && ready.plan().targetSnapshot().equals(standingTarget) && !ready.plan().edges().isEmpty(),
                    "after the target settles, the same planner must publish a real route to its supported position");
            helper.assertTrue(planner.searchRecordCount() == 0,
                    "successful replanning must release its search records");
        } finally {
            planner.close(); mob.discard();
        }
        helper.assertTrue(planner.cache().stats().transientEntries() == 0 && planner.requestCount() == 0,
                "closing the planner must release the completed route and all retained jobs");
        helper.succeed();
    }

    private static NavigationScheduler.Work job(NavigationPlanner planner, UUID mobId) {
        Object value = field(planner, "jobs");
        return (NavigationScheduler.Work) ((Map<?, ?>) value).get(mobId);
    }

    private static NavigationPlanner.Search search(NavigationScheduler.Work work) {
        return (NavigationPlanner.Search) field(work, "search");
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to inspect the actual planner job's " + name, failure);
        }
    }
}
