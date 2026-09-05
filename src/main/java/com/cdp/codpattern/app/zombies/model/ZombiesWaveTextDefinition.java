package com.cdp.codpattern.app.zombies.model;

import java.util.List;

/** Immutable chat sequence for one configured Zombies wave. */
public record ZombiesWaveTextDefinition(int wave, List<ZombiesWaveTextMessage> messages) {
    public ZombiesWaveTextDefinition {
        if (wave < 1) {
            throw new IllegalArgumentException("wave must be positive");
        }
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
