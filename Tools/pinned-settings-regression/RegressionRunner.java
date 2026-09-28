import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

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

// No Gradle or cached app classes: compile current production logic with offline doubles.
public class RegressionRunner {
    private static final Map<String, String> STUBS = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path root = repo.resolve("TMessagesProj/src/main/java");
        Path output = repo.resolve("build/pinned-settings-regression");
        Files.createDirectories(output);
        dependencyDoubles();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            ArrayList<JavaFileObject> sources = new ArrayList<>();
            manager.getJavaFileObjectsFromPaths(List.of(
                    root.resolve("tw/nekomimi/nekogram/helpers/NooagramPinnedHider.java"),
                    root.resolve("tw/nekomimi/nekogram/config/ConfigItem.java"),
                    repo.resolve("Tools/pinned-settings-regression/PinnedSettingsProbe.java")))
                    .forEach(sources::add);
            STUBS.forEach((name, text) -> sources.add(source(name, text)));
            sources.add(activityMethods(root.resolve("tw/nekomimi/nekogram/settings/NooagramPinnedHiderActivity.java")));
            sources.add(importMethods(root.resolve("tw/nekomimi/nekogram/settings/BaseNekoXSettingsActivity.java")));
            sources.add(cellGroupMethods(root.resolve("tw/nekomimi/nekogram/config/CellGroup.java")));
            sources.add(filterNotificationMethods(root.resolve("tw/nekomimi/nekogram/filters/AyuFilter.java")));
            if (!compiler.getTask(null, manager, null,
                    List.of("-proc:none", "-encoding", "UTF-8", "-d", output.toString()), null, sources).call()) {
                throw new AssertionError("Probe compilation failed");
            }
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            loader.loadClass("PinnedSettingsProbe").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[]{repo.toString()});
        }
    }

    private static JavaFileObject source(String name, String content) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
        };
    }

    private static Map<String, String> methodBodies(Path file) throws Exception {
        String original = Files.readString(file);
        Map<String, String> methods = new LinkedHashMap<>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(List.of(file)));
            var positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                for (var definition : unit.getTypeDecls()) {
                    if (!(definition instanceof ClassTree type)) continue;
                    for (var member : type.getMembers()) {
                        if (member instanceof MethodTree method && method.getBody() != null) {
                            int start = (int) positions.getStartPosition(unit, method.getBody());
                            int end = (int) positions.getEndPosition(unit, method.getBody());
                            methods.put(method.getName().toString(), original.substring(start, end));
                        }
                    }
                }
            }
        }
        return methods;
    }

    private static JavaFileObject activityMethods(Path file) throws Exception {
        var methods = methodBodies(file);
        String wrapper = """
                import java.util.*;
                import android.content.Context;
                import android.view.View;
                import org.telegram.messenger.*;
                import org.telegram.ui.ActionBar.AlertDialog;
                import org.telegram.ui.Components.BulletinFactory;
                import tw.nekomimi.nekogram.helpers.NooagramPinnedHider;
                class ProbeActivityBase {
                    protected int rowCount;
                    protected void updateRows() { rowCount = 0; }
                    public boolean onFragmentCreate() { return true; }
                    public void onFragmentDestroy() {}
                }
                public class HiderActivityProbe extends ProbeActivityBase implements NotificationCenter.NotificationCenterDelegate {
                    public int currentAccount;
                    private ArrayList<Long> hiddenDialogs = new ArrayList<>();
                    private int headerRow, startRow, endRow, emptyRow;
                    public final Adapter listAdapter = new Adapter();
                    public final ActionBar actionBar = new ActionBar();
                    public static class Adapter { public int refreshes; public void notifyDataSetChanged() { refreshes++; } }
                    public static class ActionBar {
                        private final Menu menu = new Menu();
                        public Menu createMenu() { return menu; }
                    }
                    public static class Menu {
                        private final View restore = new View();
                        public View getItem(int id) { if (id != 998) throw new AssertionError("Unexpected menu item"); return restore; }
                    }
                    public NotificationCenter getNotificationCenter() { return NotificationCenter.getInstance(currentAccount); }
                    public UserConfig getUserConfig() { return UserConfig.getInstance(currentAccount); }
                    public Context getParentActivity() { return ApplicationLoader.applicationContext; }
                    public Object getResourceProvider() { return null; }
                    public void showDialog(AlertDialog dialog) {}
                    public static String getString(int id) { return "text"; }
                    private String dialogTitle(long id) { return "ID " + id; }
                    public List<Long> rows() { return new ArrayList<>(hiddenDialogs); }
                """
                + "public void updateRows() " + methods.get("updateRows")
                + "public boolean onFragmentCreate() " + methods.get("onFragmentCreate")
                + "public void onFragmentDestroy() " + methods.get("onFragmentDestroy")
                + "public void didReceivedNotification(int id, int account, Object... args) " + methods.get("didReceivedNotification")
                + "public void onItemClick(View view, int position, float x, float y) " + methods.get("onItemClick")
                + "public void showRestoreLegacyAlert() " + methods.get("showRestoreLegacyAlert")
                + "}";
        return source("HiderActivityProbe", wrapper);
    }

    private static JavaFileObject importMethods(Path file) throws Exception {
        String wrapper = """
                import java.util.*;
                import android.content.Context;
                import org.telegram.messenger.R;
                import org.telegram.ui.ActionBar.AlertDialog;
                import tw.nekomimi.nekogram.config.*;
                public class SettingsImportProbe {
                    public final HashMap<String, Integer> rowMap = new HashMap<>();
                    public final HashMap<Integer, ConfigItem> rowConfigMapReverse = new HashMap<>();
                    public final CellGroup group = new CellGroup();
                    public final List<String> events = new ArrayList<>();
                    public Context getParentActivity() { return new Context(); }
                    public CellGroup getCellGroup() { return group; }
                    public void updateRows() { events.add("refresh"); }
                    public void scrollToRow(String key, Runnable unknown) { events.add("scroll:" + key); }
                    public static String getString(int id) { return "text"; }
                """ + "public void importToRow(String key, String value, Runnable unknown) "
                + methodBodies(file).get("importToRow") + "}";
        return source("SettingsImportProbe", wrapper);
    }

    private static JavaFileObject cellGroupMethods(Path file) throws Exception {
        return source("tw.nekomimi.nekogram.config.CellGroup", """
                package tw.nekomimi.nekogram.config;
                import org.telegram.messenger.FileLog;
                public class CellGroup {
                    public interface CallBackSettingsChanged { void run(String key, Object value); }
                    public CallBackSettingsChanged callBackSettingsChanged;
                """ + "public void runCallback(String key, Object newValue) " + methodBodies(file).get("runCallback") + "}");
    }

    private static JavaFileObject filterNotificationMethods(Path file) throws Exception {
        var methods = methodBodies(file);
        return source("FilterNotificationProbe", """
                import org.telegram.messenger.*;
                public class FilterNotificationProbe {
                    private static final Object cacheLock = new Object();
                    private static Object filterModels, chatFilterEntries, excludedSharedFilterIdsByDialog;
                    public static class AyuFilterCache {
                        public static int clears;
                        public static void clearAll() { clears++; }
                    }
                """ + "public static void rebuildCache() " + methods.get("rebuildCache")
                + "public static void invalidateFilteredCache() " + methods.get("invalidateFilteredCache")
                + "private static void notifyFiltersUpdated() " + methods.get("notifyFiltersUpdated") + "}");
    }

    private static void stub(String name, String body) {
        STUBS.put(name, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
    }

    private static void dependencyDoubles() {
        stub("android.content.SharedPreferences", """
                public class SharedPreferences {
                    public final java.util.Map<String, Object> values = new java.util.HashMap<>();
                    public int writes;
                    public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
                    public boolean contains(String key) { return values.containsKey(key); }
                    public Editor edit() { return new Editor(); }
                    public class Editor {
                        private final java.util.Map<String, Object> changes = new java.util.HashMap<>();
                        public Editor putString(String key, String value) { changes.put(key, value); return this; }
                        public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
                        public Editor putInt(String key, int value) { changes.put(key, value); return this; }
                        public Editor putLong(String key, long value) { changes.put(key, value); return this; }
                        public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
                        public Editor putStringSet(String key, java.util.Set<String> value) { changes.put(key, value); return this; }
                        public Editor remove(String key) { changes.put(key, null); return this; }
                        public void apply() { changes.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); }); writes++; }
                    }
                }
                """);
        stub("android.content.Context", """
                public class Context {
                    public static final int MODE_PRIVATE = 0;
                    public final SharedPreferences prefs = new SharedPreferences();
                    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
                }
                """);
        stub("android.view.View", """
                public class View {
                    public static final int GONE = 8;
                    public int visibility;
                    private Object tag;
                    public void setTag(Object tag) { this.tag = tag; }
                    public Object getTag() { return tag; }
                    public void setVisibility(int visibility) { this.visibility = visibility; }
                }
                """);
        stub("android.util.Base64", """
                public class Base64 {
                    public static final int DEFAULT = 0;
                    public static String encodeToString(byte[] bytes, int flags) { return java.util.Base64.getEncoder().encodeToString(bytes); }
                }
                """);
        stub("org.json.JSONException", "public class JSONException extends Exception { public JSONException() {} }");
        // Fixtures need only integer arrays; this double deliberately rejects other JSON.
        stub("org.json.JSONArray", """
                public class JSONArray {
                    private final java.util.List<Long> values = new java.util.ArrayList<>();
                    public JSONArray() {}
                    public JSONArray(String json) throws JSONException {
                        String text = json.trim();
                        if (!text.startsWith("[") || !text.endsWith("]")) throw new JSONException();
                        text = text.substring(1, text.length() - 1).trim();
                        if (!text.isEmpty()) {
                            try { for (String token : text.split(",")) values.add(Long.parseLong(token.trim())); }
                            catch (RuntimeException ex) { throw new JSONException(); }
                        }
                    }
                    public int length() { return values.size(); }
                    public long optLong(int index, long fallback) { return index < values.size() ? values.get(index) : fallback; }
                    public JSONArray put(long value) { values.add(value); return this; }
                    public String toString() { return values.toString(); }
                }
                """);
        stub("org.telegram.messenger.ApplicationLoader", "public class ApplicationLoader { public static android.content.Context applicationContext = new android.content.Context(); }");
        stub("org.telegram.messenger.AndroidUtilities", """
                public class AndroidUtilities {
                    public static final java.util.List<Runnable> queue = new java.util.ArrayList<>();
                    public static void runOnUIThread(Runnable task) { queue.add(task); }
                    public static void flush() { while (!queue.isEmpty()) queue.remove(0).run(); }
                }
                """);
        stub("org.telegram.messenger.UserConfig", """
                public class UserConfig {
                    public static final int MAX_ACCOUNT_COUNT = 4;
                    public static int selectedAccount;
                    private static final UserConfig[] instances = {new UserConfig(), new UserConfig(), new UserConfig(), new UserConfig()};
                    public long userId;
                    public static UserConfig getInstance(int account) { return instances[account]; }
                    public long getClientUserId() { return userId; }
                    public boolean isClientActivated() { return userId != 0; }
                }
                """);
        stub("org.telegram.messenger.NotificationCenter", """
                public class NotificationCenter {
                    public static final int nooagramPinnedHiderChanged = 1, regexFiltersUpdated = 2;
                    public interface NotificationCenterDelegate { void didReceivedNotification(int id, int account, Object... args); }
                    public static final java.util.Map<Integer, NotificationCenter> instances = new java.util.HashMap<>();
                    public final java.util.List<NotificationCenterDelegate> observers = new java.util.ArrayList<>();
                    public final java.util.List<Integer> events = new java.util.ArrayList<>();
                    private final int account;
                    private NotificationCenter(int account) { this.account = account; }
                    public static NotificationCenter getInstance(int account) { return instances.computeIfAbsent(account, NotificationCenter::new); }
                    public void addObserver(NotificationCenterDelegate observer, int event) { observers.add(observer); }
                    public void removeObserver(NotificationCenterDelegate observer, int event) { observers.remove(observer); }
                    public void postNotificationName(int event, Object... args) {
                        events.add(event);
                        for (var observer : java.util.List.copyOf(observers)) observer.didReceivedNotification(event, account, args);
                    }
                }
                """);
        stub("org.telegram.messenger.FileLog", "public class FileLog { public static void e(String text, Throwable error) {} public static void e(Throwable error) {} }");
        stub("org.telegram.messenger.R", """
                public class R {
                    public static class drawable { public static final int msg_pin = 1; }
                    public static class string { public static final int NooagramPinnedRestored = 1, ImportSettings = 2, ImportSettingsAlert = 3, Cancel = 4, Import = 5,
                        NooagramRestoreLegacyPinned = 6, NooagramRestoreLegacyPinnedInfo = 7, Restore = 8; }
                }
                """);
        stub("org.telegram.ui.Components.BulletinFactory", """
                public class BulletinFactory {
                    public static boolean canShowBulletin(Object owner) { return false; }
                    public static BulletinFactory of(Object owner) { return new BulletinFactory(); }
                    public BulletinFactory createSimpleBulletin(int icon, String text) { return this; }
                    public void show() {}
                }
                """);
        stub("org.telegram.ui.ActionBar.AlertDialog", """
                public class AlertDialog {
                    public interface Listener { void onClick(Object dialog, int which); }
                    public static Builder last;
                    public static class Builder {
                        public Listener positive, negative;
                        public String title, message;
                        public Builder(android.content.Context context) { last = this; }
                        public Builder(android.content.Context context, Object resourceProvider) { last = this; }
                        public Builder setTitle(String title) { this.title = title; return this; }
                        public Builder setMessage(CharSequence text) { message = text.toString(); return this; }
                        public Builder setPositiveButton(String title, Listener callback) { positive = callback; return this; }
                        public Builder setNegativeButton(String title, Listener callback) { negative = callback; return this; }
                        public AlertDialog create() { return new AlertDialog(); }
                        public void show() {}
                    }
                }
                """);
        stub("tw.nekomimi.nekogram.NekoConfig", """
                public class NekoConfig {
                    public static final Object sync = new Object();
                    public static android.content.SharedPreferences getPreferences() { return org.telegram.messenger.ApplicationLoader.applicationContext.prefs; }
                }
                """);
    }
}
