import android.database.sqlite.SQLiteDatabase;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.AyuGhostConfig;
import com.radolyn.ayugram.AyuWorker;
import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.AyuDatabase;
import com.radolyn.ayugram.database.AyuDatabaseMerger;
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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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
        Path fixtureRoot = Files.createTempDirectory(Path.of(args[0]), "fixtures-");
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

    private static void historyAccountLifecycle() {
        UserConfig.selectedAccount = 0;
        var history = new HistoryAccountProbe(message(1));
        check(history.getCurrentAccount() == 1, "R06 constructor did not bind message account before querying");
        check(history.messages.getFirst().fakeId == 207, "R06 loaded another account's deletable row");
        history.onFragmentCreate();
        check(NotificationCenter.getInstance(1).observers.contains(history), "R06 registered on wrong account");
        check(!NotificationCenter.getInstance(0).observers.contains(history), "R06 registered on foreground account");
        int refreshes = history.refreshes;
        history.didReceivedNotification(AyuConstants.MESSAGE_EDITED_NOTIFICATION, 0, 70L, 42);
        check(history.refreshes == refreshes, "R06 reacted to another account's revision");
        history.didReceivedNotification(AyuConstants.MESSAGE_EDITED_NOTIFICATION, 1, 70L, 42);
        check(history.refreshes == refreshes + 1, "R06 did not reload its own revision");
        UserConfig.selectedAccount = 2;
        history.onFragmentDestroy();
        check(!NotificationCenter.getInstance(1).observers.contains(history), "R06 left observer on original account");
        passed("R06 source-extracted constructor/query/notification lifecycle binds message account");
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
