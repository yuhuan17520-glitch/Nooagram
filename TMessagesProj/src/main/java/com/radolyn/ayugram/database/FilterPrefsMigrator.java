
package com.radolyn.ayugram.database;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.reflect.TypeToken;

import com.radolyn.ayugram.database.dao.RegexFilterDao;
import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

import xyz.nextalone.nagram.NaConfig;

public final class FilterPrefsMigrator {

    private static final String MIGRATION_PREFS = "ayu_filter_migration";
    private static final String KEY_DONE = "jsonToRoomDone";
    private static final int MIGRATION_VERSION = 3;
    private static final String KEY_VERSION = "version";

    private FilterPrefsMigrator() {
    }

    public static synchronized void runIfNeeded() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE);

        int savedVersion = prefs.getInt(KEY_VERSION, 0);
        if (savedVersion >= MIGRATION_VERSION && prefs.getBoolean(KEY_DONE, false)) {
            return;
        }
        try {
            try {
                NaConfig.INSTANCE.init();
            } catch (Exception e) {
                FileLog.e("FilterPrefsMigrator: NaConfig.init failed", e);
                return;
            }

            RegexFilterDao dao = AyuData.getRegexFilterDao();
            if (dao == null) {
                return;
            }

            String sharedJson = NaConfig.INSTANCE.getRegexFiltersData().String();
            String chatJson = NaConfig.INSTANCE.getRegexChatFiltersData().String();
            String exclusionsJson = NaConfig.INSTANCE.getRegexFiltersExcludedEntriesData().String();

            RegexFilterDao.Snapshot legacy = parseLegacy(sharedJson, chatJson, exclusionsJson);
            if (!dao.migrateLegacy(legacy.filters, legacy.exclusions)) {
                return;
            }
            if (!prefs.edit().putBoolean(KEY_DONE, true).putInt(KEY_VERSION, MIGRATION_VERSION).commit()) {
                FileLog.e("FilterPrefsMigrator: completion marker could not be saved; migration will retry");
                return;
            }
            FileLog.d("FilterPrefsMigrator: legacy JSON filters imported into Room");
        } catch (Exception e) {
            FileLog.e("FilterPrefsMigrator: migration failed", e);
        }
    }

    static RegexFilterDao.Snapshot parseLegacy(String sharedJson, String chatJson, String exclusionsJson) {
        Gson gson = new Gson();
        ArrayList<RegexFilter> filters = new ArrayList<>();
        ArrayList<RegexFilterGlobalExclusion> exclusionRows = new ArrayList<>();
        ArrayList<LegacyFilterModel> shared = readList(gson, sharedJson, new TypeToken<ArrayList<LegacyFilterModel>>(){}.getType());
        if (shared != null) {
            for (LegacyFilterModel model : shared) {
                if (model != null && model.regex != null) filters.add(legacyRow(model, null));
            }
        }
        ArrayList<LegacyChatFilterEntry> chats = readList(gson, chatJson, new TypeToken<ArrayList<LegacyChatFilterEntry>>(){}.getType());
        if (chats != null) {
            for (LegacyChatFilterEntry entry : chats) {
                if (entry == null || entry.filters == null) continue;
                for (LegacyFilterModel model : entry.filters) {
                    if (model != null && model.regex != null) filters.add(legacyRow(model, entry.dialogId));
                }
            }
        }
        ArrayList<LegacyExcludedFilterEntry> exclusions = readList(gson, exclusionsJson, new TypeToken<ArrayList<LegacyExcludedFilterEntry>>(){}.getType());
        if (exclusions != null) {
            for (LegacyExcludedFilterEntry entry : exclusions) {
                if (entry == null || entry.dialogId == 0L || isEmpty(entry.filterId)) continue;
                RegexFilterGlobalExclusion row = new RegexFilterGlobalExclusion();
                row.dialogId = entry.dialogId;
                row.filterId = entry.filterId;
                exclusionRows.add(row);
            }
        }
        return new RegexFilterDao.Snapshot(filters, exclusionRows);
    }

    private static RegexFilter legacyRow(LegacyFilterModel model, Long dialogId) {
        RegexFilter row = new RegexFilter();
        row.id = legacyId(model, dialogId);
        row.text = model.regex;
        row.dialogId = dialogId;
        row.enabled = legacyEnabled(model, dialogId == null ? 0L : dialogId);
        row.caseInsensitive = model.caseInsensitive;
        row.reversed = model.reversed;
        return row;
    }

    private static <T> ArrayList<T> readList(Gson gson, String json, Type type) {
        if (isEmpty(json)) {
            return null;
        }
        // Parse all inputs before starting the transaction. Invalid JSON must not
        // mark a partial migration complete.
        return gson.fromJson(json, type);
    }

    private static String legacyId(LegacyFilterModel model, Long dialogId) {
        if (!isEmpty(model.id)) return model.id;
        String key = "legacy-filter:" + dialogId + ":" + model.caseInsensitive + ":"
                + model.reversed + ":" + model.regex;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean legacyEnabled(LegacyFilterModel model, long dialogId) {
        if (model.enabledGroups == null && model.disabledGroups == null) return model.enabled;
        boolean defaultEnabled = model.enabledGroups != null && model.enabledGroups.contains(0L);
        return defaultEnabled ? model.disabledGroups == null || !model.disabledGroups.contains(dialogId)
                : model.enabledGroups != null && model.enabledGroups.contains(dialogId);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }


    private static class LegacyFilterModel {
        @Expose
        public String id;
        @Expose
        public String regex;
        @Expose
        public boolean caseInsensitive;
        @Expose
        public boolean enabled = true;
        @Expose
        public boolean reversed;
        public ArrayList<Long> enabledGroups;
        public ArrayList<Long> disabledGroups;
    }

    private static class LegacyChatFilterEntry {
        @Expose
        public long dialogId;
        @Expose
        public ArrayList<LegacyFilterModel> filters;
    }

    private static class LegacyExcludedFilterEntry {
        @Expose
        public long dialogId;
        @Expose
        public String filterId;
    }
}
