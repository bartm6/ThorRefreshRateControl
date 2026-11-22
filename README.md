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
3. Open the green workflow run, then download the `ThorRefreshRateControl-v1.0.2-APK`
   artifact. Extract the ZIP to get `ThorRefreshRateControl-v1.0.2-arm64.apk`.
4. To publish it under **Releases**, configure persistent signing (below), then
   create the tag `v1.0.2` from `main`. The workflow attaches that APK to a
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
