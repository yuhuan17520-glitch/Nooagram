package tw.nekomimi.nekogram.filters;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.radolyn.ayugram.database.dao.RegexFilterDao;
import com.radolyn.ayugram.database.FilterPrefsMigrator;
import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import com.radolyn.ayugram.AyuOptions;
import com.radolyn.ayugram.config.CellGroup;
import com.radolyn.ayugram.config.cell.AbstractConfigCell;
import com.radolyn.ayugram.config.cell.ConfigCellCheckBox;
import com.radolyn.ayugram.config.cell.ConfigCellCustom;
import com.radolyn.ayugram.config.cell.ConfigCellDivider;
import com.radolyn.ayugram.config.cell.ConfigCellHeader;
import com.radolyn.ayugram.config.cell.ConfigCellTextCheck;
import com.radolyn.ayugram.settings.BaseNekoXSettingsActivity;
import com.radolyn.ayugram.ui.cells.FiltersChatCell;
import com.radolyn.ayugram.utils.AyuHttpClient;
import com.radolyn.ayugram.AyuConfig;

public class RegexFiltersSettingActivity extends BaseNekoXSettingsActivity {

    private static final int VIEW_TYPE_CHAT_FILTER = 100;

    private static class DialogFilterItem {
        long dialogId;
        int chatFiltersCount;
        int excludedSharedCount;
    }

    private static class DialogFilterCell extends ConfigCellCustom {
        final DialogFilterItem item;

        DialogFilterCell(DialogFilterItem item) {
            super("RegexFiltersChat_" + item.dialogId, VIEW_TYPE_CHAT_FILTER, true);
            this.item = item;
        }
    }

    private ListAdapter listAdapter;
    private ArrayList<DialogFilterItem> dialogFilterItems = new ArrayList<>();
    private int sharedFilterCount;
    private int rowsGeneration;
    private final CellGroup cellGroup = new CellGroup(this);
    private final AbstractConfigCell filtersOptionHeaderRow = new ConfigCellHeader(getString(R.string.General));
    private final AbstractConfigCell regexFiltersEnabledRow = new ConfigCellTextCheck(AyuConfig.INSTANCE.getRegexFiltersEnabled(), null, getString(R.string.RegexFiltersEnabled));
    private final AbstractConfigCell regexFiltersEnableInChatsRow = new ConfigCellTextCheck(AyuConfig.INSTANCE.getRegexFiltersEnableInChats(), null, getString(R.string.RegexFiltersEnableInChats));
    private final AbstractConfigCell ignoreBlockedRow = new ConfigCellTextCheck(AyuOptions.ignoreBlocked, null, getString(R.string.IgnoreBlocked));
    private final AbstractConfigCell filtersOptionDividerRow = new ConfigCellDivider();
    private final AbstractConfigCell regexFiltersMaskMessagesRow = new ConfigCellCustom("RegexFiltersMaskMessagesShort", CellGroup.ITEM_TYPE_TEXT_CHECK, true);
    private final AbstractConfigCell regexFiltersMaskMessagesInfoRow = new ConfigCellCustom("RegexFiltersMaskMessagesAbout", CellGroup.ITEM_TYPE_TEXT, false);
    private final AbstractConfigCell regexFiltersHideOnlyMatchedRow = new ConfigCellCustom("RegexFiltersHideOnlyMatchedShort", CellGroup.ITEM_TYPE_TEXT_CHECK, true);
    private final AbstractConfigCell regexFiltersHideOnlyMatchedInfoRow = new ConfigCellCustom("RegexFiltersHideOnlyMatchedAbout", CellGroup.ITEM_TYPE_TEXT, false);
    private final AbstractConfigCell filtersHeaderRow = new ConfigCellHeader(getString(R.string.RegexFiltersGlobalHeader));
    private final AbstractConfigCell sharedFiltersPageRow = new ConfigCellCustom("RegexFiltersSharedHeader", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true);
    private final AbstractConfigCell userFiltersPageRow = new ConfigCellCustom("ShadowBan", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true);
    private final AbstractConfigCell dividerRow = new ConfigCellDivider();
    private final AbstractConfigCell chatFiltersHeaderRow = new ConfigCellHeader(getString(R.string.RegexFiltersChatHeader));

    public RegexFiltersSettingActivity() {
        rebuildRows();
    }

    private void addCell(AbstractConfigCell cell) {
        cell.bindCellGroup(cellGroup);
        cellGroup.rows.add(cell);
    }

    private void rebuildRows() {
        cellGroup.rows.clear();
        addCell(filtersOptionHeaderRow);
        addCell(regexFiltersEnabledRow);
        addCell(regexFiltersEnableInChatsRow);
        addCell(ignoreBlockedRow);
        addCell(filtersOptionDividerRow);
        addCell(regexFiltersMaskMessagesRow);
        addCell(regexFiltersMaskMessagesInfoRow);
        if (AyuConfig.INSTANCE.getRegexFiltersMaskMessages().Bool()) {
            addCell(regexFiltersHideOnlyMatchedRow);
            addCell(regexFiltersHideOnlyMatchedInfoRow);
        }
        addCell(filtersHeaderRow);
        addCell(sharedFiltersPageRow);
        addCell(userFiltersPageRow);
        addCell(dividerRow);
        var chatEntries = dialogFilterItems;
        if (!chatEntries.isEmpty()) {
            addCell(chatFiltersHeaderRow);
            for (DialogFilterItem item : chatEntries) {
                addCell(new DialogFilterCell(item));
            }
        }
        addRowsToMap(cellGroup);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onResume() {
        refreshRows();
        super.onResume();
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @SuppressWarnings("NewApi")
    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();
        setupLongClickListener();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(AyuOptions.ignoreBlocked.getKey())) {
                if ((boolean) newValue && !AyuConfig.INSTANCE.getRegexFiltersEnabled().Bool()) {
                    AyuConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(true);
                    if (listAdapter != null) {
                        listAdapter.notifyDataSetChanged();
                    }
                }
            }
            AyuFilter.invalidateFilteredCache();
        };

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem menuItem = menu.addItem(0, R.drawable.ic_ab_other);
        menuItem.addSubItem(1, R.drawable.msg_user_search, getString(R.string.SelectChat));
        menuItem.addColoredGap();
        menuItem.addSubItem(2, R.drawable.msg_archive, getString(R.string.RegexFiltersImport));
        menuItem.addSubItem(3, R.drawable.msg_unarchive, getString(R.string.RegexFiltersExport));
        menuItem.addColoredGap();
        ActionBarMenuSubItem clearSub = menuItem.addSubItem(4, R.drawable.msg_clear, getString(R.string.ClearRegexFilters));
        int red = Theme.getColor(Theme.key_text_RedRegular);
        clearSub.setColors(red, red);
        clearSub.setSelectorColor(Theme.multAlpha(red, .12f));

        return v;
    }

    @Override
    protected void onActionBarItemClick(int id) {
        if (id == 1) {
            presentFragment(createSelectChatActivity());
        } else if (id == 2) {
            Context context = getContext();
            if (context != null) {
                showImportSourceChooser(context);
            }
        } else if (id == 3) {
            Context context = getContext();
            if (context != null) {
                showExportSourceChooser(context);
            }
        } else if (id == 4) {
            new AlertDialog.Builder(getContext(), getResourceProvider())
                    .setTitle(getString(R.string.ClearRegexFilters))
                    .setMessage(getString(R.string.ClearRegexFiltersAlertMessage))
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
                        Utilities.globalQueue.postRunnable(() -> {
                            AyuFilter.clearAllFilters();
                            AndroidUtilities.runOnUIThread(() -> {
                                if (!isFinished && !isFinishing() && getParentActivity() != null) refreshRows();
                            });
                        });
                    })
                    .makeRed(AlertDialog.BUTTON_POSITIVE)
                    .show();
        }
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        AbstractConfigCell row = cellGroup.rows.get(position);
        if (row == regexFiltersMaskMessagesRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            AyuConfig.INSTANCE.getRegexFiltersMaskMessages().setConfigBool(enabled);
            AyuFilter.invalidateFilteredCache();
            updateHideOnlyMatchedRowsAnimated(enabled);
        } else if (row == regexFiltersHideOnlyMatchedRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            AyuConfig.INSTANCE.getRegexFiltersHideOnlyMatched().setConfigBool(enabled);
            AyuFilter.invalidateFilteredCache();
        } else if (row == sharedFiltersPageRow) {
            presentFragment(new RegexSharedFiltersListActivity());
        } else if (row == userFiltersPageRow) {
            presentFragment(new ShadowBanListActivity());
        } else if (row instanceof DialogFilterCell dialogFilterCell) {
            presentFragment(new RegexChatFiltersListActivity(dialogFilterCell.item.dialogId));
        }
    }

    @NonNull
    private DialogsActivity createSelectChatActivity() {
        Bundle b = new Bundle();
        b.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_REGEX_FILTER);
        b.putBoolean("onlySelect", true);
        b.putBoolean("canSelectTopics", false);
        b.putBoolean("allowSwitchAccount", false);
        b.putBoolean("checkCanWrite", false);
        DialogsActivity dialogsActivity = new DialogsActivity(b);
        dialogsActivity.setCurrentAccount(currentAccount);
        dialogsActivity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null && !dids.isEmpty()) {
                long dialogId = ((MessagesStorage.TopicKey) dids.get(0)).dialogId;
                presentFragment(new RegexChatFiltersListActivity(dialogId), true);
            }
            return true;
        });
        return dialogsActivity;
    }

    private int getShadowBanCount() {
        return AyuFilter.getBlockedChannelsCount() + AyuFilter.getCustomFilteredUsersList().size();
    }

    private String buildExportJson() {
        TransferData data = new TransferData();
        data.version = 2;
        data.filters = new ArrayList<>();
        data.peers = new HashMap<>();
        MessagesController messagesController = getMessagesController();
        RegexFilterDao.Snapshot snapshot = AyuFilter.getPersistedFilters();
        for (RegexFilter model : snapshot.filters) {
            data.filters.add(buildBackupFilter(model));
            if (model.dialogId != null) {
                addPeerUsername(data.peers, model.dialogId, messagesController);
            }
        }
        data.exclusions = new ArrayList<>();
        for (RegexFilterGlobalExclusion entry : snapshot.exclusions) {
            if (entry == null || TextUtils.isEmpty(entry.filterId) || entry.dialogId == 0L) {
                continue;
            }
            BackupExclusion exclusion = new BackupExclusion();
            exclusion.dialogId = entry.dialogId;
            exclusion.filterId = entry.filterId;
            data.exclusions.add(exclusion);
            addPeerUsername(data.peers, entry.dialogId, messagesController);
        }
        data.customFilteredUsersData = AyuFilter.getCustomFilteredUsersDataList();
        data.blockedChannelsData = new Gson().fromJson(AyuConfig.INSTANCE.getBlockedChannelsData().String(),
                new com.google.gson.reflect.TypeToken<ArrayList<Long>>(){}.getType());
        if (data.blockedChannelsData != null) {
            for (Long dialogId : data.blockedChannelsData) {
                if (dialogId != null) addPeerUsername(data.peers, dialogId, messagesController);
            }
        }
        return new Gson().toJson(data);
    }

    private void showExportSourceChooser(Context context) {
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.FiltersExportTitle))
                .setItems(new CharSequence[]{
                        getString(R.string.FiltersExportClipboard),
                        getString(R.string.FiltersExportURL)
                }, (dialog, which) -> {
                    if (which == 0) {
                        exportToClipboard();
                    } else {
                        exportToUrl();
                    }
                })
                .show();
    }

    private void exportToClipboard() {
        prepareExport(false);
    }

    private void exportToUrl() {
        prepareExport(true);
    }

    private void prepareExport(boolean url) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String json = buildExportJson();
                AndroidUtilities.runOnUIThread(() -> {
                    if (isFinished || isFinishing() || getParentActivity() == null) return;
                    if (url) exportToUrl(json);
                    else {
                        AndroidUtilities.addToClipboard(json);
                        BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isFinished && !isFinishing() && getParentActivity() != null) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersExportError)).show();
                    }
                });
            }
        });
    }

    private void exportToUrl(String json) {
        Request request = new Request.Builder()
                .url("https://dpaste.com/api/v2/")
                .post(new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("content", json)
                        .addFormDataPart("syntax", "json")
                        .addFormDataPart("title", "NagramXF Filters")
                        .build())
                .build();
        AyuHttpClient.INSTANCE.getInstance().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                AndroidUtilities.runOnUIThread(() ->
                        BulletinFactory.of(RegexFiltersSettingActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.FiltersToastFailPublish))
                                .show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                String location;
                try (ResponseBody ignored = response.body()) {
                    location = response.header("Location");
                }
                final String exportUrl = TextUtils.isEmpty(location) ? null : (location.endsWith(".txt") ? location : location + ".txt");
                AndroidUtilities.runOnUIThread(() -> {
                    if (TextUtils.isEmpty(exportUrl)) {
                        BulletinFactory.of(RegexFiltersSettingActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.FiltersToastFailPublish))
                                .show();
                        return;
                    }
                    AndroidUtilities.addToClipboard(exportUrl);
                    BulletinFactory.of(RegexFiltersSettingActivity.this).createCopyLinkBulletin().show();
                });
            }
        });
    }

    private boolean isKnownDialog(long dialogId) {
        if (dialogId > 0) {
            return MessagesController.getInstance(UserConfig.selectedAccount).getUser(dialogId) != null;
        }
        return MessagesController.getInstance(UserConfig.selectedAccount).getChat(-dialogId) != null;
    }

    private void resolveUnknownPeers(HashMap<Long, String> peers) {
        MessagesController messagesController = getMessagesController();
        for (var entry : peers.entrySet()) {
            Long dialogId = entry.getKey();
            String rawUsername = entry.getValue();
            if (dialogId == null || dialogId == 0L || TextUtils.isEmpty(rawUsername)) {
                continue;
            }
            if (isKnownDialog(dialogId)) {
                continue;
            }
            String username = rawUsername.startsWith("@") ? rawUsername.substring(1) : rawUsername;
            if (TextUtils.isEmpty(username)) {
                continue;
            }
            messagesController.getUserNameResolver().resolve(username, peerId -> {
                if (peerId != null && peerId != 0L) {
                    AndroidUtilities.runOnUIThread(this::refreshRows);
                }
            });
        }
    }


    private static class ParsedImport {
        ArrayList<AyuFilter.FilterModel> sharedIncoming;
        ArrayList<AyuFilter.ChatFilterEntry> chatsIncoming;
        ArrayList<AyuFilter.CustomFilteredUser> customFilteredUsersIncoming;
        ArrayList<AyuFilter.ExcludedFilterEntry> exclusionsIncoming;
        ArrayList<Long> blockedChannelsIncoming;
        HashMap<Long, String> peersIncoming;

        boolean hasAnyData() {
            return (sharedIncoming != null && !sharedIncoming.isEmpty())
                    || (chatsIncoming != null && !chatsIncoming.isEmpty())
                    || (customFilteredUsersIncoming != null && !customFilteredUsersIncoming.isEmpty())
                    || (blockedChannelsIncoming != null && !blockedChannelsIncoming.isEmpty())
                    || (exclusionsIncoming != null && !exclusionsIncoming.isEmpty());
        }
    }

    private ParsedImport parseImportJson(String rawJson) {
        if (TextUtils.isEmpty(rawJson)) return null;
        String json = rawJson.trim();
        long selfUserId = getUserConfig().getClientUserId();
        ParsedImport out = new ParsedImport();
        try {
            TransferData data;
            if (json.startsWith("[")) {
                RegexFilterDao.Snapshot legacy = FilterPrefsMigrator.parseLegacy(json, null, null);
                data = new TransferData();
                data.filters = new ArrayList<>();
                for (RegexFilter row : legacy.filters) data.filters.add(buildBackupFilter(row));
                data.exclusions = new ArrayList<>();
                for (RegexFilterGlobalExclusion row : legacy.exclusions) {
                    BackupExclusion exclusion = new BackupExclusion();
                    exclusion.dialogId = row.dialogId;
                    exclusion.filterId = row.filterId;
                    data.exclusions.add(exclusion);
                }
            } else {
                data = new Gson().fromJson(json, TransferData.class);
            }
            if (data != null) {
                if (data.filters != null) {
                    out.sharedIncoming = new ArrayList<>();
                    out.chatsIncoming = new ArrayList<>();
                    HashMap<Long, AyuFilter.ChatFilterEntry> chatMap = new HashMap<>();
                    for (BackupFilter bf : data.filters) {
                        if (bf == null || TextUtils.isEmpty(bf.text)) continue;
                        AyuFilter.FilterModel m = new AyuFilter.FilterModel();
                        m.id = bf.id;
                        m.regex = bf.text;
                        m.enabled = bf.enabled;
                        m.caseInsensitive = bf.caseInsensitive;
                        m.reversed = bf.reversed;
                        m.ensureId();
                        if (bf.dialogId == null) {
                            out.sharedIncoming.add(m);
                        } else {
                            long did = bf.dialogId;
                            AyuFilter.ChatFilterEntry entry = chatMap.get(did);
                            if (entry == null) {
                                entry = new AyuFilter.ChatFilterEntry();
                                entry.dialogId = did;
                                entry.filters = new ArrayList<>();
                                chatMap.put(did, entry);
                                out.chatsIncoming.add(entry);
                            }
                            entry.filters.add(m);
                        }
                    }
                }
                if (data.exclusions != null) {
                    out.exclusionsIncoming = new ArrayList<>();
                    for (BackupExclusion be : data.exclusions) {
                        if (be == null || TextUtils.isEmpty(be.filterId)) continue;
                        AyuFilter.ExcludedFilterEntry entry = new AyuFilter.ExcludedFilterEntry();
                        entry.dialogId = be.dialogId;
                        entry.filterId = be.filterId;
                        out.exclusionsIncoming.add(entry);
                    }
                }
                if (data.peers != null && !data.peers.isEmpty()) {
                    out.peersIncoming = new HashMap<>(data.peers);
                }
                if (data.blockedChannelsData != null) {
                    HashSet<Long> ids = new HashSet<>();
                    for (Long id : data.blockedChannelsData) {
                        if (id != null && id < 0L) ids.add(id);
                    }
                    out.blockedChannelsIncoming = new ArrayList<>(ids);
                }
                if (data.customFilteredUsersData != null) {
                    out.customFilteredUsersIncoming = new ArrayList<>();
                    for (AyuFilter.CustomFilteredUser user : data.customFilteredUsersData) {
                        if (user == null || user.id <= 0L || user.id == selfUserId) {
                            continue;
                        }
                        AyuFilter.CustomFilteredUser normalized = new AyuFilter.CustomFilteredUser();
                        normalized.id = user.id;
                        normalized.accessHash = user.accessHash;
                        normalized.username = user.username;
                        if (!TextUtils.isEmpty(user.displayName)) {
                            String displayName = user.displayName.trim();
                            if (!TextUtils.isEmpty(displayName)) {
                                normalized.displayName = displayName;
                            }
                        }
                        out.customFilteredUsersIncoming.add(normalized);
                    }
                } else if (data.customFilteredUsers != null) {
                    out.customFilteredUsersIncoming = new ArrayList<>();
                    for (Long userId : data.customFilteredUsers) {
                        if (userId == null || userId <= 0L || userId == selfUserId) {
                            continue;
                        }
                        AyuFilter.CustomFilteredUser normalized = new AyuFilter.CustomFilteredUser();
                        normalized.id = userId;
                        out.customFilteredUsersIncoming.add(normalized);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        return out.hasAnyData() ? out : null;
    }

    private FiltersImportBottomSheet.Summary buildImportSummary(ParsedImport parsed) {
        FiltersImportBottomSheet.Summary s = new FiltersImportBottomSheet.Summary();
        if (parsed == null) return s;
        RegexFilterDao.ImportResult merged = AyuFilter.importFilters(parsed.sharedIncoming,
                parsed.chatsIncoming, parsed.exclusionsIncoming, false);
        s.newFilters = merged.newFilters;
        s.newChatFilters = merged.newChatFilters;
        s.updatedFilters = merged.updatedFilters;
        s.newExclusions = merged.newExclusions;
        if (parsed.blockedChannelsIncoming != null) {
            HashSet<Long> existing = new HashSet<>();
            Long[] ids = new Gson().fromJson(AyuConfig.INSTANCE.getBlockedChannelsData().String(), Long[].class);
            if (ids != null) java.util.Collections.addAll(existing, ids);
            for (Long id : parsed.blockedChannelsIncoming) {
                if (existing.add(id)) s.newShadowBans++;
            }
        }
        if (parsed.customFilteredUsersIncoming != null) {
            long selfUserId = getUserConfig().getClientUserId();
            HashMap<Long, AyuFilter.CustomFilteredUser> existing = new HashMap<>();
            for (AyuFilter.CustomFilteredUser u : AyuFilter.getCustomFilteredUsersDataList()) {
                if (u != null && u.id > 0L) existing.put(u.id, u);
            }
            for (AyuFilter.CustomFilteredUser in : parsed.customFilteredUsersIncoming) {
                if (in == null || in.id <= 0L || in.id == selfUserId) continue;
                AyuFilter.CustomFilteredUser ex = existing.get(in.id);
                if (ex == null) {
                    s.newShadowBans++;
                } else if (!TextUtils.equals(ex.username, in.username)
                        || !TextUtils.equals(ex.displayName, in.displayName)
                        || ex.accessHash != in.accessHash) {
                    s.updatedFilters++;
                }
            }
        }
        if (parsed.peersIncoming != null) {
            for (var entry : parsed.peersIncoming.entrySet()) {
                Long did = entry.getKey();
                if (did == null || did == 0L) continue;
                if (!isKnownDialog(did)) s.peersToResolve++;
            }
        }
        return s;
    }

    private void applyImport(ParsedImport parsed) {
        if (parsed == null) return;
        long selfUserId = getUserConfig().getClientUserId();
        AyuFilter.importFilters(parsed.sharedIncoming, parsed.chatsIncoming, parsed.exclusionsIncoming, true);
        if (parsed.customFilteredUsersIncoming != null && !parsed.customFilteredUsersIncoming.isEmpty()) {
            HashMap<Long, AyuFilter.CustomFilteredUser> merged = new HashMap<>();
            for (AyuFilter.CustomFilteredUser existing : AyuFilter.getCustomFilteredUsersDataList()) {
                if (existing == null || existing.id <= 0L || existing.id == selfUserId) {
                    continue;
                }
                merged.put(existing.id, existing);
            }
            for (AyuFilter.CustomFilteredUser incoming : parsed.customFilteredUsersIncoming) {
                if (incoming == null || incoming.id <= 0L || incoming.id == selfUserId) {
                    continue;
                }
                AyuFilter.CustomFilteredUser target = merged.get(incoming.id);
                if (target == null) {
                    target = new AyuFilter.CustomFilteredUser();
                    target.id = incoming.id;
                }
                if (incoming.accessHash != 0L) {
                    target.accessHash = incoming.accessHash;
                }
                String username = incoming.username;
                if (!TextUtils.isEmpty(username)) {
                    target.username = username;
                }
                if (!TextUtils.isEmpty(incoming.displayName)) {
                    String displayName = incoming.displayName.trim();
                    if (!TextUtils.isEmpty(displayName)) {
                        target.displayName = displayName;
                    }
                }
                merged.put(target.id, target);
            }
            AyuFilter.setCustomFilteredUsersData(new ArrayList<>(merged.values()));
        }
        if (parsed.blockedChannelsIncoming != null) {
            for (Long id : parsed.blockedChannelsIncoming) AyuFilter.blockPeer(id);
        }
    }

    private void showImportPreview(ParsedImport parsed, FiltersImportBottomSheet.Summary summary) {
        if (parsed == null || !parsed.hasAnyData()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportNoChanges)).show();
            return;
        }
        if (summary.isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportNoChanges)).show();
            return;
        }
        new FiltersImportBottomSheet(this, summary, () -> {
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    applyImport(parsed);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (isFinished || isFinishing() || getParentActivity() == null) return;
                        refreshRows();
                        if (parsed.peersIncoming != null) resolveUnknownPeers(parsed.peersIncoming);
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.RegexFiltersImportSuccess)).show();
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(this::showImportError);
                }
            });
        }).show();
    }

    private void prepareImport(String json) {
        prepareImport(json, null);
    }

    private void prepareImport(String json, String url) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                ParsedImport parsed = parseImportJson(json);
                if (parsed == null) {
                    AndroidUtilities.runOnUIThread(this::showImportError);
                    return;
                }
                FiltersImportBottomSheet.Summary summary = buildImportSummary(parsed);
                AndroidUtilities.runOnUIThread(() -> {
                    if (isFinished || isFinishing() || getParentActivity() == null) return;
                    if (url != null) AyuConfig.INSTANCE.getRegexFiltersLastImportLink().setConfigString(url);
                    showImportPreview(parsed, summary);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(this::showImportError);
            }
        });
    }

    private void showImportError() {
        if (!isFinished && !isFinishing() && getParentActivity() != null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError)).show();
        }
    }

    private void showImportSourceChooser(Context context) {
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.RegexFiltersImportSourceTitle))
                .setItems(new CharSequence[]{
                        getString(R.string.RegexFiltersImportFromClipboard),
                        getString(R.string.RegexFiltersImportFromURL)
                }, (dialog, which) -> {
                    if (which == 0) {
                        importFromClipboard(context);
                    } else {
                        importFromUrl(context);
                    }
                })
                .show();
    }

    private void importFromClipboard(Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
        }
        if (text == null || TextUtils.isEmpty(text.toString().trim())) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError)).show();
            return;
        }
        prepareImport(text.toString());
    }

    private void importFromUrl(Context context) {
        FrameLayout container = new FrameLayout(context);
        EditText editText = new EditText(context);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        editText.setTextSize(16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        editText.setHint(getString(R.string.RegexFiltersImportUrlHint));
        editText.setSingleLine(true);
        editText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence clipboardText = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            clipboardText = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
        }
        String initialLink = clipboardText != null ? clipboardText.toString().trim() : "";
        String lastLink = AyuConfig.INSTANCE.getRegexFiltersLastImportLink().String();
        if (!initialLink.contains(".txt") && !initialLink.contains("github") && !initialLink.contains("bin") && !initialLink.contains("paste")) {
            initialLink = lastLink;
        }
        if (!TextUtils.isEmpty(initialLink)) {
            editText.setText(initialLink);
            editText.setSelection(editText.getText().length());
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = AndroidUtilities.dp(24);
        lp.rightMargin = AndroidUtilities.dp(24);
        lp.topMargin = AndroidUtilities.dp(6);
        container.addView(editText, lp);

        AlertDialog alert = new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.RegexFiltersImportUrlTitle))
                .setView(container)
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.RegexFiltersImportUrlAction), (dialog, which) -> {
                    String url = editText.getText() != null ? editText.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(url)) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed)).show();
                        return;
                    }
                    fetchImportFromUrl(url);
                })
                .create();
        alert.show();
        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }

    private void fetchImportFromUrl(String url) {
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER, getResourceProvider());
        progress.setCanCancel(false);
        progress.show();
        Request request;
        try {
            request = new Request.Builder().url(url).get().build();
        } catch (IllegalArgumentException e) {
            progress.dismiss();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed)).show();
            return;
        }
        AyuHttpClient.INSTANCE.getInstance().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    if (isFinished || isFinishing() || getParentActivity() == null) return;
                    BulletinFactory.of(RegexFiltersSettingActivity.this)
                            .createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed))
                            .show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                String body = null;
                try (ResponseBody rb = response.body()) {
                    if (response.isSuccessful() && rb != null) {
                        body = rb.string();
                    }
                } catch (IOException ignored) {
                }
                final String finalBody = body;
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    if (isFinished || isFinishing() || getParentActivity() == null) return;
                    if (TextUtils.isEmpty(finalBody)) {
                        BulletinFactory.of(RegexFiltersSettingActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed))
                                .show();
                        return;
                    }
                    prepareImport(finalBody, url);
                });
            }
        });
    }


    private ArrayList<DialogFilterItem> getDialogFilterItems() {
        HashMap<Long, DialogFilterItem> map = new HashMap<>();
        ArrayList<AyuFilter.ChatFilterEntry> chatEntries = AyuFilter.getChatFilterEntries();
        if (chatEntries != null) {
            for (AyuFilter.ChatFilterEntry entry : chatEntries) {
                if (entry == null || !isKnownDialog(entry.dialogId)) {
                    continue;
                }
                DialogFilterItem item = map.computeIfAbsent(entry.dialogId, did -> {
                    DialogFilterItem newItem = new DialogFilterItem();
                    newItem.dialogId = did;
                    return newItem;
                });
                item.chatFiltersCount = entry.filters != null ? entry.filters.size() : 0;
            }
        }
        for (AyuFilter.ExcludedFilterEntry entry : AyuFilter.getExcludedFilterEntries()) {
            if (entry == null || !isKnownDialog(entry.dialogId)) {
                continue;
            }
            DialogFilterItem item = map.computeIfAbsent(entry.dialogId, did -> {
                DialogFilterItem newItem = new DialogFilterItem();
                newItem.dialogId = did;
                return newItem;
            });
            item.excludedSharedCount++;
        }
        ArrayList<DialogFilterItem> items = new ArrayList<>(map.values());
        items.sort((o1, o2) -> Long.compare(o1.dialogId, o2.dialogId));
        return items;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshRows() {
        final int generation = ++rowsGeneration;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<DialogFilterItem> items = getDialogFilterItems();
            int sharedCount = AyuFilter.getRegexFilters().size();
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != rowsGeneration || isFinished || isFinishing() || getParentActivity() == null) return;
                dialogFilterItems = items;
                sharedFilterCount = sharedCount;
                rebuildRows();
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
            });
        });
    }

    private void updateHideOnlyMatchedRowsAnimated(boolean maskEnabled) {
        if (listAdapter == null) {
            return;
        }
        int infoIndex = cellGroup.rows.indexOf(regexFiltersMaskMessagesInfoRow);
        if (infoIndex < 0) {
            return;
        }
        int insertPos = infoIndex + 1;
        boolean currentlyShown = cellGroup.rows.contains(regexFiltersHideOnlyMatchedRow);
        if (maskEnabled && !currentlyShown) {
            regexFiltersHideOnlyMatchedRow.bindCellGroup(cellGroup);
            regexFiltersHideOnlyMatchedInfoRow.bindCellGroup(cellGroup);
            cellGroup.rows.add(insertPos, regexFiltersHideOnlyMatchedInfoRow);
            cellGroup.rows.add(insertPos, regexFiltersHideOnlyMatchedRow);
            addRowsToMap(cellGroup);
            listAdapter.notifyItemRangeInserted(insertPos, 2);
        } else if (!maskEnabled && currentlyShown) {
            int removeIndex = cellGroup.rows.indexOf(regexFiltersHideOnlyMatchedRow);
            cellGroup.rows.remove(regexFiltersHideOnlyMatchedRow);
            cellGroup.rows.remove(regexFiltersHideOnlyMatchedInfoRow);
            addRowsToMap(cellGroup);
            listAdapter.notifyItemRangeRemoved(removeIndex, 2);
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        return super.onItemLongClick(view, position, x, y);
    }

    private void setupLongClickListener() {
        listView.setOnItemLongClickListener((view, position, x, y) -> {
            if (isDialogFilterRow(position)) {
                return false;
            }
            if (onItemLongClick(view, position, x, y)) {
                return true;
            }
            if (position < 0 || position >= cellGroup.rows.size()) {
                return false;
            }
            if (cellGroup.rows.get(position) instanceof ConfigCellCheckBox) {
                return true;
            }
            String prefix = getSettingsPrefix();
            if (prefix == null || listAdapter == null) {
                return false;
            }
            RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(position);
            if (holder != null && listAdapter.isEnabled(holder)) {
                showDefaultLongClickOptions(view, prefix, position);
                return true;
            }
            return false;
        });
    }

    private boolean isDialogFilterRow(int position) {
        return position >= 0 && position < cellGroup.rows.size() && cellGroup.rows.get(position) instanceof DialogFilterCell;
    }

    @Override
    public String getTitle() {
        return getString(R.string.RegexFilters);
    }

    @Override
    protected String getSettingsPrefix() {
        return "regexfilters";
    }

    @Override
    protected void styleTextInfoPrivacyCell(TextInfoPrivacyCell cell) {
        cell.setBackground(Theme.getThemedDrawable(cell.getContext(), R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
    }

    private static BackupFilter buildBackupFilter(RegexFilter model) {
        BackupFilter bf = new BackupFilter();
        bf.id = model.id;
        bf.text = model.text;
        bf.dialogId = model.dialogId;
        bf.enabled = model.enabled;
        bf.caseInsensitive = model.caseInsensitive;
        bf.reversed = model.reversed;
        return bf;
    }

    private static void addPeerUsername(HashMap<Long, String> peers, long dialogId, MessagesController messagesController) {
        if (peers == null || dialogId == 0L || peers.containsKey(dialogId)) return;
        TLObject peer = dialogId > 0
                ? messagesController.getUser(dialogId)
                : messagesController.getChat(-dialogId);
        if (peer == null) return;
        String username = DialogObject.getPublicUsername(peer);
        if (!TextUtils.isEmpty(username)) {
            peers.put(dialogId, "@" + username);
        }
    }

    private static class TransferData {
        public Integer version;
        public ArrayList<BackupFilter> filters;
        public ArrayList<BackupExclusion> exclusions;
        public HashMap<Long, String> peers;
        public ArrayList<AyuFilter.CustomFilteredUser> customFilteredUsersData;
        public ArrayList<Long> customFilteredUsers;
        public ArrayList<Long> blockedChannelsData;
    }

    private static class BackupFilter {
        public String id;
        public String text;
        public Long dialogId;
        public boolean enabled = true;
        public boolean caseInsensitive;
        public boolean reversed;
    }

    private static class BackupExclusion {
        public long dialogId;
        public String filterId;
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_CHAT_FILTER) {
                FiltersChatCell chatCell = new FiltersChatCell(mContext);
                chatCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                chatCell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return chatCell;
            }
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);
            if (row == regexFiltersMaskMessagesRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setTextAndCheck(getString(R.string.RegexFiltersMaskMessagesShort), AyuConfig.INSTANCE.getRegexFiltersMaskMessages().Bool(), false);
            } else if (row == regexFiltersMaskMessagesInfoRow) {
                TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                infoCell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                infoCell.setText(getString(R.string.RegexFiltersMaskMessagesAbout));
            } else if (row == regexFiltersHideOnlyMatchedRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setTextAndCheck(getString(R.string.RegexFiltersHideOnlyMatchedShort), AyuConfig.INSTANCE.getRegexFiltersHideOnlyMatched().Bool(), false);
            } else if (row == regexFiltersHideOnlyMatchedInfoRow) {
                TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                infoCell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                infoCell.setText(getString(R.string.RegexFiltersHideOnlyMatchedAbout));
            } else if (row == sharedFiltersPageRow) {
                TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                settingsCell.setTextAndValue(getString(R.string.RegexFiltersSharedHeader), String.valueOf(sharedFilterCount), true);
            } else if (row == userFiltersPageRow) {
                TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                settingsCell.setTextAndValue(getString(R.string.ShadowBan), String.valueOf(getShadowBanCount()), false);
            } else if (row instanceof DialogFilterCell dialogFilterCell) {
                DialogFilterItem entry = dialogFilterCell.item;
                FiltersChatCell chatCell = (FiltersChatCell) holder.itemView;
                chatCell.setDialog(entry.dialogId, entry.chatFiltersCount, entry.excludedSharedCount);
            }
        }
    }
}
