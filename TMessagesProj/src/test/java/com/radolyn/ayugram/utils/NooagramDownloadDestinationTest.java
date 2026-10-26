package com.radolyn.ayugram.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = NooagramCanonicalDownloadTest.TestApplication.class)
public class NooagramDownloadDestinationTest {
    private Context context;
    private TestDocuments provider;
    private Uri tree;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        provider = new TestDocuments(Files.createTempDirectory("picked-downloads").toFile());
        ProviderInfo info = new ProviderInfo();
        info.authority = "nooagram.test.documents";
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context, info);
        provider.onCreate();
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        tree = DocumentsContract.buildTreeDocumentUri(info.authority, "root");
        context.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private File source() throws Exception {
        File source = File.createTempFile("manual-download", ".tmp", context.getCacheDir());
        Files.write(source.toPath(), "complete downloaded bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return source;
    }

    private NooagramCanonicalDownload.Claim claim() {
        return new NooagramCanonicalDownload.Claim("Download/Nooagram", "clip.mp4", "video/mp4");
    }

    @Test
    public void chosenTreeReceivesMediaAndRetainsPlaybackSourceWithoutDuplicateOnRetry() throws Exception {
        File source = source();
        String saved = NooagramMediaDownload.saveCompleted(context, "picked-media", source, claim(), tree.toString(), "My Chat");
        assertTrue(saved.startsWith("content://"));
        assertTrue(NooagramDownloadDestination.savedFileExists(context, saved, source.length()));
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(new File(provider.root, "My Chat/clip.mp4").toPath()));
        assertEquals(saved, NooagramMediaDownload.saveCompleted(context, "picked-media", source, claim(), tree.toString(), "My Chat"));
        assertEquals(1, new File(provider.root, "My Chat").list().length);
        assertTrue(source.isFile());
    }

    @Test
    public void absoluteInputOutsideDownloadWritesToThatExactDirectoryAndKeepsExistingFiles() throws Exception {
        File directory = Files.createTempDirectory("manual-full-path").toFile();
        Files.write(new File(directory, "clip.mp4").toPath(), "old bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File source = source();
        String path = directory.getAbsolutePath().replace('\\', '/');
        // The app runs on Android; give it Unix path syntax when Robolectric runs on Windows.
        if (path.length() > 2 && path.charAt(1) == ':') path = path.substring(2);
        String saved = NooagramDownloadDestination.save(context, source, claim(), path, "");
        assertEquals(new File(directory, "clip (1).mp4").getAbsolutePath(), saved);
        assertEquals("old bytes", new String(Files.readAllBytes(new File(directory, "clip.mp4").toPath()), java.nio.charset.StandardCharsets.UTF_8));
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(new File(saved).toPath()));
    }

    @Test
    public void revokedTreeGrantDoesNotSilentlySaveToDefaultDirectory() throws Exception {
        context.getContentResolver().releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertFalse(NooagramDownloadDestination.canWrite(context, tree.toString()));
        File source = source();
        assertThrows(java.io.IOException.class,
                () -> NooagramDownloadDestination.save(context, source, claim(), tree.toString(), ""));
        assertTrue(source.isFile());
    }

    @Test
    public void sdcardAliasExpandsToPrimaryStorageAndLegacyFolderRemainsSupported() {
        assertEquals(new File(android.os.Environment.getExternalStorageDirectory(), "Download/Nooagram"),
                NooagramDownloadDestination.directory("/sdcard/Download/Nooagram"));
        assertEquals("Nooagram", NooagramDownloadDestination.downloadsFolder("Nooagram"));
        assertNull(NooagramDownloadDestination.downloadsFolder("/sdcard/My Files"));
    }

    public static class TestDocuments extends DocumentsProvider {
        final File root;
        TestDocuments(File root) { this.root = root; }
        @Override public boolean onCreate() { return true; }
        private File file(String id) { return "root".equals(id) ? root : new File(root, id.substring(5)); }
        @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{"root_id"}); }
        @Override public Cursor queryDocument(String id, String[] projection) {
            MatrixCursor cursor = new MatrixCursor(projection);
            add(cursor, id, file(id));
            return cursor;
        }
        @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(projection);
            File[] files = file(id).listFiles();
            if (files != null) for (File child : files) add(cursor, id + "/" + child.getName(), child);
            return cursor;
        }
        private void add(MatrixCursor cursor, String id, File file) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                row.add(switch (column) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.getName();
                    case DocumentsContract.Document.COLUMN_SIZE -> file.length();
                    case DocumentsContract.Document.COLUMN_MIME_TYPE -> file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "video/mp4";
                    default -> null;
                });
            }
        }
        @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
            return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
        }
        @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            String id = parent + "/" + name;
            File file = file(id);
            try {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!file.mkdir()) throw new java.io.IOException("mkdir failed");
                } else if (!file.createNewFile()) throw new java.io.IOException("file exists");
            } catch (java.io.IOException e) { throw new FileNotFoundException(e.toString()); }
            return id;
        }
        @Override public void deleteDocument(String id) { file(id).delete(); }
        @Override public boolean isChildDocument(String parent, String child) { return child.startsWith(parent + "/"); }
    }
}
