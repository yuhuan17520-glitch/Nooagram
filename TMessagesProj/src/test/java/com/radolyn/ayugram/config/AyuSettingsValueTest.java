package com.radolyn.ayugram.config;

import android.content.Context;

import com.radolyn.ayugram.AyuOptions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class AyuSettingsValueTest {
    @Before public void setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext.getSharedPreferences("nkmrcfg", Context.MODE_PRIVATE)
                .edit().putBoolean("ConfigMigrated", true).apply();
    }

    @Test public void longPressSerializationOfAyuSwitchReadsCurrentBooleanWithoutCastingToString() {
        ConfigItem option = AyuOptions.disableAds;
        assertEquals("false", option.String());
        option.setConfigBool(true);
        assertEquals("true", option.String());
        // Cloud settings may update preferences without updating the cached field.
        AyuOptions.getPreferences().edit().putBoolean(option.key, false).apply();
        assertEquals("false", option.String());
        assertFalse(option.Bool());
        assertTrue(AyuOptions.getPreferences().getAll().get(option.key) instanceof Boolean);
    }

    @Test public void allAyuOnlySwitchesCanBeSerializedWithoutChangingThem() {
        for (ConfigItem option : new ConfigItem[]{AyuOptions.disableAds, AyuOptions.enableCustomSavePath,
                AyuOptions.nooagramHighRefreshRate, AyuOptions.showGhostModeStatus}) {
            boolean before = option.Bool();
            assertEquals(Boolean.toString(before), option.String());
            assertEquals(before, option.Bool());
        }
    }

    @Test public void integerAndStringReadThroughValuesUseTheirOwnPreferenceTypes() throws Exception {
        Method add = AyuOptions.class.getDeclaredMethod("addConfig", String.class, int.class, Object.class);
        add.setAccessible(true);
        ConfigItem number = (ConfigItem) add.invoke(null, "testNumber", ConfigItem.configTypeInt, 7);
        ConfigItem text = (ConfigItem) add.invoke(null, "testText", ConfigItem.configTypeString, "initial");
        AyuOptions.getPreferences().edit().putInt(number.key, 42).putString(text.key, "中文目录").apply();
        assertEquals("42", number.String());
        assertEquals("中文目录", text.String());
        assertEquals(42, number.checkConfigFromString(number.String()));
        assertEquals("中文目录", text.checkConfigFromString(text.String()));
    }

    @Test public void upstreamSwitchSerializationTracksDelegate() {
        AyuOptions.localPremium.setConfigBool(true);
        assertEquals("true", AyuOptions.localPremium.String());
        tw.nekomimi.nekogram.NekoConfig.localPremium.setConfigBool(false);
        assertEquals("false", AyuOptions.localPremium.String());
    }

    @Test public void linkedAndDelegatedSwitchesSerializeGetterInsteadOfStaleField() {
        ConfigItem delegated = new ConfigItem("delegated", ConfigItem.configTypeBool, false) {
            @Override public boolean Bool() { return true; }
        };
        assertEquals("true", delegated.String());
        ConfigItem linked = new ConfigItemKeyLinked("linked",
                new ConfigItem("flags", ConfigItem.configTypeInt, 0), 1, false);
        ((ConfigItemKeyLinked) linked).changedFromKeyLinked(2);
        assertEquals("true", linked.String());
        assertEquals(Boolean.TRUE, linked.checkConfigFromString(linked.String()));
    }
}
