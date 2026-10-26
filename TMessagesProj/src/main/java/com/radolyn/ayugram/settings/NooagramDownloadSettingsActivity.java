package com.radolyn.ayugram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import android.widget.LinearLayout;
import org.telegram.ui.Components.RecyclerListView;

import com.radolyn.ayugram.AyuConfig;
import com.radolyn.ayugram.AyuCustomSavePath;
import com.radolyn.ayugram.AyuOptions;
import com.radolyn.ayugram.config.CellGroup;
import com.radolyn.ayugram.config.cell.AbstractConfigCell;
import com.radolyn.ayugram.config.cell.ConfigCellDivider;
import com.radolyn.ayugram.config.cell.ConfigCellHeader;
import com.radolyn.ayugram.config.cell.ConfigCellTextCheck;
import com.radolyn.ayugram.config.cell.ConfigCellTextDetail;
import com.radolyn.ayugram.utils.NooagramDownloadDestination;

/** Both settings menus open this page and edit the same existing preferences. */
public class NooagramDownloadSettingsActivity extends BaseNekoXSettingsActivity {
    private static final int SELECT_DIRECTORY = 921;
    private BaseListAdapter listAdapter;
    private final CellGroup cellGroup = new CellGroup(this);
    private final AbstractConfigCell header = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.NooagramDownloadSettings)));
    private final AbstractConfigCell enabledRow = cellGroup.appendCell(new ConfigCellTextCheck(
            AyuOptions.enableCustomSavePath, getString(R.string.NooagramDownloadSaveInfo),
            getString(R.string.NooagramDownloadSaveEnabled)));
    private final AbstractConfigCell folderRow = cellGroup.appendCell(new ConfigCellTextDetail(
            AyuOptions.customSavePath, getString(R.string.NooagramDownloadFolder),
            getString(R.string.NooagramDownloadFolderHint), AyuCustomSavePath::normalize,
            (input, output) -> !AyuCustomSavePath.isValidStoragePath(input) && !NooagramDownloadDestination.isTree(input),
            NooagramDownloadDestination::displayPath) {
        @Override
        public void onClick(View view, int position) { editDirectory(); }
    });
    private final AbstractConfigCell chatFoldersRow = cellGroup.appendCell(new ConfigCellTextCheck(
            AyuConfig.INSTANCE.getSaveToChatSubfolder(), getString(R.string.NooagramDownloadChatFoldersInfo)));
    private final AbstractConfigCell divider = cellGroup.appendCell(new ConfigCellDivider());

    public NooagramDownloadSettingsActivity() {
        addRowsToMap(cellGroup);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        listAdapter = new BaseListAdapter(context) {
            @Override
            protected void onBindDefaultViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                if (cellGroup.rows.get(position) == folderRow) {
                    TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                    cell.setMultilineDetail(true);
                    String path = NooagramDownloadDestination.displayPath(AyuOptions.customSavePath.String());
                    if (!NooagramDownloadDestination.canWrite(mContext, AyuOptions.customSavePath.String())) {
                        path += "\n" + getString(R.string.NooagramDownloadNeedsAccess);
                    }
                    cell.setTextAndValue(getString(R.string.NooagramDownloadFolder), path, cellGroup.needSetDivider(folderRow));
                }
            }
        };
        listView.setAdapter(listAdapter);
        setupDefaultListeners();
        cellGroup.callBackSettingsChanged = (key, value) -> {
            if (AyuOptions.customSavePath.getKey().equals(key)
                    && !NooagramDownloadDestination.canWrite(context, AyuOptions.customSavePath.String())) {
                selectDirectory();
            }
        };
        return view;
    }

    private void editDirectory() {
        Context context = getParentActivity();
        if (context == null) return;
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setSingleLine(true);
        input.setTextSize(18);
        input.setHint(getString(R.string.NooagramDownloadFolderHint));
        input.setText(NooagramDownloadDestination.displayPath(AyuOptions.customSavePath.String()));
        input.setSelection(input.length());
        LinearLayout layout = new LinearLayout(context);
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 8));
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(getString(R.string.NooagramDownloadFolder))
                .setView(layout)
                .setNeutralButton(getString(R.string.NooagramDownloadSelectDirectory), (selectedDialog, which) -> {
                    AndroidUtilities.hideKeyboard(input);
                    selectDirectory(input.getText().toString());
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.OK), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = AyuCustomSavePath.normalize(input.getText().toString());
            if (!AyuCustomSavePath.isValidStoragePath(value) && !NooagramDownloadDestination.isTree(value)) {
                com.radolyn.ayugram.utils.AyuAndroidUtil.showInputError(input);
                return;
            }
            AyuOptions.customSavePath.setConfigString(value);
            listAdapter.notifyDataSetChanged();
            dialog.dismiss();
            if (!NooagramDownloadDestination.canWrite(context, value)) selectDirectory();
        }));
        showDialog(dialog);
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
    }

    private void selectDirectory() {
        selectDirectory(AyuOptions.customSavePath.String());
    }

    private void selectDirectory(String initialPath) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            Uri initial = AyuCustomSavePath.isValidStoragePath(initialPath) || NooagramDownloadDestination.isTree(initialPath)
                    ? NooagramDownloadDestination.initialUri(initialPath) : null;
            if (initial != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
            startActivityForResult(intent, SELECT_DIRECTORY);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.NooagramDownloadFolderAccessFailed)).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != SELECT_DIRECTORY || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException("Directory is not writable");
            getParentActivity().getContentResolver().takePersistableUriPermission(uri, flags);
            // Keeping the tree URI also supports SD cards and cloud document providers.
            AyuOptions.customSavePath.setConfigString(uri.toString());
            listAdapter.notifyDataSetChanged();
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.NooagramDownloadFolderAccessFailed)).show();
        }
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() { return listAdapter; }

    @Override
    protected CellGroup getCellGroup() { return cellGroup; }

    @Override
    protected String getSettingsPrefix() { return "downloads"; }

    @Override
    public String getTitle() { return getString(R.string.NooagramDownloadSettings); }

    @Override
    public int getBaseGuid() { return 15100; }
}
