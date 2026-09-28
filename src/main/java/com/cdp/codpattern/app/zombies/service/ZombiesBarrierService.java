package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.match.model.ModePlayerValue;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.item.ZombiesRequiredItem;
import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
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
        if (player == null || barrier == null) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_NOT_FOUND);
        }
        UUID playerId = player.getUUID();
        if (!roomMemberPredicate.test(playerId)) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_ROOM_MISMATCH);
        }
        if (!player.level().dimension().equals(barrier.dimension())) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.OBJECT_OUT_OF_RANGE);
        }
        ZombiesGamePhase phase = phaseSupplier.get();
        if (phase == null || !phase.allowsPurchases()) {
            return ZombiesServiceResult.failure(OBJECT_PHASE_LOCKED);
        }
        ZombiesRequiredItem.ParseResult requiredItem = ZombiesRequiredItem.parse(barrier.requiredItem());
        if (requiredItem.configured()
                && (!requiredItem.valid()
                || !ZombiesRequiredItem.inventoryContains(player.getInventory(), barrier.requiredItem()))) {
            return ZombiesServiceResult.failure(
                    ZombiesErrorCode.BARRIER_REQUIRED_ITEM_MISSING,
                    Map.of(
                            "requiredItem", ModePlayerValue.ofString(barrier.requiredItem()),
                            "requiredItemName", ModePlayerValue.ofString(
                                    ZombiesRequiredItem.displayName(barrier.requiredItem()).getString())),
                    requiredItem.valid() ? "" : "invalid barrier requiredItem: " + requiredItem.error());
        }

        Collection<ZombiesBarrierData> barriers = List.copyOf(barriersSupplier.get());
        Set<Integer> spawnGroups = spawnsSupplier.get().stream()
                .map(ZombiesZombieSpawnData::group).collect(Collectors.toSet());
        if (barrier.group() < 1 || !barrier.spawnGroupChanges().valid()
                || !spawnGroups.containsAll(barrier.spawnGroupChanges().referencedGroups())
                || !barriers.contains(barrier)
                || barriers.stream().filter(other -> other.group() == barrier.group())
                        .anyMatch(other -> !other.spawnGroupChanges().equals(barrier.spawnGroupChanges()))) {
            return ZombiesServiceResult.failure(ZombiesErrorCode.of("barrier.invalid_spawn_groups"));
        }

        ZombiesServiceResult<BarrierPurchaseResult> result = economyService.spendAtomically(playerId, barrier.cost(), ignoredState -> {
            ZombiesServiceResult<ZombiesObjectStateStore.BarrierGroupUpdate> clearResult =
                    objectStateStore.clearBarrierGroup(barrier.group(), barriers);
            if (!clearResult.success()) {
                return ZombiesServiceResult.failure(clearResult.code(), clearResult.params(), clearResult.logMessage());
            }

            ZombiesObjectStateStore.BarrierGroupUpdate update = clearResult.value().orElseThrow();
            activeSpawnGroupService.apply(barrier.spawnGroupChanges());
            economyService.recordBarrierOpened(playerId);
            BarrierPurchaseResult purchase = new BarrierPurchaseResult(
                    roomId,
                    update.group(),
                    update.objectIds(),
                    update.revision());
            return ZombiesServiceResult.success(purchase);
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

    public record BarrierPurchaseResult(
            RoomId roomId,
            int group,
            Collection<String> clearedObjectIds,
            long revision
    ) {
        public BarrierPurchaseResult {
            Objects.requireNonNull(roomId, "roomId");
            clearedObjectIds = clearedObjectIds == null ? List.of() : List.copyOf(clearedObjectIds);
            revision = Math.max(0L, revision);
        }
    }
}
