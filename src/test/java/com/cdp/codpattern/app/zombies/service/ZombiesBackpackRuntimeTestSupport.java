package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.match.model.ModeObjectInteractionContext;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.map.ZombiesMapSnapshot;
import com.cdp.codpattern.app.zombies.map.object.ZombiesMysteryBoxData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesWeaponWallData;
import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSlot;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;
import com.cdp.codpattern.app.zombies.validation.ZombiesMapValidationProfile;
import com.cdp.codpattern.app.zombies.validation.ZombiesMapValidator;
import com.cdp.codpattern.common.block.CodPatternBlockRegister;
import com.cdp.codpattern.compat.tacz.TaczGatewayProvider;
import com.cdp.codpattern.config.zombies.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real TaCZ stacks and production services, run only inside Forge's transformed test server. */
public final class ZombiesBackpackRuntimeTestSupport {
    private static final String GLOCK = "tacz:glock_17";
    private static final RoomId ROOM = RoomId.of("zombies", "backpack-runtime");
    private static final Gson GSON = new Gson();

    public static void repository() throws Exception {
        Path root = Files.createTempDirectory("backpack-repository-");
        ZombiesServerConfig previous = ZombiesConfigRepository.getConfig();
        try {
            Path rules = root.resolve("a/rules"); Files.createDirectories(rules);
            // Deliberately wrong types and damaged retired files: none may enter the new config path.
            String old = """
                    {"schemaVersion":1,"starterWeapon":["ignored"],"ammunition":"ignored",
                    "ammunitionPerMagazineMultiple":-77,
                    "rarities":[{"id":"common","damageMultiplier":1.75}],
                    "upgrades":{"maxUpgradeLevel":2,"levels":{"1":{"price":2500,"damageMultiplier":2.0},"2":{"price":5000,"damageMultiplier":3.0}}}}
                    """;
            Files.writeString(rules.resolve("weapon_rules.json"), old);
            Files.writeString(rules.resolve("weapon_filter.json"), "{ deliberately corrupt");
            Files.writeString(rules.resolve("zombies_weapon_filter.json"), "[ also corrupt");
            var loaded = ZombiesConfigRepository.loadResult(rules, "a");
            check(loaded.config().getBackpack().errors().isEmpty(), "retired fields must not invalidate new config");
            check(loaded.config().getBackpack().getStarterWeapon().getNbt().contains(GLOCK), "old starter cannot replace default Glock");
            check(loaded.config().getBackpack().getAmmunition().getMaxReserveAmmoByType().get("pistol") == 180, "old negative multiplier ignored");
            check(loaded.config().getWeaponRules().damageMultiplier("common").orElseThrow() == 1.75, "old rarity still loaded");
            check(Files.readString(rules.resolve("weapon_rules.json")).equals(old), "ignored old fields must not force rewrite");
            check(Files.readString(rules.resolve("weapon_filter.json")).equals("{ deliberately corrupt"), "retired filter preserved");
            check(loaded.files().stream().noneMatch(status -> status.fileName().contains("filter")), "retired files never loaded");
            Path fresh = root.resolve("b/rules");
            ZombiesConfigRepository.loadResult(fresh, "b");
            check(!Files.exists(fresh.resolve("weapon_filter.json")), "fresh map never generates filter");
            String weapons = Files.readString(fresh.resolve("weapon_rules.json"));
            check(!weapons.contains("starterWeapon") && !weapons.contains("ammunition"), "weapon template has no retired fields");
            Files.writeString(rules.resolve("backpack.json"), configJson(47, null));
            Files.writeString(fresh.resolve("backpack.json"), configJson(83, null));
            var a = ZombiesConfigRepository.loadResult(rules, "a").config();
            var b = ZombiesConfigRepository.loadResult(fresh, "b").config();
            check(a.getBackpack().getAmmunition().getDefaultMaxReserveAmmo() == 47, "map A retains its snapshot after B loads");
            check(b.getBackpack().getAmmunition().getDefaultMaxReserveAmmo() == 83, "map B owns separate rules");
        } finally {
            ZombiesConfigRepository.setConfig(previous);
            deleteTree(root);
        }
    }

    public static void entrypoints(GameTestHelper helper, Integer override) {
        initializeJdkRandomProviders();
        var config = override == null ? ZombiesBackpackConfig.defaults() : parse(configJson(120, override));
        int expected = override == null ? 180 : override;
        var configReference = new AtomicReference<>(config);
        ServerLevel level = helper.getLevel();
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "BackpackTest"));
        var inventoryService = new ZombiesWeaponInventoryService();
        var starter = new ZombiesStarterKitDistributor(configReference::get).prepareStarterWeapons(ROOM, List.of(player.getUUID()));
        success(starter, "starter prepare");
        ItemStack starterStack = starter.value().orElseThrow().weapon(player.getUUID()).orElseThrow();
        assertAmmo(starterStack, expected, expected, "starter");
        check(starter.value().orElseThrow().starterWeaponState(player.getUUID()).orElseThrow().maxReserveAmmo() == expected,
                "starter runtime cap");
        player.getInventory().setItem(0, starterStack);

        var wallConfig = ZombiesWeaponWallConfig.defaults();
        wallConfig.setRarityPools(List.of(new ZombiesWeaponWallConfig.RarityPool("common", 1, 0, 0, 1, 0,
                List.of(new ZombiesWeaponWallConfig.GunWeight(GLOCK, 1)))));
        var wall = new ZombiesWeaponWallData("test-wall", level.dimension(), helper.absolutePos(new BlockPos(0, 2, 0)), Optional.empty());
        var offer = new ZombiesWeaponWallOfferService(() -> wallConfig, ZombiesWeaponRulesConfig::defaults,
                configReference::get, new Random(7), ZombiesWeaponInventoryService::createDefaultTaczGunStackForRules).createOffer(wall, 1);
        check(offer.purchasable() && offer.maxReserveAmmo() == expected, "wall uses same fixed cap");
        var legacyRules = new ZombiesServerConfig("legacy-wall", null, null, wallConfig, null, config, List.of()).legacyRulesConfig();
        var legacyOffer = new ZombiesWeaponWallOfferService(() -> legacyRules, new Random(7),
                ZombiesWeaponInventoryService::createDefaultTaczGunStackForRules, configReference::get).createOffer(wall, 1);
        check(legacyOffer.purchasable() && legacyOffer.maxReserveAmmo() == expected, "legacy wall branch uses same fixed cap");
        var wallState = ZombiesWeaponInstanceState.wallPrimary(offer.gunId(), offer.rarityId(), 1, offer.damageMultiplier(), offer.maxReserveAmmo());
        var preparedWall = inventoryService.preparePurchasedPrimaryWeapon(ROOM, wallState);
        success(preparedWall, "wall prepare");
        success(inventoryService.applyPreparedPrimaryWeapon(player, ROOM, preparedWall.value().orElseThrow(), wallState), "wall inventory commit");
        assertAmmo(player.getInventory().getItem(1), expected, expected, "wall");

        var players = new ZombiesPlayerStateService(); var economy = new ZombiesEconomyService(players);
        var power = new ZombiesPowerService(economy); var store = new ZombiesObjectStateStore();
        var interactions = new ZombiesObjectInteractionService(ROOM, List::of, List::of, List::of, List::of,
                Optional::empty, List::of, List::of,
                new ZombiesBarrierService(ROOM, List::of, economy, store, new ZombiesActiveSpawnGroupService(),
                        ignored -> true, () -> ZombiesGamePhase.WAVE_ACTIVE),
                new ZombiesWeaponInstanceService(economy), new ZombiesAmmoBoxService(economy),
                new ZombiesArmorService(economy), power, new ZombiesBuffService(economy, power),
                new ZombiesUltimateMachineService(economy, power), store);
        var boxes = ZombiesMysteryBoxConfig.defaults(); boxes.setCost(0);
        boxes.setRarities(List.of(new ZombiesMysteryBoxConfig.Rarity("common", 1.0, 0.0, 0.0, 1.0, 1.0,
                List.of(new ZombiesMysteryBoxConfig.GunWeight(GLOCK, 1.0)))));
        BlockPos pos = helper.absolutePos(new BlockPos(0, 3, 0));
        var box = new ZombiesMysteryBoxData("test-mystery", 0, List.of(), level.dimension(), pos, Optional.empty());
        interactions.configureMysteryBoxRuntime(() -> List.of(box), () -> boxes, ZombiesWeaponRulesConfig::defaults, configReference::get, () -> 1);
        var original = level.getBlockState(pos);
        level.setBlock(pos, CodPatternBlockRegister.ZOMBIES_MYSTERY_BOX.get().defaultBlockState(), Block.UPDATE_ALL);
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + 1.5);
        players.getOrCreate(player.getUUID());
        var context = new ModeObjectInteractionContext(ROOM, InteractionHand.MAIN_HAND, pos, Direction.NORTH, null, ItemStack.EMPTY);
        try {
            check(interactions.interact(player, context) == InteractionResult.SUCCESS, "mystery purchase succeeds");
            var reward = interactions.mysteryBoxRuntime().state(box.objectId());
            check(reward != null, "mystery reward exists");
            assertAmmo(reward.preparedWeapon().itemStack(), expected, expected, "mystery prepared reward");
            configReference.set(parse(configJson(120, expected + 19)));
        } catch (RuntimeException | AssertionError failure) {
            level.setBlock(pos, original, Block.UPDATE_ALL);
            throw failure;
        }
        helper.runAfterDelay(105, () -> {
            try {
                interactions.tickMysteryBoxRuntime(level.getGameTime());
                check(interactions.interact(player, context) == InteractionResult.SUCCESS, "mystery claim succeeds");
                check(interactions.mysteryBoxRuntime().state(box.objectId()).phase() == ZombiesMysteryBoxRuntimeService.Phase.COOLDOWN,
                        "claim actually commits, not deduplicated");
                assertAmmo(player.getInventory().getItem(2), expected, expected, "mystery claimed reward retains cap despite supplier change");
                check(players.getOrCreate(player.getUUID()).mysteryBoxWeapon().orElseThrow().maxReserveAmmo() == expected, "mystery runtime cap");
                helper.succeed();
            } finally {
                level.setBlock(pos, original, Block.UPDATE_ALL);
                player.getInventory().clearContent();
            }
        });
    }

    public static void attachmentsReloadRefillAndRestore(ServerLevel level) {
        var gateway = TaczGatewayProvider.gateway();
        var config = ZombiesBackpackConfig.defaults();
        ItemStack regular = ZombiesWeaponInventoryService.createDefaultTaczGunStackForRules("tacz:ak47");
        check(!regular.isEmpty(), "real AK exists");
        gateway.configureGunAmmo(regular, 0);
        int baseMagazine = gateway.resolveMagazineAmmo(regular);
        ItemStack attachment = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation("tacz", "attachment")));
        IAttachment api = IAttachment.getIAttachmentOrNull(attachment);
        check(api != null, "real attachment item exists");
        api.setAttachmentId(attachment, new ResourceLocation("tacz", "extended_mag_3"));
        IGun gun = IGun.getIGunOrNull(regular);
        check(gun != null && gun.allowAttachment(regular, attachment), "AK permits TaCZ extended magazine");
        gun.installAttachment(regular, attachment);
        int expandedMagazine = gateway.resolveMagazineAmmo(regular);
        check(expandedMagazine > baseMagazine, "attachment really increases magazine size");
        var inventoryService = new ZombiesWeaponInventoryService(new ZombiesWeaponItemStackService(), ignored -> regular.copy());
        int cap = ZombiesBackpackAmmoResolver.resolve("tacz:ak47", gateway.resolveGunType(regular).orElse(null), config.getAmmunition());
        check(cap == 360, "AK actual TaCZ rifle category resolves");
        var state = ZombiesWeaponInstanceState.wallPrimary("tacz:ak47", "common", 1, 1, cap);
        var prepared = inventoryService.preparePurchasedPrimaryWeapon(ROOM, state); success(prepared, "expanded wall prepare");
        ItemStack stack = prepared.value().orElseThrow().itemStack();
        check(IGun.getIGunOrNull(stack).getCurrentAmmoCount(stack) == expandedMagazine, "expanded magazine initialized full");
        assertAmmo(stack, 360, 360, "expanded rifle fixed reserve");
        check(((AbstractGunItem) stack.getItem()).findAndExtractDummyAmmo(stack, expandedMagazine) == expandedMagazine,
                "TaCZ reload extraction consumes reserve");
        check(gateway.resolveReserveAmmo(stack) == 360 - expandedMagazine && gateway.resolveMaxReserveAmmo(stack) == 360,
                "reload consumption preserves maximum");
        state = state.withReserveAmmo(gateway.resolveReserveAmmo(stack));
        success(inventoryService.syncReserveAmmo(stack, ROOM, ZombiesEquipmentSlot.PRIMARY, state), "sync consumed reserve");
        assertAmmo(stack, state.reserveAmmo(), 360, "consumed reserve NBT and runtime");

        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "BackpackRestore"));
        var players = new ZombiesPlayerStateService(); var economy = new ZombiesEconomyService(players);
        var runtime = players.getOrCreate(player.getUUID()); runtime.setPrimaryWeapon(state);
        economy.addPoints(player.getUUID(), 10000);
        var refill = new ZombiesAmmoBoxService(economy).refillPrimaryWeapon(player.getUUID(), Map.of(1, 1),
                (current, filled) -> inventoryService.syncReserveAmmo(stack, ROOM, ZombiesEquipmentSlot.PRIMARY, filled));
        success(refill, "refill"); assertAmmo(stack, 360, 360, "refill uses instance cap");
        var upgrade = new ZombiesUltimateMachineService(economy, new ZombiesPowerService(economy))
                .upgradePrimaryWeapon(player.getUUID(), new ZombiesRulesConfig().getUltimateMachine(), false,
                        (current, upgraded) -> inventoryService.syncReserveAmmo(stack, ROOM, ZombiesEquipmentSlot.PRIMARY, upgraded));
        success(upgrade, "upgrade");
        check(runtime.primaryWeapon().orElseThrow().upgradeLevel() == 1, "upgrade applied");
        assertAmmo(stack, 360, 360, "upgrade preserves cap");
        player.getInventory().setItem(1, stack);
        var snapshot = new ZombiesEquipmentSnapshotService().captureInventorySnapshot(ROOM, player.getInventory(), runtime);
        player.getInventory().clearContent();
        success(new ZombiesReviveLoadoutService().restoreSnapshot(ROOM, player, runtime, snapshot), "restore death/reconnect snapshot");
        assertAmmo(player.getInventory().getItem(1), 360, 360, "restored snapshot preserves cap");
        check(runtime.primaryWeapon().orElseThrow().maxReserveAmmo() == 360, "restored runtime cap");
        var zero = state.withMaxReserveAmmo(0, true); runtime.setPrimaryWeapon(zero);
        check(!new ZombiesAmmoBoxService(economy).refillPrimaryWeapon(player.getUUID(), Map.of(1, 1)).success(), "zero reserve cannot buy extra ammo");
        player.getInventory().clearContent();
    }

    public static void invalidConfigBeforeStartupAndRuntimeFailure(ServerLevel level) throws Exception {
        Path root = Files.createTempDirectory("backpack-startup-");
        try {
            Path file = root.resolve("backpack.json"); String invalid = configJson(-1, null); Files.writeString(file, invalid);
            var bad = ZombiesBackpackConfig.load(file);
            check(!bad.errors().isEmpty(), "invalid numeric value detected");
            var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "BackpackInvalid"));
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            var validMap = ZombiesMapSnapshot.of(ROOM, ROOM.mapName(), true, List.of(
                    new ZombiesMapSnapshot.SpawnSnapshot("initial", "spawn", "INITIAL", 0, 0, false),
                    new ZombiesMapSnapshot.SpawnSnapshot("zombies", "zombieSpawn", "", 0, 1, true)), List.of());
            Path waves = root.resolve("waves"); Files.createDirectories(waves); Files.writeString(waves.resolve("wave_001.json"), "{\"wave\":1,\"mobs\":[]}");
            var validator = new ZombiesStartupValidationService(new ZombiesMapValidator(ZombiesMapValidationProfile.MVP1_MINIMAL),
                    () -> new ZombiesWaveConfigRepository(waves, new ZombiesRulesConfig.Defaults(), new ZombiesWaveValidator()), bad::fileValidationIssues);
            var request = ZombiesStartupFlow.StartupRequest.forServerLevel(ROOM, validMap, List.of(player.getUUID()), List.of(), level, List.of());
            var started = new ZombiesStartupFlow(validator, new ZombiesStarterKitDistributor(() -> bad), null, null).execute(request);
            check(!started.success() && started.code().equals(ZombiesErrorCode.STARTUP_PREFLIGHT_FAILED), "numeric config blocks preflight");
            check(player.getInventory().getItem(0).is(Items.DIAMOND) && player.getInventory().getItem(0).getCount() == 3,
                    "inventory untouched on preflight failure");
            check(Files.readString(file).equals(invalid), "startup retains invalid file");
            var wallService = new ZombiesWeaponWallOfferService(ZombiesWeaponWallConfig::defaults, ZombiesWeaponRulesConfig::defaults,
                    () -> bad, new Random(1), ZombiesWeaponInventoryService::createDefaultTaczGunStackForRules);
            check(!wallService.createOffer(new ZombiesWeaponWallData("wall", level.dimension(), BlockPos.ZERO, Optional.empty()), 1).purchasable(),
                    "invalid rules cannot produce lobby wall offers");
            JsonObject opaque = JsonParser.parseString(configJson(120, null)).getAsJsonObject();
            opaque.getAsJsonObject("starterWeapon").addProperty("item", "bad ID !");
            opaque.getAsJsonObject("starterWeapon").addProperty("nbt", "{bad SNBT");
            var labels = parse(opaque.toString()); check(labels.errors().isEmpty(), "bad labels pass configuration stage");
            check(!new ZombiesStarterKitDistributor(() -> labels).prepareStarterWeapons(ROOM, List.of(player.getUUID())).success(),
                    "uncreatable item fails at existing runtime preparation");
            check(player.getInventory().getItem(0).is(Items.DIAMOND), "runtime preparation failure leaves inventory untouched");
            starterApplyRollback();
            player.getInventory().clearContent();
        } finally { deleteTree(root); }
    }

    private static void starterApplyRollback() {
        class Target implements ZombiesStarterKitDistributor.StarterKitTarget {
            private final UUID id = UUID.randomUUID(); private int value = 7; private final boolean fail;
            Target(boolean fail) { this.fail = fail; }
            public UUID playerId() { return id; }
            public boolean canApplyStarterWeapon() { return true; }
            public ZombiesStarterKitDistributor.StarterKitSnapshot captureSnapshot() { return new Snapshot(value); }
            public void clearInventoryAndRuntime() { value = 0; }
            public void applyStarterWeapon() { if (fail) throw new IllegalStateException("fixture commit failed"); value = 1; }
            public void syncInventory() { }
            public void restoreSnapshot(ZombiesStarterKitDistributor.StarterKitSnapshot snapshot) { value = ((Snapshot) snapshot).value(); }
        }
        Target first = new Target(false), second = new Target(true);
        check(!ZombiesStarterKitDistributor.applyPreparedStarterWeapons(List.of(first, second)).success(), "apply exception reports failure");
        check(first.value == 7 && second.value == 7, "all cleared inventories roll back after apply exception");
    }
    private record Snapshot(int value) implements ZombiesStarterKitDistributor.StarterKitSnapshot { }

    private static void initializeJdkRandomProviders() {
        // Forge's context loader cannot discover jdk.random service providers on first access.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
            java.util.random.RandomGenerator.getDefault();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static String configJson(int fallback, Integer override) {
        JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 1);
        JsonObject starter = new JsonObject();
        var defaults = ZombiesBackpackConfig.defaults().getStarterWeapon();
        starter.addProperty("item", defaults.getItem()); starter.addProperty("count", 1); starter.addProperty("nbt", defaults.getNbt());
        root.add("starterWeapon", starter);
        JsonObject ammo = new JsonObject(); ammo.addProperty("defaultMaxReserveAmmo", fallback);
        if (override != null) { JsonObject guns = new JsonObject(); guns.addProperty(GLOCK, override); ammo.add("maxReserveAmmoByGunId", guns); }
        root.add("ammunition", ammo); return GSON.toJson(root);
    }
    private static ZombiesBackpackConfig parse(String json) { return ZombiesBackpackConfig.parse(json, Path.of("backpack.json")); }
    private static void assertAmmo(ItemStack stack, int reserve, int cap, String context) {
        var gateway = TaczGatewayProvider.gateway(); check(gateway.isGun(stack), context + " is a real TaCZ gun");
        var gun = IGun.getIGunOrNull(stack);
        check(gun.getCurrentAmmoCount(stack) == gateway.resolveMagazineAmmo(stack) && gun.getCurrentAmmoCount(stack) > 0,
                context + " magazine full");
        check(gun.hasBulletInBarrel(stack), context + " chamber initialized");
        check(gateway.resolveReserveAmmo(stack) == reserve && gateway.resolveMaxReserveAmmo(stack) == cap,
                context + " TaCZ ammo expected " + reserve + "/" + cap + " got " + gateway.resolveReserveAmmo(stack) + "/" + gateway.resolveMaxReserveAmmo(stack));
        var tags = new ZombiesWeaponItemStackService().readWeaponTags(stack); success(tags, context + " zombies tags");
        check(tags.value().orElseThrow().reserveAmmo() == reserve && tags.value().orElseThrow().maxReserveAmmo() == cap,
                context + " zombies tags match TaCZ");
    }
    private static void success(ZombiesServiceResult<?> result, String context) { check(result.success(), context + ": " + result.code() + " " + result.logMessage()); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private ZombiesBackpackRuntimeTestSupport() { }
}
