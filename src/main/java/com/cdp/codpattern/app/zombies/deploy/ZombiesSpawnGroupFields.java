package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.zombies.map.object.ZombiesSpawnGroupChanges;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Shared client/server parsing for the two optional group lists. */
public final class ZombiesSpawnGroupFields {
    public static final String ENABLE = "enableSpawnGroups";
    public static final String DISABLE = "disableSpawnGroups";
    private static final String SEPARATORS = "[,，;；\\r\\n]";
    private ZombiesSpawnGroupFields() { }

    public static boolean isGroupList(String key) { return ENABLE.equals(key) || DISABLE.equals(key); }

    public static List<String> rows(String value) {
        if (value == null || value.isBlank()) { return List.of(); }
        return Arrays.asList(value.split(SEPARATORS, -1));
    }

    public static Set<Integer> parse(String key, String value) {
        Set<Integer> groups = new TreeSet<>();
        for (String row : rows(value)) {
            String entry = row.trim();
            if (entry.isEmpty()) { continue; }
            try {
                if (!entry.matches("[0-9]+")) { throw new NumberFormatException(); }
                groups.add(Integer.parseInt(entry));
            } catch (NumberFormatException exception) {
                throw new InvalidGroups(key, entry, false);
            }
        }
        return groups;
    }

    public static ZombiesSpawnGroupChanges parse(Map<String, String> fields) {
        var changes = new ZombiesSpawnGroupChanges(parse(ENABLE, fields.get(ENABLE)), parse(DISABLE, fields.get(DISABLE)));
        if (!changes.conflicts().isEmpty()) {
            throw new InvalidGroups(ENABLE, format(changes.conflicts()), true);
        }
        return changes;
    }

    public static String format(Set<Integer> groups) {
        return groups.stream().sorted().map(String::valueOf).collect(Collectors.joining(", "));
    }

    public static final class InvalidGroups extends IllegalArgumentException {
        private final String field;
        private final String entry;
        private final boolean conflict;
        public InvalidGroups(String field, String entry, boolean conflict) {
            super(conflict ? "Spawn groups cannot be both enabled and disabled: " + entry
                    : "Invalid non-negative integer in " + field + ": " + entry);
            this.field = field; this.entry = entry; this.conflict = conflict;
        }
        public String field() { return field; }
        public String entry() { return entry; }
        public boolean conflict() { return conflict; }
    }
}
