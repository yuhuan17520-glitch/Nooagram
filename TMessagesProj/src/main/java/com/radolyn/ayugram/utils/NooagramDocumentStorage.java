package com.radolyn.ayugram.utils;

import java.io.File;

/** Internal document cache, separate from the user's optional export directory. */
public final class NooagramDocumentStorage {
    private NooagramDocumentStorage() {}

    public static File directory(File mediaRoot) {
        return new File(mediaRoot, "Telegram");
    }

    public static File resolve(File directory, String name) {
        File target = new File(directory, name);
        if (!target.exists() && "Telegram".equals(directory.getName())) {
            File legacy = new File(new File(directory.getParentFile(), "documents"), name);
            if (legacy.isFile()) return legacy;
        }
        return target;
    }
}
