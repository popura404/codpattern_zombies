package com.cdp.codpattern.app.zombies.map.object;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Explicit changes to spawn groups; empty sets leave the current state unchanged. */
public record ZombiesSpawnGroupChanges(Set<Integer> enable, Set<Integer> disable) {
    public static final ZombiesSpawnGroupChanges NONE = new ZombiesSpawnGroupChanges(Set.of(), Set.of());
    private static final Codec<Set<Integer>> GROUPS = Codec.INT.listOf().xmap(
            values -> Collections.unmodifiableSet(new TreeSet<>(values)), values -> values.stream().sorted().toList());
    public static final Codec<ZombiesSpawnGroupChanges> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GROUPS.optionalFieldOf("enable", Set.of()).forGetter(ZombiesSpawnGroupChanges::enable),
            GROUPS.optionalFieldOf("disable", Set.of()).forGetter(ZombiesSpawnGroupChanges::disable)
    ).apply(instance, ZombiesSpawnGroupChanges::new));

    public ZombiesSpawnGroupChanges {
        enable = Collections.unmodifiableSet(new TreeSet<>(enable == null ? Set.of() : enable));
        disable = Collections.unmodifiableSet(new TreeSet<>(disable == null ? Set.of() : disable));
    }

    public Set<Integer> referencedGroups() {
        Set<Integer> groups = new TreeSet<>(enable);
        groups.addAll(disable);
        return Collections.unmodifiableSet(groups);
    }

    public Set<Integer> conflicts() {
        Set<Integer> groups = new TreeSet<>(enable);
        groups.retainAll(disable);
        return Collections.unmodifiableSet(groups);
    }

    public boolean valid() {
        return referencedGroups().stream().allMatch(group -> group >= 0) && conflicts().isEmpty();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("enable", enable.stream().mapToInt(Integer::intValue).toArray());
        tag.putIntArray("disable", disable.stream().mapToInt(Integer::intValue).toArray());
        return tag;
    }

    public static ZombiesSpawnGroupChanges fromTag(CompoundTag tag) {
        return new ZombiesSpawnGroupChanges(
                Arrays.stream(tag.getIntArray("enable")).boxed().collect(Collectors.toSet()),
                Arrays.stream(tag.getIntArray("disable")).boxed().collect(Collectors.toSet()));
    }
}
