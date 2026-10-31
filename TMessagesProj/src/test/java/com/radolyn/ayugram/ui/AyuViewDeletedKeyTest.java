package com.radolyn.ayugram.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class AyuViewDeletedKeyTest {
    @Test
    public void menuTargetIsRelocatedAfterPrependingAPage() {
        DeletedMessageFull target = message(10L, -100L, 123);
        ArrayList<DeletedMessageFull> messages = new ArrayList<>(Arrays.asList(target, message(10L, -100L, 124)));
        AyuViewDeleted.ArchiveMessageKey menuKey = new AyuViewDeleted.ArchiveMessageKey(target);

        messages.add(0, message(10L, -100L, 122));

        assertEquals(1, menuKey.findIndex(messages));
        assertSame(target, messages.remove(menuKey.findIndex(messages)));
        assertEquals(-1, menuKey.findIndex(messages));
        assertEquals(122, messages.get(0).message.messageId);
    }

    @Test
    public void anchorFollowsItsMessageAcrossInsertion() {
        ArrayList<DeletedMessageFull> messages = new ArrayList<>(Arrays.asList(
                message(10L, -100L, 123), message(10L, -100L, 124)));
        AyuViewDeleted.ArchiveMessageKey anchor = new AyuViewDeleted.ArchiveMessageKey(messages.get(1));

        messages.addAll(0, Arrays.asList(message(10L, -100L, 121), message(10L, -100L, 122)));

        assertEquals(3, anchor.findIndex(messages));
        assertEquals(124, messages.get(anchor.findIndex(messages)).message.messageId);
    }

    @Test
    public void sameMessageIdInAnotherAccountOrDialogDoesNotMatch() {
        AyuViewDeleted.ArchiveMessageKey key = new AyuViewDeleted.ArchiveMessageKey(10L, -100L, 123);

        assertFalse(key.matches(message(11L, -100L, 123)));
        assertFalse(key.matches(message(10L, -200L, 123)));
        assertFalse(key.matches(message(10L, -100L, 124)));
        assertFalse(key.matches(null));
        assertEquals(-1, key.findIndex(Arrays.asList(message(11L, -100L, 123), message(10L, -200L, 123))));
    }

    private static DeletedMessageFull message(long userId, long dialogId, int messageId) {
        DeletedMessageFull full = new DeletedMessageFull();
        full.message = new DeletedMessage();
        full.message.userId = userId;
        full.message.dialogId = dialogId;
        full.message.messageId = messageId;
        return full;
    }
}
