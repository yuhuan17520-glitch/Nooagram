package tw.nekomimi.nekogram.helpers.remote;

import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import xyz.nextalone.nagram.NaConfig;

public final class NooagramUpdateHelper {
    private static final String UPDATE_MANIFEST_URL =
            "https://github.com/yuhuan17520-glitch/Nooagram/releases/latest/download/update.json";

    private static final class DownloadHolder {
        static final OkHttpClient CLIENT = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        static final DownloadManager MANAGER = new DownloadManager(
                Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "NooagramUpdateDownload");
                    thread.setDaemon(true);
                    return thread;
                }), AndroidUtilities::runOnUIThread, new DownloadListener() {
                    @Override
                    public void progress(int account, String name, long downloaded, long total) {
                        NotificationCenter.getInstance(account).postNotificationName(
                                NotificationCenter.fileLoadProgressChanged, name, downloaded, total);
                        if (downloaded == 0) {
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                        }
                    }

                    @Override
                    public void loaded(int account, String name, File file) {
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoaded, name, file);
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                    }

                    @Override
                    public void failed(int account, String name, int reason) {
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoadFailed, name, reason);
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                    }
                });
    }

    private NooagramUpdateHelper() {}

    public static boolean isNooagramUpdate(TLRPC.TL_help_appUpdate update) {
        return update != null && update.url != null && update.url.contains("Nooagram");
    }

    public static boolean isDownloading(String fileName) {
        return fileName != null && DownloadHolder.MANAGER.isDownloading(fileName);
    }

    public static Float getProgress(String fileName) {
        return fileName != null ? DownloadHolder.MANAGER.getProgress(fileName) : null;
    }

    public static void cancelDownload(String fileName) {
        if (fileName != null) {
            DownloadHolder.MANAGER.cancel(fileName);
        }
    }

    public static void startDownload(int accountNum, TLRPC.TL_help_appUpdate appUpdate) {
        if (appUpdate == null || appUpdate.document == null || TextUtils.isEmpty(appUpdate.url)) {
            return;
        }
        String fileName = FileLoader.getAttachFileName(appUpdate.document);
        File targetFile = FileLoader.getInstance(accountNum).getPathToAttach(appUpdate.document, true);
        Request request = new Request.Builder()
                .url(appUpdate.url)
                .header("User-Agent", "Nooagram/" + BuildConfig.VERSION_NAME)
                .build();
        DownloadHolder.MANAGER.start(accountNum, fileName, targetFile, appUpdate.document.size,
                DownloadHolder.CLIENT.newCall(request));
    }

    interface DownloadListener {
        void progress(int account, String name, long downloaded, long total);
        void loaded(int account, String name, File file);
        void failed(int account, String name, int reason);
    }

    static final class DownloadManager {
        private final ConcurrentHashMap<String, Download> downloads = new ConcurrentHashMap<>();
        private final Executor worker;
        private final Executor callbacks;
        private final DownloadListener listener;

        DownloadManager(Executor worker, Executor callbacks, DownloadListener listener) {
            this.worker = worker;
            this.callbacks = callbacks;
            this.listener = listener;
        }

        synchronized boolean isDownloading(String name) {
            Download download = downloads.get(name);
            return download != null && !download.finished;
        }

        synchronized Float getProgress(String name) {
            Download download = downloads.get(name);
            return download == null || download.finished ? null : download.progress;
        }

        synchronized void start(int account, String name, File target, long size, Call call) {
            if (isDownloading(name)) {
                return;
            }
            Download download = new Download(account, name, target, size, call);
            downloads.put(name, download);
            postProgress(download, 0, size);
            worker.execute(() -> run(download));
        }

        synchronized void cancel(String name) {
            Download download = downloads.get(name);
            if (download == null || download.finished) {
                return;
            }
            download.call.cancel();
            finish(download, false, 1);
        }

        private synchronized boolean ownsDownload(Download download) {
            return downloads.get(download.name) == download && !download.finished;
        }

        private synchronized void postProgress(Download download, long bytes, long total) {
            if (!ownsDownload(download)) {
                return;
            }
            download.progress = total > 0 ? (float) bytes / total : 0f;
            callbacks.execute(() -> {
                synchronized (DownloadManager.this) {
                    if (ownsDownload(download)) {
                        listener.progress(download.account, download.name, bytes, total);
                    }
                }
            });
        }

        private synchronized void finish(Download download, boolean success, int reason) {
            if (!ownsDownload(download)) {
                return;
            }
            download.finished = true;
            callbacks.execute(() -> {
                synchronized (DownloadManager.this) {
                    // A queued terminal event must not change a replacement download's UI.
                    if (!downloads.remove(download.name, download)) {
                        return;
                    }
                    if (success) {
                        listener.loaded(download.account, download.name, download.target);
                    } else {
                        listener.failed(download.account, download.name, reason);
                    }
                }
            });
        }

        private void run(Download download) {
            File tempFile = null;
            try {
                synchronized (this) {
                    if (!ownsDownload(download)) {
                        return;
                    }
                    if (download.target.exists() && download.target.length() > 0
                            && (download.size <= 0 || download.target.length() == download.size)) {
                        finish(download, true, 0);
                        return;
                    }
                }
                File parent = download.target.getAbsoluteFile().getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Unable to create update directory");
                }
                tempFile = File.createTempFile("nooagram-", ".temp", parent);
                try (Response response = download.call.execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("Download failed with HTTP " + response.code());
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new IOException("Empty response body");
                    }
                    long contentLength = body.contentLength();
                    long totalBytes = contentLength > 0 ? contentLength : download.size;
                    long downloadedBytes = 0;

                    try (InputStream in = body.byteStream();
                         FileOutputStream out = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[32768];
                        int read;
                        long lastPostTime = 0;
                        while ((read = in.read(buffer)) != -1) {
                            if (!ownsDownload(download)) {
                                return;
                            }
                            out.write(buffer, 0, read);
                            downloadedBytes += read;
                            long now = System.currentTimeMillis();
                            if (now - lastPostTime > 120 || downloadedBytes == totalBytes) {
                                lastPostTime = now;
                                postProgress(download, downloadedBytes, totalBytes);
                            }
                        }
                        out.flush();
                    }

                    if ((contentLength >= 0 && downloadedBytes != contentLength)
                            || (download.size > 0 && downloadedBytes != download.size)) {
                        throw new IOException("Incomplete update download");
                    }
                }
                synchronized (this) {
                    // Cancellation, replacement and promotion are one ownership decision.
                    if (!ownsDownload(download)) {
                        return;
                    }
                    if (!tempFile.renameTo(download.target)) {
                        throw new IOException("Unable to promote update file");
                    }
                    finish(download, true, 0);
                }
            } catch (Exception exception) {
                finish(download, false, download.call.isCanceled() ? 1 : 0);
            } finally {
                if (tempFile != null) {
                    tempFile.delete();
                }
            }
        }

        private static final class Download {
            final int account;
            final String name;
            final File target;
            final long size;
            final Call call;
            boolean finished;
            float progress;

            Download(int account, String name, File target, long size, Call call) {
                this.account = account;
                this.name = name;
                this.target = target;
                this.size = size;
                this.call = call;
            }
        }
    }

    public static void check(BaseRemoteHelper.Delegate delegate) {
        check(delegate, false);
    }

    public static void check(BaseRemoteHelper.Delegate delegate, boolean force) {
        if (!force && NaConfig.INSTANCE.getAutoUpdateChannel().Int() == UpdateHelper.UPDATE_OFF) {
            delegate.onTLResponse(null, null);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            TLRPC.TL_help_appUpdate update = null;
            String error = null;
            try {
                JSONObject manifest = fetchManifest();
                long versionCode = manifest.getLong("version_code");
                String version = manifest.getString("version");
                if (isNewVersionAvailable(version, versionCode, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
                    JSONObject assets = manifest.optJSONObject("assets");
                    if (assets == null) {
                        // Legacy manifests only published ARM APKs.
                        assets = new JSONObject();
                        if (manifest.has("download_url")) {
                            assets.put("arm64-v8a", new JSONObject()
                                    .put("download_url", manifest.getString("download_url"))
                                    .put("size", manifest.optLong("size", 0L)));
                        }
                        if (manifest.has("download_url_32")) {
                            assets.put("armeabi-v7a", new JSONObject()
                                    .put("download_url", manifest.getString("download_url_32"))
                                    .put("size", manifest.optLong("size_32", 0L)));
                        }
                    }
                    ArrayList<String> publishedAbis = new ArrayList<>();
                    assets.keys().forEachRemaining(publishedAbis::add);
                    String abi = selectAbi(Build.SUPPORTED_ABIS, publishedAbis);
                    if (abi == null) {
                        throw new IOException("No compatible Nooagram update APK is published for this device");
                    }
                    JSONObject asset = assets.getJSONObject(abi);
                    String downloadUrl = asset.getString("download_url");
                    long size = asset.optLong("size", 0L);
                    update = new TLRPC.TL_help_appUpdate();
                    update.version = version;
                    update.can_not_skip = manifest.optBoolean("can_not_skip", false);

                    update.url = downloadUrl;
                    update.flags |= 4;

                    TLRPC.TL_document doc = new TLRPC.TL_document();
                    doc.id = versionCode;
                    doc.dc_id = 1;
                    doc.access_hash = 0;
                    doc.file_reference = new byte[0];
                    doc.size = size;
                    doc.mime_type = "application/vnd.android.package-archive";
                    doc.file_name_fixed = "Nooagram-" + version + "-" + abi + ".apk";
                    TLRPC.TL_documentAttributeFilename attr = new TLRPC.TL_documentAttributeFilename();
                    attr.file_name = doc.file_name_fixed;
                    doc.attributes.add(attr);
                    update.document = doc;
                    update.flags |= 2;

                    update.text = manifest.optString("changelog", "");
                    if (TextUtils.isEmpty(update.text)) {
                        update.text = "Nooagram update";
                    }
                }
            } catch (Throwable throwable) {
                update = null;
                error = throwable.getMessage();
                if (TextUtils.isEmpty(error)) {
                    error = "Unable to load Nooagram update manifest";
                }
            }
            TLRPC.TL_help_appUpdate finalUpdate = update;
            String finalError = error;
            AndroidUtilities.runOnUIThread(() -> delegate.onTLResponse(finalUpdate, finalError));
        });
    }

    public static String selectAbi(String[] supportedAbis, Collection<String> publishedAbis) {
        if (supportedAbis != null && publishedAbis != null) {
            for (String abi : supportedAbis) {
                if (publishedAbis.contains(abi)) {
                    return abi;
                }
            }
        }
        return null;
    }

    private static JSONObject fetchManifest() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(UPDATE_MANIFEST_URL).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("Nooagram update server returned HTTP " + code);
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static boolean isNewVersionAvailable(String remoteVersion, long remoteVersionCode, String currentVersion, long currentVersionCode) {
        if (remoteVersion == null || currentVersion == null) {
            return false;
        }
        String remote = remoteVersion.trim();
        String current = currentVersion.trim();
        if (remote.isEmpty() || remote.equalsIgnoreCase(current)) {
            return false;
        }
        if (remoteVersionCode > currentVersionCode) {
            return true;
        }
        if (remoteVersionCode < currentVersionCode) {
            return false;
        }
        return compareSemVer(remote, current) > 0;
    }

    public static int compareSemVer(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        if (v1.equalsIgnoreCase(v2)) {
            return 0;
        }
        String[] parts1 = v1.split("\\+", 2)[0].split("-", 2);
        String[] parts2 = v2.split("\\+", 2)[0].split("-", 2);

        int cmp = compareDotSegments(parts1[0], parts2[0]);
        if (cmp != 0) {
            return cmp;
        }

        String suffix1 = parts1.length > 1 ? parts1[1] : "";
        String suffix2 = parts2.length > 1 ? parts2[1] : "";
        if (suffix1.isEmpty() && suffix2.isEmpty()) {
            return 0;
        }
        if (suffix1.isEmpty()) {
            return 1;
        }
        if (suffix2.isEmpty()) {
            return -1;
        }
        return compareDotSegments(suffix1, suffix2);
    }

    private static int compareDotSegments(String s1, String s2) {
        String[] nums1 = s1.split("\\.");
        String[] nums2 = s2.split("\\.");
        int max = Math.max(nums1.length, nums2.length);
        for (int i = 0; i < max; i++) {
            if (i == nums1.length || i == nums2.length) {
                return Integer.compare(nums1.length, nums2.length);
            }
            String n1 = nums1[i];
            String n2 = nums2[i];
            boolean numeric1 = n1.matches("[0-9]+");
            boolean numeric2 = n2.matches("[0-9]+");
            int comparison;
            if (numeric1 && numeric2) {
                comparison = new java.math.BigInteger(n1).compareTo(new java.math.BigInteger(n2));
            } else if (numeric1 != numeric2) {
                comparison = numeric1 ? -1 : 1;
            } else {
                comparison = n1.compareTo(n2);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

}
