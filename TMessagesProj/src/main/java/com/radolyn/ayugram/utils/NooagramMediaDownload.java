package com.radolyn.ayugram.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.radolyn.ayugram.AyuOptions;
import com.radolyn.ayugram.AyuConfig;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Exports completed manual media downloads without moving files used by playback/image loaders. */
public final class NooagramMediaDownload {
    private static final DispatchQueue queue = new DispatchQueue("nooagramMediaDownloads");
    private static final LinkedHashMap<String, ArrayList<Pending>> pending = new LinkedHashMap<>();

    private record Pending(String key, MessageObject message, NooagramCanonicalDownload.Claim claim,
                           String destination, String subfolder) {}

    private NooagramMediaDownload() {}

    public static void request(MessageObject message) { request(message, null); }

    public static void request(MessageObject message, String loadedFileName) {
        if (message == null || !AyuOptions.enableCustomSavePath.Bool()) return;
        boolean restricted = MessagesController.getInstance(message.currentAccount)
                .isPeerNoForwardsWithOverride(message.getDialogId());
        NooagramCanonicalDownload.Claim claim = NooagramCanonicalDownload.createMediaClaim(message, true, restricted);
        if (claim == null) return;
        if (NooagramCanonicalDownload.isEligibleDocument(message, true)
                && NooagramDownloadDestination.usesCanonicalDocuments()) return;
        TLRPC.PhotoSize photo = message.getDocument() == null ? NooagramCanonicalDownload.mediaPhoto(message) : null;
        String fileName = photo == null ? NooagramCanonicalDownload.mediaFileName(message)
                : FileLoader.getAttachFileName(photo);
        // Thumbnail and preloaded video operations never count as a manual full-size download.
        if (loadedFileName != null && !fileName.equals(loadedFileName)) return;
        String transferKey = message.currentAccount + ":" + fileName;
        String destination = AyuOptions.customSavePath.String();
        String subfolder = AyuConfig.INSTANCE.getSaveToChatSubfolder().Bool() ? AyuMediaFolders.getChatFolderName(message) : "";
        String exportKey = transferKey + ":" + message.getDialogId() + ":" + message.getId() + ":" + destination + ":" + subfolder;
        Pending request = new Pending(exportKey, message, claim, destination, subfolder);
        queue.postRunnable(() -> {
            FileLoader loader = FileLoader.getInstance(message.currentAccount);
            File source = photo != null
                    ? loader.getPathToAttach(photo)
                    : loader.getPathToMessage(message.messageOwner);
            if (source != null && source.isFile() && !FileLoader.getInstance(message.currentAccount).isLoadingFile(fileName)) {
                export(request, source);
            } else {
                ArrayList<Pending> requests = pending.computeIfAbsent(transferKey, key -> new ArrayList<>());
                if (requests.stream().noneMatch(item -> item.key.equals(exportKey))) requests.add(request);
                if (requests.size() > 16) requests.remove(0);
                // Bound retained messages even if a completion callback never arrives.
                while (pending.size() > 128) pending.remove(pending.keySet().iterator().next());
            }
        });
    }

    public static void onFileLoaded(int account, String fileName, File file) {
        queue.postRunnable(() -> {
            ArrayList<Pending> requests = pending.remove(account + ":" + fileName);
            if (requests != null) for (Pending request : requests) export(request, file);
        });
    }

    public static void onFileFailed(int account, String fileName) {
        queue.postRunnable(() -> pending.remove(account + ":" + fileName));
    }

    private static void export(Pending request, File source) {
        // A disabled switch also cancels exports queued before completion.
        if (!AyuOptions.enableCustomSavePath.Bool()) return;
        if (MessagesController.getInstance(request.message.currentAccount)
                .isPeerNoForwardsWithOverride(request.message.getDialogId())) return;
        try {
            saveCompleted(ApplicationLoader.applicationContext, request.key, source, request.claim, request.destination, request.subfolder);
        } catch (IOException | RuntimeException e) {
            FileLog.e(e);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                    org.telegram.ui.Components.BulletinFactory.global().createErrorBulletin(
                            org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NooagramDownloadFolderAccessFailed)).show());
        }
    }

    static synchronized String saveCompleted(Context context, String key, File source,
                                           NooagramCanonicalDownload.Claim claim, String destination, String subfolder) throws IOException {
        if (source == null || !source.isFile()) throw new IOException("Media download is incomplete");
        SharedPreferences exports = context.getSharedPreferences("nooagram_media_exports", Context.MODE_PRIVATE);
        String saved = exports.getString(key, null);
        if (saved != null && NooagramDownloadDestination.savedFileExists(context, saved, source.length())) return saved;
        String location = NooagramDownloadDestination.save(context, source, claim, destination, subfolder);
        // A missing record is safe: the public copy remains intact, and a retry uses a new name.
        exports.edit().putString(key, location).commit();
        return location;
    }
}
