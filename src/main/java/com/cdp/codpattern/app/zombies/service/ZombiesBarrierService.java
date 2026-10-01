package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.match.model.ModePlayerValue;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.item.ZombiesRequiredItem;
import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class ZombiesBarrierService {
    private static final ZombiesErrorCode OBJECT_PHASE_LOCKED = ZombiesErrorCode.of("object.phase_locked");

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private final Supplier<Collection<ZombiesZombieSpawnData>> spawnsSupplier;
    private final Supplier<ZombiesBarrierGroupsConfig> groupRulesSupplier;
    private final RoomId roomId;
    private final Supplier<Collection<ZombiesBarrierData>> barriersSupplier;
    private final ZombiesEconomyService economyService;
    private final ZombiesObjectStateStore objectStateStore;
    private final ZombiesActiveSpawnGroupService activeSpawnGroupService;
    private final Predicate<UUID> roomMemberPredicate;
    private final Supplier<ZombiesGamePhase> phaseSupplier;
    private final Consumer<BarrierPurchaseResult> purchaseSuccessListener;

    public ZombiesBarrierService(
            RoomId roomId,
            Supplier<Collection<ZombiesBarrierData>> barriersSupplier,
            ZombiesEconomyService economyService,
            ZombiesObjectStateStore objectStateStore,
            ZombiesActiveSpawnGroupService activeSpawnGroupService,
            Predicate<UUID> roomMemberPredicate,
            Supplier<ZombiesGamePhase> phaseSupplier
    ) {
        this(
                roomId,
                barriersSupplier,
                economyService,
                objectStateStore,
                activeSpawnGroupService,
                roomMemberPredicate,
                phaseSupplier,
                ignored -> {
                });
    }

    public ZombiesBarrierService(
            RoomId roomId,
            Supplier<Collection<ZombiesBarrierData>> barriersSupplier,
            ZombiesEconomyService economyService,
            ZombiesObjectStateStore objectStateStore,
            ZombiesActiveSpawnGroupService activeSpawnGroupService,
            Predicate<UUID> roomMemberPredicate,
            Supplier<ZombiesGamePhase> phaseSupplier,
            Consumer<BarrierPurchaseResult> purchaseSuccessListener
    ) {
        this(roomId, barriersSupplier, economyService, objectStateStore, activeSpawnGroupService,
                roomMemberPredicate, phaseSupplier, purchaseSuccessListener, List::of);
    }

    public ZombiesBarrierService(
            RoomId roomId,
            Supplier<Collection<ZombiesBarrierData>> barriersSupplier,
            ZombiesEconomyService economyService,
            ZombiesObjectStateStore objectStateStore,
            ZombiesActiveSpawnGroupService activeSpawnGroupService,
            Predicate<UUID> roomMemberPredicate,
            Supplier<ZombiesGamePhase> phaseSupplier,
            Consumer<BarrierPurchaseResult> purchaseSuccessListener,
            Supplier<Collection<ZombiesZombieSpawnData>> spawnsSupplier
    ) {
        this(roomId, barriersSupplier, economyService, objectStateStore, activeSpawnGroupService,
                roomMemberPredicate, phaseSupplier, purchaseSuccessListener, spawnsSupplier, ZombiesBarrierGroupsConfig::empty);
    }

    public ZombiesBarrierService(
            RoomId roomId,
            Supplier<Collection<ZombiesBarrierData>> barriersSupplier,
            ZombiesEconomyService economyService,
            ZombiesObjectStateStore objectStateStore,
            ZombiesActiveSpawnGroupService activeSpawnGroupService,
            Predicate<UUID> roomMemberPredicate,
            Supplier<ZombiesGamePhase> phaseSupplier,
            Consumer<BarrierPurchaseResult> purchaseSuccessListener,
            Supplier<Collection<ZombiesZombieSpawnData>> spawnsSupplier,
            Supplier<ZombiesBarrierGroupsConfig> groupRulesSupplier
    ) {
        this.groupRulesSupplier = Objects.requireNonNull(groupRulesSupplier, "groupRulesSupplier");
        this.spawnsSupplier = Objects.requireNonNull(spawnsSupplier, "spawnsSupplier");
        this.roomId = Objects.requireNonNull(roomId, "roomId");
        this.barriersSupplier = Objects.requireNonNull(barriersSupplier, "barriersSupplier");
        this.economyService = Objects.requireNonNull(economyService, "economyService");
        this.objectStateStore = Objects.requireNonNull(objectStateStore, "objectStateStore");
        this.activeSpawnGroupService = Objects.requireNonNull(activeSpawnGroupService, "activeSpawnGroupService");
        this.roomMemberPredicate = roomMemberPredicate == null ? ignored -> false : roomMemberPredicate;
        this.phaseSupplier = phaseSupplier == null ? () -> ZombiesGamePhase.WAITING : phaseSupplier;
        this.purchaseSuccessListener = purchaseSuccessListener == null ? ignored -> {
        } : purchaseSuccessListener;
    }

    public ZombiesServiceResult<BarrierPurchaseResult> purchase(ServerPlayer player, ZombiesBarrierData barrier) {
        if (player == null || barrier == null) return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_NOT_FOUND);
        UUID playerId = player.getUUID();
        if (!roomMemberPredicate.test(playerId)) return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_ROOM_MISMATCH);
        if (!player.level().dimension().equals(barrier.dimension())) return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_OUT_OF_RANGE);
        ZombiesGamePhase phase = phaseSupplier.get();
        if (phase == null || !phase.allowsPurchases()) return ZombiesServiceResult.failure(OBJECT_PHASE_LOCKED);

        Collection<ZombiesBarrierData> barriers = List.copyOf(barriersSupplier.get());
        if (barrier.group() < 1 || !barriers.contains(barrier)) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_NOT_FOUND);
        }
        // Opening state belongs to the group, not to its entry requirements.
        if (objectStateStore.isBarrierCleared(barrier)) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.of("barrier.already_cleared"));
        }
        ZombiesBarrierGroupsConfig rules = groupRulesSupplier.get();
        ZombiesBarrierGroupsConfig.ResolvedRule rule = rules == null ? null
                : rules.rule(barrier.group(), barrier.entryId()).orElse(null);
        if (rule == null) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.of("barrier.missing_entry_rules"),
                    Map.of("group", ModePlayerValue.ofInt(barrier.group()), "entryId", ModePlayerValue.ofInt(barrier.entryId())),
                    "Configure barrier group/entry " + barrier.group() + "/" + barrier.entryId() + " in "
                            + (rules == null ? ZombiesBarrierGroupsConfig.FILE_NAME : rules.sourcePath()));
        }
        Set<Integer> spawnGroups = spawnsSupplier.get().stream()
                .map(ZombiesZombieSpawnData::group).collect(Collectors.toSet());
        if (!rule.spawnGroupChanges().valid() || !spawnGroups.containsAll(rule.spawnGroupChanges().referencedGroups())) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.of("barrier.invalid_spawn_groups"));
        }
        ZombiesServiceResult<Void> eligibility = requiredItemEligibility(player, rule.requiredItem());
        if (!eligibility.success()) return ZombiesServiceResult.failure(eligibility.code(), eligibility.params(), eligibility.logMessage());

        // Capture one immutable group/entry resolution for checks, charging and spawn actions.
        ZombiesServiceResult<BarrierPurchaseResult> result = economyService.spendAtomically(playerId, rule.cost(), ignoredState -> {
            if (objectStateStore.isBarrierCleared(barrier)) {
                return ZombiesServiceResult.failure(ZombiesErrorCode.of("barrier.already_cleared"));
            }
            ZombiesServiceResult<Void> lockedEligibility = requiredItemEligibility(player, rule.requiredItem());
            if (!lockedEligibility.success()) {
                return ZombiesServiceResult.failure(lockedEligibility.code(), lockedEligibility.params(), lockedEligibility.logMessage());
            }
            ZombiesServiceResult<ZombiesObjectStateStore.BarrierGroupUpdate> clearResult =
                    objectStateStore.clearBarrierGroup(barrier.group(), barriers);
            if (!clearResult.success()) return ZombiesServiceResult.failure(clearResult.code(), clearResult.params(), clearResult.logMessage());
            ZombiesObjectStateStore.BarrierGroupUpdate update = clearResult.value().orElseThrow();
            activeSpawnGroupService.apply(rule.spawnGroupChanges());
            economyService.recordBarrierOpened(playerId);
            return ZombiesServiceResult.success(new BarrierPurchaseResult(
                    roomId, update.group(), update.objectIds(), update.revision(), rule.cost(), rule.entryId()));
        });
        if (result.success()) {
            try {
                purchaseSuccessListener.accept(result.value().orElseThrow());
            } catch (RuntimeException exception) {
                LOGGER.error("Barrier purchase committed but notification failed in room {}", roomId, exception);
            }
        }
        return result;
    }

    private static ZombiesServiceResult<Void> requiredItemEligibility(ServerPlayer player, String specification) {
        ZombiesRequiredItem.ParseResult requiredItem = ZombiesRequiredItem.parse(specification);
        if (requiredItem.configured() && (!requiredItem.valid()
                || !ZombiesRequiredItem.inventoryContains(player.getInventory(), specification))) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.BARRIER_REQUIRED_ITEM_MISSING,
                    Map.of("requiredItem", ModePlayerValue.ofString(specification),
                            "requiredItemName", ModePlayerValue.ofString(ZombiesRequiredItem.displayName(specification).getString())),
                    requiredItem.valid() ? "" : "invalid entry requiredItem: " + requiredItem.error());
        }
        return ZombiesServiceResult.ok();
    }

    public record BarrierPurchaseResult(
            RoomId roomId,
            int group,
            Collection<String> clearedObjectIds,
            long revision,
            int cost,
            int entryId
    ) {
        public BarrierPurchaseResult(RoomId roomId, int group, Collection<String> clearedObjectIds, long revision) {
            this(roomId, group, clearedObjectIds, revision, 0, 0);
        }
        public BarrierPurchaseResult(RoomId roomId, int group, Collection<String> clearedObjectIds, long revision, int cost) {
            this(roomId, group, clearedObjectIds, revision, cost, 0);
        }
        public BarrierPurchaseResult {
            Objects.requireNonNull(roomId, "roomId");
            clearedObjectIds = clearedObjectIds == null ? List.of() : List.copyOf(clearedObjectIds);
            revision = Math.max(0L, revision);
        }
    }
}
