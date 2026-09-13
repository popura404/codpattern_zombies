package com.cdp.codpattern.config.zombies;

import java.util.Objects;

/** Contents of the map-scoped room.json file. */
public final class ZombiesRoomConfig {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private int schemaVersion = SUPPORTED_SCHEMA_VERSION;
    private ZombiesRulesConfig.Room room = new ZombiesRulesConfig.Room();
    private ZombiesRulesConfig.Defaults mobDefaults = new ZombiesRulesConfig.Defaults();
    private ZombiesRulesConfig.Armor armor = new ZombiesRulesConfig.Armor();
    private ZombiesRulesConfig.SpawnPointWeighting spawnPointWeighting = new ZombiesRulesConfig.SpawnPointWeighting();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public ZombiesRulesConfig.Room getRoom() { return room == null ? (room = new ZombiesRulesConfig.Room()) : room; }
    public void setRoom(ZombiesRulesConfig.Room value) { room = value == null ? new ZombiesRulesConfig.Room() : value; }
    public ZombiesRulesConfig.Defaults getMobDefaults() { return mobDefaults == null ? (mobDefaults = new ZombiesRulesConfig.Defaults()) : mobDefaults; }
    public void setMobDefaults(ZombiesRulesConfig.Defaults value) { mobDefaults = value == null ? new ZombiesRulesConfig.Defaults() : value; }
    public ZombiesRulesConfig.Armor getArmor() { return armor == null ? (armor = new ZombiesRulesConfig.Armor()) : armor; }
    public void setArmor(ZombiesRulesConfig.Armor value) { armor = value == null ? new ZombiesRulesConfig.Armor() : value; }
    public ZombiesRulesConfig.SpawnPointWeighting getSpawnPointWeighting() { return spawnPointWeighting == null ? (spawnPointWeighting = new ZombiesRulesConfig.SpawnPointWeighting()) : spawnPointWeighting; }
    public void setSpawnPointWeighting(ZombiesRulesConfig.SpawnPointWeighting value) { spawnPointWeighting = value == null ? new ZombiesRulesConfig.SpawnPointWeighting() : value; }

    public void normalize() {
        schemaVersion = SUPPORTED_SCHEMA_VERSION;
        getRoom(); getMobDefaults(); getArmor(); getSpawnPointWeighting();
        ZombiesRulesConfig rules = new ZombiesRulesConfig();
        rules.setRoom(room); rules.setDefaults(mobDefaults); rules.setArmor(armor); rules.setSpawnPointWeighting(spawnPointWeighting); rules.normalize();
        room = rules.getRoom(); mobDefaults = rules.getDefaults(); armor = rules.getArmor(); spawnPointWeighting = rules.getSpawnPointWeighting();
    }
    public static ZombiesRoomConfig defaults() { ZombiesRoomConfig value = new ZombiesRoomConfig(); value.normalize(); return value; }
}
