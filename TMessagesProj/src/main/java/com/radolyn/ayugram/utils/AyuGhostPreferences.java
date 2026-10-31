package com.radolyn.ayugram.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import tw.nekomimi.nekogram.helpers.CloudSettingsHelper;

import java.util.concurrent.ConcurrentHashMap;

import com.radolyn.ayugram.AyuOptions;

public class AyuGhostPreferences {
    public static final int TYPE_DEFAULT = 0;
    public static final int TYPE_FORCE_BLOCK = 1;
    public static final int TYPE_FORCE_ALLOW = 2;

    public static final String ghostReadExclusionPrefix = "ghostModeReadExclusion_";
    public static final String ghostTypingExclusionPrefix = "ghostModeTypingExclusion_";
    public static final String ghostReadExceptionPrefix = "ghostModeReadException_";
    public static final String ghostTypingExceptionPrefix = "ghostModeTypingException_";

    private static final String PREFS_FILE = "ayughostexclusionsconfig";
    private static final ConcurrentHashMap<String, Integer> readExceptions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> typingExceptions = new ConcurrentHashMap<>();
    private static SharedPreferences preferences;

    private static synchronized SharedPreferences getPrefs() {
        if (preferences == null) {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
            preferences.registerOnSharedPreferenceChangeListener(CloudSettingsHelper.listener);
        }
        return preferences;
    }

    public static void setReadException(long dialogId, int type) {
        setReadException(UserConfig.selectedAccount, dialogId, type);
    }

    public static void setReadException(int account, long dialogId, int type) {
        String key = key(account, dialogId);
        int value = normalizeType(type);
        readExceptions.put(key, value);
        SharedPreferences.Editor editor = getPrefs().edit();
        if (value == TYPE_DEFAULT) {
            editor.putInt(ghostReadExceptionPrefix + key, TYPE_DEFAULT);
        } else {
            editor.putInt(ghostReadExceptionPrefix + key, value);
        }
        editor.remove(ghostReadExclusionPrefix + key);
        editor.apply();
        AyuOptions.getPreferences().edit().remove(ghostReadExclusionPrefix + key).apply();
    }

    public static int getReadException(long dialogId) {
        return getReadException(UserConfig.selectedAccount, dialogId);
    }

    public static int getReadException(int account, long dialogId) {
        String key = key(account, dialogId);
        return readExceptions.computeIfAbsent(key, ignored -> loadReadException(account, dialogId));
    }

    public static void setTypingException(long dialogId, int type) {
        setTypingException(UserConfig.selectedAccount, dialogId, type);
    }

    public static void setTypingException(int account, long dialogId, int type) {
        String key = key(account, dialogId);
        int value = normalizeType(type);
        typingExceptions.put(key, value);
        SharedPreferences.Editor editor = getPrefs().edit();
        if (value == TYPE_DEFAULT) {
            editor.putInt(ghostTypingExceptionPrefix + key, TYPE_DEFAULT);
        } else {
            editor.putInt(ghostTypingExceptionPrefix + key, value);
        }
        editor.remove(ghostTypingExclusionPrefix + key);
        editor.apply();
        AyuOptions.getPreferences().edit().remove(ghostTypingExclusionPrefix + key).apply();
    }

    public static int getTypingException(long dialogId) {
        return getTypingException(UserConfig.selectedAccount, dialogId);
    }

    public static int getTypingException(int account, long dialogId) {
        String key = key(account, dialogId);
        return typingExceptions.computeIfAbsent(key, ignored -> loadTypingException(account, dialogId));
    }

    public static void setGhostModeReadExclusion(long chatId, boolean value) {
        setReadException(chatId, value ? TYPE_FORCE_ALLOW : TYPE_DEFAULT);
    }

    public static boolean getGhostModeReadExclusion(long chatId) {
        return getReadException(chatId) == TYPE_FORCE_ALLOW;
    }

    public static void setGhostModeTypingExclusion(long chatId, boolean value) {
        setTypingException(chatId, value ? TYPE_FORCE_ALLOW : TYPE_DEFAULT);
    }

    public static boolean getGhostModeTypingExclusion(long chatId) {
        return getTypingException(chatId) == TYPE_FORCE_ALLOW;
    }

    public static boolean shouldBlockWhenGlobalDisabled(int type) {
        return type != TYPE_FORCE_ALLOW;
    }

    public static boolean shouldBlockWhenGlobalEnabled(int type) {
        return type == TYPE_FORCE_BLOCK;
    }

    private static int loadReadException(int account, long dialogId) {
        SharedPreferences prefs = getPrefs();
        String key = key(account, dialogId);
        if (prefs.contains(ghostReadExceptionPrefix + key)) {
            return normalizeType(prefs.getInt(ghostReadExceptionPrefix + key, TYPE_DEFAULT));
        }
        long legacyKey = Math.abs(dialogId);
        if (prefs.contains(ghostReadExceptionPrefix + legacyKey)) {
            return normalizeType(prefs.getInt(ghostReadExceptionPrefix + legacyKey, TYPE_DEFAULT));
        }
        if (prefs.getBoolean(ghostReadExclusionPrefix + key, false)
                || AyuOptions.getPreferences().getBoolean(ghostReadExclusionPrefix + key, false)
                || prefs.getBoolean(ghostReadExclusionPrefix + legacyKey, false)
                || AyuOptions.getPreferences().getBoolean(ghostReadExclusionPrefix + legacyKey, false)) {
            return TYPE_FORCE_ALLOW;
        }
        return TYPE_DEFAULT;
    }

    private static int loadTypingException(int account, long dialogId) {
        SharedPreferences prefs = getPrefs();
        String key = key(account, dialogId);
        if (prefs.contains(ghostTypingExceptionPrefix + key)) {
            return normalizeType(prefs.getInt(ghostTypingExceptionPrefix + key, TYPE_DEFAULT));
        }
        long legacyKey = Math.abs(dialogId);
        if (prefs.contains(ghostTypingExceptionPrefix + legacyKey)) {
            return normalizeType(prefs.getInt(ghostTypingExceptionPrefix + legacyKey, TYPE_DEFAULT));
        }
        if (prefs.getBoolean(ghostTypingExclusionPrefix + key, false)
                || AyuOptions.getPreferences().getBoolean(ghostTypingExclusionPrefix + key, false)
                || prefs.getBoolean(ghostTypingExclusionPrefix + legacyKey, false)
                || AyuOptions.getPreferences().getBoolean(ghostTypingExclusionPrefix + legacyKey, false)) {
            return TYPE_FORCE_ALLOW;
        }
        return TYPE_DEFAULT;
    }

    private static String key(int account, long dialogId) {
        return account + "_" + dialogId;
    }

    private static int normalizeType(int type) {
        if (type == TYPE_FORCE_BLOCK || type == TYPE_FORCE_ALLOW) {
            return type;
        }
        return TYPE_DEFAULT;
    }
}
