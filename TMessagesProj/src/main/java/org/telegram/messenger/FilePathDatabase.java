package org.telegram.messenger;

import android.os.Looper;
import android.util.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.ui.Storage.CacheModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class FilePathDatabase {

    public final static int FLAG_LOCALLY_CREATED = 1; //file is locally created, skip file size check in FileLoader
    public final static int FLAG_CANONICAL_DOWNLOAD = 2; //user-owned public download, never cache-delete it

    public enum CanonicalPathStatus {
        CANONICAL,
        REMOVED,
        ABSENT,
        PRESENT,
        UNCHANGED,
        CHANGED,
        ERROR
    }

    private DispatchQueue dispatchQueue;
    private final int currentAccount;

    private SQLiteDatabase database;
    private File cacheFile;
    private File shmCacheFile;

    private final String NULL_PATH = "~null~";
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    private final static int LAST_DB_VERSION = 7;

    private final static String DATABASE_NAME = "file_to_path";
    private final static String DATABASE_BACKUP_NAME = "file_to_path_backup";

    public final static int MESSAGE_TYPE_VIDEO_MESSAGE = 0;

    private final FileMeta metaTmp = new FileMeta();
    boolean databaseCreated;

    public FilePathDatabase(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public void createDatabase(int tryCount, boolean fromBackup) {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (currentAccount != 0) {
            filesDir = new File(filesDir, "account" + currentAccount + "/");
            filesDir.mkdirs();
        }
        cacheFile = new File(filesDir, DATABASE_NAME + ".db");
        shmCacheFile = new File(filesDir, DATABASE_NAME + ".db-shm");

        boolean createTable = false;

        if (!cacheFile.exists()) {
            createTable = true;
        }
        try {
            database = new SQLiteDatabase(cacheFile.getPath());
            database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
            database.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();

            if (createTable) {
                database.executeFast("CREATE TABLE paths(document_id INTEGER, dc_id INTEGER, type INTEGER, path TEXT, flags INTEGER, PRIMARY KEY(document_id, dc_id, type));").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS path_in_paths ON paths(path);").stepThis().dispose();

                database.executeFast("CREATE TABLE paths_by_dialog_id(path TEXT PRIMARY KEY, dialog_id INTEGER, message_id INTEGER, message_type INTEGER);").stepThis().dispose();

                database.executeFast("PRAGMA user_version = " + LAST_DB_VERSION).stepThis().dispose();
            } else {
                int version = database.executeInt("PRAGMA user_version");
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("current files db version = " + version);
                }
                if (version == 0) {
                    throw new Exception("malformed");
                }
                migrateDatabase(version);
                //migration
            }
            if (!fromBackup) {
                createBackup();
            }
            FileLog.d("files db created from_backup= " + fromBackup);
        } catch (Exception e) {
            if (tryCount < 4) {
                if (!fromBackup && restoreBackup()) {
                    createDatabase(tryCount + 1, true);
                    return;
                } else {
                    cacheFile.delete();
                    shmCacheFile.delete();
                    createDatabase(tryCount + 1, false);
                }
            }
            if (BuildVars.DEBUG_VERSION) {
                FileLog.e(e);
            }
        }
    }

    private void migrateDatabase(int version) throws SQLiteException {
        if (version == 1) {
            database.executeFast("CREATE INDEX IF NOT EXISTS path_in_paths ON paths(path);").stepThis().dispose();
            database.executeFast("PRAGMA user_version = " + 2).stepThis().dispose();
            version = 2;
        }
        if (version == 2) {
            database.executeFast("CREATE TABLE paths_by_dialog_id(path TEXT PRIMARY KEY, dialog_id INTEGER);").stepThis().dispose();
            database.executeFast("PRAGMA user_version = " + 3).stepThis().dispose();
            version = 3;
        }
        if (version == 3) {
            database.executeFast("ALTER TABLE paths_by_dialog_id ADD COLUMN message_id INTEGER default 0").stepThis().dispose();
            database.executeFast("ALTER TABLE paths_by_dialog_id ADD COLUMN message_type INTEGER default 0").stepThis().dispose();
            database.executeFast("PRAGMA user_version = " + 4).stepThis().dispose();
            version = 4;
        }
        if (version == 4 || version == 5 || version == 6) {
            try {
                database.executeFast("ALTER TABLE paths ADD COLUMN flags INTEGER default 0").stepThis().dispose();
            } catch (Throwable ignore) {
                FileLog.e(ignore);
            }
            database.executeFast("PRAGMA user_version = " + 7).stepThis().dispose();
        }
    }

    private void createBackup() {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (currentAccount != 0) {
            filesDir = new File(filesDir, "account" + currentAccount + "/");
            filesDir.mkdirs();
        }
        File backupCacheFile = new File(filesDir, DATABASE_BACKUP_NAME + ".db");
        try {
            AndroidUtilities.copyFile(cacheFile, backupCacheFile);
            FileLog.d("file db backup created " + backupCacheFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean restoreBackup() {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (currentAccount != 0) {
            filesDir = new File(filesDir, "account" + currentAccount + "/");
            filesDir.mkdirs();
        }
        File backupCacheFile = new File(filesDir, DATABASE_BACKUP_NAME + ".db");
        if (!backupCacheFile.exists()) {
            return false;
        }
        try {
            return AndroidUtilities.copyFile(backupCacheFile, cacheFile);
        } catch (IOException e) {
            FileLog.e(e);
        }
        return false;
    }

    public String getPath(long documentId, int dc, int type, boolean useQueue) {
        final long start = System.currentTimeMillis();
        final String key = documentId + "_" + dc + "_" + type;
        String path = cache.get(key);
        if (path == NULL_PATH) {
            if (BuildVars.DEBUG_VERSION) {
                FileLog.d("get file path cached null id=" + documentId + " dc=" + dc + " type=" + type + " path=" + null + " in " + (System.currentTimeMillis() - start) + "ms");
            }
            return null;
        } else if (path != null) {
            if (BuildVars.DEBUG_VERSION) {
                FileLog.d("get file path cached id=" + documentId + " dc=" + dc + " type=" + type + " path=" + path + " in " + (System.currentTimeMillis() - start) + "ms");
            }
            return path;
        }
        if (dispatchQueue != null && dispatchQueue.getHandler() != null && Thread.currentThread() == dispatchQueue.getHandler().getLooper().getThread()) {
            useQueue = false;
        }
        if (useQueue) {

            CountDownLatch syncLatch = new CountDownLatch(1);
            String[] res = new String[1];

            postRunnable(() -> {
                ensureDatabaseCreated();
                if (database != null) {
                    SQLiteCursor cursor = null;
                    try {
                        cursor = database.queryFinalized("SELECT path FROM paths WHERE document_id = " + documentId + " AND dc_id = " + dc + " AND type = " + type);
                        if (cursor.next()) {
                            res[0] = cursor.stringValue(0);
                            if (BuildVars.DEBUG_VERSION) {
                                FileLog.d("get file path id=" + documentId + " dc=" + dc + " type=" + type + " path=" + res[0] + " in " + (System.currentTimeMillis() - start) + "ms");
                            }
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    } finally {
                        if (cursor != null) {
                            cursor.dispose();
                        }
                    }
                }
                syncLatch.countDown();
            });
            try {
                syncLatch.await();
            } catch (Exception ignore) {
            }
            if (res[0] != null) {
                cache.put(key, res[0]);
            } else {
                cache.put(key, NULL_PATH);
            }
            return res[0];
        } else {
            if (database == null) {
                return null;
            }
            SQLiteCursor cursor = null;
            String res = null;
            try {
                cursor = database.queryFinalized("SELECT path FROM paths WHERE document_id = " + documentId + " AND dc_id = " + dc + " AND type = " + type);
                if (cursor.next()) {
                    res = cursor.stringValue(0);
                    if (BuildVars.DEBUG_VERSION) {
                        FileLog.d("get file path id=" + documentId + " dc=" + dc + " type=" + type + " path=" + res + " in " + (System.currentTimeMillis() - start) + "ms");
                    }
                }
            } catch (SQLiteException e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            if (res != null) {
                cache.put(key, res);
            } else {
                cache.put(key, NULL_PATH);
            }
            return res;
        }
    }

    public void ensureDatabaseCreated() {
        if (!databaseCreated) {
            if (!NativeLoader.loaded()) {
                int tryCount = 0;
                while (!NativeLoader.loaded()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    tryCount++;
                    if (tryCount > 5) {
                        break;
                    }
                }
            }
            createDatabase(0, false);
            databaseCreated = true;
        }
    }

    public void putPath(long id, int dc, int type, int flags, String path) {
        postRunnable(() -> putPathInternal(id, dc, type, flags, path));
    }

    /** Commits a path before its caller removes the previous file. Returns false on SQLite failure. */
    public boolean putPathBlocking(long id, int dc, int type, int flags, String path) {
        if (dispatchQueue != null && dispatchQueue.getHandler() != null
                && Thread.currentThread() == dispatchQueue.getHandler().getLooper().getThread()) {
            return putPathInternal(id, dc, type, flags, path);
        }
        boolean[] result = new boolean[1];
        CountDownLatch latch = new CountDownLatch(1);
        postRunnable(() -> {
            result[0] = putPathInternal(id, dc, type, flags, path);
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result[0];
    }

    private boolean putPathInternal(long id, int dc, int type, int flags, String path) {
        if (BuildVars.DEBUG_VERSION) {
            FileLog.d("put file path id=" + id + " dc=" + dc + " type=" + type + " path=" + path);
        }
        ensureDatabaseCreated();
        if (database == null) {
            return false;
        }
        SQLitePreparedStatement state = null;
        SQLitePreparedStatement deleteState = null;
        String key = id + "_" + dc + "_" + type;
        try {
            if (path != null) {
                deleteState = database.executeFast("DELETE FROM paths WHERE path = ?");
                deleteState.bindString(1, path);
                if (!stepWriteWithRetry(deleteState)) {
                    return false;
                }

                state = database.executeFast("REPLACE INTO paths VALUES(?, ?, ?, ?, ?)");
                state.requery();
                state.bindLong(1, id);
                state.bindInteger(2, dc);
                state.bindInteger(3, type);
                state.bindString(4, path);
                state.bindInteger(5, flags);
                if (!stepWriteWithRetry(state)) {
                    return false;
                }
                cache.put(key, path);
            } else {
                SQLitePreparedStatement deletePath = database.executeFast(
                        "DELETE FROM paths WHERE document_id = ? AND dc_id = ? AND type = ?");
                try {
                    deletePath.bindLong(1, id);
                    deletePath.bindInteger(2, dc);
                    deletePath.bindInteger(3, type);
                    if (!stepWriteWithRetry(deletePath)) {
                        return false;
                    }
                } finally {
                    deletePath.dispose();
                }
                cache.put(key, NULL_PATH);
            }
            return true;
        } catch (SQLiteException e) {
            FileLog.e(e);
            return false;
        } finally {
            if (deleteState != null) {
                deleteState.dispose();
            }
            if (state != null) {
                state.dispose();
            }
        }
    }

    private boolean stepWriteWithRetry(SQLitePreparedStatement statement) throws SQLiteException {
        for (int attempt = 0; attempt < 5; attempt++) {
            int result = statement.step();
            if (result == 1) {
                return true;
            }
            if (result != -1) {
                return false;
            }
            statement.requery();
            try {
                Thread.sleep(20L << attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public boolean removeCanonicalPathIfMatches(long id, int dc, int type, String path) {
        CanonicalPathStatus status = removeCanonicalPathIfMatchesStatus(id, dc, type, path);
        return status == CanonicalPathStatus.REMOVED || status == CanonicalPathStatus.ABSENT;
    }

    public CanonicalPathStatus removeCanonicalPathIfMatchesStatus(
            long id, int dc, int type, String path) {
        if (path == null) {
            return CanonicalPathStatus.ERROR;
        }
        CanonicalPathStatus[] result = {CanonicalPathStatus.ERROR};
        CountDownLatch latch = new CountDownLatch(1);
        postRunnable(() -> {
            ensureDatabaseCreated();
            if (database != null) {
                SQLiteCursor cursor = null;
                try {
                    cursor = database.queryFinalized(
                            "SELECT path, flags FROM paths WHERE document_id = ?"
                                    + " AND dc_id = ? AND type = ?", id, dc, type);
                    if (!cursor.next()) {
                        result[0] = CanonicalPathStatus.ABSENT;
                    } else if (!path.equals(cursor.stringValue(0))) {
                        result[0] = CanonicalPathStatus.CHANGED;
                    } else if ((cursor.intValue(1) & FLAG_CANONICAL_DOWNLOAD) == 0) {
                        result[0] = CanonicalPathStatus.UNCHANGED;
                    } else {
                        SQLitePreparedStatement deletePath = database.executeFast(
                                "DELETE FROM paths WHERE document_id = ? AND dc_id = ?"
                                        + " AND type = ? AND path = ?");
                        try {
                            deletePath.bindLong(1, id);
                            deletePath.bindInteger(2, dc);
                            deletePath.bindInteger(3, type);
                            deletePath.bindString(4, path);
                            if (stepWriteWithRetry(deletePath)) {
                                cache.put(id + "_" + dc + "_" + type, NULL_PATH);
                                result[0] = CanonicalPathStatus.REMOVED;
                            } else {
                                result[0] = CanonicalPathStatus.UNCHANGED;
                            }
                        } finally {
                            deletePath.dispose();
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    result[0] = CanonicalPathStatus.ERROR;
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CanonicalPathStatus.ERROR;
        }
        return result[0];
    }

    public CanonicalPathStatus getCanonicalMappingStatus(
            long id, int dc, int type, String path) {
        if (path == null) {
            return CanonicalPathStatus.ERROR;
        }
        CanonicalPathStatus[] result = {CanonicalPathStatus.ERROR};
        CountDownLatch latch = new CountDownLatch(1);
        postRunnable(() -> {
            ensureDatabaseCreated();
            if (database == null) {
                latch.countDown();
                return;
            }
            SQLiteCursor cursor = null;
            try {
                cursor = database.queryFinalized(
                        "SELECT path, flags FROM paths WHERE document_id = ?"
                                + " AND dc_id = ? AND type = ?", id, dc, type);
                if (!cursor.next()) {
                    result[0] = CanonicalPathStatus.ABSENT;
                } else if (!path.equals(cursor.stringValue(0))) {
                    result[0] = CanonicalPathStatus.CHANGED;
                } else if ((cursor.intValue(1) & FLAG_CANONICAL_DOWNLOAD) == 0) {
                    result[0] = CanonicalPathStatus.UNCHANGED;
                } else {
                    result[0] = CanonicalPathStatus.CANONICAL;
                }
            } catch (Exception e) {
                FileLog.e(e);
                result[0] = CanonicalPathStatus.ERROR;
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CanonicalPathStatus.ERROR;
        }
        return result[0];
    }

    public CanonicalPathStatus getCurrentMappingStatus(long id, int dc, int type) {
        CanonicalPathStatus[] result = {CanonicalPathStatus.ERROR};
        CountDownLatch latch = new CountDownLatch(1);
        postRunnable(() -> {
            ensureDatabaseCreated();
            if (database == null) {
                latch.countDown();
                return;
            }
            SQLiteCursor cursor = null;
            try {
                cursor = database.queryFinalized(
                        "SELECT path FROM paths WHERE document_id = ?"
                                + " AND dc_id = ? AND type = ?", id, dc, type);
                result[0] = cursor.next()
                        ? CanonicalPathStatus.PRESENT : CanonicalPathStatus.ABSENT;
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CanonicalPathStatus.ERROR;
        }
        return result[0];
    }

    public boolean restorePathIfAbsentBlocking(
            long id, int dc, int type, int flags, String path) {
        if (path == null) {
            return false;
        }
        boolean[] result = new boolean[1];
        CountDownLatch latch = new CountDownLatch(1);
        postRunnable(() -> {
            ensureDatabaseCreated();
            if (database == null) {
                latch.countDown();
                return;
            }
            SQLiteCursor cursor = null;
            try {
                cursor = database.queryFinalized(
                        "SELECT path FROM paths WHERE document_id = ?"
                                + " AND dc_id = ? AND type = ?", id, dc, type);
                if (!cursor.next()) {
                    result[0] = putPathInternal(id, dc, type, flags, path);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result[0];
    }

    /** Fail closed: a database error must not permit cache cleanup to delete a user file. */
    public boolean isCanonicalDownload(String path) {
        if (path == null) {
            return false;
        }
        boolean[] result = new boolean[]{true};
        CountDownLatch latch = new CountDownLatch(1);
        postRunnable(() -> {
            ensureDatabaseCreated();
            if (database == null) {
                latch.countDown();
                return;
            }
            SQLiteCursor cursor = null;
            try {
                cursor = database.queryFinalized(
                        "SELECT flags FROM paths WHERE path = ?", path);
                result[0] = cursor.next()
                        && (cursor.intValue(0) & FLAG_CANONICAL_DOWNLOAD) != 0;
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
        return result[0];
    }

    public CanonicalPathStatus getCanonicalPathStatus(String path) {
        if (path == null) {
            return CanonicalPathStatus.ERROR;
        }
        CanonicalPathStatus[] result = {CanonicalPathStatus.ERROR};
        CountDownLatch latch = new CountDownLatch(1);
        postRunnable(() -> {
            ensureDatabaseCreated();
            if (database == null) {
                latch.countDown();
                return;
            }
            SQLiteCursor cursor = null;
            try {
                cursor = database.queryFinalized(
                        "SELECT flags FROM paths WHERE path = ?", path);
                if (!cursor.next()) {
                    result[0] = CanonicalPathStatus.ABSENT;
                } else if ((cursor.intValue(0) & FLAG_CANONICAL_DOWNLOAD) != 0) {
                    result[0] = CanonicalPathStatus.CANONICAL;
                } else {
                    result[0] = CanonicalPathStatus.CHANGED;
                }
            } catch (Exception e) {
                FileLog.e(e);
                result[0] = CanonicalPathStatus.ERROR;
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CanonicalPathStatus.ERROR;
        }
        return result[0];
    }

    public boolean isCanonicalDownload(long id, int dc, int type, String path) {
        if (path == null) {
            return false;
        }
        boolean[] result = new boolean[1];
        CountDownLatch latch = new CountDownLatch(1);
        postRunnable(() -> {
            ensureDatabaseCreated();
            if (database == null) {
                latch.countDown();
                return;
            }
            SQLiteCursor cursor = null;
            try {
                cursor = database.queryFinalized(
                        "SELECT flags FROM paths WHERE document_id = " + id
                                + " AND dc_id = " + dc + " AND type = " + type
                                + " AND path = ?", path);
                result[0] = cursor.next()
                        && (cursor.intValue(0) & FLAG_CANONICAL_DOWNLOAD) != 0;
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result[0];
    }

    public void checkMediaExistance(ArrayList<MessageObject> messageObjects) {
        if (messageObjects.isEmpty()) {
            return;
        }
        ArrayList<MessageObject> arrayListFinal = new ArrayList<>(messageObjects);

        CountDownLatch syncLatch = new CountDownLatch(1);
        long time = System.currentTimeMillis();
        long[] threadTime = new long[1];
        postToFrontRunnable(() -> {
            long threadTimeLocal = System.currentTimeMillis();
            ensureDatabaseCreated();
            try {
                for (int i = 0; i < arrayListFinal.size(); i++) {
                    MessageObject messageObject = arrayListFinal.get(i);
                    messageObject.checkMediaExistance(false);
                }
                threadTime[0] = System.currentTimeMillis() - threadTimeLocal;
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                syncLatch.countDown();
            }

        });

        try {
            syncLatch.await();
        } catch (InterruptedException e) {
            FileLog.e(e);
        }

        FileLog.d("checkMediaExistance size=" + messageObjects.size() + " time=" + (System.currentTimeMillis() - time) + " thread_time=" + threadTime[0]);

        if (BuildVars.DEBUG_VERSION) {
            if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
                FileLog.e(new Exception("warning, not allowed in main thread"));
            }
        }
    }

    public void clear() {
        cache.clear();
        postRunnable(() -> {
            ensureDatabaseCreated();
            try {
                SQLitePreparedStatement clearPaths = database.executeFast(
                        "DELETE FROM paths WHERE (flags & " + FLAG_CANONICAL_DOWNLOAD + ") = 0");
                try {
                    stepWriteWithRetry(clearPaths);
                } finally {
                    clearPaths.dispose();
                }
                SQLitePreparedStatement clearDialogs = database.executeFast(
                        "DELETE FROM paths_by_dialog_id WHERE 1");
                try {
                    stepWriteWithRetry(clearDialogs);
                } finally {
                    clearDialogs.dispose();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public boolean hasAnotherRefOnFile(String path) {
        CountDownLatch syncLatch = new CountDownLatch(1);
        boolean[] res = new boolean[]{false};
        postRunnable(() -> {
            ensureDatabaseCreated();
            try {
                SQLiteCursor cursor = database.queryFinalized("SELECT document_id FROM paths WHERE path = '" + path + "'");
                if (cursor.next()) {
                    res[0] = true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                syncLatch.countDown();
            }
        });

        try {
            syncLatch.await();
        } catch (InterruptedException e) {
            FileLog.e(e);
        }
        return res[0];
    }

    public void saveFileDialogId(File file, FileMeta fileMeta) {
        if (file == null || fileMeta == null) {
            return;
        }
        postRunnable(() -> {
            ensureDatabaseCreated();
            SQLitePreparedStatement state = null;
            try {
                state = database.executeFast("REPLACE INTO paths_by_dialog_id VALUES(?, ?, ?, ?)");
                state.requery();
                state.bindString(1, shield(file.getPath()));
                state.bindLong(2, fileMeta.dialogId);
                state.bindInteger(3, fileMeta.messageId);
                state.bindInteger(4, fileMeta.messageType);
                state.step();
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
               if (state != null) {
                   state.dispose();
               }
            }
        });
    }

    public FileMeta getFileDialogId(File file, FileMeta metaTmp) {
        if (file == null) {
            return null;
        }
        if (metaTmp == null) {
            metaTmp = this.metaTmp;
        }
        long dialogId = 0;
        int messageId = 0;
        int messageType = 0;
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT dialog_id, message_id, message_type FROM paths_by_dialog_id WHERE path = '" + shield(file.getPath()) + "'");
            if (cursor.next()) {
                dialogId = cursor.longValue(0);
                messageId = cursor.intValue(1);
                messageType = cursor.intValue(2);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        metaTmp.dialogId = dialogId;
        metaTmp.messageId = messageId;
        metaTmp.messageType = messageType;
        return metaTmp;
    }

    private String shield(String path) {
        return path.replace("'","").replace("\"","");
    }

    public DispatchQueue getQueue() {
        ensureQueueExist();
        return dispatchQueue;
    }

    public void removeFiles(List<CacheModel.FileInfo> filesToRemove) {
        postRunnable(() -> {
            try {
                ensureDatabaseCreated();
                database.beginTransaction();
                for (int i = 0; i < filesToRemove.size(); i++) {
                    database.executeFast("DELETE FROM paths_by_dialog_id WHERE path = '" + shield(filesToRemove.get(i).file.getPath()) + "'").stepThis().dispose();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                database.commitTransaction();
            }
        });
    }

    public LongSparseArray<ArrayList<CacheByChatsController.KeepMediaFile>> lookupFiles(ArrayList<? extends CacheByChatsController.KeepMediaFile> keepMediaFiles) {
        CountDownLatch syncLatch = new CountDownLatch(1);
        LongSparseArray<ArrayList<CacheByChatsController.KeepMediaFile>> filesByDialogId = new LongSparseArray<>();
        postRunnable(() -> {
            try {
                ensureDatabaseCreated();
                FileMeta fileMetaTmp = new FileMeta();
                for (int i = 0; i < keepMediaFiles.size(); i++) {
                    FileMeta fileMeta = getFileDialogId(keepMediaFiles.get(i).file, fileMetaTmp);
                    if (fileMeta != null && fileMeta.dialogId != 0) {
                        ArrayList<CacheByChatsController.KeepMediaFile> list = filesByDialogId.get(fileMeta.dialogId);
                        if (list == null) {
                            list = new ArrayList<>();
                            filesByDialogId.put(fileMeta.dialogId, list);
                        }
                        keepMediaFiles.get(i).isStory = fileMeta.messageType == MessageObject.TYPE_STORY;
                        list.add(keepMediaFiles.get(i));
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                syncLatch.countDown();
            }
        });
        try {
            syncLatch.await();
        } catch (InterruptedException e) {
            FileLog.e(e);
        }
        return filesByDialogId;
    }

    private void postRunnable(Runnable runnable) {
        ensureQueueExist();
        dispatchQueue.postRunnable(runnable);
    }

    private void postToFrontRunnable(Runnable runnable) {
        ensureQueueExist();
        dispatchQueue.postToFrontRunnable(runnable);
    }

    private void ensureQueueExist() {
        if (dispatchQueue == null) {
            synchronized (this) {
                if (dispatchQueue == null) {
                    dispatchQueue = new DispatchQueue("files_database_queue_" + currentAccount);
                    dispatchQueue.setPriority(Thread.MAX_PRIORITY);
                }
            }
        }
    }

    public boolean isLocallyCreated(String path) {
        CountDownLatch syncLatch = new CountDownLatch(1);
        boolean[] res = new boolean[]{false};
        postRunnable(() -> {
            ensureDatabaseCreated();
            try {
                SQLiteCursor cursor = database.queryFinalized("SELECT flags FROM paths WHERE path = '" + path + "'");
                if (cursor.next()) {
                    res[0] = (cursor.intValue(0) & FLAG_LOCALLY_CREATED) != 0;
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                syncLatch.countDown();
            }
        });

        try {
            syncLatch.await();
        } catch (InterruptedException e) {
            FileLog.e(e);
        }
        return res[0];
    }

    public static class PathData {
        public final long id;
        public final int dc;
        public final int type;
        public volatile boolean canonicalDestination;

        public PathData(long documentId, int dcId, int type) {
            this.id = documentId;
            this.dc = dcId;
            this.type = type;
        }
    }

    public static class FileMeta {
        public long dialogId;
        public int messageId;
        public int messageType;
        public long messageSize;
    }
}
