
package com.radolyn.ayugram.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Dao
public interface RegexFilterDao {


    @Query("SELECT * FROM RegexFilter ORDER BY rowid DESC")
    List<RegexFilter> getAll();

    @Query("SELECT * FROM RegexFilter WHERE dialogId IS NULL ORDER BY rowid DESC")
    List<RegexFilter> getShared();

    @Query("SELECT * FROM RegexFilter WHERE dialogId = :dialogId ORDER BY rowid DESC")
    List<RegexFilter> getByDialogId(long dialogId);

    @Query("SELECT * FROM RegexFilter WHERE id = :id LIMIT 1")
    RegexFilter getById(String id);

    @Query("SELECT COUNT(*) FROM RegexFilter")
    int getCount();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(RegexFilter filter);

    @Query("DELETE FROM RegexFilter WHERE id = :id")
    void delete(String id);

    @Query("DELETE FROM RegexFilter")
    void deleteAllFilters();

    @Query("DELETE FROM RegexFilter WHERE dialogId IS NULL")
    void deleteAllShared();

    @Query("DELETE FROM RegexFilter WHERE dialogId IS NOT NULL")
    void deleteAllChatFilters();

    @Query("DELETE FROM RegexFilter WHERE dialogId = :dialogId")
    void deleteByDialogId(long dialogId);


    @Query("SELECT * FROM RegexFilterGlobalExclusion")
    List<RegexFilterGlobalExclusion> getAllExclusions();

    @Query("SELECT * FROM RegexFilterGlobalExclusion WHERE dialogId = :dialogId")
    List<RegexFilterGlobalExclusion> getExclusionsByDialogId(long dialogId);

    @Query("SELECT EXISTS(SELECT 1 FROM RegexFilterGlobalExclusion WHERE dialogId = :dialogId AND filterId = :filterId)")
    boolean isExcluded(long dialogId, String filterId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertExclusion(RegexFilterGlobalExclusion exclusion);

    @Query("DELETE FROM RegexFilterGlobalExclusion WHERE dialogId = :dialogId AND filterId = :filterId")
    void deleteExclusion(long dialogId, String filterId);

    @Query("DELETE FROM RegexFilterGlobalExclusion WHERE filterId = :filterId")
    void deleteExclusionsByFilterId(String filterId);

    @Query("DELETE FROM RegexFilterGlobalExclusion WHERE dialogId = :dialogId")
    void deleteExclusionsByDialogId(long dialogId);

    @Query("DELETE FROM RegexFilterGlobalExclusion")
    void deleteAllExclusions();

    @Transaction
    default Snapshot getSnapshot() {
        return new Snapshot(getAll(), getAllExclusions());
    }

    @Transaction
    default boolean replaceShared(List<RegexFilter> filters) {
        deleteAllShared();
        for (int i = filters.size() - 1; i >= 0; i--) {
            insert(filters.get(i));
        }
        return true;
    }

    @Transaction
    default boolean replaceChats(List<RegexFilter> filters) {
        deleteAllChatFilters();
        for (int i = filters.size() - 1; i >= 0; i--) {
            insert(filters.get(i));
        }
        return true;
    }

    @Transaction
    default ImportResult importFilters(List<RegexFilter> filters, List<RegexFilterGlobalExclusion> exclusions,
                                       boolean apply) {
        ImportResult result = merge(getSnapshot(), filters, exclusions, true);
        if (apply) {
            replaceSnapshot(result);
        }
        return result;
    }

    @Transaction
    default boolean migrateLegacy(List<RegexFilter> filters, List<RegexFilterGlobalExclusion> exclusions) {
        // Replaying after a commit but before the preference marker must preserve
        // existing rows, while filling any missing rows from a partial old migration.
        replaceSnapshot(merge(getSnapshot(), filters, exclusions, false));
        return true;
    }

    @Transaction
    default void replaceSnapshot(Snapshot snapshot) {
        deleteAllFilters();
        deleteAllExclusions();
        for (int i = snapshot.filters.size() - 1; i >= 0; i--) {
            insert(snapshot.filters.get(i));
        }
        for (RegexFilterGlobalExclusion exclusion : snapshot.exclusions) {
            insertExclusion(exclusion);
        }
    }

    static ImportResult merge(Snapshot current, List<RegexFilter> incoming,
                              List<RegexFilterGlobalExclusion> exclusions, boolean updateExisting) {
        ImportResult result = new ImportResult();
        HashSet<String> usedIds = new HashSet<>();
        for (RegexFilter filter : current.filters) {
            result.filters.add(copyFilter(filter));
            usedIds.add(filter.id);
        }
        HashMap<String, String> sharedIdMap = new HashMap<>();
        for (RegexFilter filter : incoming) {
            if (filter == null || filter.text == null) continue;
            RegexFilter target = null;
            for (RegexFilter existing : result.filters) {
                if (Objects.equals(existing.dialogId, filter.dialogId)
                        && Objects.equals(existing.text, filter.text)
                        && existing.caseInsensitive == filter.caseInsensitive
                        && existing.reversed == filter.reversed) {
                    target = existing;
                    break;
                }
            }
            if (target == null && !updateExisting && filter.id != null) {
                for (RegexFilter existing : result.filters) {
                    if (filter.id.equals(existing.id) && Objects.equals(filter.dialogId, existing.dialogId)) {
                        target = existing;
                        break;
                    }
                }
            }
            if (target == null) {
                target = copyFilter(filter);
                if (target.id == null || target.id.isEmpty() || usedIds.contains(target.id)) {
                    String key = "filter:" + filter.id + ":" + filter.dialogId + ":"
                            + filter.caseInsensitive + ":" + filter.reversed + ":" + filter.text;
                    target.id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
                    while (usedIds.contains(target.id)) {
                        target.id = UUID.nameUUIDFromBytes(target.id.getBytes(StandardCharsets.UTF_8)).toString();
                    }
                }
                usedIds.add(target.id);
                result.filters.add(target);
                if (target.dialogId == null) result.newFilters++;
                else result.newChatFilters++;
            } else if (updateExisting && target.enabled != filter.enabled) {
                target.enabled = filter.enabled;
                result.updatedFilters++;
            }
            if (filter.dialogId == null && filter.id != null && !filter.id.isEmpty()) {
                String previous = sharedIdMap.put(filter.id, target.id);
                if (previous != null && !previous.equals(target.id)) {
                    throw new IllegalArgumentException("Conflicting shared filter ID: " + filter.id);
                }
            }
        }
        HashSet<String> sharedIds = new HashSet<>();
        for (RegexFilter filter : result.filters) {
            if (filter.dialogId == null) sharedIds.add(filter.id);
        }
        HashSet<String> exclusionKeys = new HashSet<>();
        for (RegexFilterGlobalExclusion exclusion : current.exclusions) {
            result.exclusions.add(exclusion);
            exclusionKeys.add(exclusion.dialogId + ":" + exclusion.filterId);
        }
        for (RegexFilterGlobalExclusion exclusion : exclusions) {
            if (exclusion == null || exclusion.dialogId == 0L || exclusion.filterId == null) continue;
            String id = sharedIdMap.getOrDefault(exclusion.filterId, exclusion.filterId);
            if (sharedIds.contains(id) && exclusionKeys.add(exclusion.dialogId + ":" + id)) {
                RegexFilterGlobalExclusion remapped = new RegexFilterGlobalExclusion();
                remapped.dialogId = exclusion.dialogId;
                remapped.filterId = id;
                result.exclusions.add(remapped);
                result.newExclusions++;
            }
        }
        return result;
    }

    static RegexFilter copyFilter(RegexFilter source) {
        RegexFilter copy = new RegexFilter();
        copy.id = source.id;
        copy.text = source.text;
        copy.dialogId = source.dialogId;
        copy.enabled = source.enabled;
        copy.caseInsensitive = source.caseInsensitive;
        copy.reversed = source.reversed;
        return copy;
    }

    class Snapshot {
        public final List<RegexFilter> filters;
        public final List<RegexFilterGlobalExclusion> exclusions;

        public Snapshot(List<RegexFilter> filters, List<RegexFilterGlobalExclusion> exclusions) {
            this.filters = filters;
            this.exclusions = exclusions;
        }
    }

    class ImportResult extends Snapshot {
        public int newFilters;
        public int newChatFilters;
        public int updatedFilters;
        public int newExclusions;

        ImportResult() {
            super(new ArrayList<>(), new ArrayList<>());
        }
    }
}
