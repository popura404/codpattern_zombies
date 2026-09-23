package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.CodPatternConstants;
import com.cdp.codpattern.app.match.BuiltInGameModes;
import com.cdp.codpattern.app.zombies.bootstrap.ZombiesItemRegister;
import com.cdp.codpattern.compat.fpsmatch.map.zombies.ZombiesMap;
import com.phasetranscrystal.fpsmatch.common.item.zombies.ZombiesDeployTool;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Actual save/undo/discard behavior against an isolated GameTest map. */
@GameTestHolder(CodPatternConstants.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ZombiesDeployPersistenceGameTests {
    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 100, required = true)
    public static void invalidMapsSaveAndHistorySurvivesSave(GameTestHelper helper) {
        var service = ZombiesDeployToolService.instance();
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        var stack = new ItemStack(ZombiesItemRegister.ZOMBIES_DEPLOY_TOOL.get());
        String name = "deploy-regression-" + UUID.randomUUID().toString().substring(0, 8);
        ZombiesMap map = null;
        try {
            var registration = ZombiesDeployDraft.empty().withMapDraft(name, new BlockPos(-16, 0, -16), new BlockPos(16, 256, 16));
            var created = service.createMap(player, stack, registration);
            check(created.success(), "registration must succeed: " + created.code());
            map = (ZombiesMap) FPSMCore.getInstance().getMapByTypeWithName(BuiltInGameModes.ZOMBIES, name).orElseThrow();
            var draft = new ZombiesDeployDraft(name, ZombiesDeployFieldSchema.INITIAL, -1, ZombiesDeployFieldSchema.PROFILE_MVP3,
                    Map.of("dimension", helper.getLevel().dimension().location().toString(), "posX", "0", "posY", "64", "posZ", "0"));
            var added = service.addObject(player, stack, draft);
            check(added.success(), "add must succeed: " + added.code());
            check(added.value().orElseThrow().selectedObjectType().equals(ZombiesDeployFieldSchema.INITIAL), "adding must not advance the type");
            var saved = service.saveDraft(player, stack, ZombiesDeployTool.getDraft(stack));
            var savedSnapshot = saved.value().orElseThrow();
            check(saved.success(), "an incomplete map must save successfully: " + saved.code());
            check(savedSnapshot.validationSummaries().stream().anyMatch(value -> value.errors() > 0), "fixture must still fail map validation");
            check(!savedSnapshot.dirty() && savedSnapshot.undoCount() == 1, "save must become clean and retain history");
            check(map.objects().initialSpawns().size() == 1, "save must apply the edited objects");
            var unchanged = service.saveDraft(player, stack, ZombiesDeployTool.getDraft(stack)).value().orElseThrow();
            check(unchanged.statusKey().equals("message.codpattern.zombies.deploy.saved_invalid") && unchanged.undoCount() == 1,
                    "saving unchanged invalid data must keep its warning and history");

            Map<String, String> changed = new HashMap<>(ZombiesDeployTool.getDraft(stack).fields());
            changed.put("posX", "1");
            service.updateObject(player, stack, ZombiesDeployTool.getDraft(stack).withFields(changed));
            var secondSave = service.saveDraft(player, stack, ZombiesDeployTool.getDraft(stack)).value().orElseThrow();
            check(secondSave.undoCount() == 2, "second save must retain both operations");
            var conflict = service.undoLast(player, stack, ZombiesDeployTool.getDraft(stack), secondSave.revision() - 1).value().orElseThrow();
            check(conflict.statusCode().equals("undo.revision_conflict") && conflict.undoCount() == 2, "stale undo must leave history intact");
            var undone = service.undoLast(player, stack, ZombiesDeployTool.getDraft(stack), secondSave.revision()).value().orElseThrow();
            check(undone.dirty() && undone.redoCount() == 1, "undo after save must create an unsaved edit");
            check("0".equals(ZombiesDeployTool.getDraft(stack).fields().get("posX")), "undo must restore fields");
            var redone = service.redoLast(player, stack, ZombiesDeployTool.getDraft(stack), undone.revision()).value().orElseThrow();
            check(!redone.dirty() && redone.undoCount() == 2, "redo to saved state must be clean");
            service.undoLast(player, stack, ZombiesDeployTool.getDraft(stack), redone.revision());
            var discarded = service.discardDraft(player, stack, ZombiesDeployTool.getDraft(stack)).value().orElseThrow();
            check(!discarded.dirty() && discarded.undoCount() == 0 && discarded.redoCount() == 0, "close must clear history and draft");
            check("1".equals(ZombiesDeployTool.getDraft(stack).fields().get("posX")), "discard must return to the most recent save");

            changed = new HashMap<>(ZombiesDeployTool.getDraft(stack).fields());
            changed.put("posX", "invalid");
            var malformed = service.saveDraft(player, stack, ZombiesDeployTool.getDraft(stack).withFields(changed));
            check(!malformed.success(), "unparseable input must not be reported as saved");
            var cleared = service.discardDraft(player, stack, ZombiesDeployTool.getDraft(stack).withMapDraft("unfinished", BlockPos.ZERO, null)).value().orElseThrow();
            check(!cleared.dirty() && cleared.mapPos1() == null && cleared.draftMapName().isBlank(), "close must also clear registration text and corners");
            helper.succeed();
        } catch (Throwable error) {
            error.printStackTrace();
            helper.fail("Deploy persistence regression: " + error);
        } finally {
            service.discardDraft(player, stack, ZombiesDeployTool.getDraft(stack));
            if (map != null) { FPSMCore.getInstance().unregisterMap(map); }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
