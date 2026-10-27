package com.radolyn.ayugram.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.telegram.messenger.FileLoader;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class NooagramMediaStorageTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private static final int[] TYPES = {0, 1, 2, 3, 5, 6};
    private static final String[] FOLDERS = {"Telegram Images", "Telegram Audio", "Telegram Video",
            "Telegram Documents", "Telegram Files", "Telegram Stories"};
    private static final String[] OLD_FOLDERS = {"images", "audios", "videos", "documents", "files", "stories"};

    @Test
    public void everyTypeUsesItsOwnFolderBelowTelegram() throws Exception {
        for (String packageName : new String[]{"fork.yuhuan.nooagram", "fork.yuhuan.nooagram.migrationtest"}) {
            File selected = files.newFolder(packageName, "files");
            for (int i = 0; i < TYPES.length; i++) {
                File expected = new File(selected, "Telegram/" + FOLDERS[i]);
                assertEquals(expected, NooagramMediaStorage.directory(selected, TYPES[i]));
                assertEquals(new File(expected, "new-file.dat"), NooagramMediaStorage.resolveForDownload(expected, "new-file.dat", 4));
            }
        }
    }

    @Test
    public void selectedTelegramRootIsNotNestedTwice() throws Exception {
        File root = files.newFolder("sd-card", "Telegram");
        assertEquals(root, NooagramMediaStorage.root(root));
        for (int i = 0; i < TYPES.length; i++) {
            assertEquals(new File(root, FOLDERS[i]), NooagramMediaStorage.directory(root, TYPES[i]));
        }
    }

    @Test
    public void freshTypedDownloadsLeaveTheRootFreeOfLooseFiles() throws Exception {
        File selected = files.newFolder("app", "files");
        for (int i = 0; i < TYPES.length; i++) {
            File directory = NooagramMediaStorage.directory(selected, TYPES[i]);
            assertTrue(directory.mkdirs());
            File destination = NooagramMediaStorage.resolveForDownload(directory, "same-name.bin", 3);
            Files.write(destination.toPath(), new byte[]{1, 2, 3});
            assertEquals(directory, destination.getParentFile());
        }
        File[] entries = NooagramMediaStorage.root(selected).listFiles();
        assertNotNull(entries);
        assertEquals(6, entries.length);
        for (File entry : entries) assertTrue(entry.isDirectory());
    }

    @Test
    public void everyLegacyMediaTypeRemainsReadableAndIsNotRewrittenOnRetry() throws Exception {
        File selected = files.newFolder("app", "files");
        for (int i = 0; i < TYPES.length; i++) {
            File legacyDirectory = new File(selected, OLD_FOLDERS[i]);
            assertTrue(legacyDirectory.mkdir());
            File legacy = new File(legacyDirectory, "old-file.dat");
            byte[] oldBytes = {(byte) i};
            Files.write(legacy.toPath(), oldBytes);
            File directory = NooagramMediaStorage.directory(selected, TYPES[i]);
            assertEquals(legacy, NooagramMediaStorage.resolve(directory, legacy.getName()));
            assertEquals(legacy, NooagramMediaStorage.resolveForDownload(directory, legacy.getName(), 1));
            File fresh = new File(directory, legacy.getName());
            assertEquals(fresh, NooagramMediaStorage.resolveForDownload(directory, legacy.getName(), 2));
            assertEquals(fresh, NooagramMediaStorage.resolveMappedDownload(directory, legacy, 2));
            assertTrue(directory.mkdirs());
            Files.write(fresh.toPath(), new byte[]{7, 8});
            assertArrayEquals(oldBytes, Files.readAllBytes(legacy.toPath()));
            assertEquals(fresh, NooagramMediaStorage.resolve(directory, legacy.getName()));
        }
    }

    @Test
    public void looseDocumentsFrom136RemainReadableButNewWritesUseDocumentsSubfolder() throws Exception {
        File selected = files.newFolder("app", "files");
        File root = new File(selected, "Telegram");
        assertTrue(root.mkdir());
        File loose = new File(root, "archive.zip");
        Files.write(loose.toPath(), new byte[]{1, 2});
        File directory = NooagramMediaStorage.directory(selected, FileLoader.MEDIA_DIR_DOCUMENT);
        assertEquals(loose, NooagramMediaStorage.resolve(directory, loose.getName()));
        assertEquals(loose, NooagramMediaStorage.resolveForDownload(directory, loose.getName(), 2));
        assertEquals(new File(directory, loose.getName()),
                NooagramMediaStorage.resolveForDownload(directory, loose.getName(), 3));
        assertEquals(new File(directory, loose.getName()),
                NooagramMediaStorage.resolveMappedDownload(directory, loose, 3));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(loose.toPath()));
    }

    @Test
    public void ordinaryMediaDocumentsUseTheirMimeTypeWithoutChangingDatabaseKeys() {
        assertEquals(FileLoader.MEDIA_DIR_IMAGE, NooagramMediaStorage.typeForMime("image/jpeg", FileLoader.MEDIA_DIR_DOCUMENT));
        assertEquals(FileLoader.MEDIA_DIR_AUDIO, NooagramMediaStorage.typeForMime("audio/mpeg", FileLoader.MEDIA_DIR_DOCUMENT));
        assertEquals(FileLoader.MEDIA_DIR_VIDEO, NooagramMediaStorage.typeForMime("video/mp4", FileLoader.MEDIA_DIR_DOCUMENT));
        assertEquals(FileLoader.MEDIA_DIR_DOCUMENT, NooagramMediaStorage.typeForMime("application/zip", FileLoader.MEDIA_DIR_DOCUMENT));
        assertEquals(FileLoader.MEDIA_DIR_DOCUMENT, NooagramMediaStorage.typeForMime(null, FileLoader.MEDIA_DIR_DOCUMENT));
        assertEquals(FileLoader.MEDIA_DIR_CACHE, NooagramMediaStorage.typeForMime("image/webp", FileLoader.MEDIA_DIR_CACHE));
        assertEquals(FileLoader.MEDIA_DIR_VIDEO, NooagramMediaStorage.typeForMime("image/gif", FileLoader.MEDIA_DIR_VIDEO));
    }

    @Test
    public void musicPreviouslySavedAsADocumentCanStillBeRead() throws Exception {
        File selected = files.newFolder("app", "files");
        File documents = new File(selected, "documents");
        assertTrue(documents.mkdir());
        File song = new File(documents, "song.mp3");
        Files.write(song.toPath(), new byte[]{1, 2});
        File audio = NooagramMediaStorage.directory(selected, FileLoader.MEDIA_DIR_AUDIO);
        assertEquals(song, NooagramMediaStorage.resolve(audio, song.getName()));
        assertEquals(new File(audio, song.getName()), NooagramMediaStorage.resolveForDownload(audio, song.getName(), 3));
    }

    @Test
    public void publicExportsAndUnrelatedDocumentsMappingsKeepTheirChosenLocation() throws Exception {
        File selected = files.newFolder("app", "files");
        File current = NooagramMediaStorage.directory(selected, FileLoader.MEDIA_DIR_DOCUMENT);
        File external = new File(files.newFolder("Download", "chosen-folder"), "archive.zip");
        assertEquals(external, NooagramMediaStorage.resolveMappedDownload(current, external, 10));
        File unrelated = new File(files.newFolder("another-app", "documents"), "archive.zip");
        assertEquals(unrelated, NooagramMediaStorage.resolveMappedDownload(current, unrelated, 10));
    }
}
