package com.radolyn.ayugram.database;

import com.google.gson.JsonParseException;
import com.radolyn.ayugram.database.dao.RegexFilterDao;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class FilterPrefsMigratorTest {
    @Test
    public void parsesAllScopesOptionsAndExclusions() {
        RegexFilterDao.Snapshot snapshot = FilterPrefsMigrator.parseLegacy(
                "[{\"id\":\"A\",\"regex\":\"advert\",\"enabled\":false,\"caseInsensitive\":true,\"reversed\":true}]",
                "[{\"dialogId\":-123,\"filters\":[{\"id\":\"B\",\"regex\":\"chat\"}]}]",
                "[{\"dialogId\":-456,\"filterId\":\"A\"}]");
        assertEquals(2, snapshot.filters.size());
        assertFalse(snapshot.filters.get(0).enabled);
        assertTrue(snapshot.filters.get(0).caseInsensitive);
        assertTrue(snapshot.filters.get(0).reversed);
        assertTrue(snapshot.filters.get(1).enabled);
        assertEquals(Long.valueOf(-123L), snapshot.filters.get(1).dialogId);
        assertEquals("A", snapshot.exclusions.get(0).filterId);
        assertEquals(-456L, snapshot.exclusions.get(0).dialogId);
    }

    @Test
    public void missingIdsAreStableAcrossRestartsAndDistinctAcrossScopes() {
        String shared = "[{\"regex\":\"same\"}]";
        String chats = "[{\"dialogId\":-123,\"filters\":[{\"regex\":\"same\"}]}]";
        RegexFilterDao.Snapshot first = FilterPrefsMigrator.parseLegacy(shared, chats, "[]");
        RegexFilterDao.Snapshot retry = FilterPrefsMigrator.parseLegacy(shared, chats, "[]");
        assertEquals(first.filters.get(0).id, retry.filters.get(0).id);
        assertEquals(first.filters.get(1).id, retry.filters.get(1).id);
        assertNotEquals(first.filters.get(0).id, first.filters.get(1).id);
    }

    @Test
    public void keepsLegacyEnabledAndDisabledGroupSemantics() {
        RegexFilterDao.Snapshot snapshot = FilterPrefsMigrator.parseLegacy(
                "[{\"regex\":\"global\",\"enabledGroups\":[0]}]",
                "[{\"dialogId\":-123,\"filters\":[{\"regex\":\"chat\",\"enabledGroups\":[0],\"disabledGroups\":[-123]}]}]", "[]");
        assertTrue(snapshot.filters.get(0).enabled);
        assertFalse(snapshot.filters.get(1).enabled);
    }

    @Test(expected = JsonParseException.class)
    public void invalidLaterJsonFailsBeforeAnyTransactionCanStart() {
        FilterPrefsMigrator.parseLegacy("[{\"regex\":\"valid\"}]", "[]", "{broken");
    }
}
