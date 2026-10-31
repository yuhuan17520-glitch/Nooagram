package com.radolyn.ayugram.database;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArchiveSaveGuardTest {
    @Test
    public void clearingTopicInvalidatesQueuedSavesOnlyInThatScope() {
        ArchiveSaveGuard guard = new ArchiveSaveGuard();
        long queued = guard.capture();
        guard.invalidate(1, -20, 7);

        assertFalse(guard.isValid(queued, 1, -20, 7));
        assertTrue(guard.isValid(queued, 1, -20, 8));
        assertTrue(guard.isValid(queued, 2, -20, 7));
        assertTrue(guard.isValid(queued, 1, -21, 7));
        assertTrue(guard.isValid(guard.capture(), 1, -20, 7));
    }

    @Test
    public void clearingDialogAlsoInvalidatesEveryTopic() {
        ArchiveSaveGuard guard = new ArchiveSaveGuard();
        long queued = guard.capture();
        guard.invalidate(1, -20, 0);

        assertFalse(guard.isValid(queued, 1, -20, 0));
        assertFalse(guard.isValid(queued, 1, -20, 7));
        assertTrue(guard.isValid(queued, 1, -21, 7));
    }

    @Test
    public void databaseClearInvalidatesAllAccountsAndFutureSavesRemainValid() {
        ArchiveSaveGuard guard = new ArchiveSaveGuard();
        long queued = guard.capture();
        guard.invalidateAll();
        long afterClear = guard.capture();

        assertFalse(guard.isValid(queued, 1, -20, 7));
        assertFalse(guard.isValid(queued, 2, -21, 0));
        assertTrue(guard.isValid(afterClear, 1, -20, 7));
        guard.invalidate(1, -20, 7);
        assertFalse(guard.isValid(afterClear, 1, -20, 7));
        assertTrue(guard.isValid(afterClear, 2, -21, 0));
    }
}
