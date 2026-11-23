package com.thor.refreshrate;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
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
 * Applies AYN Smooth Display policies while both built-in panels are awake.
 *
 * <p>Threading (v1.0.5): every AYN bridge call is a synchronous binder round
 * trip that runs a shell command, and a switch can need several of them. Up to
 * v1.0.4 they ran on the accessibility service's main thread, stalling event
 * delivery during every switch. Now all bridge calls and all mutable state
 * below are confined to one process-wide worker thread. Public methods only
 * post to it (or touch volatile fields), so callers never block.
 *
 * <p>Requests are coalesced: if several targets arrive while the worker is
 * busy, only the newest is applied. That is the desired state anyway, and it
 * avoids, e.g., briefly applying 120 Hz for an app that was already closed.
 */
final class RateController {
    private static final String TAG = "ThorRefresh";
    private static final String MIN = "min_refresh_rate";
    private static final String PEAK = "peak_refresh_rate";
    static final String DUAL_SCREEN_MODE = "dual_screen_display_mode";
    private static final long WAKE_SETTLE_MS = 2000L;
    private static final long WAKE_REQUEST_COOLDOWN_MS = 6000L;
    private static final long RESTORE_SETTLE_MS = 1500L;
    private static final long RESTORE_RETRY_MS = 1200L;
    private static final long WAKE_TIMEOUT_MS = 12000L;
    private static final long RECOVERY_WINDOW_MS = 30000L;
    /** Upper bound for blocking service teardown while we relinquish the rate. */
    private static final long STOP_WAIT_MS = 2000L;
    private static final int MAX_RESTORE_ATTEMPTS = 3;
    private static final String OWNED = "controller.owned";
    private static final String OWNED_HZ = "controller.ownedHz";
    private static final String BASE_MIN = "controller.baselineMin";
    private static final String BASE_PEAK = "controller.baselinePeak";
    private static final String TEMP_WAKE_AT = "controller.temporaryBottomWakeAt";

    private static Handler sharedWorker;

    /**
     * One worker for the whole process. A reconnected service creates a new
     * controller; sharing the thread means an old controller's pending restore
     * and the new controller's work can never run concurrently.
     */
    private static synchronized Handler worker() {
        if (sharedWorker == null) {
            HandlerThread thread = new HandlerThread("Thor-Rate", Process.THREAD_PRIORITY_DEFAULT);
            thread.start();
            sharedWorker = new Handler(thread.getLooper());
        }
        return sharedWorker;
    }

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences prefs;
    private final Runnable onBottomDisplayWakeRequested;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler worker = worker();
    private final Runnable restoreBottomMode = this::restorePreviousDisplayMode;
    private final Runnable wakeTimeout = this::restorePreviousDisplayMode;
    private final Runnable applyLatest = this::applyLatestTarget;

    // Written from any thread.
    private volatile long safeAfterUptime;
    private volatile int latestHz;

    // Worker-thread only.
    private long lastWakeRequestUptime = -WAKE_REQUEST_COOLDOWN_MS;
    private boolean temporarilyEnabledBottom;
    private int restoreAttempts;

    /** @param onBottomDisplayWakeRequested always invoked on the main thread. */
    RateController(Context context, Runnable onBottomDisplayWakeRequested) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.prefs = new ProfileStore(context).preferences();
        this.onBottomDisplayWakeRequested = onBottomDisplayWakeRequested;
        // Delay the first write until Android has restored both panels.
        postponeDisplayChanges();
        worker.post(this::recoverInterruptedWake);
    }

    boolean canWrite() {
        return Settings.System.canWrite(context);
    }

    /** AYN's Smooth Display path is global, not exclusive to display 0.
     * A rate write must wait for both panels to settle (fail closed). Any thread. */
    void postponeDisplayChanges() {
        safeAfterUptime = SystemClock.uptimeMillis() + WAKE_SETTLE_MS;
    }

    /** Request a target rate (0 = relinquish). Returns immediately; any thread. */
    void onTargetChanged(int hz) {
        if (hz != 0 && hz != 60 && hz != 120) return;
        latestHz = hz;
        worker.removeCallbacks(applyLatest);
        worker.post(applyLatest);
    }

    void relinquish() { onTargetChanged(0); }

    /** A screen-on notification can finish an interrupted temporary switch. Any thread. */
    void resumeTemporaryRestore() {
        worker.post(this::restoreAfterSwitch);
    }

    /**
     * Called when Android disconnects the monitoring service. Blocks the caller
     * for at most {@link #STOP_WAIT_MS} so the override is released before the
     * process may be torn down, as the synchronous v1.0.4 code did.
     */
    void onServiceStopping() {
        CountDownLatch done = new CountDownLatch(1);
        worker.removeCallbacks(applyLatest);
        worker.post(() -> {
            try {
                stopOnWorker();
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(STOP_WAIT_MS, TimeUnit.MILLISECONDS))
                Log.w(TAG, "Relinquish still running at service teardown; continuing in background");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Everything below runs on the worker thread.
    // ------------------------------------------------------------------

    /** Recover a short, interrupted temporary wake after a service restart.
     * An old marker is discarded rather than turning off a screen that the
     * user might have enabled manually in the meantime. */
    private void recoverInterruptedWake() {
        long started = prefs.getLong(TEMP_WAKE_AT, 0L);
        long elapsed = System.currentTimeMillis() - started;
        if (started > 0L && elapsed >= 0L && elapsed < RECOVERY_WINDOW_MS
                && displayMode() == 0) {
            temporarilyEnabledBottom = true;
            worker.removeCallbacks(wakeTimeout);
            worker.postDelayed(wakeTimeout, WAKE_TIMEOUT_MS);
        } else if (started != 0L) {
            prefs.edit().remove(TEMP_WAKE_AT).commit();
        }
    }

    private void stopOnWorker() {
        // Never initiate a new asynchronous wake on teardown: there will be
        // no monitor left to retry the pending refresh-rate operation.
        if (panelsReady()) applyTarget(0, false);
        if (temporarilyEnabledBottom) {
            // The pending restore runs without depending on the service's
            // foreground detection, even if the switch was interrupted.
            worker.removeCallbacks(restoreBottomMode);
            long safeDelay = Math.max(0L, safeAfterUptime - SystemClock.uptimeMillis());
            worker.postDelayed(restoreBottomMode, safeDelay + RESTORE_SETTLE_MS);
        }
    }

    private void applyLatestTarget() {
        applyTarget(latestHz, true);
    }

    private boolean panelsReady() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive()) return false;
        if (displayMode() != 0) return false;
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return false;
        Display top = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Display bottom = BottomDisplay.find(dm);
        return top != null && top.getState() == Display.STATE_ON
                && bottom != null && bottom.getState() == Display.STATE_ON
                && SystemClock.uptimeMillis() >= safeAfterUptime;
    }

    private int displayMode() {
        return Settings.System.getInt(resolver, DUAL_SCREEN_MODE, -1);
    }

    private String read(String key) {
        return Settings.System.getString(resolver, key);
    }

    /** Executed only on foreground changes or when a profile is edited. */
    private void applyTarget(int hz, boolean allowWake) {
        if (!canWrite() || (hz != 0 && hz != 60 && hz != 120)) return;
        long startedAt = SystemClock.uptimeMillis();

        // Never restore the lower screen in the middle of a new rate write.
        worker.removeCallbacks(restoreBottomMode);
        // The vendor display-mode change may be asynchronous. Mode 1 here can
        // still mean our wake request is in flight, not that it has failed.
        int mode = displayMode();
        if (temporarilyEnabledBottom && mode != 0 && mode != 1) clearTemporaryWake();

        boolean owned = prefs.getBoolean(OWNED, false);
        int previousHz = prefs.getInt(OWNED_HZ, 0);
        String currentMin = read(MIN);
        String currentPeak = read(PEAK);

        // Respect changes made directly in the Thor control center.
        if (owned && !matchesOwned(currentMin, currentPeak, previousHz)) {
            prefs.edit().putBoolean(OWNED, false).remove(OWNED_HZ).commit();
            owned = false;
        }

        String value = hz == 120 ? "120.00001" : "60.0";
        if (hz == 0) {
            if (!owned) {
                restoreAfterSwitch();
                return;
            }
        } else {
            // Do not wake the lower display when nothing needs changing.
            if (owned && previousHz == hz) {
                restoreAfterSwitch();
                return;
            }
            if (!owned && same(currentMin, value) && same(currentPeak, value)) {
                restoreAfterSwitch();
                return;
            }
        }

        // The global rate keys may blank the Thor's lower panel if written in
        // top-only mode. Ask the stock assistant to enable dual-screen mode,
        // then return: AppMonitorService will retry after the panel settles.
        if (!panelsReady()) {
            if (allowWake) wakeBottomDisplayForSwitch();
            return;
        }

        boolean ok;
        if (hz == 0) {
            ok = writePair(prefs.getString(BASE_MIN, null),
                    prefs.getString(BASE_PEAK, null), currentMin, currentPeak);
            if (ok) {
                prefs.edit().putBoolean(OWNED, false).remove(OWNED_HZ)
                        .remove(BASE_MIN).remove(BASE_PEAK).commit();
            }
        } else {
            // Capture the manual settings before first overriding them.
            // commit() is deliberate: this state must survive a crash, and we
            // are on the worker thread, so the disk write blocks nothing visible.
            if (!owned) {
                prefs.edit().putString(BASE_MIN, currentMin)
                        .putString(BASE_PEAK, currentPeak).commit();
            }
            ok = writePair(value, value, currentMin, currentPeak);
            if (ok) {
                prefs.edit().putBoolean(OWNED, true).putInt(OWNED_HZ, hz).commit();
            } else {
                prefs.edit().putBoolean(OWNED, false).remove(OWNED_HZ).commit();
            }
        }
        Log.i(TAG, String.format(Locale.ROOT, "%s %s in %d ms",
                hz == 0 ? "Released override" : "Applied " + hz + " Hz",
                ok ? "ok" : "FAILED", SystemClock.uptimeMillis() - startedAt));
        // Restore top-only mode only when this controller turned the lower
        // panel on for the switch. An already-on panel must stay on.
        restoreAfterSwitch();
    }

    /** Let the rate change settle before restoring the user's original mode. */
    private void restoreAfterSwitch() {
        if (!temporarilyEnabledBottom || !panelsReady()) return;
        worker.removeCallbacks(restoreBottomMode);
        worker.postDelayed(restoreBottomMode, RESTORE_SETTLE_MS);
    }

    /** Restore top-only mode on the same AYN bridge used to wake the panel. */
    private void restorePreviousDisplayMode() {
        if (!temporarilyEnabledBottom) return;
        int mode = displayMode();
        if (mode != 0) {
            // The user or firmware has already changed the mode. Never override it.
            clearTemporaryWake();
            return;
        }

        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive()) {
            // Do not change displays in sleep. A screen-on event retries this.
            return;
        }
        if (restoreAttempts >= MAX_RESTORE_ATTEMPTS) {
            Log.w(TAG, "Could not restore top-only mode; leaving displays unchanged");
            clearTemporaryWake();
            return;
        }
        restoreAttempts++;
        if (!ThorBridge.run("settings put system " + DUAL_SCREEN_MODE + " 1")) {
            Log.w(TAG, "Could not restore the original top-only display mode");
        }
        // The vendor assistant may apply the mode asynchronously. Verify it,
        // and retry a bounded number of times if the request was ignored.
        worker.removeCallbacks(restoreBottomMode);
        if (displayMode() == 1) clearTemporaryWake();
        else worker.postDelayed(restoreBottomMode, RESTORE_RETRY_MS);
    }

    private void clearTemporaryWake() {
        temporarilyEnabledBottom = false;
        restoreAttempts = 0;
        worker.removeCallbacks(restoreBottomMode);
        worker.removeCallbacks(wakeTimeout);
        prefs.edit().remove(TEMP_WAKE_AT).commit();
    }

    /** Only top-only mode may be changed automatically. Never wake a sleeping
     * device or override bottom-only / unknown display configurations. */
    private void wakeBottomDisplayForSwitch() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive()) return;
        if (displayMode() != 1 || temporarilyEnabledBottom) return;
        DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display top = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (top == null || top.getState() != Display.STATE_ON) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastWakeRequestUptime < WAKE_REQUEST_COOLDOWN_MS) return;
        lastWakeRequestUptime = now;

        // Writing the stock mode key lets the vendor assistant restore its own
        // cover, touch state and display brightness in the normal way.
        if (!ThorBridge.run("settings put system " + DUAL_SCREEN_MODE + " 0")) {
            Log.w(TAG, "Could not request dual-screen mode before rate switch");
            return;
        }
        temporarilyEnabledBottom = true;
        restoreAttempts = 0;
        prefs.edit().putLong(TEMP_WAKE_AT, System.currentTimeMillis()).commit();
        worker.removeCallbacks(wakeTimeout);
        worker.postDelayed(wakeTimeout, WAKE_TIMEOUT_MS);
        postponeDisplayChanges();
        // The service's retry logic lives on the main thread.
        if (onBottomDisplayWakeRequested != null) main.post(onBottomDisplayWakeRequested);
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

    /**
     * One-time, user-initiated grant for our own declared DUMP permission.
     * Uses the same Thor vendor bridge already used for FPS switching; it does
     * not enable wireless debugging or grant privileges to other packages.
     * The caller MUST independently verify checkSelfPermission afterwards.
     * Blocking: call from a background thread.
     */
    static boolean grantOwnDumpPermission() {
        return ThorBridge.run("pm grant com.thor.refreshrate android.permission.DUMP");
    }

    /** AYN's on-device privileged bridge; there is no app-side root dependency.
     * Synchronized: used by the rate worker and by the UI's permission thread. */
    private static final class ThorBridge {
        private static IBinder service;

        static synchronized boolean run(String command) {
            long startedAt = SystemClock.uptimeMillis();
            boolean ok = false;
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
                        String result = new String(output, StandardCharsets.UTF_8).trim();
                        if (result.toLowerCase(Locale.ROOT).contains("permission denied"))
                            return false;
                    }
                    ok = true;
                    return true;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            } catch (Exception | LinkageError e) {
                Log.w(TAG, "AYN privileged refresh-rate service unavailable", e);
                return false;
            } finally {
                // Lets you measure real bridge latency: adb logcat -s ThorRefresh
                Log.d(TAG, String.format(Locale.ROOT, "bridge %s (%d ms): %s",
                        ok ? "ok" : "fail", SystemClock.uptimeMillis() - startedAt, command));
            }
        }
    }
}
