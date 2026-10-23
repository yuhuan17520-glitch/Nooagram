package com.radolyn.ayugram.utils.seq;

import android.text.TextUtils;

import com.radolyn.ayugram.controllers.AyuAttachments;
import com.radolyn.ayugram.utils.AyuMessageUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

public abstract class AyuSequentialUtils {

    @FunctionalInterface
    public interface DispatchAction {
        void dispatch();
    }

    private AyuSequentialUtils() {
    }

    public static boolean loadDocumentsSync(int currentAccount, ArrayList<MessageObject> messages) {
        return loadDocumentsSync(currentAccount, messages, () -> true);
    }

    public static boolean loadDocumentsSync(int currentAccount, ArrayList<MessageObject> messages, java.util.function.BooleanSupplier canContinue) {
        if (!canContinue.getAsBoolean()) return false;
        if (messages == null || messages.isEmpty()) {
            return true;
        }

        FileLoader fileLoader = FileLoader.getInstance(currentAccount);
        DummyFileDownloadWaiter waiter = new DummyFileDownloadWaiter(currentAccount);
        ArrayList<Runnable> loadActions = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            if (!canContinue.getAsBoolean()) return false;
            MessageObject messageObject = messages.get(i);
            if (!AyuMessageUtils.isMediaDownloadable(messageObject, false) || hasLocalCopy(currentAccount, messageObject)) {
                continue;
            }

            String trackingKey = getDownloadTrackingKey(messageObject);
            if (TextUtils.isEmpty(trackingKey)) {
                continue;
            }

            waiter.trackPendingDownload(trackingKey);
            if (fileLoader.isLoadingFile(trackingKey)) {
                continue;
            }

            Runnable loadAction = createLoadAction(currentAccount, messageObject);
            if (loadAction != null) {
                loadActions.add(loadAction);
            } else {
                waiter.clearPendingDownload(trackingKey);
            }
        }

        if (!waiter.hasPendingDownloads()) {
            return true;
        }

        waiter.subscribe();
        for (int i = 0; i < loadActions.size(); i++) {
            Runnable loadAction = loadActions.get(i);
            AndroidUtilities.runOnUIThread(() -> {
                if (canContinue.getAsBoolean()) loadAction.run();
            });
        }
        if (!waiter.await(canContinue)) return false;

        return canContinue.getAsBoolean() && allLocalCopiesReady(currentAccount, messages);
    }

    public static boolean dispatchSendSync(int currentAccount, long targetDialogId, String uploadPath, boolean waitForMessage, boolean waitForUpload, DispatchAction action) {
        return dispatchSendSync(currentAccount, targetDialogId, uploadPath, waitForMessage, waitForUpload, () -> true, action);
    }

    public static boolean dispatchSendSync(int currentAccount, long targetDialogId, String uploadPath, boolean waitForMessage, boolean waitForUpload, java.util.function.BooleanSupplier canDispatch, DispatchAction action) {
        if (!canDispatch.getAsBoolean()) return false;
        boolean onUi = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        if (onUi && (waitForMessage || waitForUpload)) {
            throw new IllegalStateException("Waiting for sends must not block the UI thread");
        }
        DummyMessageWaiter messageWaiter = waitForMessage ? new DummyMessageWaiter(currentAccount) : null;
        DummyFileUploadWaiter uploadWaiter = waitForUpload && !TextUtils.isEmpty(uploadPath) ? new DummyFileUploadWaiter(currentAccount, uploadPath) : null;
        long dialogId = 0L;
        ArrayList<Integer> existingSendingIds = null;

        if (messageWaiter != null) {
            dialogId = resolveDialogId(currentAccount, targetDialogId);
            existingSendingIds = SendMessagesHelper.getInstance(currentAccount).getSendingMessageIds(dialogId);
            messageWaiter.subscribe();
        }
        if (uploadWaiter != null) {
            uploadWaiter.subscribe();
        }

        java.util.concurrent.atomic.AtomicBoolean dispatched = new java.util.concurrent.atomic.AtomicBoolean();
        // 0 = queued, 1 = committed on UI, 2 = cancelled before commitment.
        java.util.concurrent.atomic.AtomicInteger dispatchState = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch dispatchDone = new java.util.concurrent.CountDownLatch(1);
        Runnable dispatch = () -> {
            try {
                if (!canDispatch.getAsBoolean() || !dispatchState.compareAndSet(0, 1)) return;
                action.dispatch();
                dispatched.set(true);
            } catch (Exception error) {
                org.telegram.messenger.FileLog.e(error);
            } finally {
                if (!dispatched.get()) {
                    if (uploadWaiter != null) uploadWaiter.cancel();
                    if (messageWaiter != null) messageWaiter.cancel();
                }
                dispatchDone.countDown();
            }
        };
        if (onUi) dispatch.run();
        else AndroidUtilities.runOnUIThread(dispatch);

        try {
            while (canDispatch.getAsBoolean() && !dispatchDone.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // Cancellation/logout is checked again by the UI callback before dispatch.
            }
        } catch (InterruptedException error) {
            dispatchState.compareAndSet(0, 2);
            AndroidUtilities.cancelRunOnUIThread(dispatch);
            Thread.currentThread().interrupt();
        }
        if (!dispatched.get()) {
            dispatchState.compareAndSet(0, 2);
            AndroidUtilities.cancelRunOnUIThread(dispatch);
            if (uploadWaiter != null) uploadWaiter.cancel();
            if (messageWaiter != null) messageWaiter.cancel();
            return false;
        }

        if (messageWaiter != null) {
            messageWaiter.trySetSendingId(dialogId, existingSendingIds);
        }
        if (uploadWaiter != null) {
            if (messageWaiter != null) {
                uploadWaiter.setMessageId(messageWaiter.sendingId);
            }
            uploadWaiter.await(canDispatch);
        }
        if (messageWaiter != null) {
            messageWaiter.await(canDispatch);
        }

        return dispatched.get() && canDispatch.getAsBoolean()
                && (uploadWaiter == null || !uploadWaiter.hasFailed())
                && (messageWaiter == null || !messageWaiter.hasFailed());
    }

    private static long resolveDialogId(int currentAccount, long targetDialogId) {
        TLRPC.InputPeer inputPeer = MessagesController.getInstance(currentAccount).getInputPeer(targetDialogId);
        return inputPeer != null ? DialogObject.getPeerDialogId(inputPeer) : targetDialogId;
    }

    private static Runnable createLoadAction(int currentAccount, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }

        FileLoader fileLoader = FileLoader.getInstance(currentAccount);
        if (messageObject.getDocument() != null) {
            TLRPC.Document document = messageObject.getDocument();
            return () -> fileLoader.loadFile(document, messageObject, FileLoader.PRIORITY_NORMAL, 0);
        }

        if (messageObject.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(messageObject.messageOwner);
            if (photo == null) {
                return null;
            }
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true));
            ImageLocation imageLocation = size != null ? ImageLocation.getForPhoto(size, photo) : null;
            if (imageLocation == null) {
                return null;
            }
            return () -> fileLoader.loadFile(imageLocation, messageObject, null, FileLoader.PRIORITY_NORMAL, 0);
        }

        if (messageObject.isVideo() && messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
            TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) messageObject.messageOwner.media;
            if (videoMedia.video_unused == null || videoMedia.video_unused.thumb == null) {
                return null;
            }
            ImageLocation imageLocation = ImageLocation.getForObject(videoMedia.video_unused.thumb, messageObject.messageOwner);
            if (imageLocation == null) {
                return null;
            }
            return () -> fileLoader.loadFile(imageLocation, messageObject.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
        }

        return null;
    }

    private static boolean hasLocalCopy(int currentAccount, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        String path = AyuAttachments.getInstance(currentAccount).getExistingPath(messageObject, false);
        if (TextUtils.isEmpty(path) || "/".equals(path)) {
            return false;
        }
        File file = new File(path);
        return file.exists() && !file.isDirectory();
    }

    private static boolean allLocalCopiesReady(int currentAccount, ArrayList<MessageObject> messages) {
        if (messages == null) {
            return true;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (AyuMessageUtils.isMediaDownloadable(messageObject, false) && !hasLocalCopy(currentAccount, messageObject)) {
                return false;
            }
        }
        return true;
    }

    private static String getDownloadTrackingKey(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        if (messageObject.getDocument() != null) {
            return FileLoader.getAttachFileName(messageObject.getDocument());
        }
        if (messageObject.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(messageObject.messageOwner);
            if (photo == null) {
                return null;
            }
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true));
            return size != null ? FileLoader.getAttachFileName(size) : null;
        }
        if (messageObject.isVideo() && messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
            TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) messageObject.messageOwner.media;
            if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                return FileLoader.getAttachFileName(videoMedia.video_unused.thumb);
            }
        }
        return null;
    }

    private static boolean needsLocalCopy(MessageObject messageObject) {
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
}
