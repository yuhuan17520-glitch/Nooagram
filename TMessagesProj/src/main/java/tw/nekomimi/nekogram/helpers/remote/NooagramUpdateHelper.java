package tw.nekomimi.nekogram.helpers.remote;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import xyz.nextalone.nagram.NaConfig;

public final class NooagramUpdateHelper {
    private static final String UPDATE_MANIFEST_URL =
            "https://github.com/yuhuan17520-glitch/Nooagram/releases/latest/download/update.json";

    private NooagramUpdateHelper() {}

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
                    update.url = manifest.getString("download_url");
                    update.flags |= 4;
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
