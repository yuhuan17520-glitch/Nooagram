package com.radolyn.ayugram.proprietary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.util.HashSet;
import java.util.Set;

public class AyuReplyCacheKeyTest {
    @Test
    public void deduplicatesOnlyTheSameAccountAndTarget() {
        Set<AyuHistoryHook.ReplyCacheKey> keys = new HashSet<>();
        keys.add(new AyuHistoryHook.ReplyCacheKey(0, 10L, -100L, 123));
        keys.add(new AyuHistoryHook.ReplyCacheKey(0, 10L, -100L, 123));
        keys.add(new AyuHistoryHook.ReplyCacheKey(1, 10L, -100L, 123));
        keys.add(new AyuHistoryHook.ReplyCacheKey(0, 11L, -100L, 123));
        keys.add(new AyuHistoryHook.ReplyCacheKey(0, 10L, -200L, 123));
        keys.add(new AyuHistoryHook.ReplyCacheKey(0, 10L, -100L, 124));

        assertEquals(5, keys.size());
    }

    @Test
    public void channelSourceUsesPeerEvenWhenDialogWasPrefilled() {
        TLRPC.TL_message reply = new TLRPC.TL_message();
        reply.dialog_id = -100L;
        reply.peer_id = new TLRPC.TL_peerChannel();
        reply.peer_id.channel_id = 200L;

        assertEquals(-200L, AyuHistoryHook.replySourceDialogId(reply));
        assertNotEquals(new AyuHistoryHook.ReplyCacheKey(0, 10L, -100L, 123),
                new AyuHistoryHook.ReplyCacheKey(0, 10L, AyuHistoryHook.replySourceDialogId(reply), 123));
    }

    @Test
    public void groupSourceUsesActualPeer() {
        TLRPC.TL_message reply = new TLRPC.TL_message();
        reply.dialog_id = -100L;
        reply.peer_id = new TLRPC.TL_peerChat();
        reply.peer_id.chat_id = 300L;

        assertEquals(-300L, AyuHistoryHook.replySourceDialogId(reply));
    }
}
