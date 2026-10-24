package tw.nekomimi.nekogram.helpers;

import android.util.SparseArray;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class CloudStorageHelper extends AccountInstance {

    private static final SparseArray<CloudStorageHelper> Instance = new SparseArray<>();
    private static final long WEBVIEW_BOT_ID = 1433866570L;
    private static final String WEBVIEW_BOT = "NextAloneBot";
    static final int MAX_REQUEST_DATA_BYTES = 4000;
    static final String ERROR_DATA_TOO_LONG = "DATA_TOO_LONG";
    static final String ERROR_EMPTY_RESPONSE = "EMPTY_RESPONSE";
    static final String ERROR_INVALID_RESPONSE = "INVALID_RESPONSE";
    private static final Type STRING_MAP_TYPE = new TypeToken<HashMap<String, String>>() {
    }.getType();

    private final Gson gson = new Gson();

    public CloudStorageHelper(int num) {
        super(num);
    }

    public static CloudStorageHelper getInstance(int num) {
        CloudStorageHelper localInstance = Instance.get(num);
        if (localInstance == null) {
            synchronized (CloudStorageHelper.class) {
                localInstance = Instance.get(num);
                if (localInstance == null) {
                    Instance.put(num, localInstance = new CloudStorageHelper(num));
                }
            }
        }
        return localInstance;
    }

    private void invokeWebViewCustomMethod(String method, String data, Utilities.Callback2<String, String> callback) {
        invokeWebViewCustomMethod(method, data, true, callback);
    }

    private void invokeWebViewCustomMethod(String method, String data, boolean searchUser, Utilities.Callback2<String, String> callback) {
        TLRPC.User user = getMessagesController().getUser(WEBVIEW_BOT_ID);
        if (user == null) {
            if (searchUser) {
                getUserHelper().resolveUser(WEBVIEW_BOT, WEBVIEW_BOT_ID, arg -> invokeWebViewCustomMethod(method, data, false, callback));
            } else {
                callback.run(null, "USER_NOT_FOUND");
            }
            return;
        }
        TL_bots.invokeWebViewCustomMethod req = new TL_bots.invokeWebViewCustomMethod();
        req.bot = getMessagesController().getInputUser(user);
        req.custom_method = method;
        req.params = new TLRPC.TL_dataJSON();
        req.params.data = data;
        getConnectionsManager().sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (callback != null) {
                if (error != null) {
                    callback.run(null, error.text);
                } else if (res instanceof TLRPC.TL_dataJSON) {
                    callback.run(((TLRPC.TL_dataJSON) res).data, null);
                } else {
                    callback.run(null, ERROR_INVALID_RESPONSE);
                }
            }
        }));
    }

    public void setItem(String key, String value, Utilities.Callback2<String, String> callback) {
        Utilities.Callback2<String, String> once = once(callback);
        try {
            String data = buildSetItemPayload(key, value);
            if (utf8Length(data) > MAX_REQUEST_DATA_BYTES) {
                once.run(null, ERROR_DATA_TOO_LONG);
                return;
            }
            invokeWebViewCustomMethod("saveStorageValue", data, (res, error) -> {
                if (error != null) {
                    once.run(null, error);
                    return;
                }
                String validationError = validateWriteResponse(res);
                once.run(validationError == null ? res : null, validationError);
            });
        } catch (Exception exception) {
            once.run(null, ERROR_INVALID_RESPONSE);
        }
    }

    public void getItem(String key, Utilities.Callback2<String, String> callback) {
        getItems(new String[]{key}, (res, error) -> {
            if (error == null) {
                callback.run(res.get(key), null);
            } else {
                callback.run(null, error);
            }
        });
    }

    public void getItems(String[] keys, Utilities.Callback2<HashMap<String, String>, String> callback) {
        HashMap<String, String[]> map = new HashMap<>();
        map.put("keys", keys);
        invokeWebViewCustomMethod("getStorageValues", gson.toJson(map), (res, error) -> {
            if (error == null) {
                try {
                    HashMap<String, String> result = parseItemsResponse(res);
                    callback.run(result, null);
                } catch (IllegalArgumentException exception) {
                    callback.run(null, exception.getMessage());
                }
            } else {
                callback.run(null, error);
            }
        });
    }

    public void removeItem(String key, Utilities.Callback2<String, String> callback) {
        removeItems(new String[]{key}, callback);
    }

    public void removeItems(String[] keys, Utilities.Callback2<String, String> callback) {
        Utilities.Callback2<String, String> once = once(callback);
        HashMap<String, String[]> map = new HashMap<>();
        map.put("keys", keys);
        try {
            invokeWebViewCustomMethod("deleteStorageValues", gson.toJson(map), (res, error) -> {
                if (error != null) {
                    once.run(null, error);
                    return;
                }
                String validationError = validateWriteResponse(res);
                once.run(validationError == null ? res : null, validationError);
            });
        } catch (Exception exception) {
            once.run(null, ERROR_INVALID_RESPONSE);
        }
    }

    public void getKeys(Utilities.Callback2<String[], String> callback) {
        invokeWebViewCustomMethod("getStorageKeys", "{}", (res, error) -> {
            if (error == null) {
                try {
                    callback.run(parseKeysResponse(res), null);
                } catch (IllegalArgumentException exception) {
                    callback.run(null, exception.getMessage());
                }
            } else {
                callback.run(null, error);
            }
        });
    }

    static String buildSetItemPayload(String key, String value) {
        HashMap<String, String> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("value", value);
        return new Gson().toJson(map);
    }

    static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    static String validateWriteResponse(String data) {
        JsonElement root;
        try {
            root = parseResponse(data);
        } catch (IllegalArgumentException exception) {
            return exception.getMessage();
        }
        String businessError = getBusinessError(root);
        if (businessError != null) {
            return businessError;
        }
        if ((root.isJsonObject() && root.getAsJsonObject().size() == 0) || isTrue(root)) {
            return null;
        }
        return ERROR_INVALID_RESPONSE;
    }

    static HashMap<String, String> parseItemsResponse(String data) {
        JsonElement root = parseResponse(data);
        String businessError = getBusinessError(root);
        if (businessError != null) {
            throw new IllegalArgumentException(businessError);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException(ERROR_INVALID_RESPONSE);
        }
        try {
            HashMap<String, String> result = new Gson().fromJson(root, STRING_MAP_TYPE);
            if (result == null) {
                throw new IllegalArgumentException(ERROR_INVALID_RESPONSE);
            }
            return result;
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException(ERROR_INVALID_RESPONSE);
        }
    }

    static String[] parseKeysResponse(String data) {
        JsonElement root = parseResponse(data);
        String businessError = getBusinessError(root);
        if (businessError != null) {
            throw new IllegalArgumentException(businessError);
        }
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException(ERROR_INVALID_RESPONSE);
        }
        try {
            String[] result = new Gson().fromJson(root, String[].class);
            if (result == null) {
                throw new IllegalArgumentException(ERROR_INVALID_RESPONSE);
            }
            return result;
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException(ERROR_INVALID_RESPONSE);
        }
    }

    private static JsonElement parseResponse(String data) {
        if (data == null || data.trim().isEmpty()) {
            throw new IllegalArgumentException(ERROR_EMPTY_RESPONSE);
        }
        try {
            JsonElement root = JsonParser.parseString(data);
            if (root == null || root.isJsonNull()) {
                throw new IllegalArgumentException(ERROR_EMPTY_RESPONSE);
            }
            return root;
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException(ERROR_INVALID_RESPONSE);
        }
    }

    private static String getBusinessError(JsonElement root) {
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement error = object.get("error");
        if (error != null && !error.isJsonNull()) {
            String message = getMessage(error, object);
            return message == null ? ERROR_INVALID_RESPONSE : message;
        }
        return null;
    }

    private static String getMessage(JsonElement error, JsonObject object) {
        if (error != null && error.isJsonPrimitive()) {
            String value = error.getAsString();
            return value.isEmpty() ? null : value;
        }
        if (error != null && error.isJsonObject()) {
            JsonObject errorObject = error.getAsJsonObject();
            for (String key : new String[]{"message", "error", "description"}) {
                JsonElement value = errorObject.get(key);
                if (value != null && value.isJsonPrimitive() && !value.getAsString().isEmpty()) {
                    return value.getAsString();
                }
            }
        }
        JsonElement message = object.get("message");
        if (message != null && message.isJsonPrimitive() && !message.getAsString().isEmpty()) {
            return message.getAsString();
        }
        return null;
    }

    private static boolean isTrue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static Utilities.Callback2<String, String> once(Utilities.Callback2<String, String> callback) {
        AtomicBoolean completed = new AtomicBoolean();
        return (result, error) -> {
            if (completed.compareAndSet(false, true)) {
                callback.run(result, error);
            }
        };
    }
}
