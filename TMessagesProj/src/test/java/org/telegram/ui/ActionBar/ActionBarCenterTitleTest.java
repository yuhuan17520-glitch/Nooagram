package org.telegram.ui.ActionBar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.StaticLayout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;

import com.radolyn.ayugram.settings.SettingsSwitchTouchTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.AnimatedEmojiDrawable;

import xyz.nextalone.nagram.NaConfig;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28, shadows = {
        SettingsSwitchTouchTest.SettingsPreferences.class, SettingsSwitchTouchTest.NoWallpaper.class,
        SettingsSwitchTouchTest.NoBlur.class, SettingsSwitchTouchTest.NoLottie.class,
        org.telegram.ui.Components.FragmentContextViewPlayerTest.Strings.class,
        org.telegram.ui.Components.FragmentContextViewPlayerTest.Fragment.class})
public class ActionBarCenterTitleTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext = context;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        AndroidUtilities.density = 1;
        AndroidUtilities.displaySize.set(1080, 1920);
        LocaleController.isRTL = false;
        NaConfig.INSTANCE.getCenterActionBarTitle().setConfigBool(false);
    }

    private ActionBar bar(String title) {
        ActionBar bar = new ActionBar(context);
        bar.setOccupyStatusBar(false);
        bar.setTitle(title);
        return bar;
    }

    private void layout(ActionBar bar, int width) {
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(56, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, bar.getMeasuredWidth(), bar.getMeasuredHeight());
    }

    private void assertVisibleContentCentered(ActionBar bar, boolean includeStatus) {
        SimpleTextView title = bar.getTitleTextView();
        StaticLayout text = ReflectionHelpers.getField(title, "layout");
        float start = title.getTextStartX() + text.getLineLeft(0);
        for (ViewParent parent = title.getParent(); parent instanceof View view && view != bar; parent = view.getParent()) {
            start += view.getX();
        }
        float width = text.getLineWidth(0) + (includeStatus ? title.getRightDrawableWidth() : 0);
        assertEquals("the visible text/status must be centered, not just the view bounds",
                bar.getMeasuredWidth() / 2f, start + width / 2f, 1f);
    }

    @Test public void savedPreferenceCentersNewBars() {
        NaConfig.INSTANCE.getCenterActionBarTitle().setConfigBool(true);
        ActionBar bar = bar("Nooagram");
        layout(bar, 360);
        assertTrue(bar.isTitleCentered());
        View title = bar.getTitleTextView();
        assertEquals(180f, (title.getLeft() + title.getRight()) / 2f, 0.5f);
        assertVisibleContentCentered(bar, false);
    }

    @Test public void togglingAnExistingBarRequestsLayoutAndReturnsToLeft() {
        ActionBar bar = bar("Nooagram");
        bar.setSubtitle("Connected");
        bar.createAdditionalSubtitleTextView();
        layout(bar, 360);
        int originalLeft = bar.getTitleTextView().getLeft();
        bar.centerTitle(true);
        assertTrue(bar.isLayoutRequested());
        assertEquals(Gravity.CENTER, (int) ReflectionHelpers.<Integer>getField(bar.getSubtitleTextView(), "gravity"));
        assertEquals(Gravity.CENTER, (int) ReflectionHelpers.<Integer>getField(bar.getAdditionalSubtitleTextView(), "gravity"));
        layout(bar, 360);
        assertEquals(180f, (bar.getTitleTextView().getLeft() + bar.getTitleTextView().getRight()) / 2f, 0.5f);
        bar.centerTitle(false);
        layout(bar, 360);
        assertEquals(originalLeft, bar.getTitleTextView().getLeft());
        assertEquals(Gravity.LEFT, (int) ReflectionHelpers.<Integer>getField(bar.getSubtitleTextView(), "gravity"));
    }

    @Test public void returningFromSettingsReadsBothEnabledAndDisabledPreference() {
        ActionBar bar = bar("Nooagram");
        NaConfig.INSTANCE.getCenterActionBarTitle().setConfigBool(true);
        bar.onResume();
        layout(bar, 360);
        assertTrue(bar.isTitleCentered());
        NaConfig.INSTANCE.getCenterActionBarTitle().setConfigBool(false);
        bar.onResume();
        layout(bar, 360);
        assertFalse(bar.isTitleCentered());
        assertEquals(18, bar.getTitleTextView().getLeft());
    }

    @Test public void longTitleStaysCenteredAndLeavesRoomForMenuButtons() {
        ActionBar bar = bar("A very long custom title that must leave room for every menu button");
        bar.createMenu().addItem(1, android.R.drawable.ic_menu_search);
        bar.createMenu().addItem(2, android.R.drawable.ic_menu_more);
        bar.centerTitle(true);
        for (int width : new int[]{320, 480}) {
            layout(bar, width);
            View title = bar.getTitleTextView();
            assertEquals(width / 2f, (title.getLeft() + title.getRight()) / 2f, 0.5f);
            assertTrue("title must stop before the menu", title.getRight() <= bar.menu.getLeft());
        }
    }

    @Test public void centeredTitleKeepsItsClickAction() {
        ActionBar bar = bar("Nooagram");
        int[] clicks = {0};
        bar.setTitleActionRunnable(() -> clicks[0]++);
        bar.centerTitle(true);
        layout(bar, 360);
        bar.performClick();
        assertEquals(1, clicks[0]);
    }

    @Test public void emptyStatusDoesNotLeaveAnInvisibleGapInTheHomeTitle() {
        ActionBar bar = new ActionBar(context);
        bar.setOccupyStatusBar(false);
        bar.setUseContainerForTitles();
        AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable status = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(null, 26);
        bar.setTitle("Nooagram", status);
        bar.setTitleRightDrawable(status);
        bar.centerTitle(true);
        for (int width : new int[]{320, 480}) {
            layout(bar, width);
            assertNull(bar.getTitleTextView().getRightDrawable());
            assertVisibleContentCentered(bar, false);
        }
    }

    @Test public void showingAndRemovingStatusReflowsTheVisibleTitle() {
        ActionBar bar = bar("Nooagram");
        bar.centerTitle(true);
        AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable status = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(null, 26);
        status.set(new ColorDrawable(Color.BLUE), false);
        bar.setTitleRightDrawable(status);
        layout(bar, 360);
        assertSame(status, bar.getTitleTextView().getRightDrawable());
        assertVisibleContentCentered(bar, true);
        status.set((android.graphics.drawable.Drawable) null, false);
        bar.setTitleRightDrawable(status);
        layout(bar, 360);
        assertNull(bar.getTitleTextView().getRightDrawable());
        assertVisibleContentCentered(bar, false);
    }

    @Test public void statusUpdatesPreserveConnectionOverlayAndTheBaseTitle() {
        ActionBar bar = bar("Nooagram");
        BaseFragment fragment = new BaseFragment() {
            @Override protected View createView(Context context) {
                return new android.widget.FrameLayout(context);
            }
        };
        ReflectionHelpers.setField(fragment, "parentLayout", java.lang.reflect.Proxy.newProxyInstance(
                INavigationLayout.class.getClassLoader(), new Class<?>[]{INavigationLayout.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null));
        bar.parentFragment = fragment;
        bar.centerTitle(true);
        bar.setAllowOverlayTitle(true);
        bar.setTitleOverlayText("Connecting", R.string.Connecting, null);
        CharSequence connecting = bar.getTitleTextView().getText();
        AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable status = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(null, 26);
        status.set(new ColorDrawable(Color.BLUE), false);
        bar.setTitleRightDrawable(status);
        assertEquals(connecting, bar.getTitleTextView().getText());
        assertNull(bar.getTitleTextView().getRightDrawable());
        bar.setTitleOverlayText(null, 0, null);
        layout(bar, 360);
        assertEquals("Nooagram", bar.getTitleTextView().getText().toString());
        assertSame(status, bar.getTitleTextView().getRightDrawable());
        assertVisibleContentCentered(bar, true);
    }
}
