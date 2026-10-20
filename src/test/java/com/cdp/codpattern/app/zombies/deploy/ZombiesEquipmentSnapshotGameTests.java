package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSlot;
import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSlotSnapshot;
import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSnapshot;
import com.cdp.codpattern.app.zombies.model.ZombiesPlayerRuntimeState;
import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;
import com.cdp.codpattern.app.zombies.service.ZombiesBaseGunAmmoService;
import com.cdp.codpattern.app.zombies.service.ZombiesEquipmentSnapshotService;
import com.cdp.codpattern.app.zombies.service.ZombiesReviveLoadoutService;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponItemStackService;
import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Real item copies and inventory restores; opaque native NBT is never rebuilt from runtime state. */
@GameTestHolder("codpattern_backpack")
@PrefixGameTestTemplate(false)
public final class ZombiesEquipmentSnapshotGameTests {
    private static final RoomId ROOM = RoomId.of("zombies", "snapshot-ammo");
    private static final ZombiesWeaponItemStackService TAGS = new ZombiesWeaponItemStackService();

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void metadataReadAndWriteDoNotChangeNativeAmmo(GameTestHelper helper) {
        ItemStack stack = gun();
        CompoundTag nativeBefore = nativeTag(stack);
        require(TAGS.writeWeaponTags(stack, ROOM, "snapshot-instance", ZombiesEquipmentSlot.PRIMARY,
                state(88, 120, 2)).success(), "Metadata write succeeds");
        require(nativeTag(stack).equals(nativeBefore), "Writing metadata must not change any native tag");
        CompoundTag allBeforeRead = stack.getTag().copy();
        var read = TAGS.readWeaponTags(stack).value().orElseThrow();
        require(read.reserveAmmo() == 88 && read.maxReserveAmmo() == 120,
                "Metadata read returns its mirror without overlaying root or active-mode ammo");
        require(stack.getTag().equals(allBeforeRead), "Metadata read is side-effect free");
        TAGS.stripWeaponTags(stack);
        require(stack.getTag().equals(nativeBefore), "Stripping metadata leaves every native tag intact");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void snapshotAndRestoreUsePhysicalItemInsteadOfStaleRuntime(GameTestHelper helper) {
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "SnapshotAuthority"));
        try {
            ItemStack stack = gun();
            require(TAGS.writeWeaponTags(stack, ROOM, "snapshot-instance", ZombiesEquipmentSlot.PRIMARY,
                    state(88, 120, 1)).success(), "Fixture metadata write");
            CompoundTag original = stack.getTag().copy();
            CompoundTag nativeBefore = nativeTag(stack);
            player.getInventory().setItem(0, stack);
            var runtime = new ZombiesPlayerRuntimeState(player.getUUID());
            // Even matching gun/rarity does not establish that a slot cache owns this item instance.
            runtime.setPrimaryWeapon(state(120, 120, 9));
            var snapshot = new ZombiesEquipmentSnapshotService()
                    .captureInventorySnapshot(ROOM, player.getInventory(), runtime);
            var saved = snapshot.slot(ZombiesEquipmentSlot.PRIMARY).orElseThrow();
            require(saved.weaponState().upgradeLevel() == 1, "Capture keeps the item's committed upgrade");
            require(saved.weaponState().reserveAmmo() == 17 && saved.weaponState().maxReserveAmmo() == 120,
                    "Capture mirrors physical root reserve, never the stale runtime or metadata reserve");
            require(nativeTag(saved.itemStack()).equals(nativeBefore), "Capture preserves complete native NBT");
            require(stack.getTag().equals(original), "Capture does not mutate the live source stack");

            var staleState = new ZombiesWeaponInstanceState("tacz:glock_17", "legendary",
                    9, 9, 9.0D, 9.0D, 999, 999);
            var oldSnapshot = new ZombiesEquipmentSnapshot(snapshot.roomId(), snapshot.playerId(), List.of(
                    new ZombiesEquipmentSlotSnapshot(0, ZombiesEquipmentSlot.PRIMARY, saved.itemStack(), staleState)));
            runtime.setPrimaryWeapon(staleState);
            var revive = new ZombiesReviveLoadoutService();
            revive.syncRuntimeStateFromSnapshot(runtime, oldSnapshot);
            assertRestoredState(runtime.primaryWeapon().orElseThrow());
            require(revive.restoreSnapshot(ROOM, player, runtime, oldSnapshot).success(), "Actual inventory restore succeeds");
            ItemStack restored = player.getInventory().getItem(0);
            require(nativeTag(restored).equals(nativeBefore),
                    "Restore preserves magazine, chamber, attachments, nested ammo, and opaque native fields");
            assertRestoredState(runtime.primaryWeapon().orElseThrow());
            var tag = TAGS.readWeaponTags(restored).value().orElseThrow();
            require(tag.instanceId().equals("snapshot-instance"), "Restore retains the real item instance identity");
            require(tag.reserveAmmo() == 17 && tag.maxReserveAmmo() == 120,
                    "Restored metadata mirrors the physical root reserve");
            helper.succeed();
        } finally {
            player.getInventory().clearContent();
        }
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void missingInvalidAndZeroRootReserveSurviveRestoreWithoutRefill(GameTestHelper helper) {
        var player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "SnapshotInvalid"));
        try {
            for (int variant = 0; variant < 6; variant++) {
                player.getInventory().clearContent();
                ItemStack stack = gun();
                CompoundTag root = stack.getOrCreateTag();
                switch (variant) {
                    case 0 -> { root.remove("DummyAmmo"); root.remove("MaxDummyAmmo"); }
                    case 1 -> root.remove("MaxDummyAmmo");
                    case 2 -> root.putString("DummyAmmo", "17");
                    case 3 -> root.putInt("DummyAmmo", -1);
                    case 4 -> root.putInt("DummyAmmo", 121);
                    case 5 -> { root.putInt("DummyAmmo", 0); root.putInt("MaxDummyAmmo", 0); }
                    default -> throw new AssertionError("Unexpected fixture");
                }
                var initialReserve = ZombiesBaseGunAmmoService.readReserve(stack);
                CompoundTag nativeBefore = nativeTag(stack);
                require(TAGS.writeWeaponTags(stack, ROOM, "invalid-" + variant, ZombiesEquipmentSlot.PRIMARY,
                        state(120, 120, 1)).success(), "Writing stale metadata does not initialize native reserve");
                player.getInventory().setItem(0, stack);
                var runtime = new ZombiesPlayerRuntimeState(player.getUUID());
                runtime.setPrimaryWeapon(state(120, 120, 9));
                var snapshot = new ZombiesEquipmentSnapshotService()
                        .captureInventorySnapshot(ROOM, player.getInventory(), runtime);
                var saved = snapshot.slot(ZombiesEquipmentSlot.PRIMARY).orElseThrow();
                require(saved.weaponState().reserveAmmo() == 0 && saved.weaponState().maxReserveAmmo() == 0,
                        "Unavailable or zero physical reserve has a 0/0 mirror: " + variant);
                require(nativeTag(saved.itemStack()).equals(nativeBefore), "Capture retains original root validity: " + variant);
                require(new ZombiesReviveLoadoutService().restoreSnapshot(ROOM, player, runtime, snapshot).success(),
                        "Invalid native ammo does not discard the complete item: " + variant);
                ItemStack restored = player.getInventory().getItem(0);
                require(nativeTag(restored).equals(nativeBefore), "Restore must not repair or refill native tags: " + variant);
                require(ZombiesBaseGunAmmoService.readReserve(restored).equals(initialReserve),
                        "Missing, invalid and valid-zero remain distinguishable: " + variant);
                var mirrored = TAGS.readWeaponTags(restored).value().orElseThrow();
                require(mirrored.reserveAmmo() == 0 && mirrored.maxReserveAmmo() == 0,
                        "Stale metadata reserve cannot return after restore: " + variant);
            }
            helper.succeed();
        } finally {
            player.getInventory().clearContent();
        }
    }

    private static ItemStack gun() {
        ItemStack stack = GunItemBuilder.create().setId(new ResourceLocation("tacz", "ak47"))
                .setAmmoCount(7).setAmmoInBarrel(true).build();
        require(!stack.isEmpty(), "Actual TaCZ AK47 fixture exists");
        ItemStack magazine = AttachmentItemBuilder.create()
                .setId(new ResourceLocation("tacz", "extended_mag_3")).build();
        IGun gun = IGun.getIGunOrNull(stack);
        require(gun != null && gun.allowAttachment(stack, magazine), "Actual AK47 permits the magazine fixture");
        gun.installAttachment(stack, magazine);
        CompoundTag root = stack.getOrCreateTag();
        root.putInt("DummyAmmo", 17);
        root.putInt("MaxDummyAmmo", 120);
        CompoundTag child = new CompoundTag();
        child.putString("GunId", "ts:gp25");
        child.putInt("DummyAmmo", 3);
        child.putInt("MaxDummyAmmo", 9);
        CompoundTag extras = new CompoundTag();
        extras.put("UnderBarrelRoot", child);
        extras.putByteArray("OpaqueFixtureState", new byte[]{1, 2, 3});
        root.put("Extras", extras);
        root.putString("UnrelatedFixtureLabel", "preserve");
        return stack;
    }

    private static ZombiesWeaponInstanceState state(int reserve, int maximum, int upgrade) {
        return new ZombiesWeaponInstanceState("tacz:ak47", "rare", 2, upgrade, 1.25D, 2.0D, reserve, maximum);
    }

    private static CompoundTag nativeTag(ItemStack stack) {
        ItemStack copy = stack.copy();
        TAGS.stripWeaponTags(copy);
        return copy.getOrCreateTag().copy();
    }

    private static void assertRestoredState(ZombiesWeaponInstanceState state) {
        require(state.gunId().equals("tacz:ak47") && state.rarityId().equals("rare"),
                "Physical item identity wins over an unrelated cached state");
        require(state.weaponLevel() == 2 && state.upgradeLevel() == 1,
                "Physical item levels win over cached levels");
        require(state.levelDamageMultiplier() == 1.25D && state.upgradeDamageMultiplier() == 2.0D,
                "Physical item damage multipliers are preserved");
        require(state.reserveAmmo() == 17 && state.maxReserveAmmo() == 120,
                "Runtime mirror uses physical root reserve");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private ZombiesEquipmentSnapshotGameTests() {
    }
}
