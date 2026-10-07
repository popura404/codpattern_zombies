package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.match.BuiltInGameModes;
import com.cdp.codpattern.app.match.ModeRoomHandle;
import com.cdp.codpattern.app.match.model.ModePlayerValue;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry;
import com.cdp.codpattern.app.tdm.service.CombatRegenService;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.ZombiesMapSnapshot;
import com.cdp.codpattern.app.zombies.map.object.ZombiesAmmoBoxData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesArmorStationData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesInitialSpawnData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesWeaponWallData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesMysteryBoxData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.model.ZombiesPlayerRuntimeState;
import com.cdp.codpattern.app.zombies.model.ZombiesTeamNames;
import com.cdp.codpattern.app.zombies.runtime.ZombiesLifecycleRuntime;
import com.cdp.codpattern.app.zombies.runtime.ZombiesPhaseStateMachine;
import com.cdp.codpattern.app.zombies.runtime.ZombiesRoomRuntimeState;
import com.cdp.codpattern.app.zombies.service.ZombiesActiveSpawnGroupService;
import com.cdp.codpattern.app.zombies.service.ZombiesAmmoBoxService;
import com.cdp.codpattern.app.zombies.service.ZombiesArmorService;
import com.cdp.codpattern.app.zombies.service.ZombiesBarrierBlockRuntimeService;
import com.cdp.codpattern.app.zombies.service.ZombiesBarrierService;
import com.cdp.codpattern.app.zombies.service.ZombiesBarrierVisualService;
import com.cdp.codpattern.app.zombies.service.ZombiesBuffService;
import com.cdp.codpattern.app.zombies.service.ZombiesBuffRuntimeEffectService;
import com.cdp.codpattern.app.zombies.service.ZombiesCleanupParticipant;
import com.cdp.codpattern.app.zombies.service.ZombiesCleanupService;
import com.cdp.codpattern.app.zombies.service.ZombiesConnectionStateService;
import com.cdp.codpattern.app.zombies.service.ZombiesCrashRecoveryService;
import com.cdp.codpattern.app.zombies.service.ZombiesDeathService;
import com.cdp.codpattern.app.zombies.service.ZombiesEconomyService;
import com.cdp.codpattern.app.zombies.service.ZombiesEquipmentSnapshotService;
import com.cdp.codpattern.app.zombies.service.ZombiesErrorCode;
import com.cdp.codpattern.app.zombies.service.ZombiesIntermissionRespawnService;
import com.cdp.codpattern.app.zombies.service.ZombiesLegacyTextFormatter;
import com.cdp.codpattern.app.zombies.service.ZombiesMapOccupancyService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobLifecycleService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobRecycleService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobSpawnService;
import com.cdp.codpattern.app.zombies.service.ZombiesObjectInteractionService;
import com.cdp.codpattern.app.zombies.service.ZombiesObjectStateStore;
import com.cdp.codpattern.app.zombies.service.ZombiesPlayerStateService;
import com.cdp.codpattern.app.zombies.service.ZombiesPlayerRuntimeMarkerService;
import com.cdp.codpattern.app.zombies.service.ZombiesPowerService;
import com.cdp.codpattern.app.zombies.service.ZombiesPowerSwitchBlockStateService;
import com.cdp.codpattern.app.zombies.service.ZombiesPostGameTeleportService;
import com.cdp.codpattern.app.zombies.service.ZombiesPurchasableBlockService;
import com.cdp.codpattern.app.zombies.service.ZombiesReadyService;
import com.cdp.codpattern.app.zombies.service.ZombiesReviveLoadoutService;
import com.cdp.codpattern.app.zombies.service.ZombiesRoomAnnouncementService;
import com.cdp.codpattern.app.zombies.service.ZombiesServiceResult;
import com.cdp.codpattern.app.zombies.service.ZombiesSpawnAssignmentService;
import com.cdp.codpattern.app.zombies.service.ZombiesStartVoteService;
import com.cdp.codpattern.app.zombies.service.ZombiesStarterKitDistributor;
import com.cdp.codpattern.app.zombies.service.ZombiesStartupFlow;
import com.cdp.codpattern.app.zombies.service.ZombiesStartupPreflightSnapshot;
import com.cdp.codpattern.app.zombies.service.ZombiesStartupValidationService;
import com.cdp.codpattern.app.zombies.service.ZombiesUltimateMachineService;
import com.cdp.codpattern.app.zombies.service.ZombiesWaveConfigRepository;
import com.cdp.codpattern.app.zombies.service.ZombiesWaveDirector;
import com.cdp.codpattern.app.zombies.service.ZombiesWaveTextRepository;
import com.cdp.codpattern.app.zombies.service.ZombiesWaveTextScheduler;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponInstanceService;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponWallOfferService;
import com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue;
import com.cdp.codpattern.core.throwable.ThrowableInventoryService;
import com.cdp.codpattern.config.zombies.ZombiesConfigPaths;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;
import com.cdp.codpattern.adapter.forge.network.ModNetworkChannel;
import com.cdp.codpattern.config.zombies.ZombiesBackpackConfig;
import com.cdp.codpattern.config.zombies.ZombiesMysteryBoxConfig;
import com.cdp.codpattern.config.zombies.ZombiesMysteryBoxRepository;
import com.cdp.codpattern.config.zombies.ZombiesServerConfig;
import com.cdp.codpattern.config.zombies.ZombiesConfigRepository;
import com.cdp.codpattern.config.zombies.ZombiesWeaponWallConfig;
import com.cdp.codpattern.fpsmatch.room.CodTdmRoomManager;
import com.cdp.codpattern.network.match.VoteDialogPacket;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointKind;
import com.phasetranscrystal.fpsmatch.core.map.BaseMap;
import com.phasetranscrystal.fpsmatch.core.map.EndTeleportMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.cdp.codpattern.common.sound.ZombiesSoundRegister;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ZombiesMap extends BaseMap implements EndTeleportMap<ZombiesMap>, com.cdp.codpattern.app.match.ModeRoomBackedMap, com.cdp.codpattern.app.match.port.ModeRoomEvictionPort {
    static final int SURVIVOR_LIMIT = 4;
    private static final int COMBAT_REGEN_DELAY_TICKS = 120;
    private static final float COMBAT_REGEN_HALF_HEARTS_PER_SECOND = 5.0F;

    private final RoomId roomId;
    private final ZombiesRoomRuntimeState runtimeState;
    private final ZombiesLifecycleRuntime lifecycleRuntime;
    private final ZombiesPlayerStateService playerStateService;
    private final ZombiesConnectionStateService connectionStateService;
    private final ZombiesEconomyService economyService;
    private final ZombiesReadyService readyService;
    private final ZombiesStartVoteService startVoteService;
    private final ZombiesMobSpawnService mobSpawnService;
    private final ZombiesMobLifecycleService mobLifecycleService;
    private final ZombiesMobRecycleService mobRecycleService;
    private final ZombiesDeathService deathService;
    private final ZombiesEquipmentSnapshotService equipmentSnapshotService;
    private final ZombiesReviveLoadoutService reviveLoadoutService;
    private final ZombiesCleanupService cleanupService;
    private final ZombiesPostGameTeleportService postGameTeleportService;
    private final ZombiesPlayerRuntimeMarkerService runtimeMarkerService;
    private final ZombiesActiveSpawnGroupService activeSpawnGroupService;
    private final ZombiesActiveSpawnGroupService activePlayerSpawnGroupService;
    private final ZombiesPowerService powerService;
    private final ZombiesBuffService buffService;
    private final ZombiesBuffRuntimeEffectService buffRuntimeEffectService;
    private final ZombiesIntermissionRespawnService intermissionRespawnService;
    private final ZombiesUltimateMachineService ultimateMachineService;
    private final ZombiesObjectStateStore objectStateStore;
    private final ZombiesObjectInteractionService objectInteractionService;
    private final ZombiesBarrierVisualService barrierVisualService;
    private final ZombiesBarrierBlockRuntimeService barrierBlockRuntimeService;
    private final ZombiesWaveTextScheduler waveTextScheduler = new ZombiesWaveTextScheduler(List.of());
    private final ModeRoomHandle roomHandle;
    private Optional<SpawnPointData> matchEndTeleportPoint = Optional.empty();
    private ZombiesMapObjects objects = ZombiesMapObjects.EMPTY;
    private ZombiesMapObjects frozenObjects = ZombiesMapObjects.EMPTY;
    private boolean objectsFrozen;
    private ZombiesWaveDirector waveDirector;
    private ZombiesRulesConfig rulesConfig;
    private ZombiesServerConfig serverConfig;
    private com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig frozenBarrierGroupRules =
            com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig.empty();
    private ZombiesMysteryBoxConfig mysteryBoxConfig;
    private List<ZombiesValidationIssue> rulesValidationIssues = List.of();
    private final Map<UUID, Integer> combatRegenCooldowns = new LinkedHashMap<>();
    private int rosterVersion = 1;

    public ZombiesMap(ServerLevel serverLevel, String mapName, AreaData areaData) {
        super(serverLevel, mapName, areaData);
        addTeam(ZombiesTeamNames.SURVIVORS, SURVIVOR_LIMIT);
        this.roomId = RoomId.of(BuiltInGameModes.ZOMBIES, mapName);
        loadStartupConfigs(serverLevel == null ? null : serverLevel.getServer());
        this.runtimeState = new ZombiesRoomRuntimeState(roomId);
        this.playerStateService = new ZombiesPlayerStateService();
        this.connectionStateService = new ZombiesConnectionStateService(playerStateService, configuredOfflineGraceTicks());
        this.economyService = new ZombiesEconomyService(playerStateService);
        this.readyService = new ZombiesReadyService(new ZombiesReadyHooks());
        this.mobSpawnService = new ZombiesMobSpawnService(
                ModeEntityOwnershipRegistry.instance(),
                this::aliveSurvivorPlayers,
                () -> rulesConfig().getSpawnPointWeighting());
        if (serverLevel != null && areaData != null) this.mobSpawnService.configureNavigationContext(roomId, serverLevel,
                new net.minecraft.world.phys.AABB(areaData.pos1()).minmax(
                        new net.minecraft.world.phys.AABB(areaData.pos2())));
        this.mobLifecycleService = new ZombiesMobLifecycleService(ModeEntityOwnershipRegistry.instance(), mobSpawnService);
        this.mobRecycleService = new ZombiesMobRecycleService(
                ModeEntityOwnershipRegistry.instance(),
                mobLifecycleService,
                this::aliveSurvivorPlayers);
        this.deathService = new ZombiesDeathService(
                playerStateService,
                connectionStateService.offlineGraceTicks(),
                new ZombiesDeathHooks());
        this.equipmentSnapshotService = new ZombiesEquipmentSnapshotService();
        this.reviveLoadoutService = new ZombiesReviveLoadoutService();
        this.postGameTeleportService = ZombiesPostGameTeleportService.instance();
        this.runtimeMarkerService = ZombiesPlayerRuntimeMarkerService.instance();
        this.activeSpawnGroupService = new ZombiesActiveSpawnGroupService();
        this.activePlayerSpawnGroupService = new ZombiesActiveSpawnGroupService();
        ZombiesPowerSwitchBlockStateService powerSwitchBlockStateService =
                new ZombiesPowerSwitchBlockStateService(this::levelForDimension);
        this.powerService = new ZombiesPowerService(
                economyService,
                powered -> powerSwitchBlockStateService.setPowered(runtimeObjects().powerSwitch(), powered));
        this.buffService = new ZombiesBuffService(economyService, powerService);
        this.buffRuntimeEffectService = new ZombiesBuffRuntimeEffectService(playerStateService);
        this.intermissionRespawnService = new ZombiesIntermissionRespawnService(playerStateService, buffService);
        this.ultimateMachineService = new ZombiesUltimateMachineService(economyService, powerService);
        this.objectStateStore = new ZombiesObjectStateStore(
                powerService::isPowerOn,
                new ZombiesWeaponWallOfferService(
                        () -> serverConfig().getWeaponWall(),
                        () -> serverConfig().getWeaponRules(),
                        this::backpackConfig,
                        null,
                        null),
                this::rulesConfig);
        this.objectStateStore.configureMysteryBoxConfigSupplier(this::mysteryBoxConfig);
        this.barrierVisualService = ZombiesBarrierVisualService.instance();
        this.barrierBlockRuntimeService = ZombiesBarrierBlockRuntimeService.instance();
        ZombiesBarrierService barrierService = new ZombiesBarrierService(
                roomId,
                this::runtimeBarriers,
                economyService,
                objectStateStore,
                activeSpawnGroupService,
                this::hasSurvivor,
                runtimeState::phase,
                purchase -> {
                    syncConfiguredInitialSpawnsToTeam();
                    barrierBlockRuntimeService.clearGroup(roomId, purchase.group(), this::levelForDimension);
                },
                () -> runtimeObjects().zombieSpawns(),
                this::runtimeBarrierGroupRules,
                activePlayerSpawnGroupService,
                () -> runtimeObjects().initialSpawns());
        ZombiesWeaponInstanceService weaponInstanceService = new ZombiesWeaponInstanceService(economyService);
        ZombiesAmmoBoxService ammoBoxService = new ZombiesAmmoBoxService(economyService);
        ZombiesArmorService armorService = new ZombiesArmorService(economyService);
        this.objectInteractionService = new ZombiesObjectInteractionService(
                roomId,
                this::runtimeBarriers,
                this::runtimeWeaponWalls,
                this::runtimeAmmoBoxes,
                this::runtimeArmorStations,
                () -> runtimeObjects().powerSwitch(),
                this::runtimeSodaMachines,
                this::runtimeUltimateMachines,
                barrierService,
                weaponInstanceService,
                ammoBoxService,
                armorService,
                powerService,
                buffService,
                ultimateMachineService,
                objectStateStore,
                new ZombiesRoomAnnouncementService(this::survivorPlayers),
                this::rulesConfig,
                () -> !recoveryBlocked() && runtimeState.phase().allowsPurchases());
        this.objectInteractionService.configureMysteryBoxRuntime(
                () -> runtimeObjects().mysteryBoxes(),
                this::mysteryBoxConfig,
                () -> serverConfig().getWeaponRules(),
                this::backpackConfig,
                () -> runtimeState.waveState().targetWave());
        this.objectStateStore.configureMysteryBoxRuntimeSupplier(() -> this.objectInteractionService.mysteryBoxRuntime().states());
        this.cleanupService = new ZombiesCleanupService(
                ModeEntityOwnershipRegistry.instance(),
                ZombiesMapOccupancyService.instance(),
                new ZombiesCleanupHooks(),
                List.of());
        this.lifecycleRuntime = new ZombiesLifecycleRuntime(
                runtimeState,
                lifecycleConfig(),
                this::failurePriority,
                state -> state.waveState().isWaveComplete(),
                List.of(new ZombiesLifecycleRuntimeHooks()));
        this.startVoteService = new ZombiesStartVoteService(new ZombiesStartVoteHooks());
        this.roomHandle = ZombiesRoomHandleFactory.create(this);
    }

    @Override
    public String getGameType() {
        return BuiltInGameModes.ZOMBIES;
    }

    @Override
    public com.cdp.codpattern.app.match.runtime.termination.ModeForceEndHandler forceEndHandler() {
        return new com.cdp.codpattern.app.match.runtime.termination.ModeForceEndHandler() {
            public com.cdp.codpattern.app.match.model.result.ModeOperationResult<Void> stop(
                    com.cdp.codpattern.app.match.runtime.termination.ForceEndContext context) {
                isStart = false;
                waveDirector = null;
                waveTextScheduler.cancel();
                startVoteService.clearActiveVoteSession();
                return com.cdp.codpattern.app.match.model.result.ModeOperationResult.success(null);
            }
            // Administrator cancellation deliberately has no victory/defeat settlement.
            public com.cdp.codpattern.app.match.model.result.ModeOperationResult<Void> cleanup(
                    com.cdp.codpattern.app.match.runtime.termination.ForceEndContext context) {
                var result = cleanupService.cleanup(roomId, context.reason(), ZombiesMap.this::levelForDimension);
                if (!result.success()) throw new IllegalStateException(result.logMessage());
                mobRecycleService.reset();
                mobSpawnService.resetNavigationRuntime();
                reconcileActiveMobCounter();
                return com.cdp.codpattern.app.match.model.result.ModeOperationResult.success(null);
            }
        };
    }

    private com.cdp.codpattern.app.match.runtime.termination.RoomTerminationService termination() {
        return com.cdp.codpattern.app.match.runtime.termination.RoomTerminationService.get(getServerLevel().getServer());
    }

    private void beginAttempt(Collection<UUID> members) {
        termination().begin(roomId, members, matchEndTeleportPoint.orElse(null));
    }

    @Override
    public void tick() {
        if (recoveryBlocked()) return;
        if (runtimeState.phase() == ZombiesGamePhase.START_VOTE) {
            startVoteService.tickVoteSession();
        }
        lifecycleRuntime.tick();
        tickCombatRegen();
        syncBuffRuntimeEffects();
        syncBarrierVisuals();
        objectInteractionService.tickMysteryBoxRuntime(getServerLevel().getGameTime());
    }

    @Override
    public void syncToClient() {
        markRoomListDirty();
    }

    @Override
    public void startGame() {
        if (!com.cdp.codpattern.config.storage.ServerMapStorage.canUse(getGameType())) return;
        startGame(survivorPlayerIds());
    }

    private void startGame(Collection<UUID> memberSnapshot) {
        if (recoveryBlocked()) return;
        MinecraftServer server = getServerLevel().getServer();
        List<UUID> members = normalizeStartMembers(memberSnapshot);
        if (server == null || members.isEmpty()) {
            waveTextScheduler.cancel();
            clearFrozenObjectsAndResetRuntime();
            lifecycleRuntime.cancelStartVote();
            markRoomListDirty();
            return;
        }

        beginAttempt(members);
        loadStartupConfigs(server);
        loadWaveTextConfig(server);
        reconcileActiveMobCounter();
        ZombiesStartupFlow startupFlow = new ZombiesStartupFlow(
                new ZombiesStartupValidationService(
                        ZombiesConfigPaths.zombiesMapWaves(server, getMapName()),
                        this::rulesConfig,
                        this::rulesValidationIssues),
                new ZombiesStarterKitDistributor(this::backpackConfig),
                ZombiesMapOccupancyService.instance(),
                new ZombiesSpawnAssignmentService());
        ZombiesServiceResult<ZombiesStartupFlow.StartupResult> startupResult = startupFlow.start(
                ZombiesStartupFlow.StartupRequest.forMap(
                        roomId,
                        currentMapSnapshot(),
                        members,
                        initialSpawnPoints(),
                        this,
                        List.of(new ZombiesStartupMapParticipant())));
        if (!startupResult.success()) {
            notifyStartupFailure(startupResult);
            runCleanup("startup_failed");
            clearFrozenObjectsAndResetRuntime();
            lifecycleRuntime.cancelStartVote();
            markRoomListDirty();
        }
    }

    @Override
    public void victory() {
        resetGame();
    }

    @Override
    public boolean victoryGoal() {
        return false;
    }

    @Override
    public void resetGame() {
        runCleanup("reset");
    }

    @Override
    public void leave(ServerPlayer player) {
        leaveRoomPlayer(player);
    }

    @Override
    public void onPlayerLoggedOut(ServerPlayer player) {
        if (player == null || !hasSurvivor(player.getUUID())) {
            return;
        }
        if (runtimeState.phase() == ZombiesGamePhase.WAITING || runtimeState.phase() == ZombiesGamePhase.START_VOTE) {
            leaveRoomPlayer(player);
            syncRosterToSurvivors();
            return;
        }
        connectionStateService.markOffline(player.getUUID(), runtimeState.roomTick());
        buffRuntimeEffectService.clearPlayer(player);
        barrierVisualService.clearPlayer(player, roomId);
        markRosterDirty();
        syncRosterToSurvivors();
    }

    @Override
    public void onPlayerLoggedIn(ServerPlayer player) {
        if (player != null && (termination().terminated(roomId) || termination().playerPending(player.getUUID()))) return;
        if (player == null || !hasSurvivor(player.getUUID())) {
            return;
        }
        playerStateService.recordPlayerName(player.getUUID(), player.getName().getString());
        connectionStateService.markOnline(player.getUUID());
        if (runtimeState.phase() == ZombiesGamePhase.WAITING) {
            readyService.initializeReadyState(player);
        }
        buffRuntimeEffectService.syncPlayer(player);
        syncBarrierVisual(player);
        markRosterDirty();
    }

    public boolean restoreActiveRoundReconnect(ServerPlayer player) {
        if (recoveryBlocked() || player == null || termination().playerPending(player.getUUID()) || !isStart || !runtimeState.phase().isRoundRunning()) {
            return false;
        }
        UUID playerId = player.getUUID();
        if (!playerStateService.canRestoreActiveRoundPlayer(playerId)) {
            return false;
        }
        Optional<ZombiesPlayerRuntimeState> state = playerStateService.get(playerId);
        if (state.isEmpty()) {
            return false;
        }

        playerStateService.recordPlayerName(playerId, player.getName().getString());
        if (!hasSurvivor(playerId)) {
            getMapTeams().getTeamByName(ZombiesTeamNames.SURVIVORS)
                    .ifPresent(team -> team.join(player));
            if (!hasSurvivor(playerId)) {
                return false;
            }
        }

        connectionStateService.markOnline(playerId);
        if (state.get().lifeState().isDeadSpectating()) {
            keepDeadSpectating(player);
        } else if (!player.gameMode.getGameModeForPlayer().isCreative()) {
            player.setGameMode(GameType.ADVENTURE);
        }
        buffRuntimeEffectService.syncPlayer(player);
        syncBarrierVisual(player);
        markRosterDirty();
        return true;
    }

    @Override
    public ModeRoomHandle roomHandle() {
        return roomHandle;
    }

    RoomId roomId() {
        return roomId;
    }

    ZombiesRoomRuntimeState runtimeState() {
        return runtimeState;
    }

    ZombiesLifecycleRuntime lifecycleRuntime() {
        return lifecycleRuntime;
    }

    ZombiesPlayerStateService playerStateService() {
        return playerStateService;
    }

    ZombiesConnectionStateService connectionStateService() {
        return connectionStateService;
    }

    ZombiesEconomyService economyService() {
        return economyService;
    }

    ZombiesReadyService readyService() {
        return readyService;
    }

    ZombiesStartVoteService startVoteService() {
        return startVoteService;
    }

    ZombiesDeathService deathService() {
        return deathService;
    }

    ZombiesPowerService powerService() {
        return powerService;
    }

    ZombiesCleanupService cleanupService() {
        return cleanupService;
    }

    ZombiesMobSpawnService mobSpawnService() {
        return mobSpawnService;
    }

    ZombiesObjectInteractionService objectInteractionService() {
        return objectInteractionService;
    }

    ZombiesMobLifecycleService mobLifecycleService() {
        return mobLifecycleService;
    }

    int rosterVersion() {
        return Math.max(1, rosterVersion);
    }

    boolean isSurvivorTeamFull() {
        return getMapTeams().testTeamIsFull(ZombiesTeamNames.SURVIVORS);
    }

    boolean hasSurvivor(UUID playerId) {
        return playerId != null && checkGameHasPlayer(playerId);
    }

    List<ServerPlayer> survivorPlayers() {
        List<ServerPlayer> players = new ArrayList<>();
        getMapTeams().getJoinedPlayers().forEach(playerData -> playerData.getPlayer().ifPresent(players::add));
        return players;
    }

    private List<ServerPlayer> aliveSurvivorPlayers() {
        return survivorPlayers().stream()
                .filter(player -> playerStateService.canInteract(player.getUUID()))
                .toList();
    }

    Set<UUID> survivorPlayerIds() {
        return Set.copyOf(survivorPlayerIdList());
    }

    Set<UUID> onlineSurvivorPlayerIds() {
        return survivorPlayers().stream()
                .map(ServerPlayer::getUUID)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    List<UUID> survivorPlayerIdList() {
        Set<UUID> playerIds = new LinkedHashSet<>();
        getMapTeams().getTeamByName(ZombiesTeamNames.SURVIVORS)
                .ifPresent(team -> playerIds.addAll(team.getPlayerList()));
        return List.copyOf(playerIds);
    }

    void onSurvivorJoined(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        playerStateService.recordPlayerName(playerId, player.getName().getString());
        playerStateService.markAlive(playerId);
        connectionStateService.markOnline(playerId);
        readyService.initializeReadyState(player);
        startVoteService.onPlayerJoined(playerId);
        markRosterDirty();
    }

    void onPlayerDamaged(ServerPlayer player) {
        CombatRegenService.onPlayerDamaged(
                combatRegenCooldowns,
                player,
                COMBAT_REGEN_DELAY_TICKS,
                COMBAT_REGEN_HALF_HEARTS_PER_SECOND);
    }

    @Override
    public java.util.Set<UUID> deletionMembers() {
        var members = new java.util.HashSet<>(getMapTeams().getJoinedPlayersWithSpec());
        playerStateService.states().stream().filter(state -> !state.connectionState().isLeft())
                .forEach(state -> members.add(state.playerId()));
        return java.util.Set.copyOf(members);
    }

    @Override
    public void evictRecoveredMember(UUID playerId) {
        // Shared recovery has already restored equipment, effects, HUD and destination.
        CombatRegenService.clearPlayerCooldown(combatRegenCooldowns, playerId);
        postGameTeleportService.clearPending(playerId);
        readyService.removePlayer(playerId);
        startVoteService.onSnapshotMemberLeft(playerId);
        playerStateService.remove(playerId); // connection state is stored in this same service
        getMapTeams().removePlayer(playerId);
        markRosterDirty();
    }

    void leaveRoomPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        CombatRegenService.clearPlayerCooldown(combatRegenCooldowns, playerId);
        buffRuntimeEffectService.clearPlayer(player);
        barrierVisualService.clearPlayer(player, roomId);
        postGameTeleportService.clearPending(playerId);
        runtimeMarkerService.clearMarker(player);
        readyService.removePlayer(playerId);
        playerStateService.markLeft(playerId);
        connectionStateService.markLeft(playerId);
        startVoteService.onSnapshotMemberLeft(playerId);
        super.leave(player);
        if (survivorPlayers().isEmpty() && runtimeState.phase().isRoundRunning()) {
            resetGame();
        }
        markRosterDirty();
    }

    public void clearRecoveredPlayerState(ServerPlayer player, boolean clearInventory) {
        if (player == null) {
            return;
        }
        CombatRegenService.clearPlayerCooldown(combatRegenCooldowns, player.getUUID());
        buffRuntimeEffectService.clearPlayer(player);
        playerStateService.remove(player.getUUID());
        barrierVisualService.clearPlayer(player, roomId);
        runtimeMarkerService.clearTemporaryPlayerState(player, clearInventory);
        runtimeMarkerService.clearMarker(player);
        markRosterDirty();
    }

    void markRoomListDirty() {
        CodTdmRoomManager.getInstance().markRoomListDirty();
    }

    void markRosterDirty() {
        rosterVersion = Math.max(1, rosterVersion + 1);
        markRoomListDirty();
    }

    private void syncRosterToSurvivors() {
        roomHandle.rosterPort().ifPresent(port -> {
            for (ServerPlayer survivor : survivorPlayers()) {
                port.requestRosterResync(survivor);
            }
        });
    }

    private void notifySurvivors(Component message) {
        if (message == null) {
            return;
        }
        for (ServerPlayer player : survivorPlayers()) {
            player.sendSystemMessage(message);
        }
    }

    private void notifyPlayer(UUID playerId, Component message) {
        if (playerId == null || message == null) {
            return;
        }
        MinecraftServer server = getServerLevel().getServer();
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private void notifyStartupFailure(ZombiesServiceResult<?> result) {
        notifySurvivors(startupFailureMessage(result));
    }

    private void playWaveIntro() {
        var definition = waveDirector == null ? null
                : waveDirector.waveDefinition(runtimeState.waveState().targetWave()).orElse(null);
        var sound = ZombiesSoundRegister.forWave(definition);
        for (ServerPlayer player : survivorPlayers()) {
            player.playNotifySound(sound, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    private static Component startupFailureMessage(ZombiesServiceResult<?> result) {
        String code = result == null ? "unknown" : result.code().key();
        String firstIssue = resultParam(result, "firstIssueCode");
        if (!firstIssue.isBlank()) {
            return Component.translatable(
                    "message.codpattern.zombies.startup_failed_with_issue",
                    code,
                    firstIssue);
        }
        return Component.translatable("message.codpattern.zombies.startup_failed", code);
    }

    private static Component voteFailureReasonMessage(ZombiesStartVoteService.FailureReason reason) {
        String key = "message.codpattern.zombies.vote.reason."
                + (reason == null ? "unknown" : reason.name().toLowerCase(Locale.ROOT));
        return Component.translatable(key);
    }

    private static String resultParam(ZombiesServiceResult<?> result, String key) {
        if (result == null || key == null) {
            return "";
        }
        ModePlayerValue value = result.params().get(key);
        return value == null ? "" : value.value();
    }

    @Override
    public void setMatchEndTeleportPoint(SpawnPointData data) {
        if (getServerLevel().getServer() != null && com.phasetranscrystal.fpsmatch.core.FPSMCore.initialized()
                && com.phasetranscrystal.fpsmatch.core.FPSMCore.getInstance().getMapByTypeWithName(getGameType(), getMapName()).orElse(null) == this
                && com.cdp.codpattern.app.match.management.MapDeletionCoordinator.get(getServerLevel().getServer()).blocks(roomId))
            throw new IllegalStateException("Map deletion is pending");
        matchEndTeleportPoint = Optional.ofNullable(data);
    }

    @Override
    public ZombiesMap getMap() {
        return this;
    }

    public Optional<SpawnPointData> matchEndTeleportPoint() {
        return matchEndTeleportPoint;
    }

    public ZombiesMapObjects objects() {
        return objects;
    }

    public void applyObjects(ZombiesMapObjects objects) {
        this.objects = objects == null ? ZombiesMapObjects.EMPTY : objects;
        if (objectsFrozen) {
            return;
        }
        resetObjectRuntime();
        syncConfiguredInitialSpawnsToTeam();
    }

    private void syncConfiguredInitialSpawnsToTeam() {
        getMapTeams().getTeamByName(ZombiesTeamNames.SURVIVORS).ifPresent(team -> {
            team.resetSpawnPointData(SpawnPointKind.INITIAL);
            runtimeInitialSpawnPoints().forEach(team::addSpawnPointData);
            team.clearPlayerSpawnPointAssignments();
            if (isStart) {
                team.assignNextSpawnPoints(SpawnPointKind.INITIAL);
            }
        });
    }

    public List<ZombiesInitialSpawnData> initialSpawns() {
        return objects.initialSpawns();
    }

    public List<ZombiesZombieSpawnData> zombieSpawns() {
        return objects.zombieSpawns();
    }

    public List<ZombiesBarrierData> barriers() {
        return objects.barriers();
    }

    public ZombiesGamePhase currentPhase() {
        return runtimeState.phase();
    }

    public List<ZombiesBarrierData> runtimeBarrierSnapshot() {
        return List.copyOf(runtimeBarriers());
    }

    public void clearBarrierBlockResidue() {
        scanAndClearBarrierBlockResidue();
    }

    public boolean isRuntimeBarrierCleared(ZombiesBarrierData barrier) {
        return objectStateStore.isBarrierCleared(barrier);
    }

    public boolean isAliveSurvivor(UUID playerId) {
        return hasSurvivor(playerId) && playerStateService.canInteract(playerId);
    }

    private void syncBarrierVisuals() {
        for (ServerPlayer player : survivorPlayers()) {
            syncBarrierVisual(player);
        }
    }

    private void syncBuffRuntimeEffects() {
        for (ServerPlayer player : survivorPlayers()) {
            buffRuntimeEffectService.syncPlayer(player);
        }
    }

    private void tickCombatRegen() {
        CombatRegenService.tick(
                combatRegenCooldowns,
                survivorPlayers(),
                Set.of(),
                runtimeState.phase().isRoundRunning(),
                COMBAT_REGEN_HALF_HEARTS_PER_SECOND);
    }

    private void syncBarrierVisual(ServerPlayer player) {
        barrierVisualService.syncPlayer(
                player,
                roomId,
                runtimeState.phase(),
                runtimeBarriers(),
                objectStateStore::isBarrierCleared);
    }

    private void clearBarrierVisuals() {
        barrierVisualService.clearRoom(survivorPlayers(), roomId);
    }

    private List<ZombiesBarrierData> runtimeBarriers() {
        return runtimeObjects().barriers();
    }

    private List<ZombiesWeaponWallData> runtimeWeaponWalls() {
        return runtimeObjects().weaponWalls();
    }

    private List<ZombiesAmmoBoxData> runtimeAmmoBoxes() {
        return runtimeObjects().ammoBoxes();
    }

    private List<ZombiesArmorStationData> runtimeArmorStations() {
        return runtimeObjects().armorStations();
    }

    private List<com.cdp.codpattern.app.zombies.map.object.ZombiesSodaMachineData> runtimeSodaMachines() {
        return runtimeObjects().sodaMachines();
    }

    private List<com.cdp.codpattern.app.zombies.map.object.ZombiesUltimateMachineData> runtimeUltimateMachines() {
        return runtimeObjects().ultimateMachines();
    }

    private List<ZombiesMysteryBoxData> runtimeMysteryBoxes() {
        return runtimeObjects().mysteryBoxes();
    }

    private void loadStartupConfigs(MinecraftServer server) {
        if (server == null) {
            serverConfig = ZombiesServerConfig.defaults(getMapName());
            rulesConfig = new ZombiesRulesConfig();
            rulesConfig.normalize();
            mysteryBoxConfig = ZombiesMysteryBoxConfig.defaults();
            ZombiesMysteryBoxRepository.setConfig(mysteryBoxConfig);
            rulesValidationIssues = List.of();
            return;
        }
        String mapName = getMapName();
        ZombiesConfigRepository.LoadResult loaded = ZombiesConfigRepository.loadResult(server, mapName);
        serverConfig = loaded.serverConfig();
        rulesConfig = serverConfig.legacyRulesConfig();
        rulesValidationIssues = List.copyOf(serverConfig.validationIssues());
        mysteryBoxConfig = serverConfig.getMysteryBox();
        new ZombiesWaveConfigRepository(
                ZombiesConfigPaths.zombiesMapWaves(server, mapName),
                rulesConfig().getDefaults()).load();
    }

    private void loadWaveTextConfig(MinecraftServer server) {
        if (server == null) {
            waveTextScheduler.replaceDefinitions(List.of());
            return;
        }
        ZombiesWaveTextRepository.LoadResult loadResult = new ZombiesWaveTextRepository(
                ZombiesConfigPaths.zombiesMapWaveText(server, getMapName())).load();
        waveTextScheduler.replaceDefinitions(loadResult.definitions());
    }

    private void sendWaveText(String text) {
        Component message = ZombiesLegacyTextFormatter.parse(text);
        for (ServerPlayer player : survivorPlayers()) {
            player.sendSystemMessage(message);
        }
    }

    private ZombiesRulesConfig rulesConfig() {
        if (rulesConfig == null) {
            rulesConfig = new ZombiesRulesConfig();
            rulesConfig.normalize();
        }
        return rulesConfig;
    }

    /** The complete map-scoped v1 configuration used by this running map. */
    public ZombiesServerConfig serverConfig() {
        if (serverConfig == null) serverConfig = ZombiesServerConfig.defaults(getMapName());
        return serverConfig;
    }

    public ZombiesServerConfig getServerConfig() {
        return serverConfig();
    }

    private ZombiesBackpackConfig backpackConfig() {
        return serverConfig().getBackpack();
    }

    private ZombiesMysteryBoxConfig mysteryBoxConfig() {
        if (mysteryBoxConfig == null) mysteryBoxConfig = ZombiesMysteryBoxConfig.defaults();
        return mysteryBoxConfig;
    }

    private List<ZombiesValidationIssue> rulesValidationIssues() {
        List<ZombiesValidationIssue> issues = new java.util.ArrayList<>(
                rulesValidationIssues == null ? List.of() : rulesValidationIssues);
        issues.addAll(serverConfig().getBarrierGroups().bindingIssues(objects.barriers(), objects.zombieSpawns(), objects.initialSpawns()));
        return List.copyOf(issues);
    }

    /** Deployment reads fresh rules only outside a match; live views use the frozen startup bundle. */
    public com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig barrierGroupRulesForPreview() {
        if (objectsFrozen) return frozenBarrierGroupRules;
        if (getServerLevel() == null || getServerLevel().getServer() == null) {
            return serverConfig().getBarrierGroups();
        }
        return com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig.load(
                ZombiesConfigPaths.zombiesMapBarrierGroups(getServerLevel().getServer(), getMapName()));
    }

    private ZombiesMapSnapshot currentMapSnapshot() {
        return ZombiesMapSnapshot.fromMapObjects(
                roomId,
                getMapName(),
                matchEndTeleportPoint.isPresent(),
                getServerLevel().dimension().location().toString(),
                ZombiesMapSnapshot.BoundsSnapshot.fromAreaData(getMapArea()),
                serverConfig().getBarrierGroups().resolveObjects(objects));
    }

    private List<SpawnPointData> initialSpawnPoints() {
        return ZombiesSpawnAssignmentService.activePlayerSpawnPoints(initialSpawns(),
                Set.of(ZombiesActiveSpawnGroupService.INITIAL_SPAWN_GROUP));
    }

    private List<SpawnPointData> runtimeInitialSpawnPoints() {
        return ZombiesSpawnAssignmentService.activePlayerSpawnPoints(runtimeObjects().initialSpawns(),
                activePlayerSpawnGroupService.snapshot());
    }

    private com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig runtimeBarrierGroupRules() {
        return objectsFrozen ? frozenBarrierGroupRules : serverConfig().getBarrierGroups();
    }

    private ZombiesMapObjects runtimeObjects() {
        return objectsFrozen ? frozenObjects : serverConfig().getBarrierGroups().resolveObjects(objects);
    }

    private List<UUID> normalizeStartMembers(Collection<UUID> memberSnapshot) {
        Set<UUID> requestedMembers = new LinkedHashSet<>();
        if (memberSnapshot != null) {
            memberSnapshot.stream()
                    .filter(java.util.Objects::nonNull)
                    .forEach(requestedMembers::add);
        }
        if (requestedMembers.isEmpty()) {
            requestedMembers.addAll(survivorPlayerIdList());
        }

        List<UUID> orderedMembers = new ArrayList<>();
        for (UUID playerId : survivorPlayerIdList()) {
            if (requestedMembers.contains(playerId)) {
                orderedMembers.add(playerId);
            }
        }
        for (UUID playerId : requestedMembers) {
            if (hasSurvivor(playerId) && !orderedMembers.contains(playerId)) {
                orderedMembers.add(playerId);
            }
        }
        return List.copyOf(orderedMembers);
    }

    private ZombiesPhaseStateMachine.FailureCheckResult failurePriority(ZombiesRoomRuntimeState state) {
        if (state == null || !state.phase().isRoundRunning()) {
            return ZombiesPhaseStateMachine.FailureCheckResult.none();
        }
        boolean hasAlivePlayer = playerStateService.hasAnyOnlineAlive();
        return hasAlivePlayer
                ? ZombiesPhaseStateMachine.FailureCheckResult.none()
                : ZombiesPhaseStateMachine.FailureCheckResult.failed(ZombiesErrorCode.PLAYER_DEAD);
    }

    private ZombiesPhaseStateMachine.Config lifecycleConfig() {
        ZombiesRulesConfig.Room room = rulesConfig().getRoom();
        return ZombiesPhaseStateMachine.Config.fromSeconds(
                ZombiesPhaseStateMachine.DEFAULT_OPENING_COUNTDOWN_SECONDS,
                room.getIntermissionSeconds(),
                room.getFailDelaySeconds());
    }

    private long configuredOfflineGraceTicks() {
        ZombiesRulesConfig.Room room = rulesConfig().getRoom();
        return (long) room.getOfflineGraceSeconds() * ZombiesPhaseStateMachine.TICKS_PER_SECOND;
    }

    private int voteTimeoutTicks() {
        ZombiesRulesConfig.Room room = rulesConfig().getRoom();
        return room.getStartVoteTimeoutSeconds() * ZombiesPhaseStateMachine.TICKS_PER_SECOND;
    }

    private int voteRequiredPercent() {
        return rulesConfig().getRoom().getStartVoteRequiredPercent();
    }

    private ServerLevel levelForDimension(ResourceKey<Level> dimension) {
        MinecraftServer server = getServerLevel().getServer();
        return server == null || dimension == null ? null : server.getLevel(dimension);
    }

    private void runCleanup(String reason) {
        termination().finishNormally(this);
    }

    private void reconcileActiveMobCounter() {
        mobSpawnService.reconcileActiveZombies(
                ModeEntityOwnershipRegistry.instance().entries(),
                this::levelForDimension);
    }

    private Optional<ZombiesPostGameTeleportService.TeleportTarget> endTeleportTarget() {
        return matchEndTeleportPoint.flatMap(runtimeMarkerService::targetFromSpawnPoint);
    }

    private void markActiveRoundPlayers(Collection<UUID> memberIds) {
        if (memberIds == null) {
            return;
        }
        MinecraftServer server = getServerLevel().getServer();
        if (server == null) {
            return;
        }
        Optional<ZombiesPostGameTeleportService.TeleportTarget> endTeleport = endTeleportTarget();
        for (UUID playerId : memberIds) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                runtimeMarkerService.writeActiveRoundMarker(player, roomId, endTeleport);
            }
        }
    }

    private void initializeStarterWeaponStates(
            Optional<ZombiesStarterKitDistributor.PreparedStarterKits> starterKits
    ) {
        if (starterKits == null || starterKits.isEmpty()) {
            return;
        }
        ZombiesStarterKitDistributor.PreparedStarterKits kits = starterKits.get();
        for (UUID playerId : kits.playerIds()) {
            kits.starterWeaponState(playerId)
                    .ifPresent(state -> playerStateService.getOrCreate(playerId).setStarterWeapon(state));
        }
    }

    private void clearActiveRoundPlayerMarkers(Collection<UUID> memberIds) {
        if (memberIds == null) {
            return;
        }
        MinecraftServer server = getServerLevel().getServer();
        if (server == null) {
            return;
        }
        for (UUID playerId : memberIds) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                runtimeMarkerService.clearMarker(player);
            }
        }
    }

    private void resetObjectRuntime() {
        resetObjectRuntime(1, runtimeState.waveState().maxWave());
    }

    private void resetObjectRuntime(int currentWave, int maxWave) {
        activeSpawnGroupService.resetToInitial();
        activePlayerSpawnGroupService.resetToInitial();
        powerService.reset();
        objectInteractionService.resetMysteryBoxRuntime();
        objectStateStore.resetObjects(
                runtimeObjects().barriers(),
                runtimeObjects().weaponWalls(),
                runtimeObjects().ammoBoxes(),
                runtimeObjects().armorStations(),
                runtimeObjects().powerSwitch(),
                runtimeObjects().sodaMachines(),
                runtimeObjects().ultimateMachines(),
                runtimeObjects().mysteryBoxes(),
                currentWave,
                maxWave);
    }

    private void placeRuntimeBarrierBlocks() {
        barrierBlockRuntimeService.placeActiveBarriers(
                roomId,
                runtimeBarriers(),
                objectStateStore::isBarrierCleared,
                this::levelForDimension);
    }

    private void clearRuntimeBarrierBlocks() {
        barrierBlockRuntimeService.clearRoom(roomId, this::levelForDimension);
    }

    private void scanAndClearBarrierBlockResidue() {
        barrierBlockRuntimeService.scanAndClearRoomResidue(
                roomId,
                objects.barriers(),
                this::levelForDimension);
    }

    private void freezeObjectsForRuntime(int maxWave) {
        frozenBarrierGroupRules = serverConfig().getBarrierGroups();
        frozenObjects = frozenBarrierGroupRules.resolveObjects(objects);
        objectsFrozen = true;
        resetObjectRuntime(1, maxWave);
        syncConfiguredInitialSpawnsToTeam();
    }

    private void clearFrozenObjectsAndResetRuntime() {
        waveTextScheduler.cancel();
        if (objectsFrozen) {
            powerService.reset();
        }
        frozenObjects = ZombiesMapObjects.EMPTY;
        frozenBarrierGroupRules = com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig.empty();
        objectsFrozen = false;
        mobRecycleService.reset();
        mobSpawnService.resetNavigationRuntime();
        resetObjectRuntime();
        syncConfiguredInitialSpawnsToTeam();
    }

    private void resetRuntimeForWaiting() {
        getMapTeams().removeOfflinePlayers();
        isStart = false;
        waveDirector = null;
        combatRegenCooldowns.clear();
        clearFrozenObjectsAndResetRuntime();
        lifecycleRuntime.resetToWaiting();
        playerStateService.clear();
        readyService.clear();
        for (ServerPlayer player : survivorPlayers()) {
            playerStateService.markAlive(player.getUUID());
            connectionStateService.markOnline(player.getUUID());
            readyService.initializeReadyState(player);
        }
    }

    private void resetStartupRuntime(Collection<UUID> memberIds) {
        isStart = false;
        waveDirector = null;
        combatRegenCooldowns.clear();
        clearFrozenObjectsAndResetRuntime();
        lifecycleRuntime.cancelStartVote();
        playerStateService.clear();
        clearActiveRoundPlayerMarkers(memberIds);
        for (ServerPlayer player : survivorPlayers()) {
            playerStateService.markAlive(player.getUUID());
            connectionStateService.markOnline(player.getUUID());
        }
    }

    private Map<UUID, StartupPlayerPosition> captureStartupPositions(Collection<UUID> memberIds) {
        Map<UUID, StartupPlayerPosition> positions = new LinkedHashMap<>();
        if (memberIds == null) {
            return positions;
        }
        for (UUID playerId : memberIds) {
            ServerPlayer player = getServerLevel().getServer().getPlayerList().getPlayer(playerId);
            if (player != null) {
                positions.put(playerId, StartupPlayerPosition.capture(player));
            }
        }
        return positions;
    }

    private void restoreStartupPositions(Map<UUID, StartupPlayerPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, StartupPlayerPosition> entry : positions.entrySet()) {
            ServerPlayer player = getServerLevel().getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                entry.getValue().restore(player);
            }
        }
    }

    private void clearStartupInventories(Collection<UUID> memberIds) {
        if (memberIds == null) {
            return;
        }
        for (UUID playerId : memberIds) {
            ServerPlayer player = getServerLevel().getServer().getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            player.getInventory().clearContent();
            ThrowableInventoryService.clearRuntime(player, true);
            player.inventoryMenu.broadcastChanges();
            player.inventoryMenu.slotsChanged(player.getInventory());
            ThrowableInventoryService.sync(player);
        }
    }

    private void restoreStartupHealth(Collection<UUID> memberIds) {
        if (memberIds == null) {
            return;
        }
        for (UUID playerId : memberIds) {
            if (playerId == null) {
                continue;
            }
            ServerPlayer player = getServerLevel().getServer().getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            CombatRegenService.clearPlayerCooldown(combatRegenCooldowns, playerId);
            player.setHealth(player.getMaxHealth());
        }
    }

    private void reviveIntermissionDeadSpectators() {
        List<UUID> members = survivorPlayerIdList();
        ZombiesServiceResult<ZombiesIntermissionRespawnService.IntermissionRespawnDecision> decisionResult =
                intermissionRespawnService.selectRespawnCandidates(
                        members,
                        runtimeState.roomTick(),
                        connectionStateService.offlineGraceTicks());
        if (!decisionResult.success() || decisionResult.value().isEmpty()) {
            return;
        }

        ZombiesIntermissionRespawnService.IntermissionRespawnDecision decision = decisionResult.value().get();
        if (!decision.shouldRespawnAny()) {
            return;
        }

        ZombiesSpawnAssignmentService spawnAssignmentService = new ZombiesSpawnAssignmentService();
        ZombiesServiceResult<ZombiesSpawnAssignmentService.ZombiesSpawnAssignmentPlan> planResult =
                spawnAssignmentService.assignFromInitialSpawns(runtimeInitialSpawnPoints(), decision.memberIds());
        if (!planResult.success() || planResult.value().isEmpty()) {
            return;
        }

        Set<UUID> respawnPlayerIds = onlineDeadSpectatorIds(decision.respawnPlayerIds());
        if (respawnPlayerIds.isEmpty()) {
            return;
        }
        List<ZombiesSpawnAssignmentService.ZombiesSpawnAssignment> respawnAssignments = planResult.value().get()
                .assignments()
                .stream()
                .filter(assignment -> respawnPlayerIds.contains(assignment.playerId()))
                .toList();
        if (respawnAssignments.isEmpty()) {
            return;
        }

        ZombiesServiceResult<ZombiesSpawnAssignmentService.ZombiesSpawnTeleportSummary> teleportResult =
                spawnAssignmentService.executeTeleport(this, respawnAssignments);
        if (teleportResult.value().isEmpty()) {
            return;
        }

        boolean revivedAny = false;
        for (ZombiesSpawnAssignmentService.ZombiesSpawnTeleportAttempt attempt : teleportResult.value().get().attempts()) {
            if (!attempt.success()) {
                continue;
            }
            ServerPlayer player = getServerLevel().getServer().getPlayerList().getPlayer(attempt.playerId());
            ZombiesServiceResult<ZombiesIntermissionRespawnService.IntermissionRespawnStateChange> stateResult =
                    intermissionRespawnService.prepareStateForRespawn(attempt.playerId());
            if (!stateResult.success()) {
                keepDeadSpectating(player);
                continue;
            }
            ZombiesServiceResult<ZombiesReviveLoadoutService.ReviveLoadoutRestoreResult> loadoutResult =
                    reviveLoadoutService.restoreDeathSnapshot(
                            roomId,
                            player,
                            playerStateService.getOrCreate(attempt.playerId()));
            if (!loadoutResult.success()) {
                playerStateService.markDeadSpectating(attempt.playerId());
                keepDeadSpectating(player);
                continue;
            }
            restoreRespawnedPlayer(player);
            revivedAny = true;
        }
        if (revivedAny) {
            markRosterDirty();
        }
    }

    private Set<UUID> onlineDeadSpectatorIds(List<UUID> playerIds) {
        Set<UUID> spectatorIds = new LinkedHashSet<>();
        if (playerIds == null || playerIds.isEmpty()) {
            return spectatorIds;
        }
        for (UUID playerId : playerIds) {
            ServerPlayer player = getServerLevel().getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                spectatorIds.add(playerId);
            }
        }
        return spectatorIds;
    }

    private void restoreRespawnedPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        player.setGameMode(GameType.ADVENTURE);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        buffRuntimeEffectService.syncPlayer(player);
        player.inventoryMenu.broadcastChanges();
        player.inventoryMenu.slotsChanged(player.getInventory());
        ThrowableInventoryService.sync(player);
    }

    private static void keepDeadSpectating(ServerPlayer player) {
        if (player != null && !player.gameMode.getGameModeForPlayer().isCreative()) {
            player.setGameMode(GameType.SPECTATOR);
        }
    }

    private final class ZombiesReadyHooks implements ZombiesReadyService.Hooks {
        @Override
        public boolean isWaitingPhase() {
            return !recoveryBlocked() && runtimeState.phase() == ZombiesGamePhase.WAITING;
        }

        @Override
        public void markRoomListDirty() {
            markRosterDirty();
        }
    }

    private final class ZombiesStartVoteHooks implements ZombiesStartVoteService.Hooks {
        @Override
        public Collection<UUID> currentMembers() {
            return onlineSurvivorPlayerIds();
        }

        @Override
        public boolean isWaitingPhase() {
            return !recoveryBlocked() && runtimeState.phase() == ZombiesGamePhase.WAITING;
        }

        @Override
        public boolean isPlayerReady(UUID playerId) {
            return readyService.isPlayerReady(playerId);
        }

        @Override
        public int minPlayersToStart() {
            return 1;
        }

        @Override
        public int votePercentageToStart() {
            return voteRequiredPercent();
        }

        @Override
        public int voteTimeoutTicks() {
            return ZombiesMap.this.voteTimeoutTicks();
        }

        @Override
        public void onVoteStarted(ZombiesStartVoteService.VoteSnapshot snapshot) {
            beginAttempt(snapshot.members());
            lifecycleRuntime.beginStartVote();
            sendVoteDialog(snapshot);
        }

        @Override
        public void onVotePassed(ZombiesStartVoteService.VoteSnapshot snapshot) {
            startGame(snapshot == null ? survivorPlayerIds() : snapshot.members());
        }

        @Override
        public void onVoteStartRejected(UUID initiator, ZombiesStartVoteService.FailureReason reason) {
            notifyPlayer(initiator, Component.translatable(
                    "message.codpattern.zombies.vote.start_rejected",
                    voteFailureReasonMessage(reason)));
        }

        @Override
        public void onVoteFailed(ZombiesStartVoteService.VoteSnapshot snapshot, ZombiesStartVoteService.FailureReason reason) {
            notifySurvivors(Component.translatable(
                    "message.codpattern.zombies.vote.failed",
                    voteFailureReasonMessage(reason)));
            clearFrozenObjectsAndResetRuntime();
            lifecycleRuntime.cancelStartVote();
        }

        @Override
        public void markRoomListDirty() {
            ZombiesMap.this.markRoomListDirty();
        }

        private void sendVoteDialog(ZombiesStartVoteService.VoteSnapshot snapshot) {
            if (snapshot == null) {
                return;
            }
            String initiatorName = Optional.ofNullable(getServerLevel().getPlayerByUUID(snapshot.initiator()))
                    .map(player -> player.getName().getString())
                    .orElse("");
            VoteDialogPacket packet = new VoteDialogPacket(
                    getMapName(),
                    snapshot.voteId(),
                    "START",
                    initiatorName,
                    snapshot.requiredVotes(),
                    snapshot.totalMembers());
            for (ServerPlayer player : survivorPlayers()) {
                if (snapshot.members().contains(player.getUUID())) {
                    ModNetworkChannel.sendToPlayer(packet, player);
                }
            }
        }
    }

    private final class ZombiesStartupMapParticipant implements ZombiesStartupFlow.ZombiesStartupParticipant {
        @Override
        public String name() {
            return "zombies_map_startup";
        }

        @Override
        public ZombiesServiceResult<Optional<ZombiesStartupFlow.ZombiesStartupRollbackAction>> onStartupStage(
                ZombiesStartupFlow.ParticipantStage stage,
                ZombiesStartupFlow.ZombiesStartupContext context
        ) {
            if (stage == ZombiesStartupFlow.ParticipantStage.BEFORE_OCCUPANCY_ACQUIRE) {
                if (recoveryBlocked()) throw new IllegalStateException("Terminated match cannot start");
                for (UUID id : context.memberIds()) {
                    var player = getServerLevel().getServer().getPlayerList().getPlayer(id);
                    if (player != null) {
                        termination().registerPersistentTag(player, "codpattern.zombies");
                    }
                }
                scanAndClearBarrierBlockResidue();
                return ZombiesServiceResult.success(Optional.empty());
            }
            if (stage == ZombiesStartupFlow.ParticipantStage.BEFORE_STARTER_KIT_APPLY) {
                for (UUID id : context.memberIds()) {
                    var player = getServerLevel().getServer().getPlayerList().getPlayer(id);
                    if (player != null) termination().registerRoundInventory(player);
                }
                return ZombiesServiceResult.success(Optional.empty());
            }
            if (stage == ZombiesStartupFlow.ParticipantStage.AFTER_OCCUPANCY_ACQUIRED) {
                isStart = true;
                playerStateService.registerPlayers(context.memberIds());
                context.memberIds().forEach(playerId -> {
                    Optional.ofNullable(getServerLevel().getPlayerByUUID(playerId))
                            .ifPresent(player -> playerStateService.recordPlayerName(
                                    playerId,
                                    player.getName().getString()));
                    playerStateService.markAlive(playerId);
                    connectionStateService.markOnline(playerId);
                });
                return ZombiesServiceResult.success(Optional.of(new StartupRollbackAction(
                        "reset_startup_runtime",
                        ignored -> {
                            resetStartupRuntime(context.memberIds());
                            return ZombiesServiceResult.ok();
                        })));
            }
            if (stage == ZombiesStartupFlow.ParticipantStage.BEFORE_TELEPORT) {
                Map<UUID, StartupPlayerPosition> positions = captureStartupPositions(context.memberIds());
                freezeObjectsForRuntime(context.preflightSnapshot()
                        .map(ZombiesStartupPreflightSnapshot::maxWave)
                        .orElse(1));
                ZombiesServiceResult<ZombiesBarrierBlockRuntimeService.PreflightSummary> barrierPreflight =
                        barrierBlockRuntimeService.validateFillTargets(
                                roomId,
                                runtimeBarriers(),
                                objectStateStore::isBarrierCleared,
                                ZombiesMap.this::levelForDimension);
                if (!barrierPreflight.success()) {
                    clearFrozenObjectsAndResetRuntime();
                    return ZombiesServiceResult.failure(
                            barrierPreflight.code(),
                            barrierPreflight.params(),
                            barrierPreflight.logMessage());
                }
                placeRuntimeBarrierBlocks();
                ZombiesPurchasableBlockService.PlacementChanges purchasePointChanges;
                try {
                    purchasePointChanges = ZombiesPurchasableBlockService.ensureMissingObjects(
                            runtimeObjects(), ZombiesMap.this::levelForDimension);
                } catch (RuntimeException failure) {
                    clearRuntimeBarrierBlocks();
                    clearFrozenObjectsAndResetRuntime();
                    throw failure;
                }
                return ZombiesServiceResult.success(Optional.of(new StartupRollbackAction(
                        "restore_startup_positions_and_barrier_blocks",
                        ignored -> {
                            clearRuntimeBarrierBlocks();
                            purchasePointChanges.rollback();
                            clearFrozenObjectsAndResetRuntime();
                            restoreStartupPositions(positions);
                            return ZombiesServiceResult.ok();
                        })));
            }
            if (stage == ZombiesStartupFlow.ParticipantStage.AFTER_STARTER_KIT_APPLIED) {
                return ZombiesServiceResult.success(Optional.of(new StartupRollbackAction(
                        "clear_startup_inventories",
                        ignored -> {
                            clearStartupInventories(context.memberIds());
                            return ZombiesServiceResult.ok();
                        })));
            }
            if (stage == ZombiesStartupFlow.ParticipantStage.COMPLETE_STARTUP) {
                if (context.preflightSnapshot().isEmpty()) {
                    return ZombiesServiceResult.failure(ZombiesErrorCode.STARTUP_PREFLIGHT_FAILED);
                }
                ZombiesCrashRecoveryService.instance().cleanupResidualTaggedEntitiesForRoom(
                        getServerLevel().getServer(),
                        roomId);
                ZombiesStartupFlow.ZombiesStartupContext startupContext = context;
                waveDirector = new ZombiesWaveDirector(
                        startupContext.preflightSnapshot().get().waveLoadResult(),
                        mobSpawnService);
                initializeStarterWeaponStates(startupContext.starterKits());
                restoreStartupHealth(startupContext.memberIds());
                markActiveRoundPlayers(startupContext.memberIds());
                lifecycleRuntime.beginOpeningCountdown(startupContext.preflightSnapshot().get().maxWave());
                markRoomListDirty();
            }
            return ZombiesServiceResult.success(Optional.empty());
        }
    }

    private record StartupRollbackAction(
            String name,
            RollbackHandler handler
    ) implements ZombiesStartupFlow.ZombiesStartupRollbackAction {
        @Override
        public ZombiesServiceResult<Void> rollback(ZombiesStartupFlow.ZombiesStartupRollbackContext context) {
            return handler.rollback(context);
        }
    }

    @FunctionalInterface
    private interface RollbackHandler {
        ZombiesServiceResult<Void> rollback(ZombiesStartupFlow.ZombiesStartupRollbackContext context);
    }

    private record StartupPlayerPosition(
            ServerLevel level,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            GameType gameType
    ) {
        private static StartupPlayerPosition capture(ServerPlayer player) {
            return new StartupPlayerPosition(
                    player.serverLevel(),
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYRot(),
                    player.getXRot(),
                    player.gameMode.getGameModeForPlayer());
        }

        private void restore(ServerPlayer player) {
            player.teleportTo(level, x, y, z, yaw, pitch);
            player.setGameMode(gameType == null ? GameType.ADVENTURE : gameType);
        }
    }

    private final class ZombiesDeathHooks implements ZombiesDeathService.Hooks {
        @Override
        public int activeMemberCount() {
            return survivorPlayerIds().size();
        }

        @Override
        public void captureDeathEquipment(ServerPlayer victim) {
            if (victim == null) {
                return;
            }
            equipmentSnapshotService.captureDeathEquipment(
                    roomId,
                    victim,
                    playerStateService.getOrCreate(victim.getUUID()));
        }

        @Override
        public void onRoundFailed(String reason) {
            lifecycleRuntime.fail(ZombiesErrorCode.PLAYER_DEAD);
            markRoomListDirty();
        }
    }

    private final class ZombiesCleanupHooks implements ZombiesCleanupService.Hooks {
        @Override
        public void beforeCleanup(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
            clearRuntimeBarrierBlocks();
            clearBarrierVisuals();
        }

        @Override
        public void clearPlayerRuntime(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
            resetRuntimeForWaiting();
        }

        @Override
        public void clearReadyState(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
            readyService.clear();
            for (ServerPlayer player : survivorPlayers()) {
                readyService.initializeReadyState(player);
            }
        }

        @Override
        public void clearStartVote(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
            startVoteService.clearActiveVoteSession();
        }

        @Override
        public void clearLifecycleRuntime(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
            lifecycleRuntime.resetToWaiting();
        }

        @Override
        public void onEntityCleanup(Entity entity) {
            mobLifecycleService.onCleanup(roomId, entity, runtimeState.waveState());
        }

        @Override
        public void onMissingEntityCleanup(ModeEntityOwnershipRegistry.Entry entry) {
            if (entry != null) {
                mobLifecycleService.onMissing(
                        roomId,
                        entry.entityId(),
                        runtimeState.waveState(),
                        ZombiesMobLifecycleService.TerminationReason.CLEANUP);
            }
        }

        @Override
        public void afterCleanup(ZombiesCleanupParticipant.ZombiesCleanupContext context) {
            markRosterDirty();
        }
    }

    private final class ZombiesLifecycleRuntimeHooks implements com.cdp.codpattern.app.zombies.runtime.ZombiesLifecycleHooks {
        @Override
        public ZombiesServiceResult<Void> onEnter(com.cdp.codpattern.app.zombies.runtime.ZombiesPhaseTransitionContext context) {
            if (recoveryBlocked()) return ZombiesServiceResult.ok();
            if (runtimeState.phase() == ZombiesGamePhase.INTERMISSION) {
                objectStateStore.refreshWeaponWallOffersForWave(
                        runtimeObjects().weaponWalls(),
                        runtimeState.waveState().targetWave(),
                        runtimeState.waveState().maxWave());
                reviveIntermissionDeadSpectators();
                playWaveIntro();
                waveTextScheduler.startWave(runtimeState.waveState().targetWave(), ZombiesMap.this::sendWaveText);
            }
            if (runtimeState.phase() == ZombiesGamePhase.WAVE_ACTIVE && waveDirector != null) {
                waveDirector.enterTargetWave(runtimeState.waveState());
            }
            return ZombiesServiceResult.ok();
        }

        @Override
        public ZombiesServiceResult<Void> onTick(com.cdp.codpattern.app.zombies.runtime.ZombiesPhaseTransitionContext context) {
            if (recoveryBlocked()) return ZombiesServiceResult.ok();
            for (ServerPlayer player : survivorPlayers()) {
                playerStateService.updateLastAliveTargetPos(player.getUUID(), player.blockPosition());
            }
            List<UUID> timedOutPlayers = connectionStateService.applyOfflineGraceTimeouts(runtimeState.roomTick());
            if (!timedOutPlayers.isEmpty()) {
                markRosterDirty();
            }
            if (runtimeState.phase() == ZombiesGamePhase.INTERMISSION
                    || runtimeState.phase() == ZombiesGamePhase.WAVE_ACTIVE) {
                waveTextScheduler.tick(ZombiesMap.this::sendWaveText);
            }
            if (runtimeState.phase() == ZombiesGamePhase.WAVE_ACTIVE && waveDirector != null) {
                mobRecycleService.tick(
                        roomId,
                        getServerLevel(),
                        runtimeState.waveState(),
                        runtimeState.roomTick());
                waveDirector.tick(
                        roomId,
                        getServerLevel(),
                        runtimeObjects(),
                        runtimeState.waveState(),
                        runtimeState.roomTick(),
                        activeSpawnGroupService.snapshot());
            }
            return ZombiesServiceResult.ok();
        }

        @Override
        public ZombiesServiceResult<Void> onCleanup(com.cdp.codpattern.app.zombies.runtime.ZombiesPhaseTransitionContext context) {
            runCleanup(context.previousPhase().isBlank() ? "phase_cleanup" : context.previousPhase());
            return ZombiesServiceResult.ok();
        }
    }
}
