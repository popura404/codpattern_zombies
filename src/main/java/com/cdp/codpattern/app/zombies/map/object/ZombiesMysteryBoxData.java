package com.cdp.codpattern.app.zombies.map.object;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

public record ZombiesMysteryBoxData(
        String objectId,
        int cost,
        List<String> weaponPool,
        ResourceKey<Level> dimension,
        BlockPos pos,
        Optional<BlockPos> interactionPos,
        Direction facing
) {
    public static final Codec<ZombiesMysteryBoxData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("objectId").forGetter(ZombiesMysteryBoxData::objectId),
            Codec.INT.optionalFieldOf("cost", 0).forGetter(ZombiesMysteryBoxData::cost),
            Codec.STRING.listOf().optionalFieldOf("weaponPool", List.of()).forGetter(ZombiesMysteryBoxData::weaponPool),
            ZombiesObjectCodecs.DIMENSION_CODEC.fieldOf("dimension").forGetter(ZombiesMysteryBoxData::dimension),
            BlockPos.CODEC.optionalFieldOf("pos", BlockPos.ZERO).forGetter(ZombiesMysteryBoxData::pos),
            BlockPos.CODEC.optionalFieldOf("interactionPos").forGetter(ZombiesMysteryBoxData::interactionPos),
            ZombiesObjectCodecs.OPTIONAL_HORIZONTAL_FACING.forGetter(ZombiesMysteryBoxData::facing)
    ).apply(instance, ZombiesMysteryBoxData::new));

    public ZombiesMysteryBoxData(
            String objectId,
            int cost,
            List<String> weaponPool,
            ResourceKey<Level> dimension,
            BlockPos pos,
            Optional<BlockPos> interactionPos
    ) {
        this(objectId, cost, weaponPool, dimension, pos, interactionPos, Direction.NORTH);
    }

    public ZombiesMysteryBoxData {
        objectId = objectId == null ? "" : objectId.trim();
        weaponPool = weaponPool == null ? List.of() : List.copyOf(weaponPool);
        dimension = dimension == null ? Level.OVERWORLD : dimension;
        pos = pos == null ? BlockPos.ZERO : pos;
        interactionPos = interactionPos == null ? Optional.empty() : interactionPos;
        facing = ZombiesObjectCodecs.horizontalDirection(facing);
    }
}
