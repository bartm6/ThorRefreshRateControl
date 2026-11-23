package com.thor.refreshrate;

import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Locates the Thor's lower built-in panel without assuming its display ID.
 *
 * v1.0.4 and earlier hardcoded display 4. If a firmware update renumbers the
 * displays, that lookup returns null, panelsReady() stays false forever and
 * switching silently stops. Here the framework's own display type is used
 * first; ID 4 is only a tie-breaker / fallback.
 */
final class BottomDisplay {
    private static final String TAG = "ThorRefresh";
    /** The lower panel's ID on stock Thor Android 13 firmware. */
    static final int LEGACY_ID = 4;

    // android.view.Display#getType() and its constants are @hide but
    // @UnsupportedAppUsage (accessible greylist). Values from AOSP Display.java.
    private static final int TYPE_UNKNOWN = 0;
    private static final int TYPE_INTERNAL = 1;
    private static final int TYPE_LOOKUP_FAILED = -1;

    private static volatile Method getType;
    private static volatile boolean typeUnavailable;
    private static volatile int lastLoggedId = Integer.MIN_VALUE;

    private BottomDisplay() { }

    /** Returns the lower panel, or null if none is attached (e.g. top-only mode). */
    static Display find(DisplayManager dm) {
        if (dm == null) return null;
        Display[] displays;
        try {
            displays = dm.getDisplays();
        } catch (RuntimeException e) {
            return null;
        }
        Display best = null;
        int bestScore = 0;
        for (Display d : displays) {
            if (d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            int score = score(d);
            if (score > bestScore) {
                best = d;
                bestScore = score;
            }
        }
        logChoice(best, bestScore);
        return best;
    }

    /**
     * 4 = built-in type and the stock ID, 3 = built-in type,
     * 2 = type unknown but the stock ID, 1 = type unknown and not virtual-looking,
     * 0 = never (external, Wi-Fi, overlay, virtual or private displays).
     */
    private static int score(Display d) {
        int type = type(d);
        boolean legacyId = d.getDisplayId() == LEGACY_ID;
        if (type == TYPE_INTERNAL) return legacyId ? 4 : 3;
        // A known non-internal type: HDMI/USB-C output, Miracast, overlays, VirtualDisplay.
        if (type > TYPE_INTERNAL) return 0;
        // TYPE_UNKNOWN, or the hidden API was unavailable: use public signals only.
        if (looksVirtual(d)) return 0;
        return legacyId ? 2 : 1;
    }

    private static boolean looksVirtual(Display d) {
        if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) return true;
        String name = d.getName();
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("virtual") || n.contains("overlay");
    }

    private static int type(Display d) {
        if (typeUnavailable) return TYPE_LOOKUP_FAILED;
        try {
            Method m = getType;
            if (m == null) {
                m = Display.class.getMethod("getType");
                getType = m;
            }
            Object value = m.invoke(d);
            return value instanceof Integer ? (Integer) value : TYPE_UNKNOWN;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            typeUnavailable = true;
            Log.i(TAG, "Display#getType unavailable; using public display signals", e);
            return TYPE_LOOKUP_FAILED;
        }
    }

    /** Log only when the chosen panel changes, so logcat shows renumbering. */
    private static void logChoice(Display chosen, int score) {
        int id = chosen == null ? Display.INVALID_DISPLAY : chosen.getDisplayId();
        if (id == lastLoggedId) return;
        lastLoggedId = id;
        if (chosen == null) {
            Log.i(TAG, "No lower panel attached; refresh-rate writes wait for dual-screen mode");
        } else {
            Log.i(TAG, "Lower panel is display " + id + " (\"" + chosen.getName()
                    + "\", match score " + score + ")");
        }
    }
}
