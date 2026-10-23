package com.radolyn.ayugram.utils.seq;

import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AyuSendCancellationTest {
    @Before public void setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    @Test public void cancelledTaskNeverQueuesOrExecutesASend() {
        AtomicBoolean sent = new AtomicBoolean();
        assertFalse(AyuSequentialUtils.dispatchSendSync(0, 10, null, false, false,
                () -> false, () -> sent.set(true)));
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(sent.get());
    }

    @Test public void noWaitGroupMemberReturnsOnlyAfterDispatchRuns() {
        AtomicBoolean sent = new AtomicBoolean();
        assertTrue(AyuSequentialUtils.dispatchSendSync(0, 10, null, false, false,
                () -> true, () -> sent.set(true)));
        assertTrue(sent.get());
    }

    @Test public void cancelledWaiterReleasesPromptlyWithoutCancellingSharedDownloads() {
        SyncWaiter waiter = new SyncWaiter(0) {
            @Override public void didReceivedNotification(int id, int account, Object... args) {}
        };
        assertFalse(waiter.await(() -> false));
        assertTrue(waiter.isReleased());
        waiter.cancel();
        assertTrue(waiter.isReleased());
    }

    @Test public void interruptedWorkerCannotLeaveAQueuedSendBehind() throws Exception {
        assertQueuedSendCancelled(true);
    }

    @Test public void stoppingAfterQueueingPreventsUiDispatch() throws Exception {
        assertQueuedSendCancelled(false);
    }

    private void assertQueuedSendCancelled(boolean interrupt) throws Exception {
        AtomicBoolean sent = new AtomicBoolean();
        AtomicBoolean result = new AtomicBoolean(true);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger checks = new AtomicInteger();
        CountDownLatch queued = new CountDownLatch(1);
        Thread worker = new Thread(() -> result.set(AyuSequentialUtils.dispatchSendSync(0, 10, null, false, false,
                () -> {
                    if (checks.incrementAndGet() >= 2) queued.countDown();
                    return active.get();
                }, () -> sent.set(true))));
        worker.start();
        try {
            assertTrue("Worker did not queue its UI dispatch", queued.await(2, TimeUnit.SECONDS));
            if (interrupt) worker.interrupt();
            else active.set(false);
            worker.join(2000);
            assertFalse("Worker did not stop promptly", worker.isAlive());
            assertFalse(result.get());
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("Cancelled UI callback sent a message", sent.get());
        } finally {
            active.set(false);
            worker.interrupt();
            worker.join(2000);
        }
    }

    @Test public void unrelatedSendErrorDoesNotFailThisMessage() {
        DummyMessageWaiter waiter = new DummyMessageWaiter(0);
        waiter.sendingId = -10;
        waiter.didReceivedNotification(NotificationCenter.messageSendError, 0, -20);
        assertFalse(waiter.hasFailed());
        assertFalse(waiter.isReleased());
        waiter.didReceivedNotification(NotificationCenter.messageSendError, 0, -10);
        assertTrue(waiter.hasFailed());
        assertTrue(waiter.isReleased());
    }
}
