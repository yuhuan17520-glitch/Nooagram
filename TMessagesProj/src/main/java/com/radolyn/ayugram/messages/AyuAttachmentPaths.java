package com.radolyn.ayugram.messages;

import java.io.File;

final class AyuAttachmentPaths {
    private AyuAttachmentPaths() {}

    static File defaultParent(boolean customSavePathEnabled, String customSavePath, File downloads, File privateExternalFiles, File privateFilesFallback) {
        if (customSavePathEnabled && customSavePath != null && !customSavePath.trim().isEmpty()) {
            return new File(new File(downloads, customSavePath.trim()), AyuMessagesController.attachmentsSubfolder);
        }
        File root = privateExternalFiles != null ? privateExternalFiles : privateFilesFallback;
        return new File(root, AyuMessagesController.attachmentsSubfolder);
    }
}
