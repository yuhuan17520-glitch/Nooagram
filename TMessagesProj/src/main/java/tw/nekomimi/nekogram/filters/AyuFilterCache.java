package tw.nekomimi.nekogram.filters;

import androidx.collection.LruCache;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;

import java.util.concurrent.ConcurrentHashMap;

final class AyuFilterCache {
    private static final int PER_DIALOG_LIMIT = 1000;
    private static final int PER_DIALOG_GROUP_LIMIT = 500;
    private static final ConcurrentHashMap<DialogKey, LruCache<Integer, Boolean>> messageCaches = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<DialogKey, LruCache<Long, Boolean>> groupCaches = new ConcurrentHashMap<>();

    record DialogKey(int account, long userId, int loginTime, long dialogId) {
    }

    private AyuFilterCache() {
    }

    static DialogKey keyFor(long dialogId, MessageObject msg) {
        if (msg == null || msg.currentAccount < 0 || msg.currentAccount >= UserConfig.MAX_ACCOUNT_COUNT) {
            return null;
        }
        UserConfig config = UserConfig.getInstance(msg.currentAccount);
        long userId = config.getClientUserId();
        return userId == 0L ? null : new DialogKey(msg.currentAccount, userId, config.loginTime, dialogId);
    }

    private static boolean isCurrent(DialogKey key, MessageObject msg) {
        return key != null && msg != null && msg.currentAccount == key.account
                && UserConfig.getInstance(key.account).getClientUserId() == key.userId
                && UserConfig.getInstance(key.account).loginTime == key.loginTime;
    }

    static Boolean get(DialogKey key, MessageObject msg, MessageObject.GroupedMessages group) {
        if (!isCurrent(key, msg)) {
            return null;
        }
        long groupId = group != null ? group.groupId : msg.getGroupId();
        if (groupId != 0 && group != null) {
            LruCache<Long, Boolean> grpCache = groupCaches.get(key);
            if (grpCache != null) {
                synchronized (grpCache) {
                    Boolean val = grpCache.get(groupId);
                    if (val != null) {
                        return val;
                    }
                }
            }
        }
        LruCache<Integer, Boolean> msgCache = messageCaches.get(key);
        if (msgCache != null) {
            synchronized (msgCache) {
                Boolean val = msgCache.get(msg.getId());
                if (val != null) {
                    return val;
                }
            }
        }
        if (groupId != 0 && group == null) {
            LruCache<Long, Boolean> grpCache = groupCaches.get(key);
            if (grpCache != null) {
                synchronized (grpCache) {
                    return grpCache.get(groupId);
                }
            }
        }
        return null;
    }

    static void put(DialogKey key, MessageObject msg, MessageObject.GroupedMessages group, boolean value) {
        if (!isCurrent(key, msg)) {
            return;
        }
        LruCache<Integer, Boolean> msgCache = messageCaches.computeIfAbsent(key, k -> new LruCache<>(PER_DIALOG_LIMIT));
        synchronized (msgCache) {
            msgCache.put(msg.getId(), value);
        }
        long groupId = group != null ? group.groupId : msg.getGroupId();
        if (groupId != 0) {
            LruCache<Long, Boolean> grpCache = groupCaches.computeIfAbsent(key, k -> new LruCache<>(PER_DIALOG_GROUP_LIMIT));
            synchronized (grpCache) {
                grpCache.put(groupId, value);
            }
        }
    }

    static void invalidate(long dialogId, int msgId) {
        // The edit notification has no group ID. Other members may cache the same
        // album result, so invalidate both caches for this dialog.
        clearDialog(dialogId);
    }

    static void invalidateGroup(long dialogId, long groupId) {
        if (groupId == 0) {
            return;
        }
        clearDialog(dialogId);
    }

    static void clearDialog(long dialogId) {
        messageCaches.keySet().removeIf(key -> key.dialogId == dialogId);
        groupCaches.keySet().removeIf(key -> key.dialogId == dialogId);
    }

    static void clearAll() {
        messageCaches.clear();
        groupCaches.clear();
    }
}
