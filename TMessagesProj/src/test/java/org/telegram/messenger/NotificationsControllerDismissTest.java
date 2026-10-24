package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class NotificationsControllerDismissTest {

    private SharedPreferences preferences;

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void controllerSelectsNewestReconstructedMessageRegardlessOfListOrder() {
        MessageObject older = message(101, 1, 200);
        MessageObject newest = message(101, 2, 300);
        ArrayList<MessageObject> reconstructed = new ArrayList<>(Arrays.asList(older, newest));

        assertSame(newest, NotificationsController.getLatestPushMessage(reconstructed));
    }

    @Test
    public void controllerHonorsPersistedSummaryDismissalAndAllowsNewerMessage() {
        NotificationDismissState.dismiss(preferences, 0, 300);

        assertTrue(NotificationsController.isMessageDismissed(preferences, 0, message(101, 1, 300)));
        assertFalse(NotificationsController.isMessageDismissed(preferences, 0, message(101, 2, 301)));
    }

    @Test
    public void controllerSummaryExcludesDismissedChildAndUsesRemainingDialog() {
        NotificationDismissState.dismiss(preferences, 101, 300);
        MessageObject dismissed = message(101, 1, 300);
        MessageObject remaining = message(202, 2, 250);
        ArrayList<MessageObject> messages = new ArrayList<>(Arrays.asList(dismissed, remaining));

        ArrayList<MessageObject> visible = NotificationsController.getVisiblePushMessages(preferences, messages);

        assertEquals(1, visible.size());
        assertSame(remaining, visible.get(0));
        assertSame(remaining, NotificationsController.getLatestPushMessage(visible));
        assertEquals(1, NotificationsController.getPushDialogCount(visible));
    }

    @Test
    public void controllerSummaryAllowsNewerMessageInDismissedDialog() {
        NotificationDismissState.dismiss(preferences, 101, 300);
        MessageObject dismissed = message(101, 1, 300);
        MessageObject newer = message(101, 2, 301);
        ArrayList<MessageObject> messages = new ArrayList<>(Arrays.asList(dismissed, newer));

        ArrayList<MessageObject> visible = NotificationsController.getVisiblePushMessages(preferences, messages);

        assertEquals(1, visible.size());
        assertSame(newer, visible.get(0));
        assertEquals(1, NotificationsController.getPushDialogCount(visible));
    }

    @Test
    public void controllerKeepsStoryPushesOutsideMessageDismissalCutoff() {
        NotificationDismissState.dismiss(preferences, 101, 300);
        MessageObject story = message(101, 1, 200);
        story.isStoryPush = true;

        assertFalse(NotificationsController.isMessageDismissed(preferences, 101, story));
    }

    private static MessageObject message(long dialogId, int id, int date) {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = id;
        raw.dialog_id = dialogId;
        raw.date = date;
        return new MessageObject(0, raw, "", null, null, false, false, false, false);
    }
}
