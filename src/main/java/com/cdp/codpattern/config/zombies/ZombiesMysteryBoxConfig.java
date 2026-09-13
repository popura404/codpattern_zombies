package com.cdp.codpattern.config.zombies;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Map-scoped rules for the Zombies mystery box. */
public final class ZombiesMysteryBoxConfig {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private int schemaVersion = SUPPORTED_SCHEMA_VERSION;
    private Integer cost = 950;
    private Integer refreshIntervalWaves = 5;
    @SerializedName(value = "rarityPools", alternate = {"rarities"})
    private List<Rarity> rarities = defaultRarities();

    public Integer getCost() { return cost; }
    public void setCost(Integer cost) { this.cost = cost; }
    public Integer getRefreshIntervalWaves() { return refreshIntervalWaves; }
    public void setRefreshIntervalWaves(Integer value) { this.refreshIntervalWaves = value; }
    public List<Rarity> getRarities() {
        if (rarities == null) rarities = defaultRarities();
        return rarities;
    }
    public void setRarities(List<Rarity> value) {
        rarities = value == null ? defaultRarities() : new ArrayList<>(value);
    }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    /** v1 name; rarities remains as a source-compatible alias. */
    public List<Rarity> getRarityPools() { return getRarities(); }
    public void setRarityPools(List<Rarity> value) { setRarities(value); }

    public void normalize() {
        schemaVersion = SUPPORTED_SCHEMA_VERSION;
        cost = nonNegative(cost, 950);
        refreshIntervalWaves = positive(refreshIntervalWaves, 5);
        List<Rarity> normalized = new ArrayList<>();
        for (Rarity rarity : getRarities()) {
            Rarity value = rarity == null ? new Rarity() : rarity;
            value.normalize();
            if (!value.getId().isBlank()) normalized.add(value);
        }
        rarities = normalized.isEmpty() ? defaultRarities() : normalized;
    }

    public static ZombiesMysteryBoxConfig defaults() {
        ZombiesMysteryBoxConfig config = new ZombiesMysteryBoxConfig();
        config.normalize();
        return config;
    }

    private static List<Rarity> defaultRarities() {
        return List.of(
                new Rarity("common", 70.0, -8.0, 10.0, 100.0, 1.0,
                        List.of(new GunWeight("tacz:glock_17", 100.0))),
                new Rarity("rare", 25.0, 5.0, 0.0, 100.0, 1.25,
                        List.of(new GunWeight("tacz:ak47", 100.0))),
                new Rarity("epic", 5.0, 3.0, 0.0, 100.0, 1.6,
                        List.of(new GunWeight("tacz:m4a1", 100.0))),
                new Rarity("legendary", 1.0, 1.0, 0.0, 100.0, 2.0, List.of()));
    }

    private static int nonNegative(Integer value, int fallback) { return value == null || value < 0 ? fallback : value; }
    private static int positive(Integer value, int fallback) { return value == null || value < 1 ? fallback : value; }
    private static double finite(Double value, double fallback) { return value == null || !Double.isFinite(value) ? fallback : value; }

    public static final class Rarity {
        private String id = "common";
        private Double initialWeight = 1.0;
        private Double weightDeltaPerRefresh = 0.0;
        private Double minWeight = 0.0;
        private Double maxWeight = 100.0;
        /** Deprecated in v1; damage is resolved from weapon_rules.json. */
        private transient Double damageMultiplier = 1.0;
        private List<GunWeight> guns = List.of(new GunWeight("tacz:glock_17", 1.0));

        public Rarity() { }
        public Rarity(String id, Double initialWeight, Double delta, Double min, Double max,
                      Double damageMultiplier, List<GunWeight> guns) {
            this.id = id; this.initialWeight = initialWeight; this.weightDeltaPerRefresh = delta;
            this.minWeight = min; this.maxWeight = max; this.damageMultiplier = damageMultiplier; this.guns = guns;
        }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public Double getInitialWeight() { return initialWeight; }
        public void setInitialWeight(Double value) { this.initialWeight = value; }
        public Double getWeightDeltaPerRefresh() { return weightDeltaPerRefresh; }
        public void setWeightDeltaPerRefresh(Double value) { this.weightDeltaPerRefresh = value; }
        public Double getMinWeight() { return minWeight; }
        public void setMinWeight(Double value) { this.minWeight = value; }
        public Double getMaxWeight() { return maxWeight; }
        public void setMaxWeight(Double value) { this.maxWeight = value; }
        public Double getDamageMultiplier() { return damageMultiplier; }
        public void setDamageMultiplier(Double value) { this.damageMultiplier = value; }
        public List<GunWeight> getGuns() { if (guns == null) guns = List.of(); return guns; }
        public void setGuns(List<GunWeight> value) { guns = value == null ? List.of() : new ArrayList<>(value); }
        private void normalize() {
            id = Objects.requireNonNullElse(id, "").trim().toLowerCase(Locale.ROOT);
            initialWeight = finite(initialWeight, 0.0);
            weightDeltaPerRefresh = finite(weightDeltaPerRefresh, 0.0);
            minWeight = Math.max(0.0, finite(minWeight, 0.0));
            maxWeight = Math.max(0.0, finite(maxWeight, minWeight));
            if (maxWeight < minWeight) { double t = minWeight; minWeight = maxWeight; maxWeight = t; }
            damageMultiplier = finite(damageMultiplier, 1.0);
            if (damageMultiplier <= 0.0) damageMultiplier = 1.0;
            List<GunWeight> normalized = new ArrayList<>();
            for (GunWeight gun : getGuns()) { GunWeight value = gun == null ? new GunWeight() : gun; value.normalize(); normalized.add(value); }
            guns = normalized;
        }
    }

    public static final class GunWeight {
        private String gunId = "";
        private Double weight = 1.0;
        public GunWeight() { }
        public GunWeight(String gunId, Double weight) { this.gunId = gunId; this.weight = weight; }
        public String getGunId() { return gunId; }
        public void setGunId(String value) { this.gunId = value; }
        public Double getWeight() { return weight; }
        public void setWeight(Double value) { this.weight = value; }
        private void normalize() { gunId = Objects.requireNonNullElse(gunId, "").trim(); weight = Math.max(0.0, finite(weight, 0.0)); }
    }
}
