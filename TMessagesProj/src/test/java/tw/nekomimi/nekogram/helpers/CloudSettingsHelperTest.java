package tw.nekomimi.nekogram.helpers;

import android.content.SharedPreferences;
import android.os.Looper;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class CloudSettingsHelperTest {

    @Before
    public void clearSyncedTimestamp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        UserConfig.selectedAccount = 0;
        preferences().edit().clear().commit();
    }

    @Test
    public void allChunksAndMetadataCompleteBeforeSuccessAndUseCapturedAccount() throws Exception {
        CloudSettingsHelper helper = new CloudSettingsHelper();
        FakeCloudStorageHelper storage = new FakeCloudStorageHelper(1);
        CallbackResult result = new CallbackResult();
        String setting = "x".repeat(8001);

        helper.syncToCloud(storage, 1, setting, result);

        assertEquals("neko_settings_0", storage.singlePendingKey());
        storage.succeed("neko_settings_0");
        assertEquals("neko_settings_1", storage.singlePendingKey());
        storage.succeed("neko_settings_1");
        assertEquals("neko_settings_2", storage.singlePendingKey());
        storage.succeed("neko_settings_2");
        assertEquals("neko_settings", storage.singlePendingKey());
        assertFalse(result.success);
        storage.succeed("neko_settings");
        assertEquals("neko_settings_updated_at", storage.singlePendingKey());
        assertFalse(result.success);

        UserConfig.selectedAccount = 0;
        storage.succeed("neko_settings_updated_at");

        assertEquals(1, result.calls);
        assertTrue(result.success);
        assertTrue(cloudSyncedDate(helper).get(1, 0L) > 0);
        assertTrue(cloudSyncedDate(helper).indexOfKey(0) < 0);
        assertTrue(preferences().getLong("updated_at", 0L) > 0);
    }

    @Test
    public void chunkFailureEndsOnceWithoutMetadata() {
        CloudSettingsHelper helper = new CloudSettingsHelper();
        FakeCloudStorageHelper storage = new FakeCloudStorageHelper(0);
        CallbackResult result = new CallbackResult();

        helper.syncToCloud(storage, 0, "x", result);
        Utilities.Callback2<String, String> chunkCallback = storage.take("neko_settings_0");
        chunkCallback.run(null, "chunk failed");
        chunkCallback.run(null, "duplicate callback");

        assertEquals(1, result.calls);
        assertFalse(result.success);
        assertEquals("chunk failed", result.error);
        assertFalse(storage.requested("neko_settings"));
        assertEquals(0L, preferences().getLong("updated_at", 0L));
    }

    @Test
    public void zeroChunksStillCompletesAndCallsBackOnce() {
        CloudSettingsHelper helper = new CloudSettingsHelper();
        FakeCloudStorageHelper storage = new FakeCloudStorageHelper(0);
        CallbackResult result = new CallbackResult();

        helper.syncToCloud(storage, 0, "", result);

        assertEquals("neko_settings", storage.singlePendingKey());
        storage.succeed("neko_settings");
        storage.succeed("neko_settings_updated_at");

        assertEquals(1, result.calls);
        assertTrue(result.success);
    }

    @Test
    public void timestampFailureDoesNotCommitLocalSuccess() {
        CloudSettingsHelper helper = new CloudSettingsHelper();
        FakeCloudStorageHelper storage = new FakeCloudStorageHelper(0);
        CallbackResult result = new CallbackResult();

        helper.syncToCloud(storage, 0, "x", result);
        storage.succeed("neko_settings_0");
        storage.succeed("neko_settings");
        Utilities.Callback2<String, String> timestampCallback = storage.take("neko_settings_updated_at");
        timestampCallback.run(null, "timestamp failed");
        timestampCallback.run(null, "duplicate callback");

        assertEquals(1, result.calls);
        assertFalse(result.success);
        assertEquals("timestamp failed", result.error);
        assertEquals(0L, preferences().getLong("updated_at", 0L));
    }

    @Test
    public void synchronousCloudExceptionEndsOnce() {
        CloudSettingsHelper helper = new CloudSettingsHelper();
        FakeCloudStorageHelper storage = new FakeCloudStorageHelper(0);
        storage.throwOnKey = "neko_settings_0";
        CallbackResult result = new CallbackResult();

        helper.syncToCloud(storage, 0, "x", result);

        assertEquals(1, result.calls);
        assertFalse(result.success);
        assertTrue(result.error.contains("simulated"));
        assertFalse(storage.hasPending());
    }

    @Test
    public void unicodeAndEscapedChunksFitActualRequestAndReassembleLosslessly() {
        String unit = "\u4e2d\u6587\ud83d\ude00\ud840\udc00\\\"\n\t\u0001";
        String setting = unit.repeat(2000);

        ArrayList<String> chunks = CloudSettingsHelper.splitSettings(setting);

        assertTrue(chunks.size() > 1);
        StringBuilder restored = new StringBuilder(setting.length());
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            assertFalse(chunk.isEmpty());
            assertFalse(hasUnpairedSurrogate(chunk));
            String requestData = CloudStorageHelper.buildSetItemPayload("neko_settings_" + i, chunk);
            assertTrue(CloudStorageHelper.utf8Length(requestData) <= CloudStorageHelper.MAX_REQUEST_DATA_BYTES);
            restored.append(chunk);
        }
        assertEquals(setting, restored.toString());
    }

    @Test
    public void uploadedUnicodeChunksPreserveCountAndIndices() {
        CloudSettingsHelper helper = new CloudSettingsHelper();
        FakeCloudStorageHelper storage = new FakeCloudStorageHelper(0);
        CallbackResult result = new CallbackResult();
        String setting = ("{\"text\":\"\u4e2d\u6587 \ud83d\ude00 \\\\ \\\" \\n\"}").repeat(600);

        helper.syncToCloud(storage, 0, setting, result);
        int uploadedRequests = 0;
        while (storage.hasPending()) {
            storage.succeed(storage.singlePendingKey());
            uploadedRequests++;
            if (uploadedRequests > 10000) {
                throw new AssertionError("Cloud upload did not terminate");
            }
        }

        assertTrue(result.success);
        int count = Integer.parseInt(storage.value("neko_settings"));
        assertTrue(count > 1);
        StringBuilder restored = new StringBuilder(setting.length());
        for (int i = 0; i < count; i++) {
            restored.append(storage.value("neko_settings_" + i));
        }
        assertEquals(setting, restored.toString());
    }

    @Test
    public void autoSyncCoalescesNotificationsAndNeverRunsConcurrently() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        FakeAutoSyncHelper helper = new FakeAutoSyncHelper();

        helper.doAutoSync();
        helper.doAutoSync();
        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1199, TimeUnit.MILLISECONDS);
        assertEquals(0, helper.starts);

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        assertEquals(1, helper.starts);
        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        assertEquals(1, helper.starts);

        helper.complete(true, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        assertEquals(2, helper.starts);
    }

    @Test
    public void disablingAutoSyncDropsPendingWork() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        FakeAutoSyncHelper helper = new FakeAutoSyncHelper();

        helper.doAutoSync();
        helper.updateAutoSync(false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);

        assertEquals(0, helper.starts);
        assertFalse(preferences().getBoolean("auto_sync", true));
    }

    @Test
    public void unavailableAccountOrNetworkDoesNotSendRequest() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        FakeAutoSyncHelper helper = new FakeAutoSyncHelper();
        helper.available = false;

        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);

        assertEquals(0, helper.starts);
    }

    @Test
    public void automaticFailureRetriesAreLimited() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        FakeAutoSyncHelper helper = new FakeAutoSyncHelper();

        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        assertEquals(1, helper.starts);
        helper.complete(false, "temporary failure");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5000, TimeUnit.MILLISECONDS);
        assertEquals(2, helper.starts);
        helper.complete(false, "temporary failure");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(30000, TimeUnit.MILLISECONDS);
        assertEquals(3, helper.starts);
        helper.complete(false, "temporary failure");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(120000, TimeUnit.MILLISECONDS);
        assertEquals(3, helper.starts);
    }

    @Test
    public void manualSyncWaitsForAutomaticUpload() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        SerialSyncHelper helper = new SerialSyncHelper();
        CallbackResult manualResult = new CallbackResult();

        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        assertEquals(1, helper.starts);

        helper.requestManualSync(manualResult);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        assertEquals(1, helper.starts);

        helper.complete(true, null);
        assertEquals(2, helper.starts);
        assertEquals(0, manualResult.calls);

        helper.complete(true, null);
        assertEquals(1, manualResult.calls);
        assertTrue(manualResult.success);
    }

    @Test
    public void queuedManualSyncUsesAccountCapturedAtEnqueue() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        UserConfig.selectedAccount = 1;
        SerialSyncHelper helper = new SerialSyncHelper();
        CallbackResult manualResult = new CallbackResult();

        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        helper.requestManualSync(manualResult);

        UserConfig.selectedAccount = 0;
        helper.complete(true, null);

        assertEquals(1, helper.manualStarts);
        assertEquals(1, helper.lastManualAccount);
        helper.complete(true, null);
        assertTrue(manualResult.success);
    }

    @Test
    public void failedManualSyncPreservesAutomaticRetry() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        SerialSyncHelper helper = new SerialSyncHelper();
        CallbackResult manualResult = new CallbackResult();

        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        helper.requestManualSync(manualResult);
        helper.complete(false, "automatic failure");

        assertEquals(1, helper.manualStarts);
        helper.complete(false, "manual failure");
        assertFalse(manualResult.success);

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(4999, TimeUnit.MILLISECONDS);
        assertEquals(1, helper.autoStarts);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        assertEquals(2, helper.autoStarts);
    }

    @Test
    public void retryDueDuringManualSyncRunsAfterManualFailure() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        SerialSyncHelper helper = new SerialSyncHelper();
        CallbackResult manualResult = new CallbackResult();

        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        helper.requestManualSync(manualResult);
        helper.complete(false, "automatic failure");
        assertEquals(1, helper.manualStarts);

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5000, TimeUnit.MILLISECONDS);
        assertEquals(1, helper.autoStarts);
        helper.complete(false, "manual failure");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        assertEquals(2, helper.autoStarts);
    }

    @Test
    public void finalScheduledRetrySurvivesFailedManualSync() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        SerialSyncHelper helper = new SerialSyncHelper();
        CallbackResult manualResult = new CallbackResult();

        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        helper.complete(false, "initial automatic failure");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5000, TimeUnit.MILLISECONDS);
        assertEquals(2, helper.autoStarts);

        helper.requestManualSync(manualResult);
        helper.complete(false, "final automatic retry failure");
        assertEquals(1, helper.manualStarts);
        helper.complete(false, "manual failure");

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(29999, TimeUnit.MILLISECONDS);
        assertEquals(2, helper.autoStarts);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        assertEquals(3, helper.autoStarts);
    }

    @Test
    public void successfulManualSyncCancelsSatisfiedAutomaticRetry() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        SerialSyncHelper helper = new SerialSyncHelper();
        CallbackResult manualResult = new CallbackResult();

        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        helper.requestManualSync(manualResult);
        helper.complete(false, "automatic failure");
        assertEquals(1, helper.manualStarts);

        helper.complete(true, null);
        assertTrue(manualResult.success);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(120000, TimeUnit.MILLISECONDS);
        assertEquals(1, helper.autoStarts);
    }

    @Test
    public void changesDuringManualSyncRemainPending() {
        preferences().edit().putBoolean("auto_sync", true).commit();
        SerialSyncHelper helper = new SerialSyncHelper();
        CallbackResult manualResult = new CallbackResult();

        helper.requestManualSync(manualResult);
        assertEquals(1, helper.manualStarts);
        helper.doAutoSync();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        assertEquals(0, helper.autoStarts);

        helper.complete(true, null);
        assertTrue(manualResult.success);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1200, TimeUnit.MILLISECONDS);
        assertEquals(1, helper.autoStarts);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static SparseArray<Long> cloudSyncedDate(CloudSettingsHelper helper) throws Exception {
        Field field = CloudSettingsHelper.class.getDeclaredField("cloudSyncedDate");
        field.setAccessible(true);
        return (SparseArray<Long>) field.get(helper);
    }

    private static SharedPreferences preferences() {
        try {
            Field field = CloudSettingsHelper.class.getDeclaredField("preferences");
            field.setAccessible(true);
            return (SharedPreferences) field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class CallbackResult implements Utilities.Callback2<Boolean, String> {
        private boolean success;
        private String error;
        private int calls;

        @Override
        public void run(Boolean first, String second) {
            calls++;
            success = first;
            error = second;
        }
    }

    private static final class FakeCloudStorageHelper extends CloudStorageHelper {
        private final LinkedHashMap<String, Utilities.Callback2<String, String>> pending = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        private String throwOnKey;

        private FakeCloudStorageHelper(int account) {
            super(account);
        }

        @Override
        public void setItem(String key, String value, Utilities.Callback2<String, String> callback) {
            if (key.equals(throwOnKey)) {
                throw new IllegalStateException("simulated synchronous failure");
            }
            values.put(key, value);
            pending.put(key, callback);
        }

        private void succeed(String key) {
            take(key).run("ok", null);
        }

        private void fail(String key, String error) {
            take(key).run(null, error);
        }

        private Utilities.Callback2<String, String> take(String key) {
            Utilities.Callback2<String, String> callback = pending.remove(key);
            if (callback == null) {
                throw new AssertionError("No pending callback for " + key);
            }
            return callback;
        }

        private boolean requested(String key) {
            return pending.containsKey(key);
        }

        private boolean hasPending() {
            return !pending.isEmpty();
        }

        private String value(String key) {
            return values.get(key);
        }

        private String singlePendingKey() {
            if (pending.size() != 1) {
                throw new AssertionError("Expected one pending request, got " + pending.keySet());
            }
            for (Map.Entry<String, Utilities.Callback2<String, String>> entry : pending.entrySet()) {
                return entry.getKey();
            }
            throw new AssertionError();
        }
    }

    private static final class FakeAutoSyncHelper extends CloudSettingsHelper {
        private int starts;
        private boolean available = true;
        private Utilities.Callback2<Boolean, String> pending;

        @Override
        boolean canAutoSyncNow() {
            return available;
        }

        @Override
        void performAutoSync(Utilities.Callback2<Boolean, String> callback) {
            starts++;
            pending = callback;
        }

        @Override
        void notifyAutoSyncFailure(String error) {
        }

        private void complete(boolean success, String error) {
            assertNotNull(pending);
            Utilities.Callback2<Boolean, String> callback = pending;
            pending = null;
            callback.run(success, error);
        }
    }

    private static final class SerialSyncHelper extends CloudSettingsHelper {
        private int starts;
        private int autoStarts;
        private int manualStarts;
        private int lastManualAccount = -1;
        private Utilities.Callback2<Boolean, String> pending;

        @Override
        boolean canAutoSyncNow() {
            return true;
        }

        @Override
        void performAutoSync(Utilities.Callback2<Boolean, String> callback) {
            starts++;
            autoStarts++;
            pending = callback;
        }

        @Override
        void syncToCloud(int account, Utilities.Callback2<Boolean, String> callback) {
            starts++;
            manualStarts++;
            lastManualAccount = account;
            pending = callback;
        }

        @Override
        void notifyAutoSyncFailure(String error) {
        }

        private void complete(boolean success, String error) {
            assertNotNull(pending);
            Utilities.Callback2<Boolean, String> callback = pending;
            pending = null;
            callback.run(success, error);
        }
    }
}
