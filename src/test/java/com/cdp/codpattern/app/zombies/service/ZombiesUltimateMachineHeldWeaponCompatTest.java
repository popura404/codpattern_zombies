package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSlot;
import com.cdp.codpattern.app.zombies.model.ZombiesPlayerRuntimeState;
import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Focused pure-service regressions plus source wiring checks; no Minecraft client required. */
public final class ZombiesUltimateMachineHeldWeaponCompatTest {
    public static void main(String[] args) throws Exception {
        for (ZombiesEquipmentSlot slot : ZombiesEquipmentSlot.values()) {
            upgradesOnlySelectedSource(slot);
            missingTrackedWeaponDoesNotFallBack(slot);
            liveReserveCapacityIsNotWeaponIdentity(slot);
        }
        starterAndMysteryDoNotRequirePrimary();
        failedUpgradesDoNotSpend();
        nonAmmoIdentityRemainsStrict();
        legacyPrimaryApiStillWorks();
        mainHandWiringNeverSearchesInventory();
        System.out.println("PASS: ultimate-machine focused checks; three sources, slot isolation, failures and main-hand source wiring");
    }

    private static void upgradesOnlySelectedSource(ZombiesEquipmentSlot selected) {
        Fixture f = new Fixture();
        ZombiesWeaponInstanceState held = f.weapon.withReserveAmmo(7);
        int[] commits = {0};
        var result = f.service.upgradeHeldWeapon(f.id, selected, held, f.rules, false,
                (current, upgraded) -> {
                    require(current.equals(held), "use the held item's live ammo, not stale runtime ammo");
                    require(upgraded.reserveAmmo() == 7, "upgrade must not refill or overwrite live ammo");
                    commits[0]++;
                    return ZombiesServiceResult.ok();
                });
        require(result.success(), selected + " upgrade failed: " + result.code());
        require(commits[0] == 1, "commit exactly once");
        require(f.state.points() == 4500.0D, "charge selected upgrade once");
        for (ZombiesEquipmentSlot slot : ZombiesEquipmentSlot.values()) {
            ZombiesWeaponInstanceState actual = get(f.state, slot);
            require(actual.equals(slot == selected ? held.withUpgrade(1, 2.0D) : f.weapon),
                    "same-model weapons in other slots must remain unchanged: " + slot);
        }
        var second = f.service.upgradeHeldWeapon(f.id, selected, get(f.state, selected), f.rules, false, null);
        require(second.success() && get(f.state, selected).upgradeLevel() == 2, "second upgrade");
        require(f.state.points() == 3600.0D, "second-level price");
        var max = f.service.upgradeHeldWeapon(f.id, selected, get(f.state, selected), f.rules, false, null);
        require(!max.success() && max.code().equals(ZombiesErrorCode.WEAPON_MAX_UPGRADE), "max-level rejection");
        require(f.state.points() == 3600.0D, "max level must not charge");
        System.out.println("PASS: " + selected + " upgrades only held source, preserves live ammo, respects levels and cost");
    }

    private static void missingTrackedWeaponDoesNotFallBack(ZombiesEquipmentSlot slot) {
        Fixture f = new Fixture();
        set(f.state, slot, null);
        var result = f.service.upgradeHeldWeapon(f.id, slot, f.weapon, f.rules, false, null);
        require(!result.success(), "missing selected slot must not fall back to another gun");
        require(get(f.state, slot) == null && f.state.points() == 5000.0D, "missing slot must not mutate or charge");
    }

    private static void liveReserveCapacityIsNotWeaponIdentity(ZombiesEquipmentSlot selected) {
        var reserves = java.util.List.of(
                new ZombiesBaseGunAmmoService.ReserveAmmo(ZombiesBaseGunAmmoService.ReserveStatus.VALID, 0, 0),
                new ZombiesBaseGunAmmoService.ReserveAmmo(ZombiesBaseGunAmmoService.ReserveStatus.VALID, 7, 240),
                new ZombiesBaseGunAmmoService.ReserveAmmo(ZombiesBaseGunAmmoService.ReserveStatus.INVALID, -1, 180),
                new ZombiesBaseGunAmmoService.ReserveAmmo(ZombiesBaseGunAmmoService.ReserveStatus.MISSING, 0, 0));
        for (var reserve : reserves) {
            Fixture f = new Fixture();
            ZombiesWeaponInstanceState held = ZombiesBaseGunAmmoService.withReserve(f.weapon, reserve);
            int[] commits = {0};
            var result = f.service.upgradeHeldWeapon(f.id, selected, held, f.rules, false,
                    (current, upgraded) -> {
                        require(current.equals(held), "upgrade observes live or unavailable-ammo mirror");
                        require(upgraded.reserveAmmo() == held.reserveAmmo()
                                        && upgraded.maxReserveAmmo() == held.maxReserveAmmo(),
                                "metadata upgrade cannot repair or refill ammunition");
                        commits[0]++;
                        return ZombiesServiceResult.ok();
                    });
            require(result.success() && commits[0] == 1, "changed/zero/unavailable live capacity permits metadata upgrade");
            require(f.state.points() == 4500.0D, "metadata upgrade charges exactly once");
            for (ZombiesEquipmentSlot slot : ZombiesEquipmentSlot.values()) {
                require(get(f.state, slot).equals(slot == selected ? held.withUpgrade(1, 2.0D) : f.weapon),
                        "capacity changes mirror only the selected slot");
            }
        }
        System.out.println("PASS: " + selected + " upgrades preserve zero/changed/unavailable live reserve mirrors");
    }

    private static void starterAndMysteryDoNotRequirePrimary() {
        for (ZombiesEquipmentSlot slot : new ZombiesEquipmentSlot[]{ZombiesEquipmentSlot.STARTER, ZombiesEquipmentSlot.MYSTERY_BOX}) {
            Fixture f = new Fixture();
            f.state.clearPrimaryWeapon();
            require(f.service.upgradeHeldWeapon(f.id, slot, f.weapon, f.rules, false, null).success(),
                    "starter/mystery upgrade must work without a purchased primary");
            require(f.state.primaryWeapon().isEmpty(), "must not create a primary");
        }
        System.out.println("PASS: starter and mystery-box upgrades do not require a weapon-box gun");
    }

    private static void failedUpgradesDoNotSpend() {
        Fixture f = new Fixture();
        require(!f.service.upgradeHeldWeapon(f.id, ZombiesEquipmentSlot.STARTER, null, f.rules, false, null).success(),
                "missing held gun must fail, even when other guns exist");
        require(!f.service.upgradeHeldWeapon(f.id, null, f.weapon, f.rules, false, null).success(), "missing slot");
        require(!f.service.upgradeHeldWeapon(f.id, ZombiesEquipmentSlot.STARTER,
                ZombiesWeaponInstanceState.primary("tacz:ak47", 1, 1.25D, 180), f.rules, false, null).success(),
                "mismatched held gun must fail");
        require(!f.service.upgradeHeldWeapon(f.id, ZombiesEquipmentSlot.STARTER,
                f.weapon.withUpgrade(1, 2.0D), f.rules, false, null).success(), "stale upgrade snapshot");
        var power = f.service.upgradeHeldWeapon(f.id, ZombiesEquipmentSlot.STARTER, f.weapon, f.rules, true, null);
        require(!power.success() && power.code().equals(ZombiesErrorCode.POWER_REQUIRES_POWER), "power gate");
        for (ZombiesEquipmentSlot slot : ZombiesEquipmentSlot.values()) {
            var denied = f.service.upgradeHeldWeapon(f.id, slot, f.weapon, f.rules, false,
                    (current, upgraded) -> ZombiesServiceResult.failure(ZombiesErrorCode.WEAPON_INVALID_CURRENT_WEAPON));
            require(!denied.success(), "changed/invalid hand must abort commit");
            require(get(f.state, slot).equals(f.weapon), "failed commit must not update runtime state");
        }
        require(f.state.points() == 5000.0D, "all failed requests leave points unchanged");
        f.state.spendPoints(4900.0D);
        int[] commits = {0};
        var poor = f.service.upgradeHeldWeapon(f.id, ZombiesEquipmentSlot.MYSTERY_BOX, f.weapon, f.rules, false,
                (current, upgraded) -> { commits[0]++; return ZombiesServiceResult.ok(); });
        require(!poor.success() && poor.code().equals(ZombiesErrorCode.ECONOMY_NOT_ENOUGH_POINTS), "insufficient points");
        require(commits[0] == 0 && f.state.points() == 100.0D, "unaffordable upgrade cannot reach item commit");
        System.out.println("PASS: missing/mismatched weapon, power, maximum level, failed commit and insufficient points do not charge");
    }

    private static void legacyPrimaryApiStillWorks() {
        Fixture f = new Fixture();
        require(f.service.upgradePrimaryWeapon(f.id, f.rules, false).success(), "legacy primary API");
        require(f.state.primaryWeapon().orElseThrow().upgradeLevel() == 1, "legacy primary update");
        require(f.state.starterWeapon().orElseThrow().equals(f.weapon), "legacy leaves starter alone");
    }

    private static void nonAmmoIdentityRemainsStrict() {
        Fixture f = new Fixture();
        var mismatches = java.util.List.of(
                new ZombiesWeaponInstanceState("tacz:ak47", "rare", 1, 0, 1.25, 1.0, 0, 0),
                new ZombiesWeaponInstanceState(f.weapon.gunId(), "common", 1, 0, 1.25, 1.0, 0, 0),
                new ZombiesWeaponInstanceState(f.weapon.gunId(), "rare", 2, 0, 1.25, 1.0, 0, 0),
                new ZombiesWeaponInstanceState(f.weapon.gunId(), "rare", 1, 1, 1.25, 2.0, 0, 0),
                new ZombiesWeaponInstanceState(f.weapon.gunId(), "rare", 1, 0, 1.5, 1.0, 0, 0),
                new ZombiesWeaponInstanceState(f.weapon.gunId(), "rare", 1, 0, 1.25, 1.5, 0, 0));
        int[] commits = {0};
        for (ZombiesEquipmentSlot slot : ZombiesEquipmentSlot.values()) {
            for (var held : mismatches) {
                var result = f.service.upgradeHeldWeapon(f.id, slot, held, f.rules, false,
                        (current, upgraded) -> {
                            commits[0]++;
                            return ZombiesServiceResult.ok();
                        });
                require(!result.success() && result.code().equals(ZombiesErrorCode.WEAPON_INVALID_CURRENT_WEAPON),
                        "gun, rarity, levels and both multipliers remain part of the identity check");
            }
            require(get(f.state, slot).equals(f.weapon), "identity mismatch preserves tracked weapon");
        }
        require(commits[0] == 0 && f.state.points() == 5000.0D, "identity mismatch never commits or charges");
    }

    private static void mainHandWiringNeverSearchesInventory() throws Exception {
        Path base = Path.of("src/main/java/com/cdp/codpattern/app/zombies/service");
        String source = Files.readString(base.resolve("ZombiesObjectInteractionService.java"));
        int begin = source.indexOf("    private InteractionResult useUltimateMachine(");
        int end = source.indexOf("    ZombiesServiceResult<ZombiesUltimateMachineService.WeaponUpgradeResult> useUltimateMachine(", begin);
        require(begin >= 0 && end > begin, "live interaction handler exists");
        String handler = source.substring(begin, end);
        require(handler.contains("player.getMainHandItem()"), "read server-side main hand");
        require(handler.contains("upgradeHeldWeapon(") && handler.contains("tag.slot()"), "upgrade held source slot");
        require(!handler.contains("getOffhandItem") && !handler.contains("ZombiesEquipmentSlot.PRIMARY"), "no offhand or fixed-primary selection");
        require(source.contains("context.hand() == InteractionHand.MAIN_HAND")
                && source.contains("case ULTIMATE_MACHINE -> mainHandTacz;"), "reject offhand interaction events");
        String inventory = Files.readString(base.resolve("ZombiesWeaponInventoryService.java"));
        begin = inventory.indexOf("    public ZombiesServiceResult<InventoryMutationResult> syncMainHandWeapon(");
        end = inventory.indexOf("    public ZombiesServiceResult<PreparedAmmoRefill> prepareAmmoRefill(", begin);
        require(begin >= 0 && end > begin, "dedicated main-hand commit exists");
        String commit = inventory.substring(begin, end);
        require(commit.contains("player.getMainHandItem() != expectedStack") && commit.contains("filter(expectedTag::equals)"),
                "revalidate the exact held instance and its tags before commit");
        require(commit.contains("syncWeaponMetadata(expectedStack, roomId, expectedTag.slot(), weaponState)"), "write only captured stack");
        require(!commit.contains("findWeaponStack") && !commit.contains("getContainerSize")
                && !commit.contains("getOffhandItem") && !commit.contains("getInventory()"), "no inventory/offhand fallback search");
        System.out.println("PASS: source wiring reads main hand, rejects offhand events, and commits exact stack without inventory search");
    }

    private static ZombiesWeaponInstanceState get(ZombiesPlayerRuntimeState state, ZombiesEquipmentSlot slot) {
        return switch (slot) {
            case STARTER -> state.starterWeapon().orElse(null);
            case PRIMARY -> state.primaryWeapon().orElse(null);
            case MYSTERY_BOX -> state.mysteryBoxWeapon().orElse(null);
        };
    }

    private static void set(ZombiesPlayerRuntimeState state, ZombiesEquipmentSlot slot, ZombiesWeaponInstanceState weapon) {
        switch (slot) {
            case STARTER -> state.setStarterWeapon(weapon);
            case PRIMARY -> state.setPrimaryWeapon(weapon);
            case MYSTERY_BOX -> state.setMysteryBoxWeapon(weapon);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final ZombiesPlayerStateService players = new ZombiesPlayerStateService();
        final ZombiesEconomyService economy = new ZombiesEconomyService(players);
        final ZombiesUltimateMachineService service = new ZombiesUltimateMachineService(economy, new ZombiesPowerService(economy));
        final ZombiesRulesConfig.UltimateMachine rules = new ZombiesRulesConfig.UltimateMachine();
        final ZombiesPlayerRuntimeState state = players.getOrCreate(id);
        final ZombiesWeaponInstanceState weapon = new ZombiesWeaponInstanceState("tacz:m4a1", "rare", 1, 0, 1.25D, 1.0D, 90, 180);
        Fixture() {
            rules.setMaxUpgradeLevel(2);
            rules.setLevels(Map.of("1", new ZombiesRulesConfig.UpgradeLevel(500, 2.0D),
                    "2", new ZombiesRulesConfig.UpgradeLevel(900, 3.0D)));
            economy.addPoints(id, 5000.0D);
            for (ZombiesEquipmentSlot slot : ZombiesEquipmentSlot.values()) set(state, slot, weapon);
        }
    }
}
