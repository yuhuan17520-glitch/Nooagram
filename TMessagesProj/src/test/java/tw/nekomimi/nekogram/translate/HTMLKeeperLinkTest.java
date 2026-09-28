package tw.nekomimi.nekogram.translate;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HTMLKeeperLinkTest {
    @Test
    public void formattedSegmentsKeepTheirOriginalDestination() {
        TLRPC.TL_messageEntityTextUrl first = new TLRPC.TL_messageEntityTextUrl();
        first.url = "https://first.example";
        TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
        TLRPC.TL_messageEntityTextUrl second = new TLRPC.TL_messageEntityTextUrl();
        second.url = "https://second.example";
        ArrayList<TLRPC.MessageEntity> originals = new ArrayList<>();
        originals.add(first);
        originals.add(bold);
        originals.add(second);
        assertSame(first, HTMLKeeper.originalLink(originals, 0, "https://telegram.org/"));
        assertSame(first, HTMLKeeper.originalLink(originals, 0, "https://telegram.org/"));
        assertSame(second, HTMLKeeper.originalLink(originals, 2, "https://telegram.org/"));
        assertEquals(3, originals.size());
        assertSame(first, HTMLKeeper.originalLink(originals, -1, first.url));
        assertNull(HTMLKeeper.originalLink(originals, 99, "https://unknown.example"));
    }

    @Test
    public void identifiedLinkIsConsumedByFallbackButCanBeRepeatedByIdentity() {
        TLRPC.TL_messageEntityTextUrl first = new TLRPC.TL_messageEntityTextUrl();
        first.url = "https://first.example";
        TLRPC.TL_messageEntityTextUrl second = new TLRPC.TL_messageEntityTextUrl();
        second.url = "https://second.example";
        ArrayList<TLRPC.MessageEntity> originals = new ArrayList<>();
        originals.add(first);
        originals.add(second);
        ArrayList<TLRPC.MessageEntity> fallback = new ArrayList<>(originals);

        TLRPC.MessageEntity identified = HTMLKeeper.mapLinkEntity(
                originals, fallback, 0, "https://telegram.org/");
        assertNotNull(identified);
        assertEquals(first.url, identified.url);
        assertEquals(1, fallback.size());
        assertSame(second, fallback.get(0));

        TLRPC.MessageEntity repeated = HTMLKeeper.mapLinkEntity(
                originals, fallback, 0, "https://telegram.org/");
        assertNotNull(repeated);
        assertEquals(first.url, repeated.url);
        assertEquals(1, fallback.size());

        TLRPC.MessageEntity identityless = HTMLKeeper.mapLinkEntity(
                originals, fallback, -1, "https://telegram.org/");
        assertNotNull(identityless);
        assertEquals(second.url, identityless.url);
        assertTrue(fallback.isEmpty());
        assertEquals(2, originals.size());
        assertSame(first, originals.get(0));
        assertSame(second, originals.get(1));
    }

    @Test
    public void hrefMatchAlsoConsumesFallbackWithoutLosingRepeatedLink() {
        TLRPC.TL_messageEntityTextUrl first = new TLRPC.TL_messageEntityTextUrl();
        first.url = "https://first.example";
        TLRPC.TL_messageEntityTextUrl second = new TLRPC.TL_messageEntityTextUrl();
        second.url = "https://second.example";
        ArrayList<TLRPC.MessageEntity> originals = new ArrayList<>();
        originals.add(first);
        originals.add(second);
        ArrayList<TLRPC.MessageEntity> fallback = new ArrayList<>(originals);

        assertEquals(first.url, HTMLKeeper.mapLinkEntity(originals, fallback, -1, first.url).url);
        assertEquals(first.url, HTMLKeeper.mapLinkEntity(originals, fallback, -1, first.url).url);
        assertEquals(1, fallback.size());
        assertEquals(second.url, HTMLKeeper.mapLinkEntity(originals, fallback, -1, "https://telegram.org/").url);
        assertTrue(fallback.isEmpty());
    }
}
