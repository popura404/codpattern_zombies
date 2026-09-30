package com.cdp.codpattern.app.zombies.deploy;

import java.util.Optional;
import java.util.UUID;

/** Shared point identities; all preview data stays under the existing held-tool lifecycle. */
public final class ZombiesDeployPreviewKeys {
    private ZombiesDeployPreviewKeys() {
    }

    public static String prefix(UUID playerId) {
        return "held_tool_preview:zombies_deploy:" + playerId + ":";
    }

    public static String objectKey(UUID playerId, String type, int index) {
        return prefix(playerId) + "object:" + type + ":" + index;
    }

    public static String draftKey(UUID playerId, String type) {
        return prefix(playerId) + "draft:" + type;
    }

    /** Only actual object anchors have models; capture slots and nearest-point hints do not. */
    public static Optional<String> modelObjectType(String key, UUID playerId) {
        if (key == null || playerId == null || !key.startsWith(prefix(playerId))) {
            return Optional.empty();
        }
        String[] parts = key.substring(prefix(playerId).length()).split(":", -1);
        if (parts.length == 2 && "draft".equals(parts[0]) && !parts[1].isBlank()) {
            return Optional.of(parts[1]);
        }
        if (parts.length == 3 && "object".equals(parts[0]) && !parts[1].isBlank()) {
            try {
                if (Integer.parseInt(parts[2]) >= 0) {
                    return Optional.of(parts[1]);
                }
            } catch (NumberFormatException ignored) {
                // Other debug point identities are not model anchors.
            }
        }
        return Optional.empty();
    }
}
