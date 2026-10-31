package com.radolyn.ayugram.utils;

import org.telegram.messenger.FileLoader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    /** Managed cache locations, including old directories; this never moves files. */
    public static List<File> cleanupDirectories(File... directories) {
        ArrayList<File> paths = new ArrayList<>();
        for (File directory : directories) {
            if (directory == null) continue;
            paths.add(directory);
            String oldName = legacyName(directory);
            if (oldName == null) continue;
            File mediaRoot = directory.getParentFile();
            paths.add(new File(mediaRoot.getParentFile(), oldName));
            if ("documents".equals(oldName)) {
                // The 1.3.6 document directory was the root itself. Include only loose files,
                // so clearing documents cannot recursively clear the other typed directories.
                File[] looseFiles = mediaRoot.listFiles(File::isFile);
                if (looseFiles != null) {
                    for (File file : looseFiles) {
                        if (!".nomedia".equals(file.getName())) paths.add(file);
                    }
                }
            }
        }
        return deduplicatePaths(paths);
    }

    /** Removes aliases and children already covered by a recursive parent scan. */
    public static List<File> deduplicatePaths(List<File> paths) {
        ArrayList<File> result = new ArrayList<>();
        ArrayList<String> canonicalPaths = new ArrayList<>();
        for (File path : paths) {
            if (path == null) continue;
            String candidate;
            try {
                candidate = path.getCanonicalPath();
            } catch (IOException e) {
                candidate = path.getAbsolutePath();
            }
            boolean covered = false;
            for (String existing : canonicalPaths) {
                if (candidate.equals(existing) || candidate.startsWith(existing + File.separator)) {
                    covered = true;
                    break;
                }
            }
            if (covered) continue;
            String parent = candidate + File.separator;
            for (int i = canonicalPaths.size() - 1; i >= 0; i--) {
                if (canonicalPaths.get(i).startsWith(parent)) {
                    canonicalPaths.remove(i);
                    result.remove(i);
                }
            }
            // Keep the original spelling used by FilePathDatabase metadata lookups.
            result.add(path);
            canonicalPaths.add(candidate);
        }
        return result;
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
