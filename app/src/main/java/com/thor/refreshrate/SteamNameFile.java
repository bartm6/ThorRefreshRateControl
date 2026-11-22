package com.thor.refreshrate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** A small app-private cache: steam_games.txt, one "<id> <name>" entry per line. */
final class SteamNameFile {
    private static final String FILE_NAME = "steam_games.txt";
    private static File loadedPath;
    private static Map<String, String> titles;

    private SteamNameFile() { }

    static synchronized String get(File dir, String id) {
        load(dir);
        return titles.get(id);
    }

    static synchronized boolean put(File dir, String id, String title) {
        if (!validId(id) || clean(title).isEmpty()) return false;
        load(dir);
        String safeTitle = clean(title);
        if (safeTitle.equals(titles.get(id))) return true;
        Map<String, String> next = new HashMap<>(titles);
        next.put(id, safeTitle);
        return save(dir, next);
    }

    static synchronized boolean putAll(File dir, Map<String, String> additions) {
        load(dir);
        Map<String, String> next = new HashMap<>(titles);
        for (Map.Entry<String, String> entry : additions.entrySet()) {
            if (validId(entry.getKey()) && !clean(entry.getValue()).isEmpty())
                next.putIfAbsent(entry.getKey(), clean(entry.getValue()));
        }
        return next.equals(titles) || save(dir, next);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[0-9]{1,18}");
    }

    private static String clean(String title) {
        return title == null ? "" : title.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void load(File dir) {
        File source = new File(dir, FILE_NAME);
        if (source.equals(loadedPath) && titles != null) return;
        loadedPath = source;
        Map<String, String> read = new HashMap<>();
        if (source.isFile()) {
            try (BufferedReader reader = Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int separator = line.indexOf(' ');
                    if (separator < 1) continue;
                    String id = line.substring(0, separator);
                    String name = clean(line.substring(separator + 1));
                    if (validId(id) && !name.isEmpty()) read.put(id, name);
                }
            } catch (IOException ignored) {
                // The cache is optional; app profiles are stored separately.
            }
        }
        titles = read;
    }

    private static boolean save(File dir, Map<String, String> next) {
        if (!dir.exists() && !dir.mkdirs()) return false;
        File destination = new File(dir, FILE_NAME);
        File temporary = new File(dir, FILE_NAME + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary.toPath(), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> item : new TreeMap<>(next).entrySet()) {
                writer.write(item.getKey() + " " + item.getValue());
                writer.newLine();
            }
        } catch (IOException e) { return false; }
        try {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnavailable) {
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) { return false; }
        }
        titles = next;
        return true;
    }
}
