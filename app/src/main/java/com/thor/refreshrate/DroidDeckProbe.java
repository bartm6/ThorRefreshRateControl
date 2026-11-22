package com.thor.refreshrate;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Read-only DroidDeck state; called only from the monitor's worker thread. */
final class DroidDeckProbe {
    private static final Uri AGENT = Uri.parse("content://com.droiddeck.launcher.agent");

    static final class State {
        final String steamGameId; // null means frontend/Steam menu (not a game)
        final boolean confirmed; // false for transient/unknown focus; do not change Hz
        State(String steamGameId, boolean confirmed) {
            this.steamGameId = steamGameId;
            this.confirmed = confirmed;
        }
    }

    private DroidDeckProbe() { }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED;
    }

    /** Resolve Steam App IDs using DroidDeck's own cached game library.
     * Called only after the user selects Find name, never in the FPS critical path.
     * Returns null if this game isn't in the local library. */
    static String gameName(Context context, String id) {
        if (!hasPermission(context) || id == null || !id.matches("[0-9]{1,18}")) return null;
        // A large installed library may exceed DroidDeck's 384-KiB reply cap.
        // Retry with Steam-only games before relying on the online fallback.
        String name = readLibraryTitle(context, id, null);
        return name != null ? name : readLibraryTitle(context, id, "steam");
    }

    private static String readLibraryTitle(Context context, String id, String source) {
        try {
            Bundle request = null;
            if (source != null) {
                request = new Bundle();
                String json = new JSONObject().put("source", source).toString();
                request.putString("request", Base64.encodeToString(
                        json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            }
            Bundle result = context.getContentResolver().call(AGENT, "library", null, request);
            if (result == null) return null;
            JSONObject root = new JSONObject(result.getString("json", "{}"));
            if (!root.optBoolean("ok", false)) return null;
            JSONArray games = root.optJSONArray("games");
            if (games == null) return null;
            for (int n = 0; n < games.length(); n++) {
                JSONObject game = games.optJSONObject(n);
                if (game == null || !id.equals(game.optString("id"))) continue;
                String name = game.optString("name", "").trim();
                return GameTitles.unresolved(name) ? null : name;
            }
        } catch (Exception ignored) {
            // Optional integration; never block FPS changes on metadata failures.
        }
        return null;
    }

    /** Steam UI's AppID is 769; only a focusable, positive game ID is accepted. */
    private static String gameId(String focused, Set<String> focusable) {
        String id = focused.trim();
        if (id.isEmpty() || "769".equals(id) || !id.matches("[0-9]{1,18}")
                || !focusable.contains(id)) return null;
        try {
            return Long.parseLong(id) > 0 ? id : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** null means provider unavailable, inaccessible or malformed; never assume gameplay. */
    static State read(Context context) {
        if (!hasPermission(context)) return null;
        try {
            Bundle bundle = context.getContentResolver().call(AGENT, "state", null, null);
            if (bundle == null) return null;
            String json = bundle.getString("json");
            if (json == null) return null;
            JSONObject root = new JSONObject(json);
            if (!root.optBoolean("ok", false)) return null;
            JSONObject session = root.optJSONObject("session");
            if (session == null) return null;
            JSONObject focus = session.optJSONObject("focus");
            String focused = focus == null || focus.isNull("focusedApp")
                    ? null : focus.optString("focusedApp", null);
            Set<String> focusable = new HashSet<>();
            JSONArray ids = focus == null ? null : focus.optJSONArray("focusableApps");
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) focusable.add(ids.optString(i));
            }
            boolean runningSteam = session.optBoolean("running", false)
                    && "steam".equalsIgnoreCase(session.optString("mode", ""));
            if (!runningSteam) return new State(null, true);
            if (focus == null || focused == null || focused.trim().isEmpty())
                return new State(null, false);
            if ("769".equals(focused.trim())) return new State(null, true);
            String id = gameId(focused, focusable);
            return new State(id, id != null);
        } catch (Exception e) {
            // Older DroidDeck builds may not implement this optional interface.
            return null;
        }
    }
}
