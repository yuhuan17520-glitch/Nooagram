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
        String saveMethod = method("org/telegram/messenger/MediaController.java", "saveFileInternal", 5);
        String deleteMethod = method("org/telegram/messenger/MediaController.java", "deleteMediaStoreUri", 1);
        try (URLClassLoader loader = compile("MediaStoreProbe", MEDIA_STORE_STUBS + deleteMethod + saveMethod + "}")) {
            Class<?> probe = loader.loadClass("MediaStoreProbe");
            probe.getMethod("verify").invoke(null);
        }
    }

    @Test
    public void saveNamesContinuePastTenWithoutOverwritingExistingFiles() throws Exception {
        String createMethod = method("org/telegram/messenger/MediaController.java", "createUniqueFile", 2);
        String sourceMethod = method("org/telegram/messenger/MediaController.java", "createUniqueFileForSource", 4);
        String cleanupMethod = method("org/telegram/messenger/MediaController.java", "copyFileWithCleanup", 3);
        try (URLClassLoader loader = compile("UniqueFileProbe",
                UNIQUE_FILE_STUBS + createMethod + sourceMethod + cleanupMethod + "}")) {
            Class<?> probe = loader.loadClass("UniqueFileProbe");
            probe.getMethod("verify").invoke(null);
        }
    }

    @Test
    public void saveCallbackRechecksCancellationWhenUiRunnableRuns() throws Exception {
        String method = method("org/telegram/messenger/MediaController.java", "runSaveCallbackIfNotCancelled", 2);
        try (URLClassLoader loader = compile("SaveCallbackProbe", SAVE_CALLBACK_STUBS + method + "}")) {
            Class<?> probe = loader.loadClass("SaveCallbackProbe");
            probe.getMethod("verify").invoke(null);
        }
    }

    @Test
    public void motionPhotoMediaStoreFailuresDeleteTheirOwnUri() throws Exception {
        String saveMethod = method("org/telegram/messenger/MediaController.java", "saveMotionPhotoToMediaStore", 4);
        String deleteMethod = method("org/telegram/messenger/MediaController.java", "deleteMediaStoreUri", 1);
        try (URLClassLoader loader = compile("MotionPhotoStoreProbe",
                MOTION_PHOTO_STORE_STUBS + deleteMethod + saveMethod + "}")) {
            Class<?> probe = loader.loadClass("MotionPhotoStoreProbe");
            probe.getMethod("verify").invoke(null);
        }
    }

    @Test
    public void motionPhotoFileFailuresDeleteHalfWrittenDestination() throws Exception {
        String helpMethod = method("org/telegram/messenger/MediaController.java", "writeMotionPhotoToFile", 4);
        String writeMethod = method("org/telegram/messenger/MediaController.java", "writeMotionPhoto", 4);
        String xmpMethod = method("org/telegram/messenger/MediaController.java", "buildMotionPhotoXmp", 1);
        try (URLClassLoader loader = compile("MotionPhotoFileProbe",
                MOTION_PHOTO_FILE_STUBS + xmpMethod + writeMethod + helpMethod + "}")) {
            Class<?> probe = loader.loadClass("MotionPhotoFileProbe");
            probe.getMethod("verify").invoke(null);
        }
    }

    @Test
    public void batchFailureClearsLoadingStateAndAlwaysFinishes() throws Exception {
        String method = method("org/telegram/messenger/MediaController.java", "runBatchSaving", 1);
        try (URLClassLoader loader = compile("BatchCleanupProbe", BATCH_CLEANUP_STUBS + method + "}")) {
            Class<?> probe = loader.loadClass("BatchCleanupProbe");
            probe.getMethod("verify").invoke(null);
        }
    }

    @Test
    public void cancellationReleasesAllWaitingFileCounts() throws Exception {
        String cancelMethod = method("org/telegram/messenger/MediaController.java", "cancelSave", 0);
        String prepareMethod = method("org/telegram/messenger/MediaController.java", "prepareWaitingForFile", 1);
        String releaseMethod = method("org/telegram/messenger/MediaController.java", "releaseWaitingForFile", 1);
        try (URLClassLoader loader = compile("CancelLatchProbe",
                CANCEL_LATCH_STUBS + releaseMethod + prepareMethod + cancelMethod + "}")) {
            Class<?> probe = loader.loadClass("CancelLatchProbe");
            probe.getMethod("verify").invoke(null);
        }
    }

    @Test
    public void modifiedIntegrationFilesParseWithoutAndroidOrABuild() throws Exception {
        method("com/radolyn/ayugram/messages/AyuMessagesController.java", "clearAttachments", 0);
        method("com/radolyn/ayugram/controllers/AyuAttachments.java", "getExistingPath", 2);
        method("com/radolyn/ayugram/settings/NekoAyuSpySettingsActivity.java", "resolveTreeUriToFile", 1);
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
                    MethodTree method = findMethod(type, name, parameterCount);
                    if (method != null) {
                        var positions = Trees.instance(task).getSourcePositions();
                        return source.substring((int) positions.getStartPosition(unit, method),
                                (int) positions.getEndPosition(unit, method));
                    }
                }
            }
        }
        throw new AssertionError("Missing production method: " + relativePath + ":" + name);
    }

    private static MethodTree findMethod(ClassTree type, String name, int parameterCount) {
        for (Tree member : type.getMembers()) {
            if (member instanceof MethodTree method && method.getName().contentEquals(name)
                    && method.getParameters().size() == parameterCount) {
                return method;
            }
            if (member instanceof ClassTree nested) {
                MethodTree method = findMethod(nested, name, parameterCount);
                if (method != null) {
                    return method;
                }
            }
        }
        return null;
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
                    static boolean isGifDocument(TLRPC.Document d) { return false; }
                    static boolean isStickerDocument(TLRPC.Document d) { return false; }
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
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.concurrent.atomic.AtomicBoolean;
            public class MediaStoreProbe {
                @interface RequiresApi { int api(); }
                static class Build { static class VERSION_CODES { static final int Q = 29; } }
                static class MessageObject { }
                static class Uri { final int id; Uri(int id) { this.id = id; } }
                static class ContentValues extends HashMap<String, String> { }
                static class ContentResolver {
                    Uri insert(Uri collection, ContentValues values) { throw new UnsupportedOperationException(); }
                    OutputStream openOutputStream(Uri uri) { throw new UnsupportedOperationException(); }
                    int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
                }
                static class Environment {
                    static final String DIRECTORY_PICTURES = "Pictures", DIRECTORY_MOVIES = "Movies",
                            DIRECTORY_DOWNLOADS = "Download", DIRECTORY_MUSIC = "Music";
                }
                static class MediaStore {
                    static final String VOLUME_EXTERNAL_PRIMARY = "external";
                    static class MediaColumns { static final String RELATIVE_PATH = "path", DISPLAY_NAME = "name", MIME_TYPE = "mime"; }
                    static class Collection extends MediaColumns { static Uri getContentUri(String volume) { return new Uri(0); } }
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
                static class AyuMediaFolders {
                    static String getFolderName(MessageObject object) {
                        String folder = NekoConfig.getCustomSavePath();
                        if (object != null && chat) folder = folder.isEmpty() ? "Chat" : folder + File.separator + "Chat";
                        return folder;
                    }
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
                }
                static class FileLog {
                    static Exception last;
                    static void e(Exception e) { last = e; }
                }
                static class TrackingOutput extends ByteArrayOutputStream {
                    boolean closed;
                    boolean failWrite;
                    @Override public void write(byte[] bytes, int offset, int length) {
                        if (failWrite) throw new UncheckedIOException(new IOException("write failed"));
                        super.write(bytes, offset, length);
                    }
                    @Override public void close() { closed = true; }
                    String text() { return new String(toByteArray(), StandardCharsets.UTF_8); }
                }
                static ContentValues inserted;
                static class Resolver extends ContentResolver {
                    boolean returnNullInsert;
                    int nextUri;
                    Uri lastInserted;
                    final List<Integer> deleted = new ArrayList<>();
                    TrackingOutput output;
                    AtomicBoolean cancelOnOpen;
                    @Override Uri insert(Uri collection, ContentValues values) {
                        inserted = values;
                        if (returnNullInsert) return null;
                        lastInserted = new Uri(++nextUri);
                        return lastInserted;
                    }
                    @Override OutputStream openOutputStream(Uri uri) {
                        if (cancelOnOpen != null) cancelOnOpen.set(true);
                        return output;
                    }
                    @Override int delete(Uri uri, String selection, String[] args) {
                        deleted.add(uri.id);
                        return 1;
                    }
                }
                static final Resolver resolver = new Resolver();
                static class ApplicationLoader { static Context applicationContext = new Context(); }
                static class Context { Resolver getContentResolver() { return resolver; } }

                static void must(boolean condition, String message) {
                    if (!condition) throw new AssertionError(message);
                }

                static void verifyPaths() {
                    String[] bases = {"Pictures", "Movies", "Download", "Music"};
                    resolver.returnNullInsert = true;
                    for (int type = 0; type < bases.length; type++) {
                        for (boolean custom : new boolean[]{false, true}) {
                            for (boolean subfolder : new boolean[]{false, true}) {
                                for (boolean hasMessage : new boolean[]{false, true}) {
                                    enabled = custom;
                                    chat = subfolder;
                                    saveFileInternal(type, new File("unused-source.bin"), "saved.bin",
                                            hasMessage ? new MessageObject() : null, new AtomicBoolean());
                                    String expected = bases[type];
                                    if (custom) expected += File.separator + "Archive";
                                    if (subfolder && hasMessage) expected += File.separator + "Chat";
                                    expected += File.separator;
                                    must(expected.equals(inserted.get("path")), inserted + " != " + expected);
                                }
                            }
                        }
                    }
                }

                static void verifyCopyLifecycle() throws Exception {
                    enabled = false;
                    chat = false;
                    resolver.returnNullInsert = false;
                    File source = Files.createTempFile("ayu-media-source-", ".bin").toFile();
                    Files.writeString(source.toPath(), "payload", StandardCharsets.UTF_8);

                    resolver.output = new TrackingOutput();
                    resolver.deleted.clear();
                    Uri saved = saveFileInternal(2, source, "saved.bin", null, new AtomicBoolean());
                    must(saved != null, "successful save returned null");
                    must(resolver.output.closed, "output stream was not closed");
                    must("payload".equals(resolver.output.text()), "saved bytes changed");
                    must(resolver.deleted.isEmpty(), "successful save deleted its URI");

                    resolver.output = new TrackingOutput();
                    resolver.deleted.clear();
                    AtomicBoolean cancelled = new AtomicBoolean();
                    resolver.cancelOnOpen = cancelled;
                    Uri cancelledSave = saveFileInternal(2, source, "cancelled.bin", null, cancelled);
                    resolver.cancelOnOpen = null;
                    must(cancelledSave == null, "cancelled save reported success");
                    must(resolver.output.closed, "cancelled output stream was not closed");
                    must(resolver.output.size() == 0, "cancelled save wrote bytes");
                    must(resolver.deleted.size() == 1 && resolver.deleted.get(0) == resolver.lastInserted.id,
                            "cancelled save did not delete its own URI");

                    resolver.output = new TrackingOutput();
                    resolver.output.failWrite = true;
                    resolver.deleted.clear();
                    Uri failedSave = saveFileInternal(2, source, "failed.bin", null, new AtomicBoolean());
                    must(failedSave == null, "failed save reported success");
                    must(resolver.output.closed, "failed output stream was not closed");
                    must(resolver.deleted.size() == 1 && resolver.deleted.get(0) == resolver.lastInserted.id,
                            "failed save did not delete its own URI");
                    must(FileLog.last instanceof UncheckedIOException, "write failure was not observed");

                    Files.deleteIfExists(source.toPath());
                }

                public static void verify() throws Exception {
                    verifyPaths();
                    verifyCopyLifecycle();
                }
            """;

    private static final String UNIQUE_FILE_STUBS = """
            import java.io.*;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.concurrent.atomic.AtomicBoolean;
            public class UniqueFileProbe {
                interface FileCopyOperation {
                    boolean copy(File sourceFile, File destFile);
                }
                static void must(boolean condition, String message) {
                    if (!condition) throw new AssertionError(message);
                }
                public static void verify() throws Exception {
                    Path root = Files.createTempDirectory("ayu-unique-save-");
                    File dir = root.toFile();
                    for (int i = 0; i <= 10; i++) {
                        String name = i == 0 ? "track.mp3" : "track(" + i + ").mp3";
                        Files.writeString(dir.toPath().resolve(name), "existing-" + i);
                    }
                    File selected = createUniqueFile(dir, "track.mp3");
                    must("track(11).mp3".equals(selected.getName()), "unexpected unique name: " + selected.getName());
                    must(selected.isFile() && selected.length() == 0, "selected name was not reserved");
                    must("existing-10".equals(Files.readString(dir.toPath().resolve("track(10).mp3"))),
                            "existing (10) file was overwritten");
                    File next = createUniqueFile(dir, "track.mp3");
                    must("track(12).mp3".equals(next.getName()), "subsequent unique name: " + next.getName());

                    File source = dir.toPath().resolve("track.mp3").toFile();
                    Path missingDir = root.resolve("missing-source");
                    File missing = createUniqueFileForSource(missingDir.toFile(), "download.bin",
                            root.resolve("absent.bin").toFile(), new AtomicBoolean());
                    must(missing == null, "missing source reserved a destination");
                    must(!missingDir.toFile().exists(), "missing source created an empty directory");

                    Path cancelledDir = root.resolve("cancelled");
                    File cancelled = createUniqueFileForSource(cancelledDir.toFile(), "download.bin",
                            source, new AtomicBoolean(true));
                    must(cancelled == null, "cancelled save reserved a destination");
                    must(!cancelledDir.toFile().exists(), "cancelled save created an empty directory");

                    Path readyDir = root.resolve("ready");
                    File ready = createUniqueFileForSource(readyDir.toFile(), "download.bin",
                            source, new AtomicBoolean());
                    must(ready != null && ready.isFile(), "ready source did not reserve a destination");

                    Path failedDir = root.resolve("copy-failed");
                    File failed = createUniqueFile(failedDir.toFile(), "download.bin");
                    boolean threw = false;
                    try {
                        copyFileWithCleanup(source, failed, (ignoredSource, ignoredDest) -> {
                            throw new IllegalStateException("copy failed");
                        });
                    } catch (IllegalStateException expected) {
                        threw = true;
                    }
                    must(threw, "copy exception did not propagate");
                    must(!failed.exists(), "copy exception leaked the reserved file");
                }
            """;

    private static final String SAVE_CALLBACK_STUBS = """
            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.atomic.AtomicBoolean;
            public class SaveCallbackProbe {
                static final List<Runnable> queued = new ArrayList<>();
                static int callbacks;
                static class AndroidUtilities {
                    static void runOnUIThread(Runnable runnable) {
                        queued.add(runnable);
                    }
                }
                static void must(boolean condition, String message) {
                    if (!condition) throw new AssertionError(message);
                }
                public static void verify() {
                    AtomicBoolean delayedCancellation = new AtomicBoolean();
                    runSaveCallbackIfNotCancelled(delayedCancellation, () -> callbacks++);
                    must(queued.size() == 1 && callbacks == 0, "callback ran before the UI turn");
                    delayedCancellation.set(true);
                    queued.get(0).run();
                    must(callbacks == 0, "cancelled callback ran after its UI turn");

                    AtomicBoolean active = new AtomicBoolean();
                    runSaveCallbackIfNotCancelled(active, () -> callbacks++);
                    must(queued.size() == 2 && callbacks == 0, "active callback ran before the UI turn");
                    queued.get(1).run();
                    must(callbacks == 1, "active callback did not run");
                }
            """;

    private static final String MOTION_PHOTO_STORE_STUBS = """
            import java.io.ByteArrayOutputStream;
            import java.io.IOException;
            import java.io.OutputStream;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.atomic.AtomicBoolean;
            public class MotionPhotoStoreProbe {
                interface MotionPhotoWriter {
                    void write(OutputStream outputStream) throws IOException;
                }
                static class Uri {
                    final int id;
                    Uri(int id) { this.id = id; }
                }
                static class ContentValues { }
                static class ContentResolver {
                    Uri insert(Uri collection, ContentValues values) { throw new UnsupportedOperationException(); }
                    OutputStream openOutputStream(Uri uri) { throw new UnsupportedOperationException(); }
                    int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
                }
                static class Resolver extends ContentResolver {
                    int nextId;
                    Uri lastInserted;
                    OutputStream output;
                    final List<Integer> deleted = new ArrayList<>();
                    @Override Uri insert(Uri collection, ContentValues values) {
                        lastInserted = new Uri(++nextId);
                        return lastInserted;
                    }
                    @Override OutputStream openOutputStream(Uri uri) {
                        return output;
                    }
                    @Override int delete(Uri uri, String selection, String[] args) {
                        deleted.add(uri.id);
                        return 1;
                    }
                }
                static final Resolver resolver = new Resolver();
                static class ApplicationLoader {
                    static Context applicationContext = new Context();
                }
                static class Context {
                    Resolver getContentResolver() { return resolver; }
                }
                static class FileLog {
                    static Exception last;
                    static void e(Exception e) { last = e; }
                }
                static void must(boolean condition, String message) {
                    if (!condition) throw new AssertionError(message);
                }
                public static void verify() throws Exception {
                    ContentValues values = new ContentValues();
                    Uri collection = new Uri(0);

                    resolver.output = new ByteArrayOutputStream();
                    resolver.deleted.clear();
                    AtomicBoolean active = new AtomicBoolean();
                    Uri saved = saveMotionPhotoToMediaStore(collection, values, active,
                            output -> output.write(42));
                    must(saved != null, "successful motion photo returned null");
                    must(resolver.deleted.isEmpty(), "successful motion photo deleted its URI");

                    resolver.output = new ByteArrayOutputStream();
                    resolver.deleted.clear();
                    AtomicBoolean cancelled = new AtomicBoolean();
                    Uri cancelledSave = saveMotionPhotoToMediaStore(collection, values, cancelled, output -> {
                        output.write(42);
                        cancelled.set(true);
                    });
                    must(cancelledSave == null, "cancelled motion photo reported success");
                    must(resolver.deleted.size() == 1 && resolver.deleted.get(0) == resolver.lastInserted.id,
                            "cancelled motion photo did not delete its URI");

                    resolver.output = null;
                    resolver.deleted.clear();
                    Uri failedSave = saveMotionPhotoToMediaStore(collection, values, new AtomicBoolean(), output -> { });
                    must(failedSave == null, "failed motion photo reported success");
                    must(resolver.deleted.size() == 1 && resolver.deleted.get(0) == resolver.lastInserted.id,
                            "failed motion photo did not delete its URI");
                    must(FileLog.last instanceof IOException, "motion photo output failure was not observed");
                }
            """;

    private static final String MOTION_PHOTO_FILE_STUBS = """
            import java.io.*;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.concurrent.atomic.AtomicBoolean;
            public class MotionPhotoFileProbe {
                static void must(boolean condition, String message) {
                    if (!condition) throw new AssertionError(message);
                }
                public static void verify() throws Exception {
                    Path root = Files.createTempDirectory("ayu-motion-photo-");
                    File photo = root.resolve("photo.jpg").toFile();
                    Files.write(photo.toPath(), new byte[]{(byte) 0xFF, (byte) 0xD8});
                    File missingVideo = root.resolve("missing.mp4").toFile();
                    File destination = root.resolve("output.jpg").toFile();
                    destination.createNewFile();
                    boolean threw = false;
                    try {
                        writeMotionPhotoToFile(photo, missingVideo, destination, new AtomicBoolean());
                    } catch (IOException expected) {
                        threw = true;
                    }
                    must(threw, "missing motion photo video did not fail");
                    must(!destination.exists(), "half-written motion photo was not deleted");
                }
            """;

    private static final String BATCH_CLEANUP_STUBS = """
            import java.io.IOException;
            import java.util.HashMap;
            import java.util.Map;
            public class BatchCleanupProbe {
                interface BatchSaveOperation {
                    void run() throws Exception;
                }
                final Map<String, Object> loadingMessageObjects = new HashMap<>();
                int finishCount;
                static class FileLog {
                    static void e(Exception e) { }
                }
                void checkIfFinished() {
                    finishCount++;
                }
                static void must(boolean condition, String message) {
                    if (!condition) throw new AssertionError(message);
                }
                public static void verify() {
                    BatchCleanupProbe probe = new BatchCleanupProbe();
                    probe.loadingMessageObjects.put("pending", new Object());
                    probe.runBatchSaving(() -> { throw new IOException("save failed"); });
                    must(probe.loadingMessageObjects.isEmpty(), "batch failure left loading state");
                    must(probe.finishCount == 1, "batch failure skipped finish cleanup");
                    probe.loadingMessageObjects.put("stale", new Object());
                    probe.runBatchSaving(() -> { });
                    must(probe.loadingMessageObjects.isEmpty(), "successful batch left loading state");
                    must(probe.finishCount == 2, "successful batch skipped finish cleanup");
                }
            """;

    private static final String CANCEL_LATCH_STUBS = """
            import java.util.concurrent.CountDownLatch;
            import java.util.concurrent.TimeUnit;
            import java.util.concurrent.atomic.AtomicBoolean;
            public class CancelLatchProbe {
                final AtomicBoolean cancelled = new AtomicBoolean();
                volatile CountDownLatch waitingForFile;
                final java.util.Map<String, Object> loadingMessageObjects = new java.util.HashMap<>();
                static void must(boolean condition, String message) {
                    if (!condition) throw new AssertionError(message);
                }
                public static void verify() throws Exception {
                    CancelLatchProbe probe = new CancelLatchProbe();
                    CountDownLatch waiting = probe.prepareWaitingForFile(2);
                    must(waiting.getCount() == 2, "waiting latch count changed early");
                    probe.loadingMessageObjects.put("pending", new Object());
                    probe.cancelSave();
                    must(waiting.getCount() == 0, "cancellation did not release every waiting count");
                    must(waiting.await(100, TimeUnit.MILLISECONDS), "cancelled wait was not released");
                    must(probe.loadingMessageObjects.isEmpty(), "cancellation left stale loading state");

                    probe.cancelSave();
                    CountDownLatch preparedAfterCancel = probe.prepareWaitingForFile(2);
                    must(preparedAfterCancel.getCount() == 0, "pre-cancel latch was not released");
                }
            """;
}
