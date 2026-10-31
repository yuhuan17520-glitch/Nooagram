/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.utils;

import android.util.LongSparseArray;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;

import com.radolyn.ayugram.AyuConfig;

public class AyuState {
    private static final AyuStateVariable hideSelection = new AyuStateVariable();
    private static final AyuStateVariable automaticallyScheduled = new AyuStateVariable();
    private static final AyuStateVariable allowDeleteDialogs = new AyuStateVariable();
    private static final Object deletePermittedLock = new Object();
    // Keyed by account slot first: two accounts in the same chat must not share delete permits.
    @SuppressWarnings("unchecked")
    private static final LongSparseArray<ArrayList<Integer>>[] deletePermitted = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];

    public static void setHideSelection(boolean val, int resetAfter) {
        hideSelection.val = val;
        hideSelection.resetAfter = resetAfter;
    }

    public static boolean getHideSelection() {
        return hideSelection.process();
    }

    public static void setAutomaticallyScheduled(boolean val, int resetAfter) {
        automaticallyScheduled.val = val;
        automaticallyScheduled.resetAfter = resetAfter;
    }

    public static boolean getAutomaticallyScheduled() {
        return automaticallyScheduled.process();
    }

    /**
     * 标记接下来的会话删除是用户主动发起的，此时不保存会话快照、并清除已有快照记录。
     * resetAfter 为剩余生效次数，deleteDialog 内部会递归调用，需要按调用层数放行。
     */
    public static void setAllowDeleteDialogs(boolean val, int resetAfter) {
        allowDeleteDialogs.val = val;
        allowDeleteDialogs.resetAfter = resetAfter;
    }

    public static boolean getAllowDeleteDialogs() {
        return allowDeleteDialogs.process();
    }

    private static LongSparseArray<ArrayList<Integer>> deletePermittedFor(int account, boolean create) {
        if (account < 0 || account >= deletePermitted.length) {
            return null;
        }
        var map = deletePermitted[account];
        if (map == null && create) {
            map = new LongSparseArray<>();
            deletePermitted[account] = map;
        }
        return map;
    }

    public static void permitDeleteMessage(int account, long dialogId, int messageId) {
        synchronized (deletePermittedLock) {
            var map = deletePermittedFor(account, true);
            if (map == null) {
                return;
            }
            var list = map.get(dialogId);
            if (list == null) {
                list = new ArrayList<>();
                map.put(dialogId, list);
            }

            if (!list.contains(messageId)) {
                list.add(messageId);
            }
        }
    }

    public static boolean isDeletePermitted(int account, long dialogId, int messageId) {
        synchronized (deletePermittedLock) {
            var map = deletePermittedFor(account, false);
            var list = map == null ? null : map.get(dialogId);
            if (list == null) {
                return false;
            }

            return list.contains(messageId);
        }
    }

    public static void messageDeleted(int account, long dialogId, int messageId) {
        synchronized (deletePermittedLock) {
            var map = deletePermittedFor(account, false);
            var list = map == null ? null : map.get(dialogId);
            if (list == null) {
                return;
            }

            list.remove((Object) messageId);
            if (list.isEmpty()) {
                map.delete(dialogId);
            }
        }
    }

    /** Drops all pending delete permits of an account slot (logout). */
    public static void clearDeletePermits(int account) {
        synchronized (deletePermittedLock) {
            if (account >= 0 && account < deletePermitted.length) {
                deletePermitted[account] = null;
            }
        }
    }

    /**
     * 标记阅后即焚媒体已被查看过。
     */
    public static void setMessageBurned(int account, long dialogId, int messageId) {
        if (!AyuConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return;
        }
        long clientUserId = UserConfig.getInstance(account).getClientUserId();
        MessagesController.getGlobalMainSettings().edit().putBoolean("messageBurned_" + clientUserId + "_" + dialogId + "_" + messageId, true).apply();
    }

    public static boolean isMessageBurned(int account, long dialogId, int messageId) {
        if (!AyuConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return false;
        }
        long clientUserId = UserConfig.getInstance(account).getClientUserId();
        return MessagesController.getGlobalMainSettings().getBoolean("messageBurned_" + clientUserId + "_" + dialogId + "_" + messageId, false);
    }
}
