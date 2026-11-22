package com.thor.refreshrate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manual GitHub Releases updater. No background polling or external libraries. */
final class AppUpdater {
    private static final String RELEASES =
            "https://api.github.com/repos/bartm6/ThorRefreshRateControl/releases?per_page=30";
    private static final int MAX_APK_BYTES = 100 * 1024 * 1024;
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private final Activity activity;
    private boolean waitingForInstallPermission;

    AppUpdater(Activity activity) { this.activity = activity; }

    void checkForUpdates() {
        if (!BUSY.compareAndSet(false, true)) {
            toast("An update check is already running");
            return;
        }
        toast("Checking GitHub Releases…");
        new Thread(() -> {
            try {
                Release release = newestRelease();
                if (release == null) {
                    ui(() -> message("No update available",
                            "No downloadable APK has been published in GitHub Releases yet."));
                    return;
                }
                String installed = installedVersion();
                if (compareVersions(release.version, installed) <= 0) {
                    ui(() -> message("Up to date", "Installed version: " + installed
                            + "\nLatest release: " + release.version));
                    return;
                }
                ui(() -> new AlertDialog.Builder(activity)
                        .setTitle("Update available: v" + release.version)
                        .setMessage("Installed: v" + installed + "\n\nDownload the official "
                                + "GitHub release and open Android's installer? "
                                + "Your app profiles and categories remain in place "
                                + "when the update is signed with the same key.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Download", (dialog, which) -> download(release))
                        .show());
            } catch (Exception e) {
                ui(() -> message("Update check failed", safeMessage(e)));
            } finally {
                BUSY.set(false);
            }
        }, "Thor-Update-Check").start();
    }

    private void download(Release release) {
        if (!BUSY.compareAndSet(false, true)) {
            toast("A download is already running");
            return;
        }
        TextView progress = new TextView(activity);
        progress.setPadding(32, 30, 32, 12);
        progress.setText("Starting download…");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Downloading v" + release.version)
                .setView(progress)
                .setCancelable(false)
                .create();
        dialog.show();
        new Thread(() -> {
            try {
                File apk = downloadApk(release, progress);
                verifyApk(apk, release);
                ui(() -> {
                    dialog.dismiss();
                    install(apk);
                });
            } catch (Exception e) {
                ui(() -> {
                    dialog.dismiss();
                    message("Update download failed", safeMessage(e));
                });
            } finally {
                BUSY.set(false);
            }
        }, "Thor-Update-Download").start();
    }

    /** A failed or incompatible download never changes local user data. */
    private File downloadApk(Release release, TextView progress) throws Exception {
        File directory = new File(activity.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Could not create update cache");
        File partial = new File(directory, "update.part");
        File completed = new File(directory, "update.apk");
        if (partial.exists() && !partial.delete())
            throw new IllegalStateException("Could not reset old download");
        HttpURLConnection connection = connect(release.url, "application/octet-stream");
        try {
            long expectedSize = connection.getContentLengthLong();
            if (expectedSize > MAX_APK_BYTES) throw new IllegalStateException("Update is too large");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            int downloaded = 0;
            long lastDisplay = 0;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    downloaded += n;
                    if (downloaded > MAX_APK_BYTES)
                        throw new IllegalStateException("Update exceeds maximum size");
                    output.write(buffer, 0, n);
                    hash.update(buffer, 0, n);
                    long now = SystemClock.uptimeMillis();
                    if (now - lastDisplay > 350) {
                        lastDisplay = now;
                        String label = expectedSize > 0
                                ? Math.min(100, (downloaded * 100L / expectedSize)) + "%"
                                : String.format(Locale.ROOT, "%.1f MB", downloaded / 1048576f);
                        ui(() -> progress.setText("Downloading… " + label));
                    }
                }
                output.getFD().sync();
            }
            if (downloaded < 1024 || (expectedSize >= 0 && downloaded != expectedSize))
                throw new IllegalStateException("The downloaded APK is incomplete");
            if (release.sha256 != null) {
                StringBuilder actual = new StringBuilder();
                for (byte b : hash.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
                if (!actual.toString().equalsIgnoreCase(release.sha256))
                    throw new SecurityException("Release checksum mismatch");
            }
            if (completed.exists() && !completed.delete())
                throw new IllegalStateException("Could not replace the older update file");
            if (!partial.renameTo(completed))
                throw new IllegalStateException("Could not finish update download");
            return completed;
        } finally {
            connection.disconnect();
        }
    }

    private Release newestRelease() throws Exception {
        HttpURLConnection connection = connect(RELEASES, "application/vnd.github+json");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    total += n;
                    if (total > 1024 * 1024)
                        throw new IllegalStateException("Unexpected GitHub response size");
                    bytes.write(buffer, 0, n);
                }
            }
            JSONArray releases = new JSONArray(bytes.toString("UTF-8"));
            Release best = null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject item = releases.getJSONObject(i);
                if (item.optBoolean("draft", false)) continue;
                String tag = item.optString("tag_name", "");
                String version = tag.startsWith("v") ? tag.substring(1) : tag;
                if (!validVersion(version)) continue;
                JSONArray assets = item.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.getJSONObject(j);
                    String name = asset.optString("name", "");
                    if (!name.startsWith("ThorRefreshRateControl-v")
                            || !name.endsWith("-arm64.apk")) continue;
                    // Never download arbitrary URLs supplied by another repository.
                    String url = asset.optString("browser_download_url", "");
                    if (!url.startsWith("https://github.com/bartm6/ThorRefreshRateControl/releases/download/"))
                        continue;
                    String digest = asset.optString("digest", "");
                    String sha256 = digest.matches("sha256:[0-9a-fA-F]{64}")
                            ? digest.substring(7) : null;
                    Release candidate = new Release(version, url, sha256);
                    if (best == null || compareVersions(candidate.version, best.version) > 0)
                        best = candidate;
                }
            }
            return best;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection connect(String url, String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "ThorRefreshRateControl-Android-Updater");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK)
            throw new IllegalStateException("GitHub returned HTTP " + status);
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol()))
            throw new SecurityException("Insecure download redirect");
        return connection;
    }

    private void verifyApk(File apk, Release release) throws Exception {
        PackageManager pm = activity.getPackageManager();
        @SuppressWarnings("deprecation")
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || !activity.getPackageName().equals(archive.packageName))
            throw new SecurityException("The download isn't a ThorRefreshRateControl APK");
        if (!release.version.equals(archive.versionName))
            throw new SecurityException("Release version doesn't match the APK");
        @SuppressWarnings("deprecation")
        PackageInfo installed = pm.getPackageInfo(activity.getPackageName(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive.getLongVersionCode() <= installed.getLongVersionCode())
            throw new SecurityException("Downloaded APK is not a newer build");
        // Android rejects updates signed by a different key. Verify before even
        // opening the installer to avoid suggesting users uninstall and lose data.
        if (archive.signingInfo == null || installed.signingInfo == null)
            throw new SecurityException("Cannot verify the APK's signing certificate");
        Signature[] newSigners = archive.signingInfo.getApkContentsSigners();
        Signature[] oldSigners = installed.signingInfo.getApkContentsSigners();
        if (newSigners == null || oldSigners == null || newSigners.length != 1
                || oldSigners.length != 1 || !Arrays.equals(
                newSigners[0].toByteArray(), oldSigners[0].toByteArray()))
            throw new SecurityException("Update signing key differs from the installed app. "
                    + "Configure a permanent release signing key. Do NOT uninstall: "
                    + "that would erase your local app profiles.");
    }

    private void install(File apk) {
        if (!apk.isFile()) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = true;
            toast("Enable Allow from this source, then return to install");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            try { activity.startActivity(settings); }
            catch (Exception e) { message("Open Settings", safeMessage(e)); }
            return;
        }
        waitingForInstallPermission = false;
        Uri uri = Uri.parse("content://com.thor.refreshrate.updates/update.apk");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setClipData(ClipData.newRawUri("ThorRefreshRateControl update", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivity(intent); }
        catch (Exception e) { message("Installer unavailable", safeMessage(e)); }
    }

    void onResume() {
        if (!waitingForInstallPermission) return;
        if (activity.getPackageManager().canRequestPackageInstalls())
            install(new File(new File(activity.getCacheDir(), "updates"), "update.apk"));
    }

    private String installedVersion() throws PackageManager.NameNotFoundException {
        @SuppressWarnings("deprecation")
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        return info.versionName == null ? "0.0.0" : info.versionName;
    }

    private static boolean validVersion(String v) { return v.matches("[0-9]+\\.[0-9]+\\.[0-9]+"); }

    private static int compareVersions(String a, String b) {
        if (!validVersion(a) || !validVersion(b)) return a.compareTo(b);
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int compare = Long.compare(Long.parseLong(left[i]), Long.parseLong(right[i]));
            if (compare != 0) return compare;
        }
        return 0;
    }

    private void message(String title, String text) {
        if (!activity.isFinishing() && !activity.isDestroyed())
            new AlertDialog.Builder(activity).setTitle(title).setMessage(text)
                    .setPositiveButton("OK", null).show();
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }

    private void ui(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) action.run();
        });
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message;
    }

    private static final class Release {
        final String version;
        final String url;
        final String sha256;
        Release(String version, String url, String sha256) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
        }
    }
}
