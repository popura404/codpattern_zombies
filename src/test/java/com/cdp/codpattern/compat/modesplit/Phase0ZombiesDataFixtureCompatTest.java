package com.cdp.codpattern.compat.modesplit;

import com.cdp.codpattern.architecture.ModeSplitVerificationRoots;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.service.ZombiesServiceResult;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponItemStackService;
import com.cdp.codpattern.compat.fpsmatch.data.zombies.ZombiesMapData;
import com.cdp.codpattern.config.zombies.ZombiesWeaponFilterConfig;
import com.cdp.codpattern.config.zombies.ZombiesWeaponFilterRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;

/** Addon-owned Phase 0 Zombies map, config, NBT, and ItemStack fixture runner. */
public final class Phase0ZombiesDataFixtureCompatTest {
    private Phase0ZombiesDataFixtureCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println("PASS phase0 zombies data fixture compat");
    }

    public static void runAll() throws Exception {
        bootstrapRegistriesForPureJvmFixtures();
        zombiesMapCodecFixtureIsStable();
        characterizeZombiesWeaponFilterFixture();
        playerNbtFixtureRoundTripsExactly();
        zombiesItemStackFixtureRoundTripsAndPreservesForeignTags();
    }

    private static void bootstrapRegistriesForPureJvmFixtures() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Field bootstrapFlag = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapFlag.setAccessible(true);
        bootstrapFlag.setBoolean(null, true);
        require(!BuiltInRegistries.REGISTRY.keySet().isEmpty(),
                "built-in registries must load for fixture codecs");
        require(Items.CROSSBOW != Items.AIR,
                "vanilla item constants must initialize for ItemStack fixtures");
    }

    private static void zombiesMapCodecFixtureIsStable() throws IOException {
        assertCodecFixture(
                "maps/zombies-current.json",
                ZombiesMapData.MapData.CODEC,
                data -> {
                    require(data.schemaVersion() == 1, "zombies schema version must survive");
                    require("zombies".equals(data.gameType()), "zombies game type must survive");
                    require("Zombies_MixedCase_03".equals(data.mapName()), "zombies mapName case must survive");
                    require(data.initialSpawns().size() == 1, "initial spawn fixture must decode");
                    require(data.zombieSpawns().size() == 1, "zombie spawn fixture must decode");
                    require(data.barriers().size() == 1, "barrier fixture must decode");
                    require(data.weaponWalls().size() == 1, "weapon wall fixture must decode");
                    require(data.ammoBoxes().size() == 1, "ammo box fixture must decode");
                    require(data.armorStations().size() == 1, "armor station fixture must decode");
                    require(data.powerSwitch().isPresent(), "power switch fixture must decode");
                    require(data.sodaMachines().size() == 1, "soda machine fixture must decode");
                    require(data.ultimateMachines().size() == 1, "ultimate machine fixture must decode");
                    require(data.mysteryBoxes().size() == 1, "mystery box fixture must decode");
                    require(data.windows().size() == 1, "window fixture must decode");
                    require("Barrier_A".equals(data.barriers().get(0).objectId()),
                            "zombies object ID case must survive");
                    require(Float.compare(data.endtp().orElseThrow().getPitch(), -8.0F) == 0,
                            "zombies end-teleport pitch must survive round trip");
                },
                "\"schemaVersion\"",
                "\"gameType\"",
                "\"mapName\": \"Zombies_MixedCase_03\"",
                "\"unknownTopLevel\"");
    }

    private static <T> void assertCodecFixture(
            String relativePath,
            Codec<T> codec,
            Consumer<T> assertions,
            String... orderedSourceTokens
    ) throws IOException {
        String source = readFixture(relativePath);
        assertOrdered(source, relativePath, orderedSourceTokens);
        JsonElement input = JsonParser.parseString(source);
        T decoded = codec.parse(JsonOps.INSTANCE, input)
                .getOrThrow(false, error -> {
                    throw new AssertionError(relativePath + " decode failed: " + error);
                });
        assertions.accept(decoded);

        JsonElement firstEncoded = codec.encodeStart(JsonOps.INSTANCE, decoded)
                .getOrThrow(false, error -> {
                    throw new AssertionError(relativePath + " encode failed: " + error);
                });
        require(!firstEncoded.toString().contains("unknownTopLevel"),
                relativePath + " current codec must continue ignoring unknown top-level data");

        T secondDecoded = codec.parse(JsonOps.INSTANCE, firstEncoded)
                .getOrThrow(false, error -> {
                    throw new AssertionError(relativePath + " re-decode failed: " + error);
                });
        JsonElement secondEncoded = codec.encodeStart(JsonOps.INSTANCE, secondDecoded)
                .getOrThrow(false, error -> {
                    throw new AssertionError(relativePath + " re-encode failed: " + error);
                });
        require(firstEncoded.equals(secondEncoded),
                relativePath + " encoded form must be stable after one migration pass");
        require(source.equals(readFixture(relativePath)),
                relativePath + " source fixture must remain byte-for-byte untouched");
    }

    private static void characterizeZombiesWeaponFilterFixture() throws Exception {
        Path tempRoot = Files.createTempDirectory("phase0-zombies-config-fixtures-");
        try {
            String source = readFixture("config/zombies-weapon-filter-mixed-case.json");
            assertOrdered(source, "zombies filter source", "\"weaponTabs\"", "\"blockedItemNamespaces\"",
                    "\"UnknownLegacyOption\"");
            Path target = copyFixture(tempRoot, "config/zombies-weapon-filter-mixed-case.json");

            ZombiesWeaponFilterConfig config = ZombiesWeaponFilterRepository.loadOrCreate(target);

            require(source.equals(Files.readString(target)),
                    "zombies filter's current load path must not rewrite a valid file before explicit save");
            require(config.getWeaponTabs().equals(java.util.List.of("pistol", "rifle")),
                    "zombies filter tabs currently trim/lowercase/deduplicate in memory");
            require(config.getBlockedItemNamespaces().equals(java.util.List.of("legacypack")),
                    "zombies filter blocked namespaces currently trim/lowercase/deduplicate");
            require(Math.abs(config.getAmmunitionPerMagazineMultiple() - 12.5D) < 0.0001D,
                    "zombies filter ammunition multiple must survive");

            ZombiesWeaponFilterRepository.save(config);
            String saved = Files.readString(target);
            require(!saved.contains("UnknownLegacyOption"),
                    "zombies filter's current explicit save path drops unknown fields");
            ZombiesWeaponFilterConfig reloaded = ZombiesWeaponFilterRepository.loadOrCreate(target);
            require(reloaded.getWeaponTabs().equals(java.util.List.of("pistol", "rifle")),
                    "zombies filter canonical output must reload semantically");
            require(saved.equals(Files.readString(target)),
                    "zombies filter canonical output must remain stable on reload");
            require(source.equals(readFixture("config/zombies-weapon-filter-mixed-case.json")),
                    "zombies filter source fixture must remain untouched");
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private static void playerNbtFixtureRoundTripsExactly() throws Exception {
        String source = readFixture("nbt/player-marker.snbt");
        assertOrdered(source, "player marker source", "\"UnrelatedSibling\"", "\"codpattern.zombies\"",
                "\"roomId\"", "\"state\"", "\"endtp\"");
        CompoundTag tag = TagParser.parseTag(source);
        CompoundTag root = tag.getCompound("codpattern.zombies");
        require(root.contains("roomId", Tag.TAG_STRING), "player marker roomId key/type must remain exact");
        require(root.contains("state", Tag.TAG_STRING), "player marker state key/type must remain exact");
        require(root.contains("endtp", Tag.TAG_COMPOUND), "player marker endtp key/type must remain exact");
        RoomId roomId = RoomId.decode(root.getString("roomId"));
        require("zombies".equals(roomId.gameType()), "player marker game type must decode");
        require("Zombies_MixedCase_03".equals(roomId.mapName()), "player marker map-name case must survive");
        require("pending_endtp".equals(root.getString("state")), "player marker state spelling must survive");
        require(tag.getCompound("UnrelatedSibling").getString("MixedCaseKey").equals("KeepMe"),
                "unrelated player persistent NBT must stay represented in the fixture");

        Path tempFile = Files.createTempFile("phase0-player-marker-", ".dat");
        try {
            NbtIo.writeCompressed(tag, tempFile.toFile());
            CompoundTag restored = NbtIo.readCompressed(tempFile.toFile());
            require(tag.equals(restored), "player marker NBT must survive binary compressed read/write exactly");
        } finally {
            Files.deleteIfExists(tempFile);
        }
        require(source.equals(readFixture("nbt/player-marker.snbt")),
                "player marker source fixture must remain untouched");
    }

    private static void zombiesItemStackFixtureRoundTripsAndPreservesForeignTags() throws Exception {
        String source = readFixture("nbt/zombies-item-stack.snbt");
        assertOrdered(source, "item stack source", "\"GunId\"", "\"UnknownTaCZField\"",
                "\"codpattern.zombies.roomId\"", "\"codpattern.zombies.maxReserveAmmo\"");
        CompoundTag serialized = TagParser.parseTag(source);
        ItemStack stack = ItemStack.of(serialized);
        require(!stack.isEmpty(), "fixture ItemStack must decode");
        CompoundTag saved = stack.save(new CompoundTag());
        require(serialized.equals(saved), "ItemStack must preserve its exact serialized compound on read/write");

        ZombiesWeaponItemStackService service = new ZombiesWeaponItemStackService();
        ZombiesServiceResult<ZombiesWeaponItemStackService.ZombiesWeaponTagData> result =
                service.readWeaponTags(stack.getTag());
        require(result.success(), "zombies ItemStack tags must decode through the production service");
        ZombiesWeaponItemStackService.ZombiesWeaponTagData data = result.value().orElseThrow();
        require("zombies|Zombies_MixedCase_03".equals(data.roomId()), "ItemStack room key case must survive");
        require("fixture-instance-01".equals(data.instanceId()), "ItemStack instance ID must survive");
        require("tacz:m4a1".equals(data.gunId()), "ItemStack gun ID must survive");
        require("Epic_MixedCase".equals(data.rarityId()), "ItemStack rarity case must survive");
        require(data.weaponLevel() == 3 && data.upgradeLevel() == 2,
                "ItemStack weapon and upgrade levels must survive");
        require(data.reserveAmmo() == 77 && data.maxReserveAmmo() == 140,
                "ItemStack reserve-ammo fields must survive");

        CompoundTag itemTag = stack.getTag();
        require(itemTag != null, "fixture ItemStack must retain a tag");
        CompoundTag foreignBefore = itemTag.getCompound("UnknownTaCZField").copy();
        service.writeTag(itemTag, data);
        require(foreignBefore.equals(itemTag.getCompound("UnknownTaCZField")),
                "rewriting zombies tags must preserve unknown TaCZ fields");
        require("tacz:m4a1".equals(itemTag.getString("GunId")),
                "rewriting zombies tags must preserve the foreign TaCZ GunId key");
        service.stripWeaponTags(stack);
        require(foreignBefore.equals(Objects.requireNonNull(stack.getTag()).getCompound("UnknownTaCZField")),
                "stripping zombies tags must preserve unknown TaCZ fields");
        require("tacz:m4a1".equals(stack.getTag().getString("GunId")),
                "stripping zombies tags must preserve the foreign TaCZ GunId key");
        require(!stack.getTag().contains(ZombiesWeaponItemStackService.TAG_ROOM_ID),
                "strip must remove zombies room tag");
        require(!stack.getTag().contains(ZombiesWeaponItemStackService.TAG_MAX_RESERVE_AMMO),
                "strip must remove zombies max-reserve tag");
        require(source.equals(readFixture("nbt/zombies-item-stack.snbt")),
                "ItemStack source fixture must remain untouched");
    }

    private static Path copyFixture(Path tempRoot, String relativePath) throws IOException {
        Path target = tempRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, readFixture(relativePath));
        return target;
    }

    private static String readFixture(String relativePath) throws IOException {
        return Files.readString(ModeSplitVerificationRoots.testResource("mode-split/phase0/" + relativePath));
    }

    private static void assertOrdered(String source, String label, String... tokens) {
        int previous = -1;
        for (String token : tokens) {
            int current = source.indexOf(token);
            require(current >= 0, label + " must contain source token " + token);
            require(current > previous, label + " must retain source token ordering at " + token);
            previous = current;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
