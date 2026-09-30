package com.cdp.codpattern.app.zombies.map.object;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.stream.Stream;

final class ZombiesObjectCodecs {
    static final Codec<ResourceKey<Level>> DIMENSION_CODEC = Codec.STRING.xmap(
            ZombiesObjectCodecs::dimensionKey,
            key -> key.location().toString());

    static final Codec<Direction> HORIZONTAL_DIRECTION_CODEC = Codec.STRING.comapFlatMap(value -> {
        Direction direction = Direction.byName(value.trim().toLowerCase(Locale.ROOT));
        return direction != null && direction.getAxis().isHorizontal()
                ? DataResult.success(direction)
                : DataResult.error(() -> "Box facing must be north, east, south, or west: " + value);
    }, Direction::getName);

    // DFU's optionalFieldOf also defaults malformed values. Only absence is a legacy default here.
    static final MapCodec<Direction> OPTIONAL_HORIZONTAL_FACING = new MapCodec<>() {
        @Override
        public <T> DataResult<Direction> decode(DynamicOps<T> ops, MapLike<T> input) {
            T value = input.get("facing");
            return value == null ? DataResult.success(Direction.NORTH) : HORIZONTAL_DIRECTION_CODEC.parse(ops, value);
        }

        @Override
        public <T> RecordBuilder<T> encode(Direction input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            return input == Direction.NORTH ? prefix : prefix.add("facing", HORIZONTAL_DIRECTION_CODEC.encodeStart(ops, input));
        }

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.of(ops.createString("facing"));
        }
    };

    private ZombiesObjectCodecs() {
    }

    static Direction horizontalDirection(Direction direction) {
        Direction resolved = direction == null ? Direction.NORTH : direction;
        if (!resolved.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("Box facing must be horizontal: " + resolved);
        }
        return resolved;
    }

    private static ResourceKey<Level> dimensionKey(String value) {
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            location = Level.OVERWORLD.location();
        }
        return ResourceKey.create(Registries.DIMENSION, location);
    }
}
