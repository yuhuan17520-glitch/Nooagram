package org.telegram.messenger;

import android.content.SharedPreferences;
import android.net.Uri;

final class NotificationDismissState {

    private static final String GLOBAL_DISMISS_DATE = "dismissDate";
    private static final String DIALOG_DISMISS_DATE_PREFIX = "dismissDate";

    private NotificationDismissState() {
    }

    static String getKey(long dialogId) {
        return dialogId == 0 ? GLOBAL_DISMISS_DATE : DIALOG_DISMISS_DATE_PREFIX + dialogId;
    }

    static Uri getIntentData(int account, long dialogId) {
        // PendingIntent identity ignores extras, so account and dialog must be part of the data.
        return Uri.parse("nooagram://notification-dismiss/" + account + "/" + dialogId);
    }

    static int getDismissDate(SharedPreferences preferences, long dialogId) {
        int dismissDate = preferences.getInt(GLOBAL_DISMISS_DATE, 0);
        if (dialogId != 0) {
            dismissDate = Math.max(dismissDate, preferences.getInt(getKey(dialogId), 0));
        }
        return dismissDate;
    }

    static void dismiss(SharedPreferences preferences, long dialogId, int messageDate) {
        if (messageDate <= 0) {
            return;
        }
        String key = getKey(dialogId);
        if (messageDate > preferences.getInt(key, 0)) {
            preferences.edit().putInt(key, messageDate).commit();
        }
    }

    static boolean isDismissed(SharedPreferences preferences, long dialogId, int messageDate, boolean storyPush) {
        return !storyPush && messageDate > 0 && messageDate <= getDismissDate(preferences, dialogId);
    }
}
