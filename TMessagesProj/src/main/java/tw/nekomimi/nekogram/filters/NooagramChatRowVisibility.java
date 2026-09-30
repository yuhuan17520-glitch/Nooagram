package tw.nekomimi.nekogram.filters;

import org.telegram.messenger.MessageObject;

import java.util.List;
import java.util.function.Predicate;

/** Visibility of synthetic chat rows after message filtering. */
public final class NooagramChatRowVisibility {
    private NooagramChatRowVisibility() {
    }

    public static boolean hasVisibleMessageForDate(List<MessageObject> messages, int dateIndex,
                                                   boolean reversed, Predicate<MessageObject> isHidden) {
        MessageObject date = messages.get(dateIndex);
        // Normally a date follows its day's messages in adapter order. Reversed lists
        // put the date first. Never borrow a visible message from a neighboring day.
        int step = reversed ? 1 : -1;
        for (int i = dateIndex + step; i >= 0 && i < messages.size(); i += step) {
            MessageObject candidate = messages.get(i);
            // Unread dividers can have date=0; they must not terminate a day scan.
            if (candidate == null || candidate.contentType == 2 || candidate.isVideoConversionObject) {
                continue;
            }
            if (candidate.dateKeyInt != date.dateKeyInt) {
                break;
            }
            if (!candidate.isDateObject && !isHidden.test(candidate)) {
                return true;
            }
        }
        return false;
    }
}
