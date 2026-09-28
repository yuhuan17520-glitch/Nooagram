import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Standalone JDK runner: production logic runs against offline dependency doubles.
public class RegressionRunner {
    private static final Map<String, String> STUBS = new LinkedHashMap<>();

    private static void stub(String name, String body) {
        int dot = name.lastIndexOf('.');
        STUBS.put(name, "package " + name.substring(0, dot) + ";\n" + body);
    }

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path javaRoot = repo.resolve("TMessagesProj/src/main/java");
        Path output = repo.resolve("build/ayu-privacy-history-regression");
        Files.createDirectories(output);
        dependencyDoubles();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            ArrayList<JavaFileObject> sources = new ArrayList<>();
            List<String> production = List.of(
                    "com/radolyn/ayugram/utils/AyuGhostUtils.java",
                    "com/radolyn/ayugram/utils/AyuState.java",
                    "com/radolyn/ayugram/utils/AyuStateVariable.java",
                    "com/radolyn/ayugram/utils/network/TLRPCWrappedBypass.java",
                    "com/radolyn/ayugram/controllers/AyuGhostController.java",
                    "com/radolyn/ayugram/AyuGhostConfig.java",
                    "com/radolyn/ayugram/database/AyuData.java",
                    "com/radolyn/ayugram/database/AyuDataLock.java",
                    "com/radolyn/ayugram/database/LockedDao.java",
                    "com/radolyn/ayugram/database/AyuDatabaseMerger.java");
            var paths = new ArrayList<>(production.stream().map(javaRoot::resolve).toList());
            paths.add(repo.resolve("tools/ayu-privacy-history-regression/PrivacyHistoryProbe.java"));
            manager.getJavaFileObjectsFromPaths(paths).forEach(sources::add);
            STUBS.forEach((name, text) -> sources.add(source(name, text)));
            sources.add(historyMethods(compiler, javaRoot.resolve("com/radolyn/ayugram/ui/AyuMessageHistory.java")));
            if (!compiler.getTask(null, manager, null,
                    List.of("-encoding", "UTF-8", "-d", output.toString()), null, sources).call()) {
                throw new AssertionError("Focused production compilation failed");
            }
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{
                output.toUri().toURL(), Path.of(args[1]).toUri().toURL()
        }, ClassLoader.getPlatformClassLoader())) {
            loader.loadClass("PrivacyHistoryProbe").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[]{output.toString()});
        }
    }

    private static JavaFileObject source(String name, String content) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
        };
    }

    private static JavaFileObject historyMethods(JavaCompiler compiler, Path history) throws Exception {
        // Parse the UI source using javac, then run only its account/lifecycle methods.
        // The full Android view hierarchy is deliberately outside this local harness.
        Map<String, String> methods = new LinkedHashMap<>();
        String original = Files.readString(history);
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(List.of(history)));
            var positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                for (var definition : unit.getTypeDecls()) {
                    if (!(definition instanceof ClassTree type)) continue;
                    for (var member : type.getMembers()) {
                        if (member instanceof MethodTree method) {
                            int start = (int) positions.getStartPosition(unit, method.getBody());
                            int end = (int) positions.getEndPosition(unit, method.getBody());
                            methods.putIfAbsent(method.getName().toString(), original.substring(start, end));
                        }
                    }
                }
            }
        }
        if (methods.get("createView").contains("UserConfig.selectedAccount")) {
            throw new AssertionError("History cells still use the foreground account");
        }
        String body = """
                import java.util.*;
                import org.telegram.messenger.*;
                import com.radolyn.ayugram.AyuConstants;
                import com.radolyn.ayugram.messages.AyuMessagesController;
                public class HistoryAccountProbe {
                    private int currentAccount = UserConfig.selectedAccount;
                    private MessageObject messageObject;
                    public List<AyuMessagesController.Revision> messages;
                    private int rowCount;
                    private Runnable showEmptyViewRunnable;
                    private Dummy listView, scrimPopupWindow;
                    public int refreshes;
                    private void setCurrentAccount(int account) { currentAccount = account; }
                    public int getCurrentAccount() { return currentAccount; }
                    private UserConfig getUserConfig() { return UserConfig.getInstance(currentAccount); }
                    private NotificationCenter getNotificationCenter() { return NotificationCenter.getInstance(currentAccount); }
                    private void cacheAttachmentFileNames() {}
                    private void rebuildMessageObjects() {}
                    private void updateEmptyView() { refreshes++; }
                    private void handleVoiceTranscriptionUpdate(Object... args) { refreshes++; }
                    private static class Dummy {
                        Dummy getAdapter() { return this; }
                        void notifyDataSetChanged() {}
                        void dismiss() {}
                        void setAdapter(Object value) {}
                        void setOnItemClickListener(Object value) {}
                    }
                    private static class Bulletin { static void removeDelegate(Object value) {} }
                    private static class RecyclerListView { interface OnItemClickListener {} }
                """
                + "public HistoryAccountProbe(MessageObject messageObject) " + methods.get("<init>")
                + "private void updateHistory() " + methods.get("updateHistory")
                + "public boolean onFragmentCreate() " + methods.get("onFragmentCreate").replace("super.onFragmentCreate();", "")
                + "public void onFragmentDestroy() " + methods.get("onFragmentDestroy").replace("super.onFragmentDestroy();", "")
                + "public void didReceivedNotification(int id, int account, Object... args) " + methods.get("didReceivedNotification")
                + "}";
        return source("HistoryAccountProbe", body);
    }

    private static void dependencyDoubles() {
        stub("android.content.SharedPreferences", """
                public class SharedPreferences {
                    private final java.util.Map<String, Object> values = new java.util.HashMap<>();
                    public boolean getBoolean(String key, boolean fallback) { return (boolean) values.getOrDefault(key, fallback); }
                    public int getInt(String key, int fallback) { return (int) values.getOrDefault(key, fallback); }
                    public Editor edit() { return new Editor(); }
                    public class Editor {
                        public Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
                        public Editor putInt(String key, int value) { values.put(key, value); return this; }
                        public void apply() {}
                    }
                }
                """);
        stub("android.content.Context", """
                public class Context {
                    public static final int MODE_PRIVATE = 0;
                    public static java.io.File root;
                    private final java.util.Map<String, SharedPreferences> prefs = new java.util.HashMap<>();
                    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs.computeIfAbsent(name, key -> new SharedPreferences()); }
                    public java.io.File getDatabasePath(String name) { return new java.io.File(root, name); }
                    public boolean deleteDatabase(String name) { return getDatabasePath(name).delete(); }
                }
                """);
        stub("android.util.LongSparseArray", """
                public class LongSparseArray<T> {
                    private final java.util.Map<Long, T> values = new java.util.HashMap<>();
                    public T get(long key) { return values.get(key); }
                    public void put(long key, T value) { values.put(key, value); }
                }
                """);
        stub("org.telegram.messenger.ApplicationLoader", "public class ApplicationLoader { public static android.content.Context applicationContext = new android.content.Context(); }");
        stub("org.telegram.messenger.FileLog", "public class FileLog { public static void d(String text) {} public static void e(Object... values) {} }");
        stub("org.telegram.messenger.UserConfig", """
                public class UserConfig {
                    public static final int MAX_ACCOUNT_COUNT = 3;
                    public static int selectedAccount;
                    private static final UserConfig[] ALL = { new UserConfig(100), new UserConfig(200), new UserConfig(300) };
                    public final long clientUserId;
                    private UserConfig(long userId) { clientUserId = userId; }
                    public long getClientUserId() { return clientUserId; }
                    public static UserConfig getInstance(int account) { return ALL[account]; }
                    public static boolean isValidAccount(int account) { return account >= 0 && account < MAX_ACCOUNT_COUNT; }
                }
                """);
        stub("org.telegram.messenger.BaseController", "public class BaseController { protected final int currentAccount; public BaseController(int account) { currentAccount = account; } }");
        stub("org.telegram.messenger.DialogObject", "public class DialogObject { public static int getEncryptedChatId(long id) { return (int) (id >> 32); } }");
        stub("org.telegram.messenger.MessageObject", """
                public class MessageObject {
                    public int currentAccount;
                    public boolean voice, round;
                    public org.telegram.tgnet.TLRPC.Message messageOwner = new org.telegram.tgnet.TLRPC.Message();
                    public int getId() { return messageOwner.id; }
                    public long getDialogId() { return messageOwner.dialog_id; }
                    public boolean isVoice() { return voice; }
                    public boolean isRoundVideo() { return round; }
                }
                """);
        stub("org.telegram.messenger.MessagesController", """
                public class MessagesController {
                    public static final int[] differences = new int[3];
                    private final int account;
                    private MessagesController(int account) { this.account = account; }
                    public static MessagesController getInstance(int account) { return new MessagesController(account); }
                    public static android.content.SharedPreferences getGlobalMainSettings() { return ApplicationLoader.applicationContext.getSharedPreferences("main", 0); }
                    public static org.telegram.tgnet.TLRPC.InputChannel getInputChannel(org.telegram.tgnet.TLRPC.InputPeer peer) {
                        var channel = new org.telegram.tgnet.TLRPC.InputChannel(); channel.channel_id = peer.channel_id; return channel;
                    }
                    public org.telegram.tgnet.TLRPC.InputPeer getInputPeer(org.telegram.tgnet.TLRPC.InputPeer peer) { return peer; }
                    public org.telegram.tgnet.TLRPC.EncryptedChat getEncryptedChat(int id) { return null; }
                    public void processNewDifferenceParams(int a, int b, int c, int d) { differences[account]++; }
                    public org.telegram.ui.Stories.StoriesController getStoriesController() { return new org.telegram.ui.Stories.StoriesController(); }
                }
                """);
        stub("org.telegram.messenger.Utilities", """
                public class Utilities {
                    public static final Queue stageQueue = new Queue(), globalQueue = new Queue();
                    public static class Queue {
                        private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
                        public void postRunnable(Runnable task) { tasks.add(task); }
                        public void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
                    }
                }
                """);
        stub("org.telegram.messenger.MessagesStorage", """
                public class MessagesStorage {
                    public static MessagesStorage getInstance(int account) { return new MessagesStorage(); }
                    public Utilities.Queue getStorageQueue() { return Utilities.globalQueue; }
                    public void getDialogMaxMessageId(long dialogId, java.util.function.Consumer<Integer> callback) { callback.accept(42); }
                }
                """);
        stub("org.telegram.messenger.AndroidUtilities", """
                public class AndroidUtilities {
                    public static java.io.File cache;
                    public static java.io.File getCacheDir() { return cache; }
                    public static void runOnUIThread(Runnable task, long delay) { task.run(); }
                    public static void cancelRunOnUIThread(Runnable task) {}
                }
                """);
        stub("org.telegram.messenger.NotificationCenter", """
                public class NotificationCenter {
                    public static final int mainUserInfoChanged = 1, voiceTranscriptionUpdate = 2;
                    private static final NotificationCenter[] ALL = { new NotificationCenter(), new NotificationCenter(), new NotificationCenter() };
                    public final java.util.Set<Object> observers = new java.util.HashSet<>();
                    public static NotificationCenter getInstance(int account) { return ALL[account]; }
                    public void postNotificationName(int id, Object... args) {}
                    public void addObserver(Object observer, int id) { observers.add(observer); }
                    public void removeObserver(Object observer, int id) { observers.remove(observer); }
                }
                """);
        stub("org.telegram.messenger.LocaleController", "public class LocaleController { public static String getString(int id) { return Integer.toString(id); } }");
        stub("org.telegram.messenger.R", "public class R { public static class string { public static final int SuggestGhostModeBeforeStoryTitle=1, SuggestGhostModeBeforeStoryMessage=2, SuggestGhostModeBeforeStoryEnable=3, SuggestGhostModeBeforeStorySkip=4; } }");
        stub("org.telegram.ui.Stories.StoriesController", "public class StoriesController { public boolean hasUnreadStories(long id) { return false; } }");
        stub("org.telegram.ui.ActionBar.AlertDialog", """
                public class AlertDialog {
                    public AlertDialog(android.content.Context context, int style) {}
                    public void setTitle(String text) {} public void setMessage(String text) {}
                    public void setPositiveButton(String text, java.util.function.BiConsumer<Object, Integer> callback) {}
                    public void setNegativeButton(String text, java.util.function.BiConsumer<Object, Integer> callback) {}
                    public void setOnCancelListener(java.util.function.Consumer<Object> callback) {}
                    public void show() {}
                }
                """);
        stub("com.radolyn.ayugram.AyuWorker", """
                public class AyuWorker {
                    public static final boolean[] pending = new boolean[3];
                    public static void setOnline(int account, boolean offline) { pending[account] = offline; }
                    public static void clearOnline(int account) { pending[account] = false; }
                }
                """);
        stub("com.radolyn.ayugram.AyuConstants", "public class AyuConstants { public static final String AYU_DATABASE = \"ayu-data\"; public static final int MESSAGE_EDITED_NOTIFICATION = 3; }");
        stub("com.radolyn.ayugram.utils.AyuGhostPreferences", """
                public class AyuGhostPreferences {
                    public static final int TYPE_DEFAULT=0, TYPE_FORCE_BLOCK=1, TYPE_FORCE_ALLOW=2;
                    public static final java.util.Map<Long, Integer> reads = new java.util.HashMap<>();
                    public static int getReadException(long id) { return reads.getOrDefault(id, 0); }
                    public static int getTypingException(long id) { return 0; }
                    public static boolean shouldBlockWhenGlobalDisabled(int type) { return type != TYPE_FORCE_ALLOW; }
                    public static boolean shouldBlockWhenGlobalEnabled(int type) { return type == TYPE_FORCE_BLOCK; }
                }
                """);
        stub("xyz.nextalone.nagram.NaConfig", """
                public class NaConfig {
                    public static final NaConfig INSTANCE = new NaConfig();
                    public Item getSilentMessageByDefault() { return new Item(); }
                    public Item getEnableSaveDeletedMessages() { return new Item(); }
                    public static class Item { public boolean Bool() { return false; } public void setConfigBool(boolean value) {} }
                }
                """);
        telegramDoubles();
        databaseDoubles();
    }

    private static void telegramDoubles() {
        stub("org.telegram.tgnet.InputSerializedData", "public class InputSerializedData {}");
        stub("org.telegram.tgnet.OutputSerializedData", "public class OutputSerializedData {}");
        stub("org.telegram.tgnet.TLObject", """
                public class TLObject {
                    public void readParams(InputSerializedData data, boolean exception) {}
                    public void serializeToStream(OutputSerializedData data) {}
                    public TLObject deserializeResponse(InputSerializedData data, int constructor, boolean exception) { return null; }
                    public void freeResources() {}
                    public int getObjectSize() { return 0; }
                }
                """);
        stub("org.telegram.tgnet.RequestDelegate", "public interface RequestDelegate { void run(TLObject response, TLRPC.TL_error error); }");
        StringBuilder tl = new StringBuilder("""
                public class TLRPC {
                    public static class InputPeer extends TLObject { public long user_id, chat_id, channel_id; }
                    public static class TL_inputPeerChannel extends InputPeer {}
                    public static class InputChannel extends TLObject { public long channel_id; }
                    public static class TL_inputEncryptedChat extends TLObject { public int chat_id; public long access_hash; }
                    public static class EncryptedChat { public int id; public long access_hash; }
                    public static class Message { public int id, date; public long dialog_id; public InputPeer peer_id = new InputPeer(); }
                    public static class TL_inputDialogPeer extends TLObject { public InputPeer peer; }
                    public static class TL_error extends TLObject {}
                    public static class TL_messages_affectedMessages extends TLObject { public int pts, pts_count; }
                    public static class TL_messages_readEncryptedHistory extends TLObject { public TL_inputEncryptedChat peer; public int max_date; }
                    public static class TL_messages_setEncryptedTyping extends TLObject { public TL_inputEncryptedChat peer; }
                    public static class TL_messages_markDialogUnread extends TLObject { public TLObject peer; }
                """);
        for (String name : List.of("messages_setTyping", "messages_readHistory", "messages_readDiscussion",
                "messages_sendMessage", "messages_sendMedia", "messages_sendMultiMedia", "messages_forwardMessages",
                "messages_sendInlineBotResult", "messages_sendReaction", "messages_sendVote", "messages_editMessage",
                "messages_readSavedHistory", "messages_getMessagesViews", "channels_readHistory", "channels_readMessageContents",
                "messages_readMessageContents")) {
            tl.append("public static class TL_").append(name).append(" extends TLObject { public InputPeer peer, to_peer; public InputChannel channel; public int max_id; public boolean increment; public java.util.ArrayList<Integer> id = new java.util.ArrayList<>(); }");
        }
        for (String name : List.of("messages_sendPaidReaction", "messages_createChat", "channels_createChannel", "channels_leaveChannel",
                "messages_updatePinnedMessage", "messages_forwardMessage", "messages_sendEncrypted", "messages_sendEncryptedFile",
                "messages_sendEncryptedMultiMedia", "messages_sendEncryptedService")) {
            tl.append("public static class TL_").append(name).append(" extends TLObject {}");
        }
        stub("org.telegram.tgnet.TLRPC", tl.append('}').toString());
        stub("org.telegram.tgnet.tl.TL_account", "public class TL_account { public static class updateStatus extends org.telegram.tgnet.TLObject { public boolean offline; } }");
        for (var entry : Map.of(
                "TL_forum", List.of("TL_messages_createForumTopic", "TL_messages_deleteTopicHistory", "TL_messages_editForumTopic"),
                "TL_phone", List.of("requestCall", "acceptCall", "confirmCall"),
                "TL_stories", List.of("TL_stories_sendStory", "TL_stories_sendReaction", "TL_stories_readStories", "TL_stories_incrementStoryViews")
        ).entrySet()) {
            StringBuilder text = new StringBuilder("public class " + entry.getKey() + " {");
            for (String name : entry.getValue()) text.append("public static class ").append(name).append(" extends org.telegram.tgnet.TLObject { public org.telegram.tgnet.TLRPC.InputPeer peer; }");
            stub("org.telegram.tgnet.tl." + entry.getKey(), text.append('}').toString());
        }
        stub("org.telegram.tgnet.ConnectionsManager", """
                public class ConnectionsManager {
                    public record Sent(int account, TLObject request, RequestDelegate callback) {}
                    public static final java.util.List<Sent> sent = new java.util.ArrayList<>();
                    private final int account;
                    private ConnectionsManager(int account) { this.account = account; }
                    public static ConnectionsManager getInstance(int account) { return new ConnectionsManager(account); }
                    public int getCurrentTime() { return 123; }
                    public void sendRequest(TLObject request, RequestDelegate callback) { sent.add(new Sent(account, request, callback)); }
                }
                """);
    }

    private static void databaseDoubles() {
        stub("android.database.Cursor", """
                public class Cursor implements AutoCloseable {
                    private final java.util.List<String> columns = new java.util.ArrayList<>();
                    private final java.util.List<Object[]> rows = new java.util.ArrayList<>();
                    private int position = -1;
                    public Cursor() {}
                    public Cursor(java.sql.ResultSet result) throws java.sql.SQLException {
                        var meta = result.getMetaData();
                        for (int i=1; i<=meta.getColumnCount(); i++) columns.add(meta.getColumnLabel(i));
                        while (result.next()) {
                            Object[] row = new Object[columns.size()];
                            for (int i=0; i<row.length; i++) row[i] = result.getObject(i+1);
                            rows.add(row);
                        }
                    }
                    public boolean moveToFirst() { position = 0; return !rows.isEmpty(); }
                    public boolean moveToNext() { return ++position < rows.size(); }
                    public int getColumnIndex(String name) { return columns.indexOf(name); }
                    public int getInt(int index) { return ((Number) rows.get(position)[index]).intValue(); }
                    public long getLong(int index) { return ((Number) rows.get(position)[index]).longValue(); }
                    public String getString(int index) { Object v = rows.get(position)[index]; return v == null ? null : v.toString(); }
                    public boolean isNull(int index) { return rows.get(position)[index] == null; }
                    public void close() {}
                }
                """);
        stub("android.database.DatabaseUtils", "public class DatabaseUtils { public static String sqlEscapeString(String value) { return \"'\" + value.replace(\"'\", \"''\") + \"'\"; } }");
        stub("android.database.sqlite.SQLiteDatabase", """
                public class SQLiteDatabase {
                    public static final int OPEN_READONLY = 1;
                    private final java.sql.Connection connection;
                    private boolean successful;
                    private SQLiteDatabase(java.sql.Connection connection) { this.connection = connection; }
                    public static SQLiteDatabase openDatabase(String path, Object ignored, int mode) {
                        try { return new SQLiteDatabase(java.sql.DriverManager.getConnection("jdbc:sqlite:" + path)); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }
                    public void execSQL(String sql) { execSQL(sql, new Object[0]); }
                    public void execSQL(String sql, Object[] bindings) {
                        try (var stmt = connection.prepareStatement(sql)) {
                            for (int i=0; i<bindings.length; i++) stmt.setObject(i+1, bindings[i]);
                            stmt.execute();
                        } catch (Exception e) { throw new RuntimeException(sql, e); }
                    }
                    public android.database.Cursor rawQuery(String sql, String[] bindings) {
                        try (var stmt = connection.prepareStatement(sql)) {
                            if (bindings != null) for (int i=0; i<bindings.length; i++) stmt.setString(i+1, bindings[i]);
                            try (var result = stmt.executeQuery()) { return new android.database.Cursor(result); }
                        } catch (Exception e) { throw new RuntimeException(sql, e); }
                    }
                    public int getVersion() { try (var c = rawQuery("PRAGMA user_version", null)) { return c.moveToFirst() ? c.getInt(0) : 0; } }
                    public void beginTransaction() { successful = false; execSQL("BEGIN"); }
                    public void setTransactionSuccessful() { successful = true; }
                    public void endTransaction() { execSQL(successful ? "COMMIT" : "ROLLBACK"); }
                    public void close() { try { connection.close(); } catch (Exception e) { throw new RuntimeException(e); } }
                }
                """);
        stub("androidx.sqlite.db.SimpleSQLiteQuery", "public class SimpleSQLiteQuery { public SimpleSQLiteQuery(String sql) {} }");
        stub("androidx.sqlite.db.SupportSQLiteDatabase", """
                public class SupportSQLiteDatabase {
                    public void execSQL(String sql) {}
                    public android.database.Cursor query(String sql) {
                        if (com.radolyn.ayugram.database.AyuDatabase.failValidation) throw new IllegalStateException("Injected validation failure");
                        return new android.database.Cursor();
                    }
                }
                """);
        stub("androidx.room.migration.Migration", "public abstract class Migration { public Migration(int from, int to) {} public abstract void migrate(androidx.sqlite.db.SupportSQLiteDatabase database); }");
        stub("androidx.room.Room", """
                public class Room {
                    public static Builder databaseBuilder(android.content.Context context, Class<?> type, String name) { return new Builder(); }
                    public static class Builder {
                        public Builder allowMainThreadQueries() { return this; }
                        public Builder fallbackToDestructiveMigrationOnDowngrade() { return this; }
                        public Builder addMigrations(androidx.room.migration.Migration... items) { return this; }
                        public com.radolyn.ayugram.database.AyuDatabase build() { return new com.radolyn.ayugram.database.AyuDatabase(); }
                    }
                }
                """);
        for (String name : List.of("DeletedMessageDao", "DeletedDialogDao", "SpyDao", "RegexFilterDao")) {
            stub("com.radolyn.ayugram.database.dao." + name, "public interface " + name + " {}");
        }
        stub("com.radolyn.ayugram.database.dao.EditedMessageDao", "public interface EditedMessageDao { int getTotalCount(); }");
        stub("com.radolyn.ayugram.database.AyuDatabase", """
                import com.radolyn.ayugram.database.dao.*;
                public class AyuDatabase {
                    public static final int VERSION = 35;
                    public static int created, closed;
                    public static Runnable onClose;
                    public static boolean failValidation;
                    public AyuDatabase() { created++; }
                    public EditedMessageDao editedMessageDao() { return () -> 17; }
                    public DeletedMessageDao deletedMessageDao() { return null; }
                    public DeletedDialogDao deletedDialogDao() { return null; }
                    public SpyDao spyDao() { return null; }
                    public RegexFilterDao regexFilterDao() { return null; }
                    public android.database.Cursor query(androidx.sqlite.db.SimpleSQLiteQuery query) { return new android.database.Cursor(); }
                    public void close() { closed++; if (onClose != null) { Runnable task = onClose; onClose = null; task.run(); } }
                    public Helper getOpenHelper() { return new Helper(); }
                    public static class Helper { public androidx.sqlite.db.SupportSQLiteDatabase getWritableDatabase() { return new androidx.sqlite.db.SupportSQLiteDatabase(); } }
                }
                """);
        stub("com.radolyn.ayugram.database.FilterPrefsMigrator", "public class FilterPrefsMigrator { public static void runIfNeeded() {} }");
        stub("com.radolyn.ayugram.utils.LastSeenHelper", "public class LastSeenHelper { public static void clearCaches() {} }");
        stub("com.radolyn.ayugram.messages.AyuMessagesController", """
                public class AyuMessagesController {
                    public static java.io.File attachmentsPath;
                    public static class Revision { public long fakeId; public Revision(long id) { fakeId = id; } }
                    public static AyuMessagesController getInstance() { return new AyuMessagesController(); }
                    public java.util.List<Revision> getRevisions(long userId, long dialogId, int messageId) { return java.util.List.of(new Revision(userId + 7)); }
                    public static void refreshAfterDatabaseChange() {}
                    public static void syncAttachmentsPathWithConfig() {}
                }
                """);
        stub("tw.nekomimi.nekogram.utils.AndroidUtil", "public class AndroidUtil { public static long getDirectorySize(java.io.File file) { return 0; } }");
        stub("tw.nekomimi.nekogram.utils.FileUtil", """
                public class FileUtil {
                    public static void deleteDirectory(java.io.File file) {
                        // Only the production import's fixed scratch directories are allowed here.
                        var parent = file.getAbsoluteFile().getParentFile();
                        if (!parent.equals(org.telegram.messenger.AndroidUtilities.cache.getAbsoluteFile()) ||
                            !(file.getName().equals("ayu_database_import") || file.getName().equals("ayu_database_backup"))) {
                            throw new AssertionError("Unexpected cleanup target: " + file);
                        }
                        try (var paths = java.nio.file.Files.walk(file.toPath())) {
                            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                                try { java.nio.file.Files.delete(path); } catch (java.io.IOException e) { throw new RuntimeException(e); }
                            });
                        } catch (java.io.IOException e) { throw new RuntimeException(e); }
                    }
                }
                """);
    }
}
