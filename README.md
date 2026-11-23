# ThorRefreshRateControl

Android application for managing the AYN Thor's refresh rate by foreground app.
It retains the original `com.thor.refreshrate` application ID and preferences for upgrades, and
the 60 Hz / 120 Hz / Manual profile behavior.

## Features

- Accessibility-based monitoring of the **upper display** (display 0).
- On/off switch and two-column app listing on wider screens.
- **All apps** contains every observed app, including apps assigned to a category.
  It starts collapsed on each fresh launch; tap the header to expand it.
- User-created categories appear below All apps with small, borderless headers.
  Tap a category name for **Rename**, its chevron to collapse/expand, or its red
  trash icon to delete it. Use **+** on the All apps heading to create a category.
- Unassigned app cards show **+** instead of an Apps dropdown. Assign a category
  using that button; assigned cards display the selected category dropdown.
  Changes are stored locally and persist after closing the app or restarting
  the device; deleting a category preserves refresh-rate profiles.
- App menu/gameplay defaults and supported per-game profiles remain independent of
  display categories.
- RetroArch game detection through its optional local Network Commands status.
- Optional DroidDeck Steam-game detection through its local content provider.
  The DUMP permission can be granted through AYN's existing system bridge, or
  with a user-initiated, device-local wireless ADB pairing flow.
- Steam titles are resolved only after tapping **Find name**: app-private cache,
  DroidDeck's local library, then Steam's HTTPS API.
- Existing preferences, saved game names, and one-time profile migrations are
  preserved. The existing Android accessibility service reinitializes when
  Android reconnects it after a reboot, and attempts to apply the visible app's
  saved profile without requiring an extra window switch. Manual changes made outside this app are not overridden until an
  applicable profile change, as in the original implementation.

## Building and downloading the optimized APK

The GitHub Actions workflow builds the **ARM64-only optimized release** APK, not
`assembleDebug`. It uses the existing app implementation and pins Android Gradle
Plugin 8.7.3, Gradle 8.9, JDK 17, and compile SDK 35.

- R8 optimizes and removes unused Java bytecode (including unused dependency code).
- Android removes unreferenced resources.
- Only the Thor-compatible `arm64-v8a` native libraries are packaged for release;
  `assembleDebug` remains multi-ABI for development.
- Native libraries are compressed in the APK. This reduces the APK download size
  but **increases installed storage** because Android extracts them on install.
- The `libadb-android` and Conscrypt dependencies remain: they are needed for
  optional on-device wireless ADB pairing. Replacing or deleting them is not a
  safe size optimization without pairing regression tests.

### Download via GitHub

1. Upload the project root to `main`, including `.github/workflows/build-apk.yml`.
2. Open **Actions → Build installable APK → Run workflow** (or push to `main`).
3. Open the green workflow run, then download the `ThorRefreshRateControl-v1.0.7-APK`
   artifact. Extract the ZIP to get `ThorRefreshRateControl-v1.0.7-arm64.apk`.
4. To publish it under **Releases**, configure persistent signing (below), then
   create the tag `v1.0.7` from `main`. The workflow attaches that APK to a
   GitHub prerelease. **Test-signed artifacts are deliberately not published**
   as update candidates, because their signatures change between runs.

For local debugging, run `./gradlew :app:assembleDebug` (Windows: `gradlew.bat`).
For the optimized APK, run `./gradlew :app:assembleRelease`; the APK will be in
`app/build/outputs/apk/release/app-release.apk`.

### Signing for safe updates

With no secrets configured, the optimized release APK is **debug-signed** using
GitHub's temporary runner key. It remains available for testing as an Actions
artifact but is **not** published as an in-app update. Its signing certificate
is NOT stable between runs: a future APK may not install over it.
Uninstalling the old app deletes its stored settings. Public releases should
use the same private signing key for every build.

Generate a persistent `.jks` keystore locally using the JDK `keytool` command:

```sh
keytool -genkeypair -v -keystore thor-release.jks -alias thor \
  -keyalg RSA -keysize 3072 -validity 10000
```

Store the keystore and passwords securely **outside Git**. Base64-encode the
`.jks` file (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes('thor-release.jks'))`).
In **Settings → Secrets and variables → Actions → New repository secret**, set:

- `THOR_KEYSTORE_BASE64`: encoded keystore file
- `THOR_KEYSTORE_PASSWORD`: keystore password
- `THOR_KEY_ALIAS`: `thor` (or the alias chosen when generating the key)
- `THOR_KEY_PASSWORD`: key password

The next build automatically uses this key; no source overrides, separate app
variant, or credentials in the repository are required. **Never publish the
`.jks` file or any signing password.** Signatures must match to install over
an existing version; previously installed APKs signed with a different key
cannot be upgraded in place.

## v1.0.7: no lower-screen wake, and a fix for the black lower screen

- **Root cause of the black, unresponsive lower screen (likely):** the Thor's
  lower panel only supports 60 Hz, but `min_refresh_rate` and
  `peak_refresh_rate` apply to both panels. The 120 Hz profile used to write
  min = peak = 120, which leaves the lower panel with no valid mode.
  The 120 Hz profile now writes **min 60 / peak 120**. The top panel still runs
  at 120 Hz (Android uses the top of the allowed range unless an app asks for
  less); 60 Hz profiles still lock to 60/60.
- **No more lower-screen flicker.** Because every profile range is now valid
  for the lower panel, switches are written directly, even in top-only mode.
  The old behaviour (wake the lower screen and show the AYN panel) is
  available as **Wake lower screen for switches** in the update button's
  long-press menu, off by default.
- A restored manual baseline above 60 Hz is still only written while both
  panels are on.
- Existing installs keep ownership of the old 120/120 values, and correct
  them on the next switch.
- **Current version** is shown next to the update button.

## v1.0.6: open the AYN panel during a temporary lower-screen wake

When the app has to turn the lower screen on for a refresh-rate switch
(top-only mode), it now opens AYN's dashboard there (the overlay drawn by
`com.odin.dualscreen.assistant`, showing "60/120 FPS MODE", temperature, fan
and CPU/GPU). The badge doubles as visual confirmation of the switch.

- It works by pressing the physical **AYN button** virtually: a `KEY_F24`
  down/up on the `gpio-keys` input device, sent through the AYN bridge. That
  is identical to a real button press. The device node is found by name in
  `/proc/bus/input/devices` (fallback: `/dev/input/event0`).
- The button toggles the dashboard, so it is pressed only if the overlay is
  not already visible on the lower screen (checked through accessibility).
- Only when the app itself woke the lower screen. If the lower screen was
  already on, it is never touched.
- Triggered 400 ms after the lower panel reports that it is on, not after the
  2-second settle guard.

## v1.0.5: smoother switching and more robust detection

No settings, profiles, permissions or storage keys change; this is an in-place
update. Display-mode behaviour (temporary lower-screen wake and restore) is the
same as v1.0.4.

- **Switching no longer blocks the monitor.** Every AYN bridge call is a
  synchronous binder call that runs a shell command, and a switch can need
  several. They now run on a dedicated `Thor-Rate` worker thread instead of the
  accessibility service's main thread. If several requests arrive while one is
  in progress, only the newest target is applied.
- **Lower panel found dynamically.** The bottom display is no longer assumed to
  be display 4. The app picks the built-in, non-default display reported by
  Android (ID 4 is only a tie-breaker), so a firmware renumbering doesn't
  silently stop switching.
- **Every keyboard is ignored**, not only Gboard. Opening any installed input
  method on the top screen is no longer treated as an app switch.
- **Fewer window lookups.** Window-list changes that can't mean an app switch
  (bounds, titles, PiP, child windows) are ignored. The remaining ones are
  coalesced with a 150 ms quiet period. The lookup itself fetches only the root
  node (no subtree prefetch) on Android 13.
- **Adaptive game polling.** RetroArch (700 ms) and DroidDeck (800 ms) are
  polled quickly after any change, then every 2 s once the reading has been
  stable for five polls. Any change returns to fast polling.
- **Fix:** RetroArch and DroidDeck watchers previously shared one cancellation
  counter. Stopping one could leave a running RetroArch watcher discarding all
  results until you left and re-entered RetroArch (seen when the service
  reconnected while RetroArch was open). Each watcher now has its own token.

**Diagnostics:** `adb logcat -s ThorRefresh` now shows which display was chosen
as the lower panel, the duration of every bridge command, and the total time
of each switch.

**Hardware validation required:** the threading change affects every switch.
Test 60→120 and 120→60 with the lower panel on and in top-only mode, a reboot
with a profiled app in the foreground, and turning the monitor off and on.

## v1.0.4: restore the original lower-screen state after switching

The lower display is now turned on **temporarily** if the stock Thor was in
**top-screen-only** mode when a refresh-rate change was needed. The controller
waits for both panels to be ready, applies the selected profile, waits another
1.5 seconds, and requests top-screen-only mode again. If the lower screen was
**already on**, it is never marked as temporary and remains on.

- A no-op profile does not toggle the lower display.
- The current foreground profile is always checked again after waking; an old
  request cannot apply the wrong game's rate.
- Interrupted attempts have a bounded recovery timer and a short-lived saved
  temporary-wake marker, so service reconnection can restore top-only mode.
- AYN mode changes are verified and retried up to three times. The controller
  does not switch modes while the device is asleep or force unknown display modes.
- This changes only the existing rate controller and monitoring-service cleanup;
  UI, profiles, categories, permission handling, and signing are unchanged.

**Hardware validation is required.** This only controls the Thor's stock
`dual_screen_display_mode` setting, not third-party lower-screen-off utilities.
An unresponsive vendor bridge, force-stop, or a power interruption may prevent
restoration until the monitor reconnects. If a user manually requests the lower
panel on *during* the temporary wake, Android exposes no separate event to
reliably distinguish that request from the app's own wake.

## v1.0.3: wake lower display before a refresh-rate switch

The AYN Thor's refresh-rate settings affect the entire device. When a saved
profile actually requires a rate change and the stock display mode is
**top screen only** (`dual_screen_display_mode = 1`), the app requests
**dual-screen mode** (`0`) using the same built-in AYN privileged bridge as
its existing rate-switch code. It waits for the vendor assistant's mode change
and the original 2-second panel settling guard before applying the profile.
In v1.0.3, the lower screen stayed on afterwards; v1.0.4 restores the original top-only state.

- A rate that is already active does not wake the lower screen unnecessarily.
- A sleeping device is never woken by app monitoring.
- Bottom-only or unknown vendor modes are not changed automatically.
- If the bridge cannot enable the lower panel, **no refresh-rate write occurs**.
- The accessibility service retries the current effective profile after the
  display-mode observer or a successful wake request, rather than applying a
  stale earlier profile.
- No new permissions, background polling, settings keys, or categories.
- The existing application ID and `thor_refresh_profiles` storage are unchanged.

**Hardware validation required:** The stock firmware screen-mode listener may
be delayed, and third-party "deep off" utilities may power off the panel
without changing `dual_screen_display_mode`; the app does not bypass those
utilities or issue unverified panel-power shell commands. Test a 60 → 120 and
120 → 60 transition on the Thor while the bottom panel starts in *top-only*
mode. A normal in-place APK update retains stored profiles only if signing
certificates match.

## v1.0.2: second-screen safety and manual updates

- **Display safety:** Android's `min_refresh_rate` and `peak_refresh_rate` keys are
  global. On stock AYN Thor firmware, changing them when the bottom panel is
  disabled can leave the bottom panel black. Switching now **does not write**
  these settings in top-only, bottom-only, or sleeping states. It waits for
  `dual_screen_display_mode=0`, both displays awake, and a two-second settling
  period before reapplying the active app's profile. The existing saved profiles
  remain untouched. This reduces a known firmware trigger; device testing is
  still necessary, and an already-black panel may require a restart.
- **Updater:** Tap the circular-arrow icon immediately left of the on/off switch
  to check `bartm6/ThorRefreshRateControl` GitHub Releases. If a newer ARM64 APK
  is available, confirm to download it and open the Android package installer.
  It never installs without your action. The APK's package, version, and signing
  certificate are verified before opening the installer. GitHub's SHA-256 asset
  digest is verified whenever it is provided by the Releases API. The updater
  does not poll in the background.
- **Backup/restore:** **Long-press the update icon** to open **Backup settings**
  or **Restore settings**. The backup is a JSON document saved using Android's
  file picker; it contains all per-app settings, categories, profile rates,
  master switch settings, and cached Steam game names. Display ownership state
  is intentionally excluded, because it belongs to the old boot/session.

### Important: keeping already-configured apps

An update **with the same application ID and same signing key** keeps all
settings automatically. The app ID and preferences filename are unchanged.

**Previously downloaded GitHub Actions debug APKs may have different keys.**
Android will refuse an in-place update from those to your new permanently
signed APK. Never uninstall the existing app before preserving your settings:

1. While running the old app, if it has the backup feature, long-press the
   update icon, choose **Backup settings**, and save the JSON document.
2. If the *installed* old version has no backup option, it cannot export via
   this new app until it can be installed over it with the matching signature.
   In that case do **not uninstall** just to apply the update; keep the current
   profiles intact until a safe migration path is arranged (e.g. extracting
   preferences with an existing authorized ADB/root backup method).
3. Once the old data has been safely backed up, install the newly signed
   release and use **Restore settings** (long-press the update icon).

The updater refuses different signing certificates to prevent accidentally
encouraging deletion of user settings. Persistent signing is required for
future one-tap updates. See **Signing for safe updates** above.

### Compatibility

The application ID stays `com.thor.refreshrate` and its preferences and
permissions are unchanged. `targetSdk 33` is intentionally retained; updating
it would change Android behavior and requires separate testing. The application
uses Android Gradle Plugin 8.7.3 with Gradle 8.9.

A GitHub build verifies compilation, R8 generation, ABI selection, and APK
signature, but **device testing is still required** for wireless ADB pairing,
refresh-rate changes, and service reconnection after a reboot.

## Permissions / security

- **Modify system settings** changes the two Android system refresh-rate values.
- **Accessibility** observes the foreground app's package/activity on the upper
  display and does not capture keystrokes or screen text.
- **DUMP** is an optional, user-granted Android privileged permission used for
  supported external game-state integrations.
- **Internet** supports local RetroArch status and an explicitly requested
  Steam title lookup; **Wi-Fi state** supports optional local ADB pairing.

Android controls accessibility-service reconnection. If the service is disabled,
force-stopped, or blocked by firmware after boot, automatic switching cannot run
until Android re-enables/reconnects it; the stored settings remain intact.

The application requires no external UI framework or telemetry service.
The two third-party dependencies in `app/build.gradle` support the optional
one-time local ADB pairing workflow.

## Compatibility

`targetSdk 33` is intentionally retained: increasing it would change Android
runtime behavior and requires separate regression testing. The project uses
Android Gradle Plugin 8.7.3 and Gradle 8.9 (the compatible release pair).
