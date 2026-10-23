package org.telegram.utils.localization;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.R;

import java.util.HashMap;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class ProductBrandingLocalizationTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void bundledBrandingWinsByKeyAndResourceId() {
        Localization bundled = loadBundled(Locale.SIMPLIFIED_CHINESE);
        Localization staleCloud = localization(
                "N_Config", "Nagram设置",
                "NekoX", "Nagram",
                "NekoXPushService", "Nagram Push Service"
        );

        assertEquals("Nooagram设置", ProductBrandingLocalization.resolve(
                bundled, staleCloud, context, "N_Config", 0, null, true));
        assertEquals("Nooagram设置", ProductBrandingLocalization.resolve(
                bundled, staleCloud, context, null, R.string.N_Config, null, true));
        assertEquals("Nooagram", ProductBrandingLocalization.resolve(
                bundled, staleCloud, context, "NekoX", R.string.NekoX, null, true));
    }

    @Test
    public void bundledBrandingUsesTheActiveLanguage() {
        Localization english = loadBundled(Locale.ENGLISH);
        Localization chinese = loadBundled(Locale.SIMPLIFIED_CHINESE);

        assertEquals("Nooagram Settings", ProductBrandingLocalization.resolve(
                english, Localization.EMPTY, context, "N_Config", R.string.N_Config, null, true));
        assertEquals("Nooagram设置", ProductBrandingLocalization.resolve(
                chinese, Localization.EMPTY, context, "N_Config", R.string.N_Config, null, true));
        assertNotEquals(
                ProductBrandingLocalization.resolve(
                        english, Localization.EMPTY, context, "N_Config", R.string.N_Config, null, true),
                ProductBrandingLocalization.resolve(
                        chinese, Localization.EMPTY, context, "N_Config", R.string.N_Config, null, true)
        );
    }

    @Test
    public void allProductOwnedKeysRejectOldCloudBranding() {
        for (String tag : new String[]{"en", "zh-CN", "zh-TW", "pl-PL", "id-ID"}) {
            Localization bundled = loadBundled(Locale.forLanguageTag(tag));
            for (String key : new String[]{
                    "NekoX", "N_Config", "NekoSettings", "NekoXPushService",
                    "EnablePushAlert", "DisablePushAlert", "AppUpdateNekoX",
                    "CustomTitleHint", "PassQREncryption", "CloudConfigDesc",
                    "CloudConfigAutoSyncDesc", "NeedChangeNekoSettings",
                    "NekogramRunning", "PasscodeShowInSettings", "ResetSettingsAlert",
                    "UnknownNekoSettingsOption"
            }) {
                String expected = bundled.getByResName(key);
                assertNotNull(tag + ": " + key, expected);
                assertEquals(tag + ": " + key, expected, ProductBrandingLocalization.resolve(
                        bundled, localization(key, "Old Nagram branding"), context, key, 0, null, true));
            }
        }
    }

    @Test
    public void nonBrandCloudStringsKeepPriority() {
        Localization bundled = localization("OrdinaryCloudText", "Bundled text");
        Localization cloud = localization("OrdinaryCloudText", "Cloud text");

        assertEquals("Cloud text", ProductBrandingLocalization.resolve(
                bundled, cloud, context, "OrdinaryCloudText", 0, null, true));
        assertEquals("Bundled text", ProductBrandingLocalization.resolve(
                bundled, cloud, context, "OrdinaryCloudText", 0, null, false));
    }

    @Test
    public void genericLabelsKeepCloudTranslationPriority() {
        Localization bundled = localization("About", "About", "RemoveTitleEmoji", "Remove emoji in title");
        Localization cloud = localization("About", "Despre", "RemoveTitleEmoji", "Elimina emoji del titulo");

        assertEquals("Despre", ProductBrandingLocalization.resolve(
                bundled, cloud, context, "About", R.string.About, null, true));
        assertEquals("Elimina emoji del titulo", ProductBrandingLocalization.resolve(
                bundled, cloud, context, "RemoveTitleEmoji", R.string.RemoveTitleEmoji, null, true));
        assertEquals(0, ProductBrandingLocalization.getProductBrandStringRes("About", R.string.About));
        assertEquals(0, ProductBrandingLocalization.getProductBrandStringRes(
                "RemoveTitleEmoji", R.string.RemoveTitleEmoji));
    }

    @Test
    public void localizedBrandHintsUseTheCurrentProductName() {
        for (String tag : new String[]{"ar-SA", "uk-UA"}) {
            Localization bundled = loadBundled(Locale.forLanguageTag(tag));
            for (String key : new String[]{"CustomTitleHint", "PassQREncryption"}) {
                String value = bundled.getByResName(key);
                assertNotNull(tag + ": " + key, value);
                assertTrue(tag + ": " + key + " = " + value, value.contains("Nooagram"));
                assertFalse(tag + ": " + key + " = " + value, value.contains("Nagram"));
                assertFalse(tag + ": " + key + " = " + value, value.contains("\u041d\u0430\u0433\u0440\u0430\u043c"));
                assertFalse(tag + ": " + key + " = " + value, value.contains("\u0646\u0627\u063a\u0631\u0627\u0645"));
            }
        }
    }

    @Test
    public void upstreamAndThirdPartyLabelsAreNotPinnedAsProductBranding() {
        assertEquals(0, ProductBrandingLocalization.getProductBrandStringRes("Nekogram", 0));
        assertEquals(0, ProductBrandingLocalization.getProductBrandStringRes("NekoXUpdatesChannel", 0));
        assertEquals(0, ProductBrandingLocalization.getProductBrandStringRes("NekoXFaq", 0));
        assertNotEquals(0, ProductBrandingLocalization.getProductBrandStringRes("N_Config", 0));
    }

    @Test
    public void upstreamChannelUsesBundledLocaleTranslations() {
        String english = loadBundled(Locale.ENGLISH).getByResName("UpstreamChannel");
        assertNotNull(english);
        assertFalse(english.startsWith("LOC_ERR"));

        for (String tag : new String[]{
                "ar-SA", "bn-BD", "cs-CZ", "da-DK", "de-DE", "el-GR", "es-ES",
                "fa-IR", "fi-FI", "fr-FR", "hi-IN", "id-ID", "it-IT", "ja-JP",
                "ko-KR", "ms-MY", "nl-NL", "no-NO", "pl-PL", "pt-BR", "pt-PT",
                "ro-RO", "ru-RU", "sv-SE", "tr-TR", "uk-UA", "uz-UZ", "vi-VN",
                "zh-CN", "zh-HK", "zh-TW"
        }) {
            Localization bundled = loadBundled(Locale.forLanguageTag(tag));
            for (String key : new String[]{"UpstreamChannel", "UpstreamTipsChannel"}) {
                String value = bundled.getByResName(key);
                assertNotNull(tag + ": " + key, value);
                assertFalse(tag + ": " + key + " = " + value, value.startsWith("LOC_ERR"));
                assertNotEquals(tag + ": " + key + " fell back to English", english, value);
            }
        }
    }

    @Test
    public void arabicPushServiceUsesNooagramOverOldCloudBranding() {
        Localization bundled = loadBundled(Locale.forLanguageTag("ar-SA"));
        String expected = bundled.getByResName("NekoXPushService");

        assertNotNull(expected);
        assertTrue(expected.contains("Nooagram"));
        assertEquals(expected, ProductBrandingLocalization.resolve(
                bundled,
                localization("NekoXPushService",
                        "\u062e\u062f\u0645\u0629 \u062f\u0641\u0639 \u0646\u0627\u063a\u0631\u0627\u0645"),
                context,
                "NekoXPushService",
                R.string.NekoXPushService,
                null,
                true
        ));
    }

    private Localization loadBundled(Locale locale) {
        Localization.Builder builder = new Localization.Builder();
        BundledLocalization.addTo(builder, context, Locale.ENGLISH);
        BundledLocalization.addTo(builder, context, locale);
        return builder.build();
    }

    private static Localization localization(String... entries) {
        HashMap<String, String> values = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put(entries[i], entries[i + 1]);
        }
        return new Localization.Builder().addLocalization(values).build();
    }
}
