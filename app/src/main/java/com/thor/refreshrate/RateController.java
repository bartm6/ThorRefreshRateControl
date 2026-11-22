package com.thor.refreshrate;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.os.Parcel;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.nio.charset.Charset;

/** Applies AYN Smooth Display policies while both built-in panels are awake. */
final class RateController {
    private static final String TAG = "ThorRefresh";
    private static final String MIN = "min_refresh_rate";
    private static final String PEAK = "peak_refresh_rate";
    static final String DUAL_SCREEN_MODE = "dual_screen_display_mode";
    private static final long WAKE_SETTLE_MS = 2000L;
    private static final String OWNED = "controller.owned";
    private static final String OWNED_HZ = "controller.ownedHz";
    private static final String BASE_MIN = "controller.baselineMin";
    private static final String BASE_PEAK = "controller.baselinePeak";

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences prefs;
    private volatile long safeAfterUptime;

    RateController(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.prefs = new ProfileStore(context).preferences();
        // Delay the first write until Android has restored both panels.
        postponeDisplayChanges();
    }

    boolean canWrite() {
        return Settings.System.canWrite(context);
    }

    /** AYN's Smooth Display path is global, not exclusive to display 0.
     * Changing it while the bottom OLED is disabled can blank that panel on
     * stock Thor firmware. Treat unrecognized modes as unsafe (fail closed). */
    void postponeDisplayChanges() {
        safeAfterUptime = SystemClock.uptimeMillis() + WAKE_SETTLE_MS;
    }

    private boolean panelsReady() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive()) return false;
        if (Settings.System.getInt(resolver, DUAL_SCREEN_MODE, -1) != 0) return false;
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return false;
        Display top = dm.getDisplay(Display.DEFAULT_DISPLAY);
        // The Thor's bottom built-in display is 4 on stock Android 13.
        Display bottom = dm.getDisplay(4);
        return top != null && top.getState() == Display.STATE_ON
                && bottom != null && bottom.getState() == Display.STATE_ON
                && SystemClock.uptimeMillis() >= safeAfterUptime;
    }

    private String read(String key) {
        return Settings.System.getString(resolver, key);
    }

    /** Executed only on foreground changes or when a profile is edited. */
    synchronized void onTargetChanged(int hz) {
        if (!canWrite() || (hz != 0 && hz != 60 && hz != 120)) return;
        // Never write either global rate setting while a Thor panel is off,
        // the clamshell is asleep, or the display mode is still settling.
        if (!panelsReady()) return;

        boolean owned = prefs.getBoolean(OWNED, false);
        int previousHz = prefs.getInt(OWNED_HZ, 0);
        String currentMin = read(MIN);
        String currentPeak = read(PEAK);

        // A change from the Thor's own control center takes precedence.
        if (owned && !matchesOwned(currentMin, currentPeak, previousHz)) {
            prefs.edit().putBoolean(OWNED, false).remove(OWNED_HZ).commit();
            owned = false;
        }

        if (hz == 0) {
            if (owned && writePair(prefs.getString(BASE_MIN, null),
                    prefs.getString(BASE_PEAK, null), currentMin, currentPeak)) {
                prefs.edit().putBoolean(OWNED, false).remove(OWNED_HZ)
                        .remove(BASE_MIN).remove(BASE_PEAK).commit();
            }
            return;
        }

        if (owned && previousHz == hz) return;

        // The Thor's tested 120-Hz policy value is slightly above 120.
        String value = hz == 120 ? "120.00001" : "60.0";
        // Already at this rate through the stock toggle: don't touch it.
        if (!owned && same(currentMin, value) && same(currentPeak, value)) return;

        if (!owned) {
            // Capture the manual values, including keys that were not set at all.
            prefs.edit().putString(BASE_MIN, currentMin)
                    .putString(BASE_PEAK, currentPeak).commit();
        }
        if (writePair(value, value, currentMin, currentPeak)) {
            prefs.edit().putBoolean(OWNED, true).putInt(OWNED_HZ, hz).commit();
        } else {
            prefs.edit().putBoolean(OWNED, false).remove(OWNED_HZ).commit();
        }
    }

    /**
     * Widen the acceptable range before narrowing it. A temporary min > peak
     * may cause a blank screen on some Thor firmware revisions.
     */
    private boolean writePair(String min, String peak, String oldMin, String oldPeak) {
        boolean increasing = parse(min, 0f) > parse(oldMin, 0f)
                || parse(peak, 0f) > parse(oldPeak, 0f);

        String first = increasing ? PEAK : MIN;
        String second = increasing ? MIN : PEAK;
        String firstValue = increasing ? peak : min;
        String secondValue = increasing ? min : peak;

        boolean ok = (same(increasing ? oldPeak : oldMin, firstValue)
                    || write(first, firstValue))
                && (same(increasing ? oldMin : oldPeak, secondValue)
                    || write(second, secondValue));
        if (ok) {
            // Verify the settings provider has actually accepted both changes.
            ok = same(read(MIN), min) && same(read(PEAK), peak);
        }
        if (!ok) {
            Log.w(TAG, "Refresh-rate change was not applied; restoring prior values");
            // Restore with the same safe ordering logic, without recursion.
            boolean restoreIncreasing = parse(oldMin, 0f) > parse(read(MIN), 0f)
                    || parse(oldPeak, 0f) > parse(read(PEAK), 0f);
            if (restoreIncreasing) {
                write(PEAK, oldPeak);
                write(MIN, oldMin);
            } else {
                write(MIN, oldMin);
                write(PEAK, oldPeak);
            }
        }
        return ok;
    }

    /** An external/manual rate change relinquishes the previous app override. */
    private static boolean matchesOwned(String min, String peak, int hz) {
        if (hz == 0 || min == null || peak == null) return false;
        try {
            return Math.abs(Float.parseFloat(min) - hz) < 0.1f
                    && Math.abs(Float.parseFloat(peak) - hz) < 0.1f;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static float parse(String value, float fallback) {
        try { return value == null ? fallback : Float.parseFloat(value); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null) return a == null && b == null;
        try { return Math.abs(Float.parseFloat(a) - Float.parseFloat(b)) < 0.01f; }
        catch (NumberFormatException e) { return a.equals(b); }
    }

    private boolean write(String key, String value) {
        // Both the key and value are strictly restricted; never pass user input
        // to the privileged service as an arbitrary shell command.
        if (!MIN.equals(key) && !PEAK.equals(key)) return false;
        if (value != null && !value.matches("(?:[0-9]{1,3})(?:\\.[0-9]{1,5})?")) return false;

        String command = value == null ? "settings delete system " + key
                : "settings put system " + key + " " + value;
        if (ThorBridge.run(command)) return true;

        // Fallback for ordinary Android hardware. On the stock Thor, modern
        // target SDKs may be blocked by the Settings.System client API.
        try {
            return Settings.System.putString(resolver, key, value);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Could not update " + key, e);
            return false;
        }
    }

    void relinquish() { onTargetChanged(0); }

    /**
     * One-time, user-initiated grant for our own declared DUMP permission.
     * Uses the same Thor vendor bridge already used for FPS switching; it does
     * not enable wireless debugging or grant privileges to other packages.
     * The caller MUST independently verify checkSelfPermission afterwards.
     */
    static boolean grantOwnDumpPermission() {
        return ThorBridge.run("pm grant com.thor.refreshrate android.permission.DUMP");
    }

    /** AYN's on-device privileged bridge; there is no app-side root dependency. */
    private static final class ThorBridge {
        private static IBinder service;

        static boolean run(String command) {
            try {
                if (service == null || !service.isBinderAlive()) {
                    Class<?> cls = Class.forName("android.os.ServiceManager");
                    Method method = cls.getDeclaredMethod("getService", String.class);
                    service = (IBinder) method.invoke(null, "PServerBinder");
                }
                if (service == null) return false;
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeStringArray(new String[] {command, "1"});
                    if (!service.transact(0, data, reply, 0)) return false;
                    // The vendor method returns a byte array. Reading it also
                    // propagates malformed replies into the safe fallback.
                    byte[] output = reply.createByteArray();
                    if (output != null) {
                        String result = new String(output, Charset.defaultCharset()).trim();
                        if (!result.isEmpty() && result.toLowerCase().contains("permission denied"))
                            return false;
                    }
                    return true;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            } catch (Exception | LinkageError e) {
                Log.w(TAG, "AYN privileged refresh-rate service unavailable", e);
                return false;
            }
        }
    }
}
