const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");

const repo = path.resolve(__dirname, "../../..");
const settingsPath = path.join(
  repo,
  "TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/NekoSettingsActivity.java",
);
const cloudPath = path.join(
  repo,
  "TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/CloudSettingsHelper.java",
);

function extractBlock(source, signature) {
  const start = source.indexOf(signature);
  if (start < 0) throw new Error(`block not found: ${signature}`);
  const open = source.indexOf("{", start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === "{") depth++;
    if (source[i] === "}" && --depth === 0) {
      return source.slice(start, i + 1);
    }
  }
  throw new Error(`unclosed block: ${signature}`);
}

function publicBlock(block, signature, replacement) {
  return block.replace(signature, replacement);
}

const settingsSource = fs.readFileSync(settingsPath, "utf8");
const cloudSource = fs.readFileSync(cloudPath, "utf8");

const backupBlock = extractBlock(
  settingsSource,
  "public static String backupSettingsJson(int indentSpaces) throws JSONException",
);
const spToJsonBlock = extractBlock(
  settingsSource,
  "private static void spToJSON(String sp, JSONObject object, Function<String, Boolean> filter) throws JSONException",
);
const importBlock = extractBlock(
  settingsSource,
  "public static void importSettings(JsonObject configJson) throws JSONException",
);
const preferenceUpdateBlock = extractBlock(
  settingsSource,
  "private static final class PreferenceUpdate",
);
const syncBlock = extractBlock(
  cloudSource,
  "void syncToCloud(CloudStorageHelper storageHelper, int account, String setting, Utilities.Callback2<Boolean, String> callback)",
);
const uploadChunksBlock = extractBlock(
  cloudSource,
  "private static void uploadChunks(",
);
const setItemOnceBlock = extractBlock(
  cloudSource,
  "private static void setItemOnce(",
);

const java = String.raw`
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

class JSONException extends Exception {
    JSONException(String message) { super(message); }
}

class JsonElement {
    boolean isJsonObject() { return false; }
    boolean isJsonPrimitive() { return false; }
    JsonObject getAsJsonObject() { throw new ClassCastException(); }
    JsonPrimitive getAsJsonPrimitive() { throw new ClassCastException(); }
}

class JsonObject extends JsonElement implements Iterable<Map.Entry<String, JsonElement>> {
    final LinkedHashMap<String, JsonElement> values = new LinkedHashMap<>();
    JsonObject put(String key, JsonElement value) { values.put(key, value); return this; }
    Set<Map.Entry<String, JsonElement>> entrySet() { return values.entrySet(); }
    public Iterator<Map.Entry<String, JsonElement>> iterator() { return values.entrySet().iterator(); }
    boolean isJsonObject() { return true; }
    JsonObject getAsJsonObject() { return this; }
}

class JsonPrimitive extends JsonElement {
    final Object value;
    JsonPrimitive(Object value) { this.value = value; }
    boolean isJsonPrimitive() { return true; }
    JsonPrimitive getAsJsonPrimitive() { return this; }
    boolean isBoolean() { return value instanceof Boolean; }
    boolean isNumber() { return value instanceof Number; }
    boolean getAsBoolean() { return (Boolean) value; }
    String getAsString() { return String.valueOf(value); }
    BigDecimal getAsBigDecimal() { return new BigDecimal(value.toString()); }
}

class JSONObject {
    final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    void put(String key, Object value) throws JSONException { values.put(key, value); }
    boolean has(String key) { return values.containsKey(key); }
    JSONObject getJSONObject(String key) { return (JSONObject) values.get(key); }
    Object get(String key) { return values.get(key); }
    public String toString() { return values.toString(); }
    public String toString(int ignored) { return toString(); }
}

class Activity {
    static final int MODE_PRIVATE = 0;
}

class StringsKt {
    static String substringBeforeLast(String value, String delimiter, String missing) {
        int at = value.lastIndexOf(delimiter);
        return at < 0 ? missing : value.substring(0, at);
    }
}

class NkmrConfig {
    static int compactCalls;
    static void compact() { compactCalls++; }
}

class SharedPreferences {
    static final Set<String> failCommits = new HashSet<>();
    final Map<String, Object> values;
    int commits;

    SharedPreferences(Map<String, Object> values) { this("", values); }
    Map<String, Object> getAll() { return new LinkedHashMap<>(values); }
    Editor edit() { return new Editor(this); }

    static class Editor {
        final SharedPreferences target;
        final Map<String, Object> staged = new LinkedHashMap<>();
        Editor(SharedPreferences target) { this.target = target; }
        Editor putBoolean(String key, boolean value) { staged.put(key, value); return this; }
        Editor putInt(String key, int value) { staged.put(key, value); return this; }
        Editor putLong(String key, long value) { staged.put(key, value); return this; }
        Editor putFloat(String key, float value) { staged.put(key, value); return this; }
        Editor putString(String key, String value) { staged.put(key, value); return this; }
        boolean commit() {
            target.commits++;
            if (failCommits.contains(nameOf(target))) return false;
            target.values.putAll(staged);
            return true;
        }
        void apply() { commit(); }
    }

    final String name;
    SharedPreferences(String name, Map<String, Object> values) {
        this.name = name;
        this.values = values;
    }
    static String nameOf(SharedPreferences preferences) { return preferences.name; }
}

class ApplicationContext {
    final Map<String, SharedPreferences> stores = new LinkedHashMap<>();
    SharedPreferences getSharedPreferences(String name, int mode) {
        return stores.computeIfAbsent(name, ignored -> new SharedPreferences(name, new LinkedHashMap<>()));
    }
}

class ApplicationLoader {
    static final ApplicationContext applicationContext = new ApplicationContext();
}

class NekoSettingsActivity {
    static boolean shouldTransferPreference(String preferencesName, String key) {
        return !"nkmrcfg".equals(preferencesName)
            || (!"ExtendedFeatureUnlockedToken".equals(key) && !"ShowHiddenFeature".equals(key));
    }

${publicBlock(backupBlock, "public static String backupSettingsJson", "public static String backupSettingsJson")}

${publicBlock(spToJsonBlock, "private static void spToJSON", "static void spToJSON")}

${importBlock}

${preferenceUpdateBlock}
}

class SparseArray<T> extends HashMap<Integer, T> {
    T get(int key, T defaultValue) { return getOrDefault(key, defaultValue); }
}

class UserConfig {
    static int selectedAccount;
}

class Utilities {
    interface Callback2<A, B> { void run(A first, B second); }
}

class CloudStorageHelper {
    final int account;
    final LinkedHashMap<String, Utilities.Callback2<String, String>> pending = new LinkedHashMap<>();
    final Set<String> requested = new LinkedHashSet<>();
    String throwOn;

    CloudStorageHelper(int account) { this.account = account; }

    void setItem(String key, String value, Utilities.Callback2<String, String> callback) {
        requested.add(key);
        if (key.equals(throwOn)) throw new IllegalStateException("simulated synchronous failure");
        if (pending.put(key, callback) != null) throw new AssertionError("duplicate request " + key);
    }

    Utilities.Callback2<String, String> take(String key) {
        Utilities.Callback2<String, String> callback = pending.remove(key);
        if (callback == null) throw new AssertionError("missing request " + key);
        return callback;
    }
}

class CloudSettingsHelper {
    final SparseArray<Long> cloudSyncedDate = new SparseArray<>();
    final SharedPreferences preferences =
        ApplicationLoader.applicationContext.getSharedPreferences("nekocloud", Activity.MODE_PRIVATE);
    long localSyncedDate = -1;

${syncBlock}

${uploadChunksBlock}

${setItemOnceBlock}
}

class CallbackResult implements Utilities.Callback2<Boolean, String> {
    int calls;
    boolean success;
    String error;
    public void run(Boolean first, String second) {
        calls++;
        success = first;
        error = second;
    }
}

class Fixture {
    final CloudSettingsHelper helper = new CloudSettingsHelper();
    final CloudStorageHelper storage = new CloudStorageHelper(1);
    final CallbackResult result = new CallbackResult();

    Fixture() { helper.preferences.values.clear(); }
    void succeed(String key) { storage.take(key).run("ok", null); }
    void fail(String key, String error) { storage.take(key).run(null, error); }
}

public class SettingsSyncFixProbe {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static JsonObject value(Object value) { return new JsonObject().put("v", new JsonPrimitive(value)); }

    static void settings() throws Exception {
        ApplicationLoader.applicationContext.getSharedPreferences("ayughostconfig", 0)
            .edit().putBoolean("useGlobalConfig", false).putLong("delay", 42L).commit();
        ApplicationLoader.applicationContext.getSharedPreferences("ayughostexclusionsconfig", 0)
            .edit().putInt("ghostModeReadException_9", 2).commit();
        String backup = NekoSettingsActivity.backupSettingsJson(0);
        check(backup.contains("ayughostconfig"), "Ghost config missing from backup");
        check(backup.contains("ayughostexclusionsconfig"), "Ghost exclusions missing from backup");
        check(backup.contains("delay_long=42"), "Ghost long value lost its type suffix");

        SharedPreferences main = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", 0);
        main.values.put("existing", "keep");
        JsonObject malformed = new JsonObject();
        malformed.put("mainconfig", value(true));
        JsonObject badNkmr = new JsonObject();
        badNkmr.put("bad", new JsonObject());
        malformed.put("nkmrcfg", badNkmr);
        boolean rejected = false;
        try {
            NekoSettingsActivity.importSettings(malformed);
        } catch (JSONException expected) {
            rejected = true;
        }
        check(rejected, "Malformed later preference set was accepted");
        check("keep".equals(main.values.get("existing")), "Existing value was disturbed");
        check(!main.values.containsKey("newValue"), "Earlier preference set was partially committed");

        JsonObject valid = new JsonObject();
        JsonObject typed = new JsonObject();
        typed.put("enabled", new JsonPrimitive(true));
        typed.put("count", new JsonPrimitive(7));
        typed.put("large_long", new JsonPrimitive(Long.MAX_VALUE));
        typed.put("ratio_float", new JsonPrimitive(0.25f));
        typed.put("label", new JsonPrimitive("value"));
        valid.put("mainconfig", typed);
        NekoSettingsActivity.importSettings(valid);
        check(Boolean.TRUE.equals(main.values.get("enabled")), "Boolean import failed");
        check(Integer.valueOf(7).equals(main.values.get("count")), "Int import failed");
        check(Long.valueOf(Long.MAX_VALUE).equals(main.values.get("large")), "Long import failed");
        check(Float.valueOf(0.25f).equals(main.values.get("ratio")), "Float import failed");
        check("value".equals(main.values.get("label")), "String import failed");

        SharedPreferences beforeInvalid = ApplicationLoader.applicationContext.getSharedPreferences("newstore", 0);
        JsonObject invalidNumber = new JsonObject();
        JsonObject first = new JsonObject();
        first.put("newValue", new JsonPrimitive(true));
        invalidNumber.put("mainconfig", first);
        JsonObject overflow = new JsonObject();
        overflow.put("bad", new JsonPrimitive(2147483648L));
        invalidNumber.put("nkmrcfg", overflow);
        rejected = false;
        try {
            NekoSettingsActivity.importSettings(invalidNumber);
        } catch (JSONException expected) {
            rejected = true;
        }
        check(rejected, "Out-of-range integer was accepted");
        check(!main.values.containsKey("newValue"), "Numeric validation happened after a commit");
        check(beforeInvalid.commits == 0, "Unreferenced preference store was touched");
    }

    static void cloudSequencing() {
        Fixture fixture = new Fixture();
        UserConfig.selectedAccount = 7;
        fixture.helper.syncToCloud(fixture.storage, 1, "x".repeat(8001), fixture.result);
        check(fixture.storage.pending.keySet().equals(Set.of("neko_settings_0")),
            "Only the first chunk should be pending");
        fixture.succeed("neko_settings_0");
        fixture.succeed("neko_settings_1");
        fixture.succeed("neko_settings_2");
        check(fixture.storage.pending.keySet().equals(Set.of("neko_settings")),
            "Metadata started before all chunks succeeded");
        check(fixture.result.calls == 0, "Success reported before metadata");
        UserConfig.selectedAccount = 0;
        Utilities.Callback2<String, String> metadata = fixture.storage.take("neko_settings");
        metadata.run("ok", null);
        check(fixture.storage.pending.keySet().equals(Set.of("neko_settings_updated_at")),
            "Updated timestamp should follow metadata");
        check(fixture.result.calls == 0, "Success reported before timestamp");
        metadata.run("ok", null);
        check(fixture.storage.pending.keySet().equals(Set.of("neko_settings_updated_at")),
            "Duplicate metadata callback advanced the upload twice");
        fixture.succeed("neko_settings_updated_at");
        check(fixture.result.calls == 1 && fixture.result.success, "Successful sync did not finish once");
        check(fixture.helper.cloudSyncedDate.get(1, 0L) > 0, "Captured account was not updated");
        check(!fixture.helper.cloudSyncedDate.containsKey(0), "Selected account was used during callback");
        check(fixture.helper.preferences.values.get("updated_at") instanceof Long,
            "Local success timestamp was not committed");
    }

    static void chunkFailure() {
        Fixture fixture = new Fixture();
        fixture.helper.syncToCloud(fixture.storage, 0, "x", fixture.result);
        Utilities.Callback2<String, String> callback = fixture.storage.take("neko_settings_0");
        callback.run(null, "chunk failed");
        callback.run("late", null);
        check(fixture.result.calls == 1 && !fixture.result.success, "Chunk failure did not finish once");
        check("chunk failed".equals(fixture.result.error), "Chunk error was not propagated");
        check(!fixture.storage.requested.contains("neko_settings"), "Metadata started after chunk failure");
    }

    static void zeroChunks() {
        Fixture fixture = new Fixture();
        fixture.helper.syncToCloud(fixture.storage, 0, "", fixture.result);
        check(fixture.storage.pending.keySet().equals(Set.of("neko_settings")),
            "Zero-chunk sync stalled before metadata");
        fixture.succeed("neko_settings");
        fixture.succeed("neko_settings_updated_at");
        check(fixture.result.calls == 1 && fixture.result.success, "Zero-chunk sync did not finish once");
    }

    static void timestampFailure() {
        Fixture fixture = new Fixture();
        fixture.helper.syncToCloud(fixture.storage, 0, "x", fixture.result);
        fixture.succeed("neko_settings_0");
        fixture.succeed("neko_settings");
        Utilities.Callback2<String, String> callback = fixture.storage.take("neko_settings_updated_at");
        callback.run(null, "timestamp failed");
        callback.run("late", null);
        check(fixture.result.calls == 1 && !fixture.result.success,
            "Timestamp failure did not finish once");
        check(!fixture.helper.preferences.values.containsKey("updated_at"),
            "Local success state was written before timestamp success");
    }

    static void synchronousFailure() {
        Fixture fixture = new Fixture();
        fixture.storage.throwOn = "neko_settings_0";
        fixture.helper.syncToCloud(fixture.storage, 0, "x", fixture.result);
        check(fixture.result.calls == 1 && !fixture.result.success,
            "Synchronous cloud exception did not finish once");
        check(fixture.result.error.contains("simulated"), "Synchronous error was not propagated");
    }

    public static void main(String[] args) throws Exception {
        settings();
        System.out.println("SETTINGS_REGRESSIONS=PASS");
        cloudSequencing();
        chunkFailure();
        zeroChunks();
        timestampFailure();
        synchronousFailure();
        System.out.println("CLOUD_SYNC_REGRESSIONS=PASS");
    }
}
`;
const probeSource = java.replace(/\bJsonObject\b/g, "JsonObj");

const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "nooagram-settings-sync-"));
const sourcePath = path.join(scratch, "SettingsSyncFixProbe.java");
fs.writeFileSync(sourcePath, probeSource);
const compile = spawnSync("javac", ["-encoding", "UTF-8", sourcePath], {
  cwd: scratch,
  encoding: "utf8",
});
if (compile.status !== 0) {
  process.stderr.write(compile.stdout || "");
  process.stderr.write(compile.stderr || "");
  process.exit(compile.status || 1);
}
const run = spawnSync("java", ["-cp", scratch, "SettingsSyncFixProbe"], {
  cwd: scratch,
  encoding: "utf8",
});
process.stdout.write(run.stdout || "");
process.stderr.write(run.stderr || "");
if (run.status !== 0) {
  process.exit(run.status || 1);
}
console.log(`PROBE_FIXTURES=${scratch}`);
