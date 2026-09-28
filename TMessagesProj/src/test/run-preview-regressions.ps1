$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('nooagram-preview-tests-' + [Guid]::NewGuid().ToString('N'))
$sources = @{
    'android/os/SystemClock.java' = @'
package android.os;
public class SystemClock { public static long now; public static long elapsedRealtime() { return now; } }
'@
    'android/util/Log.java' = @'
package android.util;
public class Log { public static int e(String tag, String text, Throwable error) { return 0; } }
'@
    'org/telegram/messenger/DispatchQueue.java' = @'
package org.telegram.messenger;
import java.util.*;
public class DispatchQueue {
 public final ArrayDeque<Runnable> pending = new ArrayDeque<>();
 public static boolean inStorage;
 public void postRunnable(Runnable task) { pending.add(task); }
 public void next() { inStorage = true; try { pending.remove().run(); } finally { inStorage = false; } }
}
'@
    'org/telegram/messenger/AndroidUtilities.java' = @'
package org.telegram.messenger;
import java.util.*;
public class AndroidUtilities {
 public static final ArrayDeque<Runnable> ui = new ArrayDeque<>();
 public static final Map<Runnable, Long> delayed = new LinkedHashMap<>();
 public static void runOnUIThread(Runnable task) { ui.add(task); }
 public static void runOnUIThread(Runnable task, long delay) { delayed.put(task, android.os.SystemClock.now + delay); }
 public static void cancelRunOnUIThread(Runnable task) { delayed.remove(task); }
 public static void advance(long ms) {
  android.os.SystemClock.now += ms;
  for (var entry : new ArrayList<>(delayed.entrySet())) {
   if (entry.getValue() <= android.os.SystemClock.now) { delayed.remove(entry.getKey()); ui.add(entry.getKey()); }
  }
 }
}
'@
    'org/telegram/messenger/UserConfig.java' = @'
package org.telegram.messenger;
public class UserConfig {
 public static final int MAX_ACCOUNT_COUNT = 2;
 public static final UserConfig[] instances = {new UserConfig(), new UserConfig()};
 public long userId = 10; public int loginTime = 1;
 public static UserConfig getInstance(int account) { return instances[account]; }
 public long getClientUserId() { return userId; }
}
'@
    'org/telegram/messenger/NotificationCenter.java' = @'
package org.telegram.messenger;
import java.util.*;
public class NotificationCenter {
 public static final int appDidLogout = 1, regexFiltersUpdated = 2, blockedUsersDidLoad = 3;
 public interface NotificationCenterDelegate { void didReceivedNotification(int id, int account, Object... args); }
 public static final NotificationCenter[] instances = {new NotificationCenter(0), new NotificationCenter(1)};
 final int account; final Map<Integer, List<NotificationCenterDelegate>> observers = new HashMap<>();
 NotificationCenter(int account) { this.account = account; }
 public static NotificationCenter getInstance(int account) { return instances[account]; }
 public void addObserver(NotificationCenterDelegate observer, int id) { observers.computeIfAbsent(id, k -> new ArrayList<>()).add(observer); }
 public void postNotificationName(int id) {
  for (var observer : new ArrayList<>(observers.getOrDefault(id, List.of()))) observer.didReceivedNotification(id, account);
 }
}
'@
    'org/telegram/tgnet/NativeByteBuffer.java' = @'
package org.telegram.tgnet;
public class NativeByteBuffer {
 public final TLRPC.Message message;
 public NativeByteBuffer(TLRPC.Message message) { this.message = message; }
 public int readInt32(boolean exception) { return 0; }
 public void reuse() {}
}
'@
    'org/telegram/tgnet/TLRPC.java' = @'
package org.telegram.tgnet;
import java.util.*;
public class TLRPC {
 public static class Message {
  public int id, date, edit_date, send_state; public long dialog_id;
  public boolean unread, out; public Object action; public Reply reply_to; public Message replyMessage;
  public static Message TLdeserialize(NativeByteBuffer data, int constructor, boolean exception) { return data.message; }
  public void readAttachPath(NativeByteBuffer data, long userId) {}
 }
 public static class Reply { public int reply_to_msg_id; }
 public static class TL_messageEmpty extends Message {}
 public static class TL_messageActionChatMigrateTo {}
 public static class TL_messageActionChannelCreate {}
 public static class User {}
 public static class Chat { public boolean megagroup = true; }
 public static class InputPeer { public long dialog; }
 public static class TL_messages_getHistory { public InputPeer peer; public int limit, offset_id; }
 public static class messages_Messages {
  public ArrayList<Message> messages = new ArrayList<>();
  public ArrayList<User> users = new ArrayList<>(); public ArrayList<Chat> chats = new ArrayList<>();
 }
 public static class TL_messages_messagesNotModified extends messages_Messages {}
}
'@
    'org/telegram/tgnet/ConnectionsManager.java' = @'
package org.telegram.tgnet;
import java.util.*;
public class ConnectionsManager {
 public interface Callback { void run(Object response, Object error); }
 public record Pending(int id, TLRPC.TL_messages_getHistory request, Callback callback) {}
 public static final ConnectionsManager instance = new ConnectionsManager();
 public final ArrayDeque<Pending> requests = new ArrayDeque<>();
 public final List<Integer> offsets = new ArrayList<>(); public final List<Integer> cancelled = new ArrayList<>();
 private int nextId;
 public static ConnectionsManager getInstance(int account) { return instance; }
 public int sendRequest(TLRPC.TL_messages_getHistory request, Callback callback) {
  if (request.limit != 80) throw new AssertionError("Unbounded network page");
  requests.add(new Pending(++nextId, request, callback)); offsets.add(request.offset_id); return nextId;
 }
 public void cancelRequest(int id, boolean notify) { cancelled.add(id); }
 public void respond(TLRPC.messages_Messages page, boolean failed) { requests.remove().callback().run(page, failed ? new Object() : null); }
}
'@
    'org/telegram/messenger/MessageObject.java' = @'
package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessageObject {
 public final int currentAccount; public final TLRPC.Message messageOwner;
 public MessageObject(int account, TLRPC.Message message, boolean layout, boolean media) { currentAccount = account; messageOwner = message; }
 public int getId() { return messageOwner.id; }
 public long getDialogId() { return messageOwner.dialog_id; }
 public static void setUnreadFlags(TLRPC.Message message, int flags) { message.unread = flags == 0; }
}
'@
    'org/telegram/messenger/ChatObject.java' = @'
package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class ChatObject { public static boolean isMegagroup(TLRPC.Chat chat) { return chat != null && chat.megagroup; } }
'@
    'org/telegram/messenger/DialogObject.java' = @'
package org.telegram.messenger;
public class DialogObject { public static boolean isEncryptedDialog(long dialog) { return dialog == 999; } }
'@
    'org/telegram/messenger/MessagesController.java' = @'
package org.telegram.messenger;
import java.util.*;
import org.telegram.tgnet.TLRPC;
public class MessagesController {
 public static final int LOAD_BACKWARD = 0;
 public static final MessagesController instance = new MessagesController();
 public final TLRPC.Chat chat = new TLRPC.Chat();
 public static MessagesController getInstance(int account) { return instance; }
 public TLRPC.Chat getChat(long id) { return chat; }
 public void putUsers(ArrayList<TLRPC.User> users, boolean fromCache) {}
 public void putChats(ArrayList<TLRPC.Chat> chats, boolean fromCache) {}
 public TLRPC.InputPeer getInputPeer(long dialog) { var peer = new TLRPC.InputPeer(); peer.dialog = dialog; return peer; }
 public void removeDeletedMessagesFromArray(long dialog, ArrayList<TLRPC.Message> messages) {}
}
'@
    'org/telegram/SQLite/SQLiteException.java' = @'
package org.telegram.SQLite;
public class SQLiteException extends Exception {}
'@
    'org/telegram/SQLite/SQLiteCursor.java' = @'
package org.telegram.SQLite;
import java.util.*;
import org.telegram.tgnet.*;
public class SQLiteCursor {
 private final List<TLRPC.Message> rows; private int index = -1;
 public SQLiteCursor(List<TLRPC.Message> rows) { this.rows = rows; }
 public boolean next() throws SQLiteException { return ++index < rows.size(); }
 public int intValue(int col) throws SQLiteException {
  TLRPC.Message m = rows.get(index);
  return switch(col) { case 1 -> m.send_state; case 2 -> m.id; case 3 -> m.date; case 4 -> m.unread ? 0 : 1; default -> 0; };
 }
 public NativeByteBuffer byteBufferValue(int col) throws SQLiteException { return new NativeByteBuffer(rows.get(index)); }
 public boolean isNull(int col) throws SQLiteException { return true; }
 public void dispose() {}
}
'@
    'org/telegram/SQLite/SQLiteDatabase.java' = @'
package org.telegram.SQLite;
import java.util.*;
import org.telegram.messenger.DispatchQueue;
import org.telegram.tgnet.TLRPC;
public class SQLiteDatabase {
 public final List<TLRPC.Message> rows = new ArrayList<>(); public int scans; public boolean fail;
 public SQLiteCursor queryFinalized(String sql, Object... args) throws SQLiteException {
  if (!DispatchQueue.inStorage) throw new AssertionError("Database access outside storageQueue");
  if (fail) throw new SQLiteException();
  var selected = new ArrayList<TLRPC.Message>(); long dialog = ((Number) args[0]).longValue();
  boolean byId = sql.contains("AND mid = ?");
  if (!byId) { scans++; if (!sql.contains("LIMIT ?")) throw new AssertionError("Unbounded database query"); }
  for (TLRPC.Message row : rows) {
   if (row.dialog_id != dialog) continue;
   if (byId && row.id != ((Number) args[1]).intValue()) continue;
   if (!byId && args.length > 2) {
    int date = ((Number) args[1]).intValue(), id = ((Number) args[3]).intValue();
    if (!(row.date < date || row.date == date && row.id < id)) continue;
   }
   selected.add(row);
  }
  selected.sort(Comparator.<TLRPC.Message>comparingInt(m -> m.date).thenComparingInt(m -> m.id).reversed());
  int limit = byId ? 1 : ((Number) args[args.length - 1]).intValue();
  return new SQLiteCursor(selected.subList(0, Math.min(limit, selected.size())));
 }
}
'@
    'org/telegram/messenger/MessagesStorage.java' = @'
package org.telegram.messenger;
import java.util.*;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.TLRPC;
public class MessagesStorage {
 public static final MessagesStorage[] instances = {new MessagesStorage(), new MessagesStorage()};
 public final DispatchQueue queue = new DispatchQueue(); public final SQLiteDatabase database = new SQLiteDatabase();
 public boolean failWrite; public int writes;
 public static MessagesStorage getInstance(int account) { return instances[account]; }
 public DispatchQueue getStorageQueue() { return queue; }
 public SQLiteDatabase getDatabase() { return database; }
 public ArrayList<TLRPC.User> getUsers(ArrayList<Long> ids) { return new ArrayList<>(); }
 public ArrayList<TLRPC.Chat> getChats(ArrayList<Long> ids) { return new ArrayList<>(); }
 public static void addUsersAndChatsFromMessage(TLRPC.Message m, ArrayList<Long> users, ArrayList<Long> chats, ArrayList<Long> emoji) {}
 public int getDialogReadMaxSync(boolean out, long dialog) { if (!DispatchQueue.inStorage) throw new AssertionError(); return 0; }
 public int getDialogReadMax(boolean out, long dialog) { throw new AssertionError("Blocking read-max API is not allowed"); }
 public void putMessages(TLRPC.messages_Messages page, long dialog, int loadType, int offset, boolean create, int mode, long thread) {
  queue.postRunnable(() -> {
   writes++;
   if (failWrite) return;
   for (var message : page.messages) {
    database.rows.removeIf(old -> old.dialog_id == dialog && old.id == message.id); database.rows.add(message);
   }
  });
 }
}
'@
    'tw/nekomimi/nekogram/filters/AyuFilter.java' = @'
package tw.nekomimi.nekogram.filters;
import java.util.*;
import org.telegram.messenger.MessageObject;
public class AyuFilter {
 public static final Set<Integer> regex = new HashSet<>(), blocked = new HashSet<>();
 public static boolean hideRegex = true, hideBlocked = true;
 public static boolean shouldHideFilteredMessages() { return hideRegex; }
 public static boolean shouldHideIgnoredBlockedMessages() { return hideBlocked; }
 public static boolean isFiltered(MessageObject message, Object group) { return regex.contains(message.getId()); }
 public static boolean isIgnoredBlockedMessage(MessageObject message) { return blocked.contains(message.getId()); }
}
'@
    'org/telegram/ui/Cells/DialogCell.java' = @'
package org.telegram.ui.Cells;
import org.telegram.messenger.MessageObject;
public class DialogCell {
 public int account; public long dialog = -77; public boolean attached = true;
 public MessageObject source, applied; public int refreshes;
 public long getDialogId() { return dialog; }
 public int getCurrentAccount() { return account; }
 public boolean isAttachedToWindow() { return attached; }
 public void refreshFilteredPreview() {
  refreshes++;
  applied = NooagramDialogPreviewFilter.isHidden(account, dialog, source)
   ? NooagramDialogPreviewFilter.resolve(this, account, dialog, source) : source;
 }
}
'@
}

New-Item -ItemType Directory -Path $scratch | Out-Null
foreach ($entry in $sources.GetEnumerator()) {
    $target = Join-Path $scratch $entry.Key
    New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
    [System.IO.File]::WriteAllText($target, $entry.Value)
}
$javaFiles = @(Get-ChildItem -LiteralPath $scratch -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
$javaFiles += Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Cells/NooagramDialogPreviewFilter.java'
$javaFiles += Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Cells/NooagramPreviewSearch.java'
$javaFiles += Join-Path $PSScriptRoot 'preview/NooagramDialogPreviewFilterProbe.java'
$classes = Join-Path $scratch 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
& javac -d $classes @javaFiles
if ($LASTEXITCODE -ne 0) { throw 'Preview probe compilation failed' }
& java -cp $classes org.telegram.ui.Cells.NooagramDialogPreviewFilterProbe
if ($LASTEXITCODE -ne 0) { throw 'Preview probe failed' }
Write-Output "Probe fixtures and classes: $scratch"
