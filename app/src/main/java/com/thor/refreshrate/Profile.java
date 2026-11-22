package com.thor.refreshrate;

/** A remembered app or, where exposed by the app, a distinct game/window. */
final class Profile {
    final String id;
    final String packageName;
    final String name;
    final boolean game;
    final long lastSeen;
    final int rate; // 0=Manual/Inherit; 60 or 120=override

    Profile(String id, String packageName, String name, boolean game, long lastSeen, int rate) {
        this.id = id;
        this.packageName = packageName;
        this.name = name;
        this.game = game;
        this.lastSeen = lastSeen;
        this.rate = rate;
    }
}
