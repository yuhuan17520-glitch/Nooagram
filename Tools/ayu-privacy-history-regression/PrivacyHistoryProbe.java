import android.database.sqlite.SQLiteDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.AyuGhostConfig;
import com.radolyn.ayugram.AyuWorker;
import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.AyuDatabase;
import com.radolyn.ayugram.database.AyuDatabaseMerger;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.utils.AyuGhostPreferences;
import com.radolyn.ayugram.utils.AyuGhostUtils;
import com.radolyn.ayugram.utils.AyuState;
import com.radolyn.ayugram.utils.network.TLRPCWrappedBypass;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PrivacyHistoryProbe {
    private static int assertions;
    private static int cases;

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        AyuGhostConfig.setGlobalOverride(false);
        accountReadPolicy();
        manualReadDoesNotLeak();
        delayedCallbacksStayOnAccount();
        lockedStatusCombinations();
        historyAccountLifecycle();
        deletePermitSynchronization();
        Path fixtureRoot = Files.createTempDirectory(Path.of(args[0]), "fixtures-");
        deletedDialogMigrationSurvives(fixtureRoot);
        attachmentImportUsesWritableDirectory(fixtureRoot);
        importFailure(fixtureRoot, false);
        importFailure(fixtureRoot, true);
        invalidImportRestores(fixtureRoot);
        revisionDedupe();
        oldSchemaDedupe();
        System.out.println("PASS: " + cases + " cases, " + assertions + " assertions; no network or Android runtime");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void passed(String message) {
        cases++;
        System.out.println("PASS " + message);
    }

    private static TLRPC.InputPeer peer(long id) {
        var peer = new TLRPC.InputPeer();
        peer.user_id = id;
        return peer;
    }

    private static TLRPC.TL_messages_readHistory read(long dialogId) {
        var request = new TLRPC.TL_messages_readHistory();
        request.peer = peer(dialogId);
        request.max_id = 42;
        return request;
    }

    private static boolean blocked(TLObject request, int account) {
        return AyuGhostUtils.interceptRequest(request, null, account).blockRequest();
    }

    private static MessageObject message(int account) {
        var message = new MessageObject();
        message.currentAccount = account;
        message.messageOwner.id = 42;
        message.messageOwner.dialog_id = 70;
        message.messageOwner.peer_id = peer(70);
        return message;
    }

    private static void accountReadPolicy() {
        UserConfig.selectedAccount = 0;
        AyuGhostConfig.setSendReadMessagePackets(0, true);
        AyuGhostConfig.setSendReadMessagePackets(1, false);
        check(blocked(read(70), 1), "R02 background blocked account leaked foreground policy");
        UserConfig.selectedAccount = 1;
        check(!blocked(read(70), 0), "R02 permitted account inherited foreground block");
        var channelRead = new TLRPC.TL_channels_readHistory();
        channelRead.channel = new TLRPC.InputChannel();
        channelRead.channel.channel_id = 72;
        check(blocked(channelRead, 1), "R02 channel read leaked");
        var contents = new TLRPC.TL_messages_readMessageContents();
        contents.id.add(42);
        check(blocked(contents, 1), "R02 content read without peer leaked");
        AyuGhostPreferences.reads.put(70L, AyuGhostPreferences.TYPE_FORCE_ALLOW);
        check(!blocked(read(70), 1), "explicit allow exception must still work");
        AyuGhostPreferences.reads.put(70L, AyuGhostPreferences.TYPE_FORCE_BLOCK);
        check(blocked(read(70), 0), "explicit block exception must still work");
        AyuGhostPreferences.reads.clear();
        for (Method method : AyuState.class.getDeclaredMethods()) {
            check(!method.getName().equals("setAllowReadPacket") && !method.getName().equals("getAllowReadPacket"),
                    "obsolete process-global read API remains");
        }
        Utilities.stageQueue.drain();
        passed("R02 request-account policy, content/channel requests, allow/block exceptions; global API removed");
    }

    private static void manualReadDoesNotLeak() {
        UserConfig.selectedAccount = 0;
        AyuGhostConfig.setSendReadMessagePackets(0, false);
        AyuGhostConfig.setSendReadMessagePackets(1, false);
        ConnectionsManager.sent.clear();
        AyuGhostUtils.markReadOnServer(message(1), false);
        AyuGhostUtils.markReadOnServer(1, 42, peer(70), true);
        check(ConnectionsManager.sent.size() == 2, "manual reads were not sent");
        for (var sent : ConnectionsManager.sent) {
            check(sent.account() == 1, "manual read used selectedAccount");
            check(sent.request() instanceof TLRPCWrappedBypass, "manual authorization must belong to the concrete request");
            check(!blocked(sent.request(), 1), "manual read authorization did not survive dispatch");
        }
        for (int i = 0; i < 3; i++) {
            check(blocked(read(70), 1), "R03 same-dialog future request leaked");
            check(blocked(read(99), 1), "R03 unrelated dialog leaked");
            check(blocked(read(99), 0), "R03 unrelated account leaked");
        }
        Utilities.stageQueue.drain();
        passed("R03 both manual-read overloads authorize only their wrapped request; later reads remain blocked");
    }

    private static void delayedCallbacksStayOnAccount() {
        ConnectionsManager.sent.clear();
        Arrays.fill(AyuWorker.pending, false);
        AyuGhostConfig.setSendOfflinePacketAfterOnline(1, true);
        AyuGhostUtils.markReadOnServer(message(1), false);
        var manual = ConnectionsManager.sent.getFirst();
        UserConfig.selectedAccount = 2;
        manual.callback().run(new TLRPC.TL_messages_affectedMessages(), null);
        check(MessagesController.differences[1] == 1 && MessagesController.differences[2] == 0,
                "manual-read pts update changed accounts");
        check(AyuWorker.pending[1] && !AyuWorker.pending[2], "manual offline callback changed accounts");

        ConnectionsManager.sent.clear();
        Arrays.fill(AyuWorker.pending, false);
        AyuGhostConfig.setMarkReadAfterSend(1, true);
        var send = new TLRPC.TL_messages_sendMessage();
        send.peer = peer(70);
        int[] originalCallbacks = {0};
        var intercepted = AyuGhostUtils.interceptRequest(send, (response, error) -> originalCallbacks[0]++, 1);
        check(!intercepted.blockRequest(), "send should proceed");
        intercepted.effectiveOnComplete().run(new TLRPC.TL_messages_affectedMessages(), null);
        check(ConnectionsManager.sent.isEmpty(), "storage callback must run asynchronously in fixture");
        UserConfig.selectedAccount = 0;
        Utilities.stageQueue.drain();
        Utilities.globalQueue.drain();
        check(originalCallbacks[0] == 1, "original completion was not delivered once");
        check(ConnectionsManager.sent.size() == 1, "read-after-send did not dispatch");
        var after = ConnectionsManager.sent.getFirst();
        check(after.account() == 1 && after.request() instanceof TLRPCWrappedBypass,
                "read-after-send callback lost originating account/request");
        check(AyuWorker.pending[1] && !AyuWorker.pending[0], "offline-after-send changed accounts");
        after.callback().run(new TLRPC.TL_messages_affectedMessages(), null);
        check(MessagesController.differences[1] == 2 && MessagesController.differences[0] == 0,
                "read-after-send pts callback changed accounts");
        check(blocked(read(70), 1) && blocked(read(90), 0), "read-after-send left reusable authorization");
        Utilities.stageQueue.drain();
        passed("R02/R03 delayed manual and read-after-send callbacks retain account across foreground switches");
    }

    private static void lockedStatusCombinations() {
        UserConfig.selectedAccount = 0;
        for (boolean enabled : List.of(false, true)) {
            for (boolean online : List.of(false, true)) {
                for (boolean onlineLocked : List.of(false, true)) {
                    for (boolean offline : List.of(false, true)) {
                        for (boolean offlineLocked : List.of(false, true)) {
                            var settings = AyuGhostConfig.getGhostModeSettingsForAccount(1);
                            settings.sendOnlinePackets = online;
                            settings.sendOnlinePacketsLocked = onlineLocked;
                            settings.sendOfflinePacketAfterOnline = offline;
                            settings.sendOfflinePacketAfterOnlineLocked = offlineLocked;
                            ConnectionsManager.sent.clear();
                            Arrays.fill(AyuWorker.pending, false);
                            AyuGhostConfig.setGhostMode(1, enabled);
                            boolean expectedOnline = onlineLocked ? online : !enabled;
                            boolean expectedOffline = offlineLocked ? offline : enabled;
                            check(settings.sendOnlinePackets == expectedOnline, "online setting lock changed");
                            check(settings.sendOfflinePacketAfterOnline == expectedOffline, "offline setting lock changed");
                            check(ConnectionsManager.sent.size() == 1, "toggle should emit one status request");
                            var sent = ConnectionsManager.sent.getFirst();
                            check(sent.account() == 1, "status sent on selected account");
                            var status = (TL_account.updateStatus) ((TLRPCWrappedBypass) sent.request()).inner;
                            check(status.offline == !expectedOnline, "R21 status contradicts resultant online setting");
                            check(AyuWorker.pending[1] == expectedOffline, "R21 worker contradicts resultant offline setting");
                            check(!AyuWorker.pending[0], "R21 worker touched another account");
                        }
                    }
                }
            }
        }
        AyuGhostConfig.setGlobalOverride(true);
        var shared = AyuGhostConfig.getGhostModeSettingsForAccount(1);
        shared.sendOnlinePackets = false;
        shared.sendOnlinePacketsLocked = true;
        ConnectionsManager.sent.clear();
        AyuGhostConfig.setGhostMode(1, false);
        var status = (TL_account.updateStatus) ((TLRPCWrappedBypass) ConnectionsManager.sent.getFirst().request()).inner;
        check(status.offline, "R21 global override ignored locked offline status");
        AyuGhostConfig.setGlobalOverride(false);
        passed("R21 all 32 master/online/offline/lock combinations and shared global settings");
    }

    private static void historyAccountLifecycle() throws Exception {
        UserConfig.selectedAccount = 0;
        AyuMessagesController.revisionQueries = 0;
        AyuMessagesController.attachmentsPath = Path.of("build/ayu-privacy-history-regression").toFile();
        var historySource = message(1);
        MessageObject.constructions = 0;
        var history = new HistoryAccountProbe(historySource);
        check(history.getCurrentAccount() == 1, "R06 constructor did not bind message account before querying");
        check(AyuMessagesController.revisionQueries == 0, "R24 history queried Room on the constructor thread");
        check(history.messages.isEmpty(), "R24 history snapshot appeared before the storage queue ran");
        history.onFragmentCreate();
        check(NotificationCenter.getInstance(1).observers.contains(history), "R06 registered on wrong account");
        check(!NotificationCenter.getInstance(0).observers.contains(history), "R06 registered on foreground account");
        int refreshes = history.refreshes;
        history.didReceivedNotification(AyuConstants.MESSAGE_EDITED_NOTIFICATION, 0, 70L, 42);
        check(history.refreshes == refreshes, "R06 reacted to another account's revision");
        history.didReceivedNotification(AyuConstants.MESSAGE_EDITED_NOTIFICATION, 1, 70L, 42);
        check(AyuMessagesController.revisionQueries == 0, "R24 history query left the UI queue");
        Utilities.globalQueue.drain();
        check(AyuMessagesController.revisionQueries == 2, "R24 history did not load both queued snapshots");
        check(history.messages.isEmpty(), "R24 storage snapshot was committed before UI dispatch");
        AndroidUtilities.drainUI();
        check(history.messages.getFirst().fakeId == 202, "R24 stale history snapshot replaced the latest one");
        check(history.preparedMessages.size() == 1 && history.messageObjects.size() == 1,
                "R24 prepared history snapshot was not installed");
        check(MessageObject.constructions == 0, "R24 MessageObject was constructed on the storage queue");
        history.bindFirstForTest();
        check(MessageObject.constructions == 1, "R24 MessageObject was not constructed on the UI path");
        check(history.refreshes == refreshes + 1, "R06/R24 latest revision commit did not refresh once");

        var destroyedHistory = new HistoryAccountProbe(message(1));
        destroyedHistory.onFragmentDestroy();
        Utilities.globalQueue.drain();
        AndroidUtilities.drainUI();
        check(destroyedHistory.messages.isEmpty(), "R24 destroyed history committed a late snapshot");

        AyuMessagesController.attachmentsPath = null;
        var noAttachmentsHistory = new HistoryAccountProbe(message(1));
        Utilities.globalQueue.drain();
        AndroidUtilities.drainUI();
        check(noAttachmentsHistory.messages.size() == 1, "R24 null attachments path prevented history loading");

        Path unavailableMarker = Path.of("build/ayu-privacy-history-regression/unavailable-attachments-sentinel");
        Files.deleteIfExists(unavailableMarker);
        Files.createFile(unavailableMarker);
        AyuMessagesController.attachmentsPath = unavailableMarker.toFile();
        var unavailableHistory = new HistoryAccountProbe(message(1));
        Utilities.globalQueue.drain();
        AndroidUtilities.drainUI();
        check(unavailableHistory.messages.size() == 1, "R24 attachment-unavailable sentinel prevented history loading");
        check(Files.isRegularFile(unavailableMarker), "R24 history changed the unavailable attachment sentinel");

        UserConfig.selectedAccount = 2;
        history.onFragmentDestroy();
        check(!NotificationCenter.getInstance(1).observers.contains(history), "R06 left observer on original account");
        passed("R06/R24 history binds account, queries off-UI, applies only the latest snapshot, and drops destroyed-page results");
    }

    private static void deletePermitSynchronization() throws Exception {
        int threads = 8;
        int messagesPerWriter = 2000;
        var executor = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int thread = 0; thread < threads; thread++) {
            final int worker = thread;
            futures.add(executor.submit(() -> {
                start.await();
                long dialogId = worker % 2 == 0 ? 70L : 99L;
                for (int id = 1; id <= messagesPerWriter; id++) {
                    AyuState.permitDeleteMessage(dialogId, id);
                    AyuState.isDeletePermitted(dialogId, id);
                }
                return null;
            }));
        }
        start.countDown();
        for (var future : futures) future.get(20, TimeUnit.SECONDS);
        executor.shutdown();
        check(executor.awaitTermination(5, TimeUnit.SECONDS), "R24 delete-permit workers did not terminate");

        for (long dialogId : List.of(70L, 99L)) {
            for (int id = 1; id <= messagesPerWriter; id++) {
                check(AyuState.isDeletePermitted(dialogId, id), "R24 concurrent delete permit was lost");
            }
        }
        AyuState.permitDeleteMessage(70L, 7);
        AyuState.permitDeleteMessage(70L, 7);
        AyuState.messageDeleted(70L, 7);
        check(!AyuState.isDeletePermitted(70L, 7), "R24 duplicate delete permit survived message cleanup");
        check(!AyuState.isDeletePermitted(70L, messagesPerWriter + 1), "R24 unrelated delete permit appeared");
        passed("R24 delete permits stay consistent across concurrent queue-style producers and consumers");
    }

    private static Migration migration(String fieldName) throws Exception {
        Field field = AyuData.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Migration) field.get(null);
    }

    private static void deletedDialogMigrationSurvives(Path root) throws Exception {
        android.content.Context.root = Files.createDirectories(root.resolve("migration-database")).toFile();
        AndroidUtilities.cache = Files.createDirectories(root.resolve("migration-cache")).toFile();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE DeletedDialog (" +
                        "fakeId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "userId INTEGER NOT NULL, dialogId INTEGER NOT NULL, peerId INTEGER NOT NULL, " +
                        "folderId INTEGER, topMessage INTEGER NOT NULL, lastMessageDate INTEGER NOT NULL, " +
                        "flags INTEGER NOT NULL, entityCreateDate INTEGER NOT NULL)");
                statement.execute("CREATE INDEX index_DeletedDialog_userId_dialogId ON DeletedDialog(userId, dialogId)");
                statement.execute("CREATE INDEX index_DeletedDialog_userId_entityCreateDate ON DeletedDialog(userId, entityCreateDate)");
                statement.execute("INSERT INTO DeletedDialog VALUES (17,1000,-2000,-2000,3,44,123456,5,654321)");
            }

            SupportSQLiteDatabase database = new SupportSQLiteDatabase(connection);
            migration("MIGRATION_29_30").migrate(database);
            migration("MIGRATION_30_31").migrate(database);
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT fakeId, userId, dialogId, peerId, folderId, topMessage, lastMessageDate, flags, entityCreateDate FROM DeletedDialog")) {
                check(row.next(), "R24 v29 DeletedDialog row was dropped by the v31 upgrade");
                check(row.getLong("fakeId") == 17 && row.getLong("userId") == 1000
                                && row.getLong("dialogId") == -2000 && row.getInt("folderId") == 3
                                && row.getInt("topMessage") == 44 && row.getInt("lastMessageDate") == 123456
                                && row.getInt("flags") == 5 && row.getInt("entityCreateDate") == 654321,
                        "R24 v29 DeletedDialog payload changed during migration");
                check(!row.next(), "R24 v29 DeletedDialog migration duplicated its row");
            }
            check(columnShape(connection).equals(
                            "fakeId:INTEGER:1:1|userId:INTEGER:1:0|dialogId:INTEGER:1:0|peerId:INTEGER:1:0|" +
                                    "folderId:INTEGER:0:0|topMessage:INTEGER:1:0|lastMessageDate:INTEGER:1:0|" +
                                    "flags:INTEGER:1:0|entityCreateDate:INTEGER:1:0"),
                    "R24 DeletedDialog schema differs from the Room v31 entity");
            check(indexCount(connection) == 2, "R24 DeletedDialog indexes were lost during migration");
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            migration("MIGRATION_30_31").migrate(new SupportSQLiteDatabase(connection));
            check(columnShape(connection).equals(
                            "fakeId:INTEGER:1:1|userId:INTEGER:1:0|dialogId:INTEGER:1:0|peerId:INTEGER:1:0|" +
                                    "folderId:INTEGER:0:0|topMessage:INTEGER:1:0|lastMessageDate:INTEGER:1:0|" +
                                    "flags:INTEGER:1:0|entityCreateDate:INTEGER:1:0"),
                    "R24 v30 fixture did not receive the Room v31 DeletedDialog schema");
        }
        passed("R24 production v29->30->31 migrations retain DeletedDialog data and Room-compatible schema");
    }

    private static String columnShape(Connection connection) throws Exception {
        var columns = new java.util.ArrayList<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA table_info('DeletedDialog')")) {
            while (rows.next()) {
                columns.add(rows.getString("name") + ":" + rows.getString("type") + ":"
                        + rows.getInt("notnull") + ":" + rows.getInt("pk"));
            }
        }
        return String.join("|", columns);
    }

    private static int indexCount(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND tbl_name='DeletedDialog'")) {
            row.next();
            return row.getInt(1);
        }
    }

    private static void attachmentImportUsesWritableDirectory(Path root) throws Exception {
        Path fixture = Files.createDirectories(root.resolve("attachment-import"));
        Path source = Files.writeString(fixture.resolve("history-attachment.bin"), "attachment payload");
        Path unavailable = Files.createFile(fixture.resolve("unavailable-sentinel"));
        Method copyAttachment = AyuDatabaseMerger.class.getDeclaredMethod("copyAttachment", String.class);
        copyAttachment.setAccessible(true);

        AyuMessagesController.attachmentsPath = unavailable.toFile();
        check(copyAttachment.invoke(null, source.toString()) == null,
                "R24 attachment import bypassed an unavailable writable directory");
        check(Files.isRegularFile(unavailable), "R24 attachment import changed the unavailable sentinel");

        Path writable = Files.createDirectories(fixture.resolve("writable"));
        AyuMessagesController.attachmentsPath = writable.toFile();
        String copiedPath = (String) copyAttachment.invoke(null, source.toString());
        check(copiedPath != null, "R24 attachment import rejected a writable managed directory");
        check(Files.readString(Path.of(copiedPath)).equals("attachment payload"),
                "R24 attachment import corrupted the copied file");
        AyuMessagesController.attachmentsPath = Path.of("build/ayu-privacy-history-regression").toFile();
        passed("R24 attachment import uses the writable managed directory and preserves the unavailable failure path");
    }

    private static Path importFixture(Path root, String name) throws IOException {
        Path fixture = Files.createDirectories(root.resolve(name));
        android.content.Context.root = Files.createDirectories(fixture.resolve("database")).toFile();
        AndroidUtilities.cache = Files.createDirectories(fixture.resolve("cache")).toFile();
        return android.content.Context.root.toPath().resolve(AyuConstants.AYU_DATABASE);
    }

    private static void importFailure(Path root, boolean partialBackup) throws Exception {
        Path live = importFixture(root, partialBackup ? "partial-backup-failure" : "main-backup-failure");
        byte[] original = "original database bytes".getBytes(StandardCharsets.UTF_8);
        byte[] wal = "original WAL bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(live, original);
        Files.write(Path.of(live + "-wal"), wal);
        AyuData.create();
        int created = AyuDatabase.created;
        int closed = AyuDatabase.closed;
        AyuDatabase.onClose = () -> {
            // Turn the intended backup destination into a directory after close.
            // FileOutputStream then fails deterministically before or during backup.
            try {
                String destination = AyuConstants.AYU_DATABASE + (partialBackup ? "-wal" : "");
                Files.createDirectories(AndroidUtilities.cache.toPath().resolve("ayu_database_backup").resolve(destination));
            } catch (IOException e) { throw new RuntimeException(e); }
        };
        boolean failed = false;
        try {
            AyuData.importDatabase(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
        } catch (IOException expected) { failed = true; }
        check(failed, "R22 fixture failed to interrupt backup");
        check(AyuDatabase.closed == closed + 1, "R22 fixture did not close the live database");
        check(AyuDatabase.created == created + 1, "R22 database was not reopened after backup failure");
        check(AyuData.getEditedMessageDao().getTotalCount() == 17, "R22 locked DAO remained unavailable");
        check(Arrays.equals(original, Files.readAllBytes(live)), "R22 incomplete backup replaced/restored live database");
        check(Arrays.equals(wal, Files.readAllBytes(Path.of(live + "-wal"))), "R22 incomplete backup lost live WAL");
        check(!Files.exists(AndroidUtilities.cache.toPath().resolve("ayu_database_import")), "R22 import scratch not removed");
        check(!Files.exists(AndroidUtilities.cache.toPath().resolve("ayu_database_backup")), "R22 partial backup scratch not removed");
        passed("R22 " + (partialBackup ? "partial WAL" : "initial database") + " backup failure reopens DAO and preserves original bytes");
    }

    private static void invalidImportRestores(Path root) throws Exception {
        Path live = importFixture(root, "validation-failure");
        byte[] original = new byte[]{4, 3, 2, 1};
        Files.write(live, original);
        AyuDatabase.failValidation = true;
        boolean failed = false;
        try {
            AyuData.importDatabase(new ByteArrayInputStream(new byte[]{8, 7, 6, 5}));
        } catch (IOException expected) { failed = true; }
        finally { AyuDatabase.failValidation = false; }
        check(failed, "R22 validation failure not propagated");
        check(Arrays.equals(original, Files.readAllBytes(live)), "R22 completed backup was not restored");
        check(AyuData.getEditedMessageDao().getTotalCount() == 17, "R22 failed replacement did not reopen");
        AyuData.importDatabase(new ByteArrayInputStream(new byte[]{9, 9, 9}));
        check(Arrays.equals(new byte[]{9, 9, 9}, Files.readAllBytes(live)), "R22 successful replacement did not persist");
        check(AyuData.getEditedMessageDao().getTotalCount() == 17, "R22 success did not reopen");
        passed("R22 completed backup rolls back validation failure; successful replacement also reopens");
    }

    private static int mergeEdits(SQLiteDatabase database) throws Exception {
        Method merge = AyuDatabaseMerger.class.getDeclaredMethod("mergeEditedMessages", SQLiteDatabase.class);
        merge.setAccessible(true);
        return (int) merge.invoke(null, database);
    }

    private static int count(SQLiteDatabase database) {
        try (var cursor = database.rawQuery("SELECT COUNT(*) FROM main.EditedMessage", null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private static void insertRevision(SQLiteDatabase database, String schema, Object[] values) {
        database.execSQL("INSERT INTO " + schema + ".EditedMessage (userId,dialogId,messageId,entityCreateDate,text,textEntities,documentSerialized,replySerialized,replyMarkupSerialized,richMessageSerialized,mediaPath,hqThumbPath) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", values);
    }

    private static void revisionDedupe() throws Exception {
        SQLiteDatabase database = SQLiteDatabase.openDatabase(":memory:", null, 0);
        try {
            database.execSQL("ATTACH DATABASE ':memory:' AS src");
            String columns = " (fakeId INTEGER PRIMARY KEY, userId INTEGER NOT NULL, dialogId INTEGER NOT NULL, messageId INTEGER NOT NULL, entityCreateDate INTEGER NOT NULL, text TEXT, textEntities BLOB, documentSerialized BLOB, replySerialized BLOB, replyMarkupSerialized BLOB, richMessageSerialized BLOB, mediaPath TEXT, hqThumbPath TEXT)";
            database.execSQL("CREATE TABLE main.EditedMessage" + columns);
            database.execSQL("CREATE TABLE src.EditedMessage" + columns);
            Object[] original = {100L, 70L, 42, 123, "A", null, null, null, null, null, null, null};
            insertRevision(database, "main", original);
            insertRevision(database, "src", original);
            Object[] sameSecondText = original.clone();
            sameSecondText[4] = "B";
            insertRevision(database, "src", sameSecondText);
            insertRevision(database, "src", sameSecondText);
            int expectedNew = 1;
            for (int column = 5; column <= 9; column++) {
                Object[] variant = original.clone();
                variant[column] = new byte[]{(byte) column, 0, 1};
                insertRevision(database, "src", variant);
                expectedNew++;
            }
            Object[] emptyBlob = original.clone();
            emptyBlob[5] = new byte[0];
            insertRevision(database, "src", emptyBlob);
            expectedNew++;
            Object[] otherAccount = original.clone();
            otherAccount[0] = 200L;
            insertRevision(database, "src", otherAccount);
            expectedNew++;
            Object[] mediaOnly = original.clone();
            mediaOnly[10] = "/fixture/second-photo.jpg";
            insertRevision(database, "src", mediaOnly);
            expectedNew++;
            check(mergeEdits(database) == expectedNew, "R23 lost same-second text/blob/account/media-path differences");
            check(count(database) == expectedNew + 1, "R23 exact source duplicates were inserted twice");
            check(mergeEdits(database) == 0, "R23 repeat merge is not idempotent");
            check(count(database) == expectedNew + 1, "R23 repeat merge changed target rows");
            passed("R23 actual SQLite merge keeps 9 distinct same-second variants, dedupes exact rows/nulls, and is idempotent");
        } finally { database.close(); }
    }

    private static void oldSchemaDedupe() throws Exception {
        SQLiteDatabase database = SQLiteDatabase.openDatabase(":memory:", null, 0);
        try {
            database.execSQL("ATTACH DATABASE ':memory:' AS src");
            database.execSQL("CREATE TABLE main.EditedMessage (fakeId INTEGER PRIMARY KEY, userId INTEGER, dialogId INTEGER, messageId INTEGER, entityCreateDate INTEGER, text TEXT, richMessageSerialized BLOB, forwards INTEGER NOT NULL)");
            database.execSQL("CREATE TABLE src.EditedMessage (fakeId INTEGER PRIMARY KEY, userId INTEGER, dialogId INTEGER, messageId INTEGER, entityCreateDate INTEGER, text TEXT)");
            database.execSQL("INSERT INTO main.EditedMessage VALUES (1,100,70,42,123,'A',NULL,0)");
            database.execSQL("INSERT INTO src.EditedMessage VALUES (7,100,70,42,123,'A'), (8,100,70,42,123,'B')");
            check(mergeEdits(database) == 1, "R23 shared-column merge lost legacy revision");
            check(mergeEdits(database) == 0 && count(database) == 2, "R23 legacy schema repeat import duplicated rows");
            passed("R23 older source schema uses shared columns and preserves required target defaults");
        } finally { database.close(); }
    }
}
