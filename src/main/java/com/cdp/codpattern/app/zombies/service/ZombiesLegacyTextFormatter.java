package com.cdp.codpattern.app.zombies.service;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Converts vanilla legacy section-sign codes to structured chat component styles. */
public final class ZombiesLegacyTextFormatter {
    private ZombiesLegacyTextFormatter() {
    }

    public static Component parse(String text) {
        String input = text == null ? "" : text;
        MutableComponent result = Component.empty();
        StringBuilder segment = new StringBuilder();
        Style style = Style.EMPTY;

        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current != '\u00a7') {
                segment.append(current);
                continue;
            }

            flush(result, segment, style);
            if (index + 1 >= input.length()) {
                continue;
            }
            char code = Character.toLowerCase(input.charAt(++index));
            ChatFormatting formatting = ChatFormatting.getByCode(code);
            if (formatting == null) {
                segment.append(code);
            } else if (formatting == ChatFormatting.RESET) {
                style = Style.EMPTY;
            } else if (formatting.isColor()) {
                style = Style.EMPTY.applyFormat(formatting);
            } else {
                style = style.applyFormat(formatting);
            }
        }
        flush(result, segment, style);
        return result;
    }

    private static void flush(MutableComponent result, StringBuilder segment, Style style) {
        if (segment.length() == 0) {
            return;
        }
        result.append(Component.literal(segment.toString()).setStyle(style));
        segment.setLength(0);
    }
}
