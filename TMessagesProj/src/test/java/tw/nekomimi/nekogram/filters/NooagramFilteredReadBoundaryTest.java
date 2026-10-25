package tw.nekomimi.nekogram.filters;

import com.radolyn.ayugram.utils.AyuGhostPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class NooagramFilteredReadBoundaryTest {
    private static final long PRIMARY_DIALOG_ID = 100L;
    private static final long SECONDARY_DIALOG_ID = 200L;
    private static final int LIST_HEIGHT = 1000;
    private static final int PADDING_BOTTOM = 100;
    private static final int VIEWPORT_BOTTOM = LIST_HEIGHT - PADDING_BOTTOM;
    private static final int TOLERANCE = 5;

    @Before
    public void setupApplicationContext() {
        org.telegram.messenger.ApplicationLoader.applicationContext =
                org.robolectric.RuntimeEnvironment.getApplication();
    }

    @Test
    public void allFilteredLatestTailResolvesToLatestLoadedMessage() {
        NooagramFilteredReadBoundary.Boundary latest =
                NooagramFilteredReadBoundary.latestLoadedBoundary(messages(101, 102, 103), PRIMARY_DIALOG_ID);

        assertTrue(canUseLatestLoadedBoundary(8, VIEWPORT_BOTTOM));
        assertEquals(103, resolve(emptyBoundary(), latest, true).maxPositiveId);
        assertEquals(123, resolve(emptyBoundary(), latest, true).maxDate);
    }

    @Test
    public void allFilteredRowsWithoutVisibleChildrenRequireValidatedEdgeSignal() {
        assertTrue(canUseLatestLoadedBoundary(-1, VIEWPORT_BOTTOM, true));
        assertFalse(canUseLatestLoadedBoundary(-1, VIEWPORT_BOTTOM, false));
        assertFalse(canUseLatestLoadedBoundary(-1, VIEWPORT_BOTTOM + TOLERANCE + 1, true));
    }

    @Test
    public void paddedViewportBottomRejectsTheUnpaddedListHeight() {
        assertTrue(canUseLatestLoadedBoundary(8, VIEWPORT_BOTTOM));
        assertFalse(canUseLatestLoadedBoundary(8, LIST_HEIGHT));
        assertTrue(canUseLatestLoadedBoundary(-1, VIEWPORT_BOTTOM, true));
        assertFalse(canUseLatestLoadedBoundary(-1, LIST_HEIGHT, true));
    }

    @Test
    public void mixedVisibleAndFilteredTailMovesBoundaryPastTheVisibleUnread() {
        NooagramFilteredReadBoundary.Boundary visible =
                new NooagramFilteredReadBoundary.Boundary(101, 121);
        NooagramFilteredReadBoundary.Boundary latest =
                NooagramFilteredReadBoundary.latestLoadedBoundary(messages(101, 102, 103), PRIMARY_DIALOG_ID);

        NooagramFilteredReadBoundary.Boundary resolved = resolve(visible, latest, true);

        assertEquals(103, resolved.maxPositiveId);
        assertEquals(123, resolved.maxDate);
    }

    @Test
    public void olderVisibleUnreadDoesNotAdvanceWhileAwayFromBottom() {
        NooagramFilteredReadBoundary.Boundary visible =
                new NooagramFilteredReadBoundary.Boundary(98, 118);
        NooagramFilteredReadBoundary.Boundary latest =
                NooagramFilteredReadBoundary.latestLoadedBoundary(messages(98, 102, 103), PRIMARY_DIALOG_ID);

        assertFalse(canUseLatestLoadedBoundary(1, VIEWPORT_BOTTOM + TOLERANCE + 1));
        NooagramFilteredReadBoundary.Boundary resolved = resolve(visible, latest, false);

        assertEquals(98, resolved.maxPositiveId);
        assertEquals(118, resolved.maxDate);
    }

    @Test
    public void filteredPagedownSuppressionRequiresAValidatedLatestTail() {
        NooagramFilteredReadBoundary.Boundary latest =
                NooagramFilteredReadBoundary.latestLoadedBoundary(messages(101), PRIMARY_DIALOG_ID);
        NooagramFilteredReadBoundary.Boundary unresolved =
                NooagramFilteredReadBoundary.latestLoadedBoundary(Arrays.asList(), PRIMARY_DIALOG_ID);

        assertTrue(latest.hasMessage() && canUseLatestLoadedBoundary(-1, VIEWPORT_BOTTOM, true));
        assertFalse(unresolved.hasMessage() && canUseLatestLoadedBoundary(-1, VIEWPORT_BOTTOM, true));
        assertFalse(latest.hasMessage()
                && canUseLatestLoadedBoundary(8, VIEWPORT_BOTTOM + TOLERANCE + 1));
    }

    @Test
    public void loadingOlderHistoryCannotAdvanceToTheNewestBoundary() {
        assertFalse(canUseWithState(false, true, false, 8, false));
    }

    @Test
    public void loadingForwardCannotAdvanceToTheNewestBoundary() {
        assertFalse(canUseWithState(false, false, true, 8, false));
    }

    @Test
    public void bottomRequiresAVisibleRowAndTheLatestAdapterAnchorAtTheEdge() {
        assertFalse(canUseLatestLoadedBoundary(-1, VIEWPORT_BOTTOM));
        assertFalse(canUseLatestLoadedBoundary(8, VIEWPORT_BOTTOM + TOLERANCE + 1));
        assertFalse(NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, true, false, false, true, false, false, false,
                false, false, false,
                8, -1, VIEWPORT_BOTTOM, false, VIEWPORT_BOTTOM, TOLERANCE));
    }

    @Test
    public void boundaryIsNotReissuedAfterTheFilteredTailWasRead() {
        List<MessageObject> messages = messages(101, 102, 103);

        assertTrue(NooagramFilteredReadBoundary.hasUnreadMessageAfter(messages, PRIMARY_DIALOG_ID, 100));
        for (MessageObject message : messages) {
            message.setIsRead();
        }
        assertFalse(NooagramFilteredReadBoundary.hasUnreadMessageAfter(messages, PRIMARY_DIALOG_ID, 100));
    }

    @Test
    public void onlyTheLatestPrimaryDialogMessageContributesToTheBoundary() {
        MessageObject secondary = message(200, SECONDARY_DIALOG_ID, 200);

        NooagramFilteredReadBoundary.Boundary latest =
                NooagramFilteredReadBoundary.latestLoadedBoundary(
                        Arrays.asList(message(101, PRIMARY_DIALOG_ID, 121), secondary), PRIMARY_DIALOG_ID);

        assertEquals(101, latest.maxPositiveId);
        assertEquals(121, latest.maxDate);
    }

    @Test
    public void automaticReadPolicyHonorsGlobalStateAndPerDialogOverrides() {
        assertFalse(NooagramFilteredReadBoundary.isAutomaticReadBlocked(
                true, AyuGhostPreferences.TYPE_DEFAULT));
        assertTrue(NooagramFilteredReadBoundary.isAutomaticReadBlocked(
                true, AyuGhostPreferences.TYPE_FORCE_BLOCK));
        assertTrue(NooagramFilteredReadBoundary.isAutomaticReadBlocked(
                false, AyuGhostPreferences.TYPE_DEFAULT));
        assertFalse(NooagramFilteredReadBoundary.isAutomaticReadBlocked(
                false, AyuGhostPreferences.TYPE_FORCE_ALLOW));
        assertFalse(canUseWithState(true, false, false, 8, false));
    }

    @Test
    public void filteredBottomBoundaryHonorsSafetyGates() {
        assertTrue(canUseLatestLoadedBoundary(4, VIEWPORT_BOTTOM));
        assertFalse(NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, false, true, false, false, true, false, false, false,
                false, false, false,
                4, 0, VIEWPORT_BOTTOM, false, VIEWPORT_BOTTOM, TOLERANCE));
        assertFalse(NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, false, false, false, true, false, false, false,
                false, false, false,
                4, 0, VIEWPORT_BOTTOM, false, VIEWPORT_BOTTOM, TOLERANCE));
        assertFalse(NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, true, true, false, true, false, false, false,
                false, false, false,
                4, 0, VIEWPORT_BOTTOM, false, VIEWPORT_BOTTOM, TOLERANCE));
        assertFalse(NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, true, false, true, true, false, false, false,
                false, false, false,
                4, 0, VIEWPORT_BOTTOM, false, VIEWPORT_BOTTOM, TOLERANCE));
        assertFalse(NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, true, false, false, false, false, false, false,
                false, false, false,
                4, 0, VIEWPORT_BOTTOM, false, VIEWPORT_BOTTOM, TOLERANCE));
        assertFalse(NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, true, false, false, true, true, false, false,
                false, false, false,
                4, 0, VIEWPORT_BOTTOM, false, VIEWPORT_BOTTOM, TOLERANCE));
        assertFalse(NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, true, false, false, true, false, true, false,
                false, false, false,
                4, 0, VIEWPORT_BOTTOM, false, VIEWPORT_BOTTOM, TOLERANCE));
        assertFalse(NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, true, false, false, true, false, false, true,
                false, false, false,
                4, 0, VIEWPORT_BOTTOM, false, VIEWPORT_BOTTOM, TOLERANCE));
    }

    @Test
    public void reentryOrBottomButtonReachingLatestRowsUsesTheFullBoundary() {
        NooagramFilteredReadBoundary.Boundary latest =
                NooagramFilteredReadBoundary.latestLoadedBoundary(messages(101, 102), PRIMARY_DIALOG_ID);

        assertFalse(canUseLatestLoadedBoundary(8, VIEWPORT_BOTTOM + TOLERANCE + 1));
        assertTrue(canUseLatestLoadedBoundary(8, VIEWPORT_BOTTOM + TOLERANCE));
        assertEquals(102, resolve(new NooagramFilteredReadBoundary.Boundary(101, 121), latest, true)
                .maxPositiveId);
    }

    private static boolean canUseWithState(boolean automaticReadBlocked, boolean loading,
                                           boolean loadingForward, int firstVisiblePosition,
                                           boolean collapsedRowsAtLatestEdge) {
        return NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, true, false, false, true, false, false, false,
                automaticReadBlocked, loading, loadingForward,
                firstVisiblePosition, 0, VIEWPORT_BOTTOM, collapsedRowsAtLatestEdge,
                VIEWPORT_BOTTOM, TOLERANCE);
    }

    private static boolean canUseLatestLoadedBoundary(int firstVisiblePosition, int latestAdapterBottom) {
        return canUseLatestLoadedBoundary(firstVisiblePosition, latestAdapterBottom, false);
    }

    private static boolean canUseLatestLoadedBoundary(int firstVisiblePosition, int latestAdapterBottom,
                                                      boolean collapsedRowsAtLatestEdge) {
        return NooagramFilteredReadBoundary.canUseLatestLoadedBoundary(
                true, true, true, false, false, true, false, false, false,
                false, false, false,
                firstVisiblePosition, 0, latestAdapterBottom, collapsedRowsAtLatestEdge,
                VIEWPORT_BOTTOM, TOLERANCE);
    }

    private static NooagramFilteredReadBoundary.Boundary resolve(
            NooagramFilteredReadBoundary.Boundary visible,
            NooagramFilteredReadBoundary.Boundary latest,
            boolean atLatestBottom) {
        return NooagramFilteredReadBoundary.resolveBoundary(visible, latest, atLatestBottom);
    }

    private static NooagramFilteredReadBoundary.Boundary emptyBoundary() {
        return new NooagramFilteredReadBoundary.Boundary(Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    private static List<MessageObject> messages(int... ids) {
        MessageObject[] messages = new MessageObject[ids.length];
        for (int i = 0; i < ids.length; i++) {
            messages[i] = message(ids[i], PRIMARY_DIALOG_ID, 120 + ids[i] - 100);
        }
        return Arrays.asList(messages);
    }

    private static MessageObject message(int id, long dialogId, int date) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.date = date;
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = dialogId;
        message.peer_id = peer;
        message.unread = true;
        return new MessageObject(0, message, "", null, null, false, false, false, false);
    }
}
