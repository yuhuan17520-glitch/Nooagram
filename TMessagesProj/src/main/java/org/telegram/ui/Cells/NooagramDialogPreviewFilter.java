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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import tw.nekomimi.nekogram.filters.AyuFilter;

final class NooagramDialogPreviewFilter {
    private static final int MAX_STATES = 256;
    private static final long REQUEST_TIMEOUT_MS = 30000;
    // These collections and request transitions are confined to the UI thread.
    private static final Map<NooagramPreviewSearch.Key, State> STATES = new LinkedHashMap<>(64, 0.75f, true);
    private static final Account[] ACCOUNTS = new Account[UserConfig.MAX_ACCOUNT_COUNT];
    private static long nextGeneration;

    private NooagramDialogPreviewFilter() {}

    static boolean isHidden(int account, long dialogId, MessageObject message) {
        return message != null && (
                AyuFilter.shouldHideIgnoredBlockedMessages()
                        && ChatObject.isMegagroup(MessagesController.getInstance(account).getChat(-dialogId))
                        && AyuFilter.isIgnoredBlockedMessage(message)
                || AyuFilter.shouldHideFilteredMessages() && AyuFilter.isFiltered(message, null));
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
            STATES.put(key, state);
            if (STATES.size() > MAX_STATES) {
                Iterator<State> iterator = STATES.values().iterator();
                iterator.next().dispose();
                iterator.remove();
            }
        }
        state.watch(cell);
        if (state.replacement != null && !eligible(state, state.replacement)) {
            state.replacement = null;
        }
        long token = state.search.begin(SystemClock.elapsedRealtime());
        if (token != 0) {
            loadLocal(state, token, 0, 0, 0);
        }
        return state.replacement;
    }

    private static boolean eligible(State state, MessageObject candidate) {
        return candidate != null && candidate.messageOwner != null
                && !(candidate.messageOwner instanceof TLRPC.TL_messageEmpty)
                && candidate.currentAccount == state.key.account
                && candidate.getDialogId() == state.key.dialogId
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
        // Complete shared state even if every waiting cell was rebound or destroyed.
        refresh(state);
        if (failed && state.search.shouldRetryAutomatically()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (state.sessionCurrent() && STATES.get(state.key) == state) {
                    refresh(state);
                }
            }, NooagramPreviewSearch.RETRY_MS);
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

    private static final class Account implements NotificationCenter.NotificationCenterDelegate {
        final int account;
        long userId;
        int loginTime;
        long generation = ++nextGeneration;

        Account(int account) {
            this.account = account;
        }

        void reset(boolean refresh) {
            generation = ++nextGeneration;
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
            reset(id != NotificationCenter.appDidLogout);
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
        int requestId;
        Runnable timeout;

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
