package com.radolyn.ayugram.database;

import com.radolyn.ayugram.database.dao.RegexFilterDao;
import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RegexFilterRestoreTest {
    @Test
    public void suppliedScopesReplaceExistingRulesAndExclusions() {
        RegexFilterDao.Snapshot current = snapshot(filter("old", null), filter("old-chat", -1L));
        RegexFilterDao.Snapshot backup = new RegexFilterDao.Snapshot(
                Arrays.asList(filter("restored", null), filter("restored-chat", -2L)),
                Collections.singletonList(exclusion(-3L, "restored")));
        RegexFilterDao.Snapshot restored = RegexFilterDao.restore(current, backup, true, true, true);
        assertEquals(2, restored.filters.size());
        assertEquals("restored", restored.filters.get(0).id);
        assertEquals("restored-chat", restored.filters.get(1).id);
        assertEquals("restored", restored.exclusions.get(0).filterId);
    }

    @Test
    public void emptySuppliedScopeClearsItAndMissingScopeIsPreserved() {
        RegexFilterDao.Snapshot current = new RegexFilterDao.Snapshot(
                Arrays.asList(filter("old", null), filter("chat", -1L)),
                Collections.singletonList(exclusion(-3L, "old")));
        RegexFilterDao.Snapshot restored = RegexFilterDao.restore(current, snapshot(), true, false, false);
        assertEquals(1, restored.filters.size());
        assertEquals("chat", restored.filters.get(0).id);
        assertTrue(restored.exclusions.isEmpty());
    }

    @Test
    public void exclusionsOnlyRestoreDoesNotReplaceRules() {
        RegexFilterDao.Snapshot current = new RegexFilterDao.Snapshot(
                Collections.singletonList(filter("shared", null)),
                Collections.singletonList(exclusion(-1L, "shared")));
        RegexFilterDao.Snapshot backup = new RegexFilterDao.Snapshot(Collections.emptyList(),
                Collections.singletonList(exclusion(-2L, "shared")));
        RegexFilterDao.Snapshot restored = RegexFilterDao.restore(current, backup, false, false, true);
        assertEquals(1, restored.filters.size());
        assertEquals(1, restored.exclusions.size());
        assertEquals(-2L, restored.exclusions.get(0).dialogId);
    }

    private static RegexFilterDao.Snapshot snapshot(RegexFilter... filters) {
        return new RegexFilterDao.Snapshot(Arrays.asList(filters), Collections.emptyList());
    }

    private static RegexFilter filter(String id, Long dialogId) {
        RegexFilter filter = new RegexFilter();
        filter.id = filter.text = id;
        filter.dialogId = dialogId;
        filter.enabled = true;
        return filter;
    }

    private static RegexFilterGlobalExclusion exclusion(long dialogId, String filterId) {
        RegexFilterGlobalExclusion exclusion = new RegexFilterGlobalExclusion();
        exclusion.dialogId = dialogId;
        exclusion.filterId = filterId;
        return exclusion;
    }
}
