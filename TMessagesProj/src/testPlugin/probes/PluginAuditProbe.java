import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline control-flow probes using production method bodies and local Android/Python stand-ins. */
public final class PluginAuditProbe {
    private static final class Source {
        final Map<String, List<String>> members = new HashMap<>();

        Source(Path path) throws Exception {
            String text = Files.readString(path);
            var compiler = ToolProvider.getSystemJavaCompiler();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
                var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                        List.of("-proc:none"), null, manager.getJavaFileObjects(path));
                var positions = Trees.instance(task).getSourcePositions();
                for (CompilationUnitTree unit : task.parse()) {
                    for (Tree type : unit.getTypeDecls()) {
                        if (type instanceof ClassTree klass) {
                            collect(klass, "", unit, positions, text);
                        }
                    }
                }
                for (var diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        throw new AssertionError(diagnostic.toString());
                    }
                }
            }
        }

        private void collect(ClassTree klass, String parent, CompilationUnitTree unit,
                             com.sun.source.util.SourcePositions positions, String text) {
            String owner = parent + klass.getSimpleName();
            for (Tree member : klass.getMembers()) {
                if (member instanceof ClassTree nested) {
                    collect(nested, owner + ".", unit, positions, text);
                    continue;
                }
                String name = member instanceof MethodTree method ? method.getName().toString()
                        : member instanceof VariableTree field ? field.getName().toString() : null;
                if (name != null) {
                    int start = (int) positions.getStartPosition(unit, member);
                    int end = (int) positions.getEndPosition(unit, member);
                    members.computeIfAbsent(owner + "." + name, ignored -> new ArrayList<>())
                            .add(text.substring(start, end).replace("@Override", ""));
                }
            }
        }

        String take(String owner, String... names) {
            StringBuilder result = new StringBuilder();
            for (String name : names) {
                List<String> matches = members.get(owner + "." + name);
                if (matches == null) {
                    throw new AssertionError("Missing production member: " + owner + "." + name);
                }
                for (String match : matches) {
                    result.append(match).append('\n');
                }
            }
            return result.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Path plugin = repo.resolve("TMessagesProj/src/plugin/java");
        Source engine = new Source(plugin.resolve("com/exteragram/messenger/plugins/PythonPluginsEngine.java"));
        Source platform = new Source(plugin.resolve("com/chaquo/python/android/AndroidPlatform.java"));
        Source dialog = new Source(plugin.resolve("com/exteragram/messenger/updater/PythonSdkUpdateDialog.java"));
        String generated = """
                import java.io.*;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.*;
                import java.util.*;
                import java.util.concurrent.ConcurrentHashMap;
                class PythonPluginsEngine {
                """ + engine.take("PythonPluginsEngine", "INSTANCE", "SDK_DIR", "SDK_COMPAT_SHIMS_DIR",
                "SDK_VERSION", "SDK_BETA", "SDK_SHIMS_DIR", "MAX_SDK_VERSION_BYTES", "INSTALL_CANCELLED",
                "sdkInitialized", "sdkImportStarted", "sdkUpdateStagedThisProcess", "python", "basePluginClass",
                "debuggerListener", "pluginInstances", "settingsCache", "pluginDependencyPaths",
                "setPluginEnabled", "setPluginEnabledInternal", "loadPlugin", "loadPluginFromFile",
                "restorePluginEnabledPreference", "unloadPlugin", "initSdk", "shutdown", "initOnPluginsQueue",
                "copyFile", "copyStream", "readStreamFully", "stackTraceToString")
                + ENGINE_FIXTURE
                + "static class Updater {\n"
                + engine.take("PythonPluginsEngine.Updater", "isRestartRequired", "getPythonSdkUpdateFile",
                "getPythonCurrentSdkFile", "requestSdkFromApkFile", "sdkFromApk", "restoreSdkFromApk",
                "copyArchiveToPluginsDirectory", "restartApp")
                + UPDATER_FIXTURE
                + "static class PythonSdkUpdateInfo { String appVersion, appVersionOperator, appVersionCode,"
                + " appVersionCodeOperator, version, channel, abi; Object document = new Object();\n"
                + engine.take("PythonPluginsEngine.Updater.PythonSdkUpdateInfo", "canInstall")
                + "}\n}\n" + ENGINE_TESTS + "}\nclass AndroidPlatform {\n"
                + platform.take("AndroidPlatform", "ABI", "<init>") + PLATFORM_FIXTURE + "}\n";
        Path generatedFile = output.resolve("PythonPluginsEngine.java");
        Files.writeString(generatedFile, generated);
        int compilation = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", output.toString(), generatedFile.toString());
        if (compilation != 0) {
            throw new AssertionError("Production method compilation failed");
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
            var test = loader.loadClass("PythonPluginsEngine").getDeclaredMethod("runTests", Path.class);
            test.setAccessible(true);
            test.invoke(null, output);
        }
        String button = dialog.take("PythonSdkUpdateDialog", "getDoneButtonText");
        if (!button.contains("R.string.RestartApp")) {
            throw new AssertionError("SDK update button must disclose app restart");
        }
        String task = Files.readString(repo.resolve("buildSrc/src/main/kotlin/org/telegram/chaquopy/ChaquopyTasks.kt"));
        if (!task.contains("\"-Wl,-z,max-page-size=16384\"")
                || !task.contains("\"-Wl,-z,common-page-size=16384\"")) {
            throw new AssertionError("Missing explicit 16 KB linker options");
        }
        System.out.println("PASS: dialog restart disclosure and explicit 16 KB linker options");
    }

    private static final String ENGINE_FIXTURE = """
        static final PluginsController controller = new PluginsController();
        PluginsController getPluginsController() { return controller; }
        Python getPython() { if (python == null) python = new Python(); return initSdk() ? python : null; }
        PyObject requireBasePluginClass() { return basePluginClass = new PyObject("base"); }
        boolean preparePythonCompatShims() { return true; }
        boolean isSdkDirValid(File dir) { return new File(dir, "valid").exists(); }
        void installSdkArchive(File archive, boolean fromApk) throws IOException {
            SDK_DIR.mkdirs();
            Files.writeString(new File(SDK_DIR, "valid").toPath(), Files.readString(archive.toPath()));
            installations++;
        }
        static int installations;
        static void deleteFileIfExists(File file) { file.delete(); }
        void deleteRecursive(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
            file.delete();
        }
        void stopDevServer() { }
        void checkDevServer() { }
        void removePluginPathsFromSysPath() { }
        void deleteStaleBytecode(String id) { }
        void refreshImportCaches(String id, File dir) { }
        void pruneDependencyPaths() { }
        void loadPlugins(Runnable callback) { if (callback != null) callback.run(); }
        void installPluginDependencies(String id, Plugin plugin, PipController.InstallerDelegate delegate) throws Exception {
            if (delegate != null && delegate.isCancelled()) throw new PipController.InstallationCancelledException();
            if ("dependency-error".equals(plugin.version)) throw new Exception("dependency failure");
        }
        void createPluginInstance(String id, Plugin plugin, PipController.InstallerDelegate delegate) throws Exception {
            installPluginDependencies(id, plugin, delegate);
            if ("import-error".equals(plugin.version)) throw new Exception("import failure");
            pluginInstances.put(id, new PyObject(plugin.version));
        }
        PluginsController.PluginValidationResult validatePluginFromFile(String path) {
            try { return new PluginsController.PluginValidationResult(new Plugin("demo", Files.readString(Path.of(path))), null); }
            catch (IOException e) { return new PluginsController.PluginValidationResult(null, e.toString()); }
        }
        static class Plugin {
            final String id; String version; boolean enabled; Throwable error;
            Plugin(String id, String version) { this.id = id; this.version = version; }
            String getId() { return id; }
            void setEnabled(boolean value) { enabled = value; }
            boolean hasError() { return error != null; }
            void setError(Throwable value) { error = value; }
            void setAuthor(String value) { }
            void setVersion(String value) { version = value; }
            void setEngine(String value) { }
        }
        static class PyObject {
            final String version;
            final Map<String, Object> values = new HashMap<>();
            PyObject(String version) { this.version = version; }
            void put(String key, Object value) { values.put(key, value); }
            PyObject get(String name) { return new PyObject(name); }
            PyObject callAttr(String name, Object... args) {
                if ("on_plugin_load".equals(name) && ("load-error".equals(version) || failOld && "old".equals(version))) {
                    throw new PyException("activation failure: " + version);
                }
                if ("__start__".equals(name) && failSdk) throw new PyException("SDK import failed");
                return new PyObject(name);
            }
            boolean toBoolean() { return true; }
            @SuppressWarnings("unchecked")
            <T> T toJava(Class<T> type) { return (T) (type == String.class ? "1.0" : type == Boolean.TYPE ? Boolean.FALSE : null); }
            void close() { }
        }
        static class PyException extends RuntimeException { PyException(String message) { super(message); } }
        static class PyObjectUtils {
            static boolean getBoolean(PyObject object, String key, boolean fallback) {
                return object == null ? fallback : (boolean) object.values.getOrDefault(key, fallback);
            }
        }
        static class Python {
            PyObject getModule(String name) { imports.add(name); return new PyObject(name); }
        }
        static class SharedPreferences {
            final Map<String, Boolean> values = new HashMap<>();
            boolean contains(String key) { return values.containsKey(key); }
            boolean getBoolean(String key, boolean fallback) { return values.getOrDefault(key, fallback); }
            Map<String, Boolean> getAll() { return values; }
            Editor edit() { return new Editor(); }
            class Editor {
                Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
                Editor remove(String key) { values.remove(key); return this; }
                void apply() { }
            }
        }
        static class PluginsController {
            static final String PREF_PLUGIN_ENABLED_KEY_PREFIX = "enabled_";
            final Map<String, Plugin> plugins = new HashMap<>();
            final SharedPreferences preferences = new SharedPreferences();
            final Watchdog watchdog = new Watchdog();
            File pluginsDir;
            int notifications;
            static void runOnPluginsQueue(Runnable runnable) { runnable.run(); }
            void notifyPluginsChanged() { notifications++; }
            void cleanupPlugin(String id) { }
            void loadPluginSettings(String id) { }
            void clearPluginSettingsPreferences(String id) { preferences.edit().remove("enabled_" + id).apply(); }
            static class PluginValidationResult {
                final Plugin plugin; final String error;
                PluginValidationResult(Plugin plugin, String error) { this.plugin = plugin; this.error = error; }
            }
        }
        static class Watchdog {
            int running;
            void onPluginExecutionStarted(String id) { running++; }
            void onPluginExecutionFinished(String id) { running--; }
        }
        static class PipController {
            static final PipController INSTANCE = new PipController();
            int uninstalls;
            void uninstallDependencies(String id) { uninstalls++; }
            interface InstallerDelegate { boolean isCancelled(); }
            static class InstallationCancelledException extends Exception { }
            static class UnsupportedDependencyException extends Exception { }
        }
        static class ExteraConfig { static boolean pluginsSafeMode; }
        static class AndroidUtilities {
            static void runOnUIThread(Runnable action) { action.run(); }
            static void runOnUIThread(Runnable action, long delay) { }
        }
        static class Utilities { interface Callback<T> { void run(T value); } }
        static class PluginsConstants {
            static final String PYTHON = "python", ON_PLUGIN_LOAD = "on_plugin_load", ON_PLUGIN_UNLOAD = "on_plugin_unload";
        }
        static class TextUtils { static boolean isEmpty(String text) { return text == null || text.isEmpty(); } }
        static class FileLog {
            static void e(Object... args) { }
            static void w(Object... args) { }
            static void d(Object... args) { }
        }
        static class R { static class string { static final int PluginNoAuthor = 1; } }
        static class LocaleController { static String getString(int resource) { return "author"; } }
        static class AppUtils { static boolean compareVersions(String op, String a, String b) { return false; } }
        static class ApplicationLoader {
            static File files;
            static final Context applicationContext = new Context();
            static File getFilesDirFixed() { return files; }
        }
        static class Context { Assets getAssets() { return new Assets(); } }
        static class Assets {
            InputStream open(String path) throws IOException {
                openedAssets.add(path);
                return new ByteArrayInputStream("apk-sdk".getBytes(StandardCharsets.UTF_8));
            }
        }
        static class TLRPC { static class Document { } }
        static class UserConfig { static int selectedAccount; }
        static class FileLoader {
            static File downloaded;
            static FileLoader getInstance(int account) { return new FileLoader(); }
            File getPathToAttach(TLRPC.Document doc) { return downloaded; }
        }
        static class LaunchActivity { }
        static class Intent { Intent(Context context, Class<?> activity) { } }
        static class AppRestartHelper { static int restarts; static void triggerRebirth(Context context, Intent intent) { restarts++; } }
        static final List<String> imports = new ArrayList<>(), openedAssets = new ArrayList<>();
        static boolean failOld, failSdk;
        """;

    private static final String UPDATER_FIXTURE = """
        static final int STATUS_READY = 4, STATUS_LATEST = 2;
        static int status;
        static boolean isLoading;
        static void updateStatus(int value) { status = value; }
        static void deleteSdkUpdateFile() { getPythonSdkUpdateFile().delete(); }
        static void checkUpdates() { }
        static void touchFile(File file) {
            try { file.getParentFile().mkdirs(); file.createNewFile(); }
            catch (IOException e) { throw new RuntimeException(e); }
        }
        static boolean isAppVersionCompatible(String op, String version) { return true; }
        static boolean isAppVersionCodeCompatible(String op, String version) { return true; }
        static boolean isSdkVersionNewer(String version, boolean beta) { return true; }
        """;

    private static final String ENGINE_TESTS = """
        static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
        static void reset(Path output, String name, Boolean enabled) throws Exception {
            ApplicationLoader.files = Files.createTempDirectory(output, name).toFile();
            controller.pluginsDir = new File(ApplicationLoader.files, "plugins"); controller.pluginsDir.mkdirs();
            controller.plugins.clear(); controller.preferences.values.clear();
            controller.notifications = 0; controller.watchdog.running = 0;
            INSTANCE.pluginInstances.clear();
            INSTANCE.settingsCache.clear(); INSTANCE.pluginDependencyPaths.clear();
            INSTANCE.python = new Python(); INSTANCE.basePluginClass = null;
            sdkInitialized = false; sdkImportStarted = false; sdkUpdateStagedThisProcess = false;
            SDK_DIR = null; SDK_COMPAT_SHIMS_DIR = null;
            installations = 0; imports.clear(); openedAssets.clear();
            failOld = false; failSdk = false; ExteraConfig.pluginsSafeMode = false;
            AppRestartHelper.restarts = 0; Updater.status = 0; PipController.INSTANCE.uninstalls = 0;
            AndroidPlatform.ABI = "armeabi-v7a";
            if (enabled != null) controller.preferences.edit().putBoolean("enabled_demo", enabled).apply();
        }
        static void runTests(Path output) throws Exception {
            for (String failure : List.of("load-error", "import-error", "dependency-error", "cancel")) {
                reset(output, "rollback-", true);
                File target = new File(controller.pluginsDir, "demo.py"); Files.writeString(target.toPath(), "old");
                INSTANCE.loadPlugin("demo", target.getAbsolutePath());
                File source = new File(ApplicationLoader.files, "update.py"); Files.writeString(source.toPath(), failure);
                List<String> callbacks = new ArrayList<>();
                INSTANCE.loadPluginFromFile(source.toString(), new Plugin("demo", failure),
                        () -> failure.equals("cancel"), callbacks::add);
                check(callbacks.size() == 1 && callbacks.get(0) != null, "failure must callback once with error: " + failure);
                check(Files.readString(target.toPath()).equals("old"), "working source restored: " + failure);
                check(controller.plugins.get("demo").enabled && !controller.plugins.get("demo").hasError(), "old plugin activated: " + failure);
                check(INSTANCE.pluginInstances.get("demo").version.equals("old"), "old instance restored");
                check(controller.preferences.getBoolean("enabled_demo", false), "enabled pref restored");
                check(PipController.INSTANCE.uninstalls == 0, "rollback keeps cached dependencies");
                check(controller.watchdog.running == 0, "watchdog balanced");
            }
            System.out.println("PASS: activation/import/dependency/cancellation failures restore working version and enabled preference");
            for (Boolean enabled : new Boolean[]{false, null, true}) {
                reset(output, "preference-", enabled);
                File target = new File(controller.pluginsDir, "demo.py"); Files.writeString(target.toPath(), "old");
                failOld = Boolean.TRUE.equals(enabled);
                List<String> callbacks = new ArrayList<>();
                INSTANCE.loadPluginFromFile(new File(ApplicationLoader.files, "missing.py").toString(), new Plugin("demo", "new"), callbacks::add);
                check(callbacks.size() == 1 && callbacks.get(0) != null, "copy failure reported once");
                check(controller.preferences.contains("enabled_demo") == (enabled != null), "preference presence preserved");
                check(controller.preferences.getBoolean("enabled_demo", false) == Boolean.TRUE.equals(enabled), "preference value preserved even when recovery fails");
                check(Files.readString(target.toPath()).equals("old"), "copy failure restores old source");
            }
            System.out.println("PASS: absent/disabled/enabled preference rollback, including failed recovery activation");
            reset(output, "success-", true);
            File target = new File(controller.pluginsDir, "demo.py"); Files.writeString(target.toPath(), "old");
            File source = new File(ApplicationLoader.files, "new.py"); Files.writeString(source.toPath(), "new");
            List<String> callbacks = new ArrayList<>();
            INSTANCE.loadPluginFromFile(source.toString(), new Plugin("demo", "new"), callbacks::add);
            check(callbacks.size() == 1 && callbacks.get(0) == null, "success callback once");
            check(INSTANCE.pluginInstances.get("demo").version.equals("new"), "successful version active");
            check(!new File(controller.pluginsDir, "demo.py.bak").exists(), "backup removed after success");
            reset(output, "safe-", null);
            controller.plugins.put("demo", new Plugin("demo", "old")); ExteraConfig.pluginsSafeMode = true;
            callbacks.clear(); INSTANCE.setPluginEnabled("demo", true, callbacks::add);
            check(INSTANCE.pluginInstances.isEmpty(), "safe mode creates no instance");
            INSTANCE.setPluginEnabled("demo", false, callbacks::add);
            check(callbacks.size() == 2 && callbacks.stream().allMatch(Objects::isNull), "toggle callbacks succeed");
            check(!controller.preferences.getBoolean("enabled_demo", true), "disable persisted without instance");
            check(!controller.plugins.get("demo").enabled && controller.notifications == 2, "disabled model and notification");
            ExteraConfig.pluginsSafeMode = false;
            target = new File(controller.pluginsDir, "demo.py"); Files.writeString(target.toPath(), "old");
            INSTANCE.loadPlugin("demo", target.toString());
            check(INSTANCE.pluginInstances.isEmpty(), "safe-mode disabled plugin stays disabled afterward");
            controller.plugins.put("demo", new Plugin("demo", "load-error")); callbacks.clear();
            INSTANCE.setPluginEnabled("demo", true, callbacks::add);
            check(callbacks.size() == 1 && callbacks.get(0) != null, "public activation error delivered once");
            System.out.println("PASS: successful update, public error callback, safe-mode enable/disable persistence");
            reset(output, "sdk-cold-", null);
            Updater.touchFile(Updater.getPythonSdkUpdateFile());
            Files.writeString(Updater.getPythonSdkUpdateFile().toPath(), "sdk-B");
            check(INSTANCE.initSdk(), "cold SDK initialization");
            check(installations == 1 && imports.contains("_sdk_version"), "cold update installed before import");
            check(Files.readString(new File(SDK_DIR, "valid").toPath()).equals("sdk-B"), "new SDK selected");
            Updater.touchFile(Updater.getPythonSdkUpdateFile());
            Files.writeString(Updater.getPythonSdkUpdateFile().toPath(), "sdk-C");
            INSTANCE.shutdown(null);
            check(sdkInitialized && sdkImportStarted, "SDK process state survives shutdown");
            INSTANCE.python = new Python(); check(INSTANCE.initSdk(), "engine reuses old SDK");
            check(installations == 1 && Updater.getPythonSdkUpdateFile().exists(), "hot init leaves pending archive intact");
            check(Files.readString(new File(SDK_DIR, "valid").toPath()).equals("sdk-B"), "hot init never replaces SDK files");
            INSTANCE.initOnPluginsQueue(null);
            check(AppRestartHelper.restarts == 1 && installations == 1, "engine restart requests full process restart");
            reset(output, "sdk-failed-", null); failSdk = true;
            check(!INSTANCE.initSdk(), "SDK bootstrap failure"); int count = installations;
            failSdk = false; INSTANCE.shutdown(null); INSTANCE.python = new Python();
            check(!INSTANCE.initSdk() && installations == count, "partially imported SDK cannot be replaced or retried in-process");
            reset(output, "sdk-stage-", null);
            FileLoader.downloaded = new File(ApplicationLoader.files, "download.zip"); Files.writeString(FileLoader.downloaded.toPath(), "sdk-D");
            Updater.getPythonSdkUpdateFile().getParentFile().mkdirs();
            Updater.copyArchiveToPluginsDirectory(new TLRPC.Document(), false);
            check(Updater.status == Updater.STATUS_READY && AppRestartHelper.restarts == 0, "automatic update waits for restart");
            check(!INSTANCE.initSdk() && imports.isEmpty(), "same-process staged SDK blocks first import");
            Updater.copyArchiveToPluginsDirectory(new TLRPC.Document(), true);
            check(AppRestartHelper.restarts == 1 && installations == 0, "accepted update uses process restart without hot install");
            reset(output, "sdk-restore-", null); Updater.restoreSdkFromApk();
            check(Updater.isRestartRequired() && !INSTANCE.initSdk(), "APK restore also defers until process restart");
            System.out.println("PASS: cold SDK update, hot deferral, failed import, staged update and restore restart paths");
            for (boolean is64 : new boolean[]{false, true}) {
                AndroidPlatform.Process.is64 = is64; AndroidPlatform.AssetManager.only64 = false;
                new AndroidPlatform(new AndroidPlatform.Context());
                String expected = is64 ? "arm64-v8a" : "armeabi-v7a";
                check(AndroidPlatform.ABI.equals(expected), "asset ABI matches process bitness");
                check(AndroidPlatform.AssetManager.closes > 0, "asset probe stream closed");
                openedAssets.clear(); Updater.sdkFromApk().close();
                check(openedAssets.equals(List.of("plugins_pysdk/sdk-" + expected + ".zip")), "SDK archive matches runtime ABI");
                var update = new Updater.PythonSdkUpdateInfo(); update.abi = expected;
                check(update.canInstall(), "matching SDK ABI accepted");
                update.abi = is64 ? "armeabi-v7a" : "arm64-v8a";
                check(!update.canInstall(), "other SDK ABI rejected");
            }
            AndroidPlatform.Process.is64 = false; AndroidPlatform.AssetManager.only64 = true;
            boolean rejected = false;
            try { new AndroidPlatform(new AndroidPlatform.Context()); } catch (RuntimeException expected) { rejected = true; }
            check(rejected && AndroidPlatform.ABI == null, "no fallback to incompatible device ABI or stale selection");
            System.out.println("PASS: 32/64-bit process assets, SDK ABI filtering, incompatible-only asset rejection");
        }
        """;

    private static final String PLATFORM_FIXTURE = """
        Application mContext; SharedPreferences sp; AssetManager am; JSONObject buildJson;
        void loadNativeLibs() throws JSONException { }
        String streamToString(InputStream input) throws IOException { return "{}"; }
        static class Process { static boolean is64; static boolean is64Bit() { return is64; } }
        static class Build {
            static final String[] SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a"};
            static final String[] SUPPORTED_32_BIT_ABIS = {"armeabi-v7a"}, SUPPORTED_64_BIT_ABIS = {"arm64-v8a"};
        }
        static class Context { Context getApplicationContext() { return new Application(); } }
        static class Application extends Context {
            SharedPreferences getSharedPreferences(String key, int mode) { return new SharedPreferences(); }
            AssetManager getAssets() { return new AssetManager(); }
        }
        static class SharedPreferences { }
        static class AssetManager {
            static boolean only64; static int closes;
            InputStream open(String name) throws IOException {
                if (only64 && name.contains("armeabi-v7a")) throw new IOException("missing 32-bit asset");
                return new ByteArrayInputStream(new byte[0]) { public void close() { closes++; } };
            }
        }
        static class JSONObject { JSONObject(String text) throws JSONException { } }
        static class JSONException extends Exception { }
        static class Common {
            static final String ASSET_DIR = "chaquopy", ASSET_STDLIB = "stdlib";
            static String assetZip(String name, String abi) { return name + "-" + abi + ".imy"; }
        }
        static class Log { static void e(Object... args) { } }
        """;
}
