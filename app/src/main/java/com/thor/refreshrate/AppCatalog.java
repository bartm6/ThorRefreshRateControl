package com.thor.refreshrate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Recent-app shortcuts, without scanning installed packages. */
final class AppCatalog {
    private AppCatalog() { }

    /** A short MRU list of distinct app packages, latest first. */
    static String recordRecent(String previous, String packageName) {
        if (packageName == null || packageName.isEmpty() || packageName.contains("\n"))
            return previous == null ? "" : previous;
        List<String> result = new ArrayList<>();
        result.add(packageName);
        if (previous != null && !previous.isEmpty()) {
            for (String pkg : previous.split("\n")) {
                if (result.size() == 4) break;
                if (!pkg.isEmpty() && !result.contains(pkg)) result.add(pkg);
            }
        }
        return String.join("\n", result);
    }

    static List<Profile> recent(List<Profile> profiles, String orderedPackages) {
        List<Profile> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        if (orderedPackages != null && !orderedPackages.isEmpty()) {
            for (String pkg : orderedPackages.split("\n")) {
                for (Profile profile : profiles) {
                    if (!profile.game && pkg.equals(profile.packageName) && used.add(pkg)) {
                        result.add(profile);
                        break;
                    }
                }
                if (result.size() == 4) return result;
            }
        }
        // After upgrading, use existing last-seen timestamps to fill initial slots.
        List<Profile> byTime = new ArrayList<>();
        for (Profile profile : profiles) if (!profile.game) byTime.add(profile);
        byTime.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        for (Profile profile : byTime) {
            if (used.add(profile.packageName)) result.add(profile);
            if (result.size() == 4) break;
        }
        return result;
    }
}
