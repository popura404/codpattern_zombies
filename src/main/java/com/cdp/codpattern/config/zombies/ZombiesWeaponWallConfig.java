package com.cdp.codpattern.config.zombies;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Contents of weapon_wall.json.  It deliberately contains no damage values. */
public final class ZombiesWeaponWallConfig {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private int schemaVersion = SUPPORTED_SCHEMA_VERSION;
    private int refreshIntervalWaves = 5;
    private List<RarityPool> rarityPools = defaultPools();
    public int getSchemaVersion() { return schemaVersion; } public void setSchemaVersion(int value) { schemaVersion = value; }
    public int getRefreshIntervalWaves() { return refreshIntervalWaves; } public void setRefreshIntervalWaves(int value) { refreshIntervalWaves = value; }
    public List<RarityPool> getRarityPools() { return rarityPools == null ? (rarityPools = defaultPools()) : rarityPools; }
    public void setRarityPools(List<RarityPool> value) { rarityPools = value == null ? defaultPools() : new ArrayList<>(value); }
    public void normalize() { schemaVersion = SUPPORTED_SCHEMA_VERSION; if (refreshIntervalWaves < 1) refreshIntervalWaves = 5; List<RarityPool> copy = new ArrayList<>(); for (RarityPool p : getRarityPools()) if (p != null) { p.normalize(); copy.add(p); } rarityPools = copy.isEmpty() ? defaultPools() : copy; }
    public static ZombiesWeaponWallConfig defaults() { ZombiesWeaponWallConfig c = new ZombiesWeaponWallConfig(); c.normalize(); return c; }
    private static List<RarityPool> defaultPools() { return List.of(new RarityPool("common",70,-8,10,100,500,List.of(new GunWeight("tacz:glock_17",100))), new RarityPool("rare",25,5,0,100,900,List.of(new GunWeight("tacz:ak47",100))), new RarityPool("epic",5,3,0,100,1500,List.of(new GunWeight("tacz:m4a1",100))), new RarityPool("legendary",1,1,0,100,0,List.of())); }
    public static final class RarityPool {
        private String rarityId; private double initialWeight; private double weightDeltaPerRefresh; private double minWeight; private double maxWeight = 100; private int price; private List<GunWeight> guns = List.of();
        public RarityPool() { this("common", 1, 0, 0, 100, 500, List.of()); }
        public RarityPool(String id,double initial,double delta,double min,double max,int price,List<GunWeight> guns) { rarityId=id; initialWeight=initial; weightDeltaPerRefresh=delta; minWeight=min; maxWeight=max; this.price=price; this.guns=guns; }
        public String getRarityId(){return rarityId;} public void setRarityId(String v){rarityId=v;} public String getId(){return rarityId;} public void setId(String v){rarityId=v;}
        public double getInitialWeight(){return initialWeight;} public void setInitialWeight(double v){initialWeight=v;} public double getWeightDeltaPerRefresh(){return weightDeltaPerRefresh;} public void setWeightDeltaPerRefresh(double v){weightDeltaPerRefresh=v;} public double getMinWeight(){return minWeight;} public void setMinWeight(double v){minWeight=v;} public double getMaxWeight(){return maxWeight;} public void setMaxWeight(double v){maxWeight=v;} public int getPrice(){return price;} public void setPrice(int v){price=v;} public List<GunWeight> getGuns(){return guns==null?(guns=List.of()):guns;} public void setGuns(List<GunWeight> v){guns=v==null?List.of():new ArrayList<>(v);}
        private void normalize(){rarityId=Objects.requireNonNullElse(rarityId,"").trim().toLowerCase(Locale.ROOT); if(!Double.isFinite(initialWeight))initialWeight=0;if(!Double.isFinite(weightDeltaPerRefresh))weightDeltaPerRefresh=0;if(!Double.isFinite(minWeight)||minWeight<0)minWeight=0;if(!Double.isFinite(maxWeight)||maxWeight<minWeight)maxWeight=Math.max(100,minWeight);if(price<0)price=0;}
    }
    public static final class GunWeight { private String gunId; private double weight=1; public GunWeight(){} public GunWeight(String id,double w){gunId=id;weight=w;} public String getGunId(){return gunId;} public void setGunId(String v){gunId=v;} public double getWeight(){return weight;} public void setWeight(double v){weight=v;} }
}
