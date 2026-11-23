package com.thor.refreshrate;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** App changes use Accessibility events; emulator queries run only in active sessions. */
public final class AppMonitorService extends AccessibilityService {
    /** Quiet period before resolving the top window after window-list churn. */
    private static final long WINDOW_LOOKUP_DEBOUNCE_MS = 120L;
    /** Second look once app-switch animations have finished (v1.0.9). Returning
     * to an app via recents/back/home may send no further event at the end. */
    private static final long WINDOW_LOOKUP_CONFIRM_MS = 700L;
    private static final long IME_REFRESH_MS = 60_000L;
    /** Draws the dashboard overlay that the physical AYN button toggles. */
    private static final String AYN_PANEL_PACKAGE = "com.odin.dualscreen.assistant";
    // Adaptive emulator polling: fast right after a change, slow once stable.
    private static final long RETRO_FAST_MS = 700L;
    private static final long DROID_FAST_MS = 800L;
    private static final long POLL_SLOW_MS = 2000L;
    private static final int STABLE_READINGS_BEFORE_SLOW = 5;

    private ProfileStore profiles;
    private RateController rates;
    private String activeId;
    private String activePkg;
    private String activeGameId;
    private SharedPreferences.OnSharedPreferenceChangeListener profileListener;
    private volatile ScheduledExecutorService retroExecutor;
    private ScheduledFuture<?> retroTask;
    private ScheduledFuture<?> droidTask;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Separate tokens: v1.0.4 shared one counter, so stopping the DroidDeck
    // watcher also silently orphaned a running RetroArch watcher.
    private volatile long retroGeneration;
    private volatile long droidGeneration;
    private boolean retroResolved;
    private boolean droidResolved;
    private boolean cleaned;
    private ContentObserver displayModeObserver;
    private BroadcastReceiver screenReceiver;
    private final Runnable resumeRates = this::refreshCurrentRate;
    private final Runnable topWindowLookup = this::resolveTopWindow;
    private final Runnable topWindowConfirm = this::resolveTopWindow;
    private Set<String> imePackages = Collections.emptySet();
    private long imeRefreshedAt = -IME_REFRESH_MS;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        cleaned = false;
        profiles = new ProfileStore(this);
        rates = new RateController(this, this::scheduleRateRefresh, this::isAynPanelShowing);
        // The bottom display can disappear during TOP-only mode or sleep.
        // Resume a profile only after the dual-screen mode has settled.
        displayModeObserver = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { scheduleRateRefresh(); }
        };
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(RateController.DUAL_SCREEN_MODE),
                false, displayModeObserver);
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                scheduleRateRefresh();
            }
        };
        IntentFilter screenEvents = new IntentFilter();
        screenEvents.addAction(Intent.ACTION_SCREEN_ON);
        screenEvents.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, screenEvents);
        retroExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Thor-Game-Status");
            t.setDaemon(true);
            return t;
        });
        profileListener = (p, key) -> {
            if (ProfileStore.MONITOR_ENABLED.equals(key)) {
                main.post(this::syncEnabledState);
                return;
            }
            if (profiles.enabled() && activePkg != null && key != null &&
                    (key.equals("hz." + ProfileStore.appId(activePkg))
                    || (activeGameId != null && key.equals("hz." + activeGameId))
                    || (activeGameId != null && key.equals("hz." + ProfileStore.gameplayId(activePkg))))) {
                rates.onTargetChanged(profiles.rateFor(activePkg, activeGameId));
            }
        };
        profiles.preferences().registerOnSharedPreferenceChangeListener(profileListener);
        if (!profiles.enabled()) {
            rates.relinquish();
        } else {
            // After a reboot or service restart, resume the visible app's profile
            // without waiting for the next window transition.
            main.postDelayed(() -> {
                if (profiles == null || !profiles.enabled()) return;
                WindowTarget visible = topScreenTarget();
                if (visible == null) return;
                int mode = ActivityModeDetector.detect(visible.pkg, visible.activity);
                setForeground(visible.pkg, mode == ActivityModeDetector.SESSION
                        ? ProfileStore.gameplayId(visible.pkg) : null);
                refreshCurrentRate();
            }, 2100);
        }
    }


    private void scheduleRateRefresh() {
        if (rates == null || cleaned) return;
        rates.postponeDisplayChanges();
        main.removeCallbacks(resumeRates);
        main.postDelayed(resumeRates, 2100);
    }

    private void refreshCurrentRate() {
        if (rates == null || profiles == null) return;
        if (!profiles.enabled()) {
            rates.relinquish();
            return;
        }
        if (activePkg != null) {
            rates.onTargetChanged(profiles.rateFor(activePkg, activeGameId));
        } else {
            WindowTarget visible = topScreenTarget();
            if (visible != null) {
                int mode = ActivityModeDetector.detect(visible.pkg, visible.activity);
                setForeground(visible.pkg, mode == ActivityModeDetector.SESSION
                        ? ProfileStore.gameplayId(visible.pkg) : null);
            }
        }
        // Even if no foreground app can be identified after screen-on, restore
        // a lower panel that this service temporarily enabled for a rate write.
        rates.resumeTemporaryRestore();
    }

    /** Off means no app work, no game polling, and no retained FPS override. */
    private void syncEnabledState() {
        if (profiles == null || rates == null || profiles.enabled()) return;
        main.removeCallbacks(topWindowLookup);
        main.removeCallbacks(topWindowConfirm);
        stopRetroWatcher();
        stopDroidWatcher();
        activePkg = null;
        activeId = null;
        activeGameId = null;
        rates.relinquish();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || profiles == null || !profiles.enabled()) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        int displayId = Build.VERSION.SDK_INT >= 33 ? event.getDisplayId() : -1;
        if (displayId > Display.DEFAULT_DISPLAY) return;

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && displayId == Display.DEFAULT_DISPLAY && event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if (!skip(pkg)) {
                String activity = event.getClassName() == null ? null : event.getClassName().toString();
                if (ProfileStore.isRetroArch(pkg)) {
                    if (pkg.equals(activePkg)) return;
                } else if (ActivityModeDetector.supports(pkg)) {
                    // Android reports activity changes without polling. Ignore dialogs,
                    // overlays and unidentified sub-windows of the active emulator.
                    if (pkg.equals(activePkg) && ActivityModeDetector.detect(pkg, activity)
                            == ActivityModeDetector.UNKNOWN) return;
                } else if (pkg.equals(activePkg)) {
                    return;
                }
                // The activity event names the app directly. Still confirm with
                // the window list once animations settle, in case this event was
                // for a window that is about to be covered again.
                main.removeCallbacks(topWindowLookup);
                handleTarget(new WindowTarget(pkg, activity));
                scheduleConfirm();
                return;
            }
        }
        // No usable app in the event: resolve the top window once things settle.
        // Window-list changes arrive in bursts (animations, IME, toasts), and each
        // lookup is a cross-process query, so coalesce them.
        main.removeCallbacks(topWindowLookup);
        main.postDelayed(topWindowLookup, WINDOW_LOOKUP_DEBOUNCE_MS);
        scheduleConfirm();
    }

    private void scheduleConfirm() {
        main.removeCallbacks(topWindowConfirm);
        main.postDelayed(topWindowConfirm, WINDOW_LOOKUP_CONFIRM_MS);
    }

    private void resolveTopWindow() {
        if (profiles == null || !profiles.enabled() || cleaned) return;
        WindowTarget target = topScreenTarget();
        if (target != null) handleTarget(target);
    }

    private void handleTarget(WindowTarget target) {
        if (target.pkg.equals(activePkg) && ProfileStore.isRetroArch(target.pkg)) return;
        // DroidDeck can expose its frontend while a game remains on the top display
        // (for example, when the user taps a bottom-screen control). Its actual
        // Steam focus is authoritative until Android leaves the package.
        if (target.pkg.equals(activePkg) && ActivityModeDetector.isDroidDeck(target.pkg)
                && droidTask != null) return;
        if (ActivityModeDetector.supports(target.pkg)) {
            int mode = ActivityModeDetector.detect(target.pkg, target.activity);
            if (mode == ActivityModeDetector.UNKNOWN && target.pkg.equals(activePkg)) return;
            String id = mode == ActivityModeDetector.SESSION
                    ? ProfileStore.gameplayId(target.pkg) : null;
            setForeground(target.pkg, id);
        } else {
            // Ordinary Android apps need only their package name. Window titles
            // are not ROM identities and caused the spurious duplicate entries.
            setForeground(target.pkg, null);
        }
    }

    private void setForeground(String pkg, String gameId) {
        if (!profiles.enabled()) return;
        String id = gameId == null ? ProfileStore.appId(pkg) : gameId;
        // For DroidDeck, session focus (not UI activity/secondary-screen events)
        // determines whether the user is in Steam's menu or a game.
        boolean observingDroidDeck = ActivityModeDetector.isDroidDeck(pkg)
                && DroidDeckProbe.hasPermission(this);
        if (id.equals(activeId) && (!observingDroidDeck || droidTask != null)) return;
        boolean newPackage = !pkg.equals(activePkg);
        activeId = id;
        activePkg = pkg;
        activeGameId = gameId;

        if (newPackage) stopRetroWatcher();
        if (!observingDroidDeck) stopDroidWatcher();
        if (ProfileStore.isRetroArch(pkg)) {
            if (newPackage) startRetroWatcher(pkg);
        } else if (observingDroidDeck) {
            // Keep this observer even if DroidDeck's frontend is briefly shown
            // over a running game or the bottom screen receives a tap.
            if (droidTask == null) startDroidWatcher(pkg);
        } else {
            rates.onTargetChanged(profiles.rateFor(pkg, gameId));
        }

        if (!pkg.equals(getPackageName())) {
            if (newPackage) profiles.recordRecentApp(pkg);
            String appId = ProfileStore.appId(pkg);
            profiles.observed(appId, pkg,
                    profiles.isKnown(appId) ? null : appLabel(pkg), false);
        }
    }

    private void startRetroWatcher(String pkg) {
        ScheduledExecutorService executor = retroExecutor;
        if (executor == null || executor.isShutdown()) return;
        retroResolved = false;
        final long token = ++retroGeneration;
        final PollPace pace = new PollPace(RETRO_FAST_MS);
        final int[] misses = {0}; // Only touched on the single executor thread.
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (retroGeneration != token) return;
                RetroArchProbe.Status status = RetroArchProbe.getStatus();
                if (status == null) {
                    misses[0]++;
                    // If Network Commands is disabled, fall back to the app's menu profile.
                    main.post(() -> {
                        if (retroGeneration != token || !pkg.equals(activePkg)
                                || !profiles.enabled()) return;
                        if (!retroResolved) {
                            retroResolved = true;
                            rates.onTargetChanged(profiles.rateFor(pkg, null));
                        }
                    });
                    // Three unanswered queries then stop; don't poll forever unnecessarily.
                    if (misses[0] >= 3) return;
                } else {
                    misses[0] = 0;
                    main.post(() -> applyRetroStatus(pkg, token, status));
                }
                schedulePoll(this, pace.next(status == null ? null : status.key()),
                        () -> retroGeneration == token);
            }
        };
        retroTask = schedulePoll(poll, 0L, () -> true);
    }

    private void applyRetroStatus(String pkg, long token, RetroArchProbe.Status status) {
        if (retroGeneration != token || !pkg.equals(activePkg) || !profiles.enabled()) return;
        String gameId = status.loaded ? (status.idPart == null
                ? ProfileStore.gameplayId(pkg) : ProfileStore.gameId(pkg, status.idPart)) : null;
        String id = gameId == null ? ProfileStore.appId(pkg) : gameId;
        if (retroResolved && id.equals(activeId)) return;
        retroResolved = true;
        activeId = id;
        activeGameId = gameId;
        rates.onTargetChanged(profiles.rateFor(pkg, gameId));
        if (status.loaded && status.idPart != null) {
            profiles.observed(gameId, pkg, status.title, true);
        }
    }

    private void startDroidWatcher(String pkg) {
        ScheduledExecutorService executor = retroExecutor;
        if (executor == null || executor.isShutdown()) return;
        droidResolved = false;
        final long token = ++droidGeneration;
        final PollPace pace = new PollPace(DROID_FAST_MS);
        final int[] misses = {0}; // Only touched on the single executor thread.
        final MenuLatch latch = new MenuLatch();
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (droidGeneration != token) return;
                DroidDeckProbe.State status = DroidDeckProbe.read(AppMonitorService.this);
                if (status == null) {
                    if (++misses[0] >= 3) {
                        main.post(() -> {
                            if (token != droidGeneration || !pkg.equals(activePkg)
                                    || !profiles.enabled()) return;
                            // Permission/provider may be unavailable on an older build.
                            // Preserve the previous v1.5 session fallback.
                            if (!droidResolved) rates.onTargetChanged(profiles.rateFor(pkg,
                                    activeGameId));
                            stopDroidWatcher();
                        });
                        return;
                    }
                } else {
                    misses[0] = 0;
                    // Never delay the FPS change waiting for the game's title.
                    if (latch.accept(status.steamGameId, status.confirmed))
                        main.post(() -> applyDroidState(pkg, token, status));
                }
                String reading = status == null ? null
                        : status.confirmed ? String.valueOf(status.steamGameId) : "unconfirmed";
                schedulePoll(this, pace.next(reading), () -> droidGeneration == token);
            }
        };
        droidTask = schedulePoll(poll, 0L, () -> true);
    }

    /** Self-rescheduling poll. Stopping a watcher bumps its token, which ends the chain. */
    private ScheduledFuture<?> schedulePoll(Runnable poll, long delayMs, Alive alive) {
        ScheduledExecutorService executor = retroExecutor;
        if (executor == null || executor.isShutdown() || !alive.get()) return null;
        try {
            return executor.schedule(poll, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shutDown) {
            return null;
        }
    }

    private void applyDroidState(String pkg, long token, DroidDeckProbe.State state) {
        if (token != droidGeneration || !pkg.equals(activePkg) || !profiles.enabled()) return;
        droidResolved = true;
        String gameId = state.steamGameId == null
                ? null : ProfileStore.gameId(pkg, "steam-" + state.steamGameId);
        String id = gameId == null ? ProfileStore.appId(pkg) : gameId;
        if (id.equals(activeId)) return;
        activeId = id;
        activeGameId = gameId;
        // One existing FPS-mode operation, and only if the effective rate changes.
        rates.onTargetChanged(profiles.rateFor(pkg, gameId));
        if (gameId != null) {
            profiles.observed(gameId, pkg, "Steam game " + state.steamGameId, true);
        }
    }

    private void stopDroidWatcher() {
        ++droidGeneration;
        if (droidTask != null) {
            droidTask.cancel(false);
            droidTask = null;
        }
    }

    private void stopRetroWatcher() {
        ++retroGeneration; // Discard any in-flight status response from the previous foreground app.
        if (retroTask != null) {
            retroTask.cancel(false);
            retroTask = null;
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence name = pm.getApplicationLabel(info);
            if (name != null && name.length() > 0) return name.toString();
        } catch (PackageManager.NameNotFoundException ignored) { }
        return pkg;
    }

    private WindowTarget topScreenTarget() {
        try {
            SparseArray<List<AccessibilityWindowInfo>> screens = getWindowsOnAllDisplays();
            List<AccessibilityWindowInfo> windows = screens.get(Display.DEFAULT_DISPLAY);
            if (windows == null) return null;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                // Only the root's package name is needed. Android 13+ can skip
                // prefetching the node subtree; fall back to a normal fetch.
                AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= 33
                        ? window.getRoot(0) : null;
                if (root == null) root = window.getRoot();
                // The topmost app window is unreadable (mid-animation): report
                // "unknown" rather than wrongly picking the app underneath it.
                // The confirmation lookup tries again once it has settled.
                if (root == null || root.getPackageName() == null) return null;
                String pkg = root.getPackageName().toString();
                if (skip(pkg)) continue;
                return new WindowTarget(pkg, null);
            }
        } catch (SecurityException | IllegalStateException e) {
            // Some firmware versions restrict access to other apps' window titles.
        }
        return null;
    }

    /**
     * Whether AYN's dashboard overlay is visible on a secondary display.
     * Called on RateController's worker thread; accessibility window queries
     * are binder calls and are safe off the main thread.
     */
    private boolean isAynPanelShowing() {
        if (cleaned) return false;
        SparseArray<List<AccessibilityWindowInfo>> screens = getWindowsOnAllDisplays();
        if (screens == null) return false;
        for (int i = 0; i < screens.size(); i++) {
            if (screens.keyAt(i) == Display.DEFAULT_DISPLAY) continue;
            List<AccessibilityWindowInfo> windows = screens.valueAt(i);
            if (windows == null) continue;
            for (AccessibilityWindowInfo window : windows) {
                CharSequence title = window.getTitle();
                if (title != null && title.toString().contains(AYN_PANEL_PACKAGE)) return true;
                AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= 33
                        ? window.getRoot(0) : window.getRoot();
                if (root != null && root.getPackageName() != null
                        && AYN_PANEL_PACKAGE.contentEquals(root.getPackageName())) return true;
            }
        }
        return false;
    }

    private boolean skip(String pkg) {
        return pkg.isEmpty() || pkg.equals("com.android.systemui")
                || pkg.equals("com.odin.dualscreen.assistant")
                || pkg.equals("com.odin.gameassistant")
                || pkg.equals("com.google.android.inputmethod.latin")
                || isInputMethod(pkg);
    }

    /** Any installed keyboard, not just Gboard, is an overlay rather than an app switch. */
    private boolean isInputMethod(String pkg) {
        long now = SystemClock.uptimeMillis();
        if (now - imeRefreshedAt >= IME_REFRESH_MS) {
            imeRefreshedAt = now;
            try {
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) {
                    Set<String> found = new HashSet<>();
                    for (InputMethodInfo info : imm.getInputMethodList())
                        found.add(info.getPackageName());
                    imePackages = found;
                }
            } catch (RuntimeException e) {
                // Keep the previous list; the hardcoded Gboard entry still applies.
            }
        }
        return imePackages.contains(pkg);
    }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        if (cleaned) return;
        cleaned = true;
        main.removeCallbacks(resumeRates);
        main.removeCallbacks(topWindowLookup);
        main.removeCallbacks(topWindowConfirm);
        if (displayModeObserver != null) {
            getContentResolver().unregisterContentObserver(displayModeObserver);
            displayModeObserver = null;
        }
        if (screenReceiver != null) {
            unregisterReceiver(screenReceiver);
            screenReceiver = null;
        }
        stopRetroWatcher();
        stopDroidWatcher();
        ScheduledExecutorService executor = retroExecutor;
        retroExecutor = null;
        if (executor != null) executor.shutdownNow();
        if (profiles != null && profileListener != null) {
            profiles.preferences().unregisterOnSharedPreferenceChangeListener(profileListener);
            profileListener = null;
        }
        if (rates != null) rates.onServiceStopping();
    }

    private interface Alive {
        boolean get();
    }

    /** Polls fast right after a change, then backs off while the reading is stable. */
    private static final class PollPace {
        private final long fastMs;
        private String last;
        private boolean first = true;
        private int stableReadings;

        PollPace(long fastMs) { this.fastMs = fastMs; }

        long next(String reading) {
            if (!first && Objects.equals(reading, last)) {
                if (stableReadings < STABLE_READINGS_BEFORE_SLOW) stableReadings++;
            } else {
                first = false;
                last = reading;
                stableReadings = 0;
            }
            return stableReadings >= STABLE_READINGS_BEFORE_SLOW ? POLL_SLOW_MS : fastMs;
        }
    }

    /** A confirmed Steam game is immediate; menu needs two consecutive reports. */
    private static final class MenuLatch {
        private int menuReadings;

        boolean accept(String gameId, boolean confirmed) {
            if (!confirmed) {
                menuReadings = 0;
                return false;
            }
            if (gameId != null) {
                menuReadings = 0;
                return true;
            }
            return ++menuReadings >= 2;
        }
    }

    private static final class WindowTarget {
        final String pkg;
        final String activity;
        WindowTarget(String pkg, String activity) {
            this.pkg = pkg;
            this.activity = activity;
        }
    }
}
