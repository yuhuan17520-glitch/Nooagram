package com.radolyn.ayugram.messages;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;

public class AyuDefaultAttachmentsPathTest {
    @Test
    public void disabledCustomPathStaysOutsidePublicDownloads() {
        File downloads = new File("/storage/emulated/0/Download");
        File privateFiles = new File("/storage/emulated/0/Android/data/app/files");

        File path = AyuAttachmentPaths.defaultParent(false, "Nooagram", downloads, privateFiles, privateFiles);

        assertEquals(new File(privateFiles, AyuMessagesController.attachmentsSubfolder), path);
    }

    @Test
    public void enabledCustomPathUsesConfiguredDownloadFolder() {
        File downloads = new File("/storage/emulated/0/Download");
        File privateFiles = new File("/storage/emulated/0/Android/data/app/files");

        File path = AyuAttachmentPaths.defaultParent(true, "Archive", downloads, privateFiles, privateFiles);

        assertEquals(new File(new File(downloads, "Archive"), AyuMessagesController.attachmentsSubfolder), path);
    }

    @Test
    public void blankCustomPathKeepsConfiguredDefaultName() {
        File downloads = new File("/storage/emulated/0/Download");
        File privateFiles = new File("/storage/emulated/0/Android/data/app/files");

        File path = AyuAttachmentPaths.defaultParent(true, "Nooagram", downloads, privateFiles, privateFiles);

        assertEquals(new File(new File(downloads, "Nooagram"), AyuMessagesController.attachmentsSubfolder), path);
    }

    @Test
    public void missingPrivateStorageFallsBackInsideAppFiles() {
        File downloads = new File("/storage/emulated/0/Download");
        File fallback = new File("/data/user/0/app/files");

        File path = AyuAttachmentPaths.defaultParent(false, "Nooagram", downloads, null, fallback);

        assertEquals(new File(fallback, AyuMessagesController.attachmentsSubfolder), path);
    }
}
