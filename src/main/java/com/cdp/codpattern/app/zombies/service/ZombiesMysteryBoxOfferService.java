package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;
import com.cdp.codpattern.config.zombies.ZombiesMysteryBoxConfig;
import com.cdp.codpattern.config.zombies.ZombiesMysteryBoxRepository;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.function.Supplier;

/** Weighted, wave-aware reward selection for mystery boxes. */
public final class ZombiesMysteryBoxOfferService {
    private final Supplier<ZombiesMysteryBoxConfig> configSupplier;
    private final RandomGenerator random;

    public ZombiesMysteryBoxOfferService() { this(ZombiesMysteryBoxRepository::getConfig, RandomGenerator.getDefault()); }
    public ZombiesMysteryBoxOfferService(Supplier<ZombiesMysteryBoxConfig> configSupplier, RandomGenerator random) {
        this.configSupplier = configSupplier == null ? ZombiesMysteryBoxRepository::getConfig : configSupplier;
        this.random = random == null ? RandomGenerator.getDefault() : random;
    }

    public Offer createOffer(int currentWave) {
        ZombiesMysteryBoxConfig config = configSupplier.get();
        if (config == null) config = ZombiesMysteryBoxConfig.defaults();
        config.normalize();
        int refreshes = Math.max(0, (Math.max(1, currentWave) - 1) / Math.max(1, config.getRefreshIntervalWaves()));
        WeightedRarity rarity = pickRarity(config.getRarities(), refreshes);
        if (rarity == null) return Offer.empty(config.getCost());
        ZombiesMysteryBoxConfig.GunWeight gun = pickGun(rarity.value().getGuns());
        if (gun == null || !ZombiesWeaponInstanceState.isValidGunId(gun.getGunId())) return Offer.empty(config.getCost());
        return new Offer(gun.getGunId(), rarity.value().getId(), rarity.value().getDamageMultiplier(), config.getCost());
    }

    private WeightedRarity pickRarity(List<ZombiesMysteryBoxConfig.Rarity> values, int refreshes) {
        double total = 0.0;
        List<WeightedRarity> candidates = values == null ? List.of() : values.stream().filter(Objects::nonNull)
                .filter(value -> value.getGuns() != null && value.getGuns().stream().anyMatch(gun ->
                        gun != null && ZombiesWeaponInstanceState.isValidGunId(gun.getGunId())
                                && finite(gun.getWeight()) > 0.0))
                .map(value -> {
            double weight = clamp(finite(value.getInitialWeight()) + refreshes * finite(value.getWeightDeltaPerRefresh()), finite(value.getMinWeight()), finite(value.getMaxWeight()));
            return new WeightedRarity(value, weight);
        }).filter(value -> value.weight() > 0.0).toList();
        for (WeightedRarity value : candidates) total += value.weight();
        if (total <= 0.0 || !Double.isFinite(total)) return null;
        double cursor = random.nextDouble(total);
        for (WeightedRarity value : candidates) { cursor -= value.weight(); if (cursor < 0.0) return value; }
        return candidates.get(candidates.size() - 1);
    }

    private ZombiesMysteryBoxConfig.GunWeight pickGun(List<ZombiesMysteryBoxConfig.GunWeight> values) {
        if (values == null) return null;
        double total = values.stream().filter(Objects::nonNull).filter(value -> ZombiesWeaponInstanceState.isValidGunId(value.getGunId())).mapToDouble(value -> Math.max(0.0, finite(value.getWeight()))).sum();
        if (total <= 0.0 || !Double.isFinite(total)) return null;
        double cursor = random.nextDouble(total);
        ZombiesMysteryBoxConfig.GunWeight lastValid = null;
        for (ZombiesMysteryBoxConfig.GunWeight value : values) {
            if (value == null || !ZombiesWeaponInstanceState.isValidGunId(value.getGunId())) continue;
            lastValid = value;
            cursor -= Math.max(0.0, finite(value.getWeight()));
            if (cursor < 0.0) return value;
        }
        return lastValid;
    }

    private static double finite(Double value) { return value == null || !Double.isFinite(value) ? 0.0 : value; }
    private static double clamp(double value, double min, double max) {
        double low = Math.min(min, max);
        double high = Math.max(min, max);
        return Math.max(low, Math.min(high, value));
    }

    private record WeightedRarity(ZombiesMysteryBoxConfig.Rarity value, double weight) { }
    public record Offer(String gunId, String rarityId, double damageMultiplier, int cost) {
        public Offer { gunId = Objects.requireNonNullElse(gunId, "").trim(); rarityId = Objects.requireNonNullElse(rarityId, "").trim(); cost = Math.max(0, cost); }
        public static Offer empty(int cost) { return new Offer("", "", 0.0, cost); }
        public boolean valid() { return ZombiesWeaponInstanceState.isValidGunId(gunId) && !rarityId.isBlank() && damageMultiplier > 0.0 && Double.isFinite(damageMultiplier); }
    }
}
