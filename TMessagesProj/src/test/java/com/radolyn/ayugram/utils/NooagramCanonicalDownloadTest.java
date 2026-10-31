package com.radolyn.ayugram.utils;

import android.Manifest;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowEnvironment;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FilePathDatabase;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = NooagramCanonicalDownloadTest.TestApplication.class)
public class NooagramCanonicalDownloadTest {
    public static class TestApplication extends Application {
        @Override
        public void onCreate() {
            super.onCreate();
            ApplicationLoader.applicationContext = this;
            boolean loggingEnabled = BuildVars.LOGS_ENABLED;
            BuildVars.LOGS_ENABLED = false;
            try {
                Class.forName("org.telegram.messenger.AndroidUtilities");
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            } finally {
                BuildVars.LOGS_ENABLED = loggingEnabled;
            }
        }
    }

    private Application context;
    private FakeMediaProvider provider;
    private File downloadRoot;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext = context;
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        downloadRoot = new File(context.getCacheDir(), "fake-public-downloads");
        provider = new FakeMediaProvider(downloadRoot);
        ProviderInfo info = new ProviderInfo();
        info.authority = MediaStore.AUTHORITY;
        info.packageName = context.getPackageName();
        provider.attachInfo(context, info);
        provider.onCreate();
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider);
    }

    @After
    public void tearDown() {
        ShadowContentResolver.reset();
    }

    @Test
    public void scopedPublicationWritesProviderBytesAndThenRemovesSourceAfterCommit() throws Exception {
        File source = writeSource("report source bytes");
        NooagramCanonicalDownload.Claim claim = claim("report.pdf");

        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(context, source, claim, null);

        assertNotNull(publication);
        assertTrue(source.isFile());
        assertArrayEquals("report source bytes".getBytes("UTF-8"), readFile(publication.file));
        assertEquals(0, provider.pendingCount());

        File committed = NooagramCanonicalDownload.commitAndRemoveSource(
                source, publication, new NooagramCanonicalDownload.MappingCommit() {
                    @Override
                    public boolean save(File canonicalFile) {
                        return true;
                    }

                    @Override
                    public boolean remove(File canonicalFile) {
                        return false;
                    }
                });

        assertEquals(publication.file, committed);
        assertFalse(source.exists());
        assertEquals(1, provider.rowCount());
    }

    @Test
    public void databaseFailureRollsBackPublishedTargetAndRetainsSource() throws Exception {
        File source = writeSource("database failure source");
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(context, source, claim("database.pdf"), null);

        assertThrows(IOException.class, () -> NooagramCanonicalDownload.commitAndRemoveSource(
                source, publication, new NooagramCanonicalDownload.MappingCommit() {
                    @Override
                    public boolean save(File canonicalFile) {
                        return false;
                    }

                    @Override
                    public boolean remove(File canonicalFile) {
                        return false;
                    }
                }));

        assertTrue(source.isFile());
        assertFalse(publication.file.exists());
        assertEquals(0, provider.rowCount());
    }

    @Test
    public void cancellationAfterDatabaseCommitRollsBackTargetAndRetainsSource() throws Exception {
        File source = writeSource("cancel after mapping");
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(context, source, claim("cancel.pdf"), null);
        AtomicBoolean cancelled = new AtomicBoolean();

        assertThrows(IOException.class, () -> NooagramCanonicalDownload.commitAndRemoveSource(
                source, publication, new NooagramCanonicalDownload.MappingCommit() {
                    @Override
                    public boolean save(File canonicalFile) {
                        cancelled.set(true);
                        return true;
                    }

                    @Override
                    public boolean remove(File canonicalFile) {
                        return true;
                    }
                }, cancelled::get));

        assertTrue(source.isFile());
        assertFalse(publication.file.exists());
        assertEquals(0, provider.rowCount());
    }

    @Test
    public void productionJournalRecoversUnmappedTargetAfterRestart() throws Exception {
        File source = writeSource("restart recovery source");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(501, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(
                        context, source, claim("restart.pdf"), null, 0, pathData, database);

        assertTrue(publication.file.exists());
        NooagramCanonicalDownload.recoverPending(context, database, 0);

        assertFalse(publication.file.exists());
        assertTrue(source.isFile());
        assertEquals(source.getAbsolutePath(), database.mappedPath);
        assertEquals(0, provider.rowCount());
    }

    @Test
    public void sharedSourceIsRetainedAfterCanonicalCommitAndMappedRecovery() throws Exception {
        File source = writeSource("shared account bytes");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(520, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        database.sourceReferenced = true;
        NooagramCanonicalDownload.Publication publication = NooagramCanonicalDownload.publish(
                context, source, claim("shared.pdf"), null, 0, pathData, database);
        assertEquals(publication.file, NooagramCanonicalDownload.commitAndRemoveSource(
                source, publication, 0, pathData, database, null));
        assertTrue(source.isFile());
        assertTrue(publication.file.isFile());
        assertFalse(context.getSharedPreferences("nooagram_canonical_recovery", Context.MODE_PRIVATE)
                .contains("journal:0:520:2:3"));

        FilePathDatabase.PathData recoveryData = new FilePathDatabase.PathData(521, 2, 3);
        NooagramCanonicalDownload.Publication recovery = NooagramCanonicalDownload.publish(
                context, source, claim("shared-recovery.pdf"), null, 0, recoveryData, database);
        database.putPathBlocking(recoveryData.id, recoveryData.dc, recoveryData.type,
                FilePathDatabase.FLAG_CANONICAL_DOWNLOAD, recovery.file.getAbsolutePath());
        NooagramCanonicalDownload.recoverPending(context, database, 0);
        assertTrue(source.isFile());
        assertTrue(recovery.file.isFile());
        assertFalse(context.getSharedPreferences("nooagram_canonical_recovery", Context.MODE_PRIVATE)
                .contains("journal:0:521:2:3"));
    }

    @Test
    public void missingSourceDoesNotLeaveRecoveryBlockingRetry() throws Exception {
        File source = writeSource("removed source");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(522, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication = NooagramCanonicalDownload.publish(
                context, source, claim("missing-source.pdf"), null, 0, pathData, database);
        assertTrue(source.delete());
        NooagramCanonicalDownload.retryPendingForDocument(context, database, 0, pathData);
        assertFalse(publication.file.exists());
        assertNull(database.mappedPath);
        assertFalse(context.getSharedPreferences("nooagram_canonical_recovery", Context.MODE_PRIVATE)
                .contains("journal:0:522:2:3"));
        File retrySource = writeSource("retry source");
        assertTrue(NooagramCanonicalDownload.publish(context, retrySource,
                claim("missing-source.pdf"), null, 0, pathData, database).file.isFile());
    }

    @Test
    public void missingSourceAndInvalidMappedTargetUnblocksRetryWithoutDeletingPublicBytes() throws Exception {
        File source = writeSource("original source bytes");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(523, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication = NooagramCanonicalDownload.publish(
                context, source, claim("unverifiable.pdf"), null, 0, pathData, database);
        database.putPathBlocking(pathData.id, pathData.dc, pathData.type,
                FilePathDatabase.FLAG_CANONICAL_DOWNLOAD, publication.file.getAbsolutePath());
        assertTrue(source.delete());
        java.nio.file.Files.write(publication.file.toPath(), new byte[]{1});
        NooagramCanonicalDownload.retryPendingForDocument(context, database, 0, pathData);
        assertTrue(publication.file.isFile());
        assertNull(database.mappedPath);
        assertFalse(context.getSharedPreferences("nooagram_canonical_recovery", Context.MODE_PRIVATE)
                .contains("journal:0:523:2:3"));
    }

    @Test
    @Config(sdk = 28, application = NooagramCanonicalDownloadTest.TestApplication.class)
    public void legacyCancellationWithoutJournalRemovesOnlyTheCreatedTarget() throws Exception {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        File source = writeSource("partial legacy bytes");
        File target = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "nooagram/partial-without-journal.pdf");
        assertThrows(IOException.class, () -> NooagramCanonicalDownload.publish(context, source,
                claim(target.getName()), target::isFile));
        assertFalse(target.exists());
        assertTrue(source.isFile());
    }

    @Test
    public void configuredPathSegmentsKeepTheirLengthAndCompatibilityCharacters() {
        String folder = "\u2460" + "a".repeat(70);
        assertEquals("Download/" + folder + "/My Chat",
                NooagramCanonicalDownload.relativeDownloadPath(folder + "/My Chat"));
    }

    @Test
    public void unicodeFileNamesKeepExtensionsAndLeaveRoomForCollisionSuffixes() {
        for (String stem : new String[]{"\u4e2d".repeat(100), "\ud83d\ude00".repeat(100), "a".repeat(300)}) {
            String safe = NooagramCanonicalDownload.safeFileName(stem + ".mp3", 10);
            assertTrue(safe.endsWith(".mp3"));
            assertTrue(safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 248);
            String collision = NooagramCanonicalDownload.numberedFileName(safe, 9999);
            assertTrue(collision.endsWith(" (9999).mp3"));
            assertTrue(collision.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255);
        }
    }

    @Test
    public void restartUsesActualMappingAndVerifiesPublishedBytesBeforeSourceCleanup()
            throws Exception {
        File source = writeSource("verified mapped source");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(506, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(
                        context, source, claim("verified.pdf"), null, 0, pathData, database);
        assertTrue(database.putPathBlocking(
                pathData.id, pathData.dc, pathData.type,
                FilePathDatabase.FLAG_CANONICAL_DOWNLOAD,
                publication.file.getAbsolutePath()));

        NooagramCanonicalDownload.recoverPending(context, database, 0);

        assertFalse(source.exists());
        assertTrue(publication.file.exists());
        assertEquals(publication.file.getAbsolutePath(), database.mappedPath);
        assertEquals(1, provider.rowCount());
    }

    @Test
    public void productionCancellationDuringMappingKeepsSourceAndRollsBackTarget()
            throws Exception {
        File source = writeSource("production cancellation source");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(502, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(
                        context, source, claim("mapping-cancel.pdf"), null, 0, pathData, database);
        AtomicBoolean cancelled = new AtomicBoolean();
        database.onSave = () -> cancelled.set(true);

        assertThrows(IOException.class, () -> NooagramCanonicalDownload.commitAndRemoveSource(
                source, publication, 0, pathData, database, cancelled::get));

        assertTrue(source.isFile());
        assertFalse(publication.file.exists());
        assertEquals(0, provider.rowCount());
    }

    @Test
    public void productionRecoveryNeverDeletesStillMappedTarget() throws Exception {
        File source = writeSource("mapped cleanup source");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(503, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(
                        context, source, claim("mapped.pdf"), null, 0, pathData, database);
        AtomicBoolean cancelled = new AtomicBoolean();
        database.onSave = () -> {
            cancelled.set(true);
            database.failRemoval = true;
        };

        assertThrows(IOException.class, () -> NooagramCanonicalDownload.commitAndRemoveSource(
                source, publication, 0, pathData, database, cancelled::get));

        assertTrue(publication.file.exists());
        assertTrue(source.isFile());
        assertEquals(publication.file.getAbsolutePath(), database.mappedPath);
        assertEquals(1, provider.rowCount());
    }

    @Test
    public void providerQueryFailureKeepsRecoveryTargetAndSource() throws Exception {
        File source = writeSource("provider failure source");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(504, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(
                        context, source, claim("provider.pdf"), null, 0, pathData, database);
        provider.returnNullForItemQuery = true;

        NooagramCanonicalDownload.recoverPending(context, database, 0);

        assertTrue(source.isFile());
        assertTrue(publication.file.exists());
        assertThrows(IOException.class, () -> NooagramCanonicalDownload.retryPendingForDocument(
                context, database, 0, pathData));
    }

    @Test
    public void malformedJournalBlocksNewPublication() {
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(505, 2, 3);
        assertTrue(context.getSharedPreferences("nooagram_canonical_recovery", Context.MODE_PRIVATE)
                .edit().putString("journal:0:505:2:3", "not-json").commit());
        FakePathDatabase database = new FakePathDatabase();

        assertThrows(IOException.class, () -> NooagramCanonicalDownload.retryPendingForDocument(
                context, database, 0, pathData));
    }

    @Test
    public void pendingMissingWithoutPathUsesActualMappingAndRestoresSource()
            throws Exception {
        File source = writeSource("pending without path");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(510, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        JSONObject journal = new JSONObject();
        journal.put("account", 0);
        journal.put("documentId", pathData.id);
        journal.put("dcId", pathData.dc);
        journal.put("type", pathData.type);
        journal.put("phase", "pending");
        journal.put("sourcePath", source.getAbsolutePath());
        journal.put("relativePath", "Download/nooagram");
        journal.put("displayName", "pending.pdf");
        journal.put("mimeType", "application/pdf");
        journal.put("sourceLength", source.length());
        journal.put("sourceDigest", sha256Hex(source));
        journal.put("targetUri", "content://media/external/downloads/999");
        journal.put("targetPath", "");
        assertTrue(context.getSharedPreferences("nooagram_canonical_recovery", Context.MODE_PRIVATE)
                .edit().putString("journal:0:510:2:3", journal.toString()).commit());

        NooagramCanonicalDownload.recoverPending(context, database, 0);

        assertTrue(source.isFile());
        assertEquals(source.getAbsolutePath(), database.mappedPath);
    }

    @Test
    @Config(sdk = 28, application = NooagramCanonicalDownloadTest.TestApplication.class)
    public void legacyRecoveryDeletesOnlyOwnedFileAndRestoresSource() throws Exception {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        File source = writeSource("legacy recovery");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(507, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(
                        context, source, claim("legacy.pdf"), null, 0, pathData, database);

        assertTrue(publication.file.isFile());
        NooagramCanonicalDownload.recoverPending(context, database, 0);

        assertFalse(publication.file.exists());
        assertTrue(source.isFile());
        assertEquals(source.getAbsolutePath(), database.mappedPath);
    }

    @Test
    @Config(sdk = 28, application = NooagramCanonicalDownloadTest.TestApplication.class)
    public void legacyRecoveryNeverDeletesReplacementAtPlannedPath() throws Exception {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        File source = writeSource("legacy original");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(508, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(
                        context, source, claim("replaced.pdf"), null, 0, pathData, database);
        File target = publication.file;
        assertTrue(target.delete());
        assertTrue(target.mkdir());

        NooagramCanonicalDownload.recoverPending(context, database, 0);

        assertTrue(target.isDirectory());
        assertTrue(source.isFile());
        assertThrows(IOException.class, () -> NooagramCanonicalDownload.retryPendingForDocument(
                context, database, 0, pathData));
    }

    @Test
    @Config(sdk = 28, application = NooagramCanonicalDownloadTest.TestApplication.class)
    public void legacyMappedRecoveryVerifiesRawFileWithoutUri() throws Exception {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        File source = writeSource("legacy mapped");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(509, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(
                        context, source, claim("legacy-mapped.pdf"), null, 0, pathData, database);
        assertTrue(database.putPathBlocking(
                pathData.id, pathData.dc, pathData.type,
                FilePathDatabase.FLAG_CANONICAL_DOWNLOAD,
                publication.file.getAbsolutePath()));

        NooagramCanonicalDownload.recoverPending(context, database, 0);

        assertFalse(source.exists());
        assertTrue(publication.file.isFile());
        assertEquals(publication.file.getAbsolutePath(), database.mappedPath);
    }

    @Test
    @Config(sdk = 28, application = NooagramCanonicalDownloadTest.TestApplication.class)
    public void legacyFailureCleanupDoesNotDeleteReplacementAtPlannedPath() throws Exception {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        File source = writeSource("legacy failure source");
        FilePathDatabase.PathData pathData = new FilePathDatabase.PathData(511, 2, 3);
        FakePathDatabase database = new FakePathDatabase();
        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(
                        context, source, claim("failure-replaced.pdf"), null, 0,
                        pathData, database);
        File target = publication.file;
        assertTrue(target.delete());
        assertTrue(target.mkdir());
        database.failSave = true;

        assertThrows(IOException.class, () -> NooagramCanonicalDownload.commitAndRemoveSource(
                source, publication, 0, pathData, database, null));

        assertTrue(target.isDirectory());
        assertTrue(source.isFile());
    }

    @Test
    public void publicationFailureRemovesPendingReservationAndRetainsSource() throws Exception {
        provider.failPublish = true;
        File source = writeSource("publish failure source");

        assertThrows(IOException.class, () ->
                NooagramCanonicalDownload.publish(context, source, claim("failure.pdf"), null));

        assertTrue(source.isFile());
        assertEquals(0, provider.rowCount());
        assertEquals(0, provider.pendingCount());
    }

    @Test
    public void providerWithADifferentFileStillRequiresContentVerification() throws Exception {
        File source = writeSource("original bytes");
        provider.readOverride = writeSource("modified bytes");
        assertThrows(IOException.class, () -> NooagramCanonicalDownload.publish(
                context, source, claim("different-provider.pdf"), null));
        assertTrue(source.isFile());
        assertEquals(0, provider.rowCount());
    }

    @Test
    public void filenameCollisionProducesNewNameWithoutOverwriting() throws Exception {
        provider.addPublished("Download/nooagram/", "report.pdf", "existing".getBytes("UTF-8"));
        File source = writeSource("new content");

        NooagramCanonicalDownload.Publication publication =
                NooagramCanonicalDownload.publish(context, source, claim("report.pdf"), null);

        assertEquals("report (1).pdf", publication.file.getName());
        assertArrayEquals("existing".getBytes("UTF-8"),
                readFile(new File(downloadRoot, "Download/nooagram/report.pdf")));
        assertArrayEquals("new content".getBytes("UTF-8"), readFile(publication.file));
    }

    @Test
    public void eligibilityRequiresExplicitPlainDocumentAndRejectsPrivacyVariants() {
        MessageObject document = message("archive.zip", "application/zip", false);
        document.putInDownloadsStore = true;
        assertTrue(NooagramCanonicalDownload.isEligibleDocument(document, true));
        assertFalse(NooagramCanonicalDownload.isEligibleDocument(document, false));
        assertNull(NooagramCanonicalDownload.createClaim(document, true, true));

        document.putInDownloadsStore = false;
        assertFalse(NooagramCanonicalDownload.isEligibleDocument(document, true));

        MessageObject video = message("clip.mp4", "video/mp4", true);
        video.putInDownloadsStore = true;
        assertFalse(NooagramCanonicalDownload.isEligibleDocument(video, true));

        MessageObject ttl = message("secret.pdf", "application/pdf", false);
        ttl.putInDownloadsStore = true;
        ttl.messageOwner.media.ttl_seconds = 30;
        assertFalse(NooagramCanonicalDownload.isEligibleDocument(ttl, true));

        MessageObject noForwards = message("locked.pdf", "application/pdf", false);
        noForwards.putInDownloadsStore = true;
        noForwards.messageOwner.noforwards = true;
        assertFalse(NooagramCanonicalDownload.isEligibleDocument(noForwards, true));
    }

    @Test
    public void cacheKeyAndPathSanitizationAreStable() {
        MessageObject cached = message("cache.pdf", "application/pdf", false);
        cached.getDocument().key = new byte[]{1};
        cached.putInDownloadsStore = true;
        assertFalse(NooagramCanonicalDownload.isEligibleDocument(cached, true));

        assertEquals("report_.pdf",
                NooagramCanonicalDownload.safeFileName("..report?.pdf", 10));
        assertEquals("report (2).pdf",
                NooagramCanonicalDownload.numberedFileName("report.pdf", 2));
        assertEquals("Download/nooagram",
                NooagramCanonicalDownload.relativeDownloadPath("nooagram"));
        assertEquals("Download/nooagram/My Chat",
                NooagramCanonicalDownload.relativeDownloadPath("nooagram/My Chat"));
    }

    @Test
    public void manualMediaIncludesVideoAudioAndImageDocumentsButNotAutomaticDownloads() {
        for (String mime : new String[]{"video/mp4", "audio/mpeg", "audio/ogg", "image/png"}) {
            MessageObject message = message("media.bin", mime, mime.startsWith("video/"));
            assertFalse(NooagramCanonicalDownload.isEligibleMedia(message, true, false));
            message.putInDownloadsStore = true;
            assertTrue(mime, NooagramCanonicalDownload.isEligibleMedia(message, true, false));
            assertFalse(NooagramCanonicalDownload.isEligibleMedia(message, false, false));
            assertFalse(NooagramCanonicalDownload.isEligibleMedia(message, true, true));
            message.messageOwner.noforwards = true;
            assertFalse(NooagramCanonicalDownload.isEligibleMedia(message, true, false));
            message.messageOwner.noforwards = false;
            message.messageOwner.media.ttl_seconds = 5;
            assertFalse(NooagramCanonicalDownload.isEligibleMedia(message, true, false));
            message.messageOwner.media.ttl_seconds = 0;
            message.getDocument().key = new byte[]{1};
            assertFalse(NooagramCanonicalDownload.isEligibleMedia(message, true, false));
        }
    }

    @Test
    public void manualPhotoUsesFullSizeAndRejectsMissingPhoto() {
        TLRPC.TL_photo photo = new TLRPC.TL_photo();
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.type = "x";
        size.w = 1280;
        size.h = 720;
        size.size = 80;
        size.location = new TLRPC.TL_fileLocationToBeDeprecated();
        size.location.volume_id = 101;
        size.location.local_id = 7;
        photo.sizes.add(size);
        TLRPC.TL_message raw = new TLRPC.TL_message();
        TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
        media.photo = photo;
        raw.media = media;
        raw.id = 5;
        raw.dialog_id = 10;
        MessageObject message = new TestMessageObject(raw);
        message.putInDownloadsStore = true;
        assertTrue(NooagramCanonicalDownload.isEligibleMedia(message, true, false));
        assertEquals(size, NooagramCanonicalDownload.mediaPhoto(message));
        assertEquals("101_7.jpg", NooagramCanonicalDownload.mediaFileName(message));
        TLRPC.TL_photoSize hd = new TLRPC.TL_photoSize();
        hd.type = "y";
        hd.w = 2560;
        hd.h = 1440;
        hd.size = 160;
        hd.location = new TLRPC.TL_fileLocationToBeDeprecated();
        hd.location.volume_id = 102;
        hd.location.local_id = 8;
        photo.sizes.add(hd);
        if (message.photoThumbs != null && message.photoThumbs != photo.sizes) message.photoThumbs.add(hd);
        assertEquals(hd, NooagramCanonicalDownload.mediaPhoto(message));
        assertEquals("102_8.jpg", NooagramCanonicalDownload.mediaFileName(message));
        photo.sizes.clear();
        if (message.photoThumbs != null) message.photoThumbs.clear();
        assertFalse(NooagramCanonicalDownload.isEligibleMedia(message, true, false));
    }

    @Test
    public void mediaExportKeepsPlaybackCacheAndDoesNotDuplicateCompletedFiles() throws Exception {
        File source = writeSource("media bytes");
        NooagramCanonicalDownload.Claim target = new NooagramCanonicalDownload.Claim(
                "Download/Nooagram", "clip.mp4", "video/mp4");
        File first = new File(NooagramMediaDownload.saveCompleted(context, "manual-video", source, target, "Nooagram", ""));
        assertTrue(source.isFile());
        assertTrue(first.isFile());
        assertArrayEquals(readFile(source), readFile(first));
        assertEquals(first.getAbsolutePath(), NooagramMediaDownload.saveCompleted(context, "manual-video", source, target, "Nooagram", ""));
        assertEquals(1, provider.rowCount());
        assertTrue(first.delete());
        File restored = new File(NooagramMediaDownload.saveCompleted(context, "manual-video", source, target, "Nooagram", ""));
        assertTrue(restored.isFile());
        assertTrue(source.isFile());
        assertArrayEquals(readFile(source), readFile(restored));
    }

    @Test
    public void defaultPublicFolderUsesProductCasing() {
        assertEquals("Download/Nooagram", NooagramCanonicalDownload.relativeDownloadPath(""));
    }

    private NooagramCanonicalDownload.Claim claim(String fileName) {
        return new NooagramCanonicalDownload.Claim(
                "Download/nooagram", fileName, "application/pdf");
    }

    private File writeSource(String text) throws IOException {
        File file = File.createTempFile("canonical-source-", ".tmp", context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes("UTF-8"));
        }
        return file;
    }

    private static byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    private static MessageObject message(String fileName, String mimeType, boolean video) {
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = Math.abs(fileName.hashCode()) + 1;
        document.dc_id = 2;
        document.mime_type = mimeType;
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = fileName;
        document.attributes.add(filename);
        if (video) {
            document.attributes.add(new TLRPC.TL_documentAttributeVideo());
        }

        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = document;
        TLRPC.Message raw = fileName.contains("secret")
                ? new TLRPC.TL_message_secret() : new TLRPC.TL_message();
        raw.id = document.id > Integer.MAX_VALUE ? 1 : (int) document.id;
        raw.dialog_id = 10;
        raw.date = 1;
        raw.media = media;
        return new TestMessageObject(raw);
    }

    private static final class TestMessageObject extends MessageObject {
        TestMessageObject(TLRPC.Message raw) {
            super(0, raw, "", null, null, false, false, false, false);
        }

        @Override
        public boolean isSecretMedia() {
            return false;
        }

        @Override
        public boolean shouldEncryptPhotoOrVideo() {
            return false;
        }
    }

    private static final class FakeMediaProvider extends ContentProvider {
        private final File root;
        private final Map<Long, Row> rows = new HashMap<>();
        private long nextId = 1;
        boolean failPublish;
        boolean returnNullForItemQuery;
        File readOverride;

        FakeMediaProvider(File root) {
            this.root = root;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            long id = nextId++;
            String relativePath = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
            String name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            File finalFile = new File(root, (relativePath + name).replace('/', File.separatorChar));
            File pendingFile = new File(finalFile.getParentFile(), ".pending-" + name);
            Row row = new Row(id, name, relativePath,
                    values.getAsString(MediaStore.MediaColumns.MIME_TYPE),
                    finalFile, pendingFile, true);
            rows.put(id, row);
            return uri.buildUpon().appendPath(Long.toString(id)).build();
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                            String[] selectionArgs, String sortOrder) {
            String segment = uri.getLastPathSegment();
            if (segment != null && segment.matches("\\d+")) {
                if (returnNullForItemQuery) {
                    return null;
                }
                Row row = rows.get(Long.parseLong(segment));
                if (row == null) {
                    return new MatrixCursor(projection);
                }
                return cursor(projection, row);
            }
            MatrixCursor cursor = new MatrixCursor(projection);
            String requestedName = selectionArgs != null && selectionArgs.length > 0
                    ? selectionArgs[0] : null;
            String requestedPath = selectionArgs != null && selectionArgs.length > 1
                    ? selectionArgs[1] : null;
            for (Row row : rows.values()) {
                if ((requestedName == null || requestedName.equals(row.displayName))
                        && (requestedPath == null || requestedPath.equals(row.relativePath))) {
                    addRow(cursor, projection, row);
                }
            }
            return cursor;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            String segment = uri.getLastPathSegment();
            Row row = segment == null ? null : rows.get(Long.parseLong(segment));
            if (row == null || failPublish) {
                return 0;
            }
            Integer pending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
            if (pending != null && pending == 0 && row.pending) {
                row.finalFile.getParentFile().mkdirs();
                if (row.pendingFile.exists() && !row.pendingFile.renameTo(row.finalFile)) {
                    return 0;
                }
                row.pending = false;
                return 1;
            }
            return 1;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            String segment = uri.getLastPathSegment();
            Row row = segment == null ? null : rows.remove(Long.parseLong(segment));
            if (row == null) {
                return 0;
            }
            row.pendingFile.delete();
            row.finalFile.delete();
            return 1;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            String segment = uri.getLastPathSegment();
            Row row = rows.get(Long.parseLong(segment));
            if (row == null) {
                throw new FileNotFoundException("No row");
            }
            File target = row.pending ? row.pendingFile : row.finalFile;
            if (!mode.contains("w") && readOverride != null) target = readOverride;
            target.getParentFile().mkdirs();
            int flags = mode.contains("w")
                    ? ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                    | ParcelFileDescriptor.MODE_READ_WRITE
                    : ParcelFileDescriptor.MODE_READ_ONLY;
            return ParcelFileDescriptor.open(target, flags);
        }

        void addPublished(String relativePath, String name, byte[] bytes) throws IOException {
            File file = new File(root, (relativePath + name).replace('/', File.separatorChar));
            file.getParentFile().mkdirs();
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes);
            }
            long id = nextId++;
            rows.put(id, new Row(id, name, relativePath, "application/pdf", file, file, false));
        }

        int rowCount() {
            return rows.size();
        }

        int pendingCount() {
            int count = 0;
            for (Row row : rows.values()) {
                if (row.pending) {
                    count++;
                }
            }
            return count;
        }

        private static Cursor cursor(String[] projection, Row row) {
            MatrixCursor cursor = new MatrixCursor(projection);
            addRow(cursor, projection, row);
            return cursor;
        }

        private static void addRow(MatrixCursor cursor, String[] projection, Row row) {
            Object[] values = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                if (MediaStore.MediaColumns._ID.equals(projection[i])) {
                    values[i] = row.id;
                } else if (MediaStore.MediaColumns.DATA.equals(projection[i])) {
                    values[i] = (row.pending ? row.pendingFile : row.finalFile).getAbsolutePath();
                } else if (MediaStore.MediaColumns.SIZE.equals(projection[i])) {
                    values[i] = (row.pending ? row.pendingFile : row.finalFile).length();
                } else if (MediaStore.MediaColumns.IS_PENDING.equals(projection[i])) {
                    values[i] = row.pending ? 1 : 0;
                } else if (MediaStore.MediaColumns.DISPLAY_NAME.equals(projection[i])) {
                    values[i] = row.displayName;
                } else if (MediaStore.MediaColumns.RELATIVE_PATH.equals(projection[i])) {
                    values[i] = row.relativePath;
                }
            }
            cursor.addRow(values);
        }
    }

    private static final class Row {
        final long id;
        final String displayName;
        final String relativePath;
        final String mimeType;
        final File finalFile;
        final File pendingFile;
        boolean pending;

        Row(long id, String displayName, String relativePath, String mimeType,
            File finalFile, File pendingFile, boolean pending) {
            this.id = id;
            this.displayName = displayName;
            this.relativePath = relativePath;
            this.mimeType = mimeType;
            this.finalFile = finalFile;
            this.pendingFile = pendingFile;
            this.pending = pending;
        }
    }

    private static final class FakePathDatabase extends FilePathDatabase {
        String mappedPath;
        int mappedFlags;
        boolean failRemoval;
        boolean failSave;
        boolean sourceReferenced;
        Runnable onSave;

        FakePathDatabase() {
            super(0);
        }

        @Override
        public boolean hasAnotherRefOnFileAcrossAccounts(String path) {
            return sourceReferenced;
        }

        @Override
        public boolean putPathBlocking(long id, int dc, int type, int flags, String path) {
            if (onSave != null) {
                onSave.run();
            }
            if (failSave) {
                return false;
            }
            mappedPath = path;
            mappedFlags = flags;
            return true;
        }

        @Override
        public CanonicalPathStatus removeCanonicalPathIfMatchesStatus(
                long id, int dc, int type, String path) {
            if (failRemoval) {
                return CanonicalPathStatus.UNCHANGED;
            }
            if (mappedPath == null) {
                return CanonicalPathStatus.ABSENT;
            }
            if (!mappedPath.equals(path)) {
                return CanonicalPathStatus.CHANGED;
            }
            if ((mappedFlags & FLAG_CANONICAL_DOWNLOAD) == 0) {
                return CanonicalPathStatus.UNCHANGED;
            }
            mappedPath = null;
            mappedFlags = 0;
            return CanonicalPathStatus.REMOVED;
        }

        @Override
        public CanonicalPathStatus getCanonicalMappingStatus(
                long id, int dc, int type, String path) {
            if (mappedPath == null) {
                return CanonicalPathStatus.ABSENT;
            }
            if (!mappedPath.equals(path)) {
                return CanonicalPathStatus.CHANGED;
            }
            return (mappedFlags & FLAG_CANONICAL_DOWNLOAD) != 0
                    ? CanonicalPathStatus.CANONICAL : CanonicalPathStatus.UNCHANGED;
        }

        @Override
        public CanonicalPathStatus getCanonicalPathStatus(String path) {
            if (mappedPath == null) {
                return CanonicalPathStatus.ABSENT;
            }
            if (!mappedPath.equals(path)) {
                return CanonicalPathStatus.CHANGED;
            }
            return (mappedFlags & FLAG_CANONICAL_DOWNLOAD) != 0
                    ? CanonicalPathStatus.CANONICAL : CanonicalPathStatus.CHANGED;
        }

        @Override
        public CanonicalPathStatus getCurrentMappingStatus(long id, int dc, int type) {
            return mappedPath == null ? CanonicalPathStatus.ABSENT : CanonicalPathStatus.PRESENT;
        }

        @Override
        public boolean restorePathIfAbsentBlocking(
                long id, int dc, int type, int flags, String path) {
            if (mappedPath != null) {
                return false;
            }
            mappedPath = path;
            mappedFlags = flags;
            return true;
        }
    }
}
