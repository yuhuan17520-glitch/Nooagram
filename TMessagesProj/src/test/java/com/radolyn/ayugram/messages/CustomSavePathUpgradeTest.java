package com.radolyn.ayugram.messages;

import android.content.SharedPreferences;

import com.radolyn.ayugram.AyuOptions;
import com.radolyn.ayugram.utils.AyuMediaFolders;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.lang.reflect.Field;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NkmrConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class CustomSavePathUpgradeTest {
    private SharedPreferences user;
    private SharedPreferences defaults;

    @Before
    public void prepareActualPreferenceFilesBeforeConfigLoading() throws Exception {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        // Robolectric replaces the application between tests, but the Kotlin
        // singleton retains its original prefs. Seed the real backing stores.
        user = backingPreferences("userPreferences");
        defaults = backingPreferences("defaultPreferences");
        assertTrue(user.edit().clear().putBoolean("enableCustomSavePath", true).commit());
        assertTrue(defaults.edit().clear().commit());
    }

    @After
    public void resetConfiguration() {
        NkmrConfig.clear();
        NekoConfig.loadConfig(true);
    }

    @Test
    public void oldDefaultWithoutUserOverrideKeepsHistoricalFolders() {
        assertTrue(defaults.edit().putString("customSavePath", "Nagram").commit());
        assertStableFolders("Nagram", false);
    }

    @Test
    public void candidateDefaultWithoutUserOverrideReturnsToHistoricalFolders() {
        assertTrue(defaults.edit().putString("customSavePath", "Nooagram").commit());
        assertStableFolders("Nagram", false);
    }

    @Test
    public void explicitCustomDirectorySurvivesDefaultReregistration() {
        assertTrue(defaults.edit().putString("customSavePath", "Nooagram").commit());
        assertTrue(user.edit().putString("customSavePath", "My Archive").commit());
        assertStableFolders("My Archive", true);
    }

    @Test
    public void explicitlyChosenNooagramDirectoryIsNotRewritten() {
        assertTrue(defaults.edit().putString("customSavePath", "Nooagram").commit());
        assertTrue(user.edit().putString("customSavePath", "Nooagram").commit());
        assertStableFolders("Nooagram", true);
    }

    @Test
    public void explicitHistoricalDirectoryRemainsEffectiveAfterCompaction() {
        assertTrue(user.edit().putString("customSavePath", "Nagram").commit());
        assertStableFolders("Nagram", false);
    }

    @Test
    public void freshInstallUsesHistoricalDefaultWithoutCreatingAnOverride() {
        assertStableFolders("Nagram", false);
    }

    @Test
    public void explicitEmptyValueKeepsHistoricalFallbackAndStoredValue() {
        assertTrue(user.edit().putString("customSavePath", "").commit());
        NekoConfig.loadConfig(true);
        assertEquals("", AyuOptions.customSavePath.String());
        assertEquals("", user.getString("customSavePath", null));
        assertEquals("Nagram", AyuOptions.getCustomSavePath());
        assertEquals("Nagram", AyuMediaFolders.getFolderName(null));
    }

    @Test
    public void disabledCustomFolderKeepsPrivateAttachmentStorage() {
        assertTrue(user.edit().putBoolean("enableCustomSavePath", false).commit());
        NekoConfig.loadConfig(true);
        assertEquals("", AyuOptions.getCustomSavePath());
        File privateFiles = new File("private-files");
        assertEquals(new File(privateFiles, "Saved Attachments"),
                AyuAttachmentPaths.defaultParent(false, AyuOptions.getCustomSavePath(),
                        new File("Download"), privateFiles, privateFiles));
    }

    private void assertStableFolders(String expected, boolean hasOverride) {
        // Exercise the real merged preferences, config loading and compaction,
        // including a second startup after the new default has been registered.
        for (int startup = 0; startup < 2; startup++) {
            NekoConfig.loadConfig(true);
            NkmrConfig.compact();
            assertEquals(expected, NekoConfig.customSavePath.String());
            assertEquals(expected, AyuOptions.customSavePath.String());
            assertEquals(expected, AyuOptions.getCustomSavePath());
            assertEquals(expected, AyuMediaFolders.getFolderName(null));
            File downloads = new File("Download");
            File privateFiles = new File("private-files");
            assertEquals(new File(new File(downloads, expected), "Saved Attachments"),
                    AyuAttachmentPaths.defaultParent(true, AyuOptions.getCustomSavePath(),
                            downloads, privateFiles, privateFiles));
            assertEquals("Nagram", defaults.getString("customSavePath", null));
            if (hasOverride) {
                assertEquals(expected, user.getString("customSavePath", null));
            } else {
                assertFalse(user.contains("customSavePath"));
            }
        }
    }

    private static SharedPreferences backingPreferences(String fieldName) throws Exception {
        Field field = NkmrConfig.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (SharedPreferences) field.get(null);
    }
}
