package org.telegram.ui.Cells;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.ConnectionsManager;
import android.util.Log;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

import tw.nekomimi.nekogram.filters.AyuFilter;

final class NooagramDialogPreviewFilter {
    private static final long EMPTY_RETRY_COOLDOWN_MS = 3000L;
    private static final int MAX_STATES = 256;
    private static final Map<Long, State> STATES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, State> eldest) {
            return size() > MAX_STATES;
        }
    };

    private NooagramDialogPreviewFilter() {}

    static boolean prepare(DialogCell cell, int account, long dialogId, MessageObject source) {
        State state = getState(account, dialogId, source.getId());
        if (BuildConfig.DEBUG) {
            Log.d("NooagramPreview", "prepare account=" + account
                    + " dialog=" + dialogId
                    + " source=" + source.getId()
                    + " replacement=" + (state.replacement == null ? null : state.replacement.getId())
                    + " loading=" + state.loading);
        }
        if (state.replacement != null
                && !isSameMessage(state.replacement, source)
                && !AyuFilter.isFiltered(state.replacement, null)) {
            return true;
        }

        boolean canRetry = state.lastQueryMessageId != source.getId()
                || System.currentTimeMillis() - state.lastQueryAt >= EMPTY_RETRY_COOLDOWN_MS;
        if (!state.loading && canRetry) {
            state.loading = true;
            state.lastQueryMessageId = source.getId();
            state.lastQueryAt = System.currentTimeMillis();
            state.token++;
            if (BuildConfig.DEBUG) {
                Log.d("NooagramPreview", "query account=" + account
                        + " dialog=" + dialogId
                        + " source=" + source.getId());
            }
            loadAsync(cell, account, dialogId, source.getId(), state.token);
        }
        return false;
    }

    static MessageObject getReplacement(int account, long dialogId, MessageObject source) {
        State state = getState(account, dialogId, source.getId());
        if (BuildConfig.DEBUG) {
            Log.d("NooagramPreview", "get account=" + account
                    + " dialog=" + dialogId
                    + " source=" + source.getId()
                    + " replacement=" + (state.replacement == null ? null : state.replacement.getId()));
        }
        if (state.replacement != null
                && !isSameMessage(state.replacement, source)
                && !AyuFilter.isFiltered(state.replacement, null)) {
            return state.replacement;
        }
        return null;
    }

    private static State getState(int account, long dialogId, int messageId) {
        long key = stateKey(account, dialogId);
        synchronized (STATES) {
            State state = STATES.get(key);
            if (state == null || state.sourceMessageId != messageId) {
                state = new State(messageId);
                STATES.put(key, state);
            }
            return state;
        }
    }

    private static long stateKey(int account, long dialogId) {
        return ((long) account << 48) ^ (dialogId & 0xFFFFFFFFFFFFL);
    }

    private static boolean isSameMessage(MessageObject left, MessageObject right) {
        return left.getId() == right.getId() && left.getDialogId() == right.getDialogId();
    }

    private static void loadAsync(DialogCell cell, int account, long dialogId,
                                  int sourceMessageId, long token) {
        WeakReference<DialogCell> cellRef = new WeakReference<>(cell);
        Utilities.globalQueue.postRunnable(() -> {
            MessageObject result = findLastUnfilteredMessage(account, dialogId);
            AndroidUtilities.runOnUIThread(() -> {
                DialogCell target = cellRef.get();
                if (target == null) {
                    notifyRegexFiltersUpdated(account);
                    return;
                }
                if (target.getDialogId() != dialogId || target.getCurrentAccount() != account) {
                    notifyRegexFiltersUpdated(account);
                    return;
                }
                State state;
                boolean requestBackfill = false;
                synchronized (STATES) {
                    state = STATES.get(stateKey(account, dialogId));
                    if (state == null || state.sourceMessageId != sourceMessageId || state.token != token) {
                        if (BuildConfig.DEBUG) {
                            Log.d("NooagramPreview", "stale callback account=" + account
                                    + " dialog=" + dialogId
                                    + " source=" + sourceMessageId);
                        }
                        return;
                    }
                    state.loading = false;
                    state.replacement = result;
                    requestBackfill = result == null && !state.backfillRequested;
                    if (requestBackfill) {
                        state.backfillRequested = true;
                    }
                }
                if (requestBackfill) {
                    requestHistoryBackfill(account, dialogId);
                }
                if (BuildConfig.DEBUG) {
                    Log.d("NooagramPreview", "callback account=" + account
                            + " dialog=" + dialogId
                            + " source=" + sourceMessageId
                            + " result=" + (result == null ? null : result.getId()));
                }
                if (result != null) {
                    target.applyFilteredPreviewReplacement(result);
                }
            });
        });
    }

    private static void notifyRegexFiltersUpdated(int account) {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.regexFiltersUpdated);
    }

    private static void requestHistoryBackfill(int account, long dialogId) {
        int classGuid = ConnectionsManager.generateClassGuid();
        MessagesController.getInstance(account).loadMessages(
                dialogId,
                0,
                false,
                80,
                0,
                0,
                false,
                0,
                classGuid,
                MessagesController.LOAD_BACKWARD,
                0,
                0,
                0,
                1,
                0,
                0,
                0,
                true,
                0,
                false
        );
        if (BuildConfig.DEBUG) {
            Log.d("NooagramPreview", "backfill requested account=" + account + " dialog=" + dialogId);
        }
    }

    private static MessageObject findLastUnfilteredMessage(int account, long dialogId) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        SQLiteCursor cursor = null;
        NativeByteBuffer data = null;
        try {
            SQLiteDatabase database = storage.getDatabase();
            cursor = database.queryFinalized(
                    "SELECT data, send_state, mid, date FROM messages_v2 "
                            + "WHERE uid = ? ORDER BY date DESC, mid DESC", dialogId);
            while (cursor.next()) {
                data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }

                TLRPC.Message raw = TLRPC.Message.TLdeserialize(
                        data, data.readInt32(false), false);
                if (raw == null) {
                    data.reuse();
                    data = null;
                    continue;
                }

                raw.send_state = cursor.intValue(1);
                raw.id = cursor.intValue(2);
                raw.date = cursor.intValue(3);
                raw.dialog_id = dialogId;
                data.reuse();
                data = null;

                MessageObject result = new MessageObject(account, raw, false, false);
                if (!AyuFilter.isFiltered(result, null)) {
                    if (BuildConfig.DEBUG) {
                        Log.d("NooagramPreview", "found account=" + account
                                + " dialog=" + dialogId
                                + " message=" + result.getId()
                                + " date=" + raw.date);
                    }
                    MessagesController controller = MessagesController.getInstance(account);
                    if (controller.getUser(result.getSenderId()) == null) {
                        TLRPC.User user = storage.getUser(result.getSenderId());
                        if (user != null) {
                            controller.putUser(user, true, false);
                        }
                    }
                    return result;
                }
            }
        } catch (Throwable throwable) {
            Log.e("NooagramPreview", "cannot load unfiltered dialog preview", throwable);
        } finally {
            if (data != null) {
                try {
                    data.reuse();
                } catch (Throwable ignored) {
                }
            }
            if (cursor != null) {
                try {
                    cursor.dispose();
                } catch (Throwable ignored) {
                }
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d("NooagramPreview", "no-result account=" + account
                    + " dialog=" + dialogId);
        }
        return null;
    }

    private static final class State {
        final int sourceMessageId;
        MessageObject replacement;
        boolean loading;
        boolean backfillRequested;
        long token;
        int lastQueryMessageId;
        long lastQueryAt;

        State(int sourceMessageId) {
            this.sourceMessageId = sourceMessageId;
        }
    }
}
