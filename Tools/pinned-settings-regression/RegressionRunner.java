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
                    root.resolve("com/radolyn/ayugram/config/ConfigItem.java"),
                    root.resolve("com/radolyn/ayugram/ui/cells/DeletedMessagesColorPickerCell.java"),
                    repo.resolve("Tools/pinned-settings-regression/PinnedSettingsProbe.java"),
                    repo.resolve("Tools/pinned-settings-regression/DeletedColorTouchProbe.java"),
                    repo.resolve("Tools/pinned-settings-regression/SettingsDeepLinkProbe.java"),
                    repo.resolve("Tools/pinned-settings-regression/LaunchSettingsRestoreProbe.java")))
                    .forEach(sources::add);
            STUBS.forEach((name, text) -> sources.add(source(name, text)));
            sources.add(activityMethods(root.resolve("com/radolyn/ayugram/settings/NooagramPinnedHiderActivity.java")));
            sources.add(importMethods(root.resolve("com/radolyn/ayugram/settings/BaseNekoXSettingsActivity.java")));
            sources.add(cellGroupMethods(root.resolve("com/radolyn/ayugram/config/CellGroup.java")));
            sources.add(filterNotificationMethods(root.resolve("tw/nekomimi/nekogram/filters/AyuFilter.java")));
            sources.add(experimentalSettingsMethods(root.resolve("tw/nekomimi/nekogram/settings/NekoExperimentalSettingsActivity.java")));
            sources.add(settingsDeepLinkMethods(root.resolve("tw/nekomimi/nekogram/helpers/SettingsHelper.java")));
            sources.add(launchSettingsRestoreMethods(root.resolve("org/telegram/ui/LaunchActivity.java")));
            sources.add(pinnedMenuMethods(root.resolve("org/telegram/ui/ChatActivity.java")));
            if (!compiler.getTask(null, manager, null,
                    List.of("-proc:none", "-encoding", "UTF-8", "-d", output.toString()), null, sources).call()) {
                throw new AssertionError("Probe compilation failed");
            }
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            loader.loadClass("PinnedSettingsProbe").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[]{repo.toString()});
            loader.loadClass("DeletedColorTouchProbe").getMethod("verify").invoke(null);
            loader.loadClass("SettingsDeepLinkProbe").getMethod("verify").invoke(null);
            loader.loadClass("LaunchSettingsRestoreProbe").getMethod("verify").invoke(null);
            loader.loadClass("PinnedMenuProbe").getMethod("verify").invoke(null);
        }
    }

    private static JavaFileObject source(String name, String content) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
        };
    }

    private static JavaFileObject pinnedMenuMethods(Path file) throws Exception {
        String body = methodBodies(file).get("updateNooagramPinnedHiderMenuItem");
        return source("PinnedMenuProbe", """
                public class PinnedMenuProbe {
                    static class View { static final int VISIBLE = 0, GONE = 8; }
                    static class R {
                        static class string { static final int NooagramRestoreThisGroupPinned = 1, NooagramHideThisGroupPinned = 2; }
                        static class drawable { static final int msg_pin = 3; }
                    }
                    static class Chat { boolean megagroup, channel; }
                    static class NooagramPinnedHider {
                        static boolean hidden;
                        static boolean isHidden(int account, long dialog) { return hidden; }
                    }
                    static class ActionBarMenuItem {
                        static class Item {
                            CharSequence text; int visibility;
                            void setText(CharSequence value) { text = value; }
                            void setVisibility(int value) { visibility = value; }
                        }
                        int registrations;
                        Item lazilyAddSubItem(int id, int icon, CharSequence text) {
                            registrations++; Item item = new Item(); item.text = text; return item;
                        }
                    }
                    ActionBarMenuItem headerItem = new ActionBarMenuItem();
                    ActionBarMenuItem.Item nooagramPinnedHiderItem;
                    Chat currentChat; Object currentEncryptedChat;
                    int chatMode, currentAccount; long dialog_id = -100; boolean thread;
                    static final int nkheaderbtn_nooagram_hide_pinned = 2045;
                    boolean isThreadChat() { return thread; }
                    String getString(int res) { return res == 1 ? "restore" : "hide"; }
                    void updateNooagramPinnedHiderMenuItem()
                """ + body + """
                    public static void verify() {
                        for (int type = 0; type < 3; type++) {
                            PinnedMenuProbe page = new PinnedMenuProbe();
                            page.currentChat = new Chat();
                            page.currentChat.channel = type != 0;
                            page.currentChat.megagroup = type == 1;
                            NooagramPinnedHider.hidden = false;
                            page.updateNooagramPinnedHiderMenuItem();
                            page.updateNooagramPinnedHiderMenuItem();
                            if (page.headerItem.registrations != 1 || !"hide".contentEquals(page.nooagramPinnedHiderItem.text))
                                throw new AssertionError("Missing or duplicated group/channel menu before popup inflation");
                            NooagramPinnedHider.hidden = true;
                            page.updateNooagramPinnedHiderMenuItem();
                            if (!"restore".contentEquals(page.nooagramPinnedHiderItem.text))
                                throw new AssertionError("Hidden state did not update lazy menu title");
                            NooagramPinnedHider.hidden = false;
                            page.updateNooagramPinnedHiderMenuItem();
                            if (!"hide".contentEquals(page.nooagramPinnedHiderItem.text) || page.headerItem.registrations != 1)
                                throw new AssertionError("Restore duplicated menu or left stale label");
                            page.thread = true;
                            page.updateNooagramPinnedHiderMenuItem();
                            if (page.nooagramPinnedHiderItem.visibility != View.GONE)
                                throw new AssertionError("Unsupported thread retained group-wide toggle");
                            page.thread = false;
                            page.updateNooagramPinnedHiderMenuItem();
                            if (page.nooagramPinnedHiderItem.visibility != View.VISIBLE)
                                throw new AssertionError("Returning to chat did not restore toggle");
                        }
                        for (int mode = 0; mode < 4; mode++) {
                            PinnedMenuProbe page = new PinnedMenuProbe();
                            page.currentChat = mode == 0 ? null : new Chat();
                            page.currentEncryptedChat = mode == 1 ? new Object() : null;
                            page.chatMode = mode == 2 ? 1 : 0;
                            page.thread = mode == 3;
                            page.updateNooagramPinnedHiderMenuItem();
                            if (page.headerItem.registrations != 0)
                                throw new AssertionError("Unsupported conversation registered toggle");
                        }
                        System.out.println("PASS pinned menu groups/channels, lazy registration, hide/restore title, deduplication and unsupported modes");
                    }
                }
                """);
    }

    private static JavaFileObject experimentalSettingsMethods(Path file) throws Exception {
        String body = methodBodies(file).get("setCanNotChange");
        var matcher = java.util.regex.Pattern.compile("private final AbstractConfigCell ([a-zA-Z0-9]+)").matcher(Files.readString(file));
        var rows = new java.util.LinkedHashSet<String>();
        while (matcher.find()) rows.add(matcher.group(1));
        String fields = rows.stream().map(name -> "final Object " + name + " = new Object();").collect(java.util.stream.Collectors.joining());
        return source("ExperimentalSettingsProbe", """
                import java.util.*;
                class ExperimentalBase { protected void setCanNotChange() {} }
                public class ExperimentalSettingsProbe extends ExperimentalBase {
                    static class Option {
                        boolean value = true;
                        boolean Bool() { return value; }
                        void setConfigBool(boolean value) { throw new AssertionError("Page visibility wrote an option"); }
                    }
                    static class NaConfig {
                        static final NaConfig INSTANCE = new NaConfig();
                        final Option hidden = new Option();
                        final Option channelIcon = new Option();
                        Option getShowHiddenFeature() { return hidden; }
                        Option getShowChannelIcon() { return channelIcon; }
                        Option getForceCopy() { return new Option(); }
                        Option getDisableFlagSecure() { return new Option(); }
                        Option getDisableSendReadStories() { return new Option(); }
                    }
                    static class NekoConfig {
                        static final Option localPremium = new Option(), hideSponsoredMessage = new Option(),
                            ignoreBlocked = new Option(), disableChatAction = new Option(), disableChoosingSticker = new Option();
                        static void setGhostMode(boolean value) { throw new AssertionError("Page visibility wrote ghost mode"); }
                    }
                    static class NekoXConfig { static boolean disableFlagSecure = true; }
                    static class Group { final List<Object> rows = new ArrayList<>(); }
                    final Group cellGroup = new Group();
                    void addRowsToMap() {}
                """ + fields + "public void setCanNotChange() " + body + """
                    public static void verify() {
                        for (boolean hidden : new boolean[]{false, true}) {
                          for (boolean channelIcon : new boolean[]{false, true}) {
                            NaConfig.INSTANCE.hidden.value = hidden;
                            NaConfig.INSTANCE.channelIcon.value = channelIcon;
                            ExperimentalSettingsProbe page = new ExperimentalSettingsProbe();
                            for (var field : ExperimentalSettingsProbe.class.getDeclaredFields()) {
                                if (field.getType() == Object.class) {
                                    try { page.cellGroup.rows.add(field.get(page)); }
                                    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                                }
                            }
                            page.setCanNotChange();
                            page.setCanNotChange();
                            if (Collections.frequency(page.cellGroup.rows, page.customCustomChannelLabelRow) != (channelIcon ? 0 : 1)) {
                                throw new AssertionError("Channel label visibility or duplicate row after rebuild");
                            }
                            if (!NekoXConfig.disableFlagSecure) throw new AssertionError("Page reset secure-window choice");
                            for (var field : ExperimentalSettingsProbe.class.getDeclaredFields()) {
                                if (field.getType() == Object.class && !field.getName().equals("customCustomChannelLabelRow")) {
                                    try {
                                        if (!page.cellGroup.rows.contains(field.get(page))) throw new AssertionError("Experimental option hidden: " + field.getName());
                                    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                                }
                            }
                          }
                        }
                    }
                }
                """);
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
                import com.radolyn.ayugram.config.*;
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
        return source("com.radolyn.ayugram.config.CellGroup", """
                package com.radolyn.ayugram.config;
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

    private static JavaFileObject settingsDeepLinkMethods(Path file) throws Exception {
        return source("SettingsHelperProbe", """
                import java.util.*;
                import android.app.Activity;
                import android.text.TextUtils;
                import org.telegram.messenger.AndroidUtilities;
                import org.telegram.messenger.LocaleController;
                import org.telegram.messenger.R;
                import org.telegram.ui.ActionBar.BaseFragment;
                import com.radolyn.ayugram.settings.NekoAyuMomentsSettingsActivity;
                import tw.nekomimi.nekogram.ChatHistoryActivity;
                import tw.nekomimi.nekogram.helpers.PasscodeHelper;
                import tw.nekomimi.nekogram.settings.*;
                import xyz.nextalone.nagram.network.NetworkLogActivity;
                import static org.telegram.ui.ProfileActivity.sendLogs;
                public class SettingsHelperProbe {
                    public interface Callback { void presentFragment(BaseFragment fragment); }
                    public static class TestCallback implements Callback {
                        public BaseFragment fragment;
                        public void presentFragment(BaseFragment fragment) { this.fragment = fragment; }
                    }
                """ + "public static void processDeepLink(Activity activity, android.net.Uri uri, Callback callback, Runnable unknown) "
                + methodBodies(file).get("processDeepLink") + "}");
    }

    private static JavaFileObject launchSettingsRestoreMethods(Path file) throws Exception {
        var methods = methodBodies(file);
        return source("LaunchSettingsRestoreProbeFactory", """
                import org.telegram.messenger.FileLog;
                import org.telegram.ui.ActionBar.BaseFragment;
                public class LaunchSettingsRestoreProbeFactory {
                """ + "private static boolean isCustomSettingsClassName(String className) "
                + methods.get("isCustomSettingsClassName")
                + "public static BaseFragment createRestoredCustomSettingsFragment(String className) "
                + methods.get("createRestoredCustomSettingsFragment") + "}");
    }

    private static void stub(String name, String body) {
        STUBS.put(name, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + body);
    }

    private static void dependencyDoubles() {
        stub("com.radolyn.ayugram.AyuConfig", """
                public class AyuConfig {
                    public static final AyuConfig INSTANCE = new AyuConfig();
                    public Option getRegexFiltersEnabled() { return new Option(); }
                    public static class Option {
                        public void setConfigBool(boolean value) {
                            throw new AssertionError("Page visibility wrote message-filter preference");
                        }
                    }
                }
                """);
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
                    private ViewParent parent;
                    private int measuredWidth, measuredHeight;
                    protected android.content.Context context;
                    public View() {}
                    public View(android.content.Context context) { this.context = context; }
                    public void setTag(Object tag) { this.tag = tag; }
                    public Object getTag() { return tag; }
                    public void setVisibility(int visibility) { this.visibility = visibility; }
                    public void attachTestParent(ViewParent parent) { this.parent = parent; }
                    public ViewParent getParent() { return parent; }
                    public void invalidate() {}
                    public final void measure(int widthSpec, int heightSpec) {
                        onMeasure(widthSpec, heightSpec);
                    }
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
                    }
                    protected final void setMeasuredDimension(int width, int height) {
                        measuredWidth = width;
                        measuredHeight = height;
                    }
                    public int getMeasuredWidth() { return measuredWidth; }
                    public int getMeasuredHeight() { return measuredHeight; }
                    protected void onDraw(android.graphics.Canvas canvas) {}
                    public boolean onTouchEvent(android.view.MotionEvent event) { return false; }
                    public static class MeasureSpec {
                        public static final int UNSPECIFIED = 0, EXACTLY = 1;
                        public static int makeMeasureSpec(int size, int mode) { return size; }
                        public static int getSize(int spec) { return spec & 0x00ffffff; }
                    }
                }
                """);
        stub("android.view.ViewParent", "public class ViewParent { public void requestDisallowInterceptTouchEvent(boolean disallow) {} }");
        stub("android.view.ViewConfiguration", """
                public class ViewConfiguration {
                    public static ViewConfiguration get(android.content.Context context) { return new ViewConfiguration(); }
                    public int getScaledTouchSlop() { return 8; }
                }
                """);
        stub("android.view.MotionEvent", """
                public class MotionEvent {
                    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3;
                    private final float x, y;
                    private final int action;
                    private MotionEvent(float x, float y, int action) { this.x = x; this.y = y; this.action = action; }
                    public static MotionEvent obtain(float x, float y, int action) { return new MotionEvent(x, y, action); }
                    public float getX() { return x; }
                    public float getY() { return y; }
                    public int getActionMasked() { return action; }
                }
                """);
        stub("android.graphics.RectF", """
                public class RectF {
                    private float left, top, right, bottom;
                    public RectF() {}
                    public RectF(float left, float top, float right, float bottom) { set(left, top, right, bottom); }
                    public void set(float left, float top, float right, float bottom) {
                        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
                    }
                    public void set(RectF other) { set(other.left, other.top, other.right, other.bottom); }
                    public void inset(float dx, float dy) { left -= dx; top -= dy; right += dx; bottom += dy; }
                    public boolean contains(float x, float y) { return x >= left && x <= right && y >= top && y <= bottom; }
                    public float centerX() { return (left + right) / 2f; }
                    public float centerY() { return (top + bottom) / 2f; }
                    public float width() { return right - left; }
                    public float height() { return bottom - top; }
                }
                """);
        stub("android.graphics.Paint", """
                public class Paint {
                    public static final int ANTI_ALIAS_FLAG = 1;
                    public enum Style { FILL, STROKE }
                    public Paint() {}
                    public Paint(int flags) {}
                    public void setStyle(Style style) {}
                    public void setStrokeWidth(float width) {}
                    public void setColor(int color) {}
                    public float getStrokeWidth() { return 0; }
                }
                """);
        stub("android.graphics.Canvas", """
                public class Canvas {
                    public int save() { return 0; }
                    public void restore() {}
                    public void scale(float sx, float sy, float px, float py) {}
                    public void drawCircle(float x, float y, float radius, android.graphics.Paint paint) {}
                }
                """);
        stub("android.annotation.SuppressLint", "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface SuppressLint { String[] value(); }");
        stub("androidx.annotation.NonNull", "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface NonNull {}");
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
                    public static final android.graphics.RectF rectTmp = new android.graphics.RectF();
                    public static void runOnUIThread(Runnable task) { queue.add(task); }
                    public static void flush() { while (!queue.isEmpty()) queue.remove(0).run(); }
                    public static float dp(float value) { return value; }
                    public static float dpf2(float value) { return value; }
                    public static float lerp(float start, float end, float fraction) { return start + (end - start) * fraction; }
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
        stub("org.telegram.ui.ActionBar.BaseFragment", "public class BaseFragment {}");
        stub("org.telegram.ui.ActionBar.Theme", """
                public class Theme {
                    public interface ResourcesProvider {}
                    public static final String key_chat_inTimeText = "chat_inTimeText";
                    public static final String key_windowBackgroundWhite = "windowBackgroundWhite";
                    public static int getColor(String key, ResourcesProvider provider) { return 0xff000000; }
                }
                """);
        stub("org.telegram.ui.Components.AnimatedFloat", """
                public class AnimatedFloat {
                    private float value;
                    public AnimatedFloat(android.view.View view, float value, int duration, Object interpolator) { this.value = value; }
                    public AnimatedFloat(android.view.View view, int duration, Object interpolator) {}
                    public void set(boolean value, boolean immediate) { this.value = value ? 1 : 0; }
                    public float set(boolean value) { this.value = value ? 1 : 0; return this.value; }
                }
                """);
        stub("org.telegram.ui.Components.ButtonBounce", """
                public class ButtonBounce {
                    public ButtonBounce(android.view.View view) {}
                    public float getScale(float minScale) { return 1; }
                    public void setPressed(boolean pressed) {}
                }
                """);
        stub("org.telegram.ui.Components.CubicBezierInterpolator", """
                public class CubicBezierInterpolator {
                    public static final CubicBezierInterpolator EASE_OUT_QUINT = new CubicBezierInterpolator();
                }
                """);
        stub("tw.nekomimi.nekogram.helpers.TimeStringHelper", """
                public class TimeStringHelper {
                    public static final int[] DELETED_COLORS = {1, 2, 3, 4, 5, 6, 7};
                }
                """);
        stub("android.app.Activity", "public class Activity {}");
        stub("android.net.Uri", """
                public class Uri {
                    public java.util.List<String> getPathSegments() { return java.util.List.of(); }
                    public String getQueryParameter(String key) { return null; }
                }
                """);
        stub("android.text.TextUtils", "public class TextUtils { public static boolean isEmpty(CharSequence text) { return text == null || text.length() == 0; } }");
        stub("org.telegram.messenger.LocaleController", "public class LocaleController { public static String getString(int id) { return \"text\"; } }");
        stub("org.telegram.ui.ProfileActivity", "public class ProfileActivity { public static void sendLogs(android.app.Activity activity, boolean reset) {} }");
        stub("com.radolyn.ayugram.settings.BaseNekoXSettingsActivity", """
                public class BaseNekoXSettingsActivity extends org.telegram.ui.ActionBar.BaseFragment {
                    public static String lastImport, lastScroll;
                    public void importToRow(String key, String value, Runnable unknown) { lastImport = key + "=" + value; }
                    public void scrollToRow(String key, Runnable unknown) { lastScroll = key; }
                }
                """);
        stub("com.radolyn.ayugram.settings.NekoAyuMomentsSettingsActivity", "public class NekoAyuMomentsSettingsActivity extends BaseNekoXSettingsActivity {}");
        stub("tw.nekomimi.nekogram.ChatHistoryActivity", "public class ChatHistoryActivity extends org.telegram.ui.ActionBar.BaseFragment {}");
        stub("xyz.nextalone.nagram.network.NetworkLogActivity", "public class NetworkLogActivity extends org.telegram.ui.ActionBar.BaseFragment {}");
        stub("tw.nekomimi.nekogram.helpers.PasscodeHelper", "public class PasscodeHelper { public static String getSettingsKey() { return \"passcode\"; } }");
        stub("tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity", """
                public class BaseNekoSettingsActivity extends org.telegram.ui.ActionBar.BaseFragment {
                    public static String lastImport, lastScroll;
                    public void importToRow(String key, String value, Runnable unknown) { lastImport = key + "=" + value; }
                    public void scrollToRow(String key, Runnable unknown) { lastScroll = key; }
                }
                """);
        stub("tw.nekomimi.nekogram.settings.BaseNekoXSettingsActivity", """
                public class BaseNekoXSettingsActivity extends org.telegram.ui.ActionBar.BaseFragment {
                    public static String lastImport, lastScroll;
                    public void importToRow(String key, String value, Runnable unknown) { lastImport = key + "=" + value; }
                    public void scrollToRow(String key, Runnable unknown) { lastScroll = key; }
                }
                """);
        for (String name : List.of("NekoPasscodeSettingsActivity", "NekoEmojiSettingsActivity", "NekoSettingsActivity")) {
            stub("tw.nekomimi.nekogram.settings." + name, "public class " + name + " extends BaseNekoSettingsActivity {}");
        }
        for (String name : List.of("NekoAccountSettingsActivity", "NekoChatSettingsActivity",
                "NekoDebugSettingsActivity", "NekoExperimentalSettingsActivity", "NekoGeneralSettingsActivity")) {
            stub("tw.nekomimi.nekogram.settings." + name, "public class " + name + " extends BaseNekoXSettingsActivity {}");
        }
        stub("com.radolyn.ayugram.AyuOptions", """
                public class AyuOptions {
                    public static final Object sync = new Object();
                    public static android.content.SharedPreferences getPreferences() { return org.telegram.messenger.ApplicationLoader.applicationContext.prefs; }
                }
                """);
    }
}
