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
import java.util.HashMap;
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
    private static final int MAX_GROUP_CONTEXTS = 512;
    private static final int MAX_GROUP_MEMBERS = 64;
    private static final int GROUP_FETCH_LIMIT = 20;
    private static final int GROUP_FETCH_OFFSET = -10;
    private static final int GROUP_OLDER_PROBE_LIMIT = 10;
    private static final int TELEGRAM_ALBUM_MAX_SIZE = 10;
    private static final long GROUP_RETRY_MS = 30000;
    static final int SEARCHING = 0;
    static final int UNAVAILABLE = 1;
    static final int EXHAUSTED = 2;
    static final int VISIBLE = 0;
    static final int PENDING = 1;
    static final int HIDDEN = 2;
    private static final long REQUEST_TIMEOUT_MS = 30000;
    // These collections and request transitions are confined to the UI thread.
    private static final Map<NooagramPreviewSearch.Key, State> STATES = new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<NooagramPreviewSearch.Key, ArrayList<MessageObject>> VISIBLE_PREVIEWS =
            new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<GroupKey, GroupContext> GROUP_CONTEXTS =
            new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<GroupKey, GroupLoad> GROUP_LOADS = new LinkedHashMap<>();
    private static final Account[] ACCOUNTS = new Account[UserConfig.MAX_ACCOUNT_COUNT];
    private static long nextGeneration;
    private static boolean rulesPreloadPending;
    private static final ArrayList<WeakReference<DialogCell>> RULE_WAITERS = new ArrayList<>();

    private NooagramDialogPreviewFilter() {}

    static void preloadRules(DialogCell cell) {
        boolean found = false;
        Iterator<WeakReference<DialogCell>> iterator = RULE_WAITERS.iterator();
        while (iterator.hasNext()) {
            DialogCell other = iterator.next().get();
            if (other == null) {
                iterator.remove();
            } else if (other == cell) {
                found = true;
            }
        }
        if (!found) {
            RULE_WAITERS.add(new WeakReference<>(cell));
        }
        startRulesPreload();
    }

    private static void startRulesPreload() {
        if (rulesPreloadPending) {
            return;
        }
        rulesPreloadPending = true;
        AyuFilter.preloadAsync(() -> {
            rulesPreloadPending = false;
            if (!AyuFilter.isLoaded()) {
                startRulesPreload();
                return;
            }
            ArrayList<WeakReference<DialogCell>> waiting = new ArrayList<>(RULE_WAITERS);
            RULE_WAITERS.clear();
            for (WeakReference<DialogCell> reference : waiting) {
                DialogCell cell = reference.get();
                if (cell != null) {
                    cell.onFilterRulesLoaded();
                }
            }
        });
    }

    static boolean isHidden(int account, long dialogId, MessageObject message) {
        return getVisibility(account, dialogId, message) == HIDDEN;
    }

    static int getVisibility(int account, long dialogId, MessageObject message) {
        return getVisibility(account, dialogId, message,
                message != null ? groupContext(account, dialogId, message.getGroupId()) : null);
    }

    private static int getVisibility(int account, long dialogId, MessageObject message, GroupContext group) {
        if (message == null || message.messageOwner == null) {
            return VISIBLE;
        }
        if (message.messageOwner.hide) {
            return HIDDEN;
        }
        if ((AyuFilter.shouldHideFilteredMessages() || AyuFilter.shouldMaskFilteredMessages()) && !AyuFilter.isLoaded()) {
            return PENDING;
        }
        // A chat's temporary "show filtered" flag must not bypass dialog filtering.
        boolean skipFiltering = message.skipAyuFiltering;
        try {
            message.skipAyuFiltering = false;
            boolean blocked = AyuFilter.shouldHideIgnoredBlockedMessages()
                    && ChatObject.isMegagroup(MessagesController.getInstance(account).getChat(-dialogId))
                    && AyuFilter.isIgnoredBlockedMessage(message);
            if (blocked || !AyuFilter.shouldHideFilteredMessages()) {
                return blocked ? HIDDEN : VISIBLE;
            }
            long groupId = message.getGroupId();
            if (groupId == 0) {
                return AyuFilter.isFiltered(message, null) ? HIDDEN : VISIBLE;
            }
            if (group == null || !group.complete) {
                return PENDING;
            }
            return AyuFilter.isFiltered(message, group.messages.messages.size() > 1 ? group.messages : null)
                    ? HIDDEN : VISIBLE;
        } finally {
            message.skipAyuFiltering = skipFiltering;
        }
    }

    static int dialogPreviewDate(boolean filteredPreview, MessageObject message, int dialogDate) {
        return filteredPreview ? message != null && message.messageOwner != null ? message.messageOwner.date : 0
                : dialogDate;
    }

    private static GroupContext groupContext(int account, long dialogId, long groupId) {
        if (groupId == 0) {
            return null;
        }
        Account owner = ACCOUNTS[account];
        UserConfig config = UserConfig.getInstance(account);
        if (owner == null || owner.userId != config.getClientUserId() || owner.loginTime != config.loginTime) {
            return null;
        }
        GroupKey key = new GroupKey(account, owner.userId, owner.generation, dialogId, groupId);
        synchronized (GROUP_CONTEXTS) {
            return GROUP_CONTEXTS.get(key);
        }
    }

    private static void putGroupContext(GroupKey key, MessageObject.GroupedMessages messages, boolean complete) {
        synchronized (GROUP_CONTEXTS) {
            GroupContext previous = GROUP_CONTEXTS.get(key);
            if (previous == null || complete || !previous.complete) {
                GROUP_CONTEXTS.put(key, new GroupContext(messages, complete));
            }
            if (GROUP_CONTEXTS.size() > MAX_GROUP_CONTEXTS) {
                Iterator<GroupKey> iterator = GROUP_CONTEXTS.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static void clearGroupContexts(int account) {
        synchronized (GROUP_CONTEXTS) {
            GROUP_CONTEXTS.keySet().removeIf(key -> account < 0 || key.account == account);
        }
        ArrayList<GroupLoad> removed = new ArrayList<>();
        synchronized (GROUP_LOADS) {
            Iterator<Map.Entry<GroupKey, GroupLoad>> iterator = GROUP_LOADS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<GroupKey, GroupLoad> entry = iterator.next();
                if (account < 0 || entry.getKey().account == account) {
                    removed.add(entry.getValue());
                    iterator.remove();
                }
            }
        }
        for (GroupLoad load : removed) {
            load.cancel();
        }
    }

    static void ensureGroupContext(DialogCell cell, int account, long dialogId, MessageObject message) {
        ensureGroupContext(cell, null, account, dialogId, message);
    }

    private static void ensureGroupContext(State state, MessageObject message) {
        ensureGroupContext(null, state, state.key.account, state.key.dialogId, message);
    }

    private static void ensureGroupContext(DialogCell cell, State state, int account, long dialogId, MessageObject message) {
        if (message == null || message.getGroupId() == 0) {
            return;
        }
        Account owner = account(account);
        if (owner.userId == 0) {
            return;
        }
        GroupKey key = new GroupKey(account, owner.userId, owner.generation, dialogId, message.getGroupId());
        GroupContext context = groupContext(account, dialogId, message.getGroupId());
        if (context != null && context.complete) {
            return;
        }
        GroupLoad load;
        synchronized (GROUP_LOADS) {
            load = GROUP_LOADS.get(key);
            if (load == null) {
                load = new GroupLoad(key, owner.loginTime, message.getGroupId(), message.getId());
                GROUP_LOADS.put(key, load);
            }
        }
        load.watch(cell, state);
        if (load.requestId != 0 || load.started) {
            return;
        }
        if (SystemClock.elapsedRealtime() < load.retryAt) {
            return;
        }
        sendGroupRequest(load);
    }

    private static void sendGroupRequest(GroupLoad load) {
        if (GROUP_LOADS.get(load.key) != load) {
            return;
        }
        load.started = true;
        try {
            TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
            request.peer = MessagesController.getInstance(load.key.account).getInputPeer(load.key.dialogId);
            if (load.checkingOldest) {
                request.limit = GROUP_OLDER_PROBE_LIMIT;
                request.offset_id = load.oldestGroupId;
            } else {
                // Telegram albums contain at most ten messages. A centered 20-message window must
                // contain the whole album when the album is inside both returned page boundaries.
                request.limit = GROUP_FETCH_LIMIT;
                request.offset_id = load.sourceId;
                request.add_offset = GROUP_FETCH_OFFSET;
            }
            int requestToken = ++load.requestToken;
            load.requestId = ConnectionsManager.getInstance(load.key.account).sendRequest(request,
                    (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        if (load.requestToken == requestToken) {
                            finishGroupLoad(load, response, error);
                        }
                    }));
            load.timeout = () -> {
                load.clearRequest(true);
                finishGroupLoad(load, null, new Object());
            };
            AndroidUtilities.runOnUIThread(load.timeout, REQUEST_TIMEOUT_MS);
        } catch (Exception error) {
            Log.e("NooagramPreview", "Cannot request album context", error);
            scheduleGroupRetry(load);
        }
    }

    private static void finishGroupLoad(GroupLoad load, Object response, Object error) {
        if (!load.sessionCurrent() || GROUP_LOADS.get(load.key) != load) {
            return;
        }
        boolean checkingOldest = load.checkingOldest;
        load.checkingOldest = false;
        load.clearRequest(false);
        if (error != null || !(response instanceof TLRPC.messages_Messages)
                || response instanceof TLRPC.TL_messages_messagesNotModified) {
            scheduleGroupRetry(load);
            return;
        }
        TLRPC.messages_Messages history = (TLRPC.messages_Messages) response;
        if (checkingOldest) {
            for (TLRPC.Message message : history.messages) {
                if (message != null && message.grouped_id == load.groupId) {
                    scheduleGroupRetry(load);
                    return;
                }
            }
            MessageObject.GroupedMessages completeGroup = load.completeGroup;
            load.completeGroup = null;
            load.oldestGroupId = 0;
            if (completeGroup != null) {
                putGroupContext(load.key, completeGroup, true);
                load.cancel();
                removeGroupLoad(load.key, load);
                refreshGroupLoad(load);
            } else {
                scheduleGroupRetry(load);
            }
            return;
        }
        MessagesController controller = MessagesController.getInstance(load.key.account);
        controller.putUsers(history.users, false);
        controller.putChats(history.chats, false);
        ArrayList<TLRPC.Message> page = new ArrayList<>();
        for (TLRPC.Message message : history.messages) {
            if (message != null && !(message instanceof TLRPC.TL_messageEmpty)) {
                message.dialog_id = load.key.dialogId;
                page.add(message);
            }
        }
        MessageObject.GroupedMessages group = new MessageObject.GroupedMessages();
        group.groupId = load.groupId;
        boolean sourceFound = false;
        int newestGroupId = 0;
        int firstGroupIndex = -1;
        int lastGroupIndex = -1;
        for (int i = 0; i < page.size(); i++) {
            TLRPC.Message message = page.get(i);
            if (message.grouped_id != load.groupId) {
                continue;
            }
            if (firstGroupIndex == -1) {
                firstGroupIndex = i;
            }
            lastGroupIndex = i;
            MessageObject object = new MessageObject(load.key.account, message, false, false);
            group.messages.add(object);
            sourceFound |= object.getId() == load.sourceId;
            newestGroupId = Math.max(newestGroupId, object.getId());
        }
        TLRPC.Dialog dialog = controller.dialogs_dict.get(load.key.dialogId);
        boolean topBoundary = firstGroupIndex > 0
                || dialog != null && dialog.top_message != 0 && newestGroupId == dialog.top_message;
        boolean bottomBoundary = lastGroupIndex >= 0 && lastGroupIndex < page.size() - 1;
        // A short centered page is not proof of the oldest edge because add_offset may clamp.
        // Probe immediately older than the oldest known member when the group reaches page end.
        if (sourceFound && topBoundary && !bottomBoundary && group.messages.size() <= TELEGRAM_ALBUM_MAX_SIZE) {
            group.messages.sort((left, right) -> Integer.compare(left.getId(), right.getId()));
            load.completeGroup = group;
            load.oldestGroupId = group.messages.get(0).getId();
            load.checkingOldest = true;
            sendGroupRequest(load);
        } else if (sourceFound && topBoundary && bottomBoundary
                && group.messages.size() <= TELEGRAM_ALBUM_MAX_SIZE) {
            group.messages.sort((left, right) -> Integer.compare(left.getId(), right.getId()));
            putGroupContext(load.key, group, true);
            load.cancel();
            removeGroupLoad(load.key, load);
            refreshGroupLoad(load);
        } else {
            scheduleGroupRetry(load);
        }
    }

    private static void scheduleGroupRetry(GroupLoad load) {
        load.clearRequest(true);
        load.started = false;
        load.checkingOldest = false;
        load.completeGroup = null;
        load.oldestGroupId = 0;
        load.retryAt = SystemClock.elapsedRealtime() + GROUP_RETRY_MS;
        if (load.retry != null) {
            AndroidUtilities.cancelRunOnUIThread(load.retry);
        }
        load.retry = () -> {
            load.retry = null;
            if (GROUP_LOADS.get(load.key) != load || !load.sessionCurrent()) {
                return;
            }
            if (!load.hasWatchers()) {
                load.cancel();
                removeGroupLoad(load.key, load);
                return;
            }
            if (load.requestId != 0 || load.started) {
                return;
            }
            sendGroupRequest(load);
        };
        AndroidUtilities.runOnUIThread(load.retry, GROUP_RETRY_MS);
    }

    private static void refreshGroupLoad(GroupLoad load) {
        for (WeakReference<DialogCell> reference : new ArrayList<>(load.cells)) {
            DialogCell cell = reference.get();
            if (cell != null && cell.isAttachedToWindow() && cell.getCurrentAccount() == load.key.account
                    && cell.getDialogId() == load.key.dialogId) {
                cell.refreshFilteredPreview();
            }
        }
        for (State state : new ArrayList<>(load.states)) {
            if (STATES.get(state.key) == state) {
                STATES.remove(state.key);
                state.dispose();
                refresh(state);
            }
        }
    }

    private static void removeGroupLoad(GroupKey key, GroupLoad load) {
        synchronized (GROUP_LOADS) {
            if (GROUP_LOADS.get(key) == load) {
                GROUP_LOADS.remove(key);
            }
        }
    }

    static void rememberVisible(int account, long dialogId, MessageObject message) {
        Account owner = account(account);
        if (owner.userId == 0 || !validMessage(account, dialogId, message)
                || getVisibility(account, dialogId, message) != VISIBLE) {
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
        // A state from older rules drops all its callbacks (sessionCurrent), so it must be replaced, not reused.
        if (state == null || state.rulesVersion != AyuFilter.getRulesVersion()
                || !state.search.matches(source.getId(), source.messageOwner.date, source.messageOwner.edit_date)) {
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
        if (!validMessage(state.key.account, state.key.dialogId, candidate)
                || candidate.getId() == state.search.sourceId) {
            return false;
        }
        int visibility = getVisibility(state.key.account, state.key.dialogId, candidate);
        if (visibility == PENDING) {
            ensureGroupContext(state, candidate);
            return false;
        }
        return visibility == VISIBLE;
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
        return state.sessionCurrent() && STATES.get(state.key) == state
                && state.search.accepts(token, state.rulesVersion, AyuFilter.getRulesVersion());
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
                loadGroupMembers(storage, state, page, users, chats);
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
            evaluatePage(state, page);
            AndroidUtilities.runOnUIThread(() -> {
                if (!current(state, token)) {
                    return;
                }
                if (page.failed) {
                    finish(state, token, null, true);
                    return;
                }
                publishPage(state, page);
                MessageObject result = page.candidate;
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
        page.messages.sort((left, right) -> {
            int date = Integer.compare(right.date, left.date);
            return date != 0 ? date : Integer.compare(right.id, left.id);
        });
        for (TLRPC.Message raw : page.messages) {
            MessageObject candidate = new MessageObject(state.key.account, raw, page.userMap, page.chatMap, false, false);
            if (!validMessage(state.key.account, state.key.dialogId, candidate)
                    || candidate.getId() == state.search.sourceId) {
                continue;
            }
            GroupKey key = new GroupKey(state.key, candidate.getGroupId());
            GroupContext group;
            synchronized (GROUP_CONTEXTS) {
                group = GROUP_CONTEXTS.get(key);
            }
            if (group == null || !group.complete) {
                group = page.groups.get(candidate.getGroupId());
            }
            int visibility = getVisibility(state.key.account, state.key.dialogId, candidate, group);
            if (visibility == PENDING) {
                page.pendingGroups.add(candidate);
            } else if (visibility == VISIBLE) {
                return candidate;
            }
        }
        return null;
    }

    // Called on the storage queue. UI callbacks only publish this evaluated result.
    private static void evaluatePage(State state, Page page) {
        if (page.failed || !state.sessionCurrent()) {
            return;
        }
        try {
            for (TLRPC.User user : page.users) {
                page.userMap.put(user.id, user);
            }
            for (TLRPC.Chat chat : page.chats) {
                page.chatMap.put(chat.id, chat);
            }
            for (Map.Entry<Long, ArrayList<TLRPC.Message>> entry : page.groupMembers.entrySet()) {
                MessageObject.GroupedMessages group = new MessageObject.GroupedMessages();
                group.groupId = entry.getKey();
                for (TLRPC.Message raw : entry.getValue()) {
                    group.messages.add(new MessageObject(state.key.account, raw, page.userMap, page.chatMap, false, false));
                }
                page.groups.put(entry.getKey(), new GroupContext(group, false));
            }
            page.candidate = findCandidate(state, page);
        } catch (Exception error) {
            page.failed = true;
            Log.e("NooagramPreview", "Cannot evaluate preview history", error);
        }
    }

    private static void loadGroupMembers(MessagesStorage storage, State state, Page page,
                                         ArrayList<Long> users, ArrayList<Long> chats) throws SQLiteException {
        Set<Long> groupIds = new HashSet<>();
        for (TLRPC.Message message : page.messages) {
            if (message.grouped_id != 0) {
                groupIds.add(message.grouped_id);
            }
        }
        if (state.source.getGroupId() != 0) {
            groupIds.add(state.source.getGroupId());
        }
        for (Long groupId : groupIds) {
            ArrayList<TLRPC.Message> members = new ArrayList<>();
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized(
                        "SELECT data, send_state, mid, date, read_state, replydata FROM messages_v2 "
                                + "WHERE uid = ? AND group_id = ? ORDER BY date ASC, mid ASC LIMIT ?",
                        state.key.dialogId, groupId, MAX_GROUP_MEMBERS + 1);
                while (cursor.next()) {
                    TLRPC.Message message = readMessage(cursor, state);
                    if (message != null) {
                        members.add(message);
                        MessagesStorage.addUsersAndChatsFromMessage(message, users, chats, null);
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            if (members.size() <= MAX_GROUP_MEMBERS) {
                page.groupMembers.put(groupId, members);
            }
        }
    }

    private static void publishPage(State state, Page page) {
        MessagesController controller = MessagesController.getInstance(state.key.account);
        controller.putUsers(page.users, true);
        controller.putChats(page.chats, true);
        for (Map.Entry<Long, GroupContext> entry : page.groups.entrySet()) {
            putGroupContext(new GroupKey(state.key, entry.getKey()), entry.getValue().messages, false);
        }
        for (MessageObject pending : page.pendingGroups) {
            ensureGroupContext(state, pending);
        }
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
            evaluatePage(state, page);
            AndroidUtilities.runOnUIThread(() -> {
                if (!current(state, token)) {
                    return;
                }
                if (page.failed) {
                    finish(state, token, null, true);
                    return;
                }
                publishPage(state, page);
                MessageObject result = page.candidate;
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
                    evaluatePage(state, stored);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!current(state, token)) {
                            return;
                        }
                        if (stored.failed) {
                            finish(state, token, null, true);
                            return;
                        }
                        publishPage(state, stored);
                        MessageObject result = stored.candidate;
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
        ArrayList<Long> users = new ArrayList<>();
        ArrayList<Long> chats = new ArrayList<>();
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
                    MessagesStorage.addUsersAndChatsFromMessage(raw, users, chats, null);
                } finally {
                    cursor.dispose();
                }
            }
            if (!page.failed) {
                loadGroupMembers(storage, state, page, users, chats);
            }
            page.users = storage.getUsers(users);
            page.chats = storage.getChats(chats);
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
        clearGroupContexts(account);
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
        clearGroupContexts(account);
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
            clearGroupContexts(account);
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
        final MessageObject source;
        final int loginTime;
        final long rulesVersion;
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
            rulesVersion = AyuFilter.getRulesVersion();
            this.source = source;
            search = new NooagramPreviewSearch(source.getId(), source.messageOwner.date, source.messageOwner.edit_date);
        }

        boolean sessionCurrent() {
            UserConfig config = UserConfig.getInstance(key.account);
            return !disposed && config.getClientUserId() == key.userId && config.loginTime == loginTime
                    && rulesVersion == AyuFilter.getRulesVersion();
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
        final Map<Long, ArrayList<TLRPC.Message>> groupMembers = new LinkedHashMap<>();
        final Map<Long, GroupContext> groups = new LinkedHashMap<>();
        final ArrayList<MessageObject> pendingGroups = new ArrayList<>();
        final HashMap<Long, TLRPC.User> userMap = new HashMap<>();
        final HashMap<Long, TLRPC.Chat> chatMap = new HashMap<>();
        MessageObject candidate;
        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        int rows;
        int oldestDate;
        int oldestId;
        boolean failed;
    }

    private static final class GroupContext {
        final MessageObject.GroupedMessages messages;
        final boolean complete;

        GroupContext(MessageObject.GroupedMessages messages, boolean complete) {
            this.messages = messages;
            this.complete = complete;
        }
    }

    private static final class GroupKey {
        final int account;
        final long userId;
        final long generation;
        final long dialogId;
        final long groupId;

        GroupKey(int account, long userId, long generation, long dialogId, long groupId) {
            this.account = account;
            this.userId = userId;
            this.generation = generation;
            this.dialogId = dialogId;
            this.groupId = groupId;
        }

        GroupKey(NooagramPreviewSearch.Key key, long groupId) {
            this(key.account, key.userId, key.generation, key.dialogId, groupId);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof GroupKey)) {
                return false;
            }
            GroupKey key = (GroupKey) other;
            return account == key.account && userId == key.userId && generation == key.generation
                    && dialogId == key.dialogId && groupId == key.groupId;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(account);
            result = 31 * result + Long.hashCode(userId);
            result = 31 * result + Long.hashCode(generation);
            result = 31 * result + Long.hashCode(dialogId);
            return 31 * result + Long.hashCode(groupId);
        }
    }

    private static final class GroupLoad {
        final GroupKey key;
        final int loginTime;
        final long groupId;
        final int sourceId;
        final ArrayList<WeakReference<DialogCell>> cells = new ArrayList<>();
        final ArrayList<State> states = new ArrayList<>();
        int requestId;
        int requestToken;
        boolean checkingOldest;
        int oldestGroupId;
        MessageObject.GroupedMessages completeGroup;
        boolean started;
        long retryAt;
        Runnable timeout;
        Runnable retry;

        GroupLoad(GroupKey key, int loginTime, long groupId, int sourceId) {
            this.key = key;
            this.loginTime = loginTime;
            this.groupId = groupId;
            this.sourceId = sourceId;
        }

        boolean sessionCurrent() {
            UserConfig config = UserConfig.getInstance(key.account);
            return config.getClientUserId() == key.userId && config.loginTime == loginTime;
        }

        void watch(DialogCell cell, State state) {
            if (cell != null) {
                boolean found = false;
                for (WeakReference<DialogCell> reference : cells) {
                    if (reference.get() == cell) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    cells.add(new WeakReference<>(cell));
                }
            }
            if (state != null && !states.contains(state)) {
                states.add(state);
            }
        }

        boolean hasWatchers() {
            for (WeakReference<DialogCell> reference : cells) {
                DialogCell cell = reference.get();
                if (cell != null && cell.isAttachedToWindow() && cell.getCurrentAccount() == key.account
                        && cell.getDialogId() == key.dialogId) {
                    return true;
                }
            }
            for (State state : states) {
                if (STATES.get(state.key) == state && state.hasAttachedCells()) {
                    return true;
                }
            }
            return false;
        }

        void clearRequest(boolean cancel) {
            requestToken++;
            if (cancel && requestId != 0) {
                ConnectionsManager.getInstance(key.account).cancelRequest(requestId, true);
            }
            requestId = 0;
            if (timeout != null) {
                AndroidUtilities.cancelRunOnUIThread(timeout);
                timeout = null;
            }
        }

        void cancel() {
            clearRequest(true);
            if (retry != null) {
                AndroidUtilities.cancelRunOnUIThread(retry);
                retry = null;
            }
        }
    }
}
