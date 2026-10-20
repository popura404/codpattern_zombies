package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.adapter.forge.network.ModNetworkChannel;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.model.ZombiesConnectionState;
import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSlot;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.model.ZombiesPlayerRuntimeState;
import com.cdp.codpattern.app.zombies.model.ZombiesTeamNames;
import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;
import com.cdp.codpattern.app.zombies.runtime.ZombiesRoomRuntimeState;
import com.cdp.codpattern.app.zombies.service.ZombiesBaseGunAmmoService;
import com.cdp.codpattern.app.zombies.service.ZombiesLoginRecoveryContributor;
import com.cdp.codpattern.app.zombies.service.ZombiesPlayerRuntimeMarkerService;
import com.cdp.codpattern.app.zombies.service.ZombiesPlayerStateService;
import com.cdp.codpattern.app.zombies.service.ZombiesReconnectRecoveryService;
import com.cdp.codpattern.app.zombies.service.ZombiesReviveLoadoutService;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponInventoryService;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponItemStackService;
import com.cdp.codpattern.compat.fpsmatch.map.zombies.ZombiesMap;
import com.cdp.codpattern.fpsmatch.room.PlayerInfo;
import com.cdp.codpattern.network.match.TeamPlayerListPacket;
import com.mojang.authlib.GameProfile;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.tacz.guns.item.ModernKineticGunItem;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.network.NetworkDirection;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.EnumMap;
import java.util.UUID;

/** Real login recovery after the vanilla player NBT save/load boundary; no network login is simulated. */
@GameTestHolder("codpattern_backpack")
@PrefixGameTestTemplate(false)
public final class ZombiesReconnectAmmoGameTests {
    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void activeRoundLoginPreservesPersistedBaseAmmoAndOpaqueExtras(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var core = FPSMCore.getInstance();
        // The isolated GameTest bootstrap creates an empty FPSM registry, unlike ServerStartedEvent.
        if (!core.checkGameType("zombies")) core.registerGameType("zombies", ZombiesMap::new);
        String name = "ammo-reconnect-" + UUID.randomUUID().toString().substring(0, 8);
        RoomId room = RoomId.of("zombies", name);
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        var map = new ZombiesMap(level, name, new AreaData(pos, pos.offset(8, 8, 8)));
        core.registerMap("zombies", map);
        require(core.getMapByTypeWithName("zombies", name).orElseThrow() == map,
                "Actual login resolver can find the fixture's room");
        var profile = new GameProfile(UUID.randomUUID(), "AmmoReconnect");
        var original = new FakePlayer(level, profile);
        var returned = new FakePlayer(level, profile);
        var markers = ZombiesPlayerRuntimeMarkerService.instance();
        Throwable primaryFailure = null;
        try {
            var roomState = fixtureField(map, "runtimeState", ZombiesRoomRuntimeState.class);
            var players = fixtureField(map, "playerStateService", ZombiesPlayerStateService.class);
            roomState.transitionTo(ZombiesGamePhase.WAVE_ACTIVE);
            map.isStart = true;
            var runtime = players.getOrCreate(profile.getId());
            var staleFullState = new ZombiesWeaponInstanceState("tacz:ak47", "rare", 2, 1,
                    1.25D, 1.5D, 120, 120);
            runtime.setPrimaryWeapon(staleFullState);
            runtime.addPoints(321);
            var team = map.getMapTeams().getTeamByName(ZombiesTeamNames.SURVIVORS).orElseThrow();
            team.join(original);
            ItemStack gun = ZombiesWeaponInventoryService.createDefaultTaczGunStackForRules("tacz:ak47");
            require(ZombiesBaseGunAmmoService.initialize(gun, 120, 120).success(), "Actual new gun initializes");
            require(new ZombiesWeaponItemStackService().writeWeaponTags(gun, room, "persisted-primary",
                    ZombiesEquipmentSlot.PRIMARY, staleFullState).success(), "Actual management tags initialize");
            require(((ModernKineticGunItem) gun.getItem()).findAndExtractDummyAmmo(gun, 37) == 37,
                    "Actual TaCZ consumption leaves a partially spent root reserve");
            CompoundTag child = new CompoundTag();
            child.putInt("DummyAmmo", 5);
            child.putInt("MaxDummyAmmo", 23);
            child.putInt("GunCurrentAmmoCount", 1);
            CompoundTag extras = new CompoundTag();
            extras.put("UnderBarrelRoot", child);
            extras.putString("OpaqueReconnectFixture", "preserve-this-entire-compound");
            gun.getOrCreateTag().put("Extras", extras);
            original.getInventory().selected = ZombiesEquipmentSlot.PRIMARY.defaultInventorySlot();
            original.getInventory().setItem(original.getInventory().selected, gun);
            var expectedReserves = new EnumMap<ZombiesEquipmentSlot, Integer>(ZombiesEquipmentSlot.class);
            expectedReserves.put(ZombiesEquipmentSlot.PRIMARY, 83);
            for (var slot : ZombiesEquipmentSlot.values()) {
                if (slot == ZombiesEquipmentSlot.PRIMARY) continue;
                ItemStack other = gun.copy();
                int reserve = 11 + slot.ordinal() * 7;
                require(ZombiesBaseGunAmmoService.initialize(other, 120, 120).success(), "Other actual slot initializes");
                require(new ZombiesWeaponItemStackService().writeWeaponTags(other, room, "persisted-" + slot.key(),
                        slot, staleFullState).success(), "Other slot gets its own managed identity");
                require(((ModernKineticGunItem) other.getItem()).findAndExtractDummyAmmo(other, 120 - reserve) == 120 - reserve,
                        "Each same-id gun consumes a different amount");
                original.getInventory().setItem(slot.defaultInventorySlot(), other);
                setWeapon(runtime, slot, staleFullState);
                expectedReserves.put(slot, reserve);
            }
            markers.writeActiveRoundMarker(original, room, Optional.empty());

            map.onPlayerLoggedOut(original);
            require(runtime.connectionState() == ZombiesConnectionState.OFFLINE, "Real logout marks existing state offline");
            CompoundTag expectedItem = gun.save(new CompoundTag());
            ListTag expectedInventory = original.getInventory().save(new ListTag());
            CompoundTag persistedPlayer = original.saveWithoutId(new CompoundTag());
            require(!persistedPlayer.getList("Inventory", 10).isEmpty(), "Vanilla player serialization includes the weapon");
            returned.load(persistedPlayer);
            require(markers.readMarker(returned).filter(marker -> marker.isActiveRound()
                    && room.equals(marker.roomId())).isPresent(), "Forge persistent marker survives player NBT loading");
            require(returned.getMainHandItem() != gun
                            && returned.getMainHandItem().save(new CompoundTag()).equals(expectedItem),
                    "New player loads an independent, complete persisted item");
            // Force the recovery method to exercise survivor reattachment as well as markOnline.
            team.delPlayer(profile.getId());
            var result = ZombiesLoginRecoveryContributor.recover(returned);
            require(result.outcome() == ZombiesReconnectRecoveryService.RecoveryOutcome.ACTIVE_ROUND_REJOIN,
                    "Real contributor/resolver reaches the active room reconnect method: " + result.outcome());
            require(team.hasPlayer(profile.getId()) && runtime.connectionState() == ZombiesConnectionState.ONLINE,
                    "Returning player rejoins the actual survivor roster and existing runtime state");
            require(players.get(profile.getId()).orElseThrow() == runtime && runtime.points() == 321,
                    "Login keeps the existing player state and points");
            ItemStack restored = returned.getMainHandItem();
            require(restored.save(new CompoundTag()).equals(expectedItem),
                    "Login recovery preserves complete inventory item NBT, including opaque Arcana Extras");
            require(returned.getInventory().save(new ListTag()).equals(expectedInventory),
                    "Refreshing three runtime mirrors never rewrites any persisted inventory NBT");
            var reserve = ZombiesBaseGunAmmoService.readReserve(restored);
            require(reserve.valid() && reserve.current() == 83 && reserve.maximum() == 120,
                    "Stale full runtime reserve cannot refill a persisted partially spent gun");
            for (var slot : ZombiesEquipmentSlot.values()) {
                var expected = ZombiesBaseGunAmmoService.withReserve(staleFullState,
                        new ZombiesBaseGunAmmoService.ReserveAmmo(ZombiesBaseGunAmmoService.ReserveStatus.VALID,
                                expectedReserves.get(slot), 120));
                require(weapon(runtime, slot).equals(expected),
                        "Login rebuilds each same-id gun's own runtime reserve without crossing slots: " + slot);
            }
            var prepared = new ZombiesWeaponInventoryService().prepareAmmoRefill(room, restored).value().orElseThrow();
            require(prepared.reserve().current() == 83 && prepared.weaponState().reserveAmmo() == 83,
                    "The next real refill preparation reads persisted root reserve instead of the old cache");
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                markers.clearMarker(original);
                markers.clearMarker(returned);
                original.getInventory().clearContent();
                returned.getInventory().clearContent();
                map.getMapTeams().removePlayer(profile.getId());
                // The fixture may fail after delPlayer and before recovery rejoins the roster.
                level.getScoreboard().removePlayerFromTeam(original.getScoreboardName());
                core.unregisterMap(map);
                map.getMapTeams().retireCreatedScoreboardTeams();
            } catch (RuntimeException | Error cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
        System.out.println("[ZOMBIES-RECONNECT] PASS actual login recovery refreshed three slot mirrors; root reserve=83/120 and complete item NBT preserved");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void reconnectMirrorsRejectAmbiguousIdentityAndNeverRepairNativeTags(GameTestHelper helper) {
        RoomId room = RoomId.of("zombies", "reconnect-identity");
        var tags = new ZombiesWeaponItemStackService();
        for (int variant = 0; variant < 7; variant++) {
            var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MirrorIdentity"));
            var runtime = new ZombiesPlayerRuntimeState(player.getUUID());
            var stale = new ZombiesWeaponInstanceState("tacz:m4a1", "rare", 9, 3, 2.0D, 1.5D, 120, 120);
            var committed = new ZombiesWeaponInstanceState("tacz:ak47", "rare", 2, 1, 1.25D, 1.5D, 120, 120);
            try {
                for (var slot : ZombiesEquipmentSlot.values()) {
                    ItemStack stack = ZombiesWeaponInventoryService.createDefaultTaczGunStackForRules("tacz:ak47");
                    require(ZombiesBaseGunAmmoService.initialize(stack, 10 + slot.ordinal(), 120).success(),
                            "Identity fixture initializes a real gun");
                    require(tags.writeWeaponTags(stack, room, "identity-" + slot.key(), slot, committed).success(),
                            "Identity fixture writes committed item metadata");
                    player.getInventory().setItem(slot.defaultInventorySlot(), stack);
                    setWeapon(runtime, slot, stale);
                }
                ItemStack primary = player.getInventory().getItem(ZombiesEquipmentSlot.PRIMARY.defaultInventorySlot());
                switch (variant) {
                    case 0 -> primary.getOrCreateTag().putString(ZombiesWeaponItemStackService.TAG_ROOM_ID, "zombies:other");
                    case 1 -> primary.getOrCreateTag().putString("GunId", "tacz:glock_17");
                    case 2, 6 -> {
                        ItemStack duplicate = primary.copy();
                        duplicate.getOrCreateTag().putString(ZombiesWeaponItemStackService.TAG_INSTANCE_ID, "duplicate-instance");
                        if (variant == 6) duplicate.getOrCreateTag().putString("GunId", "tacz:glock_17");
                        player.getInventory().setItem(8, duplicate);
                    }
                    case 3 -> primary.getOrCreateTag().remove(ZombiesWeaponItemStackService.TAG_INSTANCE_ID);
                    case 4 -> primary.getOrCreateTag().remove("DummyAmmo");
                    case 5 -> primary.getOrCreateTag().putInt("DummyAmmo", 121);
                    default -> throw new AssertionError("Unexpected fixture variant");
                }
                ListTag before = player.getInventory().save(new ListTag());
                new ZombiesReviveLoadoutService().syncRuntimeStateFromInventory(room, player.getInventory(), runtime);
                require(player.getInventory().save(new ListTag()).equals(before),
                        "Mirror rebuild never repairs or changes actual item NBT: " + variant);
                for (var slot : ZombiesEquipmentSlot.values()) {
                    boolean rejectedPrimary = slot == ZombiesEquipmentSlot.PRIMARY && variant != 4 && variant != 5;
                    var expected = rejectedPrimary ? stale : ZombiesBaseGunAmmoService.withReserve(committed,
                            ZombiesBaseGunAmmoService.readReserve(player.getInventory().getItem(slot.defaultInventorySlot())));
                    require(weapon(runtime, slot).equals(expected),
                            "Only an unambiguous matching identity may replace its own cache: " + variant + "/" + slot);
                }
            } finally {
                player.getInventory().clearContent();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void reconnectPublishesFullRosterForAliveAndSpectatingPlayers(GameTestHelper helper) throws Exception {
        for (boolean spectating : new boolean[]{false, true}) {
            try (var fixture = new RosterFixture(helper)) {
                if (spectating) fixture.runtime.markDeadSpectating();
                fixture.markers.writeActiveRoundMarker(fixture.original, fixture.room, Optional.empty());
                fixture.map.onPlayerLoggedOut(fixture.original);
                fixture.returned.load(fixture.original.saveWithoutId(new CompoundTag()));
                fixture.registerOnline(fixture.returned);
                fixture.map.getMapTeams().removePlayer(fixture.original.getUUID());
                fixture.clearPackets();

                var result = ZombiesLoginRecoveryContributor.recover(fixture.returned);
                require(result.outcome() == ZombiesReconnectRecoveryService.RecoveryOutcome.ACTIVE_ROUND_REJOIN,
                        "The real login contributor accepts the same UUID's new player instance");
                fixture.assertReturningRoster(spectating, "active-round recovery");
                require(fixture.peerPackets.rosters.isEmpty(),
                        "Returning-player repair sends only to that connection, not other survivors");

                // The ordinary FPSM login hook can run after contributor recovery. It must also
                // independently bootstrap a cleared client, even when the roster did not change.
                fixture.clearPackets();
                fixture.map.onPlayerLoggedIn(fixture.returned);
                fixture.assertReturningRoster(spectating, "ordinary login after recovery");
                require(fixture.peerPackets.rosters.isEmpty(),
                        "Ordinary login resync is also restricted to the returning connection");
                fixture.clearPackets();
                fixture.map.onPlayerLoggedIn(fixture.returned);
                fixture.assertReturningRoster(spectating, "repeated ordinary login");
            }
        }
        System.out.println("[ZOMBIES-RECONNECT-ROSTER] PASS alive/spectating reconnect and ordinary login send real full room rosters to the new connection");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void rejectedReconnectNeverPublishesRoomContext(GameTestHelper helper) throws Exception {
        try (var fixture = new RosterFixture(helper)) {
            var outsider = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "RosterOutsider"));
            var outsiderPackets = new RosterPacketCapture();
            outsider.connection = new CapturingPacketListener(outsider, outsiderPackets);
            fixture.map.onPlayerLoggedIn(outsider);
            require(!fixture.map.restoreActiveRoundReconnect(outsider),
                    "Unknown players cannot restore an active room");
            require(outsiderPackets.rosters.isEmpty(), "An unknown player receives no room roster/context");

            fixture.registerOnline(fixture.returned);
            fixture.runtime.markLeft();
            fixture.clearPackets();
            require(!fixture.map.restoreActiveRoundReconnect(fixture.returned),
                    "A player who left cannot recover old room membership");
            require(fixture.returnedPackets.rosters.isEmpty(), "Rejected LEFT recovery sends no room context");

            fixture.runtime.markOnline();
            fixture.map.isStart = false;
            fixture.clearPackets();
            require(!fixture.map.restoreActiveRoundReconnect(fixture.returned),
                    "A room outside an active match rejects active-round recovery");
            require(fixture.returnedPackets.rosters.isEmpty(), "Inactive-room rejection sends no room context");
            fixture.map.onPlayerLoggedIn(null);
            require(!fixture.map.restoreActiveRoundReconnect(null), "Null recovery is rejected");
        }
        helper.succeed();
    }

    private record CapturedRoster(String roomKey, int version, Map<String, List<PlayerInfo>> teams) { }

    /**
     * Capture the real Forge wire packet. PacketDistributor.PLAYER sends through Connection,
     * bypassing ServerGamePacketListenerImpl.send; intercepting only the latter misses this bug.
     */
    private static final class RosterPacketCapture extends Connection {
        private final ResourceLocation channel;
        private final int discriminator;
        private final List<CapturedRoster> rosters = new ArrayList<>();

        private RosterPacketCapture() {
            super(PacketFlow.SERVERBOUND);
            var probe = (ClientboundCustomPayloadPacket) ModNetworkChannel.channel().toVanillaPacket(
                    new TeamPlayerListPacket("fixture:probe", 0, Map.of()), NetworkDirection.PLAY_TO_CLIENT);
            channel = probe.getIdentifier();
            // Discover the discriminator from the installed CDP, whose registration order may differ.
            var data = probe.getData();
            try {
                discriminator = data.readUnsignedByte();
            } finally {
                data.release();
            }
        }

        @Override
        public void send(Packet<?> packet) {
            capture(packet);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
            capture(packet);
        }

        private void capture(Packet<?> packet) {
            if (!(packet instanceof ClientboundCustomPayloadPacket payload)
                    || !channel.equals(payload.getIdentifier())) return;
            var input = payload.getData();
            try {
                if (!input.isReadable() || input.readUnsignedByte() != discriminator) return;
                var decoded = TeamPlayerListPacket.decode(input);
                require(!input.isReadable(), "Full roster decoder consumes the complete real packet");
                var body = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    // Use the product decoder/encoder without relying on private packet field names.
                    decoded.encode(body);
                    String roomKey = body.readUtf();
                    int version = body.readInt();
                    Map<String, List<PlayerInfo>> teams = new HashMap<>();
                    for (int count = body.readInt(); count > 0; count--) {
                        String name = body.readUtf();
                        List<PlayerInfo> members = new ArrayList<>();
                        for (int players = body.readInt(); players > 0; players--) members.add(PlayerInfo.read(body));
                        teams.put(name, List.copyOf(members));
                    }
                    require(!body.isReadable(), "Inspected full roster has no trailing fields");
                    rosters.add(new CapturedRoster(roomKey, version, Map.copyOf(teams)));
                } finally {
                    body.release();
                }
            } finally {
                input.release();
            }
        }
    }

    private static final class CapturingPacketListener extends ServerGamePacketListenerImpl {
        private final RosterPacketCapture packets;

        private CapturingPacketListener(ServerPlayer player, RosterPacketCapture packets) {
            super(player.getServer(), packets, player);
            this.packets = packets;
        }

        @Override
        public void send(Packet<?> packet) {
            packets.capture(packet);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
            packets.capture(packet);
        }
    }

    private static final class RosterFixture implements AutoCloseable {
        private final ZombiesMap map;
        private final RoomId room;
        private final FakePlayer original;
        private final FakePlayer returned;
        private final FakePlayer peer;
        private final RosterPacketCapture returnedPackets = new RosterPacketCapture();
        private final RosterPacketCapture peerPackets = new RosterPacketCapture();
        private final ZombiesPlayerRuntimeState runtime;
        private final ZombiesPlayerRuntimeMarkerService markers = ZombiesPlayerRuntimeMarkerService.instance();
        private final Map<UUID, ServerPlayer> onlinePlayers;
        private final Map<UUID, ServerPlayer> previousEntries = new HashMap<>();

        private RosterFixture(GameTestHelper helper) throws Exception {
            var level = helper.getLevel();
            var core = FPSMCore.getInstance();
            if (!core.checkGameType("zombies")) core.registerGameType("zombies", ZombiesMap::new);
            String name = "roster-reconnect-" + UUID.randomUUID().toString().substring(0, 8);
            room = RoomId.of("zombies", name);
            BlockPos pos = helper.absolutePos(BlockPos.ZERO);
            map = new ZombiesMap(level, name, new AreaData(pos, pos.offset(8, 8, 8)));
            core.registerMap("zombies", map);
            onlinePlayers = playerDirectory(level.getServer().getPlayerList());
            var profile = new GameProfile(UUID.randomUUID(), "RosterReturn");
            original = new FakePlayer(level, profile);
            returned = new FakePlayer(level, profile);
            peer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "RosterPeer"));
            original.connection = new CapturingPacketListener(original, new RosterPacketCapture());
            returned.connection = new CapturingPacketListener(returned, returnedPackets);
            peer.connection = new CapturingPacketListener(peer, peerPackets);
            original.setGameMode(GameType.ADVENTURE);
            peer.setGameMode(GameType.ADVENTURE);
            registerOnline(original);
            registerOnline(peer);
            var state = fixtureField(map, "runtimeState", ZombiesRoomRuntimeState.class);
            state.transitionTo(ZombiesGamePhase.WAVE_ACTIVE);
            map.isStart = true;
            var players = fixtureField(map, "playerStateService", ZombiesPlayerStateService.class);
            runtime = players.getOrCreate(profile.getId());
            runtime.addKill();
            players.getOrCreate(peer.getUUID()).addKill();
            var team = map.getMapTeams().getTeamByName(ZombiesTeamNames.SURVIVORS).orElseThrow();
            team.join(original);
            team.join(peer);
            clearPackets();
        }

        private void registerOnline(ServerPlayer player) {
            if (!previousEntries.containsKey(player.getUUID())) {
                previousEntries.put(player.getUUID(), onlinePlayers.get(player.getUUID()));
            }
            onlinePlayers.put(player.getUUID(), player);
            require(player.getServer().getPlayerList().getPlayer(player.getUUID()) == player,
                    "Actual server UUID lookup resolves the currently connected fixture instance");
        }

        private void clearPackets() {
            returnedPackets.rosters.clear();
            peerPackets.rosters.clear();
        }

        private void assertReturningRoster(boolean spectating, String source) {
            require(returnedPackets.rosters.size() == 1,
                    source + " immediately sends exactly one actual full roster, found " + returnedPackets.rosters.size());
            var roster = returnedPackets.rosters.get(0);
            require(room.encode().equals(roster.roomKey()) && roster.version() > 0,
                    source + " carries the room identity and a valid roster version");
            require(roster.teams().keySet().equals(java.util.Set.of(ZombiesTeamNames.SURVIVORS)),
                    source + " carries the authoritative survivor team");
            var survivors = roster.teams().get(ZombiesTeamNames.SURVIVORS);
            require(survivors.size() == 2
                            && survivors.stream().anyMatch(info -> info.uuid().equals(peer.getUUID())),
                    source + " includes the full two-player roster, not a returning-player-only delta");
            var info = survivors.stream().filter(member -> member.uuid().equals(returned.getUUID())).findFirst().orElseThrow();
            require(info.name().equals("RosterReturn") && info.kills() == 1
                            && info.deaths() == (spectating ? 1 : 0) && info.isAlive() == !spectating,
                    source + " reflects the current life state and retained player statistics");
        }

        @Override
        public void close() {
            try {
                markers.clearMarker(original);
                markers.clearMarker(returned);
                map.getMapTeams().removePlayer(original.getUUID());
                map.getMapTeams().removePlayer(peer.getUUID());
                FPSMCore.getInstance().unregisterMap(map);
                map.getMapTeams().retireCreatedScoreboardTeams();
            } finally {
                previousEntries.forEach((id, previous) -> {
                    if (previous == null) onlinePlayers.remove(id);
                    else onlinePlayers.put(id, previous);
                });
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<UUID, ServerPlayer> playerDirectory(PlayerList players) throws ReflectiveOperationException {
            // Generic types survive named/reobfuscated runs; no Minecraft field-name mapping is assumed.
            for (var field : PlayerList.class.getDeclaredFields()) {
                if (field.getGenericType() instanceof ParameterizedType type
                        && type.getRawType() == Map.class
                        && java.util.Arrays.equals(type.getActualTypeArguments(), new Object[]{UUID.class, ServerPlayer.class})) {
                    field.setAccessible(true);
                    return (Map<UUID, ServerPlayer>) field.get(players);
                }
            }
            throw new IllegalStateException("Cannot locate the actual server UUID player directory");
        }
    }

    private static ZombiesWeaponInstanceState weapon(ZombiesPlayerRuntimeState runtime, ZombiesEquipmentSlot slot) {
        return switch (slot) {
            case STARTER -> runtime.starterWeapon().orElseThrow();
            case PRIMARY -> runtime.primaryWeapon().orElseThrow();
            case MYSTERY_BOX -> runtime.mysteryBoxWeapon().orElseThrow();
        };
    }

    private static void setWeapon(ZombiesPlayerRuntimeState runtime, ZombiesEquipmentSlot slot, ZombiesWeaponInstanceState weapon) {
        switch (slot) {
            case STARTER -> runtime.setStarterWeapon(weapon);
            case PRIMARY -> runtime.setPrimaryWeapon(weapon);
            case MYSTERY_BOX -> runtime.setMysteryBoxWeapon(weapon);
        }
    }

    private static <T> T fixtureField(ZombiesMap map, String name, Class<T> type) throws Exception {
        var field = ZombiesMap.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(map));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private ZombiesReconnectAmmoGameTests() { }
}
