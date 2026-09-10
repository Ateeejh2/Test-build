package com.atlasdead.wanderbot.util;

import java.util.Locale;

/** Allocation-light normalization helpers for repeatedly sampled HUD/chat text. */
public final class TextSanitizer {
    private TextSanitizer() {}

    public static String stripFormatting(String value) {
        if (value == null || value.isEmpty()) return "";
        int marker = value.indexOf('\u00a7');
        if (marker < 0) return value;

        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u00a7' && i + 1 < value.length() && isFormattingCode(value.charAt(i + 1))) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    public static String normalizedLower(String value) {
        return stripFormatting(value).trim().toLowerCase(Locale.ROOT);
    }

    public static String collapseWhitespace(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean whitespace = Character.isWhitespace(c) || c == '\u00a0';
            if (whitespace) {
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) out.append(' ');
                out.append(c);
                pendingSpace = false;
            }
        }
        return out.toString();
    }

    private static boolean isFormattingCode(char c) {
        char lower = Character.toLowerCase(c);
        return (lower >= '0' && lower <= '9')
                || (lower >= 'a' && lower <= 'f')
                || (lower >= 'k' && lower <= 'o')
                || lower == 'r';
    }
}
