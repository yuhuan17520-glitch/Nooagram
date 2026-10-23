package com.radolyn.ayugram;
import android.content.SharedPreferences;
import com.radolyn.ayugram.config.ConfigItem;
import com.radolyn.ayugram.controllers.AyuGhostController;
import org.telegram.messenger.UserConfig;
import tw.nekomimi.nekogram.NekoConfig;
import static com.radolyn.ayugram.config.ConfigItem.*;
public final class AyuOptions {
    public static final Object sync=NekoConfig.sync;
    public static SharedPreferences getPreferences() { return NekoConfig.preferences; }
    private static ConfigItem addConfig(String key,int type,Object value) {
        // Read through for Ayu-only keys, including writes made by cloud settings imports.
        ConfigItem item=new ConfigItem(key,type,value) {
            @Override public boolean Bool() { return getPreferences().getBoolean(key, (Boolean) defaultValue); }
            @Override public int Int() { return getPreferences().getInt(key, (Integer) defaultValue); }
            @Override public String String() {
                return type == configTypeString
                        ? getPreferences().getString(key, (String) defaultValue)
                        : super.String();
            }
        };
        SharedPreferences p=getPreferences();
        switch(type) {
            case configTypeBool: item.value=p.getBoolean(key,(Boolean)value);break;
            case configTypeInt: item.value=p.getInt(key,(Integer)value);break;
            case configTypeString: item.value=p.getString(key,(String)value);break;
            default: throw new IllegalArgumentException(key);
        }
        return item;
    }
    public static ConfigItem disableAds = addConfig("DisableAds", configTypeBool, false);
    public static ConfigItem disableVibration = upstream(NekoConfig.disableVibration);
    public static ConfigItem enableCustomSavePath = addConfig("enableCustomSavePath", configTypeBool, false);
    public static ConfigItem customSavePath = upstream(NekoConfig.customSavePath);
    public static ConfigItem ignoreBlocked = upstream(NekoConfig.ignoreBlocked);
    public static ConfigItem localPremium = upstream(NekoConfig.localPremium);
    public static ConfigItem nooagramHighRefreshRate = addConfig("NooagramHighRefreshRate", configTypeBool, false);
    public static ConfigItem showGhostModeStatus = addConfig("showGhostModeStatus", configTypeBool, false);
    public static ConfigItem translateToLang = upstream(NekoConfig.translateToLang);
    public static ConfigItem useSystemEmoji = upstream(NekoConfig.useSystemEmoji);
    // Share the upstream in-memory value as well as its preference key. Two cached
    // ConfigItems for the same key otherwise disagree until the app restarts.
    private static ConfigItem upstream(tw.nekomimi.nekogram.config.ConfigItem delegate) {
        return new ConfigItem(delegate.getKey(), delegate.type, delegate.defaultValue) {
            @Override public boolean Bool() { return delegate.Bool(); }
            @Override public int Int() { return delegate.Int(); }
            @Override public String String() { return delegate.String(); }
            @Override public void setConfigBool(boolean v) { delegate.setConfigBool(v); }
            @Override public boolean toggleConfigBool() { return delegate.toggleConfigBool(); }
            @Override public void setConfigInt(int v) { delegate.setConfigInt(v); }
            @Override public void setConfigString(String v) { delegate.setConfigString(v); }
            @Override public void changed(Object v) { delegate.changed(v); }
            @Override public void saveConfig() { delegate.saveConfig(); }
        };
    }
    public static String getCustomSavePath() {
        if (!enableCustomSavePath.Bool()) return "";
        return AyuCustomSavePath.normalize(customSavePath.String());
    }
    public static final tw.nekomimi.nekogram.config.ConfigItem regexFiltersEnabled = ayuBoolean(
            "RegexFiltersEnabled", () -> AyuConfig.INSTANCE.getRegexFiltersEnabled());
    public static final tw.nekomimi.nekogram.config.ConfigItem regexFiltersEnableInChats = ayuBoolean(
            "RegexFiltersEnableInChats", () -> AyuConfig.INSTANCE.getRegexFiltersEnableInChats());

    private static tw.nekomimi.nekogram.config.ConfigItem ayuBoolean(String key, java.util.function.Supplier<ConfigItem> item) {
        return new tw.nekomimi.nekogram.config.ConfigItem(key, configTypeBool, false) {
            @Override public boolean Bool() { return item.get().Bool(); }
            @Override public void setConfigBool(boolean v) { item.get().setConfigBool(v); }
            @Override public boolean toggleConfigBool() { return item.get().toggleConfigBool(); }
            @Override public void changed(Object v) { item.get().changed(v); }
            @Override public void saveConfig() { item.get().saveConfig(); }
        };
    }
    private static ConfigItem ghostDelegate(String key, boolean defaultValue,
                                            java.util.function.BooleanSupplier getter,
                                            java.util.function.Consumer<Boolean> setter) {
        ConfigItem item = new ConfigItem(key, configTypeBool, defaultValue) {
            @Override
            public boolean Bool() {
                return getter.getAsBoolean();
            }

            @Override
            public void setConfigBool(boolean v) {
                setter.accept(v);
            }

            @Override
            public boolean toggleConfigBool() {
                boolean n = !Bool();
                setConfigBool(n);
                return n;
            }

            @Override
            public void saveConfig() {
            }

            @Override
            public void changed(Object o) {
                if (o instanceof Boolean) {
                    setConfigBool((boolean) o);
                }
            }
        };

        return item;
    }

    public static ConfigItem useScheduledMessages = ghostDelegate("useScheduledMessages", false,
            () -> AyuGhostController.getInstance(UserConfig.selectedAccount).isUseScheduledMessages(),
            v -> AyuGhostController.getInstance(UserConfig.selectedAccount).setUseScheduledMessages(v));
}
