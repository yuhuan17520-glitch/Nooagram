package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Collections;

public final class NooagramPinnedHider {
    private static final String PREFERENCES = "nooagram";
    private static final String PREFIX = "hidden_pinned_";

    private NooagramPinnedHider() {}

    public static ArrayList<Long> getHiddenDialogs(int account) {
        ArrayList<Long> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences().getString(PREFIX + account, "[]"));
            for (int i = 0; i < array.length(); i++) {
                long dialogId = array.optLong(i, 0L);
                if (dialogId != 0L) {
                    result.add(dialogId);
                }
            }
        } catch (JSONException exception) {
            FileLog.e("NooagramPinnedHider.getHiddenDialogs", exception);
        }
        Collections.sort(result);
        return result;
    }

    public static boolean isHidden(int account, long dialogId) {
        return getHiddenDialogs(account).contains(dialogId);
    }

    public static void setHidden(int account, long dialogId, boolean hidden) {
        ArrayList<Long> dialogs = getHiddenDialogs(account);
        boolean changed = hidden ? dialogs.add(dialogId) : dialogs.remove(Long.valueOf(dialogId));
        if (changed) {
            save(account, dialogs);
        }
    }

    public static void toggleSelected(int account, ArrayList<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        ArrayList<Long> hidden = getHiddenDialogs(account);
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

    public static void clear(int account) {
        preferences().edit().remove(PREFIX + account).apply();
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

    private static void save(int account, ArrayList<Long> dialogs) {
        Collections.sort(dialogs);
        JSONArray array = new JSONArray();
        for (Long dialogId : dialogs) {
            if (dialogId != null && dialogId != 0L) {
                array.put(dialogId.longValue());
            }
        }
        preferences().edit().putString(PREFIX + account, array.toString()).apply();
    }

    private static SharedPreferences preferences() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            throw new IllegalStateException("Application context is not ready");
        }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
