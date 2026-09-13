package com.cdp.codpattern.app.zombies.item;

import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;

public final class ZombiesRequiredItemCompatTest {
    private ZombiesRequiredItemCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Field bootstrapFlag = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapFlag.setAccessible(true);
        bootstrapFlag.setBoolean(null, true);
        require(!BuiltInRegistries.REGISTRY.keySet().isEmpty(), "built-in registries must initialize");
        require(Items.DIAMOND != Items.AIR, "vanilla item constants must initialize");
        codecKeepsOldMapsCompatibleAndRoundTripsRequiredItem();
        parserAcceptsSupportedFormatsAndRejectsInvalidInput();
        matchingRequiresExactItemAndFullNbtButIgnoresCount();
    }

    private static void codecKeepsOldMapsCompatibleAndRoundTripsRequiredItem() {
        String base = """
                {"objectId":"barrier-1","group":2,"cost":750,"blocksPlayersOnly":true,
                 "dimension":"minecraft:overworld","areaFrom":[0,64,0],"areaTo":[0,66,0],
                 "interactionPos":[0,65,-1]%s}
                """;
        ZombiesBarrierData oldData = decode(base.formatted(""));
        require(oldData.requiredItem().isEmpty(), "old barrier JSON must default requiredItem to empty");

        String required = "{Count:1b,id:\"minecraft:tripwire_hook\",tag:{CustomModelData:1}}";
        ZombiesBarrierData newData = decode(base.formatted(",\"requiredItem\":\""
                + required.replace("\"", "\\\"") + "\""));
        require(required.equals(newData.requiredItem()), "new barrier JSON must decode requiredItem");
        JsonElement encoded = ZombiesBarrierData.CODEC.encodeStart(JsonOps.INSTANCE, newData)
                .getOrThrow(false, error -> {
                    throw new AssertionError("barrier encode failed: " + error);
                });
        ZombiesBarrierData roundTripped = ZombiesBarrierData.CODEC.parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(false, error -> {
                    throw new AssertionError("barrier re-decode failed: " + error);
                });
        require(required.equals(roundTripped.requiredItem()), "requiredItem must survive codec round trip");
    }

    private static ZombiesBarrierData decode(String json) {
        return ZombiesBarrierData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(false, error -> {
                    throw new AssertionError("barrier decode failed: " + error);
                });
    }

    private static void parserAcceptsSupportedFormatsAndRejectsInvalidInput() {
        assertParsed("minecraft:diamond", Items.DIAMOND, false);
        assertParsed("minecraft:tripwire_hook{CustomModelData:1}", Items.TRIPWIRE_HOOK, true);
        ZombiesRequiredItem.ParseResult full = ZombiesRequiredItem.parse(
                "{id:\"minecraft:tripwire_hook\",Count:23b,tag:{CustomModelData:1}}");
        require(full.valid(), "full ItemStack SNBT must parse");
        require(full.stack().orElseThrow().getCount() == 1, "normalization must force count to one");
        require(full.normalized().contains("Count:1b"), "normalized SNBT must persist count one");

        require(!ZombiesRequiredItem.parse("minecraft:not_a_real_item").valid(),
                "unknown item id must be rejected");
        require(!ZombiesRequiredItem.parse("minecraft:diamond{broken").valid(),
                "malformed item tag must be rejected");
        require(!ZombiesRequiredItem.parse("{Count:1b}").valid(),
                "full ItemStack SNBT without id must be rejected");
        require(ZombiesRequiredItem.parse("  ").valid()
                        && !ZombiesRequiredItem.parse("  ").configured(),
                "blank requirement must remain disabled");
    }

    private static void assertParsed(String input, net.minecraft.world.item.Item expected, boolean hasTag) {
        ZombiesRequiredItem.ParseResult result = ZombiesRequiredItem.parse(input);
        require(result.valid(), "supported required item must parse: " + input + " (" + result.error() + ")");
        ItemStack stack = result.stack().orElseThrow();
        require(stack.is(expected), "parsed item must match expected registry item");
        require(stack.hasTag() == hasTag, "parsed tag presence must match input");
        require(stack.getCount() == 1, "parsed count must be normalized to one");
    }

    private static void matchingRequiresExactItemAndFullNbtButIgnoresCount() {
        String requirement = "minecraft:tripwire_hook{CustomModelData:1}";
        ItemStack exact = new ItemStack(Items.TRIPWIRE_HOOK, 64);
        exact.getOrCreateTag().putInt("CustomModelData", 1);
        require(ZombiesRequiredItem.matches(requirement, exact),
                "matching item and complete NBT must succeed regardless of count");

        ItemStack differentNbt = exact.copy();
        differentNbt.getOrCreateTag().putInt("CustomModelData", 2);
        require(!ZombiesRequiredItem.matches(requirement, differentNbt), "different NBT must fail");

        ItemStack extraNbt = exact.copy();
        extraNbt.getOrCreateTag().putBoolean("Extra", true);
        require(!ZombiesRequiredItem.matches(requirement, extraNbt), "extra NBT must fail exact matching");
        require(!ZombiesRequiredItem.matches(requirement, new ItemStack(Items.DIAMOND)),
                "different item must fail");
        require(!ZombiesRequiredItem.matches("minecraft:tripwire_hook", exact),
                "a plain item ID must not match a stack carrying extra NBT");

        CompoundTag originalTag = exact.getTag().copy();
        ZombiesRequiredItem.matches(requirement, exact);
        require(exact.getCount() == 64 && originalTag.equals(exact.getTag()),
                "matching must not mutate or consume the candidate stack");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
