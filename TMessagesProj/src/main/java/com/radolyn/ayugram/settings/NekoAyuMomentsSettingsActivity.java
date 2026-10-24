package com.radolyn.ayugram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UndoView;

import java.util.ArrayList;

import com.radolyn.ayugram.AyuCustomSavePath;
import com.radolyn.ayugram.config.CellGroup;
import com.radolyn.ayugram.config.ConfigItem;
import com.radolyn.ayugram.AyuOptions;
import com.radolyn.ayugram.config.cell.AbstractConfigCell;
import com.radolyn.ayugram.config.cell.ConfigCellCustom;
import com.radolyn.ayugram.config.cell.ConfigCellDivider;
import com.radolyn.ayugram.config.cell.ConfigCellHeader;
import com.radolyn.ayugram.config.cell.ConfigCellTextCheck;
import com.radolyn.ayugram.config.cell.ConfigCellTextCheckIcon;
import com.radolyn.ayugram.config.cell.ConfigCellTextInput;
import tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity;
import tw.nekomimi.nekogram.helpers.TimeStringHelper;
import com.radolyn.ayugram.ui.cells.DeletedMessagesColorPickerCell;
import com.radolyn.ayugram.ui.cells.DeletedMessagesPreviewCell;
import com.radolyn.ayugram.AyuConfig;

@SuppressWarnings("unused")
public class NekoAyuMomentsSettingsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;
    private DeletedMessagesPreviewCell deletedMessagesPreviewCell;
    private DeletedMessagesColorPickerCell deletedMessagesColorPickerCell;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerAyuMoments = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuMoments)));
    private final AbstractConfigCell ghostModeRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "GhostMode", null, R.drawable.ayu_ghost, true, () -> presentFragment(new GhostModeActivity())));
    private final AbstractConfigCell regexFiltersEnabledRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "RegexFilters", null, R.drawable.menu_tag_filter, true, () -> presentFragment(new RegexFiltersSettingActivity())));
    private final AbstractConfigCell spySettingsRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "AyuSpySettings", null, R.drawable.msg_bot, true, () -> presentFragment(new NekoAyuSpySettingsActivity())));
    private final AbstractConfigCell pinnedHiderRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "NooagramPinnedHider", null, R.drawable.msg_pin, true, () -> presentFragment(new NooagramPinnedHiderActivity())));
    private final AbstractConfigCell customSectionDividerRow = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell customHeaderRow = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuMomentsCustomHeader)));
    private final AbstractConfigCell deletedMessagesPreviewRow = cellGroup.appendCell(new ConfigCellCustom("DeletedMessagesAppearancePreviewRow", ConfigCellCustom.CUSTOM_ITEM_DeletedMessagesAppearanceCard, false));
    private final AbstractConfigCell translucentDeletedMessagesRow = cellGroup.appendCell(new ConfigCellTextCheck(AyuConfig.INSTANCE.getTranslucentDeletedMessages()));
    private final AbstractConfigCell deletedMarkRow = cellGroup.appendCell(new ConfigCellCustom(AyuConfig.INSTANCE.getDeletedIconStyle().getKey(), CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true, R.string.DeletedMarkText));
    private final AbstractConfigCell deletedMarkColorRow = cellGroup.appendCell(new ConfigCellCustom(AyuConfig.INSTANCE.getDeletedIconColor().getKey(), ConfigCellCustom.CUSTOM_ITEM_DeletedMessagesColorPicker, false));
    private final AbstractConfigCell customDeletedMarkRow = cellGroup.appendCell(new ConfigCellTextInput(null, AyuConfig.INSTANCE.getCustomDeletedMark(), "", null));
    private final AbstractConfigCell dividerCustomExperimentalRow = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell localPremiumRow = cellGroup.appendCell(new ConfigCellTextCheck(AyuOptions.localPremium));
    private final AbstractConfigCell disableAdsRow = cellGroup.appendCell(new ConfigCellTextCheck(AyuOptions.disableAds));
    private final AbstractConfigCell enableCustomSavePathRow = cellGroup.appendCell(new ConfigCellTextCheck(AyuOptions.enableCustomSavePath));
    private final AbstractConfigCell customSavePathRow = cellGroup.appendCell(new ConfigCellTextInput(null,
            AyuOptions.customSavePath, getString(R.string.customSavePathHint), null,
            input -> AyuCustomSavePath.validateInput(input,
                    (String) AyuOptions.customSavePath.defaultValue)));
    private final AbstractConfigCell saveToChatSubfolderRow = cellGroup.appendCell(new ConfigCellTextCheck(AyuConfig.INSTANCE.getSaveToChatSubfolder()));
    private final AbstractConfigCell showGhostModeStatusRow = cellGroup.appendCell(new ConfigCellCustom("GhostModeStatusIndicator", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell highRefreshRateRow = cellGroup.appendCell(new ConfigCellTextCheck(
            AyuOptions.nooagramHighRefreshRate,
            getString(R.string.NooagramHighRefreshRateInfo),
            getString(R.string.NooagramHighRefreshRate)));
    private final AbstractConfigCell dividerCustomExperimentalBottomRow = cellGroup.appendCell(new ConfigCellDivider());

    public NekoAyuMomentsSettingsActivity() {
        checkDeletedMarkDetailRows();
        addRowsToMap(cellGroup);
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return "ayumoments";
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(AyuConfig.INSTANCE.getTranslucentDeletedMessages().getKey())) {
                notifyRowChanged(deletedMessagesPreviewRow);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(AyuConfig.INSTANCE.getDeletedIconStyle().getKey())) {
                checkDeletedMarkDetailRows();
                notifyRowChanged(deletedMessagesPreviewRow);
                notifyRowChanged(deletedMarkRow);
                notifyRowChanged(deletedMarkColorRow);
                notifyRowChanged(customDeletedMarkRow);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(AyuConfig.INSTANCE.getDeletedIconColor().getKey())) {
                notifyRowChanged(deletedMessagesPreviewRow);
                notifyRowChanged(deletedMarkRow);
                notifyRowChanged(deletedMarkColorRow);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(AyuConfig.INSTANCE.getCustomDeletedMark().getKey())) {
                notifyRowChanged(deletedMessagesPreviewRow);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(AyuOptions.localPremium.getKey())) {
                for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                    UserConfig userConfig = UserConfig.getInstance(account);
                    if (userConfig.isClientActivated()) {
                        org.telegram.messenger.MessagesController.getInstance(account).updatePremium(userConfig.isPremium());
                    }
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(AyuOptions.disableAds.getKey())) {
                org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.NooagramRestartRequired)).show();
                for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                    if (UserConfig.getInstance(account).isClientActivated()) {
                        org.telegram.messenger.MessagesController.getInstance(account).checkPromoInfo(true);
                    }
                }
            } else if (key.equals(AyuOptions.showGhostModeStatus.getKey())) {
                for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                    if (UserConfig.getInstance(account).isClientActivated()) {
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
                    }
                }
            } else if (key.equals(AyuOptions.nooagramHighRefreshRate.getKey())) {
                if (getParentActivity() instanceof LaunchActivity) {
                    ((LaunchActivity) getParentActivity()).applyNooagramHighRefreshRate();
                }
            }
        };

        return superView;
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        AbstractConfigCell row = cellGroup.rows.get(position);
        if (row == deletedMarkRow) {
            showDeletedMarkDialog();
            return;
        } else if (row == showGhostModeStatusRow) {
            AyuOptions.showGhostModeStatus.toggleConfigBool();
            ((TextCheckCell) view).setChecked(AyuOptions.showGhostModeStatus.Bool());
            NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
            return;
        }
        super.onCustomCellClick(view, position, x, y);
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (position == cellGroup.rows.indexOf(deletedMarkRow)) {
            ItemOptions options = makeLongClickOptions(view);
            addDefaultLongClickOptions(
                    options,
                    getSettingsPrefix(),
                    AyuConfig.INSTANCE.getDeletedIconStyle().getKey(),
                    AyuConfig.INSTANCE.getDeletedIconStyle().String()
            );
            showLongClickOptions(view, options);
            return true;
        }
        if (position == cellGroup.rows.indexOf(deletedMarkColorRow)) {
            ItemOptions options = makeLongClickOptions(view);
            addDefaultLongClickOptions(
                    options,
                    getSettingsPrefix(),
                    AyuConfig.INSTANCE.getDeletedIconColor().getKey(),
                    AyuConfig.INSTANCE.getDeletedIconColor().String()
            );
            showLongClickOptions(view, options);
            return true;
        }
        return super.onItemLongClick(view, position, x, y);
    }

    @Override
    public int getBaseGuid() {
        return 15000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.msg2_reactions2;
    }

    @Override
    public String getTitle() {
        return getString(R.string.AyuMoments);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"imageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayIcon));
        return themeDescriptions;
    }

    @Override
    public void importToRow(String key, String value, Runnable unknown) {
        ConfigItem config = getDeletedMessagesAppearanceConfigItem(key);
        if (config == null) {
            super.importToRow(key, value, unknown);
            return;
        }

        Context context = getParentActivity();
        if (context != null && value != null) {
            Object newValue = config.checkConfigFromString(value);
            if (newValue == null) {
                scrollToRow(key, unknown);
                return;
            }
            new AlertDialog.Builder(context)
                    .setTitle(getString(R.string.ImportSettings))
                    .setMessage(getString(R.string.ImportSettingsAlert))
                    .setNegativeButton(getString(R.string.Cancel), (dialogInter, i) -> scrollToRow(key, unknown))
                    .setPositiveButton(getString(R.string.Import), (dialogInter, i) -> {
                        config.changed(newValue);
                        config.saveConfig();
                        TimeStringHelper.invalidateDeletedStyle();
                        cellGroup.runCallback(key, newValue);
                        updateRows();
                        scrollToRow(key, unknown);
                    })
                    .show();
            return;
        }

        scrollToRow(key, unknown);
    }

    @Override
    public void scrollToRow(String key, Runnable unknown) {
        if (AyuConfig.INSTANCE.getDeletedIconColor().getKey().equals(key) && !cellGroup.rows.contains(deletedMarkColorRow)) {
            String rowKey = getRowKey(deletedMarkRow);
            super.scrollToRow(rowKey != null ? rowKey : key, unknown);
            return;
        }
        if (AyuConfig.INSTANCE.getCustomDeletedMark().getKey().equals(key) && !cellGroup.rows.contains(customDeletedMarkRow)) {
            String rowKey = getRowKey(deletedMarkRow);
            super.scrollToRow(rowKey != null ? rowKey : key, unknown);
            return;
        }
        super.scrollToRow(key, unknown);
    }

    private ConfigItem getDeletedMessagesAppearanceConfigItem(String key) {
        if (AyuConfig.INSTANCE.getDeletedIconStyle().getKey().equals(key)) {
            return AyuConfig.INSTANCE.getDeletedIconStyle();
        }
        if (AyuConfig.INSTANCE.getDeletedIconColor().getKey().equals(key)) {
            return AyuConfig.INSTANCE.getDeletedIconColor();
        }
        if (AyuConfig.INSTANCE.getCustomDeletedMark().getKey().equals(key)) {
            return AyuConfig.INSTANCE.getCustomDeletedMark();
        }
        return null;
    }

    private void checkDeletedMarkDetailRows() {
        boolean showColorRow = TimeStringHelper.getDeletedIconStyle() != 0;
        AbstractConfigCell rowToShow = showColorRow ? deletedMarkColorRow : customDeletedMarkRow;
        AbstractConfigCell rowToHide = showColorRow ? customDeletedMarkRow : deletedMarkColorRow;
        if (listAdapter == null) {
            cellGroup.rows.remove(rowToHide);
            if (!cellGroup.rows.contains(rowToShow)) {
                int index = cellGroup.rows.indexOf(deletedMarkRow);
                if (index >= 0) {
                    cellGroup.rows.add(index + 1, rowToShow);
                }
            }
            return;
        }
        int hiddenIndex = cellGroup.rows.indexOf(rowToHide);
        if (hiddenIndex != -1) {
            cellGroup.rows.remove(hiddenIndex);
            listAdapter.notifyItemRemoved(hiddenIndex);
        }
        int index = cellGroup.rows.indexOf(deletedMarkRow);
        if (index >= 0 && !cellGroup.rows.contains(rowToShow)) {
            cellGroup.rows.add(index + 1, rowToShow);
            listAdapter.notifyItemInserted(index + 1);
        }
        addRowsToMap(cellGroup);
    }

    private void bindDeletedMarkRow(TextSettingsCell cell) {
        cell.setTextAndValue(getString(R.string.DeletedMarkText), null, cellGroup.needSetDivider(deletedMarkRow));

        ImageView valueImageView = cell.getValueImageView();
        Drawable drawable = TimeStringHelper.getDeletedPreviewDrawable();
        int style = TimeStringHelper.getDeletedIconStyle();

        if (style == 0 || drawable == null) {
            valueImageView.setImageDrawable(null);
            valueImageView.setVisibility(View.INVISIBLE);
            valueImageView.setTranslationX(0f);
            return;
        }

        valueImageView.setImageDrawable(drawable);
        valueImageView.setVisibility(View.VISIBLE);
        valueImageView.setTranslationX(style == 3 ? -AndroidUtilities.dp(1) : 0f);
        valueImageView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_chat_inTimeText, getResourceProvider()),
                PorterDuff.Mode.SRC_IN
        ));
    }

    private void showDeletedMarkDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }

        CharSequence[] entries = new CharSequence[]{
                getString(R.string.DeletedMarkNothing),
                getString(R.string.DeletedMarkTrashBin),
                getString(R.string.DeletedMarkCross),
                getString(R.string.DeletedMarkEyeCrossed)
        };
        int checkedIndex = TimeStringHelper.getDeletedIconStyle();

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(getString(R.string.DeletedMarkText));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        builder.setView(linearLayout);

        for (int i = 0; i < entries.length; i++) {
            RadioColorCell cell = new RadioColorCell(context, getResourceProvider());
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setTag(i);
            cell.setCheckColor(
                    Theme.getColor(Theme.key_radioBackground, getResourceProvider()),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, getResourceProvider())
            );
            cell.setTextAndValue(entries[i], checkedIndex == i);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, getResourceProvider()), 2));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                Integer index = (Integer) v.getTag();
                AyuConfig.INSTANCE.getDeletedIconStyle().setConfigInt(index);
                TimeStringHelper.invalidateDeletedStyle();
                cellGroup.runCallback(AyuConfig.INSTANCE.getDeletedIconStyle().getKey(), index);
                builder.getDismissRunnable().run();
            });
        }

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void notifyRowChanged(AbstractConfigCell row) {
        if (listAdapter == null) {
            return;
        }
        int index = cellGroup.rows.indexOf(row);
        if (index >= 0) {
            listAdapter.notifyItemChanged(index);
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == ConfigCellCustom.CUSTOM_ITEM_DeletedMessagesAppearanceCard) {
                return deletedMessagesPreviewCell = new DeletedMessagesPreviewCell(mContext, getParentLayout());
            } else if (viewType == ConfigCellCustom.CUSTOM_ITEM_DeletedMessagesColorPicker) {
                deletedMessagesColorPickerCell = new DeletedMessagesColorPickerCell(mContext, getResourceProvider());
                deletedMessagesColorPickerCell.setOnColorSelected(colorId -> {
                    AyuConfig.INSTANCE.getDeletedIconColor().setConfigInt(colorId);
                    TimeStringHelper.invalidateDeletedStyle();
                    cellGroup.runCallback(AyuConfig.INSTANCE.getDeletedIconColor().getKey(), colorId);
                });
                return deletedMessagesColorPickerCell;
            }
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);

            if (row == deletedMessagesPreviewRow) {
                DeletedMessagesPreviewCell previewCell = (DeletedMessagesPreviewCell) holder.itemView;
                previewCell.refresh();
            } else if (row == deletedMarkRow) {
                bindDeletedMarkRow((TextSettingsCell) holder.itemView);
            } else if (row == deletedMarkColorRow) {
                DeletedMessagesColorPickerCell colorPickerCell = (DeletedMessagesColorPickerCell) holder.itemView;
                colorPickerCell.setSelectedColorId(AyuConfig.INSTANCE.getDeletedIconColor().Int(), false);
            } else if (row == showGhostModeStatusRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setEnabled(true, null);
                textCheckCell.setTextAndCheck(getString(R.string.GhostModeStatusIndicator), AyuOptions.showGhostModeStatus.Bool(), false);
            }
        }
    }
}
