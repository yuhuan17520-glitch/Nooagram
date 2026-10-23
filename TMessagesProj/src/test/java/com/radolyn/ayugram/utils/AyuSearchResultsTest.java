package com.radolyn.ayugram.utils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class AyuSearchResultsTest {
    @Before public void setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
    }

    @Test public void sameIdInDifferentDialogsIsNotDeduplicated() {
        MessageObject official = message(-10, 50, 20);
        MessageObject archive = message(-20, 50, 10);
        ArrayList<MessageObject> results = new ArrayList<>(List.of(official));
        AyuSearchResults.appendArchived(results, List.of(archive));
        assertEquals(List.of(official, archive), results);
    }

    @Test public void officialCopyWinsAndDuplicateArchivedRowsAreIdempotent() {
        MessageObject official = message(-10, 50, 20);
        MessageObject archive = message(-10, 40, 10);
        ArrayList<MessageObject> results = new ArrayList<>(List.of(official));
        List<MessageObject> archived = List.of(message(-10, 50, 20), archive, message(-10, 40, 10));
        AyuSearchResults.appendArchived(results, archived);
        AyuSearchResults.appendArchived(results, archived);
        assertEquals(List.of(official, archive), results);
        assertSame(official, results.get(0));
        assertTrue(archive.isSavedFiltered);
    }

    @Test public void mergedChatSearchUsesChronologyNotUnrelatedMessageIds() {
        MessageObject oldGroup = message(-10, 10000, 10);
        MessageObject newChannel = message(-20, 2, 30);
        MessageObject archive = message(-20, 1, 20);
        ArrayList<MessageObject> results = new ArrayList<>(List.of(newChannel, oldGroup));
        AyuSearchResults.appendArchived(results, List.of(archive));
        assertEquals(List.of(newChannel, archive, oldGroup), results);
    }

    @Test public void officialPagesAreNotMutatedAndNullArchiveRowsAreIgnored() {
        MessageObject official = message(-10, 50, 20);
        ArrayList<MessageObject> officialPage = new ArrayList<>(List.of(official));
        ArrayList<MessageObject> results = new ArrayList<>(officialPage);
        AyuSearchResults.appendArchived(results, Arrays.asList(null, message(-10, 100, 30)));
        assertEquals(1, officialPage.size());
        assertSame(official, officialPage.get(0));
        assertEquals(2, results.size());
    }

    private static MessageObject message(long dialog, int id, int date) {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = id;
        raw.dialog_id = dialog;
        raw.date = date;
        return new MessageObject(0, raw, "", null, null, false, false, false, false);
    }
}
