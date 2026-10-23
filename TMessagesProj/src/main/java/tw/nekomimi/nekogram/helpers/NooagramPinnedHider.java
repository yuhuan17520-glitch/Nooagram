package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public final class NooagramPinnedHider {
    private static final String PREFERENCES = "nooagram";
    private static final String PREFIX = "hidden_pinned_";
    private static final String USER_PREFIX = "hidden_pinned_user_";
    private static final String LEGACY_PREFIX = "legacy_hidden_pinned_";
    private static final HashMap<Long, Set<Long>> CACHE = new HashMap<>();

    private NooagramPinnedHider() {}

    public static synchronized void migrateLegacyAccounts() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            migrateLegacyAccount(account);
        }
    }

    private static void migrateLegacyAccount(int account) {
        SharedPreferences prefs = preferences();
        String oldKey = PREFIX + account;
        if (!prefs.contains(oldKey)) return;
        SharedPreferences.Editor editor = prefs.edit();
        HashSet<Long> legacy = readDialogs(LEGACY_PREFIX + account);
        legacy.addAll(readDialogs(oldKey));
        JSONArray array = new JSONArray();
        for (long dialog : legacy) array.put(dialog);
        // Old slot keys carry no user identity; only an explicit restore can adopt them.
        editor.putString(LEGACY_PREFIX + account, array.toString());
        editor.remove(oldKey).apply();
    }

    public static synchronized ArrayList<Long> getLegacyDialogs(int account) {
        migrateLegacyAccount(account);
        ArrayList<Long> result = new ArrayList<>(readDialogs(LEGACY_PREFIX + account));
        Collections.sort(result);
        return result;
    }

    public static synchronized void restoreLegacyDialogs(int account) {
        if (UserConfig.getInstance(account).getClientUserId() == 0) return;
        HashSet<Long> restored = new HashSet<>(hiddenDialogs(account));
        restored.addAll(getLegacyDialogs(account));
        save(account, restored);
        preferences().edit().remove(LEGACY_PREFIX + account).apply();
    }

    public static synchronized void onAccountLogout(int account) {
        migrateLegacyAccount(account);
        CACHE.remove(UserConfig.getInstance(account).getClientUserId());
    }

    private static Set<Long> hiddenDialogs(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        if (userId == 0) return Collections.emptySet();
        Set<Long> cached = CACHE.get(userId);
        if (cached != null) return cached;
        HashSet<Long> result = readDialogs(USER_PREFIX + userId);
        CACHE.put(userId, result);
        return result;
    }

    private static HashSet<Long> readDialogs(String key) {
        HashSet<Long> result = new HashSet<>();
        try {
            JSONArray array = new JSONArray(preferences().getString(key, "[]"));
            for (int i = 0; i < array.length(); i++) {
                long dialogId = array.optLong(i, 0L);
                if (dialogId != 0L) {
                    result.add(dialogId);
                }
            }
        } catch (JSONException exception) {
            FileLog.e("NooagramPinnedHider.getHiddenDialogs", exception);
        }
        return result;
    }

    public static synchronized ArrayList<Long> getHiddenDialogs(int account) {
        ArrayList<Long> result = new ArrayList<>(hiddenDialogs(account));
        Collections.sort(result);
        return result;
    }

    public static synchronized boolean isHidden(int account, long dialogId) {
        return hiddenDialogs(account).contains(dialogId);
    }

    public static synchronized void setHidden(int account, long dialogId, boolean hidden) {
        if (dialogId == 0 || UserConfig.getInstance(account).getClientUserId() == 0) return;
        HashSet<Long> dialogs = new HashSet<>(hiddenDialogs(account));
        boolean changed = hidden ? dialogs.add(dialogId) : dialogs.remove(Long.valueOf(dialogId));
        if (changed) {
            save(account, dialogs);
        }
    }

    public static synchronized void toggleSelected(int account, ArrayList<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        HashSet<Long> hidden = new HashSet<>(hiddenDialogs(account));
        boolean restore = hidden.containsAll(dialogIds);
        for (Long dialogId : dialogIds) {
            if (dialogId == null || dialogId == 0L) {
                continue;
            }
            if (restore) {
                hidden.remove(dialogId);
            } else if (!hidden.contains(dialogId)) {
                hidden.add(dialogId);
            }
        }
        save(account, hidden);
    }

    public static synchronized void clear(int account) {
        save(account, Collections.emptySet());
    }

    public static String getMenuLabel(int account, ArrayList<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return "隐藏置顶";
        }
        if (dialogIds.size() == 1) {
            return isHidden(account, dialogIds.get(0)) ? "恢复此群置顶" : "隐藏此群置顶";
        }
        return "切换已选置顶";
    }

    private static void save(int account, Set<Long> dialogs) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        if (userId == 0) return;
        JSONArray array = new JSONArray();
        HashSet<Long> values = new HashSet<>();
        for (Long dialogId : dialogs) {
            if (dialogId != null && dialogId != 0L) {
                array.put(dialogId.longValue());
                values.add(dialogId);
            }
        }
        CACHE.put(userId, values);
        preferences().edit().putString(USER_PREFIX + userId, array.toString()).apply();
        AndroidUtilities.runOnUIThread(() -> {
            if (UserConfig.getInstance(account).getClientUserId() == userId) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.nooagramPinnedHiderChanged);
            }
        });
    }

    private static SharedPreferences preferences() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            throw new IllegalStateException("Application context is not ready");
        }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
