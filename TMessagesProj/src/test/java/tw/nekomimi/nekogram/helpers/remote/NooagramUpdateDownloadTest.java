package tw.nekomimi.nekogram.helpers.remote;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import static org.junit.Assert.*;

public class NooagramUpdateDownloadTest {
    private static final String NAME = "update.apk";
    private static final Request REQUEST = new Request.Builder().url("https://example.invalid/update.apk").build();

    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void httpFailureIncludesFailureReasonAndRemovesTemporaryFile() throws Exception {
        assertFailure(new FakeCall(() -> response(503, body("error"))), 4);
    }

    @Test
    public void readFailureIncludesFailureReasonAndRemovesTemporaryFile() throws Exception {
        assertFailure(new FakeCall(() -> { throw new IOException("timeout"); }), 4);
    }

    @Test
    public void incompleteResponseIsNotPromoted() throws Exception {
        assertFailure(new FakeCall(() -> response(200, body("short"))), 10);
    }

    private void assertFailure(FakeCall call, long size) throws Exception {
        QueuedExecutor worker = new QueuedExecutor();
        QueuedExecutor callbacks = new QueuedExecutor();
        Events events = new Events();
        NooagramUpdateHelper.DownloadManager manager = new NooagramUpdateHelper.DownloadManager(worker, callbacks, events);
        File target = new File(files.getRoot(), NAME);
        manager.start(2, NAME, target, size, call.call);
        worker.drain();
        callbacks.drain();
        assertEquals(1, events.failures.size());
        assertArrayEquals(new Object[]{2, NAME, 0}, events.failures.get(0));
        assertFalse(manager.isDownloading(NAME));
        assertNull(manager.getProgress(NAME));
        assertFalse(target.exists());
        assertEquals(0, files.getRoot().list().length);
    }

    @Test
    public void cancellationBeforeExecutionReportsReasonOnce() {
        QueuedExecutor worker = new QueuedExecutor();
        QueuedExecutor callbacks = new QueuedExecutor();
        Events events = new Events();
        NooagramUpdateHelper.DownloadManager manager = new NooagramUpdateHelper.DownloadManager(worker, callbacks, events);
        FakeCall call = new FakeCall(() -> response(200, body("data")));
        manager.start(1, NAME, new File(files.getRoot(), NAME), 4, call.call);
        manager.cancel(NAME);
        manager.cancel(NAME);
        worker.drain();
        callbacks.drain();
        assertTrue(call.cancelled);
        assertEquals(0, call.executions);
        assertEquals(1, events.failures.size());
        assertArrayEquals(new Object[]{1, NAME, 1}, events.failures.get(0));
        assertTrue(events.progress.isEmpty());
        assertFalse(manager.isDownloading(NAME));
    }

    @Test
    public void duplicateStartHasOnlyOneOwner() {
        QueuedExecutor worker = new QueuedExecutor();
        QueuedExecutor callbacks = new QueuedExecutor();
        Events events = new Events();
        NooagramUpdateHelper.DownloadManager manager = new NooagramUpdateHelper.DownloadManager(worker, callbacks, events);
        FakeCall first = new FakeCall(() -> response(200, body("data")));
        FakeCall second = new FakeCall(() -> response(200, body("oops")));
        File target = new File(files.getRoot(), NAME);
        manager.start(0, NAME, target, 4, first.call);
        manager.start(0, NAME, target, 4, second.call);
        worker.drain();
        callbacks.drain();
        assertEquals(1, first.executions);
        assertEquals(0, second.executions);
        assertEquals(1, events.loaded.size());
    }

    @Test
    public void cancellationAtEofCannotPromoteFile() {
        QueuedExecutor worker = new QueuedExecutor();
        QueuedExecutor callbacks = new QueuedExecutor();
        Events events = new Events();
        NooagramUpdateHelper.DownloadManager manager = new NooagramUpdateHelper.DownloadManager(worker, callbacks, events);
        Source source = new Source() {
            final Buffer data = new Buffer().writeUtf8("data");
            @Override public long read(Buffer sink, long byteCount) { return data.read(sink, byteCount); }
            @Override public Timeout timeout() { return Timeout.NONE; }
            @Override public void close() { manager.cancel(NAME); }
        };
        FakeCall call = new FakeCall(() -> response(200, ResponseBody.create(null, 4, Okio.buffer(source))));
        File target = new File(files.getRoot(), NAME);
        manager.start(0, NAME, target, 4, call.call);
        worker.drain();
        callbacks.drain();
        assertFalse(target.exists());
        assertEquals(1, events.failures.size());
        assertEquals(1, events.failures.get(0)[2]);
        assertTrue(events.loaded.isEmpty());
        assertEquals(0, files.getRoot().list().length);
    }

    @Test
    public void cancelledReadCannotRemoveReplacementOrShareTemporaryFile() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Future<?>> running = new ArrayList<>();
        Executor worker = runnable -> running.add(workers.submit(runnable));
        QueuedExecutor callbacks = new QueuedExecutor();
        Events events = new Events();
        NooagramUpdateHelper.DownloadManager manager = new NooagramUpdateHelper.DownloadManager(worker, callbacks, events);
        BlockingSource oldSource = new BlockingSource("old", true);
        BlockingSource newSource = new BlockingSource("new", false);
        File target = new File(files.getRoot(), NAME);
        try {
            manager.start(0, NAME, target, 4, new FakeCall(() -> response(200, oldSource.body())).call);
            assertTrue(oldSource.blocked.await(5, TimeUnit.SECONDS));
            manager.cancel(NAME);
            manager.start(0, NAME, target, 4, new FakeCall(() -> response(200, newSource.body())).call);
            assertTrue(newSource.blocked.await(5, TimeUnit.SECONDS));
            assertEquals(2, files.getRoot().list().length);
            callbacks.drain();
            assertTrue(events.failures.isEmpty());
            assertEquals(2, events.progress.size());
            oldSource.release.countDown();
            running.get(0).get(5, TimeUnit.SECONDS);
            assertEquals(1, files.getRoot().list().length);
            assertTrue(manager.isDownloading(NAME));
            assertEquals(0.75f, manager.getProgress(NAME), 0.001f);
            newSource.release.countDown();
            running.get(1).get(5, TimeUnit.SECONDS);
            callbacks.drain();
            assertTrue(events.failures.isEmpty());
            assertEquals(1, events.loaded.size());
            assertEquals("new!", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
            assertEquals(1, files.getRoot().list().length);
            assertFalse(manager.isDownloading(NAME));
        } finally {
            oldSource.release.countDown();
            newSource.release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ResponseBody body(String value) {
        return ResponseBody.create(null, value.getBytes(StandardCharsets.UTF_8));
    }

    private static Response response(int code, ResponseBody body) {
        return new Response.Builder().request(REQUEST).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body).build();
    }

    private interface Execute { Response run() throws IOException; }

    private static final class FakeCall {
        volatile boolean cancelled;
        int executions;
        final Call call;

        FakeCall(Execute execute) {
            call = (Call) Proxy.newProxyInstance(Call.class.getClassLoader(), new Class<?>[]{Call.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "execute": executions++; return execute.run();
                    case "cancel": cancelled = true; return null;
                    case "isCanceled": return cancelled;
                    case "isExecuted": return executions > 0;
                    case "request": return REQUEST;
                    case "timeout": return Timeout.NONE;
                    case "clone": return proxy;
                    case "tag": return null;
                    default: throw new AssertionError(method.getName());
                }
            });
        }
    }

    private static final class BlockingSource implements Source {
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final String prefix;
        final boolean fail;
        int reads;

        BlockingSource(String prefix, boolean fail) { this.prefix = prefix; this.fail = fail; }
        ResponseBody body() { return ResponseBody.create(null, 4, Okio.buffer(this)); }

        @Override public long read(Buffer sink, long byteCount) throws IOException {
            if (reads++ == 0) { sink.writeUtf8(prefix); return 3; }
            if (reads > 2) { return -1; }
            blocked.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) { throw new IOException("Test timed out"); }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            if (fail) { throw new IOException("Cancelled read"); }
            sink.writeUtf8("!");
            return 1;
        }
        @Override public Timeout timeout() { return Timeout.NONE; }
        @Override public void close() {}
    }

    private static final class QueuedExecutor implements Executor {
        final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable runnable) { queue.add(runnable); }
        void drain() { Runnable next; while ((next = queue.poll()) != null) { next.run(); } }
    }

    private static final class Events implements NooagramUpdateHelper.DownloadListener {
        final List<Object[]> failures = new ArrayList<>();
        final List<File> loaded = new ArrayList<>();
        final List<Long> progress = new ArrayList<>();
        @Override public void failed(int account, String name, int reason) { failures.add(new Object[]{account, name, reason}); }
        @Override public void loaded(int account, String name, File file) { loaded.add(file); }
        @Override public void progress(int account, String name, long bytes, long total) { progress.add(bytes); }
    }
}
