package com.thor.refreshrate;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Optional manual backup for the one-time migration away from ephemeral APK signing keys. */
final class SettingsBackup {
    private static final int FORMAT = 1;
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private SettingsBackup() { }

    static void exportTo(Context context, Uri target) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(ProfileStore.PREFS,
                Context.MODE_PRIVATE);
        JSONObject data = new JSONObject();
        for (Map.Entry<String, ?> item : prefs.getAll().entrySet()) {
            String key = item.getKey();
            // These keys are a transient state of the previous system display.
            if (key.startsWith("controller.")) continue;
            Object value = item.getValue();
            JSONObject wrapped = new JSONObject();
            if (value instanceof Boolean) {
                wrapped.put("type", "boolean");
                wrapped.put("value", value);
            } else if (value instanceof Integer) {
                wrapped.put("type", "int");
                wrapped.put("value", value);
            } else if (value instanceof Long) {
                wrapped.put("type", "long");
                wrapped.put("value", value);
            } else if (value instanceof Float) {
                wrapped.put("type", "float");
                wrapped.put("value", ((Float) value).doubleValue());
            } else if (value instanceof String) {
                wrapped.put("type", "string");
                wrapped.put("value", value);
            } else if (value instanceof Set) {
                JSONArray strings = new JSONArray();
                for (Object member : (Set<?>) value) {
                    if (member instanceof String) strings.put(member);
                }
                wrapped.put("type", "string_set");
                wrapped.put("value", strings);
            } else continue;
            data.put(key, wrapped);
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("preferences", data);
        JSONObject names = new JSONObject();
        File steam = new File(context.getFilesDir(), "steam_games.txt");
        if (steam.isFile()) {
            for (String line : Files.readAllLines(steam.toPath(), StandardCharsets.UTF_8)) {
                int separator = line.indexOf(' ');
                if (separator < 1) continue;
                String id = line.substring(0, separator);
                if (id.matches("[0-9]{1,18}")) names.put(id, line.substring(separator + 1));
            }
        }
        root.put("steam_names", names);
        byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("Backup exceeds 2 MB");
        try (OutputStream output = context.getContentResolver().openOutputStream(target, "wt")) {
            if (output == null) throw new IllegalStateException("Backup destination unavailable");
            output.write(bytes);
        }
    }

    static void restoreFrom(Context context, Uri source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IllegalStateException("Backup unavailable");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (bytes.size() + n > MAX_BYTES)
                    throw new IllegalArgumentException("Backup exceeds 2 MB");
                bytes.write(buffer, 0, n);
            }
        }
        JSONObject root = new JSONObject(bytes.toString("UTF-8"));
        if (root.getInt("format") != FORMAT) throw new IllegalArgumentException("Unknown backup format");
        JSONObject saved = root.getJSONObject("preferences");
        if (saved.length() > 30000) throw new IllegalArgumentException("Invalid backup");
        Map<String, Object> values = new HashMap<>();
        java.util.Iterator<String> keys = saved.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.length() > 500 || key.startsWith("controller.")) continue;
            JSONObject entry = saved.getJSONObject(key);
            String type = entry.getString("type");
            Object value;
            switch (type) {
                case "boolean": value = entry.getBoolean("value"); break;
                case "int": value = entry.getInt("value"); break;
                case "long": value = entry.getLong("value"); break;
                case "float": value = (float) entry.getDouble("value"); break;
                case "string": value = entry.getString("value"); break;
                case "string_set":
                    JSONArray list = entry.getJSONArray("value");
                    Set<String> set = new HashSet<>();
                    for (int j = 0; j < list.length(); j++) set.add(list.getString(j));
                    value = set;
                    break;
                default: throw new IllegalArgumentException("Unsupported backup setting: " + type);
            }
            values.put(key, value);
        }
        Map<String, String> names = new HashMap<>();
        JSONObject rawNames = root.optJSONObject("steam_names");
        if (rawNames != null) {
            java.util.Iterator<String> ids = rawNames.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                if (id.matches("[0-9]{1,18}")) names.put(id, rawNames.getString(id));
            }
        }
        SharedPreferences prefs = context.getSharedPreferences(ProfileStore.PREFS,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, Object> item : values.entrySet()) {
            String key = item.getKey();
            Object value = item.getValue();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> strings = (Set<String>) value;
                editor.putStringSet(key, strings);
            }
        }
        if (!editor.commit()) throw new IllegalStateException("Could not save restored preferences");
        if (!SteamNameFile.putAll(context.getFilesDir(), names))
            throw new IllegalStateException("Preferences restored, but Steam titles failed");
    }
}
