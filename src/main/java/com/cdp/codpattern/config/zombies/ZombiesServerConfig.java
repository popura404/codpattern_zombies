package com.cdp.codpattern.config.zombies;

import java.util.List;
import java.util.Objects;

/** Immutable-ish map configuration bundle used by a running Zombies map. */
public final class ZombiesServerConfig {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private final String mapName;
    private final ZombiesRoomConfig room;
    private final ZombiesWeaponRulesConfig weaponRules;
    private final ZombiesWeaponWallConfig weaponWall;
    private final ZombiesMysteryBoxConfig mysteryBox;
    private final ZombiesWeaponFilterConfig weaponFilter;
    private final List<com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue> validationIssues;

    public ZombiesServerConfig(String mapName, ZombiesRoomConfig room, ZombiesWeaponRulesConfig weaponRules,
                               ZombiesWeaponWallConfig weaponWall, ZombiesMysteryBoxConfig mysteryBox,
                               ZombiesWeaponFilterConfig weaponFilter,
                               List<com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue> validationIssues) {
        this.mapName = Objects.requireNonNullElse(mapName, "default"); this.room = room == null ? ZombiesRoomConfig.defaults() : room;
        this.weaponRules = weaponRules == null ? ZombiesWeaponRulesConfig.defaults() : weaponRules;
        this.weaponWall = weaponWall == null ? ZombiesWeaponWallConfig.defaults() : weaponWall;
        this.mysteryBox = mysteryBox == null ? ZombiesMysteryBoxConfig.defaults() : mysteryBox;
        this.weaponFilter = weaponFilter == null ? ZombiesWeaponFilterConfig.defaults() : weaponFilter;
        this.validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
    }
    public static ZombiesServerConfig defaults(String mapName) { return new ZombiesServerConfig(mapName, null, null, null, null, null, List.of()); }
    public String getMapName() { return mapName; }
    public ZombiesRoomConfig getRoom() { return room; }
    public ZombiesRoomConfig room() { return room; }
    public ZombiesWeaponRulesConfig getWeaponRules() { return weaponRules; }
    public ZombiesWeaponRulesConfig weaponRules() { return weaponRules; }
    public ZombiesWeaponWallConfig getWeaponWall() { return weaponWall; }
    public ZombiesWeaponWallConfig weaponWall() { return weaponWall; }
    public ZombiesMysteryBoxConfig getMysteryBox() { return mysteryBox; }
    public ZombiesMysteryBoxConfig mysteryBox() { return mysteryBox; }
    public ZombiesWeaponFilterConfig getWeaponFilter() { return weaponFilter; }
    public ZombiesWeaponFilterConfig weaponFilter() { return weaponFilter; }
    public List<com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue> getValidationIssues() { return validationIssues; }
    public List<com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue> validationIssues() { return validationIssues; }

    /** Adapter for services that still consume the pre-v1 aggregate type. */
    public ZombiesRulesConfig legacyRulesConfig() {
        ZombiesRulesConfig value = new ZombiesRulesConfig();
        value.setRoom(room.getRoom()); value.setDefaults(room.getMobDefaults()); value.setArmor(room.getArmor()); value.setSpawnPointWeighting(room.getSpawnPointWeighting());
        value.setStarterWeapon(weaponRules.getStarterWeapon());
        ZombiesRulesConfig.WeaponRules ammo = new ZombiesRulesConfig.WeaponRules();
        ammo.setStarterWeaponAmmunitionPerMagazineMultiple(weaponRules.getAmmunition().getStarterWeaponMagazineMultiplier());
        ammo.setWeaponPoolAmmunitionPerMagazineMultiple(weaponRules.getAmmunition().getWeaponPoolMagazineMultiplier()); value.setWeaponRules(ammo);
        ZombiesRulesConfig.WeaponWall wall = new ZombiesRulesConfig.WeaponWall(); wall.setRefreshIntervalWaves(weaponWall.getRefreshIntervalWaves());
        java.util.List<ZombiesRulesConfig.Rarity> rarities = new java.util.ArrayList<>();
        for (ZombiesWeaponWallConfig.RarityPool pool : weaponWall.getRarityPools()) { ZombiesRulesConfig.Rarity r = new ZombiesRulesConfig.Rarity(); r.setId(pool.getRarityId()); r.setInitialWeight(pool.getInitialWeight()); r.setWeightDeltaPerRefresh(pool.getWeightDeltaPerRefresh()); r.setMinWeight(pool.getMinWeight()); r.setMaxWeight(pool.getMaxWeight()); r.setPrice(pool.getPrice()); r.setGuns(pool.getGuns().stream().map(g -> new ZombiesRulesConfig.GunWeight(g.getGunId(), g.getWeight())).toList()); rarities.add(r); }
        wall.setRarities(rarities); value.setWeaponWall(wall);
        ZombiesRulesConfig.UltimateMachine upgrades = new ZombiesRulesConfig.UltimateMachine(); upgrades.setMaxUpgradeLevel(weaponRules.getUpgrades().getMaxLevel()); java.util.Map<String,ZombiesRulesConfig.UpgradeLevel> levels = new java.util.LinkedHashMap<>(); weaponRules.getUpgrades().getLevels().forEach((k,v)->levels.put(k,new ZombiesRulesConfig.UpgradeLevel(v.getPrice(),v.getDamageMultiplier()))); upgrades.setLevels(levels); value.setUltimateMachine(upgrades);
        value.normalize(); return value;
    }
}
