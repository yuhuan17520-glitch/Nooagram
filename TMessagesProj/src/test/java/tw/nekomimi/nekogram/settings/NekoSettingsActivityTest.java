package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import org.telegram.messenger.ApplicationLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class NekoSettingsActivityTest {

    @Before
    public void clearPreferences() {
        for (String name : new String[]{
                "mainconfig", "nkmrcfg", "ayughostconfig", "ayughostexclusionsconfig"
        }) {
            preferences(name).edit().clear().commit();
        }
    }

    @Test
    public void backupIncludesBothGhostPreferenceFiles() throws Exception {
        preferences("ayughostconfig")
                .edit()
                .putBoolean("useGlobalConfig", false)
                .putLong("ghostReadDelay", 1234L)
                .commit();
        preferences("ayughostexclusionsconfig")
                .edit()
                .putInt("ghostModeReadException_42", 2)
                .commit();

        JSONObject backup = new JSONObject(NekoSettingsActivity.backupSettingsJson(0));

        JSONObject ghost = backup.getJSONObject("ayughostconfig");
        assertFalse(ghost.getBoolean("useGlobalConfig"));
        assertEquals(1234L, ghost.getLong("ghostReadDelay_long"));

        JSONObject exclusions = backup.getJSONObject("ayughostexclusionsconfig");
        assertEquals(2, exclusions.getInt("ghostModeReadException_42"));
    }

    @Test
    public void malformedLaterPreferenceSetDoesNotCommitEarlierSet() {
        SharedPreferences main = preferences("mainconfig");
        main.edit().putString("existing", "keep").commit();
        JsonObject config = JsonParser.parseString(
                "{\"mainconfig\":{\"newValue\":true},\"nkmrcfg\":{\"bad\":{\"nested\":true}}}"
        ).getAsJsonObject();

        assertThrows(JSONException.class, () -> NekoSettingsActivity.importSettings(config));

        assertTrue(main.contains("existing"));
        assertFalse(main.contains("newValue"));
    }

    @Test
    public void importPreservesTypedSuffixFormat() throws Exception {
        JsonObject config = JsonParser.parseString(
                "{\"mainconfig\":{"
                        + "\"enabled\":true,"
                        + "\"count\":7,"
                        + "\"large\":9223372036854775807_long,"
                        + "\"ratio\":0.25_float,"
                        + "\"label\":\"value\""
                        + "}}"
        ).getAsJsonObject();

        NekoSettingsActivity.importSettings(config);

        SharedPreferences main = preferences("mainconfig");
        assertTrue(main.getBoolean("enabled", false));
        assertEquals(7, main.getInt("count", 0));
        assertEquals(Long.MAX_VALUE, main.getLong("large", 0L));
        assertEquals(0.25f, main.getFloat("ratio", 0f), 0f);
        assertEquals("value", main.getString("label", null));
    }

    @Test
    public void invalidNumericTypeRejectsWholeImportBeforeCommit() {
        SharedPreferences main = preferences("mainconfig");
        JsonObject config = JsonParser.parseString(
                "{\"mainconfig\":{\"newValue\":true},"
                        + "\"nkmrcfg\":{\"bad\":2147483648}}"
        ).getAsJsonObject();

        assertThrows(JSONException.class, () -> NekoSettingsActivity.importSettings(config));

        assertFalse(main.contains("newValue"));
    }

    private static SharedPreferences preferences(String name) {
        return ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
