package com.thor.refreshrate;

import java.util.Locale;

/**
 * Pure Android activity-based menu/session detection. No timers, polling or
 * guesses from generic window titles. New emulator adapters belong here.
 * 'Session' means an emulation/activity session, not proof of a running game.
 */
final class ActivityModeDetector {
    static final int UNKNOWN = -1;
    static final int MENU = 0;
    static final int SESSION = 1;

    private ActivityModeDetector() { }

    static boolean supports(String pkg) {
        return isDroidDeck(pkg) || isEmulatorWithActivityMode(pkg);
    }

    static boolean isDroidDeck(String pkg) {
        return "com.droiddeck.launcher".equals(pkg);
    }

    private static boolean isEmulatorWithActivityMode(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.startsWith("org.dolphinemu.") || p.contains("dolphinemu")
                || p.contains("azahar") || p.startsWith("org.citra.")
                || p.contains("lime3ds") || p.startsWith("me.magnum.melonds")
                || p.contains("eden_emulator") || p.contains("yuzu_emu")
                || p.contains("sudachi") || p.contains("citron_emu");
    }

    /** Only match documented Activity classes. Unknown/transient UI events must not
     * change modes (e.g. notification, overlay, permission window). */
    static int detect(String pkg, String className) {
        if (!supports(pkg) || className == null) return UNKNOWN;
        String c = className.toLowerCase(Locale.ROOT);
        if (isDroidDeck(pkg)) {
            if (c.endsWith(".sessionactivity")) return SESSION;
            if (c.endsWith(".frontendactivity") || c.endsWith(".mainactivity")) return MENU;
            return UNKNOWN;
        }
        if (pkg.toLowerCase(Locale.ROOT).contains("dolphin")) {
            if (c.endsWith(".activities.emulationactivity")) return SESSION;
            if (c.endsWith(".ui.main.mainactivity")) return MENU;
            return UNKNOWN;
        }
        if (pkg.toLowerCase(Locale.ROOT).startsWith("me.magnum.melonds")) {
            if (c.endsWith(".ui.emulator.emulatoractivity")) return SESSION;
            if (c.endsWith(".mainactivity")) return MENU;
            return UNKNOWN;
        }
        if (c.endsWith(".activities.emulationactivity")) return SESSION;
        if (c.endsWith(".activities.mainactivity") || c.endsWith(".ui.main.mainactivity")) return MENU;
        return UNKNOWN;
    }
}
