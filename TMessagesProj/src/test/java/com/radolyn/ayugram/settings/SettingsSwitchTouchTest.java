package com.radolyn.ayugram.settings;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.RLottieDrawable;
import com.radolyn.ayugram.config.CellGroup;
import com.radolyn.ayugram.config.cell.ConfigCellTextCheckIcon;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28, shadows = {SettingsSwitchTouchTest.SettingsPreferences.class,
        SettingsSwitchTouchTest.NoWallpaper.class, SettingsSwitchTouchTest.NoBlur.class, SettingsSwitchTouchTest.NoLottie.class})
public class SettingsSwitchTouchTest {
    @Implements(RLottieDrawable.class)
    public static class NoLottie {
        // Touch dispatch does not need JNI animation decoding on the Windows test host.
        @Implementation protected boolean scheduleNextGetFrame() { return false; }
    }
    @Implements(Theme.class)
    public static class NoWallpaper {
        @Implementation public static void loadWallpaper(boolean async) {}
    }
    @Implements(SharedConfig.class)
    public static class NoBlur {
        @Implementation public static boolean chatBlurEnabled() { return false; }
        @Implementation public static int getDevicePerformanceClass() { return 1; }
    }
    // View tests need theme preferences, never a live Telegram account/native network.
    @Implements(MessagesController.class)
    public static class SettingsPreferences {
        @Implementation public static SharedPreferences getGlobalMainSettings() {
            return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        }
        @Implementation public static SharedPreferences getGlobalNotificationsSettings() {
            return ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
        }
        @Implementation public static SharedPreferences getGlobalEmojiSettings() {
            return ApplicationLoader.applicationContext.getSharedPreferences("emoji", Context.MODE_PRIVATE);
        }
    }
    private Activity activity;
    private SettingsRecyclerView list;
    private int clicks;

    @Before public void setup() {
        ApplicationLoader.applicationContext = org.robolectric.RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        AndroidUtilities.density = 1;
        LocaleController.isRTL = false;
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    private TextCheckCell switchRow(boolean rtl) {
        LocaleController.isRTL = rtl;
        TextCheckCell row = new TextCheckCell(activity);
        row.setTextAndCheck("Setting title", false, false);
        return row;
    }

    private void mount(View row, boolean enabled) {
        list = new SettingsRecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return enabled; }
            @Override public int getItemCount() { return 1; }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new RecyclerListView.Holder(row);
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
        });
        list.setOnItemClickListener((View view, int position, float x, float y) -> {
            clicks++;
            if (view instanceof TextCheckCell cell) cell.setChecked(!cell.isChecked());
        });
        activity.setContentView(list);
        layout(list, 360, 600);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private void event(View view, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private void tap(View view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        event(view, down, MotionEvent.ACTION_DOWN, x, y);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        event(view, down, MotionEvent.ACTION_UP, x, y);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    private float cx(View view) { return view.getX() + view.getWidth() / 2f; }
    private float cy(View view) { return view.getY() + view.getHeight() / 2f; }

    @Test public void listTextTapDoesNothingAndSwitchTapTogglesOnce() {
        TextCheckCell row = switchRow(false);
        mount(row, true);
        tap(list, 80, cy(row));
        assertEquals(0, clicks);
        assertFalse(row.isChecked());
        tap(list, cx(row.getCheckBox()), cy(row));
        assertEquals(1, clicks);
        assertTrue(row.isChecked());
    }

    @Test public void rightToLeftUsesActualSwitchPosition() {
        TextCheckCell row = switchRow(true);
        mount(row, true);
        tap(list, 260, cy(row));
        assertEquals(0, clicks);
        tap(list, cx(row.getCheckBox()), cy(row));
        assertEquals(1, clicks);
    }

    @Test public void disabledSwitchDoesNotToggle() {
        TextCheckCell row = switchRow(false);
        mount(row, false);
        tap(list, cx(row.getCheckBox()), cy(row));
        assertEquals(0, clicks);
    }

    @Test public void cancelAndDragFromTextIntoSwitchDoNotClick() {
        TextCheckCell row = switchRow(false);
        mount(row, true);
        float x = cx(row.getCheckBox());
        long down = SystemClock.uptimeMillis();
        event(list, down, MotionEvent.ACTION_DOWN, x, 25);
        event(list, down, MotionEvent.ACTION_CANCEL, x, 25);
        down = SystemClock.uptimeMillis();
        event(list, down, MotionEvent.ACTION_DOWN, x, 25);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        event(list, down, MotionEvent.ACTION_MOVE, x, 100);
        event(list, down, MotionEvent.ACTION_UP, x, 100);
        down = SystemClock.uptimeMillis();
        event(list, down, MotionEvent.ACTION_DOWN, 80, 25);
        event(list, down, MotionEvent.ACTION_MOVE, x, 25);
        event(list, down, MotionEvent.ACTION_UP, x, 25);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(0, clicks);
    }

    @Test public void navigationRowStillRespondsToText() {
        TextCell row = new TextCell(activity);
        row.setTextAndIcon("Subpage", 0, false);
        mount(row, true);
        tap(list, 80, cy(row));
        assertEquals(1, clicks);
    }

    @Test public void customIconNavigationClearsConstructedAndRecycledSwitch() {
        TextCell row = new TextCell(activity, 23, false, true, null);
        row.setTextAndIcon("Export database", 0, true);
        assertEquals(View.GONE, row.getCheckBox().getVisibility());
        mount(row, true);
        tap(list, 80, cy(row));
        assertEquals(1, clicks);

        row.setTextAndCheckAndIcon("Switch", true, 0, false);
        assertEquals(View.VISIBLE, row.getCheckBox().getVisibility());
        row.setTextAndIcon("Import database", new android.graphics.drawable.ColorDrawable(), false);
        assertEquals(View.GONE, row.getCheckBox().getVisibility());
        tap(list, 80, cy(row));
        assertEquals(2, clicks);

        row.setTextAndCheckAndIcon("Switch", false, 0, false);
        row.setTextAndValueAndIcon("Navigation", "Value", new android.graphics.drawable.ColorDrawable(), false);
        assertEquals(View.GONE, row.getCheckBox().getVisibility());
        tap(list, 80, cy(row));
        assertEquals(3, clicks);
    }

    @Test public void programmaticAccessibilityClickRemainsAvailable() {
        TextCheckCell row = switchRow(false);
        mount(row, true);
        list.clickItem(row, 0);
        assertEquals(1, clicks);
    }

    @Test public void multilineDoesNotMakeEntireRightEdgeClickable() {
        TextCheckCell row = switchRow(false);
        row.setTextAndValueAndCheck("Title", "Explanation\nwith several lines\nof text", false, true, false);
        layout(row, 360, 160);
        View control = row.getCheckBox();
        assertTrue(SettingsSwitchTouch.hitsControl(row, cx(control), cy(control)));
        assertFalse(SettingsSwitchTouch.hitsControl(row, cx(control), 2));
        assertFalse(SettingsSwitchTouch.hitsControl(row, 80, cy(control)));
    }

    @Test public void dialogTextAndScrollCannotToggleButControlAndAccessibilityCan() {
        TextCheckCell row = switchRow(false);
        row.setOnClickListener(v -> clicks++);
        SettingsSwitchTouch.restrictDialogRow(row);
        activity.setContentView(row);
        layout(row, 360, 50);
        tap(row, 80, 25);
        assertEquals(0, clicks);
        float x = cx(row.getCheckBox());
        tap(row, x, 25);
        assertEquals(1, clicks);
        long down = SystemClock.uptimeMillis();
        event(row, down, MotionEvent.ACTION_DOWN, x, 25);
        event(row, down, MotionEvent.ACTION_MOVE, x, 5);
        event(row, down, MotionEvent.ACTION_MOVE, x, 25);
        event(row, down, MotionEvent.ACTION_UP, x, 25);
        assertEquals(1, clicks);
        row.performClick();
        assertEquals(2, clicks);
    }

    @Test public void checkboxAndIconSwitchUseTheirOwnControls() {
        CheckBoxCell check = new CheckBoxCell(activity, CheckBoxCell.TYPE_CHECK_BOX_ROUND);
        check.setText("Checkbox", "", false, false);
        layout(check, 360, 50);
        View control = check.getCheckBoxView();
        assertTrue(SettingsSwitchTouch.hitsControl(check, cx(control), cy(control)));
        assertFalse(SettingsSwitchTouch.hitsControl(check, 180, cy(control)));
        TextCell icon = new TextCell(activity, 23, false, true, null);
        icon.setTextAndCheckAndIcon("Switch", false, 0, false);
        layout(icon, 360, 50);
        assertFalse(SettingsSwitchTouch.hitsControl(icon, 80, 25));
        assertTrue(SettingsSwitchTouch.hitsControl(icon, cx(icon.getCheckBox()), cy(icon.getCheckBox())));
        ConfigCellTextCheckIcon navigation = new ConfigCellTextCheckIcon(
                null, "navigation", "Recycled navigation", 0, false, () -> {});
        new CellGroup(null).appendCell(navigation);
        navigation.onBindViewHolder(new RecyclerListView.Holder(icon));
        assertTrue(SettingsSwitchTouch.hitsControl(icon, 80, 25));
        TextCheckBoxCell square = new TextCheckBoxCell(activity);
        square.setTextAndCheck("Square", false, false);
        layout(square, 360, 50);
        assertFalse(SettingsSwitchTouch.hitsControl(square, 80, 25));
        assertTrue(SettingsSwitchTouch.hitsControl(square, cx(square.getCheckBox()), 25));
    }

    @Test public void groupTextOnlyExpandsAndSwitchDragDoesNotToggle() {
        TextCheckCell2 row = new TextCheckCell2(activity);
        row.setTextAndCheck("Group", false, false);
        int[] toggles = {0};
        row.setCollapseArrow("0/3", true, () -> toggles[0]++);
        SettingsSwitchTouch.restrictGroupSwitch(row);
        mount(row, true);
        tap(list, 80, cy(row));
        assertEquals(1, clicks);
        assertEquals(0, toggles[0]);
        float x = cx(row.getCheckBox());
        tap(list, x, cy(row));
        assertEquals(1, clicks);
        assertEquals(1, toggles[0]);
        long down = SystemClock.uptimeMillis();
        event(list, down, MotionEvent.ACTION_DOWN, x, 25);
        event(list, down, MotionEvent.ACTION_MOVE, x, 45);
        event(list, down, MotionEvent.ACTION_UP, x, 45);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(1, toggles[0]);
    }
}
