package tw.nekomimi.nekogram.helpers.remote;

import org.junit.Test;

import static org.junit.Assert.*;

public class NooagramUpdateCheckSchedulerTest {
    private static final long HOUR = 60 * 60 * 1000L;
    private static final long RETRY = 5 * 60 * 1000L;
    private static final long START = 1_800_000_000_000L;

    @Test
    public void firstForegroundChecksImmediatelyWithoutHistory() {
        Harness h = new Harness();
        h.scheduler.resume();
        assertEquals(1, h.requests);
        assertNull(h.timer.task);
    }

    @Test
    public void successfulCheckSchedulesOneHourAndChecksWithoutAnotherResume() {
        Harness h = new Harness();
        h.scheduler.resume();
        h.scheduler.complete(true);
        assertEquals(HOUR, h.timer.delay());
        h.advance(HOUR - 1);
        assertEquals(1, h.requests);
        h.advance(1);
        assertEquals(2, h.requests);
    }

    @Test
    public void returningToForegroundUsesRemainingHour() {
        Harness h = new Harness();
        h.state.success = START - HOUR / 2;
        h.scheduler.resume();
        assertEquals(0, h.requests);
        assertEquals(HOUR / 2, h.timer.delay());
        h.scheduler.pause();
        h.advance(HOUR / 4);
        h.scheduler.resume();
        assertEquals(0, h.requests);
        assertEquals(HOUR / 4, h.timer.delay());
    }

    @Test
    public void existingHistoryChecksImmediatelyOnceHourHasElapsed() {
        Harness h = new Harness();
        h.state.success = START - HOUR;
        h.scheduler.resume();
        assertEquals(1, h.requests);
    }

    @Test
    public void failedFirstRequestRetriesInFiveMinutesInsteadOfLooping() {
        Harness h = new Harness();
        h.scheduler.resume();
        h.scheduler.complete(false);
        assertEquals(0, h.state.success);
        assertEquals(RETRY, h.timer.delay());
        h.scheduler.resume();
        h.advance(RETRY - 1);
        assertEquals(1, h.requests);
        h.advance(1);
        assertEquals(2, h.requests);
    }

    @Test
    public void failedManualRequestRetriesSoonEvenAfterRecentSuccess() {
        Harness h = new Harness();
        h.state.success = START - 1000;
        h.scheduler.resume();
        assertTrue(h.scheduler.begin(true));
        h.scheduler.complete(false);
        assertEquals(START - 1000, h.state.success);
        assertEquals(RETRY, h.timer.delay());
        h.advance(RETRY);
        assertEquals(1, h.requests);
    }

    @Test
    public void retrySuccessReturnsToHourlySchedule() {
        Harness h = new Harness();
        h.scheduler.resume();
        h.scheduler.complete(false);
        h.advance(RETRY);
        h.scheduler.complete(true);
        assertEquals(0, h.state.failure);
        assertEquals(HOUR, h.timer.delay());
    }

    @Test
    public void restartRetainsFailureCooldownAndRetriesWhenDue() {
        Harness h = new Harness();
        h.state.failure = START - RETRY / 2;
        h.scheduler.resume();
        assertEquals(0, h.requests);
        assertEquals(RETRY / 2, h.timer.delay());
        h.advance(RETRY / 2);
        assertEquals(1, h.requests);
    }

    @Test
    public void manualCheckBypassesSuccessAndFailureCooldowns() {
        Harness h = new Harness();
        h.state.success = START;
        assertTrue(h.scheduler.begin(true));
        h.scheduler.complete(false);
        assertTrue(h.scheduler.begin(true));
    }

    @Test
    public void disablingAutomaticChecksStillAllowsManualCheck() {
        Harness h = new Harness();
        h.enabled = false;
        h.scheduler.resume();
        assertEquals(0, h.requests);
        assertNull(h.timer.task);
        assertTrue(h.scheduler.begin(true));
        h.scheduler.complete(true);
        assertNull(h.timer.task);
    }

    @Test
    public void disablingPreferenceBeforeTimerFiresPreventsRequest() {
        Harness h = new Harness();
        h.state.success = START;
        h.scheduler.resume();
        h.enabled = false;
        h.advance(HOUR);
        assertEquals(0, h.requests);
        assertNull(h.timer.task);
    }

    @Test
    public void inFlightRequestPreventsDuplicatesOnResumeAndManualClick() {
        Harness h = new Harness();
        h.scheduler.resume();
        h.scheduler.resume();
        assertFalse(h.scheduler.begin(true));
        assertTrue(h.scheduler.wasManuallyRequested());
        assertEquals(1, h.requests);
        h.scheduler.complete(true);
        assertTrue(h.scheduler.begin(true));
    }

    @Test
    public void joiningManualRequestDoesNotMakeLaterAutomaticRetryManual() {
        Harness h = new Harness();
        h.scheduler.resume();
        assertFalse(h.scheduler.wasManuallyRequested());
        assertFalse(h.scheduler.begin(true));
        assertTrue(h.scheduler.wasManuallyRequested());
        h.scheduler.complete(false);
        assertFalse(h.scheduler.wasManuallyRequested());
        h.advance(RETRY);
        assertEquals(2, h.requests);
        assertFalse(h.scheduler.wasManuallyRequested());
    }

    @Test
    public void pausingCancelsTimerAndOverdueResumeChecksImmediately() {
        Harness h = new Harness();
        h.scheduler.resume();
        h.scheduler.complete(true);
        h.scheduler.pause();
        assertNull(h.timer.task);
        h.advance(HOUR);
        assertEquals(1, h.requests);
        h.scheduler.resume();
        assertEquals(2, h.requests);
    }

    @Test
    public void completionAfterPauseSavesResultWithoutSchedulingBackgroundWork() {
        Harness h = new Harness();
        h.scheduler.resume();
        h.scheduler.pause();
        h.scheduler.complete(false);
        assertEquals(START, h.state.failure);
        assertNull(h.timer.task);
        h.advance(RETRY);
        assertEquals(1, h.requests);
        h.scheduler.resume();
        assertEquals(2, h.requests);
    }

    @Test
    public void backwardsClockDoesNotDelayChecksForHoursOrDays() {
        Harness h = new Harness();
        h.state.success = START + HOUR;
        h.scheduler.resume();
        assertEquals(1, h.requests);
        h.scheduler.complete(false);
        h.now -= HOUR;
        h.scheduler.resume();
        assertEquals(2, h.requests);
    }

    private static class Harness {
        long now = START;
        boolean enabled = true;
        int requests;
        final MemoryState state = new MemoryState();
        final FakeTimer timer = new FakeTimer(this);
        final NooagramUpdateCheckScheduler scheduler = new NooagramUpdateCheckScheduler(
                state, timer, () -> now, () -> enabled, this::checkAutomatically);

        void checkAutomatically() {
            if (scheduler.begin(false)) {
                requests++;
            }
        }

        void advance(long millis) {
            now += millis;
            if (timer.task != null && now >= timer.due) {
                Runnable task = timer.task;
                timer.task = null;
                task.run();
            }
        }
    }

    private static class MemoryState implements NooagramUpdateCheckScheduler.State {
        long success;
        long failure;

        @Override public long lastSuccessTime() { return success; }
        @Override public long lastFailureTime() { return failure; }

        @Override public void recordResult(boolean succeeded, long time) {
            if (succeeded) {
                success = time;
                failure = 0;
            } else {
                failure = time;
            }
        }
    }

    private static class FakeTimer implements NooagramUpdateCheckScheduler.Timer {
        final Harness h;
        Runnable task;
        long due;

        FakeTimer(Harness h) { this.h = h; }

        @Override public void postDelayed(Runnable task, long delay) {
            assertNull("Only one future check may be scheduled", this.task);
            this.task = task;
            due = h.now + delay;
        }

        @Override public void cancel(Runnable task) {
            if (this.task == task) {
                this.task = null;
            }
        }

        long delay() {
            assertNotNull(task);
            return due - h.now;
        }
    }
}
