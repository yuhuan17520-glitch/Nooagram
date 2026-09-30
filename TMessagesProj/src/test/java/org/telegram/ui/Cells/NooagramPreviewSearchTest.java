package org.telegram.ui.Cells;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class NooagramPreviewSearchTest {
    private NooagramPreviewSearch search() {
        return new NooagramPreviewSearch(102, 1020, 0);
    }

    @Test
    public void completionWithoutACellReleasesLoadingAndAllowsRetry() {
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        assertEquals(0, search.begin(10));
        assertTrue(search.complete(request, 20, true));
        assertFalse(search.accepts(request));
        assertEquals(0, search.begin(21));
        assertNotEquals(0, search.begin(20 + NooagramPreviewSearch.RETRY_MS));
    }

    @Test
    public void staleCompletionCannotEndTheNextRequest() {
        NooagramPreviewSearch search = search();
        long first = search.begin(0);
        search.complete(first, 0, true);
        long next = search.begin(NooagramPreviewSearch.RETRY_MS);
        assertFalse(search.complete(first, NooagramPreviewSearch.RETRY_MS, false));
        assertTrue(search.accepts(next));
    }

    @Test
    public void invalidationRejectsOldResultsAndOldHistoryPages() {
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        search.invalidate();
        assertFalse(search.complete(request, 10, false));
        assertFalse(search.historyStored(request, 80, 20));
        assertEquals(0, search.historyOffset());
    }

    @Test
    public void newestEligibleMessageWinsOverAnOlderSavedCandidate() {
        assertEquals(Integer.valueOf(101), NooagramPreviewSearch.latestEligible(
                List.of(102, 101, 100, 90), id -> id != 102 && id != 100));
    }

    @Test
    public void candidateSelectionSkipsNullBlockedAndRegexMatches() {
        assertEquals(Integer.valueOf(97), NooagramPreviewSearch.latestEligible(
                Arrays.asList(null, 100, 99, 98, 97), id -> id != 100 && id != 99 && id != 98));
        assertNull(NooagramPreviewSearch.latestEligible(List.of(100, 99), id -> false));
    }

    @Test
    public void sourceChangesAndEditsRequireNewSearches() {
        NooagramPreviewSearch search = search();
        assertTrue(search.matches(102, 1020, 0));
        assertFalse(search.matches(103, 1020, 0));
        assertFalse(search.matches(102, 1021, 0));
        assertFalse(search.matches(102, 1020, 1022));
    }

    @Test
    public void cacheKeysSeparateAccountsUsersSessionsAndFullDialogIds() {
        NooagramPreviewSearch.Key key = new NooagramPreviewSearch.Key(0, 10, 1, -77);
        assertEquals(key, new NooagramPreviewSearch.Key(0, 10, 1, -77));
        assertNotEquals(key, new NooagramPreviewSearch.Key(1, 10, 1, -77));
        assertNotEquals(key, new NooagramPreviewSearch.Key(0, 20, 1, -77));
        assertNotEquals(key, new NooagramPreviewSearch.Key(0, 10, 2, -77));
        assertNotEquals(key, new NooagramPreviewSearch.Key(0, 10, 1, -77 + (1L << 48)));
    }

    @Test
    public void allFilteredFirstPageContinuesFromOldestStoredId() {
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        assertEquals(0, search.historyOffset());
        assertTrue(search.historyStored(request, 80, 121));
        assertTrue(search.canLoadHistory());
        assertEquals(121, search.historyOffset());
        assertTrue(search.historyStored(request, 80, 41));
        assertEquals(41, search.historyOffset());
    }

    @Test
    public void fetchedCandidateBeyondLocalScanCapIsEvaluatedInItsOwnPage() {
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        int oldestLocalId = 2000 - NooagramPreviewSearch.MAX_LOCAL_PAGES * NooagramPreviewSearch.PAGE_SIZE + 1;
        search.localWindowExhausted(request, oldestLocalId);
        assertEquals(1201, search.historyOffset());
        assertTrue(search.historyStored(request, 80, 1121));
        assertEquals(Integer.valueOf(1200), NooagramPreviewSearch.latestEligible(List.of(1200, 1199), id -> id == 1200));
        search.localWindowExhausted(request, 1201);
        assertEquals(1121, search.historyOffset());
    }

    @Test
    public void failureRetriesTheSamePageWithoutConsumingThePageBudget() {
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        search.historyStored(request, 80, 400);
        search.complete(request, 0, true);
        assertTrue(search.shouldRetryAutomatically());
        request = search.begin(NooagramPreviewSearch.RETRY_MS);
        assertEquals(400, search.historyOffset());
        search.complete(request, NooagramPreviewSearch.RETRY_MS, true);
        assertFalse(search.shouldRetryAutomatically());
        request = search.begin(2 * NooagramPreviewSearch.RETRY_MS);
        for (int page = 1; page < NooagramPreviewSearch.MAX_HISTORY_PAGES; page++) {
            assertTrue(search.canLoadHistory());
            search.historyStored(request, 80, 400 - page * 80);
        }
        assertFalse(search.canLoadHistory());
    }

    @Test
    public void shortEmptyAndNonAdvancingPagesStopHistory() {
        for (int count : new int[]{0, 1, 79}) {
            NooagramPreviewSearch search = search();
            search.historyStored(search.begin(0), count, count == 0 ? 0 : 20);
            assertFalse(search.canLoadHistory());
        }
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        search.historyStored(request, 80, 20);
        search.historyStored(request, 80, 20);
        assertFalse(search.canLoadHistory());
        assertEquals(20, search.historyOffset());
    }

    @Test
    public void pageBudgetRenewsWithoutResettingTheOlderOffset() {
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        for (int page = 0; page < NooagramPreviewSearch.MAX_HISTORY_PAGES; page++) {
            assertTrue(search.historyStored(request, 80, 1000 - page * 80));
        }
        assertFalse(search.canLoadHistory());
        assertTrue(search.needsHistoryContinuation());
        assertTrue(search.complete(request, 10, false));
        assertEquals(0, search.begin(10));
        int offset = search.historyOffset();
        request = search.begin(10 + NooagramPreviewSearch.REFRESH_MS);
        assertNotEquals(0, request);
        assertTrue(search.canLoadHistory());
        assertEquals(offset, search.historyOffset());
        assertTrue(search.historyStored(request, 80, offset - 80));
        assertEquals(offset - 80, search.historyOffset());
    }

    @Test
    public void exhaustedHistoryDoesNotRestartAfterCooldown() {
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        search.historyStored(request, 0, 0);
        search.complete(request, 10, false);
        assertFalse(search.needsHistoryContinuation());
        assertNotEquals(0, search.begin(10 + NooagramPreviewSearch.REFRESH_MS));
        assertFalse(search.canLoadHistory());
    }

    @Test
    public void previewDateUsesRealSourceOrDialogDatesInsteadOfZero() {
        assertEquals(100, NooagramPreviewSearch.previewDate(100, 200, 300));
        assertEquals(200, NooagramPreviewSearch.previewDate(0, 200, 300));
        assertEquals(300, NooagramPreviewSearch.previewDate(0, 0, 300));
        assertEquals(0, NooagramPreviewSearch.previewDate(0, 0, 0));
        assertEquals(0, NooagramPreviewSearch.previewDate(-1, -2, -3));
    }

    @Test
    public void invalidatingAReplacementCanSearchImmediatelyInsteadOfWaitingForCooldown() {
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        assertTrue(search.complete(request, 10, false));
        assertEquals(0, search.begin(10));
        search.invalidate();
        assertNotEquals(0, search.begin(10));
    }

    @Test
    public void successfulCompletionDoesNotCreateARefreshLoop() {
        NooagramPreviewSearch search = search();
        long request = search.begin(0);
        assertTrue(search.complete(request, 1, false));
        assertFalse(search.shouldRetryAutomatically());
        assertEquals(0, search.begin(1));
        assertEquals(0, search.begin(2));
        assertNotEquals(0, search.begin(1 + NooagramPreviewSearch.REFRESH_MS));
    }
}
