package com.cdp.codpattern.app.zombies.model;

import java.util.Objects;

/** One chat line and its delay relative to the preceding line. */
public record ZombiesWaveTextMessage(int delayTicks, String text) {
    public ZombiesWaveTextMessage {
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be non-negative");
        }
        text = Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
