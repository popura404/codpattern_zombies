package com.cdp.codpattern.app.zombies.map.object;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record ZombiesBarrierData(
        String objectId,
        String name,
        int group,
        int cost,
        boolean blocksPlayersOnly,
        ResourceKey<Level> dimension,
        BlockPos areaFrom,
        BlockPos areaTo,
        BlockPos interactionPos,
        String requiredItem,
        ZombiesSpawnGroupChanges spawnGroupChanges
) {
    public ZombiesBarrierData(
            String objectId,
            String name,
            int group,
            int cost,
            boolean blocksPlayersOnly,
            ResourceKey<Level> dimension,
            BlockPos areaFrom,
            BlockPos areaTo,
            BlockPos interactionPos
    ) {
        this(objectId, name, group, cost, blocksPlayersOnly, dimension, areaFrom, areaTo, interactionPos, "");
    }

    public ZombiesBarrierData(
            String objectId,
            int group,
            int cost,
            boolean blocksPlayersOnly,
            ResourceKey<Level> dimension,
            BlockPos areaFrom,
            BlockPos areaTo,
            BlockPos interactionPos
    ) {
        this(objectId, "", group, cost, blocksPlayersOnly, dimension, areaFrom, areaTo, interactionPos, "");
    }

    public ZombiesBarrierData(String objectId, String name, int group, int cost, boolean blocksPlayersOnly,
            ResourceKey<Level> dimension, BlockPos areaFrom, BlockPos areaTo, BlockPos interactionPos, String requiredItem) {
        this(objectId, name, group, cost, blocksPlayersOnly, dimension, areaFrom, areaTo, interactionPos,
                requiredItem, ZombiesSpawnGroupChanges.NONE);
    }

    public ZombiesBarrierData withSpawnGroupChanges(ZombiesSpawnGroupChanges changes) {
        return new ZombiesBarrierData(objectId, name, group, cost, blocksPlayersOnly, dimension,
                areaFrom, areaTo, interactionPos, requiredItem, changes);
    }

    public ZombiesBarrierData {
        spawnGroupChanges = spawnGroupChanges == null ? ZombiesSpawnGroupChanges.NONE : spawnGroupChanges;
        objectId = objectId == null ? "" : objectId.trim();
        name = name == null ? "" : name.trim();
        requiredItem = requiredItem == null ? "" : requiredItem.trim();
    }

    public String displayName() {
        return name.isBlank() ? objectId : name;
    }

    public static final Codec<ZombiesBarrierData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("objectId").forGetter(ZombiesBarrierData::objectId),
            Codec.STRING.optionalFieldOf("name", "").forGetter(ZombiesBarrierData::name),
            Codec.INT.optionalFieldOf("group", 1).forGetter(ZombiesBarrierData::group),
            Codec.INT.optionalFieldOf("cost", 0).forGetter(ZombiesBarrierData::cost),
            Codec.BOOL.optionalFieldOf("blocksPlayersOnly", true).forGetter(ZombiesBarrierData::blocksPlayersOnly),
            ZombiesObjectCodecs.DIMENSION_CODEC.fieldOf("dimension").forGetter(ZombiesBarrierData::dimension),
            BlockPos.CODEC.optionalFieldOf("areaFrom", BlockPos.ZERO).forGetter(ZombiesBarrierData::areaFrom),
            BlockPos.CODEC.optionalFieldOf("areaTo", BlockPos.ZERO).forGetter(ZombiesBarrierData::areaTo),
            BlockPos.CODEC.optionalFieldOf("interactionPos", BlockPos.ZERO).forGetter(ZombiesBarrierData::interactionPos),
            Codec.STRING.optionalFieldOf("requiredItem", "").forGetter(ZombiesBarrierData::requiredItem),
            ZombiesSpawnGroupChanges.CODEC.optionalFieldOf("spawnGroupChanges", ZombiesSpawnGroupChanges.NONE).forGetter(ZombiesBarrierData::spawnGroupChanges)
    ).apply(instance, ZombiesBarrierData::new));
}
