package com.radolyn.ayugram.settings;

import android.content.Context;
import android.text.StaticLayout;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AnimatedTextView;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28, shadows = {
        SettingsSwitchTouchTest.SettingsPreferences.class, SettingsSwitchTouchTest.NoWallpaper.class,
        SettingsSwitchTouchTest.NoBlur.class, SettingsSwitchTouchTest.NoLottie.class})
public class TextSettingsCellLayoutTest {
    private static final String LONG_FOLDER = "Ayu Attachments-4249142f-b2b0-4a50-878f-9d96c8dd13c0";
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext = context;
        AndroidUtilities.density = 1;
        AndroidUtilities.displaySize.set(1080, 1920);
        LocaleController.isRTL = false;
    }

    private static void layout(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(50, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static StaticLayout textLayout(AnimatedTextView view) throws Exception {
        Field partsField = AnimatedTextView.AnimatedTextDrawable.class.getDeclaredField("currentParts");
        partsField.setAccessible(true);
        Object part = ((Object[]) partsField.get(view.getDrawable()))[0];
        Field layoutField = part.getClass().getDeclaredField("layout");
        layoutField.setAccessible(true);
        return (StaticLayout) layoutField.get(part);
    }

    private void assertLongFolderDoesNotOverlap(boolean betterLayout, boolean rtl) throws Exception {
        LocaleController.isRTL = rtl;
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBetterLayout(betterLayout);
        cell.setTextAndValue("Attachment folder", LONG_FOLDER, false);
        layout(cell, 360);
        AnimatedTextView value = cell.getValueTextView();
        assertTrue("value must leave room for the title", value.getMeasuredWidth() <= (360 - 34) / 2);
        assertTrue("drawable must use the constrained width", textLayout(value).getWidth() <= (360 - 34) / 2);
        if (rtl) {
            assertTrue("RTL title and value must not overlap", value.getRight() <= cell.getTextView().getLeft());
        } else {
            assertTrue("title and value must not overlap", cell.getTextView().getRight() <= value.getLeft());
        }
    }

    @Test public void longFolderLeavesRoomForTitleInDebugLayout() throws Exception {
        assertLongFolderDoesNotOverlap(true, false);
    }

    @Test public void longFolderLeavesRoomForTitleInReleaseLayout() throws Exception {
        assertLongFolderDoesNotOverlap(false, false);
    }

    @Test public void longFolderLeavesRoomForTitleInRtlLayout() throws Exception {
        assertLongFolderDoesNotOverlap(true, true);
        assertLongFolderDoesNotOverlap(false, true);
    }

    @Test public void unchangedTextReflowsWhenWidthShrinksAndExpands() throws Exception {
        AnimatedTextView value = new AnimatedTextView(context);
        value.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 50));
        value.setTextSize(16);
        value.setText(LONG_FOLDER, false);
        for (int maxWidth : new int[]{240, 100, 180}) {
            value.setMaxWidth(maxWidth);
            value.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(50, View.MeasureSpec.EXACTLY));
            assertTrue("adaptWidth must respect maxWidth", value.getMeasuredWidth() <= maxWidth);
            assertEquals("unchanged text needs a new layout after resize", maxWidth, textLayout(value).getWidth());
            assertEquals(LONG_FOLDER, value.getText().toString());
        }
    }

    @Test public void recycledRowClearsValueAndCanShowLongFolderAgain() throws Exception {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBetterLayout(true);
        cell.setTextAndValue("Attachment folder", LONG_FOLDER, false);
        layout(cell, 360);
        cell.setText("Title only", false);
        layout(cell, 360);
        assertEquals(View.INVISIBLE, cell.getValueTextView().getVisibility());
        assertEquals("", cell.getValueTextView().getText().toString());
        cell.setTextAndValue("No value", null, false);
        layout(cell, 360);
        assertEquals("", cell.getValueTextView().getText().toString());
        cell.setTextAndValue("Attachment folder", LONG_FOLDER, false);
        layout(cell, 360);
        assertTrue(textLayout(cell.getValueTextView()).getWidth() <= (360 - 34) / 2);
        assertTrue(cell.getTextView().getRight() <= cell.getValueTextView().getLeft());
    }
}
