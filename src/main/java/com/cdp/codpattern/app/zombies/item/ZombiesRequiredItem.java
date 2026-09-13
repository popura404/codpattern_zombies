package com.cdp.codpattern.app.zombies.item;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;
import java.util.Optional;

/** Parses and matches the optional exact ItemStack requirement used by barriers. */
public final class ZombiesRequiredItem {
    private ZombiesRequiredItem() {
    }

    public static ParseResult parse(String input) {
        String text = Objects.requireNonNullElse(input, "").trim();
        if (text.isEmpty()) {
            return ParseResult.disabled();
        }
        try {
            ItemStack stack = text.startsWith("{") ? parseFullStack(text) : parseItemAndTag(text);
            stack.setCount(1);
            String normalized = stack.save(new CompoundTag()).toString();
            return new ParseResult(true, Optional.of(stack), normalized, "");
        } catch (Exception exception) {
            String message = Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName());
            return new ParseResult(true, Optional.empty(), text, message);
        }
    }

    public static String normalize(String input) {
        ParseResult result = parse(input);
        if (!result.valid()) {
            throw new IllegalArgumentException("invalid required item: " + result.error());
        }
        return result.normalized();
    }

    public static boolean inventoryContains(Inventory inventory, String input) {
        ParseResult result = parse(input);
        if (!result.configured() || !result.valid()) {
            return !result.configured();
        }
        ItemStack required = result.stack().orElseThrow();
        if (inventory == null) {
            return false;
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (candidate != null && !candidate.isEmpty()
                    && ItemStack.isSameItemSameTags(required, candidate)) {
                return true;
            }
        }
        return false;
    }

    public static boolean matches(String input, ItemStack candidate) {
        ParseResult result = parse(input);
        return result.valid()
                && result.stack().filter(required -> candidate != null
                        && !candidate.isEmpty()
                        && ItemStack.isSameItemSameTags(required, candidate)).isPresent();
    }

    public static Component displayName(String input) {
        ParseResult result = parse(input);
        return result.stack()
                .map(ItemStack::getHoverName)
                .orElseGet(() -> Component.literal(Objects.requireNonNullElse(input, "").trim()));
    }

    private static ItemStack parseFullStack(String text) throws Exception {
        CompoundTag serialized = TagParser.parseTag(text);
        if (!serialized.contains("id", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("full ItemStack SNBT must contain a string id");
        }
        if (serialized.contains("tag") && !serialized.contains("tag", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("full ItemStack SNBT tag must be a compound");
        }
        ResourceLocation id = parseId(serialized.getString("id"));
        requireItem(id);
        serialized.putByte("Count", (byte) 1);
        ItemStack stack = ItemStack.of(serialized);
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("ItemStack SNBT resolves to an empty item");
        }
        return stack;
    }

    private static ItemStack parseItemAndTag(String text) throws Exception {
        int tagStart = text.indexOf('{');
        String idText = tagStart < 0 ? text : text.substring(0, tagStart).trim();
        ResourceLocation id = parseId(idText);
        CompoundTag itemTag = null;
        if (tagStart >= 0) {
            String tagText = text.substring(tagStart).trim();
            itemTag = TagParser.parseTag(tagText);
        }
        ItemStack stack = new ItemStack(requireItem(id));
        if (itemTag != null) {
            stack.setTag(itemTag);
        }
        return stack;
    }

    private static ResourceLocation parseId(String text) {
        ResourceLocation id = ResourceLocation.tryParse(Objects.requireNonNullElse(text, "").trim());
        if (id == null) {
            throw new IllegalArgumentException("invalid item id: " + text);
        }
        return id;
    }

    private static Item requireItem(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown item id: " + id));
        if (item == Items.AIR) {
            throw new IllegalArgumentException("item id resolves to air: " + id);
        }
        return item;
    }

    public record ParseResult(
            boolean configured,
            Optional<ItemStack> stack,
            String normalized,
            String error
    ) {
        public ParseResult {
            stack = stack == null ? Optional.empty() : stack;
            normalized = Objects.requireNonNullElse(normalized, "");
            error = Objects.requireNonNullElse(error, "");
        }

        public static ParseResult disabled() {
            return new ParseResult(false, Optional.empty(), "", "");
        }

        public boolean valid() {
            return !configured || stack.isPresent();
        }
    }
}
