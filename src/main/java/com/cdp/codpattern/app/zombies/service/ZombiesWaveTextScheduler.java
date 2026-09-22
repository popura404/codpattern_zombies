package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.model.ZombiesWaveTextDefinition;
import com.cdp.codpattern.app.zombies.model.ZombiesWaveTextMessage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Per-room relative-tick scheduler for chat sequences spanning intermission and the active wave. */
public final class ZombiesWaveTextScheduler {
    private Map<Integer, ZombiesWaveTextDefinition> definitionsByWave = Map.of();
    private List<ZombiesWaveTextMessage> activeMessages = List.of();
    private int nextMessageIndex;
    private long elapsedTicks;
    private long nextMessageTick;
    private boolean active;

    public ZombiesWaveTextScheduler(Collection<ZombiesWaveTextDefinition> definitions) {
        replaceDefinitions(definitions);
    }

    public void replaceDefinitions(Collection<ZombiesWaveTextDefinition> definitions) {
        cancel();
        Map<Integer, ZombiesWaveTextDefinition> indexed = new LinkedHashMap<>();
        if (definitions != null) {
            definitions.stream()
                    .filter(Objects::nonNull)
                    .forEach(definition -> indexed.putIfAbsent(definition.wave(), definition));
        }
        definitionsByWave = Map.copyOf(indexed);
    }

    /** Starts a sequence and immediately drains every line due at relative tick zero. */
    public void startWave(int wave, Consumer<String> sender) {
        cancel();
        ZombiesWaveTextDefinition definition = definitionsByWave.get(wave);
        if (definition == null || definition.messages().isEmpty()) {
            return;
        }
        activeMessages = definition.messages();
        active = true;
        nextMessageTick = activeMessages.get(0).delayTicks();
        drainDue(Objects.requireNonNull(sender, "sender"));
    }

    /** Advances exactly one relative wave-cycle tick and sends all lines due on it. */
    public void tick(Consumer<String> sender) {
        if (!active) {
            return;
        }
        elapsedTicks++;
        drainDue(Objects.requireNonNull(sender, "sender"));
    }

    public void cancel() {
        activeMessages = List.of();
        nextMessageIndex = 0;
        elapsedTicks = 0L;
        nextMessageTick = 0L;
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    private void drainDue(Consumer<String> sender) {
        while (active && nextMessageIndex < activeMessages.size() && nextMessageTick <= elapsedTicks) {
            ZombiesWaveTextMessage message = activeMessages.get(nextMessageIndex);
            sender.accept(message.text());
            nextMessageIndex++;
            if (nextMessageIndex >= activeMessages.size()) {
                cancel();
                return;
            }
            nextMessageTick = saturatedAdd(nextMessageTick, activeMessages.get(nextMessageIndex).delayTicks());
        }
    }

    private static long saturatedAdd(long left, int right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
