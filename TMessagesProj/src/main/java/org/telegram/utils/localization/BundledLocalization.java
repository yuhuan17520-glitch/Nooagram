package org.telegram.utils.localization;

import android.content.Context;

import org.telegram.localization.NamespaceLocalizationUtils;

import java.util.Locale;

/** Extra string namespaces used by both the English fallback and active locale. */
public final class BundledLocalization {
    private BundledLocalization() {}

    public static void addTo(Localization.Builder builder, Context context, Locale locale) {
        for (String asset : new String[] {
                NamespaceLocalizationUtils.getLocalizationAssetNa(locale),
                NamespaceLocalizationUtils.getLocalizationAssetNeko(locale),
                NamespaceLocalizationUtils.getLocalizationAssetNekox(locale),
                NamespaceLocalizationUtils.getLocalizationAssetAyu(locale)
        }) {
            if (asset != null) {
                builder.addResLocalization(context, asset);
            }
        }
    }
}
