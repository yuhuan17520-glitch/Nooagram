package com.radolyn.ayugram.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Build;
import android.provider.DocumentsContract;

import com.radolyn.ayugram.AyuCustomSavePath;
import com.radolyn.ayugram.AyuOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Resolves legacy folder names, absolute paths, and persisted system-picker trees. */
public final class NooagramDownloadDestination {
    private NooagramDownloadDestination() {}

    public static boolean isTree(String value) {
        return value != null && value.startsWith("content://") && DocumentsContract.isTreeUri(Uri.parse(value));
    }

    public static File directory(String value) {
        if (isTree(value)) return pathFromTree(Uri.parse(value));
        String path = AyuCustomSavePath.normalize(value);
        if (!AyuCustomSavePath.isValidStoragePath(path)) throw new IllegalArgumentException("Invalid download directory");
        if (path.startsWith("/sdcard/")) {
            return new File(Environment.getExternalStorageDirectory(), path.substring(8));
        }
        return path.startsWith("/") ? new File(path)
                : new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), path);
    }

    public static String displayPath(String value) {
        File directory = directory(value);
        return directory == null ? value : directory.getAbsolutePath() + File.separator;
    }

    public static String downloadsFolder(String value) {
        if (isTree(value)) return null;
        File directory = directory(value);
        String root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + File.separator;
        String path = directory.getAbsolutePath();
        return path.startsWith(root) ? path.substring(root.length()) : null;
    }

    public static boolean usesCanonicalDocuments() {
        String value = AyuOptions.customSavePath.String();
        return !value.startsWith("/") && !isTree(value) && downloadsFolder(value) != null;
    }

    public static File pathFromTree(Uri uri) {
        if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
        try {
            String[] parts = DocumentsContract.getTreeDocumentId(uri).split(":", 2);
            File root = "primary".equalsIgnoreCase(parts[0]) ? Environment.getExternalStorageDirectory()
                    : new File("/storage", parts[0]);
            if (parts.length < 2 || parts[1].isEmpty()) return root;
            if (!AyuCustomSavePath.isValidStoragePath("/" + parts[1])) return null;
            return new File(root, parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Uri initialUri(String value) {
        if (isTree(value)) return Uri.parse(value);
        File folder = directory(value);
        if (folder == null) return null;
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        String path = folder.getAbsolutePath();
        if (path.startsWith(root + "/")) {
            return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + path.substring(root.length() + 1));
        }
        return null;
    }

    public static boolean canWrite(Context context, String value) {
        if (matchingTree(context, value) != null) return true;
        if (isTree(value)) return false;
        if (downloadsFolder(value) != null) return true;
        File folder = directory(value);
        if (Build.VERSION.SDK_INT >= 30 && context.getApplicationInfo().targetSdkVersion >= 30
                && !Environment.isExternalStorageManager()
                && !folder.getAbsolutePath().startsWith(context.getFilesDir().getParent() + "/")) {
            File external = context.getExternalFilesDir(null);
            if (external == null || !folder.getAbsolutePath().startsWith(external.getParent() + "/")) return false;
        }
        while (folder != null && !folder.exists()) folder = folder.getParentFile();
        return folder != null && folder.isDirectory() && folder.canWrite();
    }

    private static Uri matchingTree(Context context, String value) {
        File requested = directory(value);
        for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
            if (!grant.isWritePermission()) continue;
            if (isTree(value) && grant.getUri().equals(Uri.parse(value))) return grant.getUri();
            File granted = pathFromTree(grant.getUri());
            if (requested != null && granted != null) {
                String path = requested.getAbsolutePath();
                String parent = granted.getAbsolutePath();
                if (path.equals(parent) || path.startsWith(parent + "/")) return grant.getUri();
            }
        }
        return null;
    }

    public static String save(Context context, File source, NooagramCanonicalDownload.Claim claim,
                              String configured, String subfolder) throws IOException {
        String downloads = downloadsFolder(configured);
        if (downloads != null) {
            String relative = downloads + (subfolder.isEmpty() ? "" : "/" + subfolder);
            return NooagramCanonicalDownload.publish(context, source,
                    new NooagramCanonicalDownload.Claim(Environment.DIRECTORY_DOWNLOADS + "/" + relative,
                            claim.displayName, claim.mimeType), null).file.getAbsolutePath();
        }
        Uri tree = matchingTree(context, configured);
        if (tree != null) return saveToTree(context, tree, configured, subfolder, source, claim).toString();
        if (isTree(configured)) throw new IOException("The selected directory no longer has write access");
        File targetFolder = directory(configured);
        if (targetFolder == null) throw new IOException("Select the destination folder to grant access");
        if (!subfolder.isEmpty()) targetFolder = new File(targetFolder, subfolder);
        if (!targetFolder.isDirectory() && !targetFolder.mkdirs()) throw new IOException("Cannot create download folder");
        File target = null;
        for (int index = 0; index < 10000; index++) {
            File candidate = new File(targetFolder, NooagramCanonicalDownload.numberedFileName(claim.displayName, index));
            if (candidate.createNewFile()) { target = candidate; break; }
        }
        if (target == null) throw new IOException("Cannot allocate download filename");
        try (FileOutputStream output = new FileOutputStream(target)) {
            copy(source, output);
            output.getFD().sync();
        } catch (IOException | RuntimeException e) {
            target.delete();
            throw e;
        }
        return target.getAbsolutePath();
    }

    private static Uri saveToTree(Context context, Uri tree, String configured, String subfolder,
                                   File source, NooagramCanonicalDownload.Claim claim) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        File root = pathFromTree(tree);
        File directory = directory(configured);
        String suffix = root != null && directory != null ? directory.getAbsolutePath().substring(root.getAbsolutePath().length()) : "";
        if (!subfolder.isEmpty()) suffix += "/" + subfolder;
        for (String part : suffix.split("/")) {
            if (part.isEmpty()) continue;
            Uri child = findChild(resolver, tree, parent, part);
            if (child == null) child = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, part);
            if (child == null) throw new IOException("Cannot create download subfolder");
            parent = child;
        }
        String name = claim.displayName;
        for (int index = 1; findChild(resolver, tree, parent, name) != null; index++) {
            if (index > 10000) throw new IOException("Cannot allocate download filename");
            name = NooagramCanonicalDownload.numberedFileName(claim.displayName, index);
        }
        Uri target = DocumentsContract.createDocument(resolver, parent, claim.mimeType, name);
        if (target == null) throw new IOException("Cannot create download file");
        try (OutputStream output = resolver.openOutputStream(target, "w")) {
            if (output == null) throw new IOException("Cannot open download file");
            copy(source, output);
        } catch (IOException | RuntimeException e) {
            try { DocumentsContract.deleteDocument(resolver, target); } catch (Exception ignored) {}
            throw e;
        }
        return target;
    }

    private static Uri findChild(ContentResolver resolver, Uri tree, Uri parent, String name) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, (android.os.Bundle) null, null)) {
            if (cursor == null) throw new IOException("Cannot read destination directory");
            while (cursor.moveToNext()) if (name.equals(cursor.getString(1))) {
                return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
            }
        }
        return null;
    }

    public static boolean savedFileExists(Context context, String location, long length) {
        if (!location.startsWith("content://")) {
            File file = new File(location);
            return file.isFile() && file.length() == length;
        }
        try (Cursor cursor = context.getContentResolver().query(Uri.parse(location),
                new String[]{DocumentsContract.Document.COLUMN_SIZE}, (android.os.Bundle) null, null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getLong(0) == length;
        } catch (RuntimeException e) { return false; }
    }

    private static void copy(File source, OutputStream output) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
        }
    }
}
