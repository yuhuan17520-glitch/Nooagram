package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.NooagramPinnedHider;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class NooagramPinnedHiderActivity extends BaseNekoSettingsActivity implements NotificationCenter.NotificationCenterDelegate {
    private ArrayList<Long> hiddenDialogs = new ArrayList<>();
    private int headerRow;
    private int startRow;
    private int endRow;
    private int emptyRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        hiddenDialogs = NooagramPinnedHider.getHiddenDialogs(currentAccount);
        headerRow = -1;
        startRow = -1;
        endRow = -1;
        emptyRow = -1;
        if (hiddenDialogs.isEmpty()) {
            emptyRow = rowCount++;
        } else {
            headerRow = rowCount++;
            startRow = rowCount;
            rowCount += hiddenDialogs.size();
            endRow = rowCount;
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        getNotificationCenter().addObserver(this, NotificationCenter.nooagramPinnedHiderChanged);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.nooagramPinnedHiderChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        updateRows();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        if (!NooagramPinnedHider.getLegacyDialogs(currentAccount).isEmpty()) {
            menu.addItem(998, R.drawable.msg_unarchive).setContentDescription(getString(R.string.NooagramRestoreLegacyPinned));
        }
        menu.addItem(999, R.drawable.msg_delete).setContentDescription(getString(R.string.Clear));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 999) {
                    showClearAlert();
                } else if (id == 998) {
                    showRestoreLegacyAlert();
                }
            }
        });
        return view;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onResume() {
        super.onResume();
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position < startRow || position >= endRow) {
            return;
        }
        int index = position - startRow;
        if (index < 0 || index >= hiddenDialogs.size() || !(view.getTag() instanceof Long)) {
            return;
        }
        long dialogId = (Long) view.getTag();
        NooagramPinnedHider.setHidden(currentAccount, dialogId, false);
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createSimpleBulletin(
                    R.drawable.msg_pin,
                    getString(R.string.NooagramPinnedRestored) + "\n" + dialogTitle(dialogId)
            ).show();
        }
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.NooagramPinnedHider);
    }

    private void showClearAlert() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(getString(R.string.NooagramPinnedClearTitle));
        builder.setMessage(getString(R.string.NooagramPinnedClearMessage));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
            NooagramPinnedHider.clear(currentAccount);
            updateRows();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });
        showDialog(builder.create());
    }

    private void showRestoreLegacyAlert() {
        if (getParentActivity() == null) return;
        ArrayList<Long> legacy = NooagramPinnedHider.getLegacyDialogs(currentAccount);
        if (legacy.isEmpty()) return;
        long userId = getUserConfig().getClientUserId();
        StringBuilder message = new StringBuilder(getString(R.string.NooagramRestoreLegacyPinnedInfo));
        for (long dialogId : legacy) message.append("\n").append(dialogTitle(dialogId));
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.NooagramRestoreLegacyPinned))
                .setMessage(message)
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.Restore), (dialog, which) -> {
                    if (getUserConfig().getClientUserId() != userId || userId == 0) return;
                    NooagramPinnedHider.restoreLegacyDialogs(currentAccount);
                    actionBar.createMenu().getItem(998).setVisibility(View.GONE);
                }).create());
    }

    private String dialogTitle(long dialogId) {
        try {
            if (dialogId > 0) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                if (user != null) {
                    return ContactsController.formatName(user.first_name, user.last_name);
                }
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                if (chat != null) {
                    return chat.title;
                }
            }
        } catch (Exception ignored) {
        }
        return "ID " + dialogId;
    }

    private class ListAdapter extends BaseListAdapter {
        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean payload) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(getString(R.string.NooagramPinnedHiddenHeader));
                    break;
                case TYPE_TEXT: {
                    int index = position - startRow;
                    if (index >= 0 && index < hiddenDialogs.size()) {
                        TextCell cell = (TextCell) holder.itemView;
                        long dialogId = hiddenDialogs.get(index);
                        cell.setTag(dialogId);
                        String title = dialogTitle(dialogId);
                        cell.setColors(-1, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setText(title, position + 1 < endRow);
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY:
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setBackground(Theme.getThemedDrawable(
                            mContext,
                            R.drawable.greydivider_bottom,
                            Theme.key_windowBackgroundGrayShadow
                    ));
                    cell.setText(getString(R.string.NooagramPinnedEmpty));
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == emptyRow) {
                return TYPE_INFO_PRIVACY;
            }
            if (position == headerRow) {
                return TYPE_HEADER;
            }
            return TYPE_TEXT;
        }
    }
}
