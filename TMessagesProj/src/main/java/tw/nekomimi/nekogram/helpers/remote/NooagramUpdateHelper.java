package tw.nekomimi.nekogram.helpers.remote;

import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
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
import java.util.concurrent.ConcurrentHashMap;
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

    private static final ConcurrentHashMap<String, Call> downloadCalls = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Float> downloadProgressMap = new ConcurrentHashMap<>();

    private static final OkHttpClient downloadClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private NooagramUpdateHelper() {}

    public static boolean isNooagramUpdate(TLRPC.TL_help_appUpdate update) {
        return update != null && update.url != null && update.url.contains("Nooagram");
    }

    public static boolean isDownloading(String fileName) {
        return fileName != null && downloadCalls.containsKey(fileName);
    }

    public static Float getProgress(String fileName) {
        return fileName != null ? downloadProgressMap.get(fileName) : null;
    }

    public static void cancelDownload(String fileName) {
        if (fileName == null) return;
        Call call = downloadCalls.remove(fileName);
        if (call != null) {
            call.cancel();
        }
        downloadProgressMap.remove(fileName);
    }

    public static void startDownload(int accountNum, TLRPC.TL_help_appUpdate appUpdate) {
        if (appUpdate == null || appUpdate.document == null || TextUtils.isEmpty(appUpdate.url)) {
            return;
        }
        String fileName = FileLoader.getAttachFileName(appUpdate.document);
        if (isDownloading(fileName)) {
            return;
        }
        File targetFile = FileLoader.getInstance(accountNum).getPathToAttach(appUpdate.document, true);
        if (targetFile.exists() && targetFile.length() > 0) {
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getInstance(accountNum).postNotificationName(NotificationCenter.fileLoaded, fileName, targetFile);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            });
            return;
        }

        File tempFile = new File(targetFile.getAbsolutePath() + ".temp");
        Request request = new Request.Builder()
                .url(appUpdate.url)
                .header("User-Agent", "Nooagram/" + BuildConfig.VERSION_NAME)
                .build();
        Call call = downloadClient.newCall(request);
        downloadCalls.put(fileName, call);
        downloadProgressMap.put(fileName, 0.0f);

        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(accountNum).postNotificationName(NotificationCenter.fileLoadProgressChanged, fileName, 0L, appUpdate.document.size);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
        });

        Utilities.globalQueue.postRunnable(() -> {
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("Download failed with HTTP " + response.code());
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IllegalStateException("Empty response body");
                }
                long contentLength = body.contentLength();
                long totalBytes = contentLength > 0 ? contentLength : appUpdate.document.size;
                long downloadedBytes = 0;

                try (InputStream in = body.byteStream();
                     FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[32768];
                    int read;
                    long lastPostTime = 0;
                    while ((read = in.read(buffer)) != -1) {
                        if (call.isCanceled()) {
                            tempFile.delete();
                            return;
                        }
                        out.write(buffer, 0, read);
                        downloadedBytes += read;
                        long now = System.currentTimeMillis();
                        if (now - lastPostTime > 120 || downloadedBytes == totalBytes) {
                            lastPostTime = now;
                            final long finalDownloaded = downloadedBytes;
                            final float progress = totalBytes > 0 ? (float) downloadedBytes / totalBytes : 0f;
                            downloadProgressMap.put(fileName, progress);
                            AndroidUtilities.runOnUIThread(() -> {
                                NotificationCenter.getInstance(accountNum).postNotificationName(
                                        NotificationCenter.fileLoadProgressChanged,
                                        fileName,
                                        finalDownloaded,
                                        totalBytes
                                );
                            });
                        }
                    }
                    out.flush();
                }

                if (call.isCanceled()) {
                    tempFile.delete();
                    return;
                }

                if (tempFile.renameTo(targetFile) || (targetFile.delete() && tempFile.renameTo(targetFile))) {
                    downloadCalls.remove(fileName);
                    downloadProgressMap.remove(fileName);
                    AndroidUtilities.runOnUIThread(() -> {
                        NotificationCenter.getInstance(accountNum).postNotificationName(
                                NotificationCenter.fileLoaded,
                                fileName,
                                targetFile
                        );
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                    });
                } else {
                    throw new IOException("Failed to rename update file to " + targetFile.getAbsolutePath());
                }
            } catch (Throwable t) {
                FileLog.e("NooagramUpdateHelper.download", t);
                tempFile.delete();
                downloadCalls.remove(fileName);
                downloadProgressMap.remove(fileName);
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(accountNum).postNotificationName(
                            NotificationCenter.fileLoadFailed,
                            fileName
                    );
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                });
            }
        });
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
                long timestamp = manifest.getLong("timestamp");
                if (force
                        || versionCode > BuildConfig.VERSION_CODE
                        || versionCode == BuildConfig.VERSION_CODE
                        && timestamp > BuildConfig.BUILD_TIMESTAMP) {
                    update = new TLRPC.TL_help_appUpdate();
                    update.version = manifest.getString("version");
                    update.can_not_skip = manifest.optBoolean("can_not_skip", false);

                    String downloadUrl = manifest.getString("download_url");
                    long size = manifest.optLong("size", 0L);
                    boolean is64Bit = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
                    if (!is64Bit && manifest.has("download_url_32")) {
                        downloadUrl = manifest.getString("download_url_32");
                        if (manifest.has("size_32")) {
                            size = manifest.optLong("size_32", size);
                        }
                    }
                    update.url = downloadUrl;
                    update.flags |= 4;

                    TLRPC.TL_document doc = new TLRPC.TL_document();
                    doc.id = versionCode;
                    doc.dc_id = 1;
                    doc.access_hash = 0;
                    doc.file_reference = new byte[0];
                    doc.size = size > 0 ? size : 56 * 1024 * 1024L;
                    doc.mime_type = "application/vnd.android.package-archive";
                    doc.file_name_fixed = "Nooagram-" + manifest.getString("version") + ".apk";
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
}
