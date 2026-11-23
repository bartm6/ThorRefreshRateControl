package com.thor.refreshrate;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Locally stored app, RetroArch gameplay and content profiles. */
final class ProfileStore {
    static final String PREFS = "thor_refresh_profiles";
    private static final String SEEN = "seen";
    private static final String MIGRATED = "migration.retroarch.v1";
    private static final String MIGRATED_EMULATOR = "migration.generic-emulators.v2";
    private static final long RECENT_UPDATE_MS = 10 * 60_000L;
    private static final String RECENT_APPS = "recent.apps.v1";
    private static final String CATEGORIES = "categories.v1";
    private static final String CATEGORY_APP = "category.app.";
    static final String MONITOR_ENABLED = "monitor.enabled";
    /** Legacy v1.0.3-v1.0.6 behaviour: turn the lower screen on for each switch.
     * "option." keys are included in settings backups. */
    static final String WAKE_LOWER_SCREEN = "option.wakeLowerScreen";
    private static final String NAMES_MIGRATED = "steam.names.file.v1";
    private final Context context;
    private final SharedPreferences p;

    ProfileStore(Context context) {
        this.context = context.getApplicationContext();
        p = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateFakeRetroArchGames();
        migrateFakeEmulatorTitles();
        migrateSteamNames();
    }

    static String appId(String pkg) { return "app:" + pkg; }
    static String gameId(String pkg, String gameKey) { return "game:" + pkg + ":" + gameKey; }
    static String gameplayId(String pkg) { return "play:" + pkg; }
    static boolean isRetroArch(String pkg) {
        return pkg != null && (pkg.equals("com.retroarch") || pkg.startsWith("com.retroarch."));
    }

    static boolean hasSessionProfile(String pkg) {
        return isRetroArch(pkg) || ActivityModeDetector.supports(pkg);
    }

    SharedPreferences preferences() { return p; }

    boolean enabled() { return p.getBoolean(MONITOR_ENABLED, true); }

    boolean wakeLowerScreen() { return p.getBoolean(WAKE_LOWER_SCREEN, false); }

    void setWakeLowerScreen(boolean wake) {
        p.edit().putBoolean(WAKE_LOWER_SCREEN, wake).commit();
    }

    void setEnabled(boolean enabled) {
        if (enabled() != enabled) p.edit().putBoolean(MONITOR_ENABLED, enabled).commit();
    }

    /** Memory-backed file cache is consulted before any game title lookup. */
    private String storedName(String id, String fallback) {
        if (!GameTitles.unresolved(fallback)) return fallback;
        String prefix = gameId("com.droiddeck.launcher", "steam-");
        if (!id.startsWith(prefix)) return fallback;
        String local = SteamNameFile.get(context.getFilesDir(), id.substring(prefix.length()));
        return GameTitles.unresolved(local) ? fallback : local;
    }

    boolean isKnown(String id) {
        return p.getStringSet(SEEN, Collections.emptySet()).contains(id);
    }

    void observed(String id, String pkg, String label, boolean game) {
        Set<String> previous = p.getStringSet(SEEN, Collections.emptySet());
        boolean firstSeen = !previous.contains(id);
        long now = System.currentTimeMillis();
        if (!firstSeen && now - p.getLong("seen." + id, 0) < RECENT_UPDATE_MS) return;

        SharedPreferences.Editor editor = p.edit().putLong("seen." + id, now);
        if (firstSeen) {
            Set<String> updated = new HashSet<>(previous);
            updated.add(id);
            editor.putStringSet(SEEN, updated)
                    .putString("pkg." + id, pkg)
                    .putString("name." + id, storedName(id, label == null ? pkg : label))
                    .putBoolean("game." + id, game);
        }
        editor.apply();
    }

    /** Upgrade automatically generated Steam IDs to locally resolved titles.
     * Never overwrite a custom/user title. */
    void updateGameName(String id, String title) {
        if (title == null || title.trim().isEmpty()) return;
        String old = p.getString("name." + id, "");
        if (!isKnown(id) || !GameTitles.unresolved(old)
                || GameTitles.unresolved(title)) return;
        p.edit().putString("name." + id, title.trim()).apply();
        saveSteamName(id, title);
    }

    /** Custom/non-Steam titles can be named locally without changing their stable ID. */
    void renameGame(String id, String title) {
        if (!isKnown(id) || GameTitles.unresolved(title)) return;
        p.edit().putString("name." + id, title.trim()).apply();
        saveSteamName(id, title);
    }

    int rate(String id) {
        int rate = p.getInt("hz." + id, 0);
        return (rate == 60 || rate == 120) ? rate : 0;
    }

    void setRate(String id, int hz) {
        if (hz != 0 && hz != 60 && hz != 120) throw new IllegalArgumentException("Unsupported Hz");
        if (hz == 0) p.edit().remove("hz." + id).commit();
        else p.edit().putInt("hz." + id, hz).commit();
    }

    /** A game has priority over its emulator's gameplay default, then its app setting. */
    int rateFor(String pkg, String activeGameId) {
        if (activeGameId != null) {
            int gameRate = rate(activeGameId);
            if (gameRate != 0) return gameRate;
            if (hasSessionProfile(pkg)) {
                int gameplayRate = rate(gameplayId(pkg));
                if (gameplayRate != 0) return gameplayRate;
            }
        }
        return rate(appId(pkg));
    }

    /** Update the four most recently foregrounded distinct apps, only on a real switch. */
    void recordRecentApp(String pkg) {
        String old = p.getString(RECENT_APPS, "");
        String updated = AppCatalog.recordRecent(old, pkg);
        if (!updated.equals(old)) p.edit().putString(RECENT_APPS, updated).apply();
    }

    String recentApps() { return p.getString(RECENT_APPS, ""); }

    /** Categories are optional views; they never remove apps from All apps. */
    List<String> categories() {
        List<String> names = new ArrayList<>(p.getStringSet(CATEGORIES, Collections.emptySet()));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static boolean validCategoryName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 32
                && !name.equalsIgnoreCase("Apps") && !name.equalsIgnoreCase("All apps");
    }

    boolean addCategory(String name) {
        String next = name == null ? "" : name.trim();
        if (!validCategoryName(next)) return false;
        Set<String> categories = new HashSet<>(p.getStringSet(CATEGORIES, Collections.emptySet()));
        for (String existing : categories)
            if (existing.equalsIgnoreCase(next)) return false;
        categories.add(next);
        return p.edit().putStringSet(CATEGORIES, categories).commit();
    }

    /** Atomically rewrite the category name and all its existing assignments. */
    boolean renameCategory(String previous, String name) {
        String next = name == null ? "" : name.trim();
        if (!validCategoryName(next)) return false;
        Set<String> categories = new HashSet<>(p.getStringSet(CATEGORIES, Collections.emptySet()));
        if (!categories.contains(previous)) return false;
        for (String existing : categories)
            if (!existing.equals(previous) && existing.equalsIgnoreCase(next)) return false;
        if (previous.equals(next)) return true;
        categories.remove(previous);
        categories.add(next);
        SharedPreferences.Editor edit = p.edit().putStringSet(CATEGORIES, categories);
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(CATEGORY_APP) && previous.equals(p.getString(key, "")))
                edit.putString(key, next);
        }
        return edit.commit();
    }

    void removeCategory(String category) {
        Set<String> categories = new HashSet<>(p.getStringSet(CATEGORIES, Collections.emptySet()));
        if (!categories.remove(category)) return;
        SharedPreferences.Editor edit = p.edit().putStringSet(CATEGORIES, categories);
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(CATEGORY_APP) && category.equals(p.getString(key, "")))
                edit.remove(key);
        }
        edit.commit();
    }

    /** Empty category means unassigned, but the app remains in All apps. */
    String categoryFor(String pkg) {
        String category = p.getString(CATEGORY_APP + pkg, "");
        return p.getStringSet(CATEGORIES, Collections.emptySet()).contains(category)
                ? category : "";
    }

    void setCategory(String pkg, String category) {
        if (category == null || category.isEmpty()) {
            p.edit().remove(CATEGORY_APP + pkg).commit();
        } else if (p.getStringSet(CATEGORIES, Collections.emptySet()).contains(category)) {
            p.edit().putString(CATEGORY_APP + pkg, category).commit();
        }
    }

    private void saveSteamName(String profileId, String title) {
        String prefix = gameId("com.droiddeck.launcher", "steam-");
        if (profileId.startsWith(prefix)) {
            SteamNameFile.put(context.getFilesDir(), profileId.substring(prefix.length()), title);
        }
    }

    /** Existing Steam names in preferences are copied to the text file once. */
    private void migrateSteamNames() {
        if (p.getBoolean(NAMES_MIGRATED, false)) return;
        java.util.Map<String, String> existing = new java.util.HashMap<>();
        String prefix = gameId("com.droiddeck.launcher", "steam-");
        for (String id : p.getStringSet(SEEN, Collections.emptySet())) {
            if (!id.startsWith(prefix)) continue;
            String name = p.getString("name." + id, "");
            if (!GameTitles.unresolved(name))
                existing.put(id.substring(prefix.length()), name);
        }
        if (SteamNameFile.putAll(context.getFilesDir(), existing))
            p.edit().putBoolean(NAMES_MIGRATED, true).apply();
    }

    List<Profile> all() {
        List<Profile> result = new ArrayList<>();
        for (String id : p.getStringSet(SEEN, Collections.emptySet())) {
            String pkg = p.getString("pkg." + id, "");
            if (pkg.isEmpty()) continue;
            result.add(new Profile(id, pkg, storedName(id, p.getString("name." + id, pkg)),
                    p.getBoolean("game." + id, false), p.getLong("seen." + id, 0), rate(id)));
        }
        result.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return result;
    }

    /** Remove v1.3's spurious 'RetroArch aarch64 game title' profile.
     * Keep its selected Hz as the new gameplay default where possible. */
    private void migrateFakeRetroArchGames() {
        if (p.getBoolean(MIGRATED, false)) return;
        Set<String> old = p.getStringSet(SEEN, Collections.emptySet());
        Set<String> updated = new HashSet<>(old);
        SharedPreferences.Editor edit = p.edit();
        for (String id : old) {
            String pkg = p.getString("pkg." + id, "");
            if (!isRetroArch(pkg) || !id.startsWith("game:")) continue;
            String name = p.getString("name." + id, "").toLowerCase(Locale.ROOT).trim();
            if (!name.matches("retroarch(?: aarch64| 64| 32)? game title")) continue;
            String gameplayId = gameplayId(pkg);
            if (!p.contains("hz." + gameplayId) && p.contains("hz." + id)) {
                edit.putInt("hz." + gameplayId, p.getInt("hz." + id, 0));
            }
            updated.remove(id);
            edit.remove("pkg." + id).remove("name." + id)
                    .remove("game." + id).remove("seen." + id).remove("hz." + id);
        }
        if (!updated.equals(old)) edit.putStringSet(SEEN, updated);
        edit.putBoolean(MIGRATED, true).apply();
    }
    /** Remove only demonstrably fake legacy game-title entries. Preserve user
     * choices as a gameplay default rather than throwing them away. */
    private void migrateFakeEmulatorTitles() {
        if (p.getBoolean(MIGRATED_EMULATOR, false)) return;
        Set<String> old = p.getStringSet(SEEN, Collections.emptySet());
        Set<String> updated = new HashSet<>(old);
        SharedPreferences.Editor edit = p.edit();
        for (String id : old) {
            if (!id.startsWith("game:")) continue;
            String pkg = p.getString("pkg." + id, "");
            if (!hasSessionProfile(pkg)) continue;
            String name = p.getString("name." + id, "").toLowerCase(Locale.ROOT).trim();
            if (!(name.endsWith(" game title") || name.equals("emulationactivity")
                    || name.equals("emulatoractivity") || name.equals("game activity"))) continue;
            String fallback = gameplayId(pkg);
            if (!p.contains("hz." + fallback) && p.contains("hz." + id))
                edit.putInt("hz." + fallback, p.getInt("hz." + id, 0));
            updated.remove(id);
            edit.remove("pkg." + id).remove("name." + id)
                    .remove("game." + id).remove("seen." + id).remove("hz." + id);
        }
        if (!updated.equals(old)) edit.putStringSet(SEEN, updated);
        edit.putBoolean(MIGRATED_EMULATOR, true).apply();
    }

}
