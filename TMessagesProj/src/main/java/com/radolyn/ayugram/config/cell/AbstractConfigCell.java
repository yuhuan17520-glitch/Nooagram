package com.radolyn.ayugram.config.cell;

import static org.telegram.messenger.LocaleController.getString;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

import com.radolyn.ayugram.config.CellGroup;

public abstract class AbstractConfigCell {
    // can not be null!
    protected CellGroup cellGroup;

    // called by CellGroup.java
    public void bindCellGroup(CellGroup cellGroup) {
        this.cellGroup = cellGroup;
    }

    public abstract int getType();

    public CharSequence getTitle() {
        return this instanceof WithKey withKey ? getString(withKey.getKey()) : null;
    }

    /** Localized labels searchable without creating or binding Android views. */
    public List<CharSequence> getSearchTitles() {
        return Collections.singletonList(getTitle());
    }

    public abstract boolean isEnabled();

    public abstract void onBindViewHolder(RecyclerView.ViewHolder holder);
}
