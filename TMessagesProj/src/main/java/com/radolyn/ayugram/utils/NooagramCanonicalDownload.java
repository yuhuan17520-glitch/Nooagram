package com.radolyn.ayugram.utils;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.radolyn.ayugram.AyuOptions;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FilePathDatabase;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import tw.nekomimi.nekogram.utils.EnvUtil;

/**
 * Publishes an explicitly downloaded ordinary document as the single canonical public file.
 */
public final class NooagramCanonicalDownload {
    private static final int MAX_FILE_NAME_CODE_POINTS = 180;
    private static final int MAX_FILE_NAME_BYTES = 255;
    private static final int FILE_NAME_SUFFIX_BYTES = 7; // " (9999)"
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final String JOURNAL_PREFERENCES = "nooagram_canonical_recovery";
    private static final String PHASE_PREPARED = "prepared";
    private static final String PHASE_PENDING = "pending";
    private static final String PHASE_PUBLISHED = "published";
    private static final String PHASE_MAPPED = "mapped";

    private NooagramCanonicalDownload() {
    }

    public static final class Claim {
        final String relativePath;
        final String displayName;
        final String mimeType;

        Claim(String relativePath, String displayName, String mimeType) {
            this.relativePath = relativePath;
            this.displayName = displayName;
            this.mimeType = mimeType;
        }
    }

    public static final class Publication {
        public final File file;
        final Uri uri;
        final boolean mediaStore;
        final String journalKey;

        Publication(File file, Uri uri, boolean mediaStore, String journalKey) {
            this.file = file;
            this.uri = uri;
            this.mediaStore = mediaStore;
            this.journalKey = journalKey;
        }
    }

    private static final class JournalEntry {
        String key;
        int account;
        long documentId;
        int dcId;
        int type;
        String phase;
        String sourcePath;
        String relativePath;
        String displayName;
        String mimeType;
        String targetUri;
        String targetPath;
        String pendingToken;
        long targetDev;
        long targetIno;
        boolean legacyOwnershipEstablished;
        long sourceLength;
        String sourceDigest;
        boolean cleanupRequested;

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("account", account);
            object.put("documentId", documentId);
            object.put("dcId", dcId);
            object.put("type", type);
            object.put("phase", phase);
            object.put("sourcePath", sourcePath);
            object.put("relativePath", relativePath);
            object.put("displayName", displayName);
            object.put("mimeType", mimeType);
            object.put("targetUri", targetUri);
            object.put("targetPath", targetPath);
            object.put("pendingToken", pendingToken);
            object.put("targetDev", targetDev);
            object.put("targetIno", targetIno);
            object.put("legacyOwnershipEstablished", legacyOwnershipEstablished);
            object.put("sourceLength", sourceLength);
            object.put("sourceDigest", sourceDigest);
            object.put("cleanupRequested", cleanupRequested);
            return object;
        }

        static JournalEntry fromJson(String key, JSONObject object) {
            JournalEntry entry = new JournalEntry();
            entry.key = key;
            entry.account = object.optInt("account");
            entry.documentId = object.optLong("documentId");
            entry.dcId = object.optInt("dcId");
            entry.type = object.optInt("type");
            entry.phase = object.optString("phase");
            entry.sourcePath = object.optString("sourcePath");
            entry.relativePath = object.optString("relativePath");
            entry.displayName = object.optString("displayName");
            entry.mimeType = object.optString("mimeType");
            entry.targetUri = object.optString("targetUri");
            entry.targetPath = object.optString("targetPath");
            entry.pendingToken = object.optString("pendingToken");
            entry.targetDev = object.optLong("targetDev");
            entry.targetIno = object.optLong("targetIno");
            entry.legacyOwnershipEstablished =
                    object.optBoolean("legacyOwnershipEstablished");
            entry.sourceLength = object.optLong("sourceLength");
            entry.sourceDigest = object.optString("sourceDigest");
            entry.cleanupRequested = object.optBoolean("cleanupRequested");
            return entry;
        }
    }

    private static final class TargetProbe {
        static final int ERROR = 0;
        static final int ABSENT = 1;
        static final int EXISTS = 2;

        final int state;
        final String path;
        final long size;
        final boolean pending;

        TargetProbe(int state, String path, long size, boolean pending) {
            this.state = state;
            this.path = path;
            this.size = size;
            this.pending = pending;
        }
    }

    private static final class OwnedLegacyTarget {
        final File file;
        final StructStat identity;
        FileDescriptor descriptor;

        OwnedLegacyTarget(File file, FileDescriptor descriptor, StructStat identity) {
            this.file = file;
            this.descriptor = descriptor;
            this.identity = identity;
        }
    }

    public interface MappingCommit {
        boolean save(File canonicalFile);
        boolean remove(File canonicalFile);
    }

    public static Claim createClaim(MessageObject messageObject) {
        if (messageObject == null || !AyuOptions.enableCustomSavePath.Bool()
                || !NooagramDownloadDestination.usesCanonicalDocuments()
                || !isEligibleDocument(messageObject, true)) {
            return null;
        }
        boolean peerNoForwards;
        try {
            peerNoForwards = MessagesController.getInstance(messageObject.currentAccount)
                    .isPeerNoForwardsWithOverride(messageObject.getDialogId());
        } catch (Throwable t) {
            return null;
        }
        return createClaim(messageObject, true, peerNoForwards);
    }

    static Claim createClaim(MessageObject messageObject, boolean enabled, boolean peerNoForwards) {
        if (!enabled || peerNoForwards || !isEligibleDocument(messageObject, true)) {
            return null;
        }
        TLRPC.Document document = messageObject.getDocument();
        String displayName = safeFileName(FileLoader.getDocumentFileName(document), document.id);
        if (TextUtils.isEmpty(displayName)) {
            return null;
        }
        String folder = AyuMediaFolders.getFolderName(messageObject);
        String relativePath = relativeDownloadPath(folder);
        String mimeType = resolveMimeType(document, displayName);
        return new Claim(relativePath, displayName, mimeType);
    }

    static boolean isEligibleDocument(MessageObject messageObject, boolean enabled) {
        if (!enabled || messageObject == null || messageObject.messageOwner == null
                || !messageObject.putInDownloadsStore || !FileLoader.canSaveAsFile(messageObject)) {
            return false;
        }
        TLRPC.Document document = messageObject.getDocument();
        if (document == null || document.key != null
                || messageObject.messageOwner.noforwards
                || DialogObject.isEncryptedDialog(messageObject.getDialogId())
                || messageObject.isSecret() || messageObject.isSecretMedia()
                || messageObject.shouldEncryptPhotoOrVideo()) {
            return false;
        }
        String mimeType = document.mime_type;
        if (!TextUtils.isEmpty(mimeType)) {
            mimeType = mimeType.toLowerCase(Locale.ROOT);
            if (mimeType.startsWith("image/") || mimeType.startsWith("video/")
                    || mimeType.startsWith("audio/")) {
                return false;
            }
        }
        return !TextUtils.isEmpty(FileLoader.getDocumentFileName(document));
    }

    static boolean isEligibleMedia(MessageObject message, boolean enabled, boolean peerNoForwards) {
        if (!enabled || peerNoForwards || message == null || message.messageOwner == null
                || !message.putInDownloadsStore || message.isAnyKindOfSticker()
                || message.messageOwner.noforwards || DialogObject.isEncryptedDialog(message.getDialogId())
                || message.isSecret() || message.isSecretMedia() || message.shouldEncryptPhotoOrVideo()
                || message.messageOwner.media != null && message.messageOwner.media.ttl_seconds != 0) {
            return false;
        }
        TLRPC.Document document = message.getDocument();
        if (document != null) {
            return document.key == null;
        }
        return message.isPhoto() && mediaPhoto(message) != null;
    }

    static TLRPC.PhotoSize mediaPhoto(MessageObject message) {
        ArrayList<TLRPC.PhotoSize> sizes = message.photoThumbs;
        if ((sizes == null || sizes.isEmpty()) && MessageObject.getMedia(message.messageOwner) != null
                && MessageObject.getMedia(message.messageOwner).photo != null) {
            sizes = MessageObject.getMedia(message.messageOwner).photo.sizes;
        }
        return FileLoader.getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize(true));
    }

    static String mediaFileName(MessageObject message) {
        TLRPC.Document document = message.getDocument();
        return document != null ? FileLoader.getAttachFileName(document)
                : FileLoader.getAttachFileName(mediaPhoto(message));
    }

    static Claim createMediaClaim(MessageObject message, boolean enabled, boolean peerNoForwards) {
        if (!isEligibleMedia(message, enabled, peerNoForwards)) return null;
        TLRPC.Document document = message.getDocument();
        String name = document == null ? mediaFileName(message) : FileLoader.getDocumentFileName(document);
        if (TextUtils.isEmpty(name)) name = mediaFileName(message);
        if (TextUtils.isEmpty(name)) return null;
        long id = document == null ? message.getId() : document.id;
        name = safeFileName(name, id);
        return new Claim(relativeDownloadPath(AyuMediaFolders.getFolderName(message)), name,
                document == null ? "image/jpeg" : resolveMimeType(document, name));
    }

    public static Publication publish(Context context, File sourceFile, Claim claim,
                                      BooleanSupplier cancelled) throws IOException {
        return publish(context, sourceFile, claim, cancelled, 0, null, null);
    }

    public static Publication publish(Context context, File sourceFile, Claim claim,
                                      BooleanSupplier cancelled, int account,
                                      FilePathDatabase.PathData pathData,
                                      FilePathDatabase database) throws IOException {
        if (context == null || sourceFile == null || !sourceFile.isFile() || claim == null) {
            throw new IOException("No completed source file to publish");
        }
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new IOException("Canonical download cancelled");
        }
        JournalEntry journal = null;
        if (pathData != null && database != null) {
            retryPendingForDocument(context, database, account, pathData, cancelled);
            journal = createJournal(account, pathData, sourceFile, claim, cancelled);
            if (!saveJournal(context, journal)) {
                throw new IOException("Could not persist canonical recovery journal");
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return publishToMediaStore(context, sourceFile, claim, cancelled, journal);
        }
        return publishToLegacyDownloads(context, sourceFile, claim, cancelled, journal);
    }

    public static void rollback(Publication publication) {
        if (publication == null) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        if (publication.mediaStore && context != null && publication.uri != null) {
            try {
                context.getContentResolver().delete(publication.uri, null, null);
            } catch (RuntimeException e) {
                FileLog.e(e);
            }
        } else if (publication.file != null && context != null
                && publication.journalKey != null) {
            JournalEntry journal = loadJournal(context, publication.journalKey);
            if (isOwnedLegacyTarget(journal, publication.file)) {
                publication.file.delete();
            }
        }
    }

    public static File commitAndRemoveSource(File sourceFile, Publication publication,
                                             MappingCommit mappingCommit) throws IOException {
        return commitAndRemoveSource(sourceFile, publication, mappingCommit, null);
    }

    public static File commitAndRemoveSource(File sourceFile, Publication publication,
                                             MappingCommit mappingCommit,
                                             BooleanSupplier cancelled) throws IOException {
        boolean mapped = false;
        try {
            if (publication == null || mappingCommit == null) {
                throw new IOException("Missing canonical publication mapping");
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("Canonical download cancelled");
            }
            mapped = mappingCommit.save(publication.file);
            if (!mapped) {
                throw new IOException("Canonical path database commit failed");
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("Canonical download cancelled");
            }
            if (!sourceFile.equals(publication.file) && sourceFile.exists() && !sourceFile.delete()) {
                throw new IOException("Canonical source cleanup failed");
            }
            return publication.file;
        } catch (Exception e) {
            if (mapped) {
                if (!mappingCommit.remove(publication.file)) {
                    // The target remains mapped. Recovery must not delete it.
                    throw new IOException("Canonical recovery required", e);
                }
            }
            rollback(publication);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Canonical publication failed", e);
        }
    }

    public static File commitAndRemoveSource(
            File sourceFile, Publication publication, int account,
            FilePathDatabase.PathData pathData, FilePathDatabase database,
            BooleanSupplier cancelled) throws IOException {
        if (publication == null || pathData == null || database == null) {
            throw new IOException("Missing canonical database mapping");
        }
        Context context = ApplicationLoader.applicationContext;
        JournalEntry journal = publication.journalKey == null ? null
                : loadJournal(context, publication.journalKey);
        if (journal == null) {
            throw new IOException("Missing canonical recovery journal");
        }
        try {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("Canonical download cancelled");
            }
            boolean mapped = database.putPathBlocking(
                    pathData.id, pathData.dc, pathData.type,
                    FilePathDatabase.FLAG_CANONICAL_DOWNLOAD,
                    publication.file.getAbsolutePath());
            if (!mapped) {
                throw new IOException("Canonical path database commit failed");
            }
            journal.phase = PHASE_MAPPED;
            if (!saveJournal(context, journal)) {
                throw new IOException("Could not persist canonical mapping journal phase");
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("Canonical download cancelled");
            }
            if (!sourceFile.equals(publication.file) && sourceFile.exists()
                    && !database.hasAnotherRefOnFileAcrossAccounts(sourceFile.getAbsolutePath())) {
                if (!matchesSourceIdentity(sourceFile, journal, cancelled)
                        || !sourceFile.delete()) {
                    throw new IOException("Canonical source cleanup failed");
                }
            }
            clearJournal(context, journal.key);
            return publication.file;
        } catch (Exception e) {
            journal.cleanupRequested = true;
            if (!saveJournal(context, journal)) {
                throw new IOException("Could not persist canonical cleanup journal", e);
            }
            recoverJournalEntry(context, database, journal, cancelled);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Canonical commit failed", e);
        }
    }

    public static void recoverPending(Context context, FilePathDatabase database, int account) {
        if (context == null || database == null) {
            return;
        }
        for (JournalEntry entry : loadJournalEntries(context)) {
            if (entry.account != account) {
                continue;
            }
            try {
                recoverJournalEntry(context, database, entry);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public static void retryPendingForDocument(
            Context context, FilePathDatabase database, int account,
            FilePathDatabase.PathData pathData) throws IOException {
        retryPendingForDocument(context, database, account, pathData, null);
    }

    public static void retryPendingForDocument(
            Context context, FilePathDatabase database, int account,
            FilePathDatabase.PathData pathData, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        String key = journalKey(account, pathData.id, pathData.dc, pathData.type);
        String raw = journalPreferences(context).getString(key, null);
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        JournalEntry entry = loadJournal(context, key);
        if (entry == null) {
            throw new IOException("Canonical recovery journal is invalid");
        }
        if (!recoverJournalEntry(context, database, entry, cancelled)) {
            throw new IOException("Canonical recovery is still pending");
        }
        if (loadJournal(context, key) != null) {
            throw new IOException("Canonical recovery is still pending");
        }
    }

    private static JournalEntry createJournal(
            int account, FilePathDatabase.PathData pathData, File sourceFile, Claim claim,
            BooleanSupplier cancelled)
            throws IOException {
        JournalEntry entry = new JournalEntry();
        entry.key = journalKey(account, pathData.id, pathData.dc, pathData.type);
        entry.account = account;
        entry.documentId = pathData.id;
        entry.dcId = pathData.dc;
        entry.type = pathData.type;
        entry.phase = PHASE_PREPARED;
        entry.sourcePath = sourceFile.getAbsolutePath();
        entry.relativePath = claim.relativePath;
        entry.displayName = claim.displayName;
        entry.mimeType = claim.mimeType;
        entry.pendingToken = "nooagram-" + UUID.randomUUID();
        entry.sourceLength = sourceFile.length();
        entry.sourceDigest = toHex(digestFile(sourceFile, cancelled));
        return entry;
    }

    private static String journalKey(int account, long documentId, int dcId, int type) {
        return "journal:" + account + ":" + documentId + ":" + dcId + ":" + type;
    }

    private static SharedPreferences journalPreferences(Context context) {
        return context.getSharedPreferences(JOURNAL_PREFERENCES, Context.MODE_PRIVATE);
    }

    private static boolean saveJournal(Context context, JournalEntry entry) {
        if (context == null || entry == null || TextUtils.isEmpty(entry.key)) {
            return false;
        }
        try {
            return journalPreferences(context).edit()
                    .putString(entry.key, entry.toJson().toString()).commit();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static JournalEntry loadJournal(Context context, String key) {
        if (context == null || TextUtils.isEmpty(key)) {
            return null;
        }
        String value = journalPreferences(context).getString(key, null);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            return JournalEntry.fromJson(key, new JSONObject(value));
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static List<JournalEntry> loadJournalEntries(Context context) {
        List<JournalEntry> entries = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> item
                : journalPreferences(context).getAll().entrySet()) {
            if (!item.getKey().startsWith("journal:") || !(item.getValue() instanceof String)) {
                continue;
            }
            try {
                entries.add(JournalEntry.fromJson(item.getKey(),
                        new JSONObject((String) item.getValue())));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return entries;
    }

    private static void clearJournal(Context context, String key) {
        if (context != null && !TextUtils.isEmpty(key)) {
            journalPreferences(context).edit().remove(key).commit();
        }
    }

    private static boolean recoverJournalEntry(
            Context context, FilePathDatabase database, JournalEntry entry) {
        return recoverJournalEntry(context, database, entry, null);
    }

    private static boolean recoverJournalEntry(
            Context context, FilePathDatabase database, JournalEntry entry, BooleanSupplier cancelled) {
        if (entry == null) {
            return true;
        }
        if (TextUtils.isEmpty(entry.targetUri) && TextUtils.isEmpty(entry.targetPath)) {
            boolean cleaned = cleanupPreparedPendingTarget(context, entry);
            if (cleaned) {
                clearJournal(context, entry.key);
            }
            return cleaned;
        }

        TargetProbe probe;
        if (!TextUtils.isEmpty(entry.targetUri)) {
            probe = probeTarget(context, Uri.parse(entry.targetUri));
        } else {
            File target = entry.targetPath == null ? null : new File(entry.targetPath);
            if (target == null || !target.exists()) {
                probe = new TargetProbe(TargetProbe.ABSENT, entry.targetPath,
                        entry.sourceLength, false);
            } else if (isOwnedLegacyTarget(entry, target)) {
                probe = new TargetProbe(TargetProbe.EXISTS, entry.targetPath,
                        target.length(), false);
            } else {
                probe = new TargetProbe(TargetProbe.ERROR, entry.targetPath,
                        entry.sourceLength, false);
            }
        }
        if (probe.state == TargetProbe.ERROR) {
            return false;
        }
        if (!TextUtils.isEmpty(probe.path)) {
            entry.targetPath = probe.path;
            saveJournal(context, entry);
        }

        if (TextUtils.isEmpty(entry.targetPath)) {
            return recoverWithoutKnownTargetPath(context, database, entry, cancelled);
        }
        FilePathDatabase.CanonicalPathStatus mapping = database.getCanonicalMappingStatus(
                entry.documentId, entry.dcId, entry.type, entry.targetPath);
        if (mapping == FilePathDatabase.CanonicalPathStatus.ERROR) {
            return false;
        }
        if (probe.state == TargetProbe.ABSENT) {
            if (mapping == FilePathDatabase.CanonicalPathStatus.CANONICAL) {
                FilePathDatabase.CanonicalPathStatus removed =
                        database.removeCanonicalPathIfMatchesStatus(
                                entry.documentId, entry.dcId, entry.type, entry.targetPath);
                if (removed != FilePathDatabase.CanonicalPathStatus.REMOVED
                        && removed != FilePathDatabase.CanonicalPathStatus.ABSENT) {
                    return false;
                }
            }
            if (mapping == FilePathDatabase.CanonicalPathStatus.CANONICAL
                    || mapping == FilePathDatabase.CanonicalPathStatus.ABSENT) {
                return finishRecoveryOwnership(context, database, entry, cancelled);
            }
            clearJournal(context, entry.key);
            return true;
        }

        if (!entry.cleanupRequested
                && mapping == FilePathDatabase.CanonicalPathStatus.CANONICAL) {
            if (!verifyRecoveredPublication(context, entry, probe, cancelled)) {
                if (!new File(entry.sourcePath).exists() && !Thread.currentThread().isInterrupted()
                        && (cancelled == null || !cancelled.getAsBoolean())) {
                    FilePathDatabase.CanonicalPathStatus removed = database.removeCanonicalPathIfMatchesStatus(
                            entry.documentId, entry.dcId, entry.type, entry.targetPath);
                    if (removed == FilePathDatabase.CanonicalPathStatus.REMOVED
                            || removed == FilePathDatabase.CanonicalPathStatus.ABSENT) {
                        // Keep unverifiable public bytes, but let a fresh download try again.
                        clearJournal(context, entry.key);
                        return true;
                    }
                }
                return false;
            }
            File source = new File(entry.sourcePath);
            if (!source.equals(new File(entry.targetPath)) && source.exists()
                    && !database.hasAnotherRefOnFileAcrossAccounts(source.getAbsolutePath())) {
                if (!isPrivateSourceFile(context, source)
                        || !matchesSourceIdentity(source, entry, cancelled)
                        || !source.delete()) {
                    return false;
                }
            }
            clearJournal(context, entry.key);
            return true;
        }

        entry.cleanupRequested = true;
        if (!saveJournal(context, entry)) {
            return false;
        }
        if (mapping == FilePathDatabase.CanonicalPathStatus.CANONICAL) {
            FilePathDatabase.CanonicalPathStatus removed =
                    database.removeCanonicalPathIfMatchesStatus(
                            entry.documentId, entry.dcId, entry.type, entry.targetPath);
            if (removed != FilePathDatabase.CanonicalPathStatus.REMOVED
                    && removed != FilePathDatabase.CanonicalPathStatus.ABSENT) {
                return false;
            }
        }

        // A different valid request may now own the same path.
        FilePathDatabase.CanonicalPathStatus pathStatus =
                database.getCanonicalPathStatus(entry.targetPath);
        if (pathStatus == FilePathDatabase.CanonicalPathStatus.ERROR) {
            return false;
        }
        if (pathStatus == FilePathDatabase.CanonicalPathStatus.CANONICAL) {
            clearJournal(context, entry.key);
            return true;
        }
        if (pathStatus == FilePathDatabase.CanonicalPathStatus.CHANGED) {
            clearJournal(context, entry.key);
            return true;
        }
        if (!deleteRecordedTarget(context, entry)) {
            return false;
        }
        return finishRecoveryOwnership(context, database, entry, cancelled);
    }

    private static boolean recoverWithoutKnownTargetPath(
            Context context, FilePathDatabase database, JournalEntry entry, BooleanSupplier cancelled) {
        FilePathDatabase.CanonicalPathStatus current =
                database.getCurrentMappingStatus(entry.documentId, entry.dcId, entry.type);
        if (current == FilePathDatabase.CanonicalPathStatus.ERROR) {
            return false;
        }
        if (current == FilePathDatabase.CanonicalPathStatus.ABSENT) {
            return finishRecoveryOwnership(context, database, entry, cancelled);
        }

        String mappedPath = database.getPath(
                entry.documentId, entry.dcId, entry.type, true);
        if (mappedPath == null) {
            return false;
        }
        File mappedFile = new File(mappedPath);
        boolean canonical = database.isCanonicalDownload(
                entry.documentId, entry.dcId, entry.type, mappedPath);
        if (canonical) {
            if (mappedFile.isFile() && matchesSourceIdentity(mappedFile, entry, cancelled)) {
                File source = new File(entry.sourcePath);
                if (!source.equals(mappedFile) && source.exists()
                        && !database.hasAnotherRefOnFileAcrossAccounts(source.getAbsolutePath())) {
                    if (!isPrivateSourceFile(context, source)
                            || !matchesSourceIdentity(source, entry, cancelled)
                            || !source.delete()) {
                        return false;
                    }
                }
                clearJournal(context, entry.key);
                return true;
            }
            FilePathDatabase.CanonicalPathStatus removed =
                    database.removeCanonicalPathIfMatchesStatus(
                            entry.documentId, entry.dcId, entry.type, mappedPath);
            if (removed != FilePathDatabase.CanonicalPathStatus.REMOVED
                    && removed != FilePathDatabase.CanonicalPathStatus.ABSENT) {
                return false;
            }
            return finishRecoveryOwnership(context, database, entry, cancelled);
        }

        // A different valid mapping owns this document; never overwrite it.
        clearJournal(context, entry.key);
        return true;
    }

    private static boolean finishRecoveryOwnership(
            Context context, FilePathDatabase database, JournalEntry entry, BooleanSupplier cancelled) {
        FilePathDatabase.CanonicalPathStatus status =
                database.getCurrentMappingStatus(entry.documentId, entry.dcId, entry.type);
        if (status == FilePathDatabase.CanonicalPathStatus.ERROR) {
            return false;
        }
        if (status == FilePathDatabase.CanonicalPathStatus.PRESENT) {
            clearJournal(context, entry.key);
            return true;
        }
        File source = new File(entry.sourcePath);
        if (!source.isFile()) {
            clearJournal(context, entry.key);
            return true;
        }
        if (!isPrivateSourceFile(context, source)
                || !matchesSourceIdentity(source, entry, cancelled)) {
            return false;
        }
        if (!database.restorePathIfAbsentBlocking(
                entry.documentId, entry.dcId, entry.type, 0, entry.sourcePath)) {
            status = database.getCurrentMappingStatus(entry.documentId, entry.dcId, entry.type);
            if (status != FilePathDatabase.CanonicalPathStatus.PRESENT) {
                return false;
            }
        }
        clearJournal(context, entry.key);
        return true;
    }

    private static TargetProbe probeTarget(Context context, Uri uri) {
        try {
            Cursor cursor = context.getContentResolver().query(
                    MediaStore.setIncludePending(uri),
                    new String[]{
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.IS_PENDING
                    }, null, null, null);
            if (cursor == null) {
                return new TargetProbe(TargetProbe.ERROR, null, 0, false);
            }
            try {
                if (!cursor.moveToFirst()) {
                    return new TargetProbe(TargetProbe.ABSENT, null, 0, false);
                }
                int dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                int sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                int pendingColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
                String path = dataColumn >= 0 && !cursor.isNull(dataColumn)
                        ? cursor.getString(dataColumn) : null;
                long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn)
                        ? cursor.getLong(sizeColumn) : -1;
                boolean pending = pendingColumn >= 0 && cursor.getInt(pendingColumn) != 0;
                return new TargetProbe(TargetProbe.EXISTS, path, size, pending);
            } finally {
                cursor.close();
            }
        } catch (RuntimeException e) {
            FileLog.e(e);
            return new TargetProbe(TargetProbe.ERROR, null, 0, false);
        }
    }

    private static boolean verifyRecoveredPublication(
            Context context, JournalEntry entry, TargetProbe probe, BooleanSupplier cancelled) {
        if (probe == null || probe.state != TargetProbe.EXISTS
                || probe.size != entry.sourceLength || TextUtils.isEmpty(entry.sourceDigest)) {
            return false;
        }
        File rawFile = new File(entry.targetPath);
        byte[] rawDigest = digestFileQuietly(rawFile, cancelled);
        if (!rawFile.isFile() || rawFile.length() != entry.sourceLength
                || rawDigest == null || !entry.sourceDigest.equals(toHex(rawDigest))) {
            return false;
        }
        if (TextUtils.isEmpty(entry.targetUri)) {
            return isOwnedLegacyTarget(entry, rawFile);
        }
        if (probe.pending) {
            return false;
        }
        try (InputStream input = context.getContentResolver().openInputStream(
                Uri.parse(entry.targetUri))) {
            if (input == null) {
                return false;
            }
            MessageDigest digest = newDigest();
            long length = readIntoDigest(input, digest, cancelled);
            return length == entry.sourceLength
                    && entry.sourceDigest.equals(toHex(digest.digest()));
        } catch (IOException | RuntimeException e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean matchesSourceIdentity(File source, JournalEntry entry, BooleanSupplier cancelled) {
        byte[] digest = digestFileQuietly(source, cancelled);
        return source.length() == entry.sourceLength
                && !TextUtils.isEmpty(entry.sourceDigest)
                && digest != null && entry.sourceDigest.equals(toHex(digest));
    }

    private static byte[] digestFileQuietly(File file, BooleanSupplier cancelled) {
        try {
            return digestFile(file, cancelled);
        } catch (IOException e) {
            FileLog.e(e);
            return null;
        }
    }

    private static boolean cleanupPreparedPendingTarget(Context context, JournalEntry entry) {
        if (Build.VERSION.SDK_INT < 29) {
            return true;
        }
        if (TextUtils.isEmpty(entry.pendingToken)) {
            return false;
        }
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri queryUri = MediaStore.setIncludePending(collection);
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.TITLE
        };
        try (Cursor cursor = resolver.query(
                queryUri,
                projection,
                MediaStore.MediaColumns.TITLE + "=?",
                new String[]{entry.pendingToken},
                null)) {
            if (cursor == null) {
                return false;
            }
            List<Uri> matches = new ArrayList<>();
            while (cursor.moveToNext()) {
                int ownerColumn = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME);
                int pendingColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
                int titleColumn = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE);
                if (ownerColumn < 0 || pendingColumn < 0 || titleColumn < 0
                        || cursor.getString(titleColumn) == null) {
                    return false;
                }
                if (context.getPackageName().equals(cursor.getString(ownerColumn))
                        && cursor.getInt(pendingColumn) != 0
                        && entry.pendingToken.equals(cursor.getString(titleColumn))) {
                    matches.add(collection.buildUpon()
                            .appendPath(Long.toString(cursor.getLong(0))).build());
                }
            }
            for (Uri uri : matches) {
                if (!deleteMediaStoreTarget(context, uri)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean deleteRecordedTarget(Context context, JournalEntry entry) {
        if (!TextUtils.isEmpty(entry.targetUri)) {
            return deleteMediaStoreTarget(context, Uri.parse(entry.targetUri));
        }
        if (!TextUtils.isEmpty(entry.targetPath)) {
            File target = new File(entry.targetPath);
            if (!target.exists()) {
                return true;
            }
            if (!isOwnedLegacyTarget(entry, target)) {
                return false;
            }
            target.delete();
            return !target.exists();
        }
        return true;
    }

    private static boolean isOwnedLegacyTarget(JournalEntry entry, File target) {
        if (entry == null || target == null
                || !entry.legacyOwnershipEstablished || TextUtils.isEmpty(entry.targetPath)
                || !entry.targetPath.equals(target.getAbsolutePath())) {
            return false;
        }
        try {
            StructStat stat = Os.lstat(target.getAbsolutePath());
            return OsConstants.S_ISREG(stat.st_mode)
                    && stat.st_dev == entry.targetDev && stat.st_ino == entry.targetIno;
        } catch (ErrnoException e) {
            return false;
        }
    }

    private static boolean deleteMediaStoreTarget(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try {
            if (resolver.delete(uri, null, null) > 0) {
                return true;
            }
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
        try (Cursor cursor = resolver.query(
                MediaStore.setIncludePending(uri),
                new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
            return cursor != null && !cursor.moveToFirst();
        } catch (RuntimeException e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean isPrivateSourceFile(Context context, File sourceFile) {
        File[] roots = {
                context.getCacheDir(),
                context.getFilesDir(),
                context.getExternalCacheDir(),
                context.getExternalFilesDir(null)
        };
        try {
            String path = sourceFile.getCanonicalPath();
            for (File root : roots) {
                if (root == null) {
                    continue;
                }
                String rootPath = root.getCanonicalPath() + File.separator;
                if (path.startsWith(rootPath)) {
                    return true;
                }
            }
            return isUnderRoot(sourceFile, EnvUtil.getTelegramPath());
        } catch (IOException e) {
            FileLog.e(e);
        }
        return false;
    }

    static boolean isUnderRoot(File file, File root) throws IOException {
        return root != null && file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
    }

    private static Publication publishToMediaStore(Context context, File sourceFile, Claim claim,
                                                   BooleanSupplier cancelled, JournalEntry journal)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String relativePath = claim.relativePath + "/";
        String uniqueName = uniqueMediaStoreName(resolver, collection, relativePath, claim.displayName);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, claim.mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        if (journal != null) {
            values.put(MediaStore.MediaColumns.TITLE, journal.pendingToken);
        }

        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("MediaStore insert returned null");
        }
        if (journal != null) {
            journal.targetUri = uri.toString();
            journal.phase = PHASE_PENDING;
            if (!saveJournal(context, journal)) {
                throw new IOException("Could not persist allocated MediaStore target");
            }
        }
        boolean published = false;
        try {
            byte[] sourceDigest = copyToMediaStore(resolver, sourceFile, uri, cancelled);
            verifyCopiedSource(journal, sourceDigest);
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("Canonical download cancelled");
            }
            ContentValues publishValues = new ContentValues();
            publishValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, publishValues, null, null) <= 0) {
                throw new IOException("MediaStore publish failed");
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("Canonical download cancelled");
            }
            File canonicalFile = queryPublishedFile(resolver, uri, sourceFile.length());
            verifyPublishedBytes(resolver, uri, canonicalFile, sourceDigest, sourceFile.length(), cancelled);
            published = true;
            if (journal != null) {
                journal.targetPath = canonicalFile.getAbsolutePath();
                journal.phase = PHASE_PUBLISHED;
                if (!saveJournal(context, journal)) {
                    throw new IOException("Could not persist published MediaStore target");
                }
            }
            return new Publication(canonicalFile, uri, true,
                    journal == null ? null : journal.key);
        } finally {
            if (!published) {
                boolean cleanupIntentDurable = true;
                if (journal != null) {
                    journal.cleanupRequested = true;
                    cleanupIntentDurable = saveJournal(context, journal);
                }
                if (cleanupIntentDurable) {
                    boolean removed = deleteMediaStoreTarget(context, uri);
                    if (journal != null && removed) {
                        clearJournal(context, journal.key);
                    }
                }
            }
        }
    }

    private static byte[] copyToMediaStore(ContentResolver resolver, File sourceFile, Uri uri,
                                           BooleanSupplier cancelled) throws IOException {
        MessageDigest digest = newDigest();
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "w")) {
            if (descriptor == null) {
                throw new IOException("MediaStore returned no file descriptor");
            }
            try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
                 InputStream input = new FileInputStream(sourceFile)) {
                copyChecked(input, output, digest, cancelled);
                output.flush();
                output.getFD().sync();
            }
        }
        return digest.digest();
    }

    private static File queryPublishedFile(ContentResolver resolver, Uri uri, long expectedLength)
            throws IOException {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING
        };
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("Published MediaStore row is missing");
            }
            int pendingColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
            if (pendingColumn >= 0 && cursor.getInt(pendingColumn) != 0) {
                throw new IOException("MediaStore row is still pending");
            }
            int dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            if (dataColumn < 0 || cursor.isNull(dataColumn)) {
                throw new IOException("MediaStore did not expose a published file path");
            }
            File file = new File(cursor.getString(dataColumn));
            if (!file.isFile() || file.length() != expectedLength) {
                throw new IOException("Published file length verification failed");
            }
            return file;
        }
    }

    private static void verifyPublishedBytes(ContentResolver resolver, Uri uri, File canonicalFile,
                                             byte[] expectedDigest, long expectedLength,
                                             BooleanSupplier cancelled) throws IOException {
        StructStat original = null;
        try {
            original = Os.stat(canonicalFile.getAbsolutePath());
        } catch (ErrnoException e) {
            // Unknown raw-file identity requires provider content verification below.
        }
        if (!MessageDigest.isEqual(expectedDigest, digestFile(canonicalFile, cancelled))) {
            throw new IOException("Published raw file content verification failed");
        }
        // A provider descriptor for the same inode is already covered by the raw-file digest.
        // Providers that expose different bytes still require their own full verification.
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor != null) {
                try {
                    StructStat raw = Os.stat(canonicalFile.getAbsolutePath());
                    StructStat provided = Os.fstat(descriptor.getFileDescriptor());
                    if (original != null && original.st_ino != 0
                            && original.st_dev == raw.st_dev && original.st_ino == raw.st_ino
                            && original.st_size == raw.st_size && original.st_mtime == raw.st_mtime
                            && original.st_ctime == raw.st_ctime && raw.st_dev == provided.st_dev
                            && raw.st_ino == provided.st_ino && raw.st_size == provided.st_size) {
                        checkCancelled(cancelled);
                        return;
                    }
                } catch (ErrnoException e) {
                    // Unknown descriptor identity falls back to content verification.
                }
            }
        }
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("MediaStore returned no verification stream");
            }
            MessageDigest digest = newDigest();
            long count = readIntoDigest(input, digest, cancelled);
            if (count != expectedLength || !MessageDigest.isEqual(expectedDigest, digest.digest())) {
                throw new IOException("Published provider content verification failed");
            }
        }
    }

    private static Publication publishToLegacyDownloads(Context context, File sourceFile, Claim claim,
                                                        BooleanSupplier cancelled, JournalEntry journal)
            throws IOException {
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("Storage write permission was not granted");
        }
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            throw new IOException("External storage is not mounted");
        }

        File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File directory = new File(root, stripDownloadPrefix(claim.relativePath));
        boolean directoryExisted = directory.isDirectory();
        if (!directoryExisted && !directory.mkdirs()) {
            throw new IOException("Could not create the public download directory");
        }

        OwnedLegacyTarget legacyTarget = null;
        File destination = null;
        boolean complete = false;
        try {
            legacyTarget = allocateOwnedLegacyTarget(
                    context, directory, claim.displayName, journal);
            destination = legacyTarget.file;
            MessageDigest digest = newDigest();
            try {
                try (InputStream input = new FileInputStream(sourceFile);
                     FileOutputStream output = new FileOutputStream(legacyTarget.descriptor)) {
                    copyChecked(input, output, digest, cancelled);
                    output.flush();
                    output.getFD().sync();
                }
            } finally {
                legacyTarget.descriptor = null;
            }
            byte[] sourceDigest = digest.digest();
            verifyCopiedSource(journal, sourceDigest);
            if (destination.length() != sourceFile.length()
                    || !MessageDigest.isEqual(sourceDigest, digestFile(destination, cancelled))) {
                throw new IOException("Legacy public file verification failed");
            }
            if (journal != null) {
                journal.phase = PHASE_PUBLISHED;
                if (!saveJournal(context, journal)) {
                    throw new IOException("Could not persist published legacy target");
                }
            }
            complete = true;
            MediaScannerConnection.scanFile(context,
                    new String[]{destination.getAbsolutePath()},
                    new String[]{claim.mimeType}, null);
            return new Publication(destination, Uri.fromFile(destination), false,
                    journal == null ? null : journal.key);
        } finally {
            closeQuietly(legacyTarget);
            if (!complete) {
                boolean cleanupIntentDurable = true;
                if (journal != null) {
                    journal.cleanupRequested = true;
                    cleanupIntentDurable = saveJournal(context, journal);
                }
                if (cleanupIntentDurable) {
                    if (destination != null && isOwnedLegacyTarget(legacyTarget)) {
                        destination.delete();
                    }
                    if (!directoryExisted && directory.isDirectory()) {
                        String[] children = directory.list();
                        if (children != null && children.length == 0) {
                            directory.delete();
                        }
                    }
                    if (journal != null && (destination == null || !destination.exists())) {
                        clearJournal(context, journal.key);
                    }
                }
            }
        }
    }

    private static String uniqueMediaStoreName(ContentResolver resolver, Uri collection,
                                               String relativePath, String displayName)
            throws IOException {
        Uri queryUri = MediaStore.setIncludePending(collection);
        for (int index = 0; index < 10000; index++) {
            String candidate = numberedFileName(displayName, index);
            try (Cursor cursor = resolver.query(
                    queryUri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{candidate, relativePath},
                    null)) {
                if (cursor == null) {
                    throw new IOException("Could not verify MediaStore filename uniqueness");
                }
                if (!cursor.moveToFirst()) {
                    return candidate;
                }
            } catch (RuntimeException e) {
                FileLog.e(e);
                throw new IOException("Could not verify MediaStore filename uniqueness", e);
            }
        }
        throw new IOException("Could not allocate a unique MediaStore filename");
    }

    static File createUniqueFile(File directory, String displayName) throws IOException {
        for (int index = 0; index < 10000; index++) {
            File candidate = new File(directory, numberedFileName(displayName, index));
            if (candidate.createNewFile()) {
                return candidate;
            }
        }
        throw new IOException("Could not allocate a unique download file name");
    }

    private static OwnedLegacyTarget allocateOwnedLegacyTarget(
            Context context, File directory, String displayName, JournalEntry journal)
            throws IOException {
        for (int index = 0; index < 10000; index++) {
            File candidate = new File(directory, numberedFileName(displayName, index));
            FileDescriptor descriptor;
            try {
                descriptor = Os.open(
                        candidate.getAbsolutePath(),
                        OsConstants.O_WRONLY | OsConstants.O_CREAT
                                | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW,
                        0660);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EEXIST) {
                    continue;
                }
                throw new IOException("Could not create legacy download target", e);
            }
            try {
                StructStat stat = Os.fstat(descriptor);
                if (journal != null) {
                    journal.targetPath = candidate.getAbsolutePath();
                    journal.targetDev = stat.st_dev;
                    journal.targetIno = stat.st_ino;
                    journal.legacyOwnershipEstablished = true;
                    journal.phase = PHASE_PENDING;
                    if (!saveJournal(context, journal)) {
                        // Do not delete: ownership was never durably recorded.
                        throw new IOException("Could not persist legacy target ownership");
                    }
                }
                return new OwnedLegacyTarget(candidate, descriptor, stat);
            } catch (Exception e) {
                closeDescriptor(descriptor);
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                throw new IOException("Could not establish legacy target ownership", e);
            }
        }
        throw new IOException("Could not allocate a unique download file name");
    }

    private static void closeQuietly(OwnedLegacyTarget target) {
        if (target != null && target.descriptor != null) {
            closeDescriptor(target.descriptor);
            target.descriptor = null;
        }
    }

    private static boolean isOwnedLegacyTarget(OwnedLegacyTarget target) {
        if (target == null) return false;
        try {
            StructStat stat = Os.lstat(target.file.getAbsolutePath());
            return OsConstants.S_ISREG(stat.st_mode) && stat.st_dev == target.identity.st_dev
                    && stat.st_ino == target.identity.st_ino;
        } catch (ErrnoException e) {
            return false;
        }
    }

    private static void verifyCopiedSource(JournalEntry journal, byte[] digest) throws IOException {
        if (journal != null && !journal.sourceDigest.equals(toHex(digest))) {
            throw new IOException("Canonical source changed during publication");
        }
    }

    private static void closeDescriptor(FileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            Os.close(descriptor);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    static String safeFileName(String originalName, long documentId) {
        String name = originalName == null ? "" : Normalizer.normalize(originalName, Normalizer.Form.NFKC);
        StringBuilder safe = new StringBuilder();
        for (int offset = 0; offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || codePoint == '/' || codePoint == (char) 92 || codePoint == ':'
                    || codePoint == '*' || codePoint == '?' || codePoint == '"'
                    || codePoint == '<' || codePoint == '>' || codePoint == '|') {
                safe.append('_');
            } else {
                safe.appendCodePoint(codePoint);
            }
        }
        int start = 0;
        int end = safe.length();
        while (start < end && (safe.charAt(start) <= ' ' || safe.charAt(start) == '.')) {
            start++;
        }
        while (end > start && (safe.charAt(end - 1) <= ' ' || safe.charAt(end - 1) == '.')) {
            end--;
        }
        return limitFileName(start == end ? "document_" + documentId : safe.substring(start, end),
                MAX_FILE_NAME_BYTES - FILE_NAME_SUFFIX_BYTES, MAX_FILE_NAME_CODE_POINTS);
    }

    static String numberedFileName(String fileName, int index) {
        String suffix = index <= 0 ? "" : " (" + index + ")";
        fileName = limitFileName(fileName, MAX_FILE_NAME_BYTES - suffix.length(), Integer.MAX_VALUE);
        if (index <= 0) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        return dot <= 0
                ? fileName + suffix
                : fileName.substring(0, dot) + suffix + fileName.substring(dot);
    }

    private static String limitFileName(String name, int byteBudget, int pointBudget) {
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot) : "";
        String stem = dot > 0 ? name.substring(0, dot) : name;
        int firstPoint = stem.isEmpty() ? 0 : stem.codePointAt(0);
        int stemBytes = firstPoint <= 0x7f ? 1 : firstPoint <= 0x7ff ? 2 : firstPoint <= 0xffff ? 3 : 4;
        extension = utf8Prefix(extension, byteBudget - stemBytes, pointBudget - 1);
        return utf8Prefix(stem, byteBudget - extension.getBytes(StandardCharsets.UTF_8).length,
                pointBudget - extension.codePointCount(0, extension.length())) + extension;
    }

    private static String utf8Prefix(String value, int byteBudget, int pointBudget) {
        int end = 0;
        for (int count = 0; end < value.length() && count < pointBudget; count++) {
            int point = value.codePointAt(end);
            int bytes = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (bytes > byteBudget) break;
            byteBudget -= bytes;
            end += Character.charCount(point);
        }
        return value.substring(0, end);
    }

    static String relativeDownloadPath(String folderName) {
        String folder = folderName == null ? "" : folderName.replace((char) 92, '/');
        StringBuilder result = new StringBuilder(Environment.DIRECTORY_DOWNLOADS);
        boolean hasSegment = false;
        for (String segment : folder.split("/")) {
            if (TextUtils.isEmpty(segment) || ".".equals(segment) || "..".equals(segment)) {
                continue;
            }
            result.append('/').append(segment);
            hasSegment = true;
        }
        if (!hasSegment) {
            result.append('/').append(tw.nekomimi.nekogram.NekoConfig.DEFAULT_CUSTOM_SAVE_PATH);
        }
        return result.toString();
    }

    private static String stripDownloadPrefix(String relativePath) {
        String prefix = Environment.DIRECTORY_DOWNLOADS + "/";
        return relativePath.startsWith(prefix) ? relativePath.substring(prefix.length()) : relativePath;
    }

    private static String resolveMimeType(TLRPC.Document document, String displayName) {
        if (!TextUtils.isEmpty(document.mime_type)) {
            return document.mime_type;
        }
        int dot = displayName.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < displayName.length()) {
            String extension = displayName.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (!TextUtils.isEmpty(mimeType)) {
                return mimeType;
            }
        }
        return "application/octet-stream";
    }

    static void copyChecked(InputStream input, OutputStream output, MessageDigest digest,
                                    BooleanSupplier cancelled) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int read;
        while (true) {
            checkCancelled(cancelled);
            read = input.read(buffer);
            if (read == -1) break;
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
        }
    }

    private static byte[] digestFile(File file, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = new FileInputStream(file)) {
            readIntoDigest(input, digest, cancelled);
        }
        return digest.digest();
    }

    static long readIntoDigest(InputStream input, MessageDigest digest,
                                       BooleanSupplier cancelled) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long count = 0;
        int read;
        while (true) {
            checkCancelled(cancelled);
            read = input.read(buffer);
            if (read == -1) break;
            digest.update(buffer, 0, read);
            count += read;
        }
        return count;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new IOException("Canonical download cancelled");
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }
}
