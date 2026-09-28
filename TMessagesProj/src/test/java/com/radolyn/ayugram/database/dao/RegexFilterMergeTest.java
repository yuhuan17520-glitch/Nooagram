package com.radolyn.ayugram.database.dao;

import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

public class RegexFilterMergeTest {
    @Test
    public void preservesAllChatsAndRemapsDeduplicatedExclusions() {
        RegexFilter local = filter("A", "advert", null);
        local.enabled = false;
        RegexFilter hiddenChat = filter("chat", "private rule", -123L);
        RegexFilter incoming = filter("B", "advert", null);
        RegexFilterDao.Snapshot current = new RegexFilterDao.Snapshot(Arrays.asList(local, hiddenChat), Collections.emptyList());

        RegexFilterDao.ImportResult merged = RegexFilterDao.merge(current, Arrays.asList(incoming,
                filter("new-chat", "other rule", -456L)), Collections.singletonList(exclusion(-789L, "B")), true);

        assertEquals(3, merged.filters.size());
        assertEquals("chat", merged.filters.get(1).id);
        assertEquals(Long.valueOf(-123L), merged.filters.get(1).dialogId);
        assertEquals("A", merged.exclusions.get(0).filterId);
        assertEquals(0, merged.newFilters);
        assertEquals(1, merged.newChatFilters);
        assertEquals(1, merged.updatedFilters);
        assertEquals(1, merged.newExclusions);
        assertFalse(local.enabled);
        assertEquals("B", incoming.id);
    }

    @Test
    public void previewDoesNotCountAnAlreadyRemappedExclusion() {
        RegexFilterDao.Snapshot current = new RegexFilterDao.Snapshot(Collections.singletonList(filter("A", "advert", null)),
                Collections.singletonList(exclusion(-1L, "A")));
        RegexFilterDao.ImportResult merged = RegexFilterDao.merge(current, Collections.singletonList(filter("B", "advert", null)),
                Arrays.asList(exclusion(-1L, "B"), exclusion(-1L, "B")), true);
        assertEquals(1, merged.filters.size());
        assertEquals(1, merged.exclusions.size());
        assertEquals(0, merged.newExclusions);
    }

    @Test
    public void conflictingIdsDoNotOverwriteExistingRules() {
        RegexFilterDao.Snapshot current = new RegexFilterDao.Snapshot(Collections.singletonList(filter("A", "local", -1L)),
                Collections.emptyList());
        RegexFilterDao.ImportResult merged = RegexFilterDao.merge(current, Collections.singletonList(filter("A", "incoming", null)),
                Collections.singletonList(exclusion(-2L, "A")), true);
        assertEquals(2, merged.filters.size());
        assertEquals("local", merged.filters.get(0).text);
        assertNotEquals("A", merged.filters.get(1).id);
        assertEquals(merged.filters.get(1).id, merged.exclusions.get(0).filterId);
        RegexFilterDao.ImportResult retry = RegexFilterDao.merge(merged, Collections.singletonList(filter("A", "incoming", null)),
                Collections.singletonList(exclusion(-2L, "A")), true);
        assertEquals(2, retry.filters.size());
        assertEquals(1, retry.exclusions.size());
        assertEquals(0, retry.newFilters);
    }

    @Test
    public void migrationRepairsPartialRowsAndCanReplayAfterCommit() {
        RegexFilter local = filter("A", "first", null);
        local.enabled = false;
        RegexFilterDao.Snapshot partial = new RegexFilterDao.Snapshot(Collections.singletonList(local), Collections.emptyList());
        RegexFilterDao.ImportResult repaired = RegexFilterDao.merge(partial,
                Arrays.asList(filter("A", "first", null), filter(null, "second", -1L)),
                Collections.singletonList(exclusion(-2L, "A")), false);
        assertEquals(2, repaired.filters.size());
        assertEquals(1, repaired.exclusions.size());
        assertFalse(repaired.filters.get(0).enabled);
        RegexFilterDao.ImportResult replay = RegexFilterDao.merge(repaired,
                Arrays.asList(filter("A", "first", null), filter(null, "second", -1L)),
                Collections.singletonList(exclusion(-2L, "A")), false);
        assertEquals(2, replay.filters.size());
        assertEquals(repaired.filters.get(1).id, replay.filters.get(1).id);
        assertEquals(0, replay.newFilters + replay.newChatFilters + replay.newExclusions);
    }

    @Test
    public void sameTextWithDifferentOptionsOrScopeStaysDistinct() {
        RegexFilter reversed = filter("reverse", "same", null);
        reversed.reversed = true;
        RegexFilter exactCase = filter("exact", "same", null);
        exactCase.caseInsensitive = false;
        RegexFilterDao.ImportResult merged = RegexFilterDao.merge(new RegexFilterDao.Snapshot(
                        Collections.singletonList(filter("global", "same", null)), Collections.emptyList()),
                Arrays.asList(reversed, exactCase, filter("chat", "same", -1L)), Collections.emptyList(), true);
        assertEquals(4, merged.filters.size());
    }

    @Test
    public void migrationReplayPreservesAnExistingRuleEditedSinceItsBackup() {
        RegexFilterDao.ImportResult result = RegexFilterDao.merge(new RegexFilterDao.Snapshot(
                        Collections.singletonList(filter("A", "edited", null)), Collections.emptyList()),
                Collections.singletonList(filter("A", "old text", null)), Collections.emptyList(), false);
        assertEquals(1, result.filters.size());
        assertEquals("edited", result.filters.get(0).text);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAmbiguousSharedIdsBeforeWriting() {
        RegexFilterDao.merge(new RegexFilterDao.Snapshot(Collections.emptyList(), Collections.emptyList()),
                Arrays.asList(filter("A", "first", null), filter("A", "second", null)),
                Collections.singletonList(exclusion(-1L, "A")), true);
    }

    private static RegexFilter filter(String id, String text, Long dialogId) {
        RegexFilter row = new RegexFilter();
        row.id = id;
        row.text = text;
        row.dialogId = dialogId;
        row.enabled = true;
        row.caseInsensitive = true;
        return row;
    }

    private static RegexFilterGlobalExclusion exclusion(long dialogId, String id) {
        RegexFilterGlobalExclusion row = new RegexFilterGlobalExclusion();
        row.dialogId = dialogId;
        row.filterId = id;
        return row;
    }
}
