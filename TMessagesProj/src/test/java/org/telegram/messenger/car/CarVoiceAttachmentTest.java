package org.telegram.messenger.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.XmlResourceParser;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

/**
 * Covers the gating that decides whether a voice note is handed to the car as playable
 * audio. The car host is not involved, so this runs on the JVM.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class CarVoiceAttachmentTest {

    private Context context;
    private String authority;
    private File voiceFile;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        authority = context.getPackageName() + ".provider";
        File mediaDir = resolveConfiguredCacheMediaRoot();
        assertEquals(true, mediaDir.mkdirs() || mediaDir.isDirectory());
        clearFileProviderCache();
        if (File.separatorChar == '\\') {
            installPortablePathStrategy(mediaDir);
        }
        voiceFile = new File(mediaDir, "voice.ogg");
        writeBytes(voiceFile, 32);
    }

    @Test
    public void resolvesUriForDownloadedVoiceNote() {
        String state = "path=" + voiceFile.getAbsolutePath()
                + " exists=" + voiceFile.exists()
                + " length=" + voiceFile.length()
                + " authority=" + authority
                + " packageName=" + context.getPackageName()
                + " cacheDir=" + context.getCacheDir();

        Uri uri = CarVoiceAttachment.resolveUri(context, authority, voiceFile, true, false);

        // Surface provider configuration/platform failures instead of only the
        // wrapper's intentionally swallowed exception and null fallback.
        assertEquals(FileProvider.getUriForFile(context, authority, voiceFile), uri);
        assertNotNull(state, uri);
        assertEquals("content", uri.getScheme());
        assertEquals(authority, uri.getAuthority());
    }

    @Test
    public void providerConfigurationServesCacheMedia() throws Exception {
        ProviderInfo provider = context.getPackageManager()
                .resolveContentProvider(authority, PackageManager.GET_META_DATA);
        assertNotNull(provider);
        assertFalse(provider.exported);
        assertTrue(provider.grantUriPermissions);
        assertEquals(new File(context.getCacheDir(), "media").getCanonicalFile(),
                resolveConfiguredCacheMediaRoot());
    }

    @Test
    public void doesNotAttachWhenPreviewsAreSuppressed() {
        // The body already reads as a placeholder here; playing the recording would leak
        // exactly what suppressing previews is meant to hide.
        assertNull(CarVoiceAttachment.resolveUri(context, authority, voiceFile, false, false));
    }

    @Test
    public void doesNotAttachWhileLocked() {
        assertNull(CarVoiceAttachment.resolveUri(context, authority, voiceFile, true, true));
    }

    @Test
    public void doesNotAttachWhenFileIsMissing() {
        File absent = new File(context.getCacheDir(), "media/not-downloaded-yet.ogg");
        assertNull(CarVoiceAttachment.resolveUri(context, authority, absent, true, false));
    }

    @Test
    public void doesNotAttachWhenFileIsEmpty() throws IOException {
        // FileLoader#getPathToMessage never returns null; an interrupted download can leave
        // a zero-length file behind, which would surface as a silent, broken player.
        File empty = new File(context.getCacheDir(), "media/partial.ogg");
        writeBytes(empty, 0);
        assertNull(CarVoiceAttachment.resolveUri(context, authority, empty, true, false));
    }

    @Test
    public void doesNotAttachForNullFile() {
        assertNull(CarVoiceAttachment.resolveUri(context, authority, null, true, false));
    }

    @Test
    public void returnsNullInsteadOfThrowingForUnservedPath() throws IOException {
        // provider_paths.xml serves the cache directory's logs/ and media/ subtrees, not its
        // root, so a real file sitting directly in it makes FileProvider throw. The message
        // must still go out as text rather than the exception escaping.
        File unserved = new File(context.getCacheDir(), "unserved.ogg");
        writeBytes(unserved, 16);
        assertEquals(true, unserved.exists());

        try {
            FileProvider.getUriForFile(context, authority, unserved);
            fail("FileProvider accepted a file outside cache-media");
        } catch (IllegalArgumentException expected) {
        }
        assertNull(CarVoiceAttachment.resolveUri(context, authority, unserved, true, false));
    }

    @Test
    public void disclosureIsAllowedOnlyWithPreviewsAndUnlocked() {
        assertEquals(true, CarVoiceAttachment.isDisclosureAllowed(true, false));
        assertEquals(false, CarVoiceAttachment.isDisclosureAllowed(false, false));
        assertEquals(false, CarVoiceAttachment.isDisclosureAllowed(true, true));
        assertEquals(false, CarVoiceAttachment.isDisclosureAllowed(false, true));
    }

    @Test
    public void disclosureGateAgreesWithResolveUri() {
        // The caller checks the gate separately to decide whether an absent file is worth
        // downloading, so the two must not drift apart.
        boolean[] flags = {true, false};
        for (boolean preview : flags) {
            for (boolean locked : flags) {
                boolean allowed = CarVoiceAttachment.isDisclosureAllowed(preview, locked);
                Uri uri = CarVoiceAttachment.resolveUri(context, authority, voiceFile, preview, locked);
                assertEquals("preview=" + preview + " locked=" + locked, allowed, uri != null);
            }
        }
    }

    @Test
    public void mimeTypeMatchesTelegramVoiceEncoding() {
        assertEquals("audio/ogg", CarVoiceAttachment.MIME_TYPE);
    }

    private static void writeBytes(File file, int count) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[count]);
        }
    }

    private File resolveConfiguredCacheMediaRoot() throws Exception {
        PackageManager packageManager = context.getPackageManager();
        ProviderInfo provider = packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA);
        assertNotNull(provider);
        XmlResourceParser parser = provider.loadXmlMetaData(
                packageManager, "android.support.FILE_PROVIDER_PATHS");
        assertNotNull(parser);
        try {
            int event;
            while ((event = parser.next()) != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG
                        && "cache-path".equals(parser.getName())
                        && "cache-media".equals(parser.getAttributeValue(null, "name"))) {
                    return new File(context.getCacheDir(), parser.getAttributeValue(null, "path"));
                }
            }
        } finally {
            parser.close();
        }
        throw new AssertionError("provider_paths.xml is missing cache-media");
    }

    /**
     * androidx FileProvider uses '/' when checking canonical roots. Android canonical paths use
     * '/', but Robolectric on Windows returns '\', so a valid configured cache root is rejected.
     * Install an equivalent strategy rooted from the real provider_paths.xml for this JVM test.
     */
    private void installPortablePathStrategy(File mediaDir) throws Exception {
        Path root = mediaDir.getCanonicalFile().toPath();
        Class<?> strategyClass = Class.forName("androidx.core.content.FileProvider$PathStrategy");
        Object strategy = Proxy.newProxyInstance(
                strategyClass.getClassLoader(),
                new Class<?>[]{strategyClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUriForFile")) {
                        Path file = ((File) args[0]).getCanonicalFile().toPath();
                        if (!file.startsWith(root)) {
                            throw new IllegalArgumentException("File is outside cache-media: " + file);
                        }
                        String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                        return Uri.parse("content://" + authority + "/cache-media/" + Uri.encode(relative, "/"));
                    }
                    if (method.getName().equals("getFileForUri")) {
                        Uri uri = (Uri) args[0];
                        String path = uri.getEncodedPath();
                        if (!path.startsWith("/cache-media/")) {
                            throw new IllegalArgumentException("Unknown root: " + uri);
                        }
                        String relative = Uri.decode(path.substring("/cache-media/".length()));
                        File resolved = new File(mediaDir, relative.replace('/', File.separatorChar)).getCanonicalFile();
                        if (!resolved.toPath().startsWith(root)) {
                            throw new IllegalArgumentException("File escapes cache-media: " + resolved);
                        }
                        return resolved;
                    }
                    if (method.getName().equals("toString")) {
                        return "PortableFileProviderStrategy[" + authority + "]";
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    return null;
                });
        fileProviderCache().put(authority, strategy);
    }

    private static void clearFileProviderCache() throws Exception {
        fileProviderCache().clear();
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> fileProviderCache() throws Exception {
        Field cache = FileProvider.class.getDeclaredField("sCache");
        cache.setAccessible(true);
        return (Map<Object, Object>) cache.get(null);
    }
}
