package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.map.object.ZombiesSpawnGroupChanges;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ZombiesActiveSpawnGroupService {
    public static final int INITIAL_SPAWN_GROUP = 0;

    private final Set<Integer> activeGroups = new LinkedHashSet<>();

    public ZombiesActiveSpawnGroupService() {
        resetToInitial();
    }

    public synchronized void resetToInitial() {
        activeGroups.clear();
        activeGroups.add(INITIAL_SPAWN_GROUP);
    }

    public synchronized boolean activate(int group) {
        if (group < 0) {
            return false;
        }
        return activeGroups.add(group);
    }

    public synchronized Set<Integer> apply(ZombiesSpawnGroupChanges changes) {
        if (changes == null || !changes.valid()) {
            throw new IllegalArgumentException("Invalid spawn group changes");
        }
        activeGroups.removeAll(changes.disable());
        activeGroups.addAll(changes.enable());
        return snapshot();
    }

    public synchronized Set<Integer> snapshot() {
        return Set.copyOf(activeGroups);
    }
}
