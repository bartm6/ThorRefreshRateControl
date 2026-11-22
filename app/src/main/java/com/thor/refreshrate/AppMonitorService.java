package com.thor.refreshrate;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.database.ContentObserver;
import android.provider.Settings;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** App changes use Accessibility events; emulator queries run only in active sessions. */
public final class AppMonitorService extends AccessibilityService {
    private ProfileStore profiles;
    private RateController rates;
    private String activeId;
    private String activePkg;
    private String activeGameId;
    private SharedPreferences.OnSharedPreferenceChangeListener profileListener;
    private ScheduledExecutorService retroExecutor;
    private ScheduledFuture<?> retroTask;
    private ScheduledFuture<?> droidTask;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long generation;
    private boolean retroResolved;
    private boolean droidResolved;
    private boolean cleaned;
    private ContentObserver displayModeObserver;
    private BroadcastReceiver screenReceiver;
    private final Runnable resumeRates = this::refreshCurrentRate;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        cleaned = false;
        profiles = new ProfileStore(this);
        rates = new RateController(this);
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
        if (rates == null) return;
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
    }

    /** Off means no app work, no game polling, and no retained FPS override. */
    private void syncEnabledState() {
        if (profiles == null || rates == null || profiles.enabled()) return;
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

        WindowTarget target = null;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && displayId == Display.DEFAULT_DISPLAY && event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if (!skip(pkg)) {
                String activity = event.getClassName() == null ? null : event.getClassName().toString();
                if (ProfileStore.isRetroArch(pkg)) {
                    if (pkg.equals(activePkg)) return;
                    target = new WindowTarget(pkg, activity);
                } else if (ActivityModeDetector.supports(pkg)) {
                    // Android reports activity changes without polling. Ignore dialogs,
                    // overlays and unidentified sub-windows of the active emulator.
                    if (pkg.equals(activePkg) && ActivityModeDetector.detect(pkg, activity)
                            == ActivityModeDetector.UNKNOWN) return;
                    target = new WindowTarget(pkg, activity);
                } else {
                    if (pkg.equals(activePkg)) return;
                    target = new WindowTarget(pkg, activity);
                }
            }
        }
        if (target == null) target = topScreenTarget();
        if (target == null) return;
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
        if (retroExecutor == null || retroExecutor.isShutdown()) return;
        retroResolved = false;
        long token = ++generation;
        final int[] misses = {0};
        retroTask = retroExecutor.scheduleWithFixedDelay(() -> {
            RetroArchProbe.Status status = RetroArchProbe.getStatus();
            if (status == null) {
                misses[0]++;
                // If Network Commands is disabled, fall back to the app's menu profile.
                // Three unanswered queries then stop; don't poll forever unnecessarily.
                main.post(() -> {
                    if (generation != token || !pkg.equals(activePkg) || !profiles.enabled()) return;
                    if (!retroResolved) {
                        retroResolved = true;
                        rates.onTargetChanged(profiles.rateFor(pkg, null));
                    }
                    if (misses[0] >= 3 && retroTask != null) retroTask.cancel(false);
                });
            } else {
                misses[0] = 0;
                main.post(() -> applyRetroStatus(pkg, token, status));
            }
        }, 0, 700, TimeUnit.MILLISECONDS);
    }

    private void applyRetroStatus(String pkg, long token, RetroArchProbe.Status status) {
        if (generation != token || !pkg.equals(activePkg) || !profiles.enabled()) return;
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
        if (retroExecutor == null || retroExecutor.isShutdown()) return;
        droidResolved = false;
        long token = ++generation;
        final int[] misses = {0};
        final MenuLatch latch = new MenuLatch();
        droidTask = retroExecutor.scheduleWithFixedDelay(() -> {
            DroidDeckProbe.State status = DroidDeckProbe.read(this);
            if (status == null) {
                if (++misses[0] >= 3) {
                    main.post(() -> {
                        if (token != generation || !pkg.equals(activePkg) || !profiles.enabled()) return;
                        // Permission/provider may be unavailable on an older build.
                        // Preserve the previous v1.5 session fallback.
                        if (!droidResolved) rates.onTargetChanged(profiles.rateFor(pkg,
                                activeGameId));
                        stopDroidWatcher();
                    });
                }
            } else {
                misses[0] = 0;
                if (!latch.accept(status.steamGameId, status.confirmed)) return;
                // Never delay the FPS change waiting for the game's title.
                main.post(() -> applyDroidState(pkg, token, status));
            }
        }, 0, 800, TimeUnit.MILLISECONDS);
    }

    private void applyDroidState(String pkg, long token, DroidDeckProbe.State state) {
        if (token != generation || !pkg.equals(activePkg) || !profiles.enabled()) return;
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
        ++generation;
        if (droidTask != null) {
            droidTask.cancel(false);
            droidTask = null;
        }
    }

    private void stopRetroWatcher() {
        ++generation; // Discard any in-flight status response from the previous foreground app.
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
                android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                String pkg = root.getPackageName().toString();
                if (skip(pkg)) continue;
                return new WindowTarget(pkg, null);
            }
        } catch (SecurityException | IllegalStateException e) {
            // Some firmware versions restrict access to other apps' window titles.
        }
        return null;
    }

    private boolean skip(String pkg) {
        return pkg.isEmpty() || pkg.equals("com.android.systemui")
                || pkg.equals("com.odin.dualscreen.assistant")
                || pkg.equals("com.odin.gameassistant")
                || pkg.equals("com.google.android.inputmethod.latin");
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
        if (retroExecutor != null) {
            retroExecutor.shutdownNow();
            retroExecutor = null;
        }
        if (profiles != null && profileListener != null) {
            profiles.preferences().unregisterOnSharedPreferenceChangeListener(profileListener);
            profileListener = null;
        }
        if (rates != null) rates.relinquish();
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
