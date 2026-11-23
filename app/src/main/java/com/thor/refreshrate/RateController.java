package com.thor.refreshrate;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sets the Thor's refresh rate. Deliberately small (v1.0.12).
 *
 * One rule: min/peak_refresh_rate are only written while BOTH screens are on
 * and settled, because these settings apply to both panels and writing them
 * while the 60 Hz lower panel is off can leave it black. In top-only mode the
 * lower screen is switched on for the write and switched off again afterwards
 * (the proven v1.0.4 behaviour). Nothing else: no key presses, no overlays.
 *
 * Everything runs on one background thread; public methods only post to it.
 */
final class RateController {
    private static final String TAG = "ThorRefresh";
    private static final String MIN = "min_refresh_rate";
    private static final String PEAK = "peak_refresh_rate";
    static final String DUAL_SCREEN_MODE = "dual_screen_display_mode";
    private static final int MODE_BOTH = 0;
    private static final int MODE_TOP_ONLY = 1;

    /** A new target is applied once it has been stable this long. */
    private static final long SWITCH_DELAY_MS = 500L;
    /** No writes for this long after any screen/mode change. */
    private static final long SETTLE_MS = 2000L;
    /** While waiting for a woken lower screen, check again this often. */
    private static final long RETRY_MS = 1000L;
    /** Give up waiting for the lower screen after this long. */
    private static final long WAKE_TIMEOUT_MS = 8000L;
    /** Keep a woken lower screen on this long after the write. */
    private static final long RESTORE_DELAY_MS = 1500L;

    // Same keys as v1.0.4 so an update keeps its state.
    private static final String OWNED = "controller.owned";
    private static final String OWNED_HZ = "controller.ownedHz";
    private static final String BASE_MIN = "controller.baselineMin";
    private static final String BASE_PEAK = "controller.baselinePeak";
    private static final String WOKE_AT = "controller.temporaryBottomWakeAt";

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences prefs;
    private final Handler worker;
    private final Runnable apply = this::apply;
    private final Runnable restoreBottom = this::restoreBottom;

    private volatile int target;
    /** A target that failed (write rejected / screen never came on). Not retried
     * until a different target is requested, so failures can never loop. */
    private volatile int failedTarget = -1;
    private volatile long settleUntil;
    // Worker thread only.
    private boolean wokeBottom;
    private long wokeAt;

    /** The second argument is unused since v1.0.12 (retries are internal). */
    RateController(Context context, Runnable unused) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.prefs = new ProfileStore(context).preferences();
        HandlerThread thread = new HandlerThread("Thor-Rate");
        thread.start();
        this.worker = new Handler(thread.getLooper());
        postponeDisplayChanges();
        worker.post(this::recoverAfterRestart);
    }

    // ---------------------------------------------------------------- API

    void onTargetChanged(int hz) {
        if (hz != 0 && hz != 60 && hz != 120) return;
        if (hz != target) failedTarget = -1;
        target = hz;
        worker.removeCallbacks(apply);
        worker.postDelayed(apply, SWITCH_DELAY_MS);
    }

    void relinquish() { onTargetChanged(0); }

    /** Called by the service on screen on/off and display-mode changes. */
    void postponeDisplayChanges() {
        settleUntil = SystemClock.uptimeMillis() + SETTLE_MS;
    }

    /** Called after screen-on; lets a pending switch finish. */
    void resumeTemporaryRestore() {
        worker.removeCallbacks(apply);
        worker.post(apply);
    }

    /** Service is going away: release the override if it can be done safely now. */
    void onServiceStopping() {
        CountDownLatch done = new CountDownLatch(1);
        worker.removeCallbacks(apply);
        worker.post(() -> {
            try {
                target = 0;
                if (bothScreensReady()) apply();
                if (wokeBottom) restoreBottom();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean canWrite() { return Settings.System.canWrite(context); }

    // ---------------------------------------------------- worker thread

    private void apply() {
        if (!canWrite()) return;
        int hz = target;
        if (hz == failedTarget) { done(); return; }
        String curMin = read(MIN);
        String curPeak = read(PEAK);

        // A manual change in AYN's control center ends our override.
        boolean owned = prefs.getBoolean(OWNED, false);
        if (owned && !isOurs(curMin, curPeak, prefs.getInt(OWNED_HZ, 0))) {
            Log.i(TAG, "Rate changed outside the app; leaving it alone");
            prefs.edit().putBoolean(OWNED, false).remove(OWNED_HZ)
                    .remove(BASE_MIN).remove(BASE_PEAK).commit();
            owned = false;
        }

        String wantMin;
        String wantPeak;
        if (hz == 0) {
            if (!owned) { done(); return; }          // nothing of ours to undo
            wantMin = prefs.getString(BASE_MIN, null);
            wantPeak = prefs.getString(BASE_PEAK, null);
        } else {
            wantMin = wantPeak = hz == 120 ? "120.00001" : "60.0";
        }
        if (same(curMin, wantMin) && same(curPeak, wantPeak)) {
            if (hz != 0 && owned) prefs.edit().putInt(OWNED_HZ, hz).commit();
            done();
            return;
        }

        if (!topScreenOn()) return;                  // screen-on event retries

        if (!bothScreensReady()) {
            int mode = displayMode();
            if (mode == MODE_TOP_ONLY && !wokeBottom) {
                if (!wakeBottom()) { failedTarget = hz; return; }
            } else if (wokeBottom && SystemClock.uptimeMillis() - wokeAt > WAKE_TIMEOUT_MS) {
                Log.w(TAG, "Lower screen did not come on; giving up this switch");
                failedTarget = hz;
                restoreBottom();
                return;
            } else if (!wokeBottom) {
                // Lower screen off some other way (e.g. AYN button long-press):
                // wait until it is on again; the service retries then.
                Log.i(TAG, "Waiting for the lower screen (mode " + mode + ")");
                return;
            }
            worker.removeCallbacks(apply);
            worker.postDelayed(apply, RETRY_MS);
            return;
        }

        // Both screens on and settled: write.
        if (hz != 0 && !owned) {
            prefs.edit().putString(BASE_MIN, curMin).putString(BASE_PEAK, curPeak).commit();
        }
        long started = SystemClock.uptimeMillis();
        boolean ok = writePair(wantMin, wantPeak, curMin, curPeak);
        if (ok && hz != 0) {
            prefs.edit().putBoolean(OWNED, true).putInt(OWNED_HZ, hz).commit();
        } else if (ok) {
            prefs.edit().putBoolean(OWNED, false).remove(OWNED_HZ)
                    .remove(BASE_MIN).remove(BASE_PEAK).commit();
        }
        if (!ok) failedTarget = hz;
        Log.i(TAG, String.format(Locale.ROOT, "%s %s in %d ms",
                hz == 0 ? "Restored manual rate" : "Set " + hz + " Hz",
                ok ? "ok" : "FAILED", SystemClock.uptimeMillis() - started));
        done();
    }

    /** After a switch: switch a lower screen we turned on back off. */
    private void done() {
        if (!wokeBottom) return;
        worker.removeCallbacks(restoreBottom);
        worker.postDelayed(restoreBottom, RESTORE_DELAY_MS);
    }

    private boolean wakeBottom() {
        if (!ThorBridge.run("settings put system " + DUAL_SCREEN_MODE + " " + MODE_BOTH)) {
            Log.w(TAG, "Could not turn the lower screen on");
            return false;
        }
        Log.i(TAG, "Lower screen on for the switch");
        wokeBottom = true;
        wokeAt = SystemClock.uptimeMillis();
        prefs.edit().putLong(WOKE_AT, System.currentTimeMillis()).commit();
        postponeDisplayChanges();
        return true;
    }

    private void restoreBottom() {
        worker.removeCallbacks(restoreBottom);
        if (!wokeBottom) return;
        wokeBottom = false;
        prefs.edit().remove(WOKE_AT).commit();
        // Only undo our own change; if the user changed the mode meanwhile, keep it.
        if (displayMode() == MODE_BOTH && topScreenOn()) {
            ThorBridge.run("settings put system " + DUAL_SCREEN_MODE + " " + MODE_TOP_ONLY);
            postponeDisplayChanges();
            Log.i(TAG, "Lower screen off again");
        }
    }

    /** If the app died while the lower screen was temporarily on, switch it off. */
    private void recoverAfterRestart() {
        long at = prefs.getLong(WOKE_AT, 0L);
        if (at == 0L) return;
        long age = System.currentTimeMillis() - at;
        if (age >= 0 && age < 30_000L && displayMode() == MODE_BOTH) {
            wokeBottom = true;
            restoreBottom();
        } else {
            prefs.edit().remove(WOKE_AT).commit();
        }
    }

    // -------------------------------------------------------- screens

    private boolean topScreenOn() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive()) return false;
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display top = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        return top != null && top.getState() == Display.STATE_ON;
    }

    private boolean bothScreensReady() {
        if (!topScreenOn() || displayMode() != MODE_BOTH) return false;
        if (SystemClock.uptimeMillis() < settleUntil) return false;
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display bottom = lowerScreen(dm);
        return bottom != null && bottom.getState() == Display.STATE_ON;
    }

    /** Display 4 on stock firmware; otherwise any other non-default display. */
    private static Display lowerScreen(DisplayManager dm) {
        if (dm == null) return null;
        Display d = dm.getDisplay(4);
        if (d != null) return d;
        for (Display other : dm.getDisplays()) {
            if (other.getDisplayId() != Display.DEFAULT_DISPLAY) return other;
        }
        return null;
    }

    private int displayMode() {
        return Settings.System.getInt(resolver, DUAL_SCREEN_MODE, -1);
    }

    // ------------------------------------------------------- settings

    private String read(String key) { return Settings.System.getString(resolver, key); }

    /** Ours = min = peak = Hz (also accepts the 60/120 range written by v1.0.7-1.0.11). */
    private static boolean isOurs(String min, String peak, int hz) {
        if (hz == 0) return false;
        if (near(min, hz) && near(peak, hz)) return true;
        return hz == 120 && near(min, 60) && near(peak, 120);
    }

    /** Raise peak before min, lower min before peak: never min > peak in between. */
    private boolean writePair(String min, String peak, String oldMin, String oldPeak) {
        boolean raising = parse(peak) > parse(oldPeak) || parse(min) > parse(oldMin);
        boolean ok = raising
                ? write(PEAK, peak) && write(MIN, min)
                : write(MIN, min) && write(PEAK, peak);
        return ok && same(read(MIN), min) && same(read(PEAK), peak);
    }

    private boolean write(String key, String value) {
        if (!MIN.equals(key) && !PEAK.equals(key)) return false;
        if (value != null && !value.matches("[0-9]{1,3}(\\.[0-9]{1,5})?")) return false;
        String cmd = value == null ? "settings delete system " + key
                : "settings put system " + key + " " + value;
        if (ThorBridge.run(cmd)) return true;
        try {
            return Settings.System.putString(resolver, key, value);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not write " + key, e);
            return false;
        }
    }

    private static boolean near(String value, int hz) {
        return value != null && Math.abs(parse(value) - hz) < 0.1f;
    }

    private static float parse(String value) {
        try { return value == null ? 0f : Float.parseFloat(value); }
        catch (NumberFormatException e) { return 0f; }
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null) return a == null && b == null;
        return Math.abs(parse(a) - parse(b)) < 0.01f;
    }

    // --------------------------------------------------------- bridge

    /** One-time grant of our own DUMP permission (DroidDeck detection). Background thread. */
    static boolean grantOwnDumpPermission() {
        return ThorBridge.run("pm grant com.thor.refreshrate android.permission.DUMP");
    }

    /** AYN's built-in privileged command service; only fixed commands are sent. */
    private static final class ThorBridge {
        private static IBinder service;

        static synchronized boolean run(String command) {
            try {
                if (service == null || !service.isBinderAlive()) {
                    Method get = Class.forName("android.os.ServiceManager")
                            .getDeclaredMethod("getService", String.class);
                    service = (IBinder) get.invoke(null, "PServerBinder");
                }
                if (service == null) return false;
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeStringArray(new String[] {command, "1"});
                    if (!service.transact(0, data, reply, 0)) return false;
                    byte[] out = reply.createByteArray();
                    String text = out == null ? "" : new String(out, StandardCharsets.UTF_8);
                    return !text.toLowerCase(Locale.ROOT).contains("permission denied");
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            } catch (Exception | LinkageError e) {
                Log.w(TAG, "AYN bridge unavailable: " + command, e);
                return false;
            }
        }
    }
}
