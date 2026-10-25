package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

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
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28, shadows = {
        FragmentContextViewPlayerTest.Player.class, FragmentContextViewPlayerTest.Fragment.class,
        FragmentContextViewPlayerTest.Preferences.class, FragmentContextViewPlayerTest.NoWallpaper.class,
        FragmentContextViewPlayerTest.NoBlur.class, FragmentContextViewPlayerTest.NoLottie.class,
        FragmentContextViewPlayerTest.Strings.class})
public class FragmentContextViewPlayerTest {
    private static MediaController controller;
    private static MessageObject playing;
    private static boolean paused;
    private static int cleanups;
    private FragmentContextView bar;

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
        @Implementation public MessageObject getPlayingMessageObject() { return playing; }
        @Implementation public boolean isMessagePaused() { return paused; }
        @Implementation public float getPlaybackSpeed(boolean music) { return 1f; }
        // Deliberately no reset event: reproduces an already stopped player / missed event.
        @Implementation public void cleanupPlayer(boolean notify, boolean stopService) {
            cleanups++;
            playing = null;
        }
        @Implementation public boolean playMessage(MessageObject message) {
            assertSame(playing, message);
            paused = false;
            return true;
        }
        @Implementation public boolean pauseMessage(MessageObject message) {
            assertSame(playing, message);
            paused = true;
            return true;
        }
    }
    @Implements(BaseFragment.class)
    public static class Fragment {
        @Implementation protected void __constructor__(Bundle args) {}
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

    @Before public void setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        AndroidUtilities.density = 1;
        LocaleController.isRTL = false;
        controller = new MediaController();
        playing = null;
        paused = false;
        cleanups = 0;
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        BaseFragment fragment = new BaseFragment() {
            @Override protected View createView(Context context) { return new FrameLayout(context); }
        };
        fragment.fragmentView = new FrameLayout(activity);
        new FrameLayout(activity).addView(fragment.fragmentView);
        bar = new FragmentContextView(activity, fragment, false);
        // Build real button listeners without attaching live account/network observers.
        ReflectionHelpers.callInstanceMethod(bar, "checkCreateView");
        ReflectionHelpers.setField(bar, "currentStyle", FragmentContextView.STYLE_AUDIO_PLAYER);
        ReflectionHelpers.setField(bar, "visible", true);
        bar.setTopPadding(36);
        bar.setVisibility(View.VISIBLE);
    }

    private View button(String name) { return ReflectionHelpers.getField(bar, name); }
    private void finishAnimations() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); }

    @Test public void closeRemovesStaleBarWithoutResetNotification() {
        button("closeButton").performClick();
        finishAnimations();
        assertEquals(1, cleanups);
        assertEquals(View.GONE, bar.getVisibility());
        assertEquals(0, bar.getTopPadding(), 0);
    }

    @Test public void titleClearsStaleBarInsteadOfLeavingAnInertControl() {
        bar.performClick();
        finishAnimations();
        assertEquals(0, cleanups);
        assertEquals(View.GONE, bar.getVisibility());
    }

    @Test public void playClearsStaleBarWithoutAttemptingNullPlayback() {
        button("playButton").performClick();
        finishAnimations();
        assertEquals(View.GONE, bar.getVisibility());
    }

    @Test public void resumeReconcilesStoppedPlaybackAndPadding() {
        bar.refreshPlayerState();
        assertEquals(View.GONE, bar.getVisibility());
        assertEquals(0, bar.getTopPadding(), 0);
        assertNull(ReflectionHelpers.getField(bar, "lastMessageObject"));
    }

    @Test public void resumeCancelsOldAnimationThatCouldRestoreStaleVisibility() {
        button("closeButton").performClick();
        assertNotNull(ReflectionHelpers.getField(bar, "animatorSet"));
        bar.refreshPlayerState();
        finishAnimations();
        assertEquals(View.GONE, bar.getVisibility());
        assertEquals(0, bar.getTopPadding(), 0);
        assertNull(ReflectionHelpers.getField(bar, "animatorSet"));
    }

    private void activePlayback() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 42;
        playing = new MessageObject(0, raw, "", null, null, false, false, false, false);
        ReflectionHelpers.setField(bar, "lastMessageObject", playing);
    }

    @Test public void activePlaybackStillPausesAndResumes() {
        activePlayback();
        button("playButton").performClick();
        assertTrue(paused);
        button("playButton").performClick();
        assertFalse(paused);
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(0, cleanups);
    }

    @Test public void closeStopsActivePlaybackEvenWithoutNotificationDelivery() {
        activePlayback();
        button("closeButton").performClick();
        finishAnimations();
        assertNull(playing);
        assertEquals(1, cleanups);
        assertEquals(View.GONE, bar.getVisibility());
    }

    @Test public void resumeKeepsPausedPlaybackAvailable() {
        activePlayback();
        paused = true;
        bar.refreshPlayerState();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertNotNull(playing);
        assertEquals(36, bar.getTopPadding(), 0);
    }
}
