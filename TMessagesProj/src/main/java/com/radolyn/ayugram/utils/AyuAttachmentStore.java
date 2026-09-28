package com.radolyn.ayugram.utils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Ownership records live in private app storage, never in the selected directory. */
public final class AyuAttachmentStore {
    private final File directory;
    private final File canonicalDirectory;
    private final File records;

    public AyuAttachmentStore(File parent, File privateRecords, String installationId) throws IOException {
        if (installationId == null || !installationId.matches("[a-zA-Z0-9-]+")) {
            throw new IOException("Invalid attachment store identity");
        }
        directory = new File(parent.getAbsoluteFile(), "Ayu Attachments-" + installationId);
        canonicalDirectory = new File(resolveRealPath(parent), directory.getName());
        records = new File(resolveRealPath(privateRecords), digest(canonicalDirectory.getPath()));
    }

    public File getDirectory() {
        return directory;
    }

    public void prepare() throws IOException {
        if (!resolveRealPath(directory).equals(canonicalDirectory) || Files.isSymbolicLink(directory.toPath())) {
            throw new IOException("Attachment directory is a link or has moved");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create attachment directory");
        }
        if (!isSafeDirectory()) {
            throw new IOException("Attachment directory is a link or has moved");
        }
        File nomedia = new File(directory, ".nomedia");
        if (!Files.exists(nomedia.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            nomedia.createNewFile();
        }
    }

    public boolean owns(File file) {
        try {
            BasicFileAttributes attributes = attributes(file);
            return attributes != null && matchesRecord(file, attributes);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** Called only for a completed app save, not for directory enumeration or imported paths. */
    public void remember(File file) throws IOException {
        BasicFileAttributes attributes = attributes(file);
        if (attributes == null) {
            return;
        }
        if (!records.isDirectory() && !records.mkdirs()) {
            throw new IOException("Cannot create attachment ownership records");
        }
        if (!records.equals(resolveRealPath(records))) {
            throw new IOException("Attachment ownership records are a link");
        }
        File record = recordFor(file);
        if (Files.isSymbolicLink(record.toPath())) {
            throw new IOException("Attachment ownership record is a link");
        }
        // An interrupted record write fails closed: the file is retained by cleanup.
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(record))) {
            output.writeUTF(file.getName());
            output.writeLong(attributes.size());
            output.writeLong(attributes.lastModifiedTime().toMillis());
            output.writeUTF(fileKey(attributes));
        }
    }

    public long trim(long limit, File keepFile, Consumer<File> onDeleted) throws IOException {
        if (limit == Long.MAX_VALUE) {
            return 0L;
        }
        return clean(Math.max(0L, limit), keepFile, false, onDeleted);
    }

    public long clear(Consumer<File> onDeleted) throws IOException {
        return clean(0L, null, true, onDeleted);
    }

    private long clean(long limit, File keepFile, boolean clearAll, Consumer<File> onDeleted) throws IOException {
        if (!isSafeDirectory()) {
            return 0L;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return 0L;
        }
        List<SavedFile> ownedFiles = new ArrayList<>();
        long currentSize = 0L;
        for (File file : files) {
            BasicFileAttributes attributes = attributes(file);
            if (attributes != null && matchesRecord(file, attributes)) {
                ownedFiles.add(new SavedFile(file, attributes));
                currentSize = saturatedAdd(currentSize, attributes.size());
            }
        }
        if (!clearAll && currentSize <= limit) {
            return 0L;
        }
        if (!clearAll) {
            ownedFiles.sort(Comparator.comparingLong(file -> file.modified));
        }
        File keepPath = keepFile == null ? null : resolveRealPath(keepFile);
        long deletedSize = 0L;
        for (SavedFile saved : ownedFiles) {
            if (!clearAll && currentSize <= limit) {
                break;
            }
            if (keepPath != null && keepPath.equals(resolveRealPath(saved.file))) {
                continue;
            }
            // Recheck ownership and canonical containment immediately before each unlink.
            BasicFileAttributes current = attributes(saved.file);
            if (current != null && saved.matches(current) && matchesRecord(saved.file, current) && saved.file.delete()) {
                currentSize -= saved.size;
                deletedSize = saturatedAdd(deletedSize, saved.size);
                recordFor(saved.file).delete();
                onDeleted.accept(saved.file);
            }
        }
        return deletedSize;
    }

    private boolean isSafeDirectory() throws IOException {
        return Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
                && resolveRealPath(directory).equals(canonicalDirectory);
    }

    private BasicFileAttributes attributes(File file) throws IOException {
        if (file == null || file.getName().startsWith(".") || !isSafeDirectory()
                || !directory.equals(file.getAbsoluteFile().getParentFile())
                || !resolveRealPath(file).equals(new File(canonicalDirectory, file.getName()))
                || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean matchesRecord(File file, BasicFileAttributes attributes) {
        File record = recordFor(file);
        try {
            if (!records.equals(resolveRealPath(records))
                    || !Files.isRegularFile(record.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            try (DataInputStream input = new DataInputStream(new FileInputStream(record))) {
                return input.readUTF().equals(file.getName())
                        && input.readLong() == attributes.size()
                        && input.readLong() == attributes.lastModifiedTime().toMillis()
                        && input.readUTF().equals(fileKey(attributes))
                        && input.read() == -1;
            }
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private File recordFor(File file) {
        return new File(records, digest(file.getName()));
    }

    private static String fileKey(BasicFileAttributes attributes) {
        return attributes.fileKey() == null ? "" : attributes.fileKey().toString();
    }

    private static File resolveRealPath(File file) throws IOException {
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return file.toPath().toRealPath().toFile();
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null) {
            throw new IOException("Cannot resolve attachment filesystem root");
        }
        return new File(resolveRealPath(parent), file.getName());
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) {
                result.append(Character.forDigit((valueByte >>> 4) & 15, 16));
                result.append(Character.forDigit(valueByte & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class SavedFile {
        final File file;
        final long size;
        final long modified;
        final String key;

        SavedFile(File file, BasicFileAttributes attributes) {
            this.file = file;
            size = attributes.size();
            modified = attributes.lastModifiedTime().toMillis();
            key = fileKey(attributes);
        }

        boolean matches(BasicFileAttributes attributes) {
            return size == attributes.size() && modified == attributes.lastModifiedTime().toMillis()
                    && key.equals(fileKey(attributes));
        }
    }
}
