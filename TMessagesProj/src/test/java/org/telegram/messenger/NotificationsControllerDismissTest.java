package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.SparseArray;

import androidx.collection.LongSparseArray;

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
    public void liveDeliveryFilterDropsDismissedMessageAndKeepsNewerMessage() {
        NotificationDismissState.dismiss(preferences, -1479646397, 400);
        MessageObject dismissed = message(-1479646397, 1, 400);
        MessageObject newer = message(-1479646397, 2, 401);
        ArrayList<MessageObject> delivered = new ArrayList<>(Arrays.asList(dismissed, newer));

        ArrayList<MessageObject> deliverable = NotificationsController.getVisiblePushMessages(preferences, delivered);

        assertEquals(1, deliverable.size());
        assertSame(newer, deliverable.get(0));
    }

    @Test
    public void controllerKeepsStoryPushesOutsideMessageDismissalCutoff() {
        NotificationDismissState.dismiss(preferences, 101, 300);
        MessageObject story = message(101, 1, 200);
        story.isStoryPush = true;

        assertFalse(NotificationsController.isMessageDismissed(preferences, 101, story));
    }

    @Test
    public void staleQueueReconciliationDropsDismissedChildAndKeepsNewerArrival() {
        long channelId = 1479646397;
        NotificationDismissState.dismiss(preferences, -channelId, 400);
        MessageObject oldChild = channelMessage(channelId, 1, 400);
        MessageObject newArrival = channelMessage(channelId, 2, 401);
        MessageObject otherDialog = message(-200, 3, 400);
        ArrayList<MessageObject> queue = new ArrayList<>(Arrays.asList(oldChild, newArrival, otherDialog));
        LongSparseArray<SparseArray<MessageObject>> messagesById = new LongSparseArray<>();
        SparseArray<MessageObject> childMessages = new SparseArray<>();
        childMessages.put(oldChild.getId(), oldChild);
        childMessages.put(newArrival.getId(), newArrival);
        messagesById.put(-channelId, childMessages);

        ArrayList<MessageObject> removed = NotificationsController.removeDismissedPushMessages(preferences, queue, messagesById);

        assertEquals(1, removed.size());
        assertSame(oldChild, removed.get(0));
        assertEquals(2, queue.size());
        assertTrue(queue.contains(newArrival));
        assertTrue(queue.contains(otherDialog));
        assertEquals(1, childMessages.size());
        assertSame(newArrival, childMessages.get(newArrival.getId()));
    }

    @Test
    public void mixedSummaryClearsOlderStoryAndDismissedMessageInOneReconciliation() {
        assertMixedSummaryReconciliation(400L * 1000L - 1000L);
    }

    @Test
    public void mixedSummaryClearsNewerStoryAndDismissedMessageInOneReconciliation() {
        assertMixedSummaryReconciliation(400L * 1000L + 1000L);
    }

    @Test
    public void mixedSummaryMarksStoryCleanupWhenNewestItemIsMessage() {
        MessageObject latestMessage = message(-1479646397, 2, 500);
        NotificationsController.StoryNotification olderStory =
                new NotificationsController.StoryNotification(-200, "Story", 1, 400L * 1000L);
        ArrayList<NotificationsController.StoryNotification> stories =
                new ArrayList<>(Arrays.asList(olderStory));

        Intent dismissIntent = new Intent();
        NotificationsController.setSummaryDismissExtras(dismissIntent, latestMessage, stories);

        assertTrue(dismissIntent.getBooleanExtra("story", false));
        assertTrue(dismissIntent.getBooleanExtra(NotificationDismissState.EXTRA_DISMISS_MESSAGES, false));
        assertEquals(500, dismissIntent.getIntExtra(NotificationDismissState.EXTRA_MESSAGE_DATE, 0));
    }

    @Test
    public void mixedSummaryMarksStoryCleanupWhenNewestItemIsStory() {
        MessageObject olderMessage = message(-1479646397, 2, 400);
        NotificationsController.StoryNotification newerStory =
                new NotificationsController.StoryNotification(-200, "Story", 1, 500L * 1000L);
        ArrayList<NotificationsController.StoryNotification> stories =
                new ArrayList<>(Arrays.asList(newerStory));

        Intent dismissIntent = new Intent();
        NotificationsController.setSummaryDismissExtras(dismissIntent, olderMessage, stories);

        assertTrue(dismissIntent.getBooleanExtra("story", false));
        assertTrue(dismissIntent.getBooleanExtra(NotificationDismissState.EXTRA_DISMISS_MESSAGES, false));
        assertEquals(400, dismissIntent.getIntExtra(NotificationDismissState.EXTRA_MESSAGE_DATE, 0));
    }

    @Test
    public void dismissedChannelPushUsesNegativeChannelPeerKey() {
        long channelId = 1479646397;
        MessageObject channelMessage = channelMessage(channelId, 1, 400);
        assertEquals(-channelId, NotificationsController.getPushMessagesDictKey(channelMessage));
        NotificationDismissState.dismiss(preferences, -channelId, 400);
        ArrayList<MessageObject> messages = new ArrayList<>(Arrays.asList(channelMessage));
        LongSparseArray<SparseArray<MessageObject>> messagesDict = new LongSparseArray<>();
        SparseArray<MessageObject> channelMessages = new SparseArray<>();
        channelMessages.put(channelMessage.getId(), channelMessage);
        messagesDict.put(-channelId, channelMessages);

        ArrayList<MessageObject> removed = NotificationsController.removeDismissedPushMessages(
                preferences, messages, messagesDict);

        assertEquals(1, removed.size());
        assertTrue(messages.isEmpty());
        assertEquals(0, messagesDict.size());
    }

    @Test
    public void dismissedBasicGroupPushUsesSharedZeroPeerKey() {
        long chatId = 987654321;
        MessageObject chatMessage = chatMessage(chatId, 1, 400);
        assertEquals(0, NotificationsController.getPushMessagesDictKey(chatMessage));
        NotificationDismissState.dismiss(preferences, -chatId, 400);
        ArrayList<MessageObject> messages = new ArrayList<>(Arrays.asList(chatMessage));
        LongSparseArray<SparseArray<MessageObject>> messagesDict = new LongSparseArray<>();
        SparseArray<MessageObject> basicGroupMessages = new SparseArray<>();
        basicGroupMessages.put(chatMessage.getId(), chatMessage);
        messagesDict.put(0, basicGroupMessages);

        ArrayList<MessageObject> removed = NotificationsController.removeDismissedPushMessages(
                preferences, messages, messagesDict);

        assertEquals(1, removed.size());
        assertTrue(messages.isEmpty());
        assertEquals(0, messagesDict.size());
    }

    private void assertMixedSummaryReconciliation(long storyDate) {
        NotificationDismissState.dismiss(preferences, -1479646397, 400);
        MessageObject dismissed = message(-1479646397, 1, 400);
        MessageObject newer = message(-1479646397, 2, 401);
        ArrayList<MessageObject> messages = new ArrayList<>(Arrays.asList(dismissed, newer));
        LongSparseArray<SparseArray<MessageObject>> messagesDict = new LongSparseArray<>();
        SparseArray<MessageObject> messagesById = new SparseArray<>();
        messagesById.put(dismissed.getId(), dismissed);
        messagesById.put(newer.getId(), newer);
        messagesDict.put(-1479646397, messagesById);
        NotificationsController.StoryNotification story =
                new NotificationsController.StoryNotification(-1479646397, "Story", 1, storyDate);
        NotificationsController.StoryNotification newerStory =
                new NotificationsController.StoryNotification(-200, "Other", 2, storyDate + 1000);
        ArrayList<NotificationsController.StoryNotification> stories =
                new ArrayList<>(Arrays.asList(story, newerStory));
        LongSparseArray<NotificationsController.StoryNotification> storiesDict = new LongSparseArray<>();
        storiesDict.put(story.dialogId, story);
        storiesDict.put(newerStory.dialogId, newerStory);

        ArrayList<MessageObject> removed = NotificationsController.reconcileDismissedSummary(
                preferences, messages, messagesDict, stories, storiesDict);

        assertEquals(1, removed.size());
        assertSame(dismissed, removed.get(0));
        assertEquals(1, messages.size());
        assertSame(newer, messages.get(0));
        assertTrue(stories.isEmpty());
        assertEquals(0, storiesDict.size());
    }

    private static MessageObject message(long dialogId, int id, int date) {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = id;
        raw.dialog_id = dialogId;
        raw.date = date;
        return new MessageObject(0, raw, "", null, null, false, false, false, false);
    }

    private static MessageObject channelMessage(long channelId, int id, int date) {
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = channelId;
        return message(peer, id, date);
    }

    private static MessageObject chatMessage(long chatId, int id, int date) {
        TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
        peer.chat_id = chatId;
        return message(peer, id, date);
    }

    private static MessageObject message(TLRPC.Peer peer, int id, int date) {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = id;
        raw.peer_id = peer;
        raw.date = date;
        return new MessageObject(0, raw, "", null, null, false, false, false, false);
    }
}
