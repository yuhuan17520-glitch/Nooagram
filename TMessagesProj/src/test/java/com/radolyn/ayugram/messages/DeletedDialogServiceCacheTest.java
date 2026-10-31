package com.radolyn.ayugram.messages;

import org.junit.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeletedDialogServiceCacheTest {
    @Test
    public void reusingSlotClearsDialogAndTopicPreviewsAndRejectsOldLoad() {
        DeletedDialogService.AccountCache<String> cache = new DeletedDialogService.AccountCache<>();
        long oldLoad = cache.bind(10);
        cache.messages.put(-20L, "first user's dialog");
        ConcurrentHashMap<Long, String> topics = new ConcurrentHashMap<>();
        topics.put(7L, "first user's topic");
        cache.topics.put(-20L, topics);

        long newLoad = cache.bind(11);

        assertTrue(cache.messages.isEmpty());
        assertTrue(cache.topics.isEmpty());
        assertFalse(cache.isCurrent(10, oldLoad));
        assertFalse(cache.isCurrent(10, newLoad));
        assertTrue(cache.isCurrent(11, newLoad));
    }

    @Test
    public void logoutInvalidatesQueuedRestoreEvenWhenSameUserLogsInAgain() {
        DeletedDialogService.AccountCache<String> cache = new DeletedDialogService.AccountCache<>();
        long queuedRestore = cache.bind(10);
        cache.messages.put(-20L, "dialog");
        cache.topics.put(-20L, new ConcurrentHashMap<>());

        cache.clear();
        long newSession = cache.bind(10);

        assertFalse(cache.isCurrent(10, queuedRestore));
        assertTrue(cache.isCurrent(10, newSession));
        assertTrue(cache.messages.isEmpty());
        assertTrue(cache.topics.isEmpty());
    }

    @Test
    public void switchingAwayAndBackDoesNotReviveQueuedLoad() {
        DeletedDialogService.AccountCache<String> cache = new DeletedDialogService.AccountCache<>();
        long queuedLoad = cache.bind(10);
        cache.bind(11);
        long latestLoad = cache.bind(10);

        assertFalse(cache.isCurrent(10, queuedLoad));
        assertTrue(cache.isCurrent(10, latestLoad));
    }

    @Test
    public void readingSameOwnerPreservesPreviewsAndPendingLoad() {
        DeletedDialogService.AccountCache<String> cache = new DeletedDialogService.AccountCache<>();
        long queuedLoad = cache.bind(10);
        cache.messages.put(-20L, "dialog");

        assertEquals(queuedLoad, cache.bind(10));
        assertEquals("dialog", cache.messages.get(-20L));
        assertTrue(cache.isCurrent(10, queuedLoad));
    }

    @Test
    public void inactiveSlotCannotCommitAndClearingOneSlotKeepsAnotherValid() {
        DeletedDialogService.AccountCache<String> first = new DeletedDialogService.AccountCache<>();
        DeletedDialogService.AccountCache<String> second = new DeletedDialogService.AccountCache<>();
        long firstLoad = first.bind(10);
        long secondLoad = second.bind(11);
        second.messages.put(-20L, "second slot");

        long inactive = first.bind(0);
        first.clear();

        assertFalse(first.isCurrent(10, firstLoad));
        assertFalse(first.isCurrent(0, inactive));
        assertFalse(first.isCurrent(0, first.bind(0)));
        assertTrue(second.isCurrent(11, secondLoad));
        assertEquals("second slot", second.messages.get(-20L));
    }
}
