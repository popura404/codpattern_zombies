package com.cdp.codpattern.app.zombies.map.object;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;

public record ZombiesPowerSwitchData(
        String objectId,
        String block,
        int cost,
        ResourceKey<Level> dimension,
        BlockPos pos,
        Optional<BlockPos> interactionPos,
        Direction facing
) {
    public static final Codec<ZombiesPowerSwitchData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("objectId").forGetter(ZombiesPowerSwitchData::objectId),
            Codec.STRING.optionalFieldOf("block", "codpattern:zombies_power_switch").forGetter(ZombiesPowerSwitchData::block),
            Codec.INT.optionalFieldOf("cost", 0).forGetter(ZombiesPowerSwitchData::cost),
            ZombiesObjectCodecs.DIMENSION_CODEC.fieldOf("dimension").forGetter(ZombiesPowerSwitchData::dimension),
            BlockPos.CODEC.optionalFieldOf("pos", BlockPos.ZERO).forGetter(ZombiesPowerSwitchData::pos),
            BlockPos.CODEC.optionalFieldOf("interactionPos").forGetter(ZombiesPowerSwitchData::interactionPos),
            ZombiesObjectCodecs.OPTIONAL_HORIZONTAL_FACING.forGetter(ZombiesPowerSwitchData::facing)
    ).apply(instance, ZombiesPowerSwitchData::new));

    public ZombiesPowerSwitchData(
            String objectId, String block, int cost, ResourceKey<Level> dimension,
            BlockPos pos, Optional<BlockPos> interactionPos
    ) {
        this(objectId, block, cost, dimension, pos, interactionPos, Direction.NORTH);
    }

    public ZombiesPowerSwitchData {
        objectId = objectId == null ? "" : objectId.trim();
        block = block == null ? "" : block.trim();
        dimension = dimension == null ? Level.OVERWORLD : dimension;
        pos = pos == null ? BlockPos.ZERO : pos;
        interactionPos = interactionPos == null ? Optional.empty() : interactionPos;
        facing = ZombiesObjectCodecs.horizontalDirection(facing);
    }
}
