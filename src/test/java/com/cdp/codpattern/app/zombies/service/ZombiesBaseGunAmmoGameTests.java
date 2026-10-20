package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.resource.pojo.data.attachment.AttachmentData;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/** Standard TaCZ tests; the separate runtime compatibility suite exercises actual alternate modes. */
@GameTestHolder("codpattern_zombies_ammo")
@PrefixGameTestTemplate(false)
public final class ZombiesBaseGunAmmoGameTests {
    private static final ResourceLocation AK47 = new ResourceLocation("tacz:ak47");

    @GameTest(template = "empty", templateNamespace = "codpattern_zombies_ammo", timeoutTicks = 100)
    public static void reservePresenceAndInvalidDataNeverBecomeCachedAmmo(GameTestHelper helper) {
        ItemStack gun = gun();
        gun.setTag(null);
        check(ZombiesBaseGunAmmoService.readReserve(gun).status()
                == ZombiesBaseGunAmmoService.ReserveStatus.MISSING, "Absent root is missing");
        check(gun.getTag() == null, "Reading missing ammo must not create a tag");

        CompoundTag root = new CompoundTag();
        root.putString("GunId", AK47.toString());
        gun.setTag(root);
        root.putInt("DummyAmmo", 0);
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.MISSING, false);
        root.putInt("MaxDummyAmmo", 0);
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.VALID, true);
        root.remove("GunId");
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.MISSING, false);
        root.putInt("GunId", 1);
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.INVALID, false);
        root.putString("GunId", "invalid gun id");
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.INVALID, false);
        root.putString("GunId", "");
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.INVALID, false);
        root.putString("GunId", AK47.toString());
        root.putInt("MaxDummyAmmo", 120);
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.VALID, true);
        check(ZombiesBaseGunAmmoService.readReserve(gun).current() == 0, "Zero reserve is exhausted, not absent");
        root.putString("DummyAmmo", "0");
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.INVALID, false);
        root.putInt("DummyAmmo", -1);
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.INVALID, false);
        root.putInt("DummyAmmo", 121);
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.INVALID, false);
        root.putInt("DummyAmmo", 0);
        root.putInt("MaxDummyAmmo", -1);
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.INVALID, false);
        root.putLong("MaxDummyAmmo", 120L);
        assertReadOnly(gun, ZombiesBaseGunAmmoService.ReserveStatus.INVALID, false);

        var cached = new ZombiesWeaponInstanceState(AK47.toString(), "rare", 2, 1, 1.5, 2.0, 120, 120);
        var mirrored = ZombiesBaseGunAmmoService.withReserve(cached, ZombiesBaseGunAmmoService.readReserve(gun));
        check(mirrored.reserveAmmo() == 0 && mirrored.maxReserveAmmo() == 0, "Invalid live state cannot reuse cached ammo");
        check(mirrored.gunId().equals(cached.gunId()) && mirrored.rarityId().equals(cached.rarityId())
                && mirrored.weaponLevel() == 2 && mirrored.upgradeLevel() == 1
                && mirrored.levelDamageMultiplier() == 1.5 && mirrored.upgradeDamageMultiplier() == 2.0,
                "Ammo mirroring preserves weapon metadata");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_zombies_ammo", timeoutTicks = 100)
    public static void initializationWritesOnlyFourRootAmmoFields(GameTestHelper helper) {
        ItemStack gun = gun();
        addOpaqueState(gun);
        CompoundTag original = gun.getTag().copy();
        GunData resource = TimelessAPI.getCommonGunIndex(AK47).orElseThrow().getGunData();
        var info = ZombiesBaseGunAmmoService.resolveBaseGun(gun).value().orElseThrow();
        check(info.gunId().equals(AK47.toString()) && info.magazineAmmo() == resource.getAmmoAmount(),
                "Initialization resolves the loaded root gun's capacity");
        check(gun.getTag().equals(original), "Resource resolution must be read-only");
        check(ZombiesBaseGunAmmoService.initialize(gun, 17, 120).success(), "Initialization succeeds");
        CompoundTag expected = original.copy();
        expected.putInt("GunCurrentAmmoCount", resource.getAmmoAmount());
        expected.putBoolean("HasBulletInBarrel", resource.getAmmoAmount() > 0 && resource.getBolt() != Bolt.OPEN_BOLT);
        expected.putInt("DummyAmmo", 17);
        expected.putInt("MaxDummyAmmo", 120);
        check(gun.getTag().equals(expected), "Initialization changes exactly the four root ammunition fields");
        check(ZombiesBaseGunAmmoService.initialize(gun, 0, 0).success(), "Explicit zero capacity is a valid grant");
        var zero = ZombiesBaseGunAmmoService.readReserve(gun);
        check(zero.valid() && zero.current() == 0 && zero.maximum() == 0, "Zero capacity remains present and valid");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_zombies_ammo", timeoutTicks = 100)
    public static void failedInitializationAndStaleRefillsLeaveTheWholeStackUntouched(GameTestHelper helper) {
        ItemStack gun = gun();
        addOpaqueState(gun);
        CompoundTag before = gun.getTag().copy();
        check(!ZombiesBaseGunAmmoService.initialize(gun, 121, 120).success(), "Invalid grant amounts fail");
        check(gun.getTag().equals(before), "Invalid grant leaves all state intact");
        gun.getTag().putString("GunId", "codpattern_zombies:missing_test_resource");
        before = gun.getTag().copy();
        var failed = ZombiesBaseGunAmmoService.initialize(gun, 0, 120);
        check(!failed.success() && failed.code().key().equals("weapon.item_unavailable"), "Missing base resource fails");
        check(gun.getTag().equals(before), "Missing resource cannot partially initialize");
        gun.getTag().putString("GunId", AK47.toString());
        check(ZombiesBaseGunAmmoService.initialize(gun, 0, 120).success(), "Setup initializes a real gun");
        var expected = ZombiesBaseGunAmmoService.readReserve(gun);
        gun.getTag().putInt("DummyAmmo", 1);
        assertRefillFailure(gun, expected, "ammo.base_reserve_unavailable");
        expected = ZombiesBaseGunAmmoService.readReserve(gun);
        gun.getTag().putInt("MaxDummyAmmo", 121);
        assertRefillFailure(gun, expected, "ammo.base_reserve_unavailable");

        gun.getTag().putInt("DummyAmmo", 121);
        assertRefillFailure(gun, ZombiesBaseGunAmmoService.readReserve(gun), "ammo.already_full");
        gun.getTag().putInt("DummyAmmo", 0);
        gun.getTag().putInt("MaxDummyAmmo", 0);
        assertRefillFailure(gun, ZombiesBaseGunAmmoService.readReserve(gun), "ammo.no_reserve_capacity");
        gun.getTag().remove("MaxDummyAmmo");
        assertRefillFailure(gun, ZombiesBaseGunAmmoService.readReserve(gun), "ammo.base_reserve_unavailable");

        gun.getTag().putInt("MaxDummyAmmo", 120);
        before = gun.getTag().copy();
        var filled = ZombiesBaseGunAmmoService.refill(gun, ZombiesBaseGunAmmoService.readReserve(gun));
        check(filled.success() && filled.value().orElseThrow().current() == 120, "Exhausted root reserve refills");
        before.putInt("DummyAmmo", 120);
        check(gun.getTag().equals(before), "Refill writes only DummyAmmo, preserving capacity and every other tag");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_zombies_ammo", timeoutTicks = 100)
    public static void realExtendedMagazineControlsNewGunInitialization(GameTestHelper helper) {
        ItemStack gun = gun();
        IGun api = IGun.getIGunOrNull(gun);
        GunData data = TimelessAPI.getCommonGunIndex(AK47).orElseThrow().getGunData();
        var entry = TimelessAPI.getAllCommonAttachmentIndex().stream()
                .filter(candidate -> candidate.getValue().getType() == AttachmentType.EXTENDED_MAG)
                .filter(candidate -> candidate.getValue().getData().getExtendedMagLevel() > 0)
                .filter(candidate -> api.allowAttachment(gun, AttachmentItemBuilder.create().setId(candidate.getKey()).build()))
                .findFirst().orElseThrow(() -> new AssertionError("Loaded default AK47 must have a compatible extended magazine"));
        ItemStack magazine = AttachmentItemBuilder.create().setId(entry.getKey()).build();
        api.installAttachment(gun, magazine);
        check(entry.getKey().equals(api.getAttachmentId(gun, AttachmentType.EXTENDED_MAG)),
                "TaCZ actually installed the fixture magazine");
        AttachmentData effective = data.getExclusiveAttachments() == null ? null : data.getExclusiveAttachments().get(entry.getKey());
        int level = Math.min(3, (effective == null ? entry.getValue().getData() : effective).getExtendedMagLevel());
        int capacity = data.getExtendedMagAmmoAmount()[level - 1];
        check(ZombiesBaseGunAmmoService.initialize(gun, 0, 120).success(), "Extended-mag grant succeeds");
        check(gun.getTag().getInt("GunCurrentAmmoCount") == capacity, "Grant uses the loaded extended capacity");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_zombies_ammo", timeoutTicks = 100)
    public static void exclusiveCapacityPriorityAndMalformedResourcesAreBounded(GameTestHelper helper) {
        // Isolated algorithm fixtures: these do not replace registered resources or claim gun-pack coverage.
        ResourceLocation id = new ResourceLocation("codpattern_zombies:test_exclusive_mag");
        CompoundTag root = new CompoundTag();
        CompoundTag attachment = new CompoundTag();
        CompoundTag tag = new CompoundTag();
        tag.putString("AttachmentId", id.toString());
        attachment.put("tag", tag);
        root.put("AttachmentEXTENDED_MAG", attachment);
        check(ZombiesBaseGunAmmoService.magazineAmmo(root, capacityData(id, 2, new int[]{40, 50, 60})) == 50,
                "Gun-specific capacity works without a global attachment resource");
        check(ZombiesBaseGunAmmoService.magazineAmmo(root, capacityData(id, 9, new int[]{40, 50, 60})) == 60,
                "Extended level is bounded to TaCZ's three levels");
        check(ZombiesBaseGunAmmoService.magazineAmmo(root, capacityData(id, 0, new int[]{40, 50, 60})) == 30,
                "Zero extension level uses the base capacity");
        check(ZombiesBaseGunAmmoService.magazineAmmo(root, capacityData(id, 3, new int[]{40})) < 0,
                "Short extended arrays are unavailable rather than indexing or using active mode");
        check(ZombiesBaseGunAmmoService.magazineAmmo(root, capacityData(id, 2, new int[]{40, -5, 60})) < 0,
                "Negative configured capacity cannot initialize a gun");
        check(ZombiesBaseGunAmmoService.magazineAmmo(root, capacityData(id, 2, null)) == 30,
                "No extended array follows TaCZ's base-capacity behavior");
        helper.succeed();
    }

    private static GunData capacityData(ResourceLocation id, int level, int[] capacities) {
        AttachmentData attachment = new AttachmentData() {
            @Override
            public int getExtendedMagLevel() {
                return level;
            }
        };
        return new GunData() {
            @Override
            public int getAmmoAmount() {
                return 30;
            }

            @Override
            public int[] getExtendedMagAmmoAmount() {
                return capacities;
            }

            @Override
            public List<AttachmentType> getAllowAttachments() {
                return List.of(AttachmentType.EXTENDED_MAG);
            }

            @Override
            public Map<ResourceLocation, AttachmentData> getExclusiveAttachments() {
                return Map.of(id, attachment);
            }
        };
    }

    private static void assertReadOnly(ItemStack gun, ZombiesBaseGunAmmoService.ReserveStatus status, boolean valid) {
        CompoundTag before = gun.getTag().copy();
        var reserve = ZombiesBaseGunAmmoService.readReserve(gun);
        check(reserve.status() == status && reserve.valid() == valid, "Reserve presence/type/range is classified correctly");
        check(gun.getTag().equals(before), "Reserve reads never repair or rewrite state");
    }

    private static void assertRefillFailure(ItemStack gun, ZombiesBaseGunAmmoService.ReserveAmmo expected, String code) {
        CompoundTag before = gun.getTag().copy();
        var result = ZombiesBaseGunAmmoService.refill(gun, expected);
        check(!result.success() && result.code().key().equals(code), "Refill must fail with " + code);
        check(gun.getTag().equals(before), "Rejected refill must not mutate any field");
    }

    private static ItemStack gun() {
        ItemStack stack = GunItemBuilder.create().setId(AK47).setAmmoCount(0).setAmmoInBarrel(false).build();
        check(!stack.isEmpty(), "Loaded default gun pack must contain tacz:ak47");
        return stack;
    }

    private static void addOpaqueState(ItemStack stack) {
        CompoundTag opaque = new CompoundTag();
        opaque.putInt("DummyAmmo", 3);
        opaque.putInt("MaxDummyAmmo", 9);
        opaque.putInt("GunCurrentAmmoCount", 1);
        opaque.putString("state", "preserve");
        stack.getOrCreateTag().put("test_other_mode", opaque);
        stack.getTag().putString("GunFireMode", "SEMI");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
