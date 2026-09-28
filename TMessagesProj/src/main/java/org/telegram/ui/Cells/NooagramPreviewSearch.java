package org.telegram.ui.Cells;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Request state belongs to a login and dialog, never to a recycled view. */
final class NooagramPreviewSearch {
    static final int PAGE_SIZE = 80;
    static final int MAX_LOCAL_PAGES = 10;
    static final int MAX_HISTORY_PAGES = 5;
    static final long RETRY_MS = 3000;
    static final long REFRESH_MS = 60000;

    static final class Key {
        final int account;
        final long userId;
        final long generation;
        final long dialogId;

        Key(int account, long userId, long generation, long dialogId) {
            this.account = account;
            this.userId = userId;
            this.generation = generation;
            this.dialogId = dialogId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return account == key.account && userId == key.userId
                    && generation == key.generation && dialogId == key.dialogId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(account, userId, generation, dialogId);
        }
    }

    final int sourceId;
    final int sourceDate;
    final int sourceEditDate;
    private long token;
    private boolean loading;
    private long retryAt;
    private int historyOffset;
    private int historyPages;
    private boolean historyEnd;
    private int failures;

    NooagramPreviewSearch(int sourceId, int sourceDate, int sourceEditDate) {
        this.sourceId = sourceId;
        this.sourceDate = sourceDate;
        this.sourceEditDate = sourceEditDate;
    }

    boolean matches(int id, int date, int editDate) {
        return sourceId == id && sourceDate == date && sourceEditDate == editDate;
    }

    long begin(long now) {
        if (loading || now < retryAt) {
            return 0;
        }
        loading = true;
        return ++token;
    }

    boolean accepts(long requestToken) {
        return loading && token == requestToken;
    }

    boolean complete(long requestToken, long now, boolean failed) {
        if (!accepts(requestToken)) {
            return false;
        }
        loading = false;
        failures = failed ? failures + 1 : 0;
        retryAt = now + (failed ? RETRY_MS : REFRESH_MS);
        return true;
    }

    void invalidate() {
        ++token;
        loading = false;
    }

    boolean canLoadHistory() {
        return !historyEnd && historyPages < MAX_HISTORY_PAGES;
    }

    int historyOffset() {
        return historyOffset;
    }

    void localWindowExhausted(long requestToken, int oldestId) {
        if (accepts(requestToken) && historyPages == 0 && historyOffset == 0 && oldestId > 0) {
            historyOffset = oldestId;
        }
    }

    // Called only after the response's storage write and verification have finished.
    boolean historyStored(long requestToken, int count, int oldestId) {
        if (!accepts(requestToken)) {
            return false;
        }
        ++historyPages;
        boolean advanced = oldestId > 0 && (historyOffset == 0 || oldestId < historyOffset);
        historyEnd = count < PAGE_SIZE || !advanced;
        if (advanced) {
            historyOffset = oldestId;
        }
        return true;
    }

    boolean shouldRetryAutomatically() {
        return failures == 1;
    }

    static <T> T latestEligible(List<T> newestFirst, Predicate<T> eligible) {
        for (T candidate : newestFirst) {
            if (candidate != null && eligible.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
