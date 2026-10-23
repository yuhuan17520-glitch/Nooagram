package org.telegram.ui.Cells;

import android.os.SystemClock;
import android.util.Log;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteException;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tw.nekomimi.nekogram.filters.AyuFilter;

final class NooagramDialogPreviewFilter {
    private static final int MAX_STATES = 256;
    private static final int MAX_REMEMBERED_MESSAGES = 4;
    static final int SEARCHING = 0;
    static final int UNAVAILABLE = 1;
    static final int EXHAUSTED = 2;
    private static final long REQUEST_TIMEOUT_MS = 30000;
    // These collections and request transitions are confined to the UI thread.
    private static final Map<NooagramPreviewSearch.Key, State> STATES = new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<NooagramPreviewSearch.Key, ArrayList<MessageObject>> VISIBLE_PREVIEWS =
            new LinkedHashMap<>(64, 0.75f, true);
    private static final Account[] ACCOUNTS = new Account[UserConfig.MAX_ACCOUNT_COUNT];
    private static long nextGeneration;

    private NooagramDialogPreviewFilter() {}

    static boolean isHidden(int account, long dialogId, MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        if (message.messageOwner.hide) {
            return true;
        }
        // A chat's temporary "show filtered" flag must not bypass dialog filtering.
        boolean skipFiltering = message.skipAyuFiltering;
        try {
            message.skipAyuFiltering = false;
            return AyuFilter.shouldHideIgnoredBlockedMessages()
                        && ChatObject.isMegagroup(MessagesController.getInstance(account).getChat(-dialogId))
                        && AyuFilter.isIgnoredBlockedMessage(message)
                    || AyuFilter.shouldHideFilteredMessages() && AyuFilter.isFiltered(message, null);
        } finally {
            message.skipAyuFiltering = skipFiltering;
        }
    }

    static void rememberVisible(int account, long dialogId, MessageObject message) {
        Account owner = account(account);
        if (owner.userId == 0 || !validMessage(account, dialogId, message) || isHidden(account, dialogId, message)) {
            return;
        }
        NooagramPreviewSearch.Key key = new NooagramPreviewSearch.Key(account, owner.userId, owner.generation, dialogId);
        remember(key, message);
        // A new visible top message makes an older filtered-source request obsolete.
        State previous = STATES.remove(key);
        if (previous != null) {
            previous.dispose();
        }
    }

    private static boolean validMessage(int account, long dialogId, MessageObject message) {
        return message != null && message.messageOwner != null
                && !(message.messageOwner instanceof TLRPC.TL_messageEmpty)
                && message.messageOwner.date > 0 && message.getId() != Integer.MAX_VALUE
                && message.currentAccount == account && message.getDialogId() == dialogId;
    }

    private static void remember(NooagramPreviewSearch.Key key, MessageObject message) {
        ArrayList<MessageObject> remembered = VISIBLE_PREVIEWS.get(key);
        if (remembered == null) {
            remembered = new ArrayList<>();
            VISIBLE_PREVIEWS.put(key, remembered);
        }
        remembered.removeIf(previous -> previous.getId() == message.getId());
        remembered.add(message);
        remembered.sort((left, right) -> {
            int date = Integer.compare(right.messageOwner.date, left.messageOwner.date);
            return date != 0 ? date : Integer.compare(right.getId(), left.getId());
        });
        while (remembered.size() > MAX_REMEMBERED_MESSAGES) {
            remembered.remove(remembered.size() - 1);
        }
        if (VISIBLE_PREVIEWS.size() > MAX_STATES) {
            Iterator<NooagramPreviewSearch.Key> iterator = VISIBLE_PREVIEWS.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static void forget(NooagramPreviewSearch.Key key, int messageId) {
        ArrayList<MessageObject> remembered = VISIBLE_PREVIEWS.get(key);
        if (remembered != null) {
            remembered.removeIf(message -> message.getId() == messageId);
            if (remembered.isEmpty()) {
                VISIBLE_PREVIEWS.remove(key);
            }
        }
    }

    private static MessageObject rememberedPreview(State state) {
        ArrayList<MessageObject> remembered = VISIBLE_PREVIEWS.get(state.key);
        if (remembered == null) {
            return null;
        }
        remembered.removeIf(message -> !validMessage(state.key.account, state.key.dialogId, message)
                || isHidden(state.key.account, state.key.dialogId, message));
        return NooagramPreviewSearch.latestEligible(remembered, message -> eligible(state, message));
    }

    static int getStatus(int account, long dialogId) {
        Account owner = account(account);
        State state = STATES.get(new NooagramPreviewSearch.Key(account, owner.userId, owner.generation, dialogId));
        return state != null ? state.status : SEARCHING;
    }

    static MessageObject resolve(DialogCell cell, int account, long dialogId, MessageObject source) {
        Account owner = account(account);
        if (owner.userId == 0 || source == null || source.messageOwner == null) {
            return null;
        }
        NooagramPreviewSearch.Key key = new NooagramPreviewSearch.Key(account, owner.userId, owner.generation, dialogId);
        State state = STATES.get(key);
        if (state == null || !state.search.matches(source.getId(), source.messageOwner.date, source.messageOwner.edit_date)) {
            if (state != null) {
                state.dispose();
            }
            state = new State(key, owner.loginTime, source);
            state.replacement = rememberedPreview(state);
            STATES.put(key, state);
            if (STATES.size() > MAX_STATES) {
                Iterator<State> iterator = STATES.values().iterator();
                iterator.next().dispose();
                iterator.remove();
            }
        }
        state.watch(cell);
        if (state.replacement != null && !eligible(state, state.replacement)) {
            forget(state.key, state.replacement.getId());
            state.replacement = rememberedPreview(state);
            state.clearRequest(true);
            state.cancelScheduledRefresh();
            state.search.invalidate();
        }
        long token = state.search.begin(SystemClock.elapsedRealtime());
        if (token != 0) {
            state.status = SEARCHING;
            state.cancelScheduledRefresh();
            loadLocal(state, token, 0, 0, 0);
        }
        return state.replacement;
    }

    private static boolean eligible(State state, MessageObject candidate) {
        return validMessage(state.key.account, state.key.dialogId, candidate)
                && candidate.getId() != state.search.sourceId
                && !isHidden(state.key.account, state.key.dialogId, candidate);
    }

    private static Account account(int account) {
        UserConfig config = UserConfig.getInstance(account);
        Account owner = ACCOUNTS[account];
        if (owner == null) {
            owner = new Account(account);
            ACCOUNTS[account] = owner;
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(owner, NotificationCenter.appDidLogout);
            center.addObserver(owner, NotificationCenter.regexFiltersUpdated);
            center.addObserver(owner, NotificationCenter.blockedUsersDidLoad);
            center.addObserver(owner, NotificationCenter.messagesDeleted);
            center.addObserver(owner, NotificationCenter.replaceMessagesObjects);
        }
        if (owner.userId != config.getClientUserId() || owner.loginTime != config.loginTime) {
            owner.reset(false);
            owner.userId = config.getClientUserId();
            owner.loginTime = config.loginTime;
        }
        return owner;
    }

    private static boolean current(State state, long token) {
        return state.sessionCurrent() && STATES.get(state.key) == state && state.search.accepts(token);
    }

    private static void loadLocal(State state, long token, int pageIndex, int beforeDate, int beforeId) {
        MessagesStorage storage = MessagesStorage.getInstance(state.key.account);
        storage.getStorageQueue().postRunnable(() -> {
            if (!state.sessionCurrent()) {
                return;
            }
            Page page = new Page();
            SQLiteCursor cursor = null;
            try {
                String select = "SELECT data, send_state, mid, date, read_state, replydata FROM messages_v2 WHERE uid = ?";
                if (pageIndex == 0) {
                    cursor = storage.getDatabase().queryFinalized(select
                            + " ORDER BY date DESC, mid DESC LIMIT ?", state.key.dialogId, NooagramPreviewSearch.PAGE_SIZE);
                } else {
                    cursor = storage.getDatabase().queryFinalized(select
                            + " AND (date < ? OR (date = ? AND mid < ?)) ORDER BY date DESC, mid DESC LIMIT ?",
                            state.key.dialogId, beforeDate, beforeDate, beforeId, NooagramPreviewSearch.PAGE_SIZE);
                }
                ArrayList<Long> users = new ArrayList<>();
                ArrayList<Long> chats = new ArrayList<>();
                while (cursor.next()) {
                    ++page.rows;
                    page.oldestId = cursor.intValue(2);
                    page.oldestDate = cursor.intValue(3);
                    TLRPC.Message message = readMessage(cursor, state);
                    if (message != null) {
                        page.messages.add(message);
                        MessagesStorage.addUsersAndChatsFromMessage(message, users, chats, null);
                        if (message.replyMessage != null) {
                            MessagesStorage.addUsersAndChatsFromMessage(message.replyMessage, users, chats, null);
                        }
                    }
                }
                page.users = storage.getUsers(users);
                page.chats = storage.getChats(chats);
            } catch (Exception error) {
                page.failed = true;
                Log.e("NooagramPreview", "Cannot read preview history", error);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!current(state, token)) {
                    return;
                }
                if (page.failed) {
                    finish(state, token, null, true);
                    return;
                }
                MessageObject result;
                try {
                    result = findCandidate(state, page);
                } catch (Exception error) {
                    Log.e("NooagramPreview", "Cannot evaluate preview history", error);
                    finish(state, token, null, true);
                    return;
                }
                if (result != null) {
                    finish(state, token, result, false);
                } else if (page.rows == NooagramPreviewSearch.PAGE_SIZE && pageIndex + 1 < NooagramPreviewSearch.MAX_LOCAL_PAGES) {
                    loadLocal(state, token, pageIndex + 1, page.oldestDate, page.oldestId);
                } else if (state.replacement != null) {
                    recheckReplacement(state, token);
                } else if (!DialogObject.isEncryptedDialog(state.key.dialogId) && state.search.canLoadHistory()) {
                    if (page.rows == NooagramPreviewSearch.PAGE_SIZE && pageIndex + 1 == NooagramPreviewSearch.MAX_LOCAL_PAGES) {
                        state.search.localWindowExhausted(token, page.oldestId);
                    }
                    requestHistory(state, token);
                } else {
                    finish(state, token, null, false);
                }
            });
        });
    }

    private static MessageObject findCandidate(State state, Page page) {
        MessagesController controller = MessagesController.getInstance(state.key.account);
        controller.putUsers(page.users, true);
        controller.putChats(page.chats, true);
        page.messages.sort((left, right) -> {
            int date = Integer.compare(right.date, left.date);
            return date != 0 ? date : Integer.compare(right.id, left.id);
        });
        ArrayList<MessageObject> candidates = new ArrayList<>();
        for (TLRPC.Message raw : page.messages) {
            candidates.add(new MessageObject(state.key.account, raw, false, false));
        }
        return NooagramPreviewSearch.latestEligible(candidates, candidate -> eligible(state, candidate));
    }

    private static void recheckReplacement(State state, long token) {
        ArrayList<TLRPC.Message> messages = new ArrayList<>();
        messages.add(state.replacement.messageOwner);
        MessagesStorage storage = MessagesStorage.getInstance(state.key.account);
        storage.getStorageQueue().postRunnable(() -> {
            if (!state.sessionCurrent()) {
                return;
            }
            Page page = readStoredHistory(storage, state, messages, false);
            AndroidUtilities.runOnUIThread(() -> {
                if (!current(state, token)) {
                    return;
                }
                if (page.failed) {
                    finish(state, token, null, true);
                    return;
                }
                MessageObject result;
                try {
                    result = findCandidate(state, page);
                } catch (Exception error) {
                    Log.e("NooagramPreview", "Cannot recheck preview replacement", error);
                    finish(state, token, null, true);
                    return;
                }
                if (result == null) {
                    forget(state.key, state.replacement.getId());
                }
                state.replacement = result;
                if (result == null && !DialogObject.isEncryptedDialog(state.key.dialogId) && state.search.canLoadHistory()) {
                    requestHistory(state, token);
                } else {
                    finish(state, token, result, false);
                }
            });
        });
    }

    private static TLRPC.Message readMessage(SQLiteCursor cursor, State state) throws SQLiteException {
        NativeByteBuffer data = cursor.byteBufferValue(0);
        if (data == null) {
            return null;
        }
        TLRPC.Message raw;
        try {
            raw = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            if (raw == null) {
                return null;
            }
            raw.readAttachPath(data, state.key.userId);
            raw.send_state = cursor.intValue(1);
            raw.id = cursor.intValue(2);
            raw.date = cursor.intValue(3);
            raw.dialog_id = state.key.dialogId;
            MessageObject.setUnreadFlags(raw, cursor.intValue(4));
        } finally {
            data.reuse();
        }
        if (raw.reply_to != null && raw.reply_to.reply_to_msg_id != 0 && !cursor.isNull(5)) {
            NativeByteBuffer reply = cursor.byteBufferValue(5);
            if (reply != null) {
                try {
                    raw.replyMessage = TLRPC.Message.TLdeserialize(reply, reply.readInt32(false), false);
                } finally {
                    reply.reuse();
                }
            }
        }
        return raw;
    }

    private static void requestHistory(State state, long token) {
        try {
            sendHistoryRequest(state, token);
        } catch (Exception error) {
            Log.e("NooagramPreview", "Cannot request preview history", error);
            state.clearRequest(true);
            finish(state, token, null, true);
        }
    }

    private static void sendHistoryRequest(State state, long token) {
        MessagesController controller = MessagesController.getInstance(state.key.account);
        TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
        request.peer = controller.getInputPeer(state.key.dialogId);
        request.limit = NooagramPreviewSearch.PAGE_SIZE;
        request.offset_id = state.search.historyOffset();
        ConnectionsManager connections = ConnectionsManager.getInstance(state.key.account);
        state.requestId = connections.sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!current(state, token) || state.requestId == 0) {
                return;
            }
            state.clearRequest(false);
            if (error != null || !(response instanceof TLRPC.messages_Messages)
                    || response instanceof TLRPC.TL_messages_messagesNotModified) {
                finish(state, token, null, true);
                return;
            }
            TLRPC.messages_Messages messages = (TLRPC.messages_Messages) response;
            int count = messages.messages.size();
            if (count > NooagramPreviewSearch.PAGE_SIZE) {
                finish(state, token, null, true);
                return;
            }
            int oldest = 0;
            for (TLRPC.Message message : messages.messages) {
                if (message.id > 0 && (oldest == 0 || message.id < oldest)) {
                    oldest = message.id;
                }
                message.dialog_id = state.key.dialogId;
            }
            controller.removeDeletedMessagesFromArray(state.key.dialogId, messages.messages);
            controller.putUsers(messages.users, false);
            controller.putChats(messages.chats, false);
            storeHistory(state, token, messages, count, oldest, request.offset_id);
        }));
        state.timeout = () -> {
            if (current(state, token) && state.requestId != 0) {
                state.clearRequest(true);
                finish(state, token, null, true);
            }
        };
        AndroidUtilities.runOnUIThread(state.timeout, REQUEST_TIMEOUT_MS);
    }

    private static void storeHistory(State state, long token, TLRPC.messages_Messages messages,
                                     int count, int oldestId, int offsetId) {
        MessagesStorage storage = MessagesStorage.getInstance(state.key.account);
        storage.getStorageQueue().postRunnable(() -> {
            if (!state.sessionCurrent()) {
                return;
            }
            int inbox = storage.getDialogReadMaxSync(false, state.key.dialogId);
            int outbox = storage.getDialogReadMaxSync(true, state.key.dialogId);
            for (TLRPC.Message raw : messages.messages) {
                raw.unread = !(raw.action instanceof TLRPC.TL_messageActionChatMigrateTo)
                        && !(raw.action instanceof TLRPC.TL_messageActionChannelCreate)
                        && (raw.out ? outbox : inbox) < raw.id;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!current(state, token)) {
                    return;
                }
                // Queue both from the UI thread so logout cleanup cannot interleave them.
                storage.putMessages(messages, state.key.dialogId, MessagesController.LOAD_BACKWARD, offsetId, false, 0, 0);
                storage.getStorageQueue().postRunnable(() -> {
                    if (!state.sessionCurrent()) {
                        return;
                    }
                    Page stored = readStoredHistory(storage, state, messages.messages, true);
                    stored.users = messages.users;
                    stored.chats = messages.chats;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!current(state, token)) {
                            return;
                        }
                        if (stored.failed) {
                            finish(state, token, null, true);
                            return;
                        }
                        MessageObject result;
                        try {
                            result = findCandidate(state, stored);
                        } catch (Exception error) {
                            Log.e("NooagramPreview", "Cannot evaluate stored preview history", error);
                            finish(state, token, null, true);
                            return;
                        }
                        state.search.historyStored(token, count, oldestId);
                        if (result != null) {
                            finish(state, token, result, false);
                        } else if (state.search.canLoadHistory()) {
                            requestHistory(state, token);
                        } else {
                            finish(state, token, null, false);
                        }
                    });
                });
            });
        });
    }

    // Read the fetched page itself after the write, including rows outside the local scan cap.
    private static Page readStoredHistory(MessagesStorage storage, State state, ArrayList<TLRPC.Message> messages, boolean requireAll) {
        Page page = new Page();
        try {
            for (TLRPC.Message message : messages) {
                if (message instanceof TLRPC.TL_messageEmpty) {
                    continue;
                }
                SQLiteCursor cursor = storage.getDatabase().queryFinalized(
                        "SELECT data, send_state, mid, date, read_state, replydata FROM messages_v2 WHERE uid = ? AND mid = ? LIMIT 1",
                        state.key.dialogId, message.id);
                try {
                    TLRPC.Message raw = cursor.next() ? readMessage(cursor, state) : null;
                    if (raw == null) {
                        if (requireAll) {
                            page.failed = true;
                            break;
                        }
                        continue;
                    }
                    page.messages.add(raw);
                } finally {
                    cursor.dispose();
                }
            }
        } catch (Exception error) {
            page.failed = true;
            Log.e("NooagramPreview", "Cannot verify preview history write", error);
        }
        return page;
    }

    private static void finish(State state, long token, MessageObject result, boolean failed) {
        if (!current(state, token) || !state.search.complete(token, SystemClock.elapsedRealtime(), failed)) {
            return;
        }
        if (!failed || state.replacement == null || !eligible(state, state.replacement)) {
            state.replacement = result;
        }
        if (state.replacement != null) {
            remember(state.key, state.replacement);
        }
        state.status = failed ? UNAVAILABLE
                : state.replacement == null && state.search.needsHistoryContinuation() ? SEARCHING : EXHAUSTED;
        // Complete shared state even if every waiting cell was rebound or destroyed.
        refresh(state);
        boolean retryFailure = failed && state.search.shouldRetryAutomatically();
        boolean continueHistory = !failed && state.replacement == null && state.search.needsHistoryContinuation();
        if (retryFailure || continueHistory) {
            state.cancelScheduledRefresh();
            state.scheduledRefresh = () -> {
                state.scheduledRefresh = null;
                if (state.sessionCurrent() && STATES.get(state.key) == state && state.hasAttachedCells()) {
                    refresh(state);
                }
            };
            AndroidUtilities.runOnUIThread(state.scheduledRefresh,
                    retryFailure ? NooagramPreviewSearch.RETRY_MS : NooagramPreviewSearch.REFRESH_MS);
        }
    }

    private static void refresh(State state) {
        for (WeakReference<DialogCell> reference : new ArrayList<>(state.cells)) {
            DialogCell cell = reference.get();
            if (cell != null && cell.isAttachedToWindow() && cell.getCurrentAccount() == state.key.account
                    && cell.getDialogId() == state.key.dialogId) {
                cell.refreshFilteredPreview();
            }
        }
    }

    private static void invalidateEdited(int account, Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof List<?>)
                || args.length > 2 && Boolean.TRUE.equals(args[2])) {
            return;
        }
        long dialogId = ((Number) args[0]).longValue();
        Account owner = account(account);
        NooagramPreviewSearch.Key key = new NooagramPreviewSearch.Key(account, owner.userId, owner.generation, dialogId);
        Set<Integer> ids = new HashSet<>();
        for (Object replacement : (List<?>) args[1]) {
            if (!(replacement instanceof MessageObject)) {
                continue;
            }
            MessageObject message = (MessageObject) replacement;
            if (!validMessage(account, dialogId, message)) {
                continue;
            }
            ids.add(message.getId());
            ArrayList<MessageObject> remembered = VISIBLE_PREVIEWS.get(key);
            if (remembered != null && remembered.removeIf(previous -> previous.getId() == message.getId())) {
                if (!isHidden(account, dialogId, message)) {
                    remember(key, message);
                }
            }
        }
        State state = STATES.get(key);
        if (state != null && (ids.contains(state.search.sourceId)
                || state.replacement != null && ids.contains(state.replacement.getId()))) {
            STATES.remove(key);
            state.dispose();
            AndroidUtilities.runOnUIThread(() -> refresh(state));
        }
    }

    private static boolean isDeletedDialog(int account, long dialogId, long channelId) {
        return channelId != 0 ? dialogId == -channelId
                : !ChatObject.isChannel(MessagesController.getInstance(account).getChat(-dialogId));
    }

    private static boolean invalidateDeleted(int account, Object[] args) {
        if (args.length < 2 || !(args[0] instanceof List<?>) || !(args[1] instanceof Number)) {
            return false;
        }
        if (args.length > 2 && Boolean.TRUE.equals(args[2])) {
            return true; // Scheduled-message deletion does not affect the main dialog preview.
        }
        Set<Integer> ids = new HashSet<>();
        for (Object id : (List<?>) args[0]) {
            if (id instanceof Number) {
                ids.add(((Number) id).intValue());
            }
        }
        long channelId = ((Number) args[1]).longValue();
        Iterator<Map.Entry<NooagramPreviewSearch.Key, ArrayList<MessageObject>>> previews = VISIBLE_PREVIEWS.entrySet().iterator();
        while (previews.hasNext()) {
            Map.Entry<NooagramPreviewSearch.Key, ArrayList<MessageObject>> entry = previews.next();
            if (entry.getKey().account == account && isDeletedDialog(account, entry.getKey().dialogId, channelId)) {
                entry.getValue().removeIf(message -> ids.contains(message.getId()));
                if (entry.getValue().isEmpty()) {
                    previews.remove();
                }
            }
        }
        ArrayList<State> removed = new ArrayList<>();
        Iterator<State> states = STATES.values().iterator();
        while (states.hasNext()) {
            State state = states.next();
            if (state.key.account == account && isDeletedDialog(account, state.key.dialogId, channelId)
                    && (ids.contains(state.search.sourceId)
                    || state.replacement != null && ids.contains(state.replacement.getId()))) {
                state.dispose();
                removed.add(state);
                states.remove();
            }
        }
        if (!removed.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> {
                for (State state : removed) {
                    refresh(state);
                }
            });
        }
        return true;
    }

    private static final class Account implements NotificationCenter.NotificationCenterDelegate {
        final int account;
        long userId;
        int loginTime;
        long generation = ++nextGeneration;

        Account(int account) {
            this.account = account;
        }

        void reset(boolean refresh) {
            reset(refresh, refresh);
        }

        void reset(boolean refresh, boolean keepVisible) {
            generation = ++nextGeneration;
            ArrayList<MessageObject> remembered = new ArrayList<>();
            Iterator<Map.Entry<NooagramPreviewSearch.Key, ArrayList<MessageObject>>> previews = VISIBLE_PREVIEWS.entrySet().iterator();
            while (previews.hasNext()) {
                Map.Entry<NooagramPreviewSearch.Key, ArrayList<MessageObject>> entry = previews.next();
                if (entry.getKey().account == account) {
                    if (keepVisible && entry.getKey().userId == userId) {
                        remembered.addAll(entry.getValue());
                    }
                    previews.remove();
                }
            }
            for (MessageObject message : remembered) {
                if (validMessage(account, message.getDialogId(), message)
                        && !isHidden(account, message.getDialogId(), message)) {
                    remember(new NooagramPreviewSearch.Key(account, userId, generation, message.getDialogId()), message);
                }
            }
            ArrayList<State> removed = new ArrayList<>();
            Iterator<State> iterator = STATES.values().iterator();
            while (iterator.hasNext()) {
                State state = iterator.next();
                if (state.key.account == account) {
                    state.dispose();
                    removed.add(state);
                    iterator.remove();
                }
            }
            if (refresh) {
                AndroidUtilities.runOnUIThread(() -> {
                    for (State state : removed) {
                        refresh(state);
                    }
                });
            }
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id == NotificationCenter.replaceMessagesObjects) {
                invalidateEdited(account, args);
                return;
            }
            if (id == NotificationCenter.messagesDeleted && invalidateDeleted(account, args)) {
                return;
            }
            reset(id != NotificationCenter.appDidLogout,
                    id == NotificationCenter.regexFiltersUpdated || id == NotificationCenter.blockedUsersDidLoad);
            if (id == NotificationCenter.appDidLogout) {
                userId = 0;
            }
        }
    }

    private static final class State {
        final NooagramPreviewSearch.Key key;
        final NooagramPreviewSearch search;
        final int loginTime;
        final ArrayList<WeakReference<DialogCell>> cells = new ArrayList<>();
        volatile boolean disposed;
        MessageObject replacement;
        int status = SEARCHING;
        int requestId;
        Runnable timeout;
        Runnable scheduledRefresh;

        State(NooagramPreviewSearch.Key key, int loginTime, MessageObject source) {
            this.key = key;
            this.loginTime = loginTime;
            search = new NooagramPreviewSearch(source.getId(), source.messageOwner.date, source.messageOwner.edit_date);
        }

        boolean sessionCurrent() {
            UserConfig config = UserConfig.getInstance(key.account);
            return !disposed && config.getClientUserId() == key.userId && config.loginTime == loginTime;
        }

        void watch(DialogCell cell) {
            Iterator<WeakReference<DialogCell>> iterator = cells.iterator();
            while (iterator.hasNext()) {
                DialogCell other = iterator.next().get();
                if (other == cell) {
                    return;
                }
                if (other == null || other.getCurrentAccount() != key.account || other.getDialogId() != key.dialogId) {
                    iterator.remove();
                }
            }
            cells.add(new WeakReference<>(cell));
        }

        boolean hasAttachedCells() {
            for (WeakReference<DialogCell> reference : cells) {
                DialogCell cell = reference.get();
                if (cell != null && cell.isAttachedToWindow() && cell.getCurrentAccount() == key.account
                        && cell.getDialogId() == key.dialogId) {
                    return true;
                }
            }
            return false;
        }

        void cancelScheduledRefresh() {
            if (scheduledRefresh != null) {
                AndroidUtilities.cancelRunOnUIThread(scheduledRefresh);
                scheduledRefresh = null;
            }
        }

        void clearRequest(boolean cancel) {
            if (cancel && requestId != 0) {
                ConnectionsManager.getInstance(key.account).cancelRequest(requestId, true);
            }
            requestId = 0;
            if (timeout != null) {
                AndroidUtilities.cancelRunOnUIThread(timeout);
                timeout = null;
            }
        }

        void dispose() {
            disposed = true;
            search.invalidate();
            clearRequest(true);
            cancelScheduledRefresh();
            replacement = null;
        }
    }

    private static final class Page {
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        int rows;
        int oldestDate;
        int oldestId;
        boolean failed;
    }
}
