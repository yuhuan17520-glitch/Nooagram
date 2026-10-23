package org.telegram.utils.localization;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.telegram.messenger.R;

public final class ProductBrandingLocalization {
    private ProductBrandingLocalization() {
    }

    @Nullable
    public static String resolve(
            Localization bundled,
            Localization cloud,
            Context context,
            String key,
            @StringRes int stringRes,
            String fallback,
            boolean useCloud
    ) {
        final int productBrandRes = getProductBrandStringRes(key, stringRes);
        if (productBrandRes != 0) {
            String value = bundled.getByResNameOrResId(context, key, stringRes);
            if (value != null) {
                return value;
            }
            return context != null ? context.getString(productBrandRes) : null;
        }

        if (useCloud) {
            String value = cloud.getByResNameOrResId(context, key, stringRes);
            if (value != null) {
                return value;
            }
            value = cloud.getByResName(fallback);
            if (value != null) {
                return value;
            }
        }

        String value = bundled.getByResNameOrResId(context, key, stringRes);
        if (value != null) {
            return value;
        }
        return bundled.getByResName(fallback);
    }

    @StringRes
    static int getProductBrandStringRes(String key, @StringRes int stringRes) {
        if (stringRes == R.string.NekoX
                || stringRes == R.string.N_Config
                || stringRes == R.string.NekoSettings
                || stringRes == R.string.NekoXPushService
                || stringRes == R.string.EnablePushAlert
                || stringRes == R.string.DisablePushAlert
                || stringRes == R.string.AppUpdateNekoX
                || stringRes == R.string.CustomTitleHint
                || stringRes == R.string.PassQREncryption
                || stringRes == R.string.CloudConfigDesc
                || stringRes == R.string.CloudConfigAutoSyncDesc
                || stringRes == R.string.NeedChangeNekoSettings
                || stringRes == R.string.NekogramRunning
                || stringRes == R.string.PasscodeShowInSettings
                || stringRes == R.string.ResetSettingsAlert
                || stringRes == R.string.UnknownNekoSettingsOption) {
            return stringRes;
        }
        if (key == null) {
            return 0;
        }
        switch (key) {
            case "NekoX":
                return R.string.NekoX;
            case "N_Config":
                return R.string.N_Config;
            case "NekoSettings":
                return R.string.NekoSettings;
            case "NekoXPushService":
                return R.string.NekoXPushService;
            case "EnablePushAlert":
                return R.string.EnablePushAlert;
            case "DisablePushAlert":
                return R.string.DisablePushAlert;
            case "AppUpdateNekoX":
                return R.string.AppUpdateNekoX;
            case "CustomTitleHint":
                return R.string.CustomTitleHint;
            case "PassQREncryption":
                return R.string.PassQREncryption;
            case "CloudConfigDesc":
                return R.string.CloudConfigDesc;
            case "CloudConfigAutoSyncDesc":
                return R.string.CloudConfigAutoSyncDesc;
            case "NeedChangeNekoSettings":
                return R.string.NeedChangeNekoSettings;
            case "NekogramRunning":
                return R.string.NekogramRunning;
            case "PasscodeShowInSettings":
                return R.string.PasscodeShowInSettings;
            case "ResetSettingsAlert":
                return R.string.ResetSettingsAlert;
            case "UnknownNekoSettingsOption":
                return R.string.UnknownNekoSettingsOption;
        }
        return 0;
    }
}
