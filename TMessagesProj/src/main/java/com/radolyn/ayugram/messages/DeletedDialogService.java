/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.messages;

import android.text.TextUtils;
import android.util.LongSparseArray;

import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.dao.DeletedDialogDao;
import com.radolyn.ayugram.database.entities.DeletedDialog;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.utils.AyuMessageUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.radolyn.ayugram.AyuConfig;

public class DeletedDialogService {
    private final ConcurrentHashMap<Integer, AccountCache<MessageObject>> cachesByAccount = new ConcurrentHashMap<>();

    static final class AccountCache<T> {
        private long userId;
        private long generation;
        final ConcurrentHashMap<Long, T> messages = new ConcurrentHashMap<>();
        /** dialogId -> (topicId -> 该话题内最后一条已删除消息)，供话题单元格的预览回退 */
        final ConcurrentHashMap<Long, ConcurrentHashMap<Long, T>> topics = new ConcurrentHashMap<>();

        synchronized long bind(long userId) {
            if (this.userId != userId) {
                clear();
                this.userId = userId;
            }
            return generation;
        }

        synchronized boolean isCurrent(long userId, long generation) {
            return userId != 0 && this.userId == userId && this.generation == generation;
        }

        synchronized void clear() {
            generation++;
            messages.clear();
            topics.clear();
        }
    }

    public DeletedDialogService() {
    }

    private AccountCache<MessageObject> cacheForAccount(int account) {
        return cachesByAccount.computeIfAbsent(account, k -> new AccountCache<>());
    }

    private boolean isCurrent(int account, long userId, long generation) {
        return UserConfig.getInstance(account).clientUserId == userId
                && cacheForAccount(account).isCurrent(userId, generation);
    }

    private void postForAccount(int account, long userId, long generation, Runnable runnable, long delay) {
        AccountCache<MessageObject> cache = cacheForAccount(account);
        synchronized (cache) {
            if (!isCurrent(account, userId, generation)) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (cache) {
                    if (isCurrent(account, userId, generation)) {
                        runnable.run();
                    }
                }
            }, delay);
        }
    }

    public void clearAccount(int account) {
        cacheForAccount(account).clear();
    }

    public MessageObject getLastMessageCached(int account, long dialogId) {
        AccountCache<MessageObject> cache = cacheForAccount(account);
        synchronized (cache) {
            long userId = UserConfig.getInstance(account).clientUserId;
            cache.bind(userId);
            return userId != 0 ? cache.messages.get(dialogId) : null;
        }
    }

    public MessageObject getLastTopicMessageCached(int account, long dialogId, long topicId) {
        AccountCache<MessageObject> cache = cacheForAccount(account);
        synchronized (cache) {
            long userId = UserConfig.getInstance(account).clientUserId;
            cache.bind(userId);
            ConcurrentHashMap<Long, MessageObject> topicMap = cache.topics.get(dialogId);
            return userId != 0 && topicId != 0 && topicMap != null ? topicMap.get(topicId) : null;
        }
    }

    public void putLastTopicMessage(int account, long ownerUserId, long dialogId, long topicId, MessageObject messageObject) {
        if (messageObject == null || topicId == 0) {
            return;
        }
        AccountCache<MessageObject> cache = cacheForAccount(account);
        synchronized (cache) {
            long userId = UserConfig.getInstance(account).clientUserId;
            cache.bind(userId);
            if (userId == 0 || userId != ownerUserId) {
                return;
            }
            ConcurrentHashMap<Long, MessageObject> topicMap = cache.topics
                    .computeIfAbsent(dialogId, k -> new ConcurrentHashMap<>());
            MessageObject existing = topicMap.get(topicId);
            if (AyuMessageUtils.isNewerMessage(messageObject, existing)) {
                topicMap.put(topicId, messageObject);
            }
        }
    }

    public void removeTopicMessages(int account, long dialogId) {
        AccountCache<MessageObject> cache = cacheForAccount(account);
        synchronized (cache) {
            cache.bind(UserConfig.getInstance(account).clientUserId);
            cache.topics.remove(dialogId);
        }
    }

    /** ownerUserId is the archive owner; the write is dropped if the slot now belongs to someone else. */
    public void putLastMessage(int account, long ownerUserId, long dialogId, MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        AccountCache<MessageObject> cache = cacheForAccount(account);
        synchronized (cache) {
            long userId = UserConfig.getInstance(account).clientUserId;
            cache.bind(userId);
            if (userId != 0 && userId == ownerUserId && AyuMessageUtils.isNewerMessage(messageObject, cache.messages.get(dialogId))) {
                cache.messages.put(dialogId, messageObject);
            }
        }
    }

    public void onDialogDeleted(int account, long dialogId) {
        if (!AyuConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return;
        }
        try {
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.Dialog dialog = mc.getDialog(dialogId);
            if (dialog == null) {
                return;
            }
            long userId = UserConfig.getInstance(account).clientUserId;
            if (userId == 0) {
                return;
            }
            long generation = cacheForAccount(account).bind(userId);
            int entityCreateDate = ConnectionsManager.getInstance(account).getCurrentTime();
            DeletedDialog entity = mapDialogToEntity(dialog, userId, entityCreateDate);
            Utilities.globalQueue.postRunnable(() -> saveDeletedDialog(account, entity, generation));
        } catch (Throwable e) {
            FileLog.e("onDialogDeleted", e);
        }
    }

    private void saveDeletedDialog(int account, DeletedDialog entity, long generation) {
        if (!isCurrent(account, entity.userId, generation)) {
            return;
        }
        try {
            DeletedDialogDao dao = AyuData.getDeletedDialogDao();
            if (dao == null) {
                return;
            }
            DeletedDialog existing = dao.get(entity.userId, entity.dialogId);
            if (existing != null) {
                dao.delete(existing);
            }
            dao.insert(entity);
            postForAccount(account, entity.userId, generation, () -> {
                putDialog(account, entity);
                MessagesController mc = MessagesController.getInstance(account);
                mc.sortDialogs(null);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            }, 1500);
        } catch (Throwable e) {
            FileLog.e("saveDeletedDialog", e);
        }
    }

    public void loadAndRestore(int account) {
        if (!AyuConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return;
        }
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        long userId = UserConfig.getInstance(account).clientUserId;
        long generation = cacheForAccount(account).bind(userId);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                loadLastMessages(account, userId, generation);
                loadDeletedDialogs(account, userId, generation);
            } catch (Throwable e) {
                FileLog.e("loadAndRestore", e);
            }
        });
    }

    /**
     * 归档被清空后立刻丢掉这些会话（含其话题）的预览缓存，避免界面继续显示已经不存在的内容。
     */
    public void removeLastMessages(int account, long dialogId, long mergeDialogId) {
        AccountCache<MessageObject> cache = cacheForAccount(account);
        synchronized (cache) {
            cache.bind(UserConfig.getInstance(account).clientUserId);
            cache.messages.remove(dialogId);
            cache.topics.remove(dialogId);
            if (mergeDialogId != 0) {
                cache.messages.remove(mergeDialogId);
                cache.topics.remove(mergeDialogId);
            }
        }
    }

    /** 重新载入最后消息缓存（归档行被删除后刷新会话预览）。 */
    public void reloadLastMessages(int account) {
        if (!AyuConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return;
        }
        long userId = UserConfig.getInstance(account).clientUserId;
        long generation = cacheForAccount(account).bind(userId);
        loadLastMessages(account, userId, generation);
    }

    private void loadLastMessages(int account, long userId, long generation) {
        if (!isCurrent(account, userId, generation)) {
            return;
        }
        List<DeletedMessageFull> list;
        try {
            list = AyuData.getDeletedMessageDao().getLastMessages(userId);
        } catch (Throwable e) {
            FileLog.e("loadLastMessages", e);
            return;
        }

        ConcurrentHashMap<Long, ConcurrentHashMap<Long, MessageObject>> topicMap = new ConcurrentHashMap<>();

        ArrayList<Long> usersToLoad = new ArrayList<>();
        ArrayList<Long> chatsToLoad = new ArrayList<>();
        ConcurrentHashMap<Long, MessageObject> mapped = new ConcurrentHashMap<>();

        for (DeletedMessageFull full : list != null ? list : java.util.Collections.<DeletedMessageFull>emptyList()) {
            if (full == null || full.message == null || isEmpty(full.message)) {
                continue;
            }
            try {
                TLRPC.TL_message tlMessage = new TLRPC.TL_message();
                AyuMessageUtils.map(full.message, tlMessage, account);
                try {
                    AyuMessageUtils.mapMedia(full.message, tlMessage, account);
                } catch (Exception e) {
                    FileLog.e("Failed to map media for message " + full.message.messageId, e);
                }
                tlMessage.ayuDeleted = true;
                MessagesStorage.addUsersAndChatsFromMessage(tlMessage, usersToLoad, chatsToLoad, null);
                MessageObject messageObject = new MessageObject(account, tlMessage, false, false);
                if (!TextUtils.isEmpty(messageObject.messageText)) {
                    // 同一会话可能返回多行（如密聊按 MAX(messageId) 取到的并非最新），取更新的那条
                    MessageObject existing = mapped.get(full.message.dialogId);
                    if (AyuMessageUtils.isNewerMessage(messageObject, existing)) {
                        mapped.put(full.message.dialogId, messageObject);
                    }
                    if (full.message.topicId != 0) {
                        ConcurrentHashMap<Long, MessageObject> topicMessages = topicMap.computeIfAbsent(
                                full.message.dialogId, k -> new ConcurrentHashMap<>());
                        MessageObject existingTopic = topicMessages.get(full.message.topicId);
                        if (AyuMessageUtils.isNewerMessage(messageObject, existingTopic)) {
                            topicMessages.put(full.message.topicId, messageObject);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e("loadLastMessages map", e);
            }
        }

        AccountCache<MessageObject> cache = cacheForAccount(account);
        synchronized (cache) {
            if (!isCurrent(account, userId, generation)) {
                return;
            }
            cache.messages.clear();
            cache.messages.putAll(mapped);
            cache.topics.clear();
            cache.topics.putAll(topicMap);
        }
        if (mapped.isEmpty()) {
            return;
        }

        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        loadUsersAndChats(account, usersToLoad, chatsToLoad, users, chats);

        postForAccount(account, userId, generation, () -> {
            MessagesController mc = MessagesController.getInstance(account);
            if (!users.isEmpty()) {
                mc.putUsers(users, true);
            }
            if (!chats.isEmpty()) {
                mc.putChats(chats, true);
            }
        }, 0);
    }

    private void loadDeletedDialogs(int account, long userId, long generation) {
        if (!isCurrent(account, userId, generation)) {
            return;
        }
        List<DeletedDialog> all;
        try {
            all = AyuData.getDeletedDialogDao().getAll(userId);
        } catch (Throwable e) {
            FileLog.e("loadDeletedDialogs", e);
            return;
        }
        if (all == null || all.isEmpty()) {
            return;
        }

        ArrayList<Long> userIds = new ArrayList<>();
        ArrayList<Long> chatIds = new ArrayList<>();
        collectDialogIds(all, userIds, chatIds);

        LongSparseArray<Boolean> present = new LongSparseArray<>();
        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        MessagesStorage storage = MessagesStorage.getInstance(account);

        for (int i = 0; i < userIds.size(); i++) {
            long id = userIds.get(i);
            TLRPC.User user = storage.getUser(id);
            present.put(id, user != null);
            if (user != null) {
                users.add(user);
            }
        }
        for (int i = 0; i < chatIds.size(); i++) {
            long id = chatIds.get(i);
            TLRPC.Chat chat = storage.getChat(id);
            present.put(id, chat != null);
            if (chat != null) {
                chats.add(chat);
            }
        }

        final List<DeletedDialog> dialogs = all;
        postForAccount(account, userId, generation, () -> {
            MessagesController mc = MessagesController.getInstance(account);
            for (DeletedDialog deletedDialog : dialogs) {
                long dialogId = deletedDialog.dialogId;
                try {
                    if (DialogObject.isUserDialog(dialogId)) {
                        if (Boolean.TRUE.equals(present.get(dialogId, false))) {
                            putDialog(account, deletedDialog);
                        }
                    } else if (DialogObject.isChatDialog(dialogId)) {
                        if (Boolean.TRUE.equals(present.get(-dialogId, false))) {
                            putDialog(account, deletedDialog);
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e("Failed to put deleted dialog " + dialogId, e);
                }
            }
            mc.sortDialogs(null);
            try {
                if (!users.isEmpty()) {
                    mc.putUsers(users, true);
                }
                if (!chats.isEmpty()) {
                    mc.putChats(chats, true);
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            } catch (Throwable e) {
                FileLog.e("Failed to reload dialogs after loading deleted dialogs", e);
            }
        }, 0);
    }

    private void collectDialogIds(List<DeletedDialog> list, ArrayList<Long> userIds, ArrayList<Long> chatIds) {
        for (DeletedDialog deletedDialog : list) {
            long dialogId = deletedDialog.dialogId;
            if (DialogObject.isUserDialog(dialogId)) {
                if (!userIds.contains(dialogId)) {
                    userIds.add(dialogId);
                }
            } else if (DialogObject.isChatDialog(dialogId)) {
                long chatId = -dialogId;
                if (!chatIds.contains(chatId)) {
                    chatIds.add(chatId);
                }
            }
        }
    }

    private void loadUsersAndChats(int account, ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad,
                                   ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        MessagesController mc = MessagesController.getInstance(account);
        try {
            if (!usersToLoad.isEmpty()) {
                ArrayList<Long> missing = new ArrayList<>();
                for (Long id : usersToLoad) {
                    TLRPC.User user = mc.getUser(id);
                    if (user != null) {
                        users.add(user);
                    } else {
                        missing.add(id);
                    }
                }
                if (!missing.isEmpty()) {
                    storage.getUsersInternal(missing, users);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            if (!chatsToLoad.isEmpty()) {
                ArrayList<Long> missing = new ArrayList<>();
                for (Long id : chatsToLoad) {
                    TLRPC.Chat chat = mc.getChat(id);
                    if (chat != null) {
                        chats.add(chat);
                    } else {
                        missing.add(id);
                    }
                }
                if (!missing.isEmpty()) {
                    storage.getChatsInternal(TextUtils.join(",", missing), chats);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void putDialog(int account, DeletedDialog entity) {
        if (UserConfig.getInstance(account).clientUserId != entity.userId) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        if (mc.getDialog(entity.dialogId) != null) {
            return;
        }
        TLRPC.TL_dialog dialog = mapEntityToDialog(account, entity);
        mc.dialogs_dict.put(dialog.id, dialog);
        mc.getAllDialogs().add(dialog);
        MessageObject lastMessage = getLastMessageCached(account, entity.dialogId);
        if (lastMessage != null) {
            ArrayList<MessageObject> existing = mc.dialogMessage.get(dialog.id);
            if (existing == null || existing.isEmpty()
                    || AyuMessageUtils.isNewerMessage(lastMessage, existing.get(0))) {
                ArrayList<MessageObject> list = new ArrayList<>();
                list.add(lastMessage);
                mc.dialogMessage.put(dialog.id, list);
            }
        }
    }

    public static DeletedDialog mapDialogToEntity(TLRPC.Dialog dialog, long userId, int entityCreateDate) {
        DeletedDialog entity = new DeletedDialog();
        entity.userId = userId;
        entity.dialogId = dialog.id;
        entity.peerId = dialog.peer != null ? MessageObject.getPeerId(dialog.peer) : dialog.id;
        entity.folderId = dialog.folder_id;
        entity.topMessage = dialog.top_message;
        entity.lastMessageDate = dialog.last_message_date;
        entity.flags = dialog.flags;
        entity.entityCreateDate = entityCreateDate;
        return entity;
    }

    public static TLRPC.TL_dialog mapEntityToDialog(int account, DeletedDialog entity) {
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.id = entity.dialogId;
        dialog.peer = MessagesController.getInstance(account).getPeer(entity.peerId != 0 ? entity.peerId : entity.dialogId);
        if (entity.folderId != null) {
            dialog.folder_id = entity.folderId;
        }
        dialog.top_message = entity.topMessage;
        dialog.last_message_date = entity.lastMessageDate;
        dialog.flags = entity.flags;
        return dialog;
    }

    public void updateDeletedDialogsFolder(int account, List<Long> dialogIds, int folderId) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        long userId = UserConfig.getInstance(account).clientUserId;
        if (userId == 0) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                DeletedDialogDao dao = AyuData.getDeletedDialogDao();
                if (dao != null) {
                    dao.updateDialogsFolder(userId, dialogIds, folderId);
                }
            } catch (Throwable e) {
                FileLog.e("updateDeletedDialogsFolder", e);
            }
        });
    }

    public void onOfficialDialogsLoaded(int account, List<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        if (!AyuConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return;
        }
        long userId = UserConfig.getInstance(account).clientUserId;
        if (userId == 0) {
            return;
        }
        long generation = cacheForAccount(account).bind(userId);
        ArrayList<Long> ids = new ArrayList<>(dialogIds);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                DeletedDialogDao dao = AyuData.getDeletedDialogDao();
                if (dao != null) {
                    dao.deleteExisting(userId, ids);
                }
                AccountCache<MessageObject> cache = cacheForAccount(account);
                synchronized (cache) {
                    if (!isCurrent(account, userId, generation)) {
                        return;
                    }
                    for (int i = 0; i < ids.size(); i++) {
                        cache.messages.remove(ids.get(i));
                    }
                }
            } catch (Throwable e) {
                FileLog.e("onOfficialDialogsLoaded", e);
            }
        });
    }

    public void deleteExistingDialogs(long userId, List<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        try {
            DeletedDialogDao dao = AyuData.getDeletedDialogDao();
            if (dao != null) {
                dao.deleteExisting(userId, dialogIds);
            }
        } catch (Throwable e) {
            FileLog.e("deleteExistingDialogs", e);
        }
    }

    public void deleteDialogRecord(int account, long userId, long dialogId) {
        AccountCache<MessageObject> cache = cacheForAccount(account);
        synchronized (cache) {
            cache.bind(UserConfig.getInstance(account).clientUserId);
            if (cache.userId == userId) {
                cache.messages.remove(dialogId);
                cache.topics.remove(dialogId);
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                DeletedDialogDao dao = AyuData.getDeletedDialogDao();
                if (dao != null) {
                    dao.delete(userId, dialogId);
                }
            } catch (Throwable e) {
                FileLog.e("deleteDialogRecord", e);
            }
        });
    }

    public void deleteDialogRecord(long userId, long dialogId) {
        deleteDialogRecord(UserConfig.selectedAccount, userId, dialogId);
    }

    private static boolean isEmpty(com.radolyn.ayugram.database.entities.AyuMessageBase message) {
        return TextUtils.isEmpty(message.text) && TextUtils.isEmpty(message.mediaPath) && message.documentSerialized == null;
    }
}
