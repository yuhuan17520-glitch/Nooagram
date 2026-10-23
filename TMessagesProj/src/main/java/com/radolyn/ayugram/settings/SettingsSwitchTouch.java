package com.radolyn.ayugram.settings;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell2;

/** Touch-only restriction: keyboard and accessibility clicks retain their usual action. */
final class SettingsSwitchTouch {
    private SettingsSwitchTouch() {}

    static View control(View row) {
        View control = null;
        if (row instanceof TextCheckCell cell) {
            control = cell.getCheckBox();
        } else if (row instanceof TextCell cell) {
            control = cell.getCheckBox();
        } else if (row instanceof CheckBoxCell cell) {
            control = cell.getCheckBoxView();
        } else if (row instanceof TextCheckBoxCell cell) {
            control = cell.getCheckBox();
        } else if (row instanceof TextCheckCell2 cell) {
            control = cell.getCheckBox();
        }
        return control != null && control.getVisibility() == View.VISIBLE ? control : null;
    }

    static boolean hitsControl(View row, float x, float y) {
        View control = control(row);
        if (control == null) return true; // Navigation rows have no switch.
        float halfWidth = Math.max(control.getWidth(), AndroidUtilities.dp(48)) / 2f;
        float halfHeight = Math.max(control.getHeight(), AndroidUtilities.dp(48)) / 2f;
        float centerX = control.getX() + control.getWidth() / 2f;
        float centerY = control.getY() + control.getHeight() / 2f;
        return x >= 0 && x < row.getWidth() && y >= 0 && y < row.getHeight()
                && Math.abs(x - centerX) <= halfWidth && Math.abs(y - centerY) <= halfHeight;
    }

    static void restrictDialogRow(View row) {
        restrictTouch(row, row);
    }

    static void restrictGroupSwitch(TextCheckCell2 row) {
        View area = row.getCheckBoxClickArea();
        if (area != null) restrictTouch(area, row);
    }

    private static void restrictTouch(View touchView, View row) {
        touchView.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private boolean rejected;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        downX = event.getX();
                        downY = event.getY();
                        float x = downX + (view == row ? 0 : view.getX());
                        float y = downY + (view == row ? 0 : view.getY());
                        rejected = !row.isEnabled() || !hitsControl(row, x, y);
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                        if (!rejected && (Math.abs(event.getX() - downX) > slop
                                || Math.abs(event.getY() - downY) > slop)) {
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            view.onTouchEvent(cancel);
                            cancel.recycle();
                            rejected = true;
                        }
                    }
                    case MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        view.onTouchEvent(cancel);
                        cancel.recycle();
                        rejected = true;
                    }
                }
                return rejected;
            }
        });
    }
}
