package com.cdp.codpattern.app.zombies.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-authoritative payment, roll, claim and cooldown state for mystery boxes. */
public final class ZombiesMysteryBoxRuntimeService {
    public static final long ROLL_TICKS = 100L;
    public static final long CLAIM_TICKS = 200L;
    public static final long COOLDOWN_TICKS = 20L;
    private final Map<String, RuntimeState> states = new LinkedHashMap<>();
    private long revision;

    public synchronized ZombiesServiceResult<RuntimeState> begin(String objectId, UUID owner,
            ZombiesMysteryBoxOfferService.Offer offer,
            ZombiesWeaponInventoryService.PreparedWeaponStack prepared,
            List<String> previewGunIds, long startTick, ChargeAction charge) {
        String key = normalize(objectId);
        if (key.isBlank() || owner == null || offer == null || !offer.valid() || prepared == null || prepared.itemStack().isEmpty())
            return ZombiesServiceResult.failure(ZombiesErrorCode.WEAPON_INVALID_CURRENT_WEAPON);
        RuntimeState current = states.get(key);
        if (current != null && current.phase() != Phase.IDLE)
            return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_BUSY);
        ZombiesServiceResult<?> charged = charge == null ? ZombiesServiceResult.ok() : charge.charge();
        if (charged == null || !charged.success())
            return ZombiesServiceResult.failure(charged == null ? ZombiesErrorCode.ECONOMY_INVALID_COST : charged.code(),
                    charged == null ? Map.of() : charged.params(), charged == null ? "Mystery box charge failed" : charged.logMessage());
        List<String> frames = new ArrayList<>();
        if (previewGunIds != null) frames.addAll(previewGunIds);
        if (frames.isEmpty()) frames.add(offer.gunId());
        while (frames.size() < 10) frames.add(frames.get((frames.size()) % frames.size()));
        if (frames.size() > 10) frames = new ArrayList<>(frames.subList(0, 10));
        frames.set(9, offer.gunId());
        long start = Math.max(0L, startTick);
        RuntimeState next = new RuntimeState(key, owner, Phase.ROLLING, start, start + ROLL_TICKS,
                start + ROLL_TICKS + CLAIM_TICKS, start + ROLL_TICKS + CLAIM_TICKS + COOLDOWN_TICKS,
                List.copyOf(frames), offer, prepared, ++revision);
        states.put(key, next);
        return ZombiesServiceResult.success(next);
    }

    public synchronized ZombiesServiceResult<RuntimeState> claim(String objectId, UUID playerId, long gameTime, ClaimAction action) {
        RuntimeState state = states.get(normalize(objectId));
        if (state == null || state.phase() != Phase.CLAIMABLE) return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_BUSY);
        if (!Objects.equals(state.owner(), playerId)) return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_BUSY);
        if (gameTime >= state.claimDeadlineTick()) {
            expire(state.objectId());
            return ZombiesServiceResult.failure(ZombiesErrorCode.of("mystery_box.claim_expired"));
        }
        ZombiesServiceResult<?> result = action == null ? ZombiesServiceResult.ok() : action.claim(state);
        if (result == null || !result.success())
            return ZombiesServiceResult.failure(result == null ? ZombiesErrorCode.of("mystery_box.claim_failed") : result.code(),
                    result == null ? Map.of() : result.params(), result == null ? "Mystery box claim failed" : result.logMessage());
        RuntimeState cooldown = state.withPhase(Phase.COOLDOWN, Math.max(0L, gameTime) + COOLDOWN_TICKS, ++revision);
        states.put(state.objectId(), cooldown);
        return ZombiesServiceResult.success(cooldown);
    }

    public synchronized void tick(long gameTime) {
        long now = Math.max(0L, gameTime);
        for (RuntimeState state : List.copyOf(states.values())) {
            if (state.phase() == Phase.ROLLING && now >= state.claimableTick())
                states.put(state.objectId(), state.withPhase(Phase.CLAIMABLE, state.cooldownUntilTick(), ++revision));
            else if (state.phase() == Phase.CLAIMABLE && now >= state.claimDeadlineTick()) expire(state.objectId());
            else if (state.phase() == Phase.COOLDOWN && now >= state.cooldownUntilTick())
                states.put(state.objectId(), state.withPhase(Phase.IDLE, 0L, ++revision));
        }
    }

    public synchronized void reset() { states.clear(); revision++; }
    public synchronized RuntimeState state(String objectId) { return states.get(normalize(objectId)); }
    public synchronized Collection<RuntimeState> states() { return List.copyOf(states.values()); }

    private void expire(String objectId) {
        RuntimeState state = states.get(normalize(objectId));
        if (state != null) states.put(state.objectId(), state.withPhase(Phase.COOLDOWN, state.claimDeadlineTick() + COOLDOWN_TICKS, ++revision));
    }
    private static String normalize(String value) { return Objects.requireNonNullElse(value, "").trim(); }

    public enum Phase { IDLE, ROLLING, CLAIMABLE, COOLDOWN }
    public record RuntimeState(String objectId, UUID owner, Phase phase, long startTick, long claimableTick,
            long claimDeadlineTick, long cooldownUntilTick, List<String> previewGunIds,
            ZombiesMysteryBoxOfferService.Offer offer, ZombiesWeaponInventoryService.PreparedWeaponStack preparedWeapon,
            long revision) {
        public RuntimeState {
            objectId = Objects.requireNonNullElse(objectId, "").trim();
            phase = phase == null ? Phase.IDLE : phase;
            previewGunIds = previewGunIds == null ? List.of() : List.copyOf(previewGunIds);
            revision = Math.max(0L, revision);
        }
        public RuntimeState withPhase(Phase next, long cooldownUntil, long nextRevision) {
            return new RuntimeState(objectId, owner, next, startTick, claimableTick, claimDeadlineTick,
                    cooldownUntil, previewGunIds, offer, preparedWeapon, nextRevision);
        }
    }
    @FunctionalInterface public interface ChargeAction { ZombiesServiceResult<?> charge(); }
    @FunctionalInterface public interface ClaimAction { ZombiesServiceResult<?> claim(RuntimeState state); }
}
