package com.cdp.codpattern.app.zombies.service.navigation;

import java.util.Locale;

/** Read once by each room service. Promotion requires the recorded release gates. */
public enum NavigationEngine {
    LEGACY, LAYERED;

    public static NavigationEngine configured() {
        String value = System.getProperty("codpattern.zombies.navigationEngine", "legacy");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "legacy" -> LEGACY;
            case "layered" -> LAYERED;
            default -> throw new IllegalArgumentException("navigationEngine must be legacy or layered: " + value);
        };
    }
}
