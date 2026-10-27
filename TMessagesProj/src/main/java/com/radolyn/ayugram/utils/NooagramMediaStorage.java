package com.radolyn.ayugram.utils;

import org.telegram.messenger.FileLoader;

import java.io.File;

/** Typed internal media directories, independent of the optional public export destination. */
public final class NooagramMediaStorage {
    private NooagramMediaStorage() {}

    public static File root(File selectedDirectory) {
        return "Telegram".equals(selectedDirectory.getName())
                ? selectedDirectory : new File(selectedDirectory, "Telegram");
    }

    public static File directory(File selectedDirectory, int type) {
        String name = switch (type) {
            case FileLoader.MEDIA_DIR_IMAGE -> "Telegram Images";
            case FileLoader.MEDIA_DIR_AUDIO -> "Telegram Audio";
            case FileLoader.MEDIA_DIR_VIDEO -> "Telegram Video";
            case FileLoader.MEDIA_DIR_DOCUMENT -> "Telegram Documents";
            case FileLoader.MEDIA_DIR_FILES -> "Telegram Files";
            case FileLoader.MEDIA_DIR_STORIES -> "Telegram Stories";
            default -> throw new IllegalArgumentException("Not an internal media type: " + type);
        };
        return new File(root(selectedDirectory), name);
    }

    private static String legacyName(File directory) {
        if (directory == null || directory.getParentFile() == null
                || !"Telegram".equals(directory.getParentFile().getName())) return null;
        return switch (directory.getName()) {
            case "Telegram Images" -> "images";
            case "Telegram Audio" -> "audios";
            case "Telegram Video" -> "videos";
            case "Telegram Documents" -> "documents";
            case "Telegram Files" -> "files";
            case "Telegram Stories" -> "stories";
            default -> null;
        };
    }

    public static int typeForMime(String mimeType, int type) {
        if (type != FileLoader.MEDIA_DIR_DOCUMENT || mimeType == null) return type;
        if (mimeType.startsWith("image/")) return FileLoader.MEDIA_DIR_IMAGE;
        if (mimeType.startsWith("audio/")) return FileLoader.MEDIA_DIR_AUDIO;
        if (mimeType.startsWith("video/")) return FileLoader.MEDIA_DIR_VIDEO;
        return type;
    }

    public static File resolve(File directory, String name) {
        File target = new File(directory, name);
        String legacyName = legacyName(directory);
        if (target.exists() || legacyName == null) return target;
        File mediaRoot = directory.getParentFile();
        File legacy = new File(new File(mediaRoot.getParentFile(), legacyName), name);
        if (legacy.isFile()) return legacy;
        // 1.3.6 incorrectly used Telegram itself as the ordinary document directory.
        if (!"files".equals(legacyName) && !"stories".equals(legacyName)) {
            File oldDocument = new File(new File(mediaRoot.getParentFile(), "documents"), name);
            if (oldDocument.isFile()) return oldDocument;
            File looseDocument = new File(mediaRoot, name);
            if (looseDocument.isFile()) return looseDocument;
        }
        return target;
    }

    public static File resolveForDownload(File directory, String name, long expectedSize) {
        return resolveMappedDownload(directory, resolve(directory, name), expectedSize);
    }

    /** Complete old files stay readable; a new transfer always uses its typed directory. */
    public static File resolveMappedDownload(File directory, File file, long expectedSize) {
        if (legacyName(directory) == null) return file;
        File mediaRoot = directory.getParentFile();
        File parent = file.getParentFile();
        boolean legacy = mediaRoot.equals(parent);
        for (String name : new String[]{"images", "audios", "videos", "documents", "files", "stories"}) {
            legacy |= new File(mediaRoot.getParentFile(), name).equals(parent);
        }
        if (legacy && (!file.isFile() || expectedSize <= 0 || file.length() != expectedSize)) {
            return new File(directory, file.getName());
        }
        return file;
    }
}
