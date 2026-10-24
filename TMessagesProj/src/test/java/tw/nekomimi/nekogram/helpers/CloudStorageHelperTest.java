package tw.nekomimi.nekogram.helpers;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class CloudStorageHelperTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
    }

    @Test
    public void oversizedSaveRequestFailsWithoutSending() {
        CloudStorageHelper helper = new CloudStorageHelper(0);
        CallbackResult result = new CallbackResult();

        helper.setItem("neko_settings_0", "\u4e2d".repeat(2000), result);

        assertEquals(1, result.calls);
        assertFalse(result.success);
        assertEquals(CloudStorageHelper.ERROR_DATA_TOO_LONG, result.error);
    }

    @Test
    public void requestBudgetCountsUtf8AndJsonEscapes() {
        String value = "\u4e2d\u6587\ud83d\ude00\\\"\n\t\u0001<>&=";
        String payload = CloudStorageHelper.buildSetItemPayload("neko_settings_0", value);

        assertTrue(payload.contains("\\n"));
        assertTrue(payload.contains("\\t"));
        assertTrue(CloudStorageHelper.utf8Length(payload) > value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(CloudStorageHelper.utf8Length(payload) <= CloudStorageHelper.MAX_REQUEST_DATA_BYTES);
    }

    @Test
    public void writeResponseUsesActualObjectOrBooleanContract() {
        assertNull(CloudStorageHelper.validateWriteResponse("{}"));
        assertNull(CloudStorageHelper.validateWriteResponse("true"));

        assertEquals(CloudStorageHelper.ERROR_EMPTY_RESPONSE, CloudStorageHelper.validateWriteResponse(null));
        assertEquals(CloudStorageHelper.ERROR_EMPTY_RESPONSE, CloudStorageHelper.validateWriteResponse(" "));
        assertEquals(CloudStorageHelper.ERROR_INVALID_RESPONSE, CloudStorageHelper.validateWriteResponse("not json"));
        assertEquals(CloudStorageHelper.ERROR_INVALID_RESPONSE, CloudStorageHelper.validateWriteResponse("false"));
        assertEquals(CloudStorageHelper.ERROR_INVALID_RESPONSE, CloudStorageHelper.validateWriteResponse("\"OK\""));
        assertEquals(CloudStorageHelper.ERROR_INVALID_RESPONSE, CloudStorageHelper.validateWriteResponse("{\"success\":true}"));
    }

    @Test
    public void businessErrorsAreNotTreatedAsSuccess() {
        assertEquals("data too long", CloudStorageHelper.validateWriteResponse("{\"error\":\"data too long\"}"));
        assertEquals("busy", CloudStorageHelper.validateWriteResponse("{\"ok\":false,\"error\":\"busy\"}"));
        assertEquals("denied", CloudStorageHelper.validateWriteResponse("{\"error\":{\"message\":\"denied\"}}"));
    }

    @Test
    public void readResponsesUseTheirExpectedJsonShapes() {
        HashMap<String, String> values = CloudStorageHelper.parseItemsResponse("{\"neko_settings_0\":\"\u4e2d\ud83d\ude00\"}");
        assertEquals("\u4e2d\ud83d\ude00", values.get("neko_settings_0"));

        String[] keys = CloudStorageHelper.parseKeysResponse("[\"neko_settings\",\"neko_settings_0\"]");
        assertEquals(2, keys.length);
        assertEquals("neko_settings_0", keys[1]);

        assertInvalid(() -> CloudStorageHelper.parseItemsResponse(null));
        assertInvalid(() -> CloudStorageHelper.parseItemsResponse("[]"));
        assertInvalid(() -> CloudStorageHelper.parseItemsResponse("{\"error\":\"denied\"}"));
        assertInvalid(() -> CloudStorageHelper.parseKeysResponse("{}"));
        assertInvalid(() -> CloudStorageHelper.parseKeysResponse("not json"));
    }

    private static void assertInvalid(Runnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    private static final class CallbackResult implements Utilities.Callback2<String, String> {
        private boolean success;
        private String error;
        private int calls;

        @Override
        public void run(String first, String second) {
            calls++;
            success = second == null;
            error = second;
        }
    }
}
