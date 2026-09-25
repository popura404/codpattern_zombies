package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry;
import com.cdp.codpattern.app.match.runtime.lifecycle.CleanupCoordinator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Idempotent cleanup orchestrator for zombies rounds. It only depends on public
 * services and callbacks; map-private reset details stay in integration hooks.
 */
public class ZombiesCleanupService {
    private final ModeEntityOwnershipRegistry ownershipRegistry;
    private final ZombiesMapOccupancyService occupancyService;
    private final Hooks hooks;
    private final CleanupCoordinator<CleanupWork, ZombiesServiceResult<Void>, CleanupSummary> coordinator;
    private long cleanupRevision;

    public ZombiesCleanupService(
            ModeEntityOwnershipRegistry ownershipRegistry,
            ZombiesMapOccupancyService occupancyService,
            Hooks hooks,
            Collection<ZombiesCleanupParticipant> participants
    ) {
        this.ownershipRegistry = ownershipRegistry == null
                ? ModeEntityOwnershipRegistry.instance()
                : ownershipRegistry;
        this.occupancyService = occupancyService == null
                ? ZombiesMapOccupancyService.instance()
                : occupancyService;
        this.hooks = hooks == null ? Hooks.noop() : hooks;
        this.coordinator = new CleanupCoordinator<>(
                adaptParticipants(participants),
                (work, participantName, failure, completedParticipants) ->
                        this.hooks.onParticipantFailure(work.context(), participantName, failure),
                this::finishCleanup);
    }

    public ZombiesServiceResult<CleanupSummary> cleanup(RoomId roomId, String reason, LevelResolver levelResolver) {
        Objects.requireNonNull(roomId, "roomId");
        long revision = ++cleanupRevision;
        ZombiesCleanupParticipant.ZombiesCleanupContext context =
                new ZombiesCleanupParticipant.ZombiesCleanupContext(roomId, reason, revision);

        hooks.beforeCleanup(context);
        // Shared termination retains resource evidence and deletes entities independently of these hooks.
        EntityCleanupSummary entitySummary = new EntityCleanupSummary(
                ownershipRegistry.entitiesInRoom(roomId).size(), 0, 0);
        CleanupCoordinator.Result<ZombiesServiceResult<Void>, CleanupSummary> result =
                coordinator.execute(new CleanupWork(context, entitySummary));
        if (!result.success()) {
            ZombiesServiceResult<Void> participantResult = result.failure().orElseGet(() ->
                    ZombiesServiceResult.failure(
                            ZombiesErrorCode.of("cleanup.participant_failed"),
                            java.util.Map.of(),
                            "Cleanup participant failed without a result"));
            return ZombiesServiceResult.failure(
                    participantResult.code(),
                    participantResult.params(),
                    participantResult.logMessage());
        }
        return ZombiesServiceResult.success(result.summary().orElseThrow());
    }

    public EntityCleanupSummary cleanupEntities(RoomId roomId, LevelResolver levelResolver) {
        Objects.requireNonNull(roomId, "roomId");
        List<ModeEntityOwnershipRegistry.Entry> entries = ownershipRegistry.entitiesInRoom(roomId);
        int removedEntities = 0;
        int missingEntities = 0;
        for (ModeEntityOwnershipRegistry.Entry entry : entries) {
            ServerLevel level = levelResolver == null ? null : levelResolver.level(entry.dimension());
            Entity entity = level == null ? null : level.getEntity(entry.entityId());
            if (entity == null) {
                try { hooks.onMissingEntityCleanup(entry); }
                catch (RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().warn("Missing entity notification failed", failure); }
                missingEntities++;
                continue;
            }
            try { hooks.onEntityCleanup(entity); }
            catch (RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().warn("Entity notification failed", failure); }
            entity.remove(Entity.RemovalReason.DISCARDED);
            if (entity.isRemoved()) {
                ownershipRegistry.unregister(entity);
                com.cdp.codpattern.app.match.runtime.termination.RoomTerminationService.current()
                        .ifPresent(service -> service.acknowledgeEntity(entity.getUUID()));
                removedEntities++;
            }
        }
        return new EntityCleanupSummary(entries.size(), removedEntities, missingEntities);
    }

    public EntityCleanupSummary cleanupMissingEntity(RoomId roomId, ModeEntityOwnershipRegistry.Entry entry) {
        Objects.requireNonNull(roomId, "roomId");
        if (entry == null || !sameRoom(entry.roomId(), roomId)) {
            return new EntityCleanupSummary(0, 0, 0);
        }

        Optional<ModeEntityOwnershipRegistry.Entry> removed = ownershipRegistry.unregister(entry.entityId());
        ModeEntityOwnershipRegistry.Entry cleanupEntry = removed.orElse(entry);
        if (!sameRoom(cleanupEntry.roomId(), roomId)) {
            return new EntityCleanupSummary(0, 0, 0);
        }
        hooks.onMissingEntityCleanup(cleanupEntry);
        return new EntityCleanupSummary(1, 0, 1);
    }

    private CleanupSummary finishCleanup(CleanupWork work) {
        ZombiesCleanupParticipant.ZombiesCleanupContext context = work.context();
        hooks.clearObjectRuntime(context);
        hooks.clearPlayerRuntime(context);
        hooks.clearReadyState(context);
        hooks.clearStartVote(context);
        hooks.clearLifecycleRuntime(context);
        hooks.clearHudState(context);
        boolean occupancyReleased = com.cdp.codpattern.app.match.runtime.termination.RoomTerminationService.current().isEmpty()
                && occupancyService.release(context.roomId()); // Pure fixtures only; live completion belongs to the main mod.
        hooks.afterOccupancyReleased(context, occupancyReleased);
        hooks.afterCleanup(context);
        return new CleanupSummary(context.cleanupRevision(), work.entitySummary(), occupancyReleased);
    }

    private static List<CleanupCoordinator.Participant<CleanupWork, ZombiesServiceResult<Void>>> adaptParticipants(
            Collection<ZombiesCleanupParticipant> participants
    ) {
        List<CleanupCoordinator.Participant<CleanupWork, ZombiesServiceResult<Void>>> adapted = new ArrayList<>();
        if (participants == null) {
            return adapted;
        }
        for (ZombiesCleanupParticipant participant : participants) {
            if (participant == null) {
                continue;
            }
            adapted.add(new CleanupCoordinator.Participant<>() {
                @Override
                public String name() {
                    return participant.getClass().getName();
                }

                @Override
                public int order() {
                    return participant.order();
                }

                @Override
                public CleanupCoordinator.ParticipantResult<ZombiesServiceResult<Void>> cleanup(CleanupWork work) {
                    ZombiesServiceResult<Void> result = participant.cleanup(work.context());
                    return result == null || result.success()
                            ? CleanupCoordinator.ParticipantResult.completed()
                            : CleanupCoordinator.ParticipantResult.failed(result);
                }
            });
        }
        return adapted;
    }

    private static boolean sameRoom(RoomId left, RoomId right) {
        return left != null
                && right != null
                && left.gameType().equalsIgnoreCase(right.gameType())
                && left.mapName().equals(right.mapName());
    }

    public interface Hooks {
        default void beforeCleanup(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
        }

        default void clearObjectRuntime(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
        }

        default void clearPlayerRuntime(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
        }

        default void clearReadyState(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
        }

        default void clearStartVote(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
        }

        default void clearLifecycleRuntime(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
        }

        default void clearHudState(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
        }

        default void onEntityCleanup(Entity entity) {
        }

        default void onMissingEntityCleanup(ModeEntityOwnershipRegistry.Entry entry) {
        }

        default void onParticipantFailure(
                ZombiesCleanupParticipant.ZombiesCleanupContext context,
                String participantName,
                ZombiesServiceResult<Void> failure
        ) {
        }

        default void afterOccupancyReleased(ZombiesCleanupParticipant.ZombiesCleanupContext context, boolean released) {
        }

        default void afterCleanup(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
        }

        static Hooks noop() {
            return new Hooks() {
            };
        }
    }

    @FunctionalInterface
    public interface LevelResolver {
        ServerLevel level(net.minecraft.resources.ResourceKey<Level> dimension);
    }

    public record CleanupSummary(
            long cleanupRevision,
            EntityCleanupSummary entities,
            boolean occupancyReleased
    ) {
    }

    public record EntityCleanupSummary(
            int registeredEntries,
            int removedEntities,
            int missingEntities
    ) {
    }

    private record CleanupWork(
            ZombiesCleanupParticipant.ZombiesCleanupContext context,
            EntityCleanupSummary entitySummary
    ) {
    }
}
