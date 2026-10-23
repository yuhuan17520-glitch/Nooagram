package com.radolyn.ayugram.localization;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.localization.NamespaceLocalizationUtils;
import org.telegram.tgnet.SerializedData;
import org.telegram.utils.localization.BundledLocalization;
import org.telegram.utils.localization.Localization;

import java.util.Locale;
import java.io.InputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class AyuLocalizationTest {
    private Context context;
    private static final String[] SETTINGS_KEYS = {
            "AyuMoments", "GhostMode", "RegexFilters", "AyuSpySettings", "NooagramPinnedHider",
            "AyuMomentsCustomHeader", "AyuMomentsPreviewMessage", "TranslucentDeletedMessages",
            "DeletedMarkText", "CustomDeletedMark", "localPremium", "DisableAds", "enableCustomSavePath",
            "SaveToChatSubfolder", "GhostModeStatusIndicator", "NooagramHighRefreshRate", "NooagramHighRefreshRateInfo",
            "EnableSaveDeletedMessages", "EnableSaveEditsHistory", "SaveDeletedMessageForBot",
            "SaveDeletedMessageForBotUser", "SaveLocalLastSeen", "SaveReadDate", "RegexFiltersMaskMessages"
    };

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext = context;
    }

    private Localization load(Locale locale) {
        Localization.Builder builder = new Localization.Builder();
        BundledLocalization.addTo(builder, context, Locale.ENGLISH);
        BundledLocalization.addTo(builder, context, locale);
        return builder.build();
    }

    @Test public void everySettingsTitleResolvesByDynamicKeyInEveryBundledAyuLanguage() {
        for (String tag : new String[]{"en", "zh-CN", "zh-TW", "ja-JP", "ko", "ru-RU", "uk", "fr-FR"}) {
            Localization strings = load(Locale.forLanguageTag(tag));
            for (String key : SETTINGS_KEYS) {
                String value = strings.getByResName(key);
                assertNotNull(tag + ": " + key, value);
                assertFalse(tag + ": " + key, value.isEmpty() || value.startsWith("LOC_ERR"));
            }
        }
    }

    @Test public void simplifiedChineseAndResourceIdLookupsReturnActualTranslations() {
        Localization strings = load(Locale.SIMPLIFIED_CHINESE);
        assertEquals("间谍设置", strings.getByResName("AyuSpySettings"));
        assertEquals("置顶隐藏", strings.getByResName("NooagramPinnedHider"));
        assertEquals("自定义", strings.getByResId(context, R.string.AyuMomentsCustomHeader));
        assertEquals("尽人事，听天命", strings.getByResId(context, R.string.AyuMomentsPreviewMessage));
        assertEquals(context.getResources().getString(R.string.TranslucentDeletedMessages), load(Locale.ENGLISH).getByResName("TranslucentDeletedMessages"));
    }

    @Test public void languageChangesDoNotKeepPreviousChineseAndMissingTranslationsFallBackToEnglish() {
        assertEquals("置顶隐藏", load(Locale.SIMPLIFIED_CHINESE).getByResName("NooagramPinnedHider"));
        assertEquals("Pinned Hider", load(Locale.ENGLISH).getByResName("NooagramPinnedHider"));
        assertEquals("Pinned Hider", load(Locale.FRENCH).getByResName("NooagramPinnedHider"));
        assertNotEquals("间谍设置", load(Locale.TRADITIONAL_CHINESE).getByResName("AyuSpySettings"));
    }

    @Test public void everyPackagedAyuStringIsAvailableThroughTheRuntimeLoader() throws Exception {
        for (String tag : new String[]{"en", "zh-CN", "zh-TW", "ja-JP", "ko", "ru-RU", "uk"}) {
            Locale locale = Locale.forLanguageTag(tag);
            Localization strings = load(locale);
            String asset = NamespaceLocalizationUtils.getLocalizationAssetAyu(locale);
            try (InputStream stream = context.getAssets().open(asset)) {
                SerializedData data = new SerializedData(stream);
                int count = data.readInt32(true);
                assertTrue("Empty Ayu language pack: " + tag, count > 25);
                for (int i = 0; i < count; i++) {
                    int hash = data.readInt32(true);
                    String value = data.readString(true);
                    assertEquals(tag + " string hash " + hash, value, strings.get(hash));
                }
            }
        }
    }
}
