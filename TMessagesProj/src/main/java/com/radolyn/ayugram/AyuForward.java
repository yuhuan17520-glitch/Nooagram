package com.radolyn.ayugram;

import android.text.TextUtils;
import android.util.LongSparseArray;

import com.radolyn.ayugram.controllers.AyuAttachments;
import com.radolyn.ayugram.controllers.AyuMapper;
import com.radolyn.ayugram.utils.AyuMessageUtils;
import com.radolyn.ayugram.utils.seq.AyuSequentialUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SendMessageChatArguments;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessageSuggestionParams;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;


public class AyuForward {

    private static final DispatchQueue forwardQueue = new DispatchQueue("AyuForwardQueue");
    private record ForwardKey(int account, long userId, int loginTime, long dialogId) {}
    private static final ConcurrentHashMap<ForwardKey, AyuForward> activeForwards = new ConcurrentHashMap<>();
    private volatile ForwardKey activeKey;
    private long nextTaskId;

    private static ForwardKey keyFor(int account, long dialogId) {
        UserConfig config = UserConfig.getInstance(account);
        return new ForwardKey(account, config.getClientUserId(), config.loginTime, dialogId);
    }

    private static final int STATUS_IDLE = 0;
    private static final int STATUS_LOADING = 1;
    private static final int STATUS_FORWARDING = 2;
    private static final int STATUS_REFRESH_MASK_BASE = 1 << 30;

    private static final class CaptionPayload {
        final String text;
        final ArrayList<TLRPC.MessageEntity> entities;

        CaptionPayload(String text, ArrayList<TLRPC.MessageEntity> entities) {
            this.text = text;
            this.entities = entities;
        }

        boolean hasText() {
            return !TextUtils.isEmpty(text);
        }
    }

    private static final class ForwardGroupInfo {
        final long groupToken;
        final int firstMessageId;
        int totalCount;
        int remainingCount;
        boolean captionAbove;
        int uniqueCaptionSourceId;
        boolean multipleCaptionSources;
        CaptionPayload uniqueCaption;

        ForwardGroupInfo(long groupToken, int firstMessageId) {
            this.groupToken = groupToken;
            this.firstMessageId = firstMessageId;
        }
    }

    private static final class ForwardGroupState {
        final Long groupToken;
        final boolean firstItem;
        final boolean finalItem;
        final boolean moveCaptionToFirstItem;
        final int uniqueCaptionSourceId;
        final boolean preserveOwnCaption;
        final boolean captionAbove;
        final CaptionPayload groupCaption;

        ForwardGroupState(Long groupToken, boolean firstItem, boolean finalItem, boolean moveCaptionToFirstItem, int uniqueCaptionSourceId, boolean preserveOwnCaption, boolean captionAbove, CaptionPayload groupCaption) {
            this.groupToken = groupToken;
            this.firstItem = firstItem;
            this.finalItem = finalItem;
            this.moveCaptionToFirstItem = moveCaptionToFirstItem;
            this.uniqueCaptionSourceId = uniqueCaptionSourceId;
            this.preserveOwnCaption = preserveOwnCaption;
            this.captionAbove = captionAbove;
            this.groupCaption = groupCaption;
        }
    }

    public interface CompletionCallback {
        void onComplete(boolean shouldContinue);
    }

    private final ChatActivity parentFragment;
    private final int currentAccount;
    private final MessageObject replyToTopMessage;
    private final String quickReplyShortcut;
    private final int quickReplyShortcutId;
    private final long monoForumPeerId;
    private final MessageSuggestionParams suggestionParams;
    private final AyuMapper mapper;

    private volatile long activeTaskId;
    private volatile long targetDialogId;
    private volatile int currentStatus = STATUS_IDLE;
    private volatile int totalMessages;
    private volatile int sentMessages;
    private volatile int skippedMessages;
    private volatile int currentChunkIndex;
    private volatile int totalChunks = 1;
    private volatile String currentStatusDetail;
    private volatile String lastFailureReason;
    private volatile boolean stopRequested;
    private volatile boolean disposed;
    private volatile boolean detached;

    private int statusUpdateVersion;

    public AyuForward(ChatActivity fragment, int account) {
        this(
                fragment,
                account,
                fragment != null ? fragment.getThreadMessage() : null,
                fragment != null ? fragment.quickReplyShortcut : null,
                fragment != null ? fragment.getQuickReplyId() : 0,
                fragment != null ? fragment.getSendMonoForumPeerId() : 0,
                fragment != null ? fragment.getSendMessageSuggestionParams() : null
        );
    }

    public AyuForward(int account, MessageObject replyToTopMessage, int chatMode, String quickReplyShortcut, int quickReplyShortcutId, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        this(null, account, replyToTopMessage, quickReplyShortcut, quickReplyShortcutId, monoForumPeerId, suggestionParams);
    }

    private AyuForward(ChatActivity fragment, int account, MessageObject replyToTopMessage, String quickReplyShortcut, int quickReplyShortcutId, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        this.parentFragment = fragment;
        this.currentAccount = account;
        this.replyToTopMessage = replyToTopMessage;
        this.quickReplyShortcut = quickReplyShortcut;
        this.quickReplyShortcutId = quickReplyShortcutId;
        this.monoForumPeerId = monoForumPeerId;
        this.suggestionParams = suggestionParams;
        this.mapper = new AyuMapper(account);
    }

    public static boolean isChatNoForwards(MessageObject messageObject) {
        return AyuMessageUtils.isChatNoForwards(messageObject);
    }

    public static boolean isPeerNoForwards(MessageObject messageObject) {
        return AyuMessageUtils.isPeerNoForwards(messageObject);
    }

    public static boolean canForwardAyuDeletedMessage(MessageObject messageObject) {
        return AyuMessageUtils.canForwardAyuDeletedMessage(messageObject);
    }

    public static boolean isUnforwardable(MessageObject messageObject) {
        return AyuMessageUtils.isUnforwardable(messageObject);
    }

    public static boolean isFullAyuForwardsNeeded(MessageObject messageObject) {
        return AyuMessageUtils.isFullAyuForwardsNeeded(messageObject);
    }

    public static boolean isFullAyuForwardsNeeded(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        return isFullAyuForwardsNeeded(messages.get(0));
    }

    public static boolean isAyuForwardNeeded(MessageObject messageObject) {
        return AyuMessageUtils.canForwardAyuDeletedMessage(messageObject)
                || AyuMessageUtils.isUnforwardable(messageObject);
    }

    public static boolean isAyuForwardNeeded(ArrayList<MessageObject> messages) {
        if (messages == null) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (isAyuForwardNeeded(messages.get(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isForwardingToDialog(long dialogId) {
        return isForwardingToDialog(UserConfig.selectedAccount, dialogId);
    }

    public static boolean isForwardingToDialog(int currentAccount, long dialogId) {
        AyuForward forward = activeForwards.get(keyFor(currentAccount, dialogId));
        return forward != null && forward.isForwarding();
    }

    public static boolean requestStopForDialog(long dialogId) {
        return requestStopForDialog(UserConfig.selectedAccount, dialogId);
    }

    public static boolean requestStopForDialog(int currentAccount, long dialogId) {
        AyuForward forward = activeForwards.get(keyFor(currentAccount, dialogId));
        if (forward != null) {
            forward.requestStop();
            return true;
        }
        return false;
    }

    public static void stopAccount(int account) {
        activeForwards.forEach((key, worker) -> {
            if (key.account == account && activeForwards.remove(key, worker)) worker.dispose();
        });
    }

    public static String getStatusForDialog(long dialogId) {
        return getStatusForDialog(UserConfig.selectedAccount, dialogId);
    }

    public static String getStatusForDialog(int currentAccount, long dialogId) {
        AyuForward forward = activeForwards.get(keyFor(currentAccount, dialogId));
        return forward != null ? forward.getForwardingStatus() : null;
    }


    public static String consumeFailureReasonForDialog(long dialogId) {
        AyuForward forward = activeForwards.get(keyFor(UserConfig.selectedAccount, dialogId));
        return forward != null ? forward.consumeLastFailureReason() : null;
    }

    public void dispose() {
        disposed = true;
        detached = true;
        stopRequested = true;
        synchronized (this) {
            if (activeTaskId == 0L) {
                clearRunStateLocked();
            }
        }
        lastFailureReason = null;
        notifyStatusChanged();
    }

    public void requestStop() {
        if (disposed || !isForwarding()) {
            return;
        }
        stopRequested = true;
        notifyStatusChanged();
    }

    public void detachFromFragment() {
        detached = true;
    }

    public boolean isForwarding() {
        return activeTaskId != 0L && !stopRequested;
    }

    public synchronized String consumeLastFailureReason() {
        String reason = lastFailureReason;
        lastFailureReason = null;
        return reason;
    }


    public String getForwardingStatus() {
        if (!isForwarding()) {
            return null;
        }
        if (currentStatus == STATUS_LOADING) {
            return TextUtils.isEmpty(currentStatusDetail)
                    ? LocaleController.getString(R.string.ForceForwardStatusPreparingMedia)
                    : currentStatusDetail;
        }

        String progress = LocaleController.formatString(R.string.ForceForwardStatusSentCount, sentMessages, totalMessages);
        if (totalChunks > 1) {
            progress = progress + " | " + LocaleController.formatString(R.string.ForceForwardStatusChunkCount, currentChunkIndex + 1, totalChunks);
        }

        if (currentStatus == STATUS_FORWARDING) {
            String label = TextUtils.isEmpty(currentStatusDetail)
                    ? LocaleController.getString(R.string.ForceForwardStatusForwarding)
                    : currentStatusDetail;
            return label + " " + progress;
        }

        return null;
    }

    public void forwardMessages(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, CompletionCallback onComplete) {
        forwardMessages(messagesToSend, targetDialogId, showUndo, hideCaption, notify, scheduleDate, payStars, 0, 1, onComplete);
    }

    public void forwardMessages(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, int chunkIndex, int chunkCount, CompletionCallback onComplete) {
        if (disposed || messagesToSend == null || messagesToSend.isEmpty() || (!detached && parentFragment != null && parentFragment.getParentActivity() == null)) {
            setFailureReason(null);
            runCompletion(onComplete, false);
            return;
        }

        ArrayList<MessageObject> request = new ArrayList<>(messagesToSend);
        long taskId = startRun(request.size(), targetDialogId, chunkIndex, chunkCount);
        if (taskId == 0) {
            runCompletion(onComplete, false);
            return;
        }
        forwardQueue.postRunnable(() -> executeForward(request, targetDialogId, hideCaption, notify, scheduleDate, payStars, taskId, onComplete));
    }

    private void executeForward(ArrayList<MessageObject> messages, long targetDialogId, boolean hideCaption, boolean notify, int scheduleDate, long payStars, long taskId, CompletionCallback onComplete) {
        LongSparseArray<ForwardGroupInfo> groupInfos = null;
        try {
            if (!ensureTaskCanProceed(taskId, onComplete)) {
                return;
            }

            boolean fullAyuForwardsNeeded = isFullAyuForwardsNeeded(messages);
            ArrayList<MessageObject> pendingDownloads = collectPendingDownloads(messages, fullAyuForwardsNeeded);
            if (!pendingDownloads.isEmpty()) {
                updateLoadingState(LocaleController.getString(R.string.ForceForwardStatusWaitingDownloads));
                AyuSequentialUtils.loadDocumentsSync(currentAccount, pendingDownloads, dispatchGuard());
                if (!ensureTaskCanProceed(taskId, onComplete)) {
                    return;
                }
            }

            groupInfos = new LongSparseArray<>();
            prepareGroupState(messages, groupInfos);
            totalMessages = messages.size();
            sentMessages = 0;
            skippedMessages = 0;
            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStarting));

            for (int i = 0; i < messages.size(); i++) {
                if (!ensureTaskCanProceed(taskId, onComplete)) {
                    return;
                }
                MessageObject messageObject = messages.get(i);
                if (messageObject == null || messageObject.messageOwner == null) {
                    onMessageSkipped();
                    continue;
                }

                if (!isPotentiallySendable(messageObject)) {
                    onMessageSkipped();
                    continue;
                }
                ForwardGroupState groupState = peekGroupState(messageObject, groupInfos);
                if (!forwardSingleMessage(messageObject, targetDialogId, hideCaption, notify, scheduleDate, payStars, groupState)) {
                    onMessageSkipped();
                    continue;
                }
                commitGroupState(messageObject, groupInfos);
                onMessageSent();
            }

            finishPendingGroups(groupInfos, scheduleDate);
            finishRun(taskId, true, onComplete);
        } catch (Exception e) {
            FileLog.e(e);
            failRun(taskId, LocaleController.getString(R.string.ForceForwardFailed), onComplete);
        } finally {
            finishPendingGroups(groupInfos, scheduleDate);
        }
    }

    private boolean isPotentiallySendable(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        String sourceText = resolveMessageText(messageObject);
        boolean mediaDownloadable = AyuMessageUtils.isMediaDownloadable(messageObject, false);
        if (TextUtils.isEmpty(sourceText) && !mediaDownloadable) {
            return false;
        }
        if (messageObject.isSticker() || messageObject.isAnimatedSticker()) {
            return messageObject.getDocument() != null;
        }
        if (!mediaDownloadable) {
            return true;
        }
        return (messageObject.getDocument() != null || MessageObject.getPhoto(messageObject.messageOwner) != null)
                && resolveExistingFile(messageObject) != null;
    }

    private boolean forwardSingleMessage(MessageObject messageObject, long targetDialogId, boolean hideCaption, boolean notify, int scheduleDate, long payStars, ForwardGroupState groupState) {
        String sourceText = resolveMessageText(messageObject);
        boolean mediaDownloadable = AyuMessageUtils.isMediaDownloadable(messageObject, false);
        if (TextUtils.isEmpty(sourceText) && !mediaDownloadable) {
            return false;
        }

        boolean hasMediaSpoilers = messageObject.hasMediaSpoilers() && !messageObject.isHiddenSensitive();
        boolean invertMedia = messageObject.messageOwner != null && messageObject.messageOwner.invert_media;
        TLRPC.Document document = messageObject.getDocument();
        TLRPC.Photo photo = MessageObject.getPhoto(messageObject.messageOwner);

        if (messageObject.isSticker() || messageObject.isAnimatedSticker()) {
            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStickerCopy));
            return sendStickerSync(messageObject, targetDialogId, notify, scheduleDate, payStars);
        }

        if (!mediaDownloadable) {
            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextCopy));
            return sendTextSync(messageObject, sourceText, targetDialogId, notify, scheduleDate, payStars);
        }

        boolean waitForMessage = groupState.groupToken == null || groupState.finalItem;
        boolean waitForUpload = groupState.groupToken == null;
        Long groupToken = groupState.groupToken;
        File file = resolveExistingFile(messageObject);

        if (document != null) {
            if (file == null) {
                return false;
            }

            updateForwardingState(groupToken != null
                    ? (messageObject.isVideo() || messageObject.isGif()
                    ? LocaleController.getString(R.string.ForceForwardStatusMediaGroup)
                    : LocaleController.getString(R.string.ForceForwardStatusDocumentGroup))
                    : (messageObject.isVideo() || messageObject.isGif()
                    ? LocaleController.getString(R.string.ForceForwardStatusMediaCopy)
                    : LocaleController.getString(R.string.ForceForwardStatusDocumentCopy)));

            SendMessagesHelper.SendMessageParams params = buildOriginalDocumentParams(
                    messageObject,
                    document,
                    file,
                    hideCaption ? null : sourceText,
                    hideCaption ? null : copyEntitiesOrNull(messageObject),
                    targetDialogId,
                    notify,
                    scheduleDate,
                    groupToken,
                    groupState.finalItem,
                    hasMediaSpoilers,
                    invertMedia
            );
            return dispatchParamsSync(params, file.getAbsolutePath(), targetDialogId, payStars, waitForMessage, waitForUpload);
        }

        if (photo != null) {
            if (file == null) {
                return false;
            }

            updateForwardingState(groupState.groupToken != null
                    ? LocaleController.getString(R.string.ForceForwardStatusMediaGroup)
                    : LocaleController.getString(R.string.ForceForwardStatusPhotoCopy));

            String caption = TextUtils.isEmpty(photo.caption) ? sourceText : photo.caption;
            if (hideCaption) {
                caption = null;
            }
            SendMessagesHelper.SendMessageParams params = buildOriginalPhotoParams(
                    photo,
                    file,
                    caption,
                    hideCaption ? null : copyEntitiesOrNull(messageObject),
                    targetDialogId,
                    notify,
                    scheduleDate,
                    groupToken,
                    groupState.finalItem,
                    hasMediaSpoilers,
                    invertMedia
            );
            return dispatchParamsSync(params, resolvePhotoUploadTrackingPath(params.photo), targetDialogId, payStars, waitForMessage, waitForUpload);
        }

        return true;
    }

    private boolean fallbackToText(MessageObject messageObject, String text, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextFallback));
        return sendTextSync(messageObject, text, targetDialogId, notify, scheduleDate, payStars);
    }

    private void prepareGroupState(ArrayList<MessageObject> messages, LongSparseArray<ForwardGroupInfo> groupInfos) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (!isPotentiallySendable(messageObject) || messageObject.getGroupId() == 0L) {
                continue;
            }
            long groupId = messageObject.getGroupId();
            ForwardGroupInfo info = groupInfos.get(groupId);
            if (info == null) {
                info = new ForwardGroupInfo(Utilities.random.nextLong(), messageObject.getId());
                groupInfos.put(groupId, info);
            }
            info.totalCount++;
            info.remainingCount++;
        }
    }

    private ForwardGroupState peekGroupState(MessageObject messageObject, LongSparseArray<ForwardGroupInfo> groupInfos) {
        if (messageObject == null) {
            return new ForwardGroupState(null, false, false, false, 0, true, false, null);
        }
        long groupId = messageObject.getGroupId();
        if (groupId == 0L) {
            return new ForwardGroupState(null, false, false, false, 0, true, false, null);
        }
        ForwardGroupInfo info = groupInfos.get(groupId);
        if (info == null) {
            return new ForwardGroupState(null, false, false, false, 0, true, false, null);
        }
        boolean finalItem = info.remainingCount <= 1;
        return new ForwardGroupState(info.groupToken, false, finalItem, false, 0, true, false, null);
    }

    private void commitGroupState(MessageObject messageObject, LongSparseArray<ForwardGroupInfo> groupInfos) {
        if (messageObject == null || messageObject.getGroupId() == 0L) {
            return;
        }
        ForwardGroupInfo info = groupInfos.get(messageObject.getGroupId());
        if (info == null) {
            return;
        }
        info.remainingCount--;
        if (info.remainingCount <= 0) {
            groupInfos.remove(messageObject.getGroupId());
        }
    }

    private void finishPendingGroups(LongSparseArray<ForwardGroupInfo> groupInfos, int scheduleDate) {
        if (groupInfos == null) {
            return;
        }
        for (int i = 0; i < groupInfos.size(); i++) {
            ForwardGroupInfo info = groupInfos.valueAt(i);
            if (info != null) {
                SendMessagesHelper.getInstance(currentAccount).finishDelayedGroup(info.groupToken, scheduleDate);
            }
        }
        groupInfos.clear();
    }

    private ArrayList<MessageObject> collectPendingDownloads(ArrayList<MessageObject> messages, boolean fullAyuForwardsNeeded) {
        ArrayList<MessageObject> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null
                    && (fullAyuForwardsNeeded || AyuMessageUtils.isUnforwardable(messageObject))
                    && AyuMessageUtils.isMediaDownloadable(messageObject, false)) {
                result.add(messageObject);
            }
        }
        return result;
    }

    private boolean hasUndownloadedAyuDeletedMedia(ArrayList<MessageObject> messages) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && messageObject.isAyuDeleted() && needsLocalCopy(messageObject) && !hasLocalCopy(messageObject)) {
                return true;
            }
        }
        return false;
    }

    private boolean needsLocalCopy(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        if (messageObject.type == MessageObject.TYPE_TEXT || messageObject.isAnimatedEmoji()) {
            return false;
        }
        if (messageObject.isPhoto() || messageObject.isVideo() || messageObject.isGif()) {
            return true;
        }
        if (messageObject.isAyuDeleted()) {
            return messageObject.getDocument() != null;
        }
        return messageObject.getDocument() != null && !messageObject.isSticker() && !messageObject.isAnimatedSticker();
    }

    private boolean hasLocalCopy(MessageObject messageObject) {
        return resolveExistingFile(messageObject) != null;
    }

    private String resolveMessageText(MessageObject messageObject) {
        CharSequence messageText = resolveMessageTextSequence(messageObject);
        return messageText != null ? messageText.toString() : "";
    }

    private CharSequence resolveMessageTextSequence(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        if (messageObject.type == MessageObject.TYPE_ANIMATED_STICKER
                || messageObject.type == MessageObject.TYPE_STICKER) {
            return null;
        }

        CharSequence messageText = ChatActivity.getMessageCaption(messageObject, null, null);
        if (messageText == null && messageObject.isPoll()) {
            try {
                TLRPC.TL_messageMediaPoll pollMedia = (TLRPC.TL_messageMediaPoll) messageObject.messageOwner.media;
                if (pollMedia != null && pollMedia.poll != null) {
                    StringBuilder pollText = new StringBuilder("Poll: ")
                            .append(pollMedia.poll.question.text)
                            .append('\n');
                    for (int i = 0; i < pollMedia.poll.answers.size(); i++) {
                        TLRPC.PollAnswer answer = pollMedia.poll.answers.get(i);
                        pollText.append("- ");
                        pollText.append(answer != null && answer.text != null ? answer.text.text : "");
                        pollText.append('\n');
                    }
                    messageText = pollText.toString();
                }
            } catch (Exception ignored) {
            }
        }
        if (messageText == null && MessageObject.isMediaEmpty(messageObject.messageOwner)) {
            messageText = ChatActivity.getMessageContent(messageObject, 0, false);
        }
        if (messageText != null && Emoji.fullyConsistsOfEmojis(messageText)) {
            messageText = messageObject.messageOwner.message;
        }
        if (messageObject.isRestrictedMessage) {
            messageText = null;
        } else if (messageObject.translated) {
            messageText = messageObject.messageOwner.message;
        }
        return messageText;
    }

    private String getTextFallback(String sourceText, String caption) {
        if (!TextUtils.isEmpty(sourceText)) {
            return sourceText;
        }
        return caption;
    }

    private CaptionPayload resolveOriginalCaption(MessageObject messageObject, String sourceText) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return new CaptionPayload(null, new ArrayList<>());
        }

        String caption = null;
        if (messageObject.isPhoto()) {
            String photoCaption = resolvePhotoCaption(messageObject);
            caption = !TextUtils.isEmpty(photoCaption) ? photoCaption : sourceText;
        } else if (messageObject.getDocument() != null) {
            caption = sourceText;
        }

        return new CaptionPayload(caption, caption != null ? copyEntities(messageObject) : new ArrayList<>());
    }

    private String resolvePhotoCaption(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || !messageObject.isPhoto()) {
            return null;
        }
        TLRPC.Photo photo = MessageObject.getPhoto(messageObject.messageOwner);
        if (photo == null || TextUtils.isEmpty(photo.caption)) {
            return null;
        }
        return photo.caption;
    }

    private String resolvePath(MessageObject messageObject) {
        String path = AyuAttachments.getInstance(currentAccount).getExistingPath(messageObject, false);
        return TextUtils.isEmpty(path) || "/".equals(path) ? null : path;
    }

    private boolean sendTextSync(MessageObject messageObject, String sourceText, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        if (TextUtils.isEmpty(sourceText)) {
            return false;
        }

        ArrayList<TLRPC.MessageEntity> entities = copyEntitiesOrNull(messageObject);
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                sourceText,
                targetDialogId,
                replyToTopMessage,
                replyToTopMessage,
                null,
                false,
                entities,
                null,
                null,
                notify,
                scheduleDate,
                0,
                null,
                false
        );
        applySendContext(params, payStars);

        return AyuSequentialUtils.dispatchSendSync(currentAccount, targetDialogId, null, true, false, dispatchGuard(), () ->
                SendMessagesHelper.getInstance(currentAccount).sendMessage(params));
    }

    private boolean sendStickerSync(MessageObject messageObject, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        if (messageObject == null || messageObject.getDocument() == null) {
            return false;
        }
        MessageObject replyToMessage = replyToTopMessage;
        SendMessageChatArguments.Builder stickerChatArguments = new SendMessageChatArguments.Builder();
        stickerChatArguments.setQuickReplyShortcut(quickReplyShortcut, quickReplyShortcutId);
        return AyuSequentialUtils.dispatchSendSync(currentAccount, targetDialogId, null, true, false, dispatchGuard(), () ->
                SendMessagesHelper.getInstance(currentAccount).sendSticker(
                        messageObject.getDocument(),
                        null,
                        targetDialogId,
                        replyToMessage,
                        replyToMessage,
                        null,
                        null,
                        null,
                        notify,
                        scheduleDate,
                        0,
                        false,
                        null,
                        stickerChatArguments.build(),
                        payStars,
                        monoForumPeerId,
                        suggestionParams
                ));
    }

    private boolean dispatchParamsSync(SendMessagesHelper.SendMessageParams params, String uploadTrackingPath, long targetDialogId, long payStars, boolean waitForMessage, boolean waitForUpload) {
        if (params == null) {
            return false;
        }
        applySendContext(params, payStars);
        String effectiveUploadPath = !TextUtils.isEmpty(uploadTrackingPath) ? uploadTrackingPath : params.path;
        return AyuSequentialUtils.dispatchSendSync(currentAccount, targetDialogId, effectiveUploadPath, waitForMessage, waitForUpload && !TextUtils.isEmpty(effectiveUploadPath), dispatchGuard(), () ->
                SendMessagesHelper.getInstance(currentAccount).sendMessage(params));
    }

    private String resolvePhotoUploadTrackingPath(TLRPC.TL_photo photo) {
        if (photo == null || photo.sizes == null || photo.sizes.isEmpty()) {
            return null;
        }
        TLRPC.PhotoSize photoSize = photo.sizes.get(photo.sizes.size() - 1);
        if (photoSize == null || photoSize.location == null) {
            photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true));
        }
        if (photoSize == null || photoSize.location == null) {
            return null;
        }
        return FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE) + "/" + photoSize.location.volume_id + "_" + photoSize.location.local_id + ".jpg";
    }

    private File resolveExistingFile(MessageObject messageObject) {
        String path = resolvePath(messageObject);
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        File file = new File(path);
        return file.exists() && !file.isDirectory() ? file : null;
    }

    private ArrayList<TLRPC.MessageEntity> copyEntitiesOrNull(MessageObject messageObject) {
        ArrayList<TLRPC.MessageEntity> entities = copyEntities(messageObject);
        return entities.isEmpty() ? null : entities;
    }

    private HashMap<String, String> buildGroupedParams(Long groupToken, boolean finalItem) {
        return groupToken != null ? mapper.createGroupedParams(groupToken, finalItem) : null;
    }

    private SendMessagesHelper.SendMessageParams buildOriginalPhotoParams(TLRPC.Photo sourcePhoto, File file, String caption, ArrayList<TLRPC.MessageEntity> entities, long targetDialogId, boolean notify, int scheduleDate, Long groupToken, boolean finalItem, boolean hasMediaSpoilers, boolean invertMedia) {
        TLRPC.TL_photo photo = mapPhoto(sourcePhoto, file);
        if (photo == null) {
            return null;
        }
        HashMap<String, String> params = buildGroupedParams(groupToken, finalItem);
        if (params != null) {
            params.put("originalPath", resolvePhotoUploadTrackingPath(photo));
        }
        SendMessagesHelper.SendMessageParams sendParams = SendMessagesHelper.SendMessageParams.of(
                photo,
                file.getAbsolutePath(),
                targetDialogId,
                replyToTopMessage,
                replyToTopMessage,
                TextUtils.isEmpty(caption) ? null : caption,
                entities,
                null,
                params,
                notify,
                scheduleDate,
                0,
                0,
                null,
                false,
                hasMediaSpoilers
        );
        sendParams.invert_media = invertMedia;
        return sendParams;
    }

    private SendMessagesHelper.SendMessageParams buildOriginalDocumentParams(MessageObject messageObject, TLRPC.Document sourceDocument, File file, String caption, ArrayList<TLRPC.MessageEntity> entities, long targetDialogId, boolean notify, int scheduleDate, Long groupToken, boolean finalItem, boolean hasMediaSpoilers, boolean invertMedia) {
        TLRPC.TL_document document = mapDocument(messageObject, sourceDocument, file, false);
        if (document == null) {
            return null;
        }
        SendMessagesHelper.SendMessageParams sendParams = SendMessagesHelper.SendMessageParams.of(
                document,
                null,
                file.getAbsolutePath(),
                targetDialogId,
                replyToTopMessage,
                replyToTopMessage,
                TextUtils.isEmpty(caption) ? null : caption,
                entities,
                null,
                buildGroupedParams(groupToken, finalItem),
                notify,
                scheduleDate,
                0,
                0,
                null,
                null,
                false,
                hasMediaSpoilers
        );
        sendParams.invert_media = invertMedia;
        return sendParams;
    }

    private TLRPC.TL_document mapDocument(MessageObject messageObject, TLRPC.Document source, File file, boolean keepFileReference) {
        if (source == null) {
            return null;
        }
        int currentTime = AccountInstance.getInstance(currentAccount).getConnectionsManager().getCurrentTime();
        TLRPC.TL_document mapped = new TLRPC.TL_document();
        mapped.flags = source.flags;
        mapped.file_reference = keepFileReference && source.file_reference != null ? source.file_reference : new byte[0];
        mapped.dc_id = keepFileReference ? source.dc_id : Integer.MIN_VALUE;
        mapped.user_id = source.user_id;
        mapped.version = source.version;
        mapped.mime_type = source.mime_type;
        mapped.file_name = source.file_name;
        mapped.file_name_fixed = source.file_name_fixed;
        mapped.date = currentTime;
        if (file != null) {
            mapped.size = file.length();
            mapped.localPath = file.getAbsolutePath();
        }
        mapped.thumbs = source.thumbs;
        mapped.video_thumbs = source.video_thumbs;
        mapped.localThumbPath = source.localThumbPath;
        mapped.attributes = source.attributes;
        if (messageObject != null && messageObject.isGif()) {
            mapped.mime_type = "video/mp4";
        }
        return mapped;
    }

    private TLRPC.TL_photo mapPhoto(TLRPC.Photo source, File file) {
        if (source == null || file == null) {
            return null;
        }
        int currentTime = AccountInstance.getInstance(currentAccount).getConnectionsManager().getCurrentTime();
        TLRPC.TL_photo mapped = SendMessagesHelper.getInstance(currentAccount).generatePhotoSizes(file.getAbsolutePath(), null);
        if (mapped == null) {
            return null;
        }
        mapped.flags = source.flags;
        mapped.has_stickers = source.has_stickers;
        mapped.date = currentTime;
        mapped.geo = source.geo;
        mapped.caption = source.caption;
        return mapped;
    }

    private SendMessagesHelper.SendMessageParams buildMappedPhotoParams(MessageObject messageObject, String caption, ArrayList<TLRPC.MessageEntity> entities, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams, boolean allowPseudoReply, boolean invertMedia) {
        String filePath = resolvePath(messageObject);
        TLRPC.TL_photo photo = mapper.mapPhoto(messageObject, filePath);
        if (photo == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> effectiveEntities = cloneEntities(entities);
        String effectiveCaption = allowPseudoReply ? prependPseudoReplyCaption(messageObject, caption, effectiveEntities, true) : caption;
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        SendMessagesHelper.SendMessageParams sendParams = SendMessagesHelper.SendMessageParams.of(
                photo,
                filePath,
                targetDialogId,
                null,
                null,
                effectiveCaption,
                effectiveEntities.isEmpty() ? null : effectiveEntities,
                null,
                params,
                notify,
                scheduleDate,
                0,
                mapper.getMessageTtl(messageObject),
                messageObject,
                false,
                messageObject != null && messageObject.hasMediaSpoilers()
        );
        sendParams.invert_media = invertMedia;
        return sendParams;
    }

    private SendMessagesHelper.SendMessageParams buildMappedDocumentParams(MessageObject messageObject, String caption, ArrayList<TLRPC.MessageEntity> entities, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams, boolean allowPseudoReply, boolean invertMedia) {
        String filePath = resolvePath(messageObject);
        if (TextUtils.isEmpty(filePath)) {
            return null;
        }
        TLRPC.TL_document document = mapper.mapDocument(messageObject, filePath);
        if (document == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> effectiveEntities = cloneEntities(entities);
        String effectiveCaption = allowPseudoReply ? prependPseudoReplyCaption(messageObject, caption, effectiveEntities, false) : caption;
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        SendMessagesHelper.SendMessageParams sendParams = SendMessagesHelper.SendMessageParams.of(
                document,
                null,
                filePath,
                targetDialogId,
                null,
                null,
                effectiveCaption,
                effectiveEntities.isEmpty() ? null : effectiveEntities,
                null,
                params,
                notify,
                scheduleDate,
                0,
                mapper.getMessageTtl(messageObject),
                messageObject,
                null,
                false,
                messageObject != null && messageObject.hasMediaSpoilers()
        );
        sendParams.invert_media = invertMedia;
        return sendParams;
    }

    private SendMessagesHelper.SendMessageParams buildDocumentFallbackParams(MessageObject messageObject, String caption, ArrayList<TLRPC.MessageEntity> entities, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams, boolean allowPseudoReply, boolean invertMedia) {
        String filePath = resolvePath(messageObject);
        if (TextUtils.isEmpty(filePath)) {
            return null;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }

        TLRPC.TL_document document = mapper.mapDocument(messageObject, filePath);
        if (document == null) {
            return null;
        }

        document.localPath = filePath;
        document.size = (int) file.length();
        document.date = (int) (System.currentTimeMillis() / 1000);

        ArrayList<TLRPC.MessageEntity> effectiveEntities = cloneEntities(entities);
        String effectiveCaption = allowPseudoReply ? prependPseudoReplyCaption(messageObject, caption, effectiveEntities, false) : caption;
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }

        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        SendMessagesHelper.SendMessageParams sendParams = SendMessagesHelper.SendMessageParams.of(
                document,
                null,
                filePath,
                targetDialogId,
                null,
                null,
                effectiveCaption,
                effectiveEntities.isEmpty() ? null : effectiveEntities,
                null,
                params,
                notify,
                scheduleDate,
                0,
                0,
                messageObject,
                null,
                false,
                messageObject != null && messageObject.hasMediaSpoilers()
        );
        sendParams.invert_media = invertMedia;
        return sendParams;
    }

    private ArrayList<TLRPC.MessageEntity> copyEntities(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.messageOwner.entities == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(messageObject.messageOwner.entities);
    }

    private ArrayList<TLRPC.MessageEntity> cloneEntities(ArrayList<TLRPC.MessageEntity> entities) {
        return entities == null ? new ArrayList<>() : new ArrayList<>(entities);
    }

    private void applySendContext(SendMessagesHelper.SendMessageParams params, long payStars) {
        if (params == null) {
            return;
        }
        if (params.replyToMsg == null) {
            params.replyToMsg = replyToTopMessage;
        }
        if (params.replyToTopMsg == null) {
            params.replyToTopMsg = replyToTopMessage;
        }
        SendMessageChatArguments.Builder builder = new SendMessageChatArguments.Builder();
        builder.setQuickReplyShortcut(quickReplyShortcut, quickReplyShortcutId);
        params.sendMessageChatArguments = builder.build();
        params.quick_reply_shortcut = quickReplyShortcut;
        params.quick_reply_shortcut_id = quickReplyShortcutId;
        params.payStars = payStars;
        params.monoForumPeer = monoForumPeerId;
        params.suggestionParams = suggestionParams;
    }

    private String prependPseudoReply(MessageObject messageObject, String text, ArrayList<TLRPC.MessageEntity> entities) {
        return AyuMessageUtils.prependPseudoReply(text, null, null, targetDialogId, null, messageObject, entities).text;
    }

    private String prependPseudoReplyCaption(MessageObject messageObject, String caption, ArrayList<TLRPC.MessageEntity> entities, boolean photoMarker) {
        return AyuMessageUtils.prependPseudoReply(null, caption, photoMarker ? new TLRPC.TL_photo() : null, targetDialogId, null, messageObject, entities).caption;
    }

    private boolean ensureTaskCanProceed(long taskId, CompletionCallback onComplete) {
        if (disposed || !isTaskActive(taskId) || stopRequested || activeKey == null
                || activeKey.userId == 0 || !activeKey.equals(keyFor(currentAccount, targetDialogId))) {
            finishRun(taskId, false, onComplete);
            return false;
        }
        return true;
    }

    private java.util.function.BooleanSupplier dispatchGuard() {
        final long task = activeTaskId;
        final ForwardKey key = activeKey;
        return () -> task != 0 && activeTaskId == task && !disposed && !stopRequested
                && key != null && key.userId != 0 && key.equals(keyFor(currentAccount, key.dialogId));
    }

    private synchronized long startRun(int messageCount, long targetDialogId, int chunkIndex, int chunkCount) {
        // Reusing a worker must not overwrite a still-running task's mutable state.
        if (disposed || activeTaskId != 0 || keyFor(currentAccount, targetDialogId).userId == 0) return 0;
        activeTaskId = ++nextTaskId;
        this.targetDialogId = targetDialogId;
        this.currentChunkIndex = Math.max(chunkIndex, 0);
        this.totalChunks = Math.max(chunkCount, 1);
        this.currentStatus = STATUS_LOADING;
        this.totalMessages = Math.max(messageCount, 0);
        this.sentMessages = 0;
        this.skippedMessages = 0;
        this.currentStatusDetail = LocaleController.getString(R.string.ForceForwardStatusPreparingMedia);
        this.lastFailureReason = null;
        this.stopRequested = false;
        this.statusUpdateVersion = 0;
        activeKey = keyFor(currentAccount, targetDialogId);
        AyuForward previous = activeForwards.put(activeKey, this);
        if (previous != null && previous != this) previous.requestStop();
        notifyStatusChanged();
        return activeTaskId;
    }

    private synchronized boolean isTaskActive(long taskId) {
        return activeTaskId == taskId;
    }

    private void updateLoadingState(String detail) {
        currentStatus = STATUS_LOADING;
        currentStatusDetail = detail;
        notifyStatusChanged();
    }

    private void updateForwardingState(String detail) {
        currentStatus = STATUS_FORWARDING;
        currentStatusDetail = detail;
        notifyStatusChanged();
    }

    private synchronized void setFailureReason(String failureReason) {
        lastFailureReason = failureReason;
    }

    private void failRun(long taskId, String failureReason, CompletionCallback onComplete) {
        if (isTaskActive(taskId)) setFailureReason(failureReason);
        finishRun(taskId, false, onComplete);
    }

    private void onMessageSent() {
        sentMessages++;
        notifyStatusChanged();
    }

    private void onMessageSkipped() {
        if (totalMessages > 0) {
            totalMessages--;
        }
        skippedMessages++;
        if (sentMessages > totalMessages) {
            sentMessages = totalMessages;
        }
        notifyStatusChanged();
    }

    private void finishRun(long taskId, boolean shouldContinue, CompletionCallback onComplete) {
        boolean currentTask;
        final ForwardKey finishedKey;
        final boolean callbackAllowed;
        synchronized (this) {
            currentTask = activeTaskId == taskId;
            finishedKey = activeKey;
            callbackAllowed = currentTask && shouldContinue && skippedMessages == 0 && !disposed && !stopRequested
                    && finishedKey != null && finishedKey.userId != 0
                    && finishedKey.equals(keyFor(currentAccount, finishedKey.dialogId));
            if (currentTask) {
                if (skippedMessages > 0 && shouldContinue && lastFailureReason == null) {
                    lastFailureReason = LocaleController.formatString(R.string.ForceForwardSomeSkipped, skippedMessages);
                }
                clearRunStateLocked();
            }
        }

        if (currentTask) {
            notifyStatusChanged();
        }

        AndroidUtilities.runOnUIThread(() -> {
            if (detached) {
                if (currentTask) {
                    disposed = true;
                }
                return;
            }
            if (onComplete != null) {
                onComplete.onComplete(callbackAllowed && finishedKey.equals(keyFor(currentAccount, finishedKey.dialogId)));
            }
        });
    }

    private void clearRunStateLocked() {
        if (activeKey != null) activeForwards.remove(activeKey, this);
        activeKey = null;
        activeTaskId = 0L;
        targetDialogId = 0L;
        currentStatus = STATUS_IDLE;
        totalMessages = 0;
        sentMessages = 0;
        skippedMessages = 0;
        currentStatusDetail = null;
        stopRequested = false;
        currentChunkIndex = 0;
        totalChunks = 1;
        statusUpdateVersion = 0;
    }

    private void notifyStatusChanged() {
        if (disposed) {
            return;
        }
        int updateMask = STATUS_REFRESH_MASK_BASE | ((statusUpdateVersion++ & 0x1FF) << 21);
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, updateMask));
    }

    private void runCompletion(CompletionCallback onComplete, boolean shouldContinue) {
        AndroidUtilities.runOnUIThread(() -> {
            if (onComplete != null) {
                onComplete.onComplete(shouldContinue);
            }
        });
    }
}
