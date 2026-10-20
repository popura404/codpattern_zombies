package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.match.model.ModeObjectInteractionContext;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.map.object.ZombiesAmmoBoxData;
import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSlot;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.model.ZombiesPlayerRuntimeState;
import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;
import com.cdp.codpattern.app.zombies.service.ZombiesActiveSpawnGroupService;
import com.cdp.codpattern.app.zombies.service.ZombiesAmmoBoxService;
import com.cdp.codpattern.app.zombies.service.ZombiesArmorService;
import com.cdp.codpattern.app.zombies.service.ZombiesBarrierService;
import com.cdp.codpattern.app.zombies.service.ZombiesBaseGunAmmoService;
import com.cdp.codpattern.app.zombies.service.ZombiesBuffService;
import com.cdp.codpattern.app.zombies.service.ZombiesEconomyService;
import com.cdp.codpattern.app.zombies.service.ZombiesObjectInteractionService;
import com.cdp.codpattern.app.zombies.service.ZombiesObjectStateStore;
import com.cdp.codpattern.app.zombies.service.ZombiesPlayerStateService;
import com.cdp.codpattern.app.zombies.service.ZombiesPowerService;
import com.cdp.codpattern.app.zombies.service.ZombiesUltimateMachineService;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponInstanceService;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponInventoryService;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponItemStackService;
import com.cdp.codpattern.common.block.CodPatternBlockRegister;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Exercises the real prepared-item commit inside the economy transaction, including rollback. */
@GameTestHolder("codpattern_backpack")
@PrefixGameTestTemplate(false)
public final class ZombiesBaseAmmoCommitGameTests {
    private static final RoomId ROOM = RoomId.of("zombies", "base-ammo-commit");
    private static final ZombiesWeaponItemStackService TAGS = new ZombiesWeaponItemStackService();

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void actualRefillUpdatesOnlyThePreparedItemAndDeclaredSlot(GameTestHelper helper) {
        for (var slot : ZombiesEquipmentSlot.values()) {
            var fixture = fixture(helper);
            try {
                fixture.player().getInventory().selected = slot.defaultInventorySlot();
                ItemStack stack = fixture.player().getMainHandItem();
                var beforeSlots = weaponStates(fixture.runtime());
                var beforeItems = itemTags(fixture.player());
                var prepare = fixture.inventory().prepareAmmoRefill(ROOM, stack);
                require(prepare.success(), "Actual gun prepares a base refill");
                var prepared = prepare.value().orElseThrow();
                var result = fixture.ammo().refillHeldWeapon(fixture.player().getUUID(), slot,
                        prepared.weaponState(), Map.of(2, 100),
                        (current, filled) -> fixture.inventory().commitAmmoRefill(fixture.player(), ROOM, prepared));
                require(result.success(), "Actual player item commit succeeds for " + slot);
                require(fixture.runtime().points() == 900, "Successful item commit charges exactly once");
                for (var other : ZombiesEquipmentSlot.values()) {
                    var expectedState = other == slot ? beforeSlots.get(other.ordinal()).refillReserveAmmo()
                            : beforeSlots.get(other.ordinal());
                    require(weaponStates(fixture.runtime()).get(other.ordinal()).equals(expectedState),
                            "Identical guns in other runtime slots are untouched: " + slot + "/" + other);
                    CompoundTag expectedTag = beforeItems.get(other.ordinal()).copy();
                    if (other == slot) {
                        expectedTag.putInt("DummyAmmo", 120);
                        expectedTag.putInt(ZombiesWeaponItemStackService.TAG_RESERVE_AMMO, 120);
                    }
                    require(fixture.player().getInventory().getItem(other.defaultInventorySlot()).getTag().equals(expectedTag),
                            "Only the target root reserve and metadata mirror change: " + slot + "/" + other);
                }
                require(!fixture.inventory().prepareAmmoRefill(ROOM, stack).success(),
                        "Already-full physical item cannot prepare a second purchase");
            } finally {
                fixture.player().getInventory().clearContent();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void staleAmmoOtherItemRoomOrMetadataRejectBeforeSpending(GameTestHelper helper) {
        for (int variant = 0; variant < 6; variant++) {
            var fixture = fixture(helper);
            try {
                ItemStack original = fixture.player().getInventory().getItem(ZombiesEquipmentSlot.PRIMARY.defaultInventorySlot());
                var prepared = fixture.inventory().prepareAmmoRefill(ROOM, original).value().orElseThrow();
                ItemStack target = original;
                RoomId room = ROOM;
                switch (variant) {
                    case 0 -> original.getOrCreateTag().putInt("DummyAmmo", prepared.reserve().current() - 1);
                    case 1 -> target = original.copy(); // Same complete tag is still a different item instance.
                    case 2 -> room = RoomId.of("zombies", "other-commit-room");
                    case 3 -> original.getOrCreateTag().putString(ZombiesWeaponItemStackService.TAG_INSTANCE_ID, "replaced");
                    case 4 -> original.getOrCreateTag().putString(ZombiesWeaponItemStackService.TAG_SLOT,
                            ZombiesEquipmentSlot.MYSTERY_BOX.key());
                    case 5 -> original.getOrCreateTag().putString("GunId", "tacz:glock_17");
                    default -> throw new AssertionError("Unexpected variant");
                }
                CompoundTag beforeOriginal = original.getTag().copy();
                CompoundTag beforeTarget = target.getTag().copy();
                var beforeRuntime = weaponStates(fixture.runtime());
                ItemStack capturedTarget = target;
                RoomId capturedRoom = room;
                var result = fixture.ammo().refillHeldWeapon(fixture.player().getUUID(), ZombiesEquipmentSlot.PRIMARY,
                        prepared.weaponState(), Map.of(2, 100),
                        (current, filled) -> fixture.inventory().commitAmmoRefill(capturedTarget, capturedRoom, prepared));
                require(!result.success(), "Stale prepared item must fail before payment: " + variant);
                require(fixture.runtime().points() == 1000, "Rejected commit never deducts points: " + variant);
                require(weaponStates(fixture.runtime()).equals(beforeRuntime), "Rejected commit never changes slot caches: " + variant);
                require(original.getTag().equals(beforeOriginal) && target.getTag().equals(beforeTarget),
                        "Rejected commit changes neither original nor alternate item NBT: " + variant);
            } finally {
                fixture.player().getInventory().clearContent();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void metadataFailureRollsBackNativeRefillAndPayment(GameTestHelper helper) {
        var fixture = fixture(helper);
        try {
            ItemStack stack = fixture.player().getInventory().getItem(ZombiesEquipmentSlot.PRIMARY.defaultInventorySlot());
            var prepared = fixture.inventory().prepareAmmoRefill(ROOM, stack).value().orElseThrow();
            // Initial identity checks and native refill succeed, but metadata synchronization must reject this state.
            var wrongState = new ZombiesWeaponInstanceState("tacz:glock_17", prepared.weaponState().rarityId(),
                    2, 0, 1.25D, 1.0D, prepared.reserve().current(), prepared.reserve().maximum());
            var inconsistent = new ZombiesWeaponInventoryService.PreparedAmmoRefill(stack, prepared.tagData(),
                    prepared.gunId(), prepared.reserve(), wrongState);
            CompoundTag before = stack.getTag().copy();
            var beforeRuntime = weaponStates(fixture.runtime());
            var result = fixture.ammo().refillHeldWeapon(fixture.player().getUUID(), ZombiesEquipmentSlot.PRIMARY,
                    prepared.weaponState(), Map.of(2, 100),
                    (current, filled) -> fixture.inventory().commitAmmoRefill(stack, ROOM, inconsistent));
            require(!result.success(), "Metadata failure must fail the whole refill transaction");
            require(stack.getTag().equals(before), "Failure after native refill rolls back complete original item NBT");
            require(ZombiesBaseGunAmmoService.readReserve(stack).equals(prepared.reserve()),
                    "Native base reserve is restored instead of staying refilled");
            require(fixture.runtime().points() == 1000, "Rolled-back item commit is not charged");
            require(weaponStates(fixture.runtime()).equals(beforeRuntime), "Rolled-back item commit cannot advance any slot cache");
            helper.succeed();
        } finally {
            fixture.player().getInventory().clearContent();
        }
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void realAmmoBoxAcceptsCopiedContextAndRejectsReplacedInstance(GameTestHelper helper) {
        var fixture = fixture(helper);
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(0, 3, 0));
        var originalBlock = level.getBlockState(pos);
        try {
            level.setBlock(pos, CodPatternBlockRegister.ZOMBIES_AMMO_BOX.get().defaultBlockState(), Block.UPDATE_ALL);
            fixture.player().setPos(pos.getX() + .5, pos.getY(), pos.getZ() + 1.5);
            fixture.player().getInventory().selected = ZombiesEquipmentSlot.PRIMARY.defaultInventorySlot();
            ItemStack held = fixture.player().getMainHandItem();
            ItemStack before = held.copy();
            var beforeState = fixture.runtime().primaryWeapon().orElseThrow();
            var ammoBox = new ZombiesAmmoBoxData("copied-context-ammo", Map.of("2", 100),
                    level.dimension(), pos, Optional.empty());
            var context = new ModeObjectInteractionContext(ROOM, InteractionHand.MAIN_HAND,
                    pos, Direction.NORTH, null, held);
            require(context.itemStack() != held && ItemStack.isSameItemSameTags(context.itemStack(), held),
                    "Real interaction context contains a distinct copy of the held item");

            require(interactions(fixture, ammoBox).interact(fixture.player(), context) == InteractionResult.SUCCESS,
                    "Actual ammo-box interaction accepts the copied context and commits the held item");
            require(fixture.player().getMainHandItem() == held,
                    "Refill commits the actual held item instead of replacing it with the context snapshot");
            require(ZombiesBaseGunAmmoService.readReserve(held).current() == 120
                            && fixture.runtime().primaryWeapon().orElseThrow().reserveAmmo() == 120,
                    "Actual ammo-box interaction refills native base reserve and the declared slot");
            require(fixture.runtime().points() == 900, "Actual interaction charges the configured price exactly once");
            require(ItemStack.isSameItemSameTags(context.itemStack(), before),
                    "Actual refill leaves the captured interaction snapshot unchanged");

            ItemStack replacement = before.copy();
            require(TAGS.writeWeaponTags(replacement, ROOM, "different-primary-instance",
                    ZombiesEquipmentSlot.PRIMARY, beforeState).success(), "Replacement metadata initializes");
            fixture.player().getInventory().setItem(ZombiesEquipmentSlot.PRIMARY.defaultInventorySlot(), replacement);
            fixture.runtime().setPrimaryWeapon(beforeState);
            var beforeRejectedItems = itemTags(fixture.player());
            var beforeRejectedState = weaponStates(fixture.runtime());
            // A fresh dispatcher avoids the successful first click's deduplication entry.
            require(interactions(fixture, ammoBox).interact(fixture.player(), context) == InteractionResult.FAIL,
                    "Context captured for an earlier instance rejects the same gun in a different instance");
            require(fixture.runtime().points() == 900, "Stale interaction cannot charge a replacement gun");
            require(itemTags(fixture.player()).equals(beforeRejectedItems)
                            && weaponStates(fixture.runtime()).equals(beforeRejectedState),
                    "Stale interaction changes neither replacement reserve nor any slot cache or item NBT");
            helper.succeed();
        } finally {
            level.setBlock(pos, originalBlock, Block.UPDATE_ALL);
            fixture.player().getInventory().clearContent();
        }
    }

    private static ZombiesObjectInteractionService interactions(Fixture fixture, ZombiesAmmoBoxData ammoBox) {
        var power = new ZombiesPowerService(fixture.economy());
        var store = new ZombiesObjectStateStore(power::isPowerOn);
        store.resetObjects(List.of(), List.of(), List.of(ammoBox), List.of());
        return new ZombiesObjectInteractionService(ROOM, List::of, List::of, () -> List.of(ammoBox), List::of,
                Optional::empty, List::of, List::of,
                new ZombiesBarrierService(ROOM, List::of, fixture.economy(), store,
                        new ZombiesActiveSpawnGroupService(), ignored -> true, () -> ZombiesGamePhase.WAVE_ACTIVE),
                new ZombiesWeaponInstanceService(fixture.economy()), fixture.ammo(),
                new ZombiesArmorService(fixture.economy()), power, new ZombiesBuffService(fixture.economy(), power),
                new ZombiesUltimateMachineService(fixture.economy(), power), fixture.inventory(), store);
    }

    private static Fixture fixture(GameTestHelper helper) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "BaseAmmoCommit"));
        player.getInventory().clearContent();
        var players = new ZombiesPlayerStateService();
        var economy = new ZombiesEconomyService(players);
        economy.addPoints(player.getUUID(), 1000);
        var runtime = players.getOrCreate(player.getUUID());
        for (var slot : ZombiesEquipmentSlot.values()) {
            var state = new ZombiesWeaponInstanceState("tacz:ak47", "rare", 2, 0, 1.25D, 1.0D,
                    12 + slot.ordinal(), 120);
            ItemStack stack = ZombiesWeaponInventoryService.createDefaultTaczGunStackForRules("tacz:ak47");
            require(ZombiesBaseGunAmmoService.initialize(stack, state.reserveAmmo(), state.maxReserveAmmo()).success(),
                    "Actual newly granted AK47 fixture initializes");
            CompoundTag nested = new CompoundTag();
            nested.putInt("DummyAmmo", 3);
            nested.putInt("MaxDummyAmmo", 9);
            CompoundTag extras = new CompoundTag();
            extras.put("UnderBarrelRoot", nested);
            stack.getOrCreateTag().put("Extras", extras);
            stack.getOrCreateTag().putString("OpaqueCommitFixture", slot.key());
            require(TAGS.writeWeaponTags(stack, ROOM, "commit-" + slot.key(), slot, state).success(),
                    "Fixture writes management metadata");
            player.getInventory().setItem(slot.defaultInventorySlot(), stack);
            switch (slot) {
                case STARTER -> runtime.setStarterWeapon(state);
                case PRIMARY -> runtime.setPrimaryWeapon(state);
                case MYSTERY_BOX -> runtime.setMysteryBoxWeapon(state);
            }
        }
        return new Fixture(player, runtime, new ZombiesAmmoBoxService(economy), new ZombiesWeaponInventoryService(), economy);
    }

    private static List<ZombiesWeaponInstanceState> weaponStates(ZombiesPlayerRuntimeState runtime) {
        return List.of(runtime.starterWeapon().orElseThrow(), runtime.primaryWeapon().orElseThrow(),
                runtime.mysteryBoxWeapon().orElseThrow());
    }

    private static List<CompoundTag> itemTags(ServerPlayer player) {
        return List.of(player.getInventory().getItem(0).getTag().copy(),
                player.getInventory().getItem(1).getTag().copy(), player.getInventory().getItem(2).getTag().copy());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(ServerPlayer player, ZombiesPlayerRuntimeState runtime,
                           ZombiesAmmoBoxService ammo, ZombiesWeaponInventoryService inventory,
                           ZombiesEconomyService economy) { }

    private ZombiesBaseAmmoCommitGameTests() { }
}
