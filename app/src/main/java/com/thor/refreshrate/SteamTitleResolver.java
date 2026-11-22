package com.thor.refreshrate;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Local cache, DroidDeck library, then Steam; invoked only by the user. */
final class SteamTitleResolver {
    private SteamTitleResolver() { }

    static String resolve(Context context, String appId) {
        if (appId == null || !appId.matches("[0-9]{1,10}")) return null;
        String cached = SteamNameFile.get(context.getFilesDir(), appId);
        if (!GameTitles.unresolved(cached)) return cached;
        String title = lookup(context, appId);
        if (title != null) SteamNameFile.put(context.getFilesDir(), appId, title);
        return title;
    }

    private static String lookup(Context context, String appId) {
        String local = DroidDeckProbe.gameName(context, appId);
        if (!GameTitles.unresolved(local)) return local;
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://store.steampowered.com/api/appdetails?appids="
                    + appId + "&filters=basic&l=english");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(3000);
            connection.setUseCaches(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ThorRefreshRateControl/2.4 (Android)");
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int read;
                while ((read = in.read(buffer)) != -1 && bytes.size() < 128 * 1024)
                    bytes.write(buffer, 0, Math.min(read, 128 * 1024 - bytes.size()));
                JSONObject record = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
                        .optJSONObject(appId);
                if (record == null || !record.optBoolean("success", false)) return null;
                JSONObject details = record.optJSONObject("data");
                if (details == null) return null;
                String title = details.optString("name", "").trim();
                return GameTitles.unresolved(title) ? null : title;
            }
        } catch (Exception ignored) {
            // Custom/non-store games or unavailable internet may not resolve automatically.
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
