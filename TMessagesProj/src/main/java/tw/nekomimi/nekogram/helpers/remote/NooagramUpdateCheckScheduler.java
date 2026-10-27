package tw.nekomimi.nekogram.helpers.remote;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Coordinates update checks on the UI thread; no work is scheduled while backgrounded. */
public final class NooagramUpdateCheckScheduler {
    public static final long CHECK_INTERVAL_MS = 60 * 60 * 1000L;
    public static final long RETRY_INTERVAL_MS = 5 * 60 * 1000L;

    public interface State {
        long lastSuccessTime();
        long lastFailureTime();
        void recordResult(boolean success, long time);
    }

    public interface Timer {
        void postDelayed(Runnable task, long delay);
        void cancel(Runnable task);
    }

    private final State state;
    private final Timer timer;
    private final LongSupplier clock;
    private final BooleanSupplier enabled;
    private final Runnable automaticCheck;
    private final Runnable scheduledCheck;
    private boolean foreground;
    private boolean inProgress;
    private boolean manuallyRequested;

    public NooagramUpdateCheckScheduler(State state, Timer timer, LongSupplier clock,
                                       BooleanSupplier enabled, Runnable automaticCheck) {
        this.state = state;
        this.timer = timer;
        this.clock = clock;
        this.enabled = enabled;
        this.automaticCheck = automaticCheck;
        scheduledCheck = () -> {
            if (foreground && enabled.getAsBoolean()) {
                this.automaticCheck.run();
            }
        };
    }

    public void resume() {
        foreground = true;
        automaticCheck.run();
    }

    public void pause() {
        foreground = false;
        timer.cancel(scheduledCheck);
    }

    /** Manual checks bypass both the interval and the automatic-check preference. */
    public boolean begin(boolean force) {
        if (inProgress) {
            // A manual click joins the current request and still receives its result.
            manuallyRequested |= force;
            return false;
        }
        timer.cancel(scheduledCheck);
        if (!force && !enabled.getAsBoolean()) {
            return false;
        }
        if (!force && nextDelay() > 0) {
            scheduleNext();
            return false;
        }
        inProgress = true;
        manuallyRequested = force;
        return true;
    }

    public boolean wasManuallyRequested() {
        return inProgress && manuallyRequested;
    }

    public void complete(boolean success) {
        if (!inProgress) {
            return;
        }
        inProgress = false;
        state.recordResult(success, clock.getAsLong());
        scheduleNext();
    }

    private void scheduleNext() {
        timer.cancel(scheduledCheck);
        if (foreground && !inProgress && enabled.getAsBoolean()) {
            timer.postDelayed(scheduledCheck, nextDelay());
        }
    }

    private long nextDelay() {
        // A failure has its own short cooldown and never counts as a successful check.
        long failure = state.lastFailureTime();
        long lastCheck = failure > 0 ? failure : state.lastSuccessTime();
        long interval = failure > 0 ? RETRY_INTERVAL_MS : CHECK_INTERVAL_MS;
        long now = clock.getAsLong();
        // Missing history and a backwards wall-clock change must not postpone updates.
        if (lastCheck <= 0 || lastCheck > now) {
            return 0;
        }
        return Math.max(0, interval - (now - lastCheck));
    }
}
