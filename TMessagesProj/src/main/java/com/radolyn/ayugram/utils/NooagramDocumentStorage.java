package com.radolyn.ayugram.utils;

import java.io.File;

/** Internal document cache, separate from the user's optional export directory. */
public final class NooagramDocumentStorage {
    private NooagramDocumentStorage() {}

    public static File directory(File mediaRoot) {
        return NooagramMediaStorage.directory(mediaRoot, org.telegram.messenger.FileLoader.MEDIA_DIR_DOCUMENT);
    }

    public static File resolve(File directory, String name) {
        return NooagramMediaStorage.resolve(directory, name);
    }

    /** Reuse complete legacy files, but never redownload into the legacy directory. */
    public static File resolveForDownload(File directory, String name, long expectedSize) {
        return NooagramMediaStorage.resolveForDownload(directory, name, expectedSize);
    }

    /** Applies the same rule to paths remembered in the file database. */
    public static File resolveMappedDownload(File directory, File file, long expectedSize) {
        return NooagramMediaStorage.resolveMappedDownload(directory, file, expectedSize);
    }
}
