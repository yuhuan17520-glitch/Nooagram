package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class CloudSettingsHelperTest {

    @Before
    public void clearSyncedTimestamp() {
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
        storage.fail("neko_settings_0", "chunk failed");
        storage.fail("neko_settings_0", "duplicate callback");

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
        storage.fail("neko_settings_updated_at", "timestamp failed");
        storage.fail("neko_settings_updated_at", "duplicate callback");

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

    @SuppressWarnings("unchecked")
    private static SparseArray<Long> cloudSyncedDate(CloudSettingsHelper helper) throws Exception {
        Field field = CloudSettingsHelper.class.getDeclaredField("cloudSyncedDate");
        field.setAccessible(true);
        return (SparseArray<Long>) field.get(helper);
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nekocloud", Context.MODE_PRIVATE);
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
        private String throwOnKey;

        private FakeCloudStorageHelper(int account) {
            super(account);
        }

        @Override
        public void setItem(String key, String value, Utilities.Callback2<String, String> callback) {
            if (key.equals(throwOnKey)) {
                throw new IllegalStateException("simulated synchronous failure");
            }
            pending.put(key, callback);
        }

        private void succeed(String key) {
            callback(key).run("ok", null);
        }

        private void fail(String key, String error) {
            callback(key).run(null, error);
        }

        private Utilities.Callback2<String, String> callback(String key) {
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
}
