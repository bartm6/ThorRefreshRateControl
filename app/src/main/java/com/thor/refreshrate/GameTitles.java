package com.thor.refreshrate;

import java.util.Locale;

/** Keep Steam IDs internal; never show generated numeric placeholders as titles. */
final class GameTitles {
    private GameTitles() { }

    static boolean unresolved(String value) {
        if (value == null || value.trim().isEmpty()) return true;
        String s = value.trim().toLowerCase(Locale.ROOT);
        return s.equals("unknown game") || s.equals("game name unavailable")
                || s.matches("steam (?:game|app)\\s*#?\\s*\\d+")
                || s.matches("steam[- ]?\\d+") || s.matches("\\d+");
    }

    static String display(String value) {
        return unresolved(value) ? "Unknown game" : value.trim();
    }
}
