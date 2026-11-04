package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

import tw.nekomimi.nekogram.NekoConfig;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
// SDK 27 keeps EditTextBoldCursor off HiddenApiBypass, which needs sun.misc.Unsafe on the host JVM.
@Config(application = android.app.Application.class, sdk = 27, shadows = {
        ChatActivityEnterViewFieldRightTest.Player.class, ChatActivityEnterViewFieldRightTest.Preferences.class,
        ChatActivityEnterViewFieldRightTest.NoWallpaper.class, ChatActivityEnterViewFieldRightTest.NoBlur.class,
        ChatActivityEnterViewFieldRightTest.NoLottie.class, ChatActivityEnterViewFieldRightTest.Strings.class,
        ChatActivityEnterViewFieldRightTest.FreeChat.class})
public class ChatActivityEnterViewFieldRightTest {
    private static MediaController controller;
    private ChatActivityEnterView view;
    private Object attachMenu;

    @Implements(LocaleController.class)
    public static class Strings {
        @Implementation public static String getString(int res) {
            return ApplicationLoader.applicationContext.getString(res);
        }
        @Implementation public static String getString(String key, int res) {
            return getString(res);
        }
    }
    @Implements(MediaController.class)
    public static class Player {
        @Implementation protected void __constructor__() {}
        @Implementation public static MediaController getInstance() { return controller; }
    }
    @Implements(MessagesController.class)
    public static class Preferences {
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
    @Implements(Theme.class)
    public static class NoWallpaper {
        @Implementation public static void loadWallpaper(boolean async) {}
    }
    @Implements(SharedConfig.class)
    public static class NoBlur {
        @Implementation public static boolean chatBlurEnabled() { return false; }
        @Implementation public static int getDevicePerformanceClass() { return 1; }
    }
    @Implements(RLottieDrawable.class)
    public static class NoLottie extends org.robolectric.shadows.ShadowDrawable {
        @Implementation protected boolean scheduleNextGetFrame() { return false; }
    }
    @Implements(ChatActivityEnterView.class)
    public static class FreeChat extends org.robolectric.shadows.ShadowViewGroup {
        @Implementation public long getStarsPrice() { return 0; }
        // Hint text needs account state and does not affect the right margin.
        @Implementation public void updateFieldHint(boolean animated) {}
    }

    @Before public void setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        AndroidUtilities.density = 1;
        LocaleController.isRTL = false;
        controller = new MediaController();
        attachMenu = NekoConfig.useChatAttachMediaMenu.value;
        NekoConfig.useChatAttachMediaMenu.value = true;
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        view = new ChatActivityEnterView(activity, new SizeNotifierFrameLayout(activity), null, true);
        hide("notifyButton");
    }

    @After public void cleanup() {
        NekoConfig.useChatAttachMediaMenu.value = attachMenu;
    }

    private void hide(String name) {
        View button = ReflectionHelpers.getField(view, name);
        if (button != null) button.setVisibility(View.GONE);
    }

    // attachVisible == 1 is the typing state when the attach menu stays visible (useChatAttachMediaMenu).
    private int rightMarginWhileTyping() {
        ReflectionHelpers.callInstanceMethod(view, "updateFieldRight", ReflectionHelpers.ClassParameter.from(int.class, 1));
        return ((FrameLayout.LayoutParams) view.messageEditText.getLayoutParams()).rightMargin;
    }

    @Test public void menuButtonOnlyKeepsOriginalMargin() {
        assertEquals(50, rightMarginWhileTyping());
    }

    @Test public void suggestButtonReservesItsOwnSlotWhileTyping() {
        view.setSuggestionButtonVisible(true, false);
        assertEquals(98, rightMarginWhileTyping());

        View suggest = ReflectionHelpers.getField(view, "suggestButton");
        FrameLayout container = view.messageEditTextContainer;
        container.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(44, View.MeasureSpec.EXACTLY));
        container.layout(0, 0, 400, 44);
        View attachLayout = ReflectionHelpers.getField(view, "attachLayout");
        int suggestLeft = attachLayout.getLeft() + suggest.getLeft();
        assertTrue("text " + view.messageEditText.getRight() + " overlaps suggest " + suggestLeft,
                view.messageEditText.getRight() <= suggestLeft);
    }

    @Test public void suggestAndNotifyButtonsBothReserveSlots() {
        view.setSuggestionButtonVisible(true, false);
        View notify = ReflectionHelpers.getField(view, "notifyButton");
        notify.setVisibility(View.VISIBLE);
        assertEquals(146, rightMarginWhileTyping());
    }

    @Test public void hiddenSuggestButtonReleasesSlot() {
        view.setSuggestionButtonVisible(true, false);
        view.setSuggestionButtonVisible(false, false);
        assertEquals(50, rightMarginWhileTyping());
    }

    @Test public void hiddenAttachLayoutIgnoresSuggestButton() {
        view.setSuggestionButtonVisible(true, false);
        ReflectionHelpers.callInstanceMethod(view, "updateFieldRight", ReflectionHelpers.ClassParameter.from(int.class, 0));
        assertEquals(2, ((FrameLayout.LayoutParams) view.messageEditText.getLayoutParams()).rightMargin);
    }
}
