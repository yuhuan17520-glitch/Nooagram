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
    public void freshDownloadsUseTelegramInsideTheSelectedApplicationsFiles() {
        File root = files.getRoot();
        File directory = NooagramDocumentStorage.directory(root);
        assertEquals(new File(root, "Telegram"), directory);
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
        File current = new File(files.newFolder("Telegram"), "download.apk");
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
}
