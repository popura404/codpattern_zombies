package com.cdp.codpattern.config.zombies;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Contents of weapon_rules.json.  Rarity damage is defined only here. */
public final class ZombiesWeaponRulesConfig {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private int schemaVersion = SUPPORTED_SCHEMA_VERSION;
    private ZombiesRulesConfig.StarterWeapon starterWeapon = ZombiesRulesConfig.StarterWeapon.defaults();
    private Ammunition ammunition = new Ammunition();
    private List<Rarity> rarities = defaultRarities();
    private Upgrades upgrades = new Upgrades();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public ZombiesRulesConfig.StarterWeapon getStarterWeapon() { return starterWeapon == null ? (starterWeapon = ZombiesRulesConfig.StarterWeapon.defaults()) : starterWeapon; }
    public void setStarterWeapon(ZombiesRulesConfig.StarterWeapon value) { starterWeapon = value == null ? ZombiesRulesConfig.StarterWeapon.defaults() : value; }
    public Ammunition getAmmunition() { return ammunition == null ? (ammunition = new Ammunition()) : ammunition; }
    public void setAmmunition(Ammunition value) { ammunition = value == null ? new Ammunition() : value; }
    public List<Rarity> getRarities() { return rarities == null ? (rarities = defaultRarities()) : rarities; }
    public void setRarities(List<Rarity> value) { rarities = value == null ? defaultRarities() : new ArrayList<>(value); }
    public Upgrades getUpgrades() { return upgrades == null ? (upgrades = new Upgrades()) : upgrades; }
    public void setUpgrades(Upgrades value) { upgrades = value == null ? new Upgrades() : value; }
    public java.util.OptionalDouble damageMultiplier(String id) { String key = normalize(id); return getRarities().stream().filter(Objects::nonNull).filter(r -> key.equals(normalize(r.getId()))).mapToDouble(Rarity::getDamageMultiplier).findFirst(); }
    public double rarityDamageMultiplier(String id) { return damageMultiplier(id).orElse(1.0); }

    public void normalize() {
        schemaVersion = SUPPORTED_SCHEMA_VERSION; getStarterWeapon().normalizeCompat(); getAmmunition().normalize(); getUpgrades().normalize();
        List<Rarity> values = new ArrayList<>();
        for (Rarity rarity : getRarities()) { if (rarity == null) continue; rarity.normalize(); if (!rarity.getId().isBlank()) values.add(rarity); }
        rarities = values.isEmpty() ? defaultRarities() : values;
    }
    public static ZombiesWeaponRulesConfig defaults() { ZombiesWeaponRulesConfig c = new ZombiesWeaponRulesConfig(); c.normalize(); return c; }
    private static List<Rarity> defaultRarities() { return List.of(new Rarity("common", 1.0), new Rarity("rare", 1.25), new Rarity("epic", 1.6), new Rarity("legendary", 2.0)); }
    private static String normalize(String value) { return Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT); }

    public static final class Ammunition {
        @SerializedName(value = "starterWeaponAmmunitionPerMagazineMultiple", alternate = {"starterWeaponMagazineMultiplier"})
        private int starterWeaponMagazineMultiplier = ZombiesRulesConfig.WeaponRules.DEFAULT_AMMUNITION_PER_MAGAZINE_MULTIPLE;
        @SerializedName(value = "weaponPoolAmmunitionPerMagazineMultiple", alternate = {"weaponPoolMagazineMultiplier"})
        private int weaponPoolMagazineMultiplier = ZombiesRulesConfig.WeaponRules.DEFAULT_AMMUNITION_PER_MAGAZINE_MULTIPLE;
        public int getStarterWeaponMagazineMultiplier() { return starterWeaponMagazineMultiplier; }
        public void setStarterWeaponMagazineMultiplier(int value) { starterWeaponMagazineMultiplier = value; }
        public int getWeaponPoolMagazineMultiplier() { return weaponPoolMagazineMultiplier; }
        public void setWeaponPoolMagazineMultiplier(int value) { weaponPoolMagazineMultiplier = value; }
        public int getStarterWeaponAmmunitionPerMagazineMultiple() { return starterWeaponMagazineMultiplier; }
        public int getWeaponPoolAmmunitionPerMagazineMultiple() { return weaponPoolMagazineMultiplier; }
        private void normalize() { if (starterWeaponMagazineMultiplier < 0) starterWeaponMagazineMultiplier = 10; if (weaponPoolMagazineMultiplier < 0) weaponPoolMagazineMultiplier = 10; }
    }
    public static final class Rarity {
        private String id; private double damageMultiplier = 1.0;
        public Rarity() { this("common", 1.0); }
        public Rarity(String id, double damageMultiplier) { this.id = id; this.damageMultiplier = damageMultiplier; }
        public String getId() { return id; } public void setId(String value) { id = value; }
        public double getDamageMultiplier() { return damageMultiplier; } public void setDamageMultiplier(double value) { damageMultiplier = value; }
        private void normalize() { id = ZombiesWeaponRulesConfig.normalize(id); if (!Double.isFinite(damageMultiplier) || damageMultiplier <= 0) damageMultiplier = 1.0; }
    }
    public static final class Upgrades {
        @SerializedName(value = "maxUpgradeLevel", alternate = {"maxLevel"})
        private int maxLevel = 2; private Map<String, Level> levels = defaultLevels();
        public int getMaxLevel() { return maxLevel; } public void setMaxLevel(int value) { maxLevel = value; }
        public int getMaxUpgradeLevel() { return maxLevel; } public void setMaxUpgradeLevel(int value) { maxLevel = value; }
        public Map<String, Level> getLevels() { return levels == null ? (levels = defaultLevels()) : levels; }
        public void setLevels(Map<String, Level> value) { levels = value == null ? defaultLevels() : new LinkedHashMap<>(value); }
        private void normalize() { if (maxLevel < 0) maxLevel = 2; Map<String, Level> copy = new LinkedHashMap<>(); for (Map.Entry<String, Level> e : getLevels().entrySet()) if (e.getKey() != null && e.getValue() != null) { e.getValue().normalize(); copy.put(e.getKey(), e.getValue()); } levels = copy.isEmpty() ? defaultLevels() : copy; }
        private static Map<String, Level> defaultLevels() { Map<String, Level> m = new LinkedHashMap<>(); m.put("1", new Level(2500, 2.0)); m.put("2", new Level(5000, 3.0)); return m; }
    }
    public static final class Level {
        private int price; private double damageMultiplier;
        public Level() { this(2500, 2.0); } public Level(int price, double damageMultiplier) { this.price = price; this.damageMultiplier = damageMultiplier; }
        public int getPrice() { return price; } public void setPrice(int value) { price = value; }
        public int getCost() { return price; } public void setCost(int value) { price = value; }
        public double getDamageMultiplier() { return damageMultiplier; } public void setDamageMultiplier(double value) { damageMultiplier = value; }
        private void normalize() { if (price < 0) price = 0; if (!Double.isFinite(damageMultiplier) || damageMultiplier <= 0) damageMultiplier = 1.0; }
    }
}
