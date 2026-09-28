import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.helpers.NooagramPinnedHider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PinnedSettingsProbe {
    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static Path root;

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]).resolve("TMessagesProj/src/main/java");
        check("active-account legacy data is quarantined without automatic assignment", () -> {
            login(0, 1001);
            prefs().edit().putString("hidden_pinned_0", "[-20, -10, -20, 0]").apply();
            NooagramPinnedHider.migrateLegacyAccounts();
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(0));
            equal(List.of(-20L, -10L), NooagramPinnedHider.getLegacyDialogs(0));
            require(!prefs().contains("hidden_pinned_0"), "legacy key remains");
            require(prefs().contains("legacy_hidden_pinned_0"), "quarantine key absent");
            require(!prefs().contains("hidden_pinned_user_1001"), "unowned list assigned to current user");
            int writes = prefs().writes;
            NooagramPinnedHider.migrateLegacyAccounts();
            equal(List.of(-20L, -10L), NooagramPinnedHider.getLegacyDialogs(0));
            equal(writes, prefs().writes);
        });
        check("inactive-slot data cannot reach a later login", () -> {
            prefs().edit().putString("hidden_pinned_2", "[-20]").apply();
            NooagramPinnedHider.migrateLegacyAccounts();
            login(2, 2002);
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(2));
            equal(List.of(-20L), NooagramPinnedHider.getLegacyDialogs(2));
            require(!prefs().contains("hidden_pinned_2"), "inactive legacy key remains");
        });
        check("existing user data wins over legacy data", () -> {
            login(0, 1001);
            prefs().edit().putString("hidden_pinned_0", "[-20]")
                    .putString("hidden_pinned_user_1001", "[-30]").apply();
            NooagramPinnedHider.migrateLegacyAccounts();
            equal(List.of(-30L), NooagramPinnedHider.getHiddenDialogs(0));
            equal(List.of(-20L), NooagramPinnedHider.getLegacyDialogs(0));
            equal("[-30]", prefs().getString("hidden_pinned_user_1001", null));
        });
        check("logout before migration cannot contaminate slot reuse", () -> {
            login(0, 1001);
            prefs().edit().putString("hidden_pinned_0", "[-20]").apply();
            NooagramPinnedHider.onAccountLogout(0);
            login(0, 2002);
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(0));
            login(1, 1001);
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(1));
            equal(List.of(-20L), NooagramPinnedHider.getLegacyDialogs(0));
            equal(List.of(), NooagramPinnedHider.getLegacyDialogs(1));
        });
        check("migration preserves an existing quarantine and survives cache restart", () -> {
            login(0, 1001);
            prefs().edit().putString("hidden_pinned_0", "[-20, -10]")
                    .putString("legacy_hidden_pinned_0", "[-30, -20]").apply();
            NooagramPinnedHider.migrateLegacyAccounts();
            NooagramPinnedHider.getLegacyDialogs(0).clear();
            NooagramPinnedHider.clear(0);
            clearHiderCache();
            NooagramPinnedHider.migrateLegacyAccounts();
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(0));
            equal(List.of(-30L, -20L, -10L), NooagramPinnedHider.getLegacyDialogs(0));
        });
        check("legacy restore previews titles and merges only after confirmation", () -> {
            login(0, 1001);
            login(1, 2002);
            UserConfig.selectedAccount = 1;
            prefs().edit().putString("legacy_hidden_pinned_0", "[-30, -20]")
                    .putString("hidden_pinned_user_1001", "[-20, -10]")
                    .putString("hidden_pinned_user_2002", "[-40]").apply();
            HiderActivityProbe activity = activity(0);
            int writes = prefs().writes;
            activity.showRestoreLegacyAlert();
            require(AlertDialog.last != null, "restore confirmation absent");
            require(AlertDialog.last.message.contains("ID -30") && AlertDialog.last.message.contains("ID -20"),
                    "legacy titles absent from confirmation preview");
            equal(writes, prefs().writes);
            equal(List.of(-20L, -10L), NooagramPinnedHider.getHiddenDialogs(0));
            equal(List.of(-30L, -20L), NooagramPinnedHider.getLegacyDialogs(0));
            if (AlertDialog.last.negative != null) AlertDialog.last.negative.onClick(null, 0);
            equal(writes, prefs().writes);
            equal(List.of(-30L, -20L), NooagramPinnedHider.getLegacyDialogs(0));
            activity.showRestoreLegacyAlert();
            require(AlertDialog.last.positive != null, "restore confirmation callback absent");
            AlertDialog.last.positive.onClick(null, 0);
            equal(List.of(-30L, -20L, -10L), NooagramPinnedHider.getHiddenDialogs(0));
            equal(List.of(-40L), NooagramPinnedHider.getHiddenDialogs(1));
            equal(List.of(), NooagramPinnedHider.getLegacyDialogs(0));
            require(!prefs().contains("legacy_hidden_pinned_0"), "restored quarantine remains");
            AndroidUtilities.flush();
            equal(List.of(-30L, -20L, -10L), activity.rows());
            equal(View.GONE, activity.actionBar.createMenu().getItem(998).visibility);
            NooagramPinnedHider.restoreLegacyDialogs(0);
            equal(List.of(-30L, -20L, -10L), NooagramPinnedHider.getHiddenDialogs(0));
            clearHiderCache();
            equal(List.of(-30L, -20L, -10L), NooagramPinnedHider.getHiddenDialogs(0));
        });
        check("legacy restore confirmation cannot cross an account replacement", () -> {
            login(0, 1001);
            prefs().edit().putString("legacy_hidden_pinned_0", "[-20]").apply();
            HiderActivityProbe activity = activity(0);
            activity.showRestoreLegacyAlert();
            require(AlertDialog.last != null && AlertDialog.last.positive != null, "restore confirmation absent");
            NooagramPinnedHider.onAccountLogout(0);
            login(0, 2002);
            int writes = prefs().writes;
            AlertDialog.last.positive.onClick(null, 0);
            equal(writes, prefs().writes);
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(0));
            equal(List.of(-20L), NooagramPinnedHider.getLegacyDialogs(0));
        });
        check("logged-out restore preserves quarantine for review", () -> {
            prefs().edit().putString("legacy_hidden_pinned_0", "[-20]").apply();
            int writes = prefs().writes;
            NooagramPinnedHider.restoreLegacyDialogs(0);
            equal(writes, prefs().writes);
            equal(List.of(-20L), NooagramPinnedHider.getLegacyDialogs(0));
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(0));
        });
        check("user data follows identity between slots", () -> {
            login(0, 1001);
            NooagramPinnedHider.setHidden(0, -20, true);
            NooagramPinnedHider.onAccountLogout(0);
            login(0, 2002);
            NooagramPinnedHider.setHidden(0, -30, true);
            login(2, 1001);
            equal(List.of(-20L), NooagramPinnedHider.getHiddenDialogs(2));
            equal(List.of(-30L), NooagramPinnedHider.getHiddenDialogs(0));
        });
        check("repeated setHidden is idempotent", () -> {
            login(0, 1001);
            NooagramPinnedHider.setHidden(0, -20, true);
            NooagramPinnedHider.setHidden(0, -20, true);
            AndroidUtilities.flush();
            equal(1, prefs().writes);
            equal(1, NotificationCenter.getInstance(0).events.size());
            NooagramPinnedHider.setHidden(0, -20, false);
            NooagramPinnedHider.setHidden(0, -20, false);
            AndroidUtilities.flush();
            equal(2, prefs().writes);
            equal(2, NotificationCenter.getInstance(0).events.size());
        });
        check("clear and returned-list mutation preserve state", () -> {
            login(0, 1001);
            NooagramPinnedHider.setHidden(0, -20, true);
            NooagramPinnedHider.getHiddenDialogs(0).clear();
            require(NooagramPinnedHider.isHidden(0, -20), "caller mutated cache");
            NooagramPinnedHider.clear(0);
            NooagramPinnedHider.clear(0);
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(0));
        });
        check("bulk hide and restore deduplicate selected IDs", () -> {
            login(0, 1001);
            ArrayList<Long> ids = new ArrayList<>(List.of(-10L, -20L, -20L));
            NooagramPinnedHider.toggleSelected(0, ids);
            equal(List.of(-20L, -10L), NooagramPinnedHider.getHiddenDialogs(0));
            NooagramPinnedHider.toggleSelected(0, ids);
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(0));
        });
        check("logged-out writes and zero IDs are ignored", () -> {
            NooagramPinnedHider.setHidden(0, -20, true);
            NooagramPinnedHider.toggleSelected(0, new ArrayList<>(List.of(-20L)));
            NooagramPinnedHider.clear(0);
            login(0, 1001);
            NooagramPinnedHider.setHidden(0, 0, true);
            equal(0, prefs().writes);
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(0));
        });
        check("queued old-account events are rejected after slot reuse", () -> {
            login(0, 1001);
            NooagramPinnedHider.setHidden(0, -20, true);
            NooagramPinnedHider.onAccountLogout(0);
            login(0, 2002);
            AndroidUtilities.flush();
            equal(0, NotificationCenter.getInstance(0).events.size());
        });
        check("events refresh only their account and unregister on destroy", () -> {
            login(0, 1001);
            login(1, 2002);
            HiderActivityProbe first = activity(0);
            HiderActivityProbe second = activity(1);
            NooagramPinnedHider.setHidden(0, -20, true);
            AndroidUtilities.flush();
            equal(List.of(-20L), first.rows());
            equal(List.of(), second.rows());
            equal(1, first.listAdapter.refreshes);
            equal(0, second.listAdapter.refreshes);
            first.onFragmentDestroy();
            NooagramPinnedHider.clear(0);
            AndroidUtilities.flush();
            equal(1, first.listAdapter.refreshes);
            equal(0, NotificationCenter.getInstance(0).observers.size());
        });
        check("stale bound row cannot restore its new position neighbor", () -> {
            login(0, 1001);
            login(1, 2002);
            UserConfig.selectedAccount = 1;
            NooagramPinnedHider.toggleSelected(0, new ArrayList<>(List.of(-30L, -20L, -10L)));
            AndroidUtilities.flush();
            HiderActivityProbe activity = activity(0);
            View row = new View();
            row.setTag(-30L);
            activity.onItemClick(row, 1, 0, 0);
            activity.onItemClick(row, 1, 0, 0);
            equal(List.of(-20L, -10L), NooagramPinnedHider.getHiddenDialogs(0));
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(1));
            row.setTag(-20L);
            activity.onItemClick(row, 2, 0, 0);
            equal(List.of(-10L), NooagramPinnedHider.getHiddenDialogs(0));
        });
        check("import saves before its one callback and refresh", () -> {
            SettingsImportProbe screen = settings(ConfigItem.configTypeBool, false);
            ConfigItem config = screen.rowConfigMapReverse.get(0);
            screen.group.callBackSettingsChanged = (key, value) -> {
                equal("setting", key);
                equal(true, value);
                equal(true, config.value);
                equal(true, prefs().values.get("setting"));
                screen.events.add("callback");
            };
            screen.importToRow("setting", "true", () -> {});
            equal(false, config.value);
            AlertDialog.last.positive.onClick(null, 0);
            equal(List.of("callback", "refresh", "scroll:setting"), screen.events);
        });
        check("positional imports send config key to callback", () -> {
            SettingsImportProbe screen = settings(ConfigItem.configTypeInt, 2);
            screen.group.callBackSettingsChanged = (key, value) -> {
                equal("setting", key);
                equal(7, value);
                screen.events.add("callback");
            };
            screen.importToRow("0", "7", () -> {});
            AlertDialog.last.positive.onClick(null, 0);
            equal(List.of("callback", "refresh", "scroll:0"), screen.events);
        });
        check("cancelled or invalid imports do not call settings callbacks", () -> {
            SettingsImportProbe screen = settings(ConfigItem.configTypeInt, 2);
            screen.group.callBackSettingsChanged = (key, value) -> { throw new AssertionError("unexpected callback"); };
            screen.importToRow("setting", "bad number", () -> {});
            require(AlertDialog.last == null, "invalid import opened confirmation");
            screen.importToRow("setting", "7", () -> {});
            AlertDialog.last.negative.onClick(null, 0);
            equal(2, screen.rowConfigMapReverse.get(0).value);
            equal(0, prefs().writes);
        });
        check("import preserves reserved characters in decoded values", () -> {
            SettingsImportProbe screen = settings(ConfigItem.configTypeString, "old");
            String value = "gone&local=1+#100% /?\n\u6807\u8bb0";
            screen.group.callBackSettingsChanged = (key, incoming) -> equal(value, incoming);
            screen.importToRow("setting", value, () -> {});
            AlertDialog.last.positive.onClick(null, 0);
            equal(value, prefs().values.get("setting"));
        });
        check("source integration ordering and view binding", () -> {
            String app = read("org/telegram/messenger/ApplicationLoader.java");
            before(app, "UserConfig.getInstance(a).loadConfig()", "NooagramPinnedHider.migrateLegacyAccounts()");
            String controller = read("org/telegram/messenger/MessagesController.java");
            controller = controller.substring(controller.indexOf("public void performLogout(int type)"));
            before(controller, "NooagramPinnedHider.onAccountLogout(currentAccount)", "getUserConfig().clearConfig()");
            String activity = read("tw/nekomimi/nekogram/settings/NooagramPinnedHiderActivity.java");
            require(activity.contains("cell.setTag(dialogId)"), "bound dialog tag absent");
            require(!activity.contains("private ListAdapter listAdapter"), "adapter shadow returned");
            require(activity.contains("else if (id == 998)") && activity.contains("showRestoreLegacyAlert();"),
                    "legacy restore menu action absent");
            String chat = read("org/telegram/ui/ChatActivity.java");
            require(chat.contains(".add(NotificationCenter.nooagramPinnedHiderChanged)"), "chat observer absent");
            int handler = chat.indexOf("id == NotificationCenter.nooagramPinnedHiderChanged");
            require(handler >= 0 && chat.substring(handler, Math.min(handler + 200, chat.length()))
                    .contains("updatePinnedMessageView(false)"), "chat does not refresh on event");
            require(read("org/telegram/messenger/NotificationCenter.java").contains("int nooagramPinnedHiderChanged = totalEvents++"),
                    "account event missing");
        });
        check("R36 source uses matching URI encoding and decoding APIs", () -> {
            String base = read("tw/nekomimi/nekogram/settings/BaseNekoXSettingsActivity.java");
            require(base.contains(".appendQueryParameter(\"r\", key)"), "row key not encoded");
            require(base.contains(".appendQueryParameter(\"v\", value)"), "value not encoded");
            require(!base.contains("&v=%s"), "raw backup URL format remains");
            require(read("tw/nekomimi/nekogram/helpers/SettingsHelper.java").contains("uri.getQueryParameter(\"v\")"),
                    "value query not decoded");
        });
        check("global filter rebuild reaches all activated accounts", () -> {
            login(0, 1001);
            login(2, 2002);
            UserConfig.selectedAccount = 1;
            int clears = FilterNotificationProbe.AyuFilterCache.clears;
            FilterNotificationProbe.rebuildCache();
            equal(clears + 1, FilterNotificationProbe.AyuFilterCache.clears);
            AndroidUtilities.flush();
            equal(List.of(NotificationCenter.regexFiltersUpdated), NotificationCenter.getInstance(0).events);
            equal(List.of(), NotificationCenter.getInstance(1).events);
            equal(List.of(NotificationCenter.regexFiltersUpdated), NotificationCenter.getInstance(2).events);
            equal(List.of(), NotificationCenter.getInstance(3).events);
        });
        check("global filter option changes skip accounts logged out before delivery", () -> {
            login(0, 1001);
            login(2, 2002);
            FilterNotificationProbe.invalidateFilteredCache();
            login(0, 0);
            AndroidUtilities.flush();
            equal(List.of(), NotificationCenter.getInstance(0).events);
            equal(List.of(NotificationCenter.regexFiltersUpdated), NotificationCenter.getInstance(2).events);
        });
        check("R32 pre-upgrade stale slot data must not be assigned to a replacement login", () -> {
            // On the old app A hid a dialog, logged out, and B reused the slot.
            // The first fixed-app startup only has B's ID and the unowned old key.
            prefs().edit().putString("hidden_pinned_0", "[-20]").apply();
            login(0, 2002);
            NooagramPinnedHider.migrateLegacyAccounts();
            require(!NooagramPinnedHider.isHidden(0, -20), "B inherited A's stale hidden_pinned_0 via migration");
            require(!prefs().contains("hidden_pinned_user_2002"), "B received an implicit user list");
            equal(List.of(-20L), NooagramPinnedHider.getLegacyDialogs(0));
            NooagramPinnedHider.migrateLegacyAccounts();
            equal(List.of(), NooagramPinnedHider.getHiddenDialogs(0));
            equal(List.of(-20L), NooagramPinnedHider.getLegacyDialogs(0));
        });
        System.out.println("RESULT: " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
    }

    private static HiderActivityProbe activity(int account) {
        HiderActivityProbe activity = new HiderActivityProbe();
        activity.currentAccount = account;
        activity.onFragmentCreate();
        activity.updateRows();
        return activity;
    }

    private static SettingsImportProbe settings(int type, Object original) {
        SettingsImportProbe screen = new SettingsImportProbe();
        screen.rowMap.put("setting", 0);
        screen.rowConfigMapReverse.put(0, new ConfigItem("setting", type, original));
        return screen;
    }

    private static void reset() throws Exception {
        ApplicationLoader.applicationContext = new Context();
        AndroidUtilities.queue.clear();
        NotificationCenter.instances.clear();
        AlertDialog.last = null;
        UserConfig.selectedAccount = 0;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) login(account, 0);
        clearHiderCache();
    }

    private static void clearHiderCache() throws Exception {
        var field = NooagramPinnedHider.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).clear();
    }

    private static void login(int account, long userId) { UserConfig.getInstance(account).userId = userId; }
    private static SharedPreferences prefs() { return ApplicationLoader.applicationContext.prefs; }
    private static String read(String relative) throws Exception { return Files.readString(root.resolve(relative)); }
    private static void equal(Object expected, Object actual) { require(Objects.equals(expected, actual), "expected=" + expected + " actual=" + actual); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void before(String source, String first, String second) {
        require(source.indexOf(first) >= 0 && source.indexOf(second) > source.indexOf(first), "missing or reversed order: " + first);
    }
    private interface Test { void run() throws Exception; }
    private static void check(String name, Test test) {
        try {
            reset();
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            failures.add(name + ": " + error.getMessage());
            System.out.println("FAIL " + name + ": " + error.getMessage());
        }
    }
}
