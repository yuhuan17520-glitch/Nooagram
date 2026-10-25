/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class NotificationDismissReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        int currentAccount = intent.getIntExtra("currentAccount", UserConfig.selectedAccount);
        if (!NotificationDismissState.isValidAccount(context, currentAccount)) {
            return;
        }
        long dialogId = intent.getLongExtra("dialogId", 0);
        int date = intent.getIntExtra(NotificationDismissState.EXTRA_MESSAGE_DATE, 0);
        boolean story = intent.getBooleanExtra("story", false);
        boolean storyReaction = intent.getBooleanExtra("storyReaction", false);
        boolean dismissMessages = intent.getBooleanExtra(NotificationDismissState.EXTRA_DISMISS_MESSAGES, !story && !storyReaction);
        boolean clearStorySummaries = dismissMessages && story && dialogId == 0;

        if (dismissMessages) {
            SharedPreferences preferences = NotificationDismissState.getAccountPreferences(context, currentAccount);
            if (dialogId == 0) {
                FileLog.d("set dismissDate of global to " + date);
            } else {
                FileLog.d("set dismissDate of " + dialogId + " to " + date);
            }
            NotificationDismissState.dismiss(preferences, dialogId, date);
            if (clearStorySummaries) {
                NotificationsController.getInstance(currentAccount);
            }
            NotificationsController.reconcileDismissal(currentAccount, dialogId, date, clearStorySummaries);
        }

        if (story && !clearStorySummaries) {
            NotificationsController.getInstance(currentAccount).processIgnoreStories();
        } else if (storyReaction) {
            NotificationsController.getInstance(currentAccount).processIgnoreStoryReactions();
        } else if (!dismissMessages && dialogId == 0) {
            FileLog.d("set dismissDate of global to " + date);
            NotificationDismissState.dismiss(MessagesController.getNotificationsSettings(currentAccount), 0, date);
        } else if (!dismissMessages) {
            FileLog.d("set dismissDate of " + dialogId + " to " + date);
            NotificationDismissState.dismiss(MessagesController.getNotificationsSettings(currentAccount), dialogId, date);
        }
    }
}
