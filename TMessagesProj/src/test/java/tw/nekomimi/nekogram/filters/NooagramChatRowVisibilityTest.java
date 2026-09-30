package tw.nekomimi.nekogram.filters;

import org.junit.Test;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NooagramChatRowVisibilityTest {
    private static final Predicate<MessageObject> HIDDEN = message -> message.messageOwner.hide;

    @Test
    public void removesConsecutiveFilteredDaysWithoutBorrowingOlderVisibleMessages() {
        List<MessageObject> rows = Arrays.asList(message(30, true), date(30),
                message(29, true), date(29), message(28, false), date(28));
        assertFalse(visible(rows, 1, false));
        assertFalse(visible(rows, 3, false));
        assertTrue(visible(rows, 5, false));
    }

    @Test
    public void retainsDateWhenAnyMessageOnTheDayRemainsVisible() {
        List<MessageObject> rows = Arrays.asList(message(30, true), message(30, false),
                message(30, true), date(30));
        assertTrue(visible(rows, 3, false));
    }

    @Test
    public void reversedListsLookAfterRatherThanBeforeTheDate() {
        List<MessageObject> rows = Arrays.asList(date(28), message(28, false),
                date(29), message(29, true), date(30), message(30, true));
        assertTrue(visible(rows, 0, true));
        assertFalse(visible(rows, 2, true));
        assertFalse(visible(rows, 4, true));
    }

    @Test
    public void syntheticRowsDoNotKeepAnEmptyDayVisible() {
        MessageObject unread = message(0, false);
        unread.contentType = 2;
        MessageObject conversion = date(30);
        conversion.isVideoConversionObject = true;
        List<MessageObject> rows = Arrays.asList(message(30, true), null, unread, conversion, date(30));
        assertFalse(visible(rows, 4, false));
    }

    @Test
    public void unreadDividerWithNoDateDoesNotHideVisibleContentBehindIt() {
        MessageObject unread = message(0, false);
        unread.contentType = 2;
        List<MessageObject> rows = Arrays.asList(message(30, false), unread, message(30, true), date(30));
        assertTrue(visible(rows, 3, false));
        Collections.reverse(rows);
        assertTrue(visible(rows, 0, true));
    }

    @Test
    public void realServiceMessagesKeepTheirDate() {
        MessageObject service = message(30, false);
        service.contentType = 1;
        List<MessageObject> rows = Arrays.asList(message(30, true), service, date(30));
        assertTrue(visible(rows, 2, false));
    }

    @Test
    public void showingFilteredMessagesRestoresTheDateWithoutChangingTheList() {
        MessageObject hidden = message(30, true);
        List<MessageObject> rows = Arrays.asList(hidden, date(30));
        assertFalse(visible(rows, 1, false));
        assertTrue(NooagramChatRowVisibility.hasVisibleMessageForDate(rows, 1, false, m -> false));
        assertFalse(visible(rows, 1, false));
    }

    @Test
    public void emptyAndAdjacentDateRowsAreHidden() {
        assertFalse(visible(Collections.singletonList(date(30)), 0, false));
        assertFalse(visible(Collections.singletonList(date(30)), 0, true));
        List<MessageObject> rows = Arrays.asList(date(30), date(29));
        assertFalse(visible(rows, 0, false));
        assertFalse(visible(rows, 1, false));
        assertFalse(visible(rows, 0, true));
        assertFalse(visible(rows, 1, true));
    }

    @Test
    public void doesNotBorrowContentAcrossDaysEvenWhenThereIsNoNextDateRow() {
        List<MessageObject> rows = Arrays.asList(message(29, false), message(30, true), date(30));
        assertFalse(visible(rows, 2, false));
        Collections.reverse(rows);
        assertFalse(visible(rows, 0, true));
    }

    private static boolean visible(List<MessageObject> rows, int dateIndex, boolean reversed) {
        return NooagramChatRowVisibility.hasVisibleMessageForDate(rows, dateIndex, reversed, HIDDEN);
    }

    private static MessageObject message(int day, boolean hidden) {
        TLRPC.TL_message owner = new TLRPC.TL_message();
        owner.hide = hidden;
        MessageObject message = new MessageObject(0, owner, "", null, null, false, false, false, false);
        message.dateKeyInt = day;
        message.contentType = 0;
        return message;
    }

    private static MessageObject date(int day) {
        MessageObject date = message(day, false);
        date.type = MessageObject.TYPE_DATE;
        date.contentType = 1;
        date.isDateObject = true;
        return date;
    }
}
