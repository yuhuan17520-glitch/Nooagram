package com.radolyn.ayugram.config.cell;

import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.ui.cells.HeaderCell;

import com.radolyn.ayugram.config.CellGroup;

public class ConfigCellHeader extends AbstractConfigCell {
    private final String title;

    public ConfigCellHeader(String title) {
        this.title = title;
    }

    public int getType() {
        return CellGroup.ITEM_TYPE_HEADER;
    }

    public boolean isEnabled() {
        return false;
    }

    public void onBindViewHolder(RecyclerView.ViewHolder holder) {
        HeaderCell headerCell = (HeaderCell) holder.itemView;
        headerCell.applySeparatedHeadersStyle();
        headerCell.setText(title);
    }
}
