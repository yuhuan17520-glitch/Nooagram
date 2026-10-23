package com.radolyn.ayugram.utils;

import org.telegram.messenger.MessageObject;

import java.util.HashSet;
import java.util.List;

/** Official pages remain the only pagination source; archived hits are display-only. */
public final class AyuSearchResults {
    private AyuSearchResults() {}

    public static void appendArchived(List<MessageObject> results, List<MessageObject> archived) {
        if (archived.isEmpty()) return;
        HashSet<AyuMessageKey> keys = new HashSet<>();
        for (MessageObject message : results) {
            keys.add(new AyuMessageKey(message.getDialogId(), message.getId()));
        }
        boolean added = false;
        for (MessageObject message : archived) {
            if (message == null || message.messageOwner == null) continue;
            if (keys.add(new AyuMessageKey(message.getDialogId(), message.getId()))) {
                message.isSavedFiltered = true;
                results.add(message);
                added = true;
            }
        }
        if (added) {
            results.sort((a, b) -> {
                // Message IDs are not comparable across a migrated group/channel pair.
                int date = Integer.compare(b.messageOwner.date, a.messageOwner.date);
                return date != 0 ? date : AyuMessageUtils.compareMessages(a.messageOwner, b.messageOwner);
            });
        }
    }
}
