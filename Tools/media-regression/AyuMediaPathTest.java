package com.radolyn.ayugram.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Desktop-JDK probe; kept outside Android's restricted unit-test compile bootclasspath. */
public class AyuMediaPathTest {
    private static final Path SOURCE_ROOT = findSourceRoot();

    @Test
    public void fileLoaderUsesItsOwnAccountMappingAndRetainsFallbacks() throws Exception {
        String method = method("org/telegram/messenger/FileLoader.java", "getPathToAttach", 5);
        try (URLClassLoader loader = compile("FileLoaderProbe", FILE_LOADER_STUBS + method + "}")) {
            Class<?> probe = loader.loadClass("FileLoaderProbe");
            probe.getMethod("verify").invoke(null);
        }
    }

    @Test
    public void mediaStoreUsesTheEnabledPathForEveryMediaTypeAndChatOption() throws Exception {
        String method = method("org/telegram/messenger/MediaController.java", "saveFileInternal", 4);
        try (URLClassLoader loader = compile("MediaStoreProbe", MEDIA_STORE_STUBS + method + "}")) {
            Class<?> probe = loader.loadClass("MediaStoreProbe");
            probe.getMethod("verify").invoke(null);
        }
    }

    @Test
    public void modifiedIntegrationFilesParseWithoutAndroidOrABuild() throws Exception {
        method("com/radolyn/ayugram/messages/AyuMessagesController.java", "clearAttachments", 0);
        method("com/radolyn/ayugram/controllers/AyuAttachments.java", "getExistingPath", 2);
        method("tw/nekomimi/nekogram/settings/NekoAyuSpySettingsActivity.java", "resolveTreeUriToFile", 1);
    }

    private static String method(String relativePath, String name, int parameterCount) throws Exception {
        Path path = SOURCE_ROOT.resolve(relativePath);
        String source = Files.readString(path);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                    manager.getJavaFileObjects(path.toFile()));
            for (CompilationUnitTree unit : task.parse()) {
                assertFalse(diagnostics.getDiagnostics().toString(), diagnostics.getDiagnostics().stream()
                        .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR));
                for (Tree declaration : unit.getTypeDecls()) {
                    if (!(declaration instanceof ClassTree type)) continue;
                    for (Tree member : type.getMembers()) {
                        if (member instanceof MethodTree method && method.getName().contentEquals(name)
                                && method.getParameters().size() == parameterCount) {
                            var positions = Trees.instance(task).getSourcePositions();
                            return source.substring((int) positions.getStartPosition(unit, method),
                                    (int) positions.getEndPosition(unit, method));
                        }
                    }
                }
            }
        }
        throw new AssertionError("Missing production method: " + relativePath + ":" + name);
    }

    private static URLClassLoader compile(String name, String source) throws Exception {
        Path directory = Files.createTempDirectory("ayu-media-path-probe-");
        Path path = directory.resolve(name + ".java");
        Files.writeString(path, source);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                directory.toString(), path.toString());
        assertEquals("Production method probe must compile", 0, result);
        return new URLClassLoader(new URL[]{directory.toUri().toURL()});
    }

    private static Path findSourceRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null) {
            Path candidate = root.resolve("TMessagesProj/src/main/java");
            if (Files.isDirectory(candidate)) return candidate;
            root = root.getParent();
        }
        throw new AssertionError("Run media path tests from the repository");
    }

    private static final String FILE_LOADER_STUBS = """
            import java.io.File;
            import java.util.ArrayList;
            public class FileLoaderProbe {
                static final int MEDIA_DIR_CACHE = 4, MEDIA_DIR_AUDIO = 1, MEDIA_DIR_VIDEO = 2,
                        MEDIA_DIR_DOCUMENT = 3, MEDIA_DIR_IMAGE = 0;
                static class TLObject { }
                static class TLRPC {
                    static class Document extends TLObject { String localPath; byte[] key; long id = 7; int dc_id = 2; }
                    static class Photo extends TLObject { ArrayList<PhotoSize> sizes; }
                    static class PhotoSize extends TLObject { FileLocation location; int size; }
                    static class TL_photoStrippedSize extends PhotoSize { }
                    static class TL_photoPathSize extends PhotoSize { }
                    static class TL_videoSize extends TLObject { FileLocation location; int size; }
                    static class FileLocation extends TLObject { byte[] key; long volume_id; int local_id, dc_id; }
                    static class UserProfilePhoto extends TLObject { }
                    static class ChatPhoto extends TLObject { }
                    static class TL_secureFile extends TLObject { }
                }
                static class SecureDocument extends TLObject { }
                static class WebFile extends TLObject { String mime_type; }
                static class AyuFileLocation extends TLRPC.FileLocation { String path; }
                static class TextUtils { static boolean isEmpty(String s) { return s == null || s.isEmpty(); } }
                static class AndroidUtilities { static int getPhotoSize(boolean b) { return 1280; } }
                static class MessageObject {
                    static boolean isVoiceDocument(TLRPC.Document d) { return false; }
                    static boolean isVideoDocument(TLRPC.Document d) { return false; }
                }
                static class UserConfig { static int selectedAccount = 0; }
                static final FileLoaderProbe[] INSTANCES = { new FileLoaderProbe("account-A"), new FileLoaderProbe("account-B") };
                static FileLoaderProbe getInstance(int account) { return INSTANCES[account]; }
                static class Database {
                    String path; boolean queue;
                    Database(String p) { path = p; }
                    String getPath(long id, int dc, int type, boolean useQueue) { queue = useQueue; return path; }
                }
                final Database database;
                FileLoaderProbe(String path) { database = new Database(path); }
                Database getFileDatabase() { return database; }
                static File getDirectory(int type) { return new File("directory-" + type); }
                static String getAttachFileName(TLObject object, String ext) { return "fallback.dat"; }
                static TLRPC.PhotoSize getClosestPhotoSizeWithSize(ArrayList<TLRPC.PhotoSize> sizes, int size) { return sizes.get(0); }
                File getPathToAttach(TLObject object, String ext, boolean cache, boolean queue) {
                    return getPathToAttach(object, null, ext, cache, queue);
                }
                static void check(File file, String expected) {
                    if (!file.equals(new File(expected))) throw new AssertionError(file + " != " + expected);
                }
                public static void verify() {
                    TLRPC.Document document = new TLRPC.Document();
                    FileLoaderProbe background = INSTANCES[1];
                    check(background.getPathToAttach(document, null, null, false, false), "account-B");
                    if (background.database.queue) throw new AssertionError("Queue flag changed");
                    UserConfig.selectedAccount = 1;
                    check(INSTANCES[0].getPathToAttach(document, null, null, false, true), "account-A");
                    check(background.getPathToAttach(document, null, null, true, true), "directory-4" + File.separator + "fallback.dat");
                    document.localPath = "legacy-saved-file";
                    check(background.getPathToAttach(document, null, null, false, true), "legacy-saved-file");
                    document.localPath = null;
                    background.database.path = null;
                    check(background.getPathToAttach(document, null, null, false, true), "directory-3" + File.separator + "fallback.dat");
                    AyuFileLocation location = new AyuFileLocation();
                    location.path = "legacy-ayu-file";
                    check(background.getPathToAttach(location, null, null, false, true), "legacy-ayu-file");
                }
            """;

    private static final String MEDIA_STORE_STUBS = """
            import java.io.*;
            import java.util.HashMap;
            public class MediaStoreProbe {
                @interface RequiresApi { int api(); }
                static class Build { static class VERSION_CODES { static final int Q = 29; } }
                static class MessageObject { }
                static class Uri { }
                static class ContentValues extends HashMap<String, String> { }
                static class TextUtils { static boolean isEmpty(String s) { return s == null || s.isEmpty(); } }
                static class Environment {
                    static final String DIRECTORY_PICTURES = "Pictures", DIRECTORY_MOVIES = "Movies",
                            DIRECTORY_DOWNLOADS = "Download", DIRECTORY_MUSIC = "Music";
                }
                static class MediaStore {
                    static final String VOLUME_EXTERNAL_PRIMARY = "external";
                    static class MediaColumns { static final String RELATIVE_PATH = "path", DISPLAY_NAME = "name", MIME_TYPE = "mime"; }
                    static class Collection extends MediaColumns { static Uri getContentUri(String volume) { return new Uri(); } }
                    static class Images { static class Media extends Collection { } }
                    static class Video { static class Media extends Collection { } }
                    static class Downloads extends Collection { }
                    static class Audio { static class Media extends Collection { } }
                }
                static class Config {
                    String String() { return "Archive"; }
                    boolean Bool() { return chat; }
                }
                static boolean enabled, chat;
                static class NekoConfig {
                    static Config customSavePath = new Config();
                    static String getCustomSavePath() { return enabled ? customSavePath.String() : ""; }
                }
                static class NaConfig {
                    static NaConfig INSTANCE = new NaConfig();
                    Config getSaveToChatSubfolder() { return new Config(); }
                }
                static class ChatsHelper { static String getChatFolderName(MessageObject object) { return "Chat"; } }
                static class FileLoader { static String getFileExtension(File file) { return "bin"; } }
                static class MimeTypeMap {
                    static MimeTypeMap getSingleton() { return new MimeTypeMap(); }
                    String getMimeTypeFromExtension(String ext) { return "application/octet-stream"; }
                }
                static class AndroidUtilities {
                    static String generateFileName(int type, String ext) { return "generated." + ext; }
                    static void copyFile(InputStream in, OutputStream out) { }
                }
                static class FileLog { static void e(Exception e) { throw new AssertionError(e); } }
                static ContentValues inserted;
                static class ApplicationLoader { static Context applicationContext = new Context(); }
                static class Context { Resolver getContentResolver() { return new Resolver(); } }
                static class Resolver {
                    Uri insert(Uri collection, ContentValues values) { inserted = values; return null; }
                    OutputStream openOutputStream(Uri uri) { throw new AssertionError("No filesystem access in path probe"); }
                }
                public static void verify() {
                    String[] bases = {"Pictures", "Movies", "Download", "Music"};
                    for (int type = 0; type < bases.length; type++) {
                        for (boolean custom : new boolean[]{false, true}) {
                            for (boolean subfolder : new boolean[]{false, true}) {
                                for (boolean hasMessage : new boolean[]{false, true}) {
                                    enabled = custom;
                                    chat = subfolder;
                                    saveFileInternal(type, new File("unused-source.bin"), "saved.bin", hasMessage ? new MessageObject() : null);
                                    String expected = bases[type];
                                    if (custom) expected += File.separator + "Archive";
                                    if (subfolder && hasMessage) expected += File.separator + "Chat";
                                    expected += File.separator;
                                    if (!expected.equals(inserted.get("path"))) throw new AssertionError(inserted + " != " + expected);
                                }
                            }
                        }
                    }
                }
            """;
}
