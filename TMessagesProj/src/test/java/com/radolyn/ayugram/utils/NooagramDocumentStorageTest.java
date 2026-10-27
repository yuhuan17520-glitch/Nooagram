package com.radolyn.ayugram.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class NooagramDocumentStorageTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void freshDownloadsUseTelegramDocumentsInsideTheSelectedApplicationsFiles() {
        File root = files.getRoot();
        File directory = NooagramDocumentStorage.directory(root);
        assertEquals(new File(root, "Telegram/Telegram Documents"), directory);
        assertEquals(new File(directory, "download.apk"), NooagramDocumentStorage.resolve(directory, "download.apk"));
    }

    @Test
    public void oldDocumentsStayReadableWithoutMovingOrChangingTheirBytes() throws Exception {
        File legacy = files.newFolder("documents");
        File saved = new File(legacy, "download.apk");
        byte[] bytes = {1, 3, 5, 7};
        Files.write(saved.toPath(), bytes);
        File directory = NooagramDocumentStorage.directory(files.getRoot());
        assertEquals(saved, NooagramDocumentStorage.resolve(directory, saved.getName()));
        assertArrayEquals(bytes, Files.readAllBytes(saved.toPath()));
        assertFalse(new File(directory, saved.getName()).exists());
    }

    @Test
    public void currentFileTakesPrecedenceOverLegacyFileWithTheSameName() throws Exception {
        File legacy = new File(files.newFolder("documents"), "download.apk");
        File current = new File(files.newFolder("Telegram", "Telegram Documents"), "download.apk");
        Files.write(legacy.toPath(), new byte[]{1});
        Files.write(current.toPath(), new byte[]{2});
        assertEquals(current, NooagramDocumentStorage.resolve(current.getParentFile(), current.getName()));
    }

    @Test
    public void unrelatedCacheDirectoriesDoNotReadDocuments() throws Exception {
        File legacy = new File(files.newFolder("documents"), "download.apk");
        Files.write(legacy.toPath(), new byte[]{1});
        File images = files.newFolder("images");
        assertEquals(new File(images, legacy.getName()), NooagramDocumentStorage.resolve(images, legacy.getName()));
    }

    @Test
    public void completeLegacyDownloadsCanBeReusedWithoutCopying() throws Exception {
        File legacy = new File(files.newFolder("documents"), "download.apk");
        Files.write(legacy.toPath(), new byte[]{1, 2, 3});
        File directory = NooagramDocumentStorage.directory(files.getRoot());
        assertEquals(legacy, NooagramDocumentStorage.resolveForDownload(directory, legacy.getName(), 3));
        assertEquals(legacy, NooagramDocumentStorage.resolveMappedDownload(directory, legacy, 3));
        assertFalse(directory.exists());
    }

    @Test
    public void incompleteLegacyFilesDoNotRedirectNewWritesToDocuments() throws Exception {
        File legacy = new File(files.newFolder("documents"), "download.apk");
        byte[] oldBytes = {1, 2};
        Files.write(legacy.toPath(), oldBytes);
        File directory = files.newFolder("Telegram", "Telegram Documents");
        File destination = NooagramDocumentStorage.resolveForDownload(directory, legacy.getName(), 4);
        assertEquals(new File(directory, legacy.getName()), destination);
        Files.write(destination.toPath(), new byte[]{3, 4, 5, 6});
        assertArrayEquals(oldBytes, Files.readAllBytes(legacy.toPath()));
        assertEquals(destination, NooagramDocumentStorage.resolve(directory, legacy.getName()));
    }

    @Test
    public void oversizedLegacyFilesAlsoRedownloadIntoTelegram() throws Exception {
        File legacy = new File(files.newFolder("documents"), "download.apk");
        Files.write(legacy.toPath(), new byte[]{1, 2, 3});
        File directory = NooagramDocumentStorage.directory(files.getRoot());
        assertEquals(new File(directory, legacy.getName()),
                NooagramDocumentStorage.resolveForDownload(directory, legacy.getName(), 2));
        assertEquals(3, legacy.length());
    }

    @Test
    public void missingLegacyDatabaseMappingsResolveToTheCurrentDirectory() {
        File legacy = new File(new File(files.getRoot(), "documents"), "download.apk");
        File directory = NooagramDocumentStorage.directory(files.getRoot());
        assertEquals(new File(directory, legacy.getName()),
                NooagramDocumentStorage.resolveMappedDownload(directory, legacy, 4));
    }

    @Test
    public void incompleteLegacyDatabaseMappingsResolveToTheCurrentDirectory() throws Exception {
        File legacy = new File(files.newFolder("documents"), "download.apk");
        Files.write(legacy.toPath(), new byte[]{1});
        File directory = NooagramDocumentStorage.directory(files.getRoot());
        assertEquals(new File(directory, legacy.getName()),
                NooagramDocumentStorage.resolveMappedDownload(directory, legacy, 4));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(legacy.toPath()));
    }

    @Test
    public void unknownSizesDoNotTreatLegacyFilesAsComplete() throws Exception {
        File legacy = new File(files.newFolder("documents"), "download.apk");
        Files.write(legacy.toPath(), new byte[]{1});
        File directory = NooagramDocumentStorage.directory(files.getRoot());
        for (long size : new long[]{0, -1}) {
            assertEquals(new File(directory, legacy.getName()),
                    NooagramDocumentStorage.resolveForDownload(directory, legacy.getName(), size));
        }
    }

    @Test
    public void customExportMappingsAreNotRedirected() throws Exception {
        File directory = NooagramDocumentStorage.directory(files.getRoot());
        File custom = new File(files.newFolder("my-downloads"), "download.apk");
        Files.write(custom.toPath(), new byte[]{1});
        assertEquals(custom, NooagramDocumentStorage.resolveMappedDownload(directory, custom, 4));
        File otherDocuments = new File(files.newFolder("another-volume", "documents"), "download.apk");
        assertEquals(otherDocuments, NooagramDocumentStorage.resolveMappedDownload(directory, otherDocuments, 4));
    }
}
