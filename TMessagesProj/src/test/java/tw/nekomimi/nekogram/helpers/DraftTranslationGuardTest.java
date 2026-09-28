package tw.nekomimi.nekogram.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DraftTranslationGuardTest {
    @Test
    public void unchangedDraftAcceptsCurrentResult() {
        DraftTranslationGuard guard = new DraftTranslationGuard();
        assertTrue(guard.canApply(guard.begin()));
    }

    @Test
    public void cancelledOrSupersededRequestCannotOverwriteDraft() {
        DraftTranslationGuard guard = new DraftTranslationGuard();
        DraftTranslationGuard.Ticket cancelled = guard.begin();
        guard.cancel();
        assertFalse(guard.canApply(cancelled));
        DraftTranslationGuard.Ticket older = guard.begin();
        DraftTranslationGuard.Ticket newer = guard.begin();
        assertFalse(guard.canApply(older));
        assertTrue(guard.canApply(newer));
    }

    @Test
    public void EditingAndThenRestoringTextStillRejectsOldResult() {
        DraftTranslationGuard guard = new DraftTranslationGuard();
        DraftTranslationGuard.Ticket ticket = guard.begin();
        guard.draftChanged();
        guard.draftChanged();
        assertFalse(guard.canApply(ticket));
    }
}
