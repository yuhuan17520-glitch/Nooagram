package com.radolyn.ayugram.database;

import java.util.HashMap;
import java.util.Map;

/** Callers serialize final commits and cleanup separately; capturing a token never waits for disk work. */
public final class ArchiveSaveGuard {
    private long generation;
    private long databaseGeneration;
    private final Map<String, Long> clearedScopes = new HashMap<>();

    public synchronized long capture() {
        return generation;
    }

    public synchronized boolean isValid(long captured, long userId, long dialogId, long topicId) {
        return captured >= databaseGeneration
                && captured >= clearedScopes.getOrDefault(key(userId, dialogId, 0), 0L)
                && captured >= clearedScopes.getOrDefault(key(userId, dialogId, topicId), 0L);
    }

    public synchronized void invalidate(long userId, long dialogId, long topicId) {
        clearedScopes.put(key(userId, dialogId, topicId), ++generation);
    }

    public synchronized void invalidateAll() {
        databaseGeneration = ++generation;
        clearedScopes.clear();
    }

    private static String key(long userId, long dialogId, long topicId) {
        return userId + ":" + dialogId + ":" + topicId;
    }
}
