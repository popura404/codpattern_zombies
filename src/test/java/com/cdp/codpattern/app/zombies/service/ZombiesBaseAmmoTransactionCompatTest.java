package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSlot;
import com.cdp.codpattern.app.zombies.model.ZombiesPlayerRuntimeState;
import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure economy and slot transaction checks; no Minecraft bootstrap or skipped checks. */
public final class ZombiesBaseAmmoTransactionCompatTest {
    private static int assertions;

    public static void main(String[] args) {
        assertions = 0;
        identicalGunAndRarityUpdateOnlyTheDeclaredSlot();
        zeroFullAndMissingGuardNeverSpend();
        failedItemCommitLeavesPointsAndAllSlotCachesUnchanged();
        pricesAndSingleSuccessfulCharge();
        System.out.println("ZombiesBaseAmmoTransactionCompatTest PASS (" + assertions + " assertions, no skips)");
    }

    private static void identicalGunAndRarityUpdateOnlyTheDeclaredSlot() {
        for (var slot : ZombiesEquipmentSlot.values()) {
            var fixture = fixture();
            var before = weapons(fixture.runtime());
            var live = weapon(3, 120); // Actual item is newer than all three slot mirrors.
            var commits = new AtomicInteger();
            var result = fixture.ammo().refillHeldWeapon(fixture.player(), slot, live, Map.of(2, 150),
                    (current, refilled) -> {
                        commits.incrementAndGet();
                        require(current.equals(live), "Guard receives the prepared live item state");
                        require(refilled.reserveAmmo() == 120 && refilled.maxReserveAmmo() == 120,
                                "Guard receives exactly the filled base reserve");
                        require(fixture.runtime().points() == 1000, "Points are deducted only after item commit");
                        require(weapons(fixture.runtime()).equals(before), "Slot mirrors change only after item commit");
                        return ZombiesServiceResult.ok();
                    });
            require(result.success(), "Explicit slot refill succeeds: " + slot);
            require(commits.get() == 1, "Successful refill invokes one commit: " + slot);
            require(fixture.runtime().points() == 850, "Successful refill deducts its price once: " + slot);
            require(result.value().orElseThrow().starterWeapon() == (slot == ZombiesEquipmentSlot.STARTER),
                    "Result identifies the declared starter slot");
            for (var other : ZombiesEquipmentSlot.values()) {
                var actual = weapon(fixture.runtime(), other);
                require(actual.equals(other == slot ? live.refillReserveAmmo() : before.get(other.ordinal())),
                        "Same gun/rarity in another slot must not be overwritten: " + slot + " / " + other);
            }
            var second = fixture.ammo().refillHeldWeapon(fixture.player(), slot, live.refillReserveAmmo(), Map.of(2, 150),
                    (current, refilled) -> { commits.incrementAndGet(); return ZombiesServiceResult.ok(); });
            requireFailure(second, "ammo.already_full");
            require(commits.get() == 1 && fixture.runtime().points() == 850,
                    "Already-full repeat cannot commit or deduct again");
        }
    }

    private static void zeroFullAndMissingGuardNeverSpend() {
        for (var reserve : List.of(weapon(0, 0), weapon(120, 120))) {
            var fixture = fixture();
            var before = weapons(fixture.runtime());
            var calls = new AtomicInteger();
            var result = fixture.ammo().refillHeldWeapon(fixture.player(), ZombiesEquipmentSlot.PRIMARY,
                    reserve, Map.of(2, 100), (current, refilled) -> {
                        calls.incrementAndGet(); return ZombiesServiceResult.ok();
                    });
            requireFailure(result, reserve.maxReserveAmmo() == 0 ? "ammo.no_reserve_capacity" : "ammo.already_full");
            require(calls.get() == 0, "Zero-capacity/full reserve never enters the commit guard");
            requireUnchanged(fixture, before, "Zero-capacity/full reserve");
        }
        var fixture = fixture();
        var before = weapons(fixture.runtime());
        requireFailure(fixture.ammo().refillHeldWeapon(fixture.player(), ZombiesEquipmentSlot.PRIMARY,
                weapon(3, 120), Map.of(2, 100), null), "weapon.invalid_current_weapon");
        requireFailure(fixture.ammo().refillHeldWeapon(fixture.player(), null,
                weapon(3, 120), Map.of(2, 100), (current, refilled) -> ZombiesServiceResult.ok()),
                "weapon.invalid_current_weapon");
        requireUnchanged(fixture, before, "Missing slot/commit guard");
    }

    private static void failedItemCommitLeavesPointsAndAllSlotCachesUnchanged() {
        for (boolean nullResult : List.of(false, true)) {
            for (var slot : ZombiesEquipmentSlot.values()) {
                var fixture = fixture();
                var before = weapons(fixture.runtime());
                var calls = new AtomicInteger();
                var result = fixture.ammo().refillHeldWeapon(fixture.player(), slot, weapon(3, 120), Map.of(2, 150),
                        (current, refilled) -> {
                            calls.incrementAndGet();
                            return nullResult ? null : ZombiesServiceResult.failure(ZombiesErrorCode.of("fixture.commit_rejected"));
                        });
                requireFailure(result, nullResult ? "weapon.invalid_current_weapon" : "fixture.commit_rejected");
                require(calls.get() == 1, "Rejected item commit is attempted only once");
                requireUnchanged(fixture, before, "Rejected item commit for " + slot);
            }
        }
    }

    private static void pricesAndSingleSuccessfulCharge() {
        List<Map<?, Integer>> prices = List.of(Map.of(), Map.of(1, 150), Map.of(2, -1), Map.of(2, 1001));
        List<String> errors = List.of("ammo.missing_price", "ammo.missing_price",
                "economy.invalid_cost", "economy.not_enough_points");
        for (int i = 0; i < prices.size(); i++) {
            var fixture = fixture();
            var before = weapons(fixture.runtime());
            var calls = new AtomicInteger();
            var result = fixture.ammo().refillHeldWeapon(fixture.player(), ZombiesEquipmentSlot.PRIMARY,
                    weapon(3, 120), prices.get(i), (current, refilled) -> {
                        calls.incrementAndGet(); return ZombiesServiceResult.ok();
                    });
            requireFailure(result, errors.get(i));
            require(calls.get() == 0, "Invalid/unaffordable price never commits an item");
            requireUnchanged(fixture, before, "Invalid/unaffordable price");
        }
        List<Map<?, Integer>> validPrices = List.of(Map.of("2", 170), Map.of(2, 130, "2", 170), Map.of(2, 0));
        List<Integer> costs = List.of(170, 130, 0);
        for (int i = 0; i < validPrices.size(); i++) {
            var fixture = fixture();
            var calls = new AtomicInteger();
            var result = fixture.ammo().refillHeldWeapon(fixture.player(), ZombiesEquipmentSlot.MYSTERY_BOX,
                    weapon(3, 120), validPrices.get(i), (current, refilled) -> {
                        calls.incrementAndGet(); return ZombiesServiceResult.ok();
                    });
            require(result.success(), "Integer/string/free price can refill");
            require(result.value().orElseThrow().cost() == costs.get(i), "Resolved price is reported exactly");
            require(calls.get() == 1 && fixture.runtime().points() == 1000 - costs.get(i),
                    "Each successful transaction commits and charges once");
        }
    }

    private static Fixture fixture() {
        var players = new ZombiesPlayerStateService();
        var economy = new ZombiesEconomyService(players);
        UUID player = UUID.randomUUID();
        economy.addPoints(player, 1000);
        var state = players.getOrCreate(player);
        state.setStarterWeapon(weapon(11, 120));
        state.setPrimaryWeapon(weapon(22, 120));
        state.setMysteryBoxWeapon(weapon(33, 120));
        return new Fixture(player, state, new ZombiesAmmoBoxService(economy));
    }

    private static ZombiesWeaponInstanceState weapon(int current, int maximum) {
        return new ZombiesWeaponInstanceState("tacz:ak47", "rare", 2, 1, 1.25D, 2.0D, current, maximum);
    }

    private static ZombiesWeaponInstanceState weapon(ZombiesPlayerRuntimeState state, ZombiesEquipmentSlot slot) {
        return switch (slot) {
            case STARTER -> state.starterWeapon().orElseThrow();
            case PRIMARY -> state.primaryWeapon().orElseThrow();
            case MYSTERY_BOX -> state.mysteryBoxWeapon().orElseThrow();
        };
    }

    private static List<ZombiesWeaponInstanceState> weapons(ZombiesPlayerRuntimeState state) {
        return List.of(state.starterWeapon().orElseThrow(), state.primaryWeapon().orElseThrow(),
                state.mysteryBoxWeapon().orElseThrow());
    }

    private static void requireUnchanged(Fixture fixture, List<ZombiesWeaponInstanceState> before, String context) {
        require(fixture.runtime().points() == 1000, context + " must not deduct points");
        require(weapons(fixture.runtime()).equals(before), context + " must not modify any slot cache");
    }

    private static void requireFailure(ZombiesServiceResult<?> result, String code) {
        require(!result.success() && result.code().equals(ZombiesErrorCode.of(code)),
                "Expected " + code + ", got " + result.code());
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(UUID player, ZombiesPlayerRuntimeState runtime, ZombiesAmmoBoxService ammo) { }

    private ZombiesBaseAmmoTransactionCompatTest() { }
}
