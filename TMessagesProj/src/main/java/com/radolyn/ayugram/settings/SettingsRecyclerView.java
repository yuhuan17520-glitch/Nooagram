package com.radolyn.ayugram.settings;

import android.content.Context;
import android.view.View;
import org.telegram.ui.Cells.TextCheckCell2;

import org.telegram.ui.Components.BlurredRecyclerView;

/** Reject text presses at DOWN, before RecyclerListView starts a click or long press. */
class SettingsRecyclerView extends BlurredRecyclerView {
    SettingsRecyclerView(Context context) {
        super(context);
    }

    @Override
    protected boolean allowSelectChildAtPosition(View row) {
        ViewHolder holder = findContainingViewHolder(row);
        return row.isEnabled() && holder != null && getAdapter() instanceof SelectionAdapter adapter
                && adapter.isEnabled(holder) && super.allowSelectChildAtPosition(row);
    }

    @Override
    protected boolean allowSelectChildAtPosition(float x, float y) {
        View row = findChildViewUnder(x, y);
        return super.allowSelectChildAtPosition(x, y) && (row == null || row instanceof TextCheckCell2
                || SettingsSwitchTouch.hitsControl(row, x - row.getX(), y - row.getY()));
    }
}
