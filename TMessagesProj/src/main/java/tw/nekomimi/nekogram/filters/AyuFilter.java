package tw.nekomimi.nekogram.filters;

import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.FilterPrefsMigrator;
import com.radolyn.ayugram.database.dao.RegexFilterDao;
import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.radolyn.ayugram.AyuOptions;
import com.radolyn.ayugram.helpers.AyuMessageHelper;
import com.radolyn.ayugram.AyuConfig;

public class AyuFilter {
    private static final Object cacheLock = new Object();
    private static long cacheVersion;
    // Bumped only when rules/exclusions change; message edits bump cacheVersion alone.
    private static long rulesVersion;
    private static volatile List<FilterModel> filterModels;
    private static volatile List<ChatRules> chatFilterEntries;
    private static volatile Set<Long> excludedDialogs;
    private static volatile HashMap<Long, HashSet<String>> excludedSharedFilterIdsByDialog;
    private static volatile HashSet<Long> blockedChannels;
    private static volatile HashSet<Long> customFilteredUsers;
    private static volatile HashMap<Long, CustomFilteredUser> customFilteredUsersData;


    public static ArrayList<FilterModel> getRegexFilters() {
        return copyFilters(getSharedSnapshot());
    }

    private static List<FilterModel> getSharedSnapshot() {
        List<FilterModel> snapshot = filterModels;
        if (snapshot == null) {
            synchronized (cacheLock) {
                snapshot = filterModels;
                if (snapshot == null) {
                    filterModels = snapshot = Collections.unmodifiableList(copyFilters(loadSharedFilters()));
                }
            }
        }
        return snapshot;
    }

    private static ArrayList<FilterModel> copyFilters(List<FilterModel> source) {
        ArrayList<FilterModel> copy = new ArrayList<>(source.size());
        for (FilterModel model : source) {
            if (model == null) continue;
            FilterModel item = new FilterModel();
            item.id = model.id;
            item.regex = model.regex;
            item.enabled = model.enabled;
            item.caseInsensitive = model.caseInsensitive;
            item.reversed = model.reversed;
            item.pattern = model.pattern;
            copy.add(item);
        }
        return copy;
    }

    private static ArrayList<ChatFilterEntry> copyChatEntries(List<ChatRules> source) {
        ArrayList<ChatFilterEntry> copy = new ArrayList<>(source.size());
        for (ChatRules entry : source) {
            ChatFilterEntry item = new ChatFilterEntry();
            item.dialogId = entry.dialogId;
            item.filters = entry.filters == null ? new ArrayList<>() : copyFilters(entry.filters);
            copy.add(item);
        }
        return copy;
    }

    private static final class ChatRules {
        final long dialogId;
        final List<FilterModel> filters;

        ChatRules(ChatFilterEntry entry) {
            dialogId = entry.dialogId;
            filters = Collections.unmodifiableList(entry.filters == null ? new ArrayList<>() : copyFilters(entry.filters));
        }
    }

    /** Non-blocking readiness check for UI callers. Does not load rules or query Room. */
    public static boolean isLoaded() {
        return filterModels != null && chatFilterEntries != null
                && excludedSharedFilterIdsByDialog != null && excludedDialogs != null;
    }

    /** Read-only version check; never waits for rule loading. */
    public static long getRulesVersion() {
        synchronized (cacheLock) {
            return rulesVersion;
        }
    }

    /** Loads/compiles on globalQueue, then calls onLoaded on the UI thread. Recheck isLoaded there. */
    public static void preloadAsync(Runnable onLoaded) {
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (cacheLock) {
                getSharedSnapshot();
                getChatSnapshot();
                getExcludedSharedFilterIdsByDialog();
                getExcludedDialogs();
            }
            if (onLoaded != null) AndroidUtilities.runOnUIThread(onLoaded);
        });
    }

    private static ArrayList<FilterModel> loadSharedFilters() {
        ArrayList<FilterModel> out = new ArrayList<>();
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao != null) {
            try {
                List<RegexFilter> rows = dao.getShared();
                for (RegexFilter row : rows) {
                    FilterModel m = new FilterModel();
                    m.id = row.id != null ? row.id : UUID.randomUUID().toString();
                    m.regex = row.text;
                    m.caseInsensitive = row.caseInsensitive;
                    m.reversed = row.reversed;
                    m.enabled = row.enabled;
                    m.buildPattern();
                    out.add(m);
                }
            } catch (Exception e) {
                FileLog.e("AyuFilter.loadSharedFilters", e);
            }
        }
        if (out.isEmpty()) {
            ArrayList<FilterModel> legacy = loadSharedFiltersFromPrefs();
            if (legacy != null && !legacy.isEmpty()) {
                saveFilter(legacy);
                return legacy;
            }
        }
        return out;
    }

    private static ArrayList<FilterModel> loadSharedFiltersFromPrefs() {
        try {
            String str = AyuConfig.INSTANCE.getRegexFiltersData().String();
            if (TextUtils.isEmpty(str) || "[]".equals(str)) {
                return null;
            }
            FilterModel[] arr = new Gson().fromJson(str, FilterModel[].class);
            if (arr == null || arr.length == 0) {
                return null;
            }
            ArrayList<FilterModel> list = new ArrayList<>();
            for (FilterModel f : arr) {
                if (f == null) continue;
                f.ensureId();
                f.buildPattern();
                list.add(f);
            }
            return list;
        } catch (Exception e) {
            FileLog.e("AyuFilter.loadSharedFiltersFromPrefs", e);
            return null;
        }
    }

    public static void addFilter(String text, boolean caseInsensitive) {
        addFilter(text, caseInsensitive, false);
    }

    public static void addFilter(String text, boolean caseInsensitive, boolean reversed) {
        var list = new ArrayList<>(getRegexFilters());
        FilterModel filterModel = new FilterModel();
        filterModel.regex = text;
        filterModel.caseInsensitive = caseInsensitive;
        filterModel.reversed = reversed;
        filterModel.enabled = true;
        filterModel.buildPattern();
        list.add(0, filterModel);
        saveFilter(list);
    }

    public static void editFilter(int filterIdx, String text, boolean caseInsensitive) {
        editFilter(filterIdx, text, caseInsensitive, false);
    }

    public static void editFilter(int filterIdx, String text, boolean caseInsensitive, boolean reversed) {
        var list = getRegexFilters();
        if (filterIdx < 0 || filterIdx >= list.size()) {
            return;
        }
        FilterModel filterModel = list.get(filterIdx);
        filterModel.regex = text;
        filterModel.caseInsensitive = caseInsensitive;
        filterModel.reversed = reversed;
        filterModel.buildPattern();
        saveFilter(list);
    }

    public static void saveFilter(ArrayList<FilterModel> filterModels1) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) {
            return;
        }
        try {
            ArrayList<RegexFilter> rows = new ArrayList<>();
            for (FilterModel m : filterModels1) {
                if (m == null) continue;
                m.ensureId();
                m.buildPattern();
                rows.add(toRow(m, null));
            }
            if (!dao.replaceShared(rows)) {
                throw new IllegalStateException("Filter database unavailable");
            }
        } catch (Exception e) {
            FileLog.e("AyuFilter.saveFilter", e);
            return;
        }
        AyuConfig.INSTANCE.getRegexFiltersData().setConfigString(new Gson().toJson(filterModels1));
        rebuildCache();
    }

    public static void removeFilter(int filterIdx) {
        var list = getRegexFilters();
        if (filterIdx < 0 || filterIdx >= list.size()) {
            return;
        }
        FilterModel removed = list.remove(filterIdx);
        if (removed != null && !TextUtils.isEmpty(removed.id)) {
            removeExcludedSharedFilterEntries(removed.id);
        }
        saveFilter(list);
    }

    public static void saveRuleAsync(FilterModel model, Long dialogId, boolean existing,
                                     Runnable onSaved, Runnable onError) {
        FilterModel copy = copyFilters(Collections.singletonList(model)).get(0);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                copy.ensureId();
                copy.buildPattern();
                RegexFilterDao dao = AyuData.getRegexFilterDao();
                if (copy.pattern == null || dao == null || !dao.saveRule(toRow(copy, dialogId), existing)) {
                    throw new IllegalStateException("Filter could not be saved");
                }
                completeRuleWrite(dao, onSaved);
            } catch (Exception e) {
                FileLog.e(e);
                if (onError != null) AndroidUtilities.runOnUIThread(onError);
            }
        });
    }

    public static void setFilterEnabledAsync(String id, boolean enabled, Runnable onSaved) {
        Utilities.globalQueue.postRunnable(() -> {
            RegexFilterDao dao = AyuData.getRegexFilterDao();
            if (dao == null) return;
            try {
                RegexFilter row = dao.getById(id);
                if (row == null) return;
                row.enabled = enabled;
                if (dao.saveRule(row, true)) completeRuleWrite(dao, onSaved);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static void removeRuleAsync(String id, Runnable onSaved) {
        Utilities.globalQueue.postRunnable(() -> {
            RegexFilterDao dao = AyuData.getRegexFilterDao();
            if (dao == null) return;
            try {
                if (dao.deleteRule(id)) completeRuleWrite(dao, onSaved);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static void completeRuleWrite(RegexFilterDao dao, Runnable onSaved) {
        RegexFilterDao.Snapshot snapshot = dao.getSnapshot();
        if (snapshot == null) throw new IllegalStateException("Filter database unavailable");
        persistFilterBackup(snapshot);
        rebuildCache();
        // Preference listeners run on UI; enqueue preloading after those invalidations.
        AndroidUtilities.runOnUIThread(() -> preloadAsync(onSaved));
    }

    public static CharSequence getMessageText(MessageObject selectedObject, MessageObject.GroupedMessages selectedObjectGroup) {
        if (selectedObject == null) {
            return null;
        }
        CharSequence messageText = AyuMessageHelper.getMessageFilterMatchText(selectedObject, selectedObjectGroup);
        if (TextUtils.isEmpty(messageText)) {
            messageText = null;
        }
        if (selectedObject.translated || selectedObject.isRestrictedMessage) {
            messageText = null;
        }
        return messageText;
    }

    public static void rebuildCache() {
        synchronized (cacheLock) {
            cacheVersion++;
            rulesVersion++;
            filterModels = null;
            chatFilterEntries = null;
            excludedSharedFilterIdsByDialog = null;
            excludedDialogs = null;
            AyuFilterCache.clearAll();
        }
        notifyFiltersUpdated();
    }

    public static void onPreferencesChanged(String key) {
        if (key == null || key.startsWith("RegexFilter") || key.equals("IgnoreBlocked")
                || key.equals("BlockedChannelsData") || key.equals("CustomFilteredUsersData")) {
            synchronized (cacheLock) {
                blockedChannels = null;
                customFilteredUsers = null;
                customFilteredUsersData = null;
            }
            // No preference writes here: import/toggle invalidation cannot loop.
            rebuildCache();
        }
    }

    public static void invalidateFilteredCache() {
        synchronized (cacheLock) {
            cacheVersion++;
            rulesVersion++;
            AyuFilterCache.clearAll();
        }
        notifyFiltersUpdated();
    }

    private static void notifyFiltersUpdated() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.regexFiltersUpdated);
                }
            }
        });
    }

    private static boolean isFilterMatch(FilterModel filter, CharSequence text) {
        if (filter == null || !filter.enabled || filter.pattern == null || TextUtils.isEmpty(text)) {
            return false;
        }
        if (filter.reversed && AyuConfig.INSTANCE.getRegexFiltersMaskMessages().Bool()) {
            return false;
        }
        boolean matched = filter.pattern.matcher(text).find();
        return filter.reversed ? !matched : matched;
    }

    private static boolean isFilteredInternal(CharSequence text, long dialogId) {
        List<ChatRules> chats = getChatSnapshot();
        List<FilterModel> shared = getSharedSnapshot();
        if (chats != null) {
            for (var entry : chats) {
                if (entry.dialogId == dialogId) {
                    if (entry.filters != null) {
                        for (var pattern : entry.filters) {
                            if (isFilterMatch(pattern, text)) {
                                return true;
                            }
                        }
                    }
                    break;
                }
            }
        }

        boolean isPrivateDialog = dialogId > 0;
        if (isPrivateDialog && !AyuConfig.INSTANCE.getRegexFiltersEnableInChats().Bool()) {
            return false;
        }

        if (shared != null) {
            HashSet<String> excludedFilterIds = getExcludedSharedFilterIds(dialogId);
            for (var pattern : shared) {
                if (!TextUtils.isEmpty(pattern.id) && excludedFilterIds.contains(pattern.id)) {
                    continue;
                }
                if (isFilterMatch(pattern, text)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void collectMatchedRanges(ArrayList<int[]> ranges, FilterModel filter, CharSequence text) {
        if (filter == null || !filter.enabled || filter.pattern == null || filter.reversed) {
            return;
        }
        try {
            var matcher = filter.pattern.matcher(text);
            int length = text.length();
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                if (start >= 0 && end > start && end <= length) {
                    ranges.add(new int[]{start, end});
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static ArrayList<int[]> findFilteredRanges(CharSequence text, long dialogId) {
        ArrayList<int[]> ranges = new ArrayList<>();
        if (TextUtils.isEmpty(text)) {
            return ranges;
        }
        List<ChatRules> chats = getChatSnapshot();
        List<FilterModel> shared = getSharedSnapshot();
        if (chats != null) {
            for (var entry : chats) {
                if (entry.dialogId == dialogId) {
                    if (entry.filters != null) {
                        for (var filter : entry.filters) {
                            collectMatchedRanges(ranges, filter, text);
                        }
                    }
                    break;
                }
            }
        }

        boolean isPrivateDialog = dialogId > 0;
        if (isPrivateDialog && !AyuConfig.INSTANCE.getRegexFiltersEnableInChats().Bool()) {
            return mergeRanges(ranges);
        }

        if (shared != null) {
            HashSet<String> excludedFilterIds = getExcludedSharedFilterIds(dialogId);
            for (var filter : shared) {
                if (!TextUtils.isEmpty(filter.id) && excludedFilterIds.contains(filter.id)) {
                    continue;
                }
                collectMatchedRanges(ranges, filter, text);
            }
        }
        return mergeRanges(ranges);
    }

    private static ArrayList<int[]> mergeRanges(ArrayList<int[]> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }
        Collections.sort(ranges, (a, b) -> Integer.compare(a[0], b[0]));
        ArrayList<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (merged.isEmpty()) {
                merged.add(new int[]{range[0], range[1]});
                continue;
            }
            int[] last = merged.get(merged.size() - 1);
            if (range[0] <= last[1]) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new int[]{range[0], range[1]});
            }
        }
        return merged;
    }

    public static boolean isFiltered(MessageObject msg, MessageObject.GroupedMessages group) {
        if (!AyuConfig.INSTANCE.getRegexFiltersEnabled().Bool()) {
            return false;
        }

        if (msg == null || msg.isOutOwner() || msg.isOut()) {
            return false;
        }

        if (msg.skipAyuFiltering) {
            return false;
        }

        long dialogId = msg.getDialogId();
        if (isDialogExcluded(dialogId)) {
            return false;
        }

        // Push payloads carry preview text only; never let them share cache entries with the real message.
        boolean cacheable = !msg.isFcmMessage();
        AyuFilterCache.DialogKey cacheKey = cacheable ? AyuFilterCache.keyFor(dialogId, msg) : null;
        final long version;
        final Boolean cached;
        synchronized (cacheLock) {
            version = cacheVersion;
            cached = cacheable ? AyuFilterCache.get(cacheKey, msg, group) : null;
        }
        if (cached != null) {
            return cached;
        }

        var text = getMessageText(msg, group);
        if (TextUtils.isEmpty(text)) {
            return false;
        }

        boolean result = isFilteredInternal(text, dialogId);
        if (cacheable && (group != null || msg.getGroupId() == 0)) {
            synchronized (cacheLock) {
                if (version == cacheVersion) AyuFilterCache.put(cacheKey, msg, group, result);
            }
        }

        return result;
    }

    public static boolean shouldMaskFilteredMessages() {
        return AyuConfig.INSTANCE.getRegexFiltersEnabled().Bool() && AyuConfig.INSTANCE.getRegexFiltersMaskMessages().Bool();
    }

    public static boolean shouldHideOnlyMatched() {
        return AyuConfig.INSTANCE.getRegexFiltersEnabled().Bool()
                && AyuConfig.INSTANCE.getRegexFiltersMaskMessages().Bool()
                && AyuConfig.INSTANCE.getRegexFiltersHideOnlyMatched().Bool();
    }

    public static boolean shouldHideFilteredMessages() {
        return AyuConfig.INSTANCE.getRegexFiltersEnabled().Bool() && !AyuConfig.INSTANCE.getRegexFiltersMaskMessages().Bool();
    }

    public static boolean shouldMaskIgnoredBlockedMessages() {
        return AyuConfig.INSTANCE.getRegexFiltersEnabled().Bool()
            && AyuOptions.ignoreBlocked.Bool()
            && AyuConfig.INSTANCE.getRegexFiltersMaskMessages().Bool();
    }

    public static boolean shouldHideIgnoredBlockedMessages() {
        return AyuConfig.INSTANCE.getRegexFiltersEnabled().Bool()
            && AyuOptions.ignoreBlocked.Bool()
            && !AyuConfig.INSTANCE.getRegexFiltersMaskMessages().Bool();
    }

    public static boolean shouldHideFilteredMessage(MessageObject msg, MessageObject.GroupedMessages group) {
        return shouldHideFilteredMessages() && isFiltered(msg, group);
    }

    public static boolean shouldMaskFilteredMessage(MessageObject msg, MessageObject.GroupedMessages group) {
        if (shouldHideOnlyMatched()) {
            return false;
        }
        return shouldMaskFilteredMessages() && isFiltered(msg, group);
    }

    /** Filtered or ignored-blocked messages never notify, whether the chat hides or masks them. */
    public static boolean shouldSuppressNotification(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || msg.isStoryPush || msg.isStoryReactionPush) {
            return false;
        }
        if (isFiltered(msg, null)) {
            return true;
        }
        return isIgnoredBlockedMessage(msg);
    }

    /** Batch-local album suppression; never publishes partial album results to the chat cache. */
    public static HashSet<MessageObject> getSuppressedNotifications(List<MessageObject> messages) {
        HashSet<MessageObject> suppressed = new HashSet<>();
        HashSet<String> albums = new HashSet<>();
        if (messages == null) return suppressed;
        for (MessageObject message : messages) {
            if (shouldSuppressNotification(message)) {
                suppressed.add(message);
                if (message.getGroupId() != 0) albums.add(notificationAlbumKey(message));
            }
        }
        for (MessageObject message : messages) {
            if (message != null && message.messageOwner != null && message.getGroupId() != 0
                    && albums.contains(notificationAlbumKey(message))) {
                suppressed.add(message);
            }
        }
        return suppressed;
    }

    private static String notificationAlbumKey(MessageObject message) {
        return message.currentAccount + ":" + message.getDialogId() + ":" + message.getGroupId();
    }

    public static boolean shouldMaskMessage(MessageObject msg, MessageObject.GroupedMessages group) {
        return shouldMaskFilteredMessage(msg, group) || (shouldMaskIgnoredBlockedMessages() && isIgnoredBlockedMessage(msg));
    }

    public static ArrayList<TLRPC.MessageEntity> addSpoilerEntities(MessageObject msg, ArrayList<TLRPC.MessageEntity> original, CharSequence text) {
        if (msg == null || TextUtils.isEmpty(text)) {
            return original;
        }

        if (shouldHideOnlyMatched() && isFiltered(msg, null)) {
            ArrayList<int[]> ranges = findFilteredRanges(text, msg.getDialogId());
            if (!ranges.isEmpty()) {
                ArrayList<TLRPC.MessageEntity> result = original != null ? new ArrayList<>(original) : new ArrayList<>();
                for (int[] range : ranges) {
                    TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
                    spoiler.offset = range[0];
                    spoiler.length = range[1] - range[0];
                    result.add(spoiler);
                }
                return result;
            }
        }

        if (!shouldMaskMessage(msg, null)) {
            return original;
        }

        ArrayList<TLRPC.MessageEntity> result = original != null ? new ArrayList<>(original) : new ArrayList<>();
        for (int i = 0, size = result.size(); i < size; i++) {
            TLRPC.MessageEntity entity = result.get(i);
            if (entity instanceof TLRPC.TL_messageEntitySpoiler && entity.offset == 0 && entity.length >= text.length()) {
                return result;
            }
        }
        TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
        spoiler.offset = 0;
        spoiler.length = text.length();
        result.add(spoiler);
        return result;
    }

    public static void syncMaskedSpoilerRevealState(MessageObject msg, MessageObject.GroupedMessages group) {
        if (msg != null && shouldMaskMessage(msg, group)) {
            msg.isSpoilersRevealed = false;
        }
    }

    public static void syncMaskMarkerSpan(Spannable text, MessageObject msg, MessageObject.GroupedMessages group) {
        if (text == null) {
            return;
        }
        FilterMaskSpan[] spans = text.getSpans(0, text.length(), FilterMaskSpan.class);
        for (int i = 0; i < spans.length; i++) {
            text.removeSpan(spans[i]);
        }
        if (shouldMaskMessage(msg, group) && text.length() > 0) {
            text.setSpan(new FilterMaskSpan(), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static boolean hasMaskedFilterSpan(CharSequence text) {
        if (!(text instanceof Spanned spanned) || spanned.length() == 0) {
            return false;
        }
        return spanned.getSpans(0, spanned.length(), FilterMaskSpan.class).length > 0;
    }

    public static void refreshMaskStateForMessage(MessageObject msg) {
        if (msg == null) {
            return;
        }
        if (msg.messageText instanceof Spannable) {
            syncMaskMarkerSpan((Spannable) msg.messageText, msg, null);
        }
        if (msg.messageTextForReply instanceof Spannable) {
            syncMaskMarkerSpan((Spannable) msg.messageTextForReply, msg, null);
        }
        if (msg.caption instanceof Spannable) {
            syncMaskMarkerSpan((Spannable) msg.caption, msg, null);
        }
        syncMaskedSpoilerRevealState(msg, null);
    }

    public static boolean isIgnoredBlockedMessage(MessageObject msg) {
        if (msg == null || msg.isOutOwner() || msg.isOut() || !AyuOptions.ignoreBlocked.Bool()) {
            return false;
        }
        if (isBlockedPeer(msg.currentAccount, msg.getFromChatId())) {
            return true;
        }
        if (isShadowBannedPeerChain(msg)) {
            return true;
        }
        return msg.replyMessageObject != null && isBlockedPeer(msg.currentAccount, msg.replyMessageObject.getFromChatId());
    }

    private static boolean isShadowBannedPeerChain(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) {
            return false;
        }
        long viaBotUserId = msg.messageOwner.via_bot_id;
        if (viaBotUserId != 0L && isBlockedPeer(msg.currentAccount, viaBotUserId)) {
            return true;
        }
        TLRPC.MessageFwdHeader fwd = msg.messageOwner.fwd_from;
        if (fwd == null) {
            return false;
        }
        if (fwd.from_id != null) {
            long did = DialogObject.getPeerDialogId(fwd.from_id);
            if (isBlockedPeer(msg.currentAccount, did)) {
                return true;
            }
        }
        if (fwd.saved_from_peer != null) {
            long did = DialogObject.getPeerDialogId(fwd.saved_from_peer);
            if (isBlockedPeer(msg.currentAccount, did)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockedPeer(int currentAccount, long peerId) {
        if (peerId == 0L) {
            return false;
        }
        return isCustomFilteredPeer(peerId) || isBlockedChannel(peerId)
            || MessagesController.getInstance(currentAccount).blockePeers.indexOfKey(peerId) >= 0;
    }

    private static class FilterMaskSpan {
    }

    public static ArrayList<ChatFilterEntry> getChatFilterEntries() {
        return copyChatEntries(getChatSnapshot());
    }

    private static List<ChatRules> getChatSnapshot() {
        List<ChatRules> snapshot = chatFilterEntries;
        if (snapshot == null) {
            synchronized (cacheLock) {
                snapshot = chatFilterEntries;
                if (snapshot == null) {
                    ArrayList<ChatRules> entries = new ArrayList<>();
                    for (ChatFilterEntry entry : loadChatFilterEntries()) entries.add(new ChatRules(entry));
                    chatFilterEntries = snapshot = Collections.unmodifiableList(entries);
                }
            }
        }
        return snapshot;
    }

    private static ArrayList<ChatFilterEntry> loadChatFilterEntries() {
        ArrayList<ChatFilterEntry> out = new ArrayList<>();
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao != null) {
            try {
                HashMap<Long, ChatFilterEntry> byDialog = new HashMap<>();
                List<RegexFilter> all = dao.getAll();
                for (RegexFilter row : all) {
                    if (row.dialogId == null) {
                        continue;
                    }
                    long did = row.dialogId;
                    ChatFilterEntry entry = byDialog.get(did);
                    if (entry == null) {
                        entry = new ChatFilterEntry();
                        entry.dialogId = did;
                        entry.filters = new ArrayList<>();
                        byDialog.put(did, entry);
                    }
                    FilterModel m = new FilterModel();
                    m.id = row.id != null ? row.id : UUID.randomUUID().toString();
                    m.regex = row.text;
                    m.caseInsensitive = row.caseInsensitive;
                    m.reversed = row.reversed;
                    m.enabled = row.enabled;
                    m.buildPattern();
                    entry.filters.add(m);
                }
                out.addAll(byDialog.values());
            } catch (Exception e) {
                FileLog.e("AyuFilter.loadChatFilterEntries", e);
            }
        }
        if (out.isEmpty()) {
            ArrayList<ChatFilterEntry> legacy = loadChatFilterEntriesFromPrefs();
            if (legacy != null && !legacy.isEmpty()) {
                saveChatFilterEntries(legacy);
                return legacy;
            }
        }
        return out;
    }

    private static ArrayList<ChatFilterEntry> loadChatFilterEntriesFromPrefs() {
        try {
            String str = AyuConfig.INSTANCE.getRegexChatFiltersData().String();
            if (TextUtils.isEmpty(str) || "[]".equals(str)) {
                return null;
            }
            ChatFilterEntry[] arr = new Gson().fromJson(str, ChatFilterEntry[].class);
            if (arr == null || arr.length == 0) {
                return null;
            }
            ArrayList<ChatFilterEntry> list = new ArrayList<>();
            for (ChatFilterEntry entry : arr) {
                if (entry == null || entry.filters == null) continue;
                for (FilterModel f : entry.filters) {
                    if (f != null) {
                        f.ensureId();
                        f.buildPattern();
                    }
                }
                list.add(entry);
            }
            return list;
        } catch (Exception e) {
            FileLog.e("AyuFilter.loadChatFilterEntriesFromPrefs", e);
            return null;
        }
    }

    public static void saveChatFilterEntries(ArrayList<ChatFilterEntry> entries) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) {
            return;
        }
        try {
            ArrayList<RegexFilter> rows = new ArrayList<>();
            for (ChatFilterEntry entry : entries) {
                if (entry == null || entry.filters == null) continue;
                for (FilterModel m : entry.filters) {
                    if (m != null) {
                        m.ensureId();
                        m.buildPattern();
                        rows.add(toRow(m, entry.dialogId));
                    }
                }
            }
            if (!dao.replaceChats(rows)) {
                throw new IllegalStateException("Filter database unavailable");
            }
        } catch (Exception e) {
            FileLog.e("AyuFilter.saveChatFilterEntries", e);
            return;
        }
        AyuConfig.INSTANCE.getRegexChatFiltersData().setConfigString(new Gson().toJson(entries));
        rebuildCache();
    }

    public static RegexFilterDao.Snapshot getPersistedFilters() {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        RegexFilterDao.Snapshot snapshot = dao == null ? null : dao.getSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("Filter database unavailable");
        }
        return snapshot;
    }

    /** Restore only supplied backup scopes, atomically replacing their Room rows. */
    public static void restoreFilterPreferences(String sharedJson, String chatsJson, String exclusionsJson) {
        if (sharedJson == null && chatsJson == null && exclusionsJson == null) return;
        RegexFilterDao.Snapshot restored = FilterPrefsMigrator.parseLegacy(sharedJson, chatsJson, exclusionsJson);
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null || !dao.restoreProvidedFilters(restored, sharedJson != null,
                chatsJson != null, exclusionsJson != null)) {
            throw new IllegalStateException("Filter database unavailable");
        }
    }

    public static RegexFilterDao.ImportResult importFilters(ArrayList<FilterModel> shared,
                                                            ArrayList<ChatFilterEntry> chats,
                                                            ArrayList<ExcludedFilterEntry> exclusions,
                                                            boolean apply) {
        ArrayList<RegexFilter> rows = new ArrayList<>();
        if (shared != null) {
            for (FilterModel model : shared) {
                if (model != null && model.regex != null) rows.add(toRow(model, null));
            }
        }
        if (chats != null) {
            for (ChatFilterEntry entry : chats) {
                if (entry == null || entry.filters == null) continue;
                for (FilterModel model : entry.filters) {
                    if (model != null && model.regex != null) rows.add(toRow(model, entry.dialogId));
                }
            }
        }
        ArrayList<RegexFilterGlobalExclusion> exclusionRows = new ArrayList<>();
        if (exclusions != null) {
            for (ExcludedFilterEntry entry : exclusions) {
                if (entry == null) continue;
                RegexFilterGlobalExclusion row = new RegexFilterGlobalExclusion();
                row.dialogId = entry.dialogId;
                row.filterId = entry.filterId;
                exclusionRows.add(row);
            }
        }
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        RegexFilterDao.ImportResult result = dao == null ? null : dao.importFilters(rows, exclusionRows, apply);
        if (result == null) {
            throw new IllegalStateException("Filter database unavailable");
        }
        if (apply) {
            persistFilterBackup(result);
            rebuildCache();
        }
        return result;
    }

    private static void persistFilterBackup(RegexFilterDao.Snapshot snapshot) {
        ArrayList<FilterModel> shared = new ArrayList<>();
        HashMap<Long, ChatFilterEntry> chats = new HashMap<>();
        for (RegexFilter row : snapshot.filters) {
            FilterModel model = new FilterModel();
            model.id = row.id;
            model.regex = row.text;
            model.enabled = row.enabled;
            model.caseInsensitive = row.caseInsensitive;
            model.reversed = row.reversed;
            if (row.dialogId == null) {
                shared.add(model);
            } else {
                ChatFilterEntry entry = chats.computeIfAbsent(row.dialogId, did -> {
                    ChatFilterEntry chat = new ChatFilterEntry();
                    chat.dialogId = did;
                    chat.filters = new ArrayList<>();
                    return chat;
                });
                entry.filters.add(model);
            }
        }
        Gson gson = new Gson();
        AyuConfig.INSTANCE.getRegexFiltersData().setConfigString(gson.toJson(shared));
        AyuConfig.INSTANCE.getRegexChatFiltersData().setConfigString(gson.toJson(chats.values()));
        AyuConfig.INSTANCE.getRegexFiltersExcludedEntriesData().setConfigString(gson.toJson(snapshot.exclusions));
    }

    public static ArrayList<FilterModel> getChatFiltersForDialog(long dialogId) {
        var entries = getChatFilterEntries();
        for (var e : entries) {
            if (e.dialogId == dialogId) {
                return e.filters != null ? e.filters : new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }

    public static void addChatFilter(long dialogId, String text, boolean caseInsensitive) {
        addChatFilter(dialogId, text, caseInsensitive, false);
    }

    public static void addChatFilter(long dialogId, String text, boolean caseInsensitive, boolean reversed) {
        var entries = new ArrayList<>(getChatFilterEntries());
        ChatFilterEntry target = null;
        for (var e : entries) {
            if (e.dialogId == dialogId) {
                target = e;
                break;
            }
        }
        if (target == null) {
            target = new ChatFilterEntry();
            target.dialogId = dialogId;
            target.filters = new ArrayList<>();
            entries.add(target);
        }
        FilterModel m = new FilterModel();
        m.regex = text;
        m.caseInsensitive = caseInsensitive;
        m.reversed = reversed;
        m.enabled = true;
        m.buildPattern();
        target.filters.add(0, m);
        saveChatFilterEntries(entries);
    }

    public static void editChatFilter(long dialogId, int filterIdx, String text, boolean caseInsensitive) {
        editChatFilter(dialogId, filterIdx, text, caseInsensitive, false);
    }

    public static void editChatFilter(long dialogId, int filterIdx, String text, boolean caseInsensitive, boolean reversed) {
        var entries = getChatFilterEntries();
        ChatFilterEntry target = null;
        for (var e : entries) {
            if (e.dialogId == dialogId) {
                target = e;
                break;
            }
        }
        if (target == null || target.filters == null) return;
        if (filterIdx < 0 || filterIdx >= target.filters.size()) return;
        FilterModel m = target.filters.get(filterIdx);
        m.regex = text;
        m.caseInsensitive = caseInsensitive;
        m.reversed = reversed;
        m.buildPattern();
        saveChatFilterEntries(entries);
    }

    public static void removeChatFilter(long dialogId, int filterIdx) {
        var entries = getChatFilterEntries();
        ChatFilterEntry target = null;
        for (var e : entries) {
            if (e.dialogId == dialogId) {
                target = e;
                break;
            }
        }
        if (target == null || target.filters == null) return;
        if (filterIdx < 0 || filterIdx >= target.filters.size()) return;
        target.filters.remove(filterIdx);
        if (target.filters.isEmpty()) {
            entries.remove(target);
        }
        saveChatFilterEntries(entries);
    }


    private static Set<Long> getExcludedDialogs() {
        Set<Long> snapshot = excludedDialogs;
        if (snapshot != null) return snapshot;
        synchronized (cacheLock) {
            snapshot = excludedDialogs;
            if (snapshot == null) {
                excludedDialogs = snapshot = Collections.unmodifiableSet(loadExcludedDialogs());
            }
        }
        return snapshot;
    }

    private static HashSet<Long> loadExcludedDialogs() {
        HashSet<Long> set = new HashSet<>();
        try {
            String str = AyuConfig.INSTANCE.getRegexFiltersExcludedDialogs().String();
            Long[] arr = new Gson().fromJson(str, Long[].class);
            if (arr != null) {
                set.addAll(Arrays.asList(arr));
            }
        } catch (Exception e) {
            FileLog.e("AyuFilter.getExcludedDialogs", e);
        }
        return set;
    }

    public static boolean isDialogExcluded(long dialogId) {
        return getExcludedDialogs().contains(dialogId);
    }

    public static void setDialogExcluded(long dialogId, boolean excluded) {
        HashSet<Long> set = new HashSet<>(getExcludedDialogs());
        boolean changed;
        if (excluded) {
            changed = set.add(dialogId);
        } else {
            changed = set.remove(dialogId);
        }
        if (changed) {
            Long[] arr = set.toArray(new Long[0]);
            String str = new Gson().toJson(arr);
            AyuConfig.INSTANCE.getRegexFiltersExcludedDialogs().setConfigString(str);
            synchronized (cacheLock) {
                excludedDialogs = Collections.unmodifiableSet(new HashSet<>(set));
                cacheVersion++;
                rulesVersion++;
                AyuFilterCache.clearDialog(dialogId);
            }
            notifyFiltersUpdated();
        }
    }


    public static ArrayList<ExcludedFilterEntry> getExcludedFilterEntries() {
        ArrayList<ExcludedFilterEntry> out = new ArrayList<>();
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) {
            return out;
        }
        try {
            List<RegexFilterGlobalExclusion> rows = dao.getAllExclusions();
            for (RegexFilterGlobalExclusion row : rows) {
                ExcludedFilterEntry entry = new ExcludedFilterEntry();
                entry.dialogId = row.dialogId;
                entry.filterId = row.filterId;
                out.add(entry);
            }
        } catch (Exception e) {
            FileLog.e("AyuFilter.getExcludedFilterEntries", e);
        }
        return out;
    }

    private static HashMap<Long, HashSet<String>> getExcludedSharedFilterIdsByDialog() {
        HashMap<Long, HashSet<String>> snapshot = excludedSharedFilterIdsByDialog;
        if (snapshot == null) {
            synchronized (cacheLock) {
                snapshot = excludedSharedFilterIdsByDialog;
                if (snapshot == null) {
                    excludedSharedFilterIdsByDialog = snapshot = buildExcludedSharedFilterIdsMap(getExcludedFilterEntries());
                }
            }
        }
        return snapshot;
    }

    private static HashMap<Long, HashSet<String>> buildExcludedSharedFilterIdsMap(ArrayList<ExcludedFilterEntry> entries) {
        HashMap<Long, HashSet<String>> result = new HashMap<>();
        if (entries == null) {
            return result;
        }
        for (var entry : entries) {
            if (entry == null || entry.dialogId == 0L || TextUtils.isEmpty(entry.filterId)) {
                continue;
            }
            result.computeIfAbsent(entry.dialogId, k -> new HashSet<>()).add(entry.filterId);
        }
        return result;
    }

    public static HashSet<String> getExcludedSharedFilterIds(long dialogId) {
        HashSet<String> ids = getExcludedSharedFilterIdsByDialog().get(dialogId);
        return ids != null ? new HashSet<>(ids) : new HashSet<>();
    }

    public static boolean isSharedFilterExcluded(long dialogId, String filterId) {
        return !TextUtils.isEmpty(filterId) && getExcludedSharedFilterIds(dialogId).contains(filterId);
    }

    public static ArrayList<FilterModel> getExcludedSharedFiltersForDialog(long dialogId) {
        ArrayList<FilterModel> filters = new ArrayList<>();
        HashSet<String> excludedIds = getExcludedSharedFilterIds(dialogId);
        if (excludedIds.isEmpty()) {
            return filters;
        }
        for (var filter : getRegexFilters()) {
            if (filter != null && !TextUtils.isEmpty(filter.id) && excludedIds.contains(filter.id)) {
                filters.add(filter);
            }
        }
        return filters;
    }

    public static int getExcludedSharedFiltersCountForDialog(long dialogId) {
        return getExcludedSharedFiltersForDialog(dialogId).size();
    }

    public static String getFilterDisplayText(FilterModel filter) {
        if (filter == null) {
            return "";
        }
        String regex = filter.regex != null ? filter.regex : "";
        return filter.reversed ? "!= " + regex : regex;
    }

    public static void setSharedFilterExcluded(long dialogId, String filterId, boolean excluded) {
        if (dialogId == 0L || TextUtils.isEmpty(filterId)) {
            return;
        }
        if (excluded) {
            addSharedFilterExclusion(dialogId, filterId);
        } else {
            removeSharedFilterExclusion(dialogId, filterId);
        }
    }

    private static void addSharedFilterExclusion(long dialogId, String filterId) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) return;
        try {
            if (dao.isExcluded(dialogId, filterId)) {
                return;
            }
            RegexFilterGlobalExclusion row = new RegexFilterGlobalExclusion();
            row.dialogId = dialogId;
            row.filterId = filterId;
            dao.insertExclusion(row);
        } catch (Exception e) {
            FileLog.e("AyuFilter.addSharedFilterExclusion", e);
            return;
        }
        synchronized (cacheLock) {
            excludedSharedFilterIdsByDialog = null;
            cacheVersion++;
            rulesVersion++;
            AyuFilterCache.clearAll();
        }
        notifyFiltersUpdated();
    }

    private static void removeSharedFilterExclusion(long dialogId, String filterId) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) return;
        try {
            dao.deleteExclusion(dialogId, filterId);
        } catch (Exception e) {
            FileLog.e("AyuFilter.removeSharedFilterExclusion", e);
            return;
        }
        synchronized (cacheLock) {
            excludedSharedFilterIdsByDialog = null;
            cacheVersion++;
            rulesVersion++;
            AyuFilterCache.clearAll();
        }
        notifyFiltersUpdated();
    }

    private static void removeExcludedSharedFilterEntries(String filterId) {
        if (TextUtils.isEmpty(filterId)) {
            return;
        }
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao != null) {
            try {
                dao.deleteExclusionsByFilterId(filterId);
            } catch (Exception e) {
                FileLog.e("AyuFilter.removeExcludedSharedFilterEntries", e);
            }
        }
        synchronized (cacheLock) {
            excludedSharedFilterIdsByDialog = null;
            cacheVersion++;
            rulesVersion++;
            AyuFilterCache.clearAll();
        }
    }

    public static void clearAllFilters() {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao != null) {
            try {
                dao.deleteAllFilters();
                dao.deleteAllExclusions();
            } catch (Exception e) {
                FileLog.e("AyuFilter.clearAllFilters", e);
            }
        }
        AyuConfig.INSTANCE.getRegexFiltersData().setConfigString("[]");
        AyuConfig.INSTANCE.getRegexChatFiltersData().setConfigString("[]");
        AyuConfig.INSTANCE.getRegexFiltersExcludedEntriesData().setConfigString("[]");
        AyuConfig.INSTANCE.getRegexFiltersExcludedDialogs().setConfigString("[]");
        AyuConfig.INSTANCE.getCustomFilteredUsersData().setConfigString("[]");
        synchronized (cacheLock) {
            customFilteredUsers = new HashSet<>();
            customFilteredUsersData = new HashMap<>();
        }
        rebuildCache();
    }


    private static HashSet<Long> getBlockedChannels() {
        HashSet<Long> snapshot = blockedChannels;
        if (snapshot == null) {
            synchronized (cacheLock) {
                snapshot = blockedChannels;
                if (snapshot == null) {
                    snapshot = new HashSet<>();
                    try {
                        String str = AyuConfig.INSTANCE.getBlockedChannelsData().String();
                        Long[] arr = new Gson().fromJson(str, Long[].class);
                        if (arr != null) {
                            snapshot.addAll(Arrays.asList(arr));
                        }
                    } catch (Exception e) {
                    }
                    blockedChannels = snapshot;
                }
            }
        }
        return snapshot;
    }

    public static boolean isBlockedChannel(long dialogId) {
        return AyuOptions.ignoreBlocked.Bool() && getBlockedChannels().contains(dialogId);
    }

    public static boolean isCustomFilteredPeer(long peerId) {
        return AyuOptions.ignoreBlocked.Bool() && peerId > 0L && getCustomFilteredUsers().contains(peerId);
    }

    public static void blockPeer(long dialogId) {
        HashSet<Long> set = new HashSet<>(getBlockedChannels());
        if (set.add(dialogId)) {
            Long[] arr = set.toArray(new Long[0]);
            String str = new Gson().toJson(arr);
            AyuConfig.INSTANCE.getBlockedChannelsData().setConfigString(str);
            synchronized (cacheLock) {
                blockedChannels = set;
            }
            invalidateFilteredCache();
        }
    }

    public static void unblockPeer(long dialogId) {
        HashSet<Long> set = new HashSet<>(getBlockedChannels());
        if (set.remove(dialogId)) {
            Long[] arr = set.toArray(new Long[0]);
            String str = new Gson().toJson(arr);
            AyuConfig.INSTANCE.getBlockedChannelsData().setConfigString(str);
            synchronized (cacheLock) {
                blockedChannels = set;
            }
            invalidateFilteredCache();
        }
    }

    public static ArrayList<Long> getBlockedChannelsList() {
        return checkBlockedChannels(getBlockedChannels());
    }

    public static int getBlockedChannelsCount() {
        return getBlockedChannels().size();
    }

    public static void clearBlockedChannels() {
        AyuConfig.INSTANCE.getBlockedChannelsData().setConfigString("[]");
        synchronized (cacheLock) {
            blockedChannels = new HashSet<>();
        }
        invalidateFilteredCache();
    }

    public static ArrayList<Long> checkBlockedChannels(HashSet<Long> blockedChannels) {
        if (blockedChannels == null || blockedChannels.isEmpty()) return new ArrayList<>();
        ArrayList<Long> filtered = new ArrayList<>();
        try {
            final MessagesController mc = MessagesController.getInstance(UserConfig.selectedAccount);
            final MessagesStorage ms = MessagesStorage.getInstance(UserConfig.selectedAccount);
            for (Long did : blockedChannels) {
                if (did == null) continue;
                if (did < 0) {
                    TLRPC.Chat chat = mc.getChat(-did);
                    if (chat == null) {
                        chat = ms.getChatSync(-did);
                    }
                    if (chat != null) {
                        filtered.add(did);
                        mc.putChat(chat, true);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return filtered;
    }

    public static void onMessageEdited(int msgId, long dialogId) {
        synchronized (cacheLock) {
            cacheVersion++;
            AyuFilterCache.invalidate(dialogId, msgId);
        }
    }


    private static void ensureCustomFilteredUsersLoaded() {
        if (customFilteredUsers != null && customFilteredUsersData != null) {
            return;
        }
        synchronized (cacheLock) {
            if (customFilteredUsers != null && customFilteredUsersData != null) {
                return;
            }
            HashSet<Long> ids = new HashSet<>();
            HashMap<Long, CustomFilteredUser> data = new HashMap<>();
            try {
                String str = AyuConfig.INSTANCE.getCustomFilteredUsersData().String();
                CustomFilteredUser[] arr = new Gson().fromJson(str, CustomFilteredUser[].class);
                if (arr != null) {
                    for (CustomFilteredUser item : arr) {
                        if (item != null && item.id > 0L) {
                            ids.add(item.id);
                            data.put(item.id, item);
                        }
                    }
                }
            } catch (Exception ignore) {
            }
            customFilteredUsers = ids;
            customFilteredUsersData = data;
        }
    }

    private static HashSet<Long> getCustomFilteredUsers() {
        synchronized (cacheLock) {
            ensureCustomFilteredUsersLoaded();
            return customFilteredUsers;
        }
    }

    private static HashMap<Long, CustomFilteredUser> getCustomFilteredUsersDataMap() {
        synchronized (cacheLock) {
            ensureCustomFilteredUsersLoaded();
            return customFilteredUsersData;
        }
    }

    private static void saveCustomFilteredUsers(HashSet<Long> ids, HashMap<Long, CustomFilteredUser> dataMap) {
        boolean membershipChanged = !ids.equals(getCustomFilteredUsers());
        ArrayList<Long> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        ArrayList<CustomFilteredUser> out = new ArrayList<>(sorted.size());
        HashMap<Long, CustomFilteredUser> resultMap = new HashMap<>(sorted.size());
        for (Long id : sorted) {
            if (id == null || id <= 0L) {
                continue;
            }
            CustomFilteredUser user = dataMap.get(id);
            if (user == null) {
                user = new CustomFilteredUser();
                user.id = id;
            }
            out.add(user);
            resultMap.put(user.id, user);
        }
        String str = new Gson().toJson(out.toArray(new CustomFilteredUser[0]));
        AyuConfig.INSTANCE.getCustomFilteredUsersData().setConfigString(str);
        synchronized (cacheLock) {
            customFilteredUsers = new HashSet<>(resultMap.keySet());
            customFilteredUsersData = resultMap;
        }
        if (membershipChanged) {
            invalidateFilteredCache();
        }
    }

    public static ArrayList<Long> getCustomFilteredUsersList() {
        ArrayList<Long> list = new ArrayList<>(getCustomFilteredUsers());
        Collections.sort(list);
        return list;
    }

    public static ArrayList<CustomFilteredUser> getCustomFilteredUsersDataList() {
        HashMap<Long, CustomFilteredUser> map = getCustomFilteredUsersDataMap();
        ArrayList<Long> sortedIds = getCustomFilteredUsersList();
        ArrayList<CustomFilteredUser> list = new ArrayList<>(sortedIds.size());
        for (Long id : sortedIds) {
            if (id == null || id <= 0L) {
                continue;
            }
            CustomFilteredUser item = map.get(id);
            if (item == null) {
                item = new CustomFilteredUser();
                item.id = id;
            }
            list.add(item);
        }
        return list;
    }

    public static CustomFilteredUser getCustomFilteredUser(long userId) {
        if (userId <= 0L) {
            return null;
        }
        return getCustomFilteredUsersDataMap().get(userId);
    }

    public static void setCustomFilteredUsersData(ArrayList<CustomFilteredUser> users) {
        HashSet<Long> ids = new HashSet<>();
        HashMap<Long, CustomFilteredUser> map = new HashMap<>();
        if (users != null) {
            for (CustomFilteredUser item : users) {
                if (item != null && item.id > 0L) {
                    ids.add(item.id);
                    map.put(item.id, item);
                }
            }
        }
        saveCustomFilteredUsers(ids, map);
    }

    public static void setCustomFilteredUsers(ArrayList<Long> ids) {
        HashSet<Long> set = new HashSet<>();
        HashMap<Long, CustomFilteredUser> current = new HashMap<>(getCustomFilteredUsersDataMap());
        HashMap<Long, CustomFilteredUser> data = new HashMap<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && id > 0L) {
                    set.add(id);
                    CustomFilteredUser item = current.get(id);
                    if (item == null) {
                        item = new CustomFilteredUser();
                        item.id = id;
                    }
                    data.put(id, item);
                }
            }
        }
        saveCustomFilteredUsers(set, data);
    }

    public static void updateCustomFilteredUserFromLocalUser(TLRPC.User user) {
        if (user == null || user.id <= 0L || !getCustomFilteredUsers().contains(user.id)) {
            return;
        }
        HashSet<Long> ids = new HashSet<>(getCustomFilteredUsers());
        HashMap<Long, CustomFilteredUser> map = new HashMap<>(getCustomFilteredUsersDataMap());
        CustomFilteredUser current = map.get(user.id);
        if (current == null) {
            current = new CustomFilteredUser();
            current.id = user.id;
        }
        boolean changed = false;
        String username = UserObject.getPublicUsername(user);
        String displayName = UserObject.getUserName(user);
        if (user.access_hash != 0L && current.accessHash != user.access_hash) {
            current.accessHash = user.access_hash;
            changed = true;
        }
        if (!TextUtils.equals(current.username, username)) {
            current.username = username;
            changed = true;
        }
        if (!TextUtils.equals(current.displayName, displayName)) {
            current.displayName = displayName;
            changed = true;
        }
        if (changed) {
            map.put(user.id, current);
            saveCustomFilteredUsers(ids, map);
        }
    }


    private static RegexFilter toRow(FilterModel m, Long dialogId) {
        RegexFilter row = new RegexFilter();
        row.id = m.id;
        row.text = m.regex;
        row.dialogId = dialogId;
        row.enabled = m.enabled;
        row.caseInsensitive = m.caseInsensitive;
        row.reversed = m.reversed;
        return row;
    }

    private static void insertFilterRow(FilterModel m, Long dialogId) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) return;
        m.ensureId();
        try {
            dao.insert(toRow(m, dialogId));
        } catch (Exception e) {
            FileLog.e("AyuFilter.insertFilterRow", e);
        }
    }


    public static class FilterModel {
        @Expose
        public String id = UUID.randomUUID().toString();
        @Expose
        public String regex;
        @Expose
        public boolean caseInsensitive;
        @Expose
        public boolean enabled = true;
        @Expose
        public boolean reversed;
        public Pattern pattern;

        public ArrayList<Long> enabledGroups;
        public ArrayList<Long> disabledGroups;

        public boolean ensureId() {
            if (!TextUtils.isEmpty(id)) {
                return false;
            }
            id = UUID.randomUUID().toString();
            return true;
        }

        public void buildPattern() {
            var flags = Pattern.MULTILINE;
            if (caseInsensitive) {
                flags |= Pattern.CASE_INSENSITIVE;
            }
            try {
                pattern = Pattern.compile(regex, flags);
            } catch (Exception e) {
                pattern = null;
                FileLog.e(e);
            }
        }

        public boolean migrateFromLegacy(long dialogId) {
            if (enabledGroups == null && disabledGroups == null) {
                return false;
            }
            boolean defaultEnabled = enabledGroups != null && enabledGroups.contains(0L);
            if (defaultEnabled) {
                enabled = disabledGroups == null || !disabledGroups.contains(dialogId);
            } else {
                enabled = enabledGroups != null && enabledGroups.contains(dialogId);
            }
            enabledGroups = null;
            disabledGroups = null;
            return true;
        }
    }

    public static class ChatFilterEntry {
        @Expose
        public long dialogId;
        @Expose
        public ArrayList<FilterModel> filters;
    }

    public static class ExcludedFilterEntry {
        @Expose
        public long dialogId;
        @Expose
        public String filterId;
    }

    public static class CustomFilteredUser {
        @Expose
        public long id;
        @Expose
        public long accessHash;
        @Expose
        public String username;
        @Expose
        public String displayName;
    }
}
