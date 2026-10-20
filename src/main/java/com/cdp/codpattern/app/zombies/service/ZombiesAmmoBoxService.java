package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.match.model.ModePlayerValue;
import com.cdp.codpattern.app.zombies.model.ZombiesPlayerRuntimeState;
import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSlot;
import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure ammo-box refill service. It does not inspect or mutate Minecraft item stacks.
 */
public final class ZombiesAmmoBoxService {
    private static final ZombiesErrorCode AMMO_MISSING_PRICE = ZombiesErrorCode.of("ammo.missing_price");
    private static final ZombiesErrorCode AMMO_ALREADY_FULL = ZombiesErrorCode.of("ammo.already_full");

    private final ZombiesEconomyService economyService;

    public ZombiesAmmoBoxService(ZombiesEconomyService economyService) {
        this.economyService = Objects.requireNonNull(economyService, "economyService");
    }

    public ZombiesServiceResult<AmmoRefillResult> refillPrimaryWeapon(
            UUID playerId,
            Map<?, Integer> pricesByWeaponLevel
    ) {
        return refillPrimaryWeapon(playerId, pricesByWeaponLevel, null);
    }

    public ZombiesServiceResult<AmmoRefillResult> refillPrimaryWeapon(
            UUID playerId,
            Map<?, Integer> pricesByWeaponLevel,
            AmmoRefillCommitGuard commitGuard
    ) {
        ZombiesPlayerRuntimeState state = economyService.state(playerId).orElse(null);
        if (state == null) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.WEAPON_INVALID_CURRENT_WEAPON);
        }

        ZombiesWeaponInstanceState currentWeapon = state.primaryWeapon().orElse(null);
        if (currentWeapon == null) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.WEAPON_INVALID_CURRENT_WEAPON);
        }
        if (currentWeapon.maxReserveAmmo() == 0) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.of("ammo.no_reserve_capacity"));
        }
        if (currentWeapon.isReserveFull()) {
            return ZombiesServiceResult.failure(AMMO_ALREADY_FULL, weaponParams(currentWeapon), "");
        }

        Integer cost = priceForWeaponLevel(pricesByWeaponLevel, currentWeapon.weaponLevel());
        if (cost == null) {
            return ZombiesServiceResult.failure(AMMO_MISSING_PRICE, weaponParams(currentWeapon), "");
        }
        if (!ZombiesPlayerRuntimeState.isValidCost(cost)) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.ECONOMY_INVALID_COST, weaponParams(currentWeapon), "");
        }

        return economyService.spendAtomically(playerId, cost, lockedState -> {
            ZombiesWeaponInstanceState lockedWeapon = lockedState.primaryWeapon().orElse(null);
            if (lockedWeapon == null) {
                return ZombiesServiceResult.failure(ZombiesErrorCode.WEAPON_INVALID_CURRENT_WEAPON);
            }
            if (lockedWeapon.maxReserveAmmo() == 0) {
                return ZombiesServiceResult.failure(ZombiesErrorCode.of("ammo.no_reserve_capacity"));
            }
            if (lockedWeapon.isReserveFull()) {
                return ZombiesServiceResult.failure(AMMO_ALREADY_FULL, weaponParams(lockedWeapon), "");
            }
            Integer lockedCost = priceForWeaponLevel(pricesByWeaponLevel, lockedWeapon.weaponLevel());
            if (!Objects.equals(cost, lockedCost)) {
                return ZombiesServiceResult.failure(AMMO_MISSING_PRICE, weaponParams(lockedWeapon), "");
            }

            ZombiesWeaponInstanceState refilledWeapon = lockedWeapon.refillReserveAmmo();
            ZombiesServiceResult<?> guardResult = commitGuard == null
                    ? ZombiesServiceResult.ok()
                    : commitGuard.beforeCommit(lockedWeapon, refilledWeapon);
            if (guardResult == null || !guardResult.success()) {
                return ZombiesServiceResult.failure(
                        guardResult == null ? ZombiesErrorCode.WEAPON_INVALID_CURRENT_WEAPON : guardResult.code(),
                        guardResult == null ? Map.of() : guardResult.params(),
                        guardResult == null ? "" : guardResult.logMessage());
            }
            lockedState.setPrimaryWeapon(refilledWeapon);
            return ZombiesServiceResult.success(new AmmoRefillResult(refilledWeapon, cost, false));
        });
    }

    public ZombiesServiceResult<AmmoRefillResult> refillHeldWeapon(
            UUID playerId,
            ZombiesEquipmentSlot slot,
            ZombiesWeaponInstanceState currentWeapon,
            Map<?, Integer> pricesByWeaponLevel,
            AmmoRefillCommitGuard commitGuard
    ) {
        if (slot == null || commitGuard == null || currentWeapon == null
                || !ZombiesWeaponInstanceState.isValidGunId(currentWeapon.gunId())
                || !ZombiesWeaponInstanceState.isValidWeaponLevel(currentWeapon.weaponLevel())) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.WEAPON_INVALID_CURRENT_WEAPON);
        }
        if (currentWeapon.maxReserveAmmo() == 0) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.of("ammo.no_reserve_capacity"));
        }
        if (currentWeapon.isReserveFull()) {
            return ZombiesServiceResult.failure(AMMO_ALREADY_FULL, weaponParams(currentWeapon), "");
        }

        Integer cost = priceForWeaponLevel(pricesByWeaponLevel, currentWeapon.weaponLevel());
        if (cost == null) {
            return ZombiesServiceResult.failure(AMMO_MISSING_PRICE, weaponParams(currentWeapon), "");
        }
        if (!ZombiesPlayerRuntimeState.isValidCost(cost)) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.ECONOMY_INVALID_COST, weaponParams(currentWeapon), "");
        }

        return economyService.spendAtomically(playerId, cost, lockedState -> {
            ZombiesWeaponInstanceState refilledWeapon = currentWeapon.refillReserveAmmo();
            ZombiesServiceResult<?> guardResult = commitGuard == null
                    ? ZombiesServiceResult.ok()
                    : commitGuard.beforeCommit(currentWeapon, refilledWeapon);
            if (guardResult == null || !guardResult.success()) {
                return ZombiesServiceResult.failure(
                        guardResult == null ? ZombiesErrorCode.WEAPON_INVALID_CURRENT_WEAPON : guardResult.code(),
                        guardResult == null ? Map.of() : guardResult.params(),
                        guardResult == null ? "" : guardResult.logMessage());
            }
            // The item commit validated the exact instance. Only its declared equipment slot is mirrored.
            switch (slot) {
                case STARTER -> lockedState.setStarterWeapon(refilledWeapon);
                case PRIMARY -> lockedState.setPrimaryWeapon(refilledWeapon);
                case MYSTERY_BOX -> lockedState.setMysteryBoxWeapon(refilledWeapon);
            }
            return ZombiesServiceResult.success(new AmmoRefillResult(
                    refilledWeapon, cost, slot == ZombiesEquipmentSlot.STARTER));
        });
    }

    private static Integer priceForWeaponLevel(Map<?, Integer> pricesByWeaponLevel, int weaponLevel) {
        if (pricesByWeaponLevel == null || pricesByWeaponLevel.isEmpty()) {
            return null;
        }
        Integer price = pricesByWeaponLevel.get(weaponLevel);
        return price == null ? pricesByWeaponLevel.get(Integer.toString(weaponLevel)) : price;
    }

    private static Map<String, ModePlayerValue> weaponParams(ZombiesWeaponInstanceState weapon) {
        Map<String, ModePlayerValue> params = new LinkedHashMap<>();
        params.put("gunId", ModePlayerValue.ofString(weapon == null ? "" : weapon.gunId()));
        params.put("rarityId", ModePlayerValue.ofString(weapon == null ? "" : weapon.rarityId()));
        params.put("weaponLevel", ModePlayerValue.ofInt(weapon == null ? 0 : weapon.weaponLevel()));
        return params;
    }

    public record AmmoRefillResult(
            ZombiesWeaponInstanceState weapon,
            double cost,
            boolean starterWeapon
    ) {
        public AmmoRefillResult {
            Objects.requireNonNull(weapon, "weapon");
            cost = Math.max(0.0D, cost);
        }
    }

    @FunctionalInterface
    public interface AmmoRefillCommitGuard {
        ZombiesServiceResult<?> beforeCommit(
                ZombiesWeaponInstanceState currentWeapon,
                ZombiesWeaponInstanceState refilledWeapon);
    }
}
