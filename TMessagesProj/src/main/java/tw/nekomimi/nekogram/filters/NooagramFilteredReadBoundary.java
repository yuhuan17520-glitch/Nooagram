package tw.nekomimi.nekogram.filters;

import com.radolyn.ayugram.utils.AyuGhostPreferences;

import org.telegram.messenger.MessageObject;

import java.util.List;

/** Resolves a safe read boundary when filtered messages are collapsed to zero-height rows. */
public final class NooagramFilteredReadBoundary {
    private NooagramFilteredReadBoundary() {
    }

    public static final class Boundary {
        public final int maxPositiveId;
        public final int maxDate;

        public Boundary(int maxPositiveId, int maxDate) {
            this.maxPositiveId = maxPositiveId;
            this.maxDate = maxDate;
        }

        public boolean hasMessage() {
            return maxPositiveId != Integer.MIN_VALUE;
        }
    }

    public static boolean canUseLatestLoadedBoundary(
            boolean forwardEndReached,
            boolean primaryDialog,
            boolean filteredRowsEnabled,
            boolean adapterFiltered,
            boolean adapterFrozen,
            boolean defaultChatMode,
            boolean reversed,
            boolean readSuppressed,
            boolean encrypted,
            boolean automaticReadBlocked,
            boolean loading,
            boolean loadingForward,
            int firstVisiblePosition,
            int latestAdapterPosition,
            int latestAdapterBottom,
            boolean collapsedRowsAtLatestEdge,
            int viewportBottom,
            int tolerancePx) {
        if (!forwardEndReached || !primaryDialog || !filteredRowsEnabled || adapterFiltered
                || adapterFrozen || !defaultChatMode || reversed || readSuppressed || encrypted
                || automaticReadBlocked || loading || loadingForward) {
            return false;
        }
        if (firstVisiblePosition < 0 || latestAdapterPosition != 0
                || viewportBottom <= 0 || tolerancePx < 0) {
            if (firstVisiblePosition < 0 && collapsedRowsAtLatestEdge
                    && latestAdapterPosition == 0 && viewportBottom > 0 && tolerancePx >= 0) {
                return Math.abs(latestAdapterBottom - viewportBottom) <= tolerancePx;
            }
            return false;
        }
        // Adapter row 0 is the latest edge in this reverse-layout chat. Requiring
        // its measured bottom here also accepts zero-height filtered rows above it.
        return Math.abs(latestAdapterBottom - viewportBottom) <= tolerancePx;
    }

    public static boolean isAutomaticReadBlocked(boolean sendReadPackets, int readExceptionType) {
        return sendReadPackets
                ? AyuGhostPreferences.shouldBlockWhenGlobalEnabled(readExceptionType)
                : AyuGhostPreferences.shouldBlockWhenGlobalDisabled(readExceptionType);
    }

    public static Boundary latestLoadedBoundary(List<MessageObject> messages, long primaryDialogId) {
        int maxPositiveId = Integer.MIN_VALUE;
        int maxDate = Integer.MIN_VALUE;
        for (MessageObject message : messages) {
            if (message == null || message.messageOwner == null) {
                continue;
            }
            int id = message.getId();
            if (id <= 0 || message.getDialogId() != primaryDialogId
                    || MessageObject.isEphemeralMessageId(id)) {
                continue;
            }
            maxPositiveId = Math.max(maxPositiveId, id);
            if (message.messageOwner.date > 0) {
                maxDate = Math.max(maxDate, message.messageOwner.date);
            }
        }
        return new Boundary(maxPositiveId, maxDate);
    }

    public static boolean hasUnreadMessageAfter(List<MessageObject> messages, long primaryDialogId,
                                                int readMaxId) {
        for (MessageObject message : messages) {
            if (message == null || message.messageOwner == null) {
                continue;
            }
            int id = message.getId();
            if (id <= readMaxId || id <= 0 || message.getDialogId() != primaryDialogId
                    || MessageObject.isEphemeralMessageId(id) || message.isAyuDeleted()) {
                continue;
            }
            if (message.messageOwner.from_scheduled || !message.isOut() && message.isUnread()) {
                return true;
            }
        }
        return false;
    }

    public static Boundary resolveBoundary(Boundary visibleBoundary, Boundary latestLoadedBoundary,
                                           boolean atLatestBottom) {
        if (!atLatestBottom || latestLoadedBoundary == null || !latestLoadedBoundary.hasMessage()) {
            return visibleBoundary;
        }
        int maxDate = Math.max(visibleBoundary.maxDate, latestLoadedBoundary.maxDate);
        return new Boundary(latestLoadedBoundary.maxPositiveId, maxDate);
    }
}
