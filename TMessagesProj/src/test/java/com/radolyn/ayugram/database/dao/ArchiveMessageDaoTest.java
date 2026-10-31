package com.radolyn.ayugram.database.dao;

import android.database.Cursor;

import androidx.room.Room;

import com.radolyn.ayugram.database.AyuDatabase;
import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;
import com.radolyn.ayugram.database.entities.EditedMessage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercises SQL and Room transactions without initializing Telegram controllers or native code. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class ArchiveMessageDaoTest {
    private AyuDatabase database;
    private DeletedMessageDao dao;

    @Before public void setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AyuDatabase.class)
                .allowMainThreadQueries().build();
        dao = database.deletedMessageDao();
    }

    @After public void tearDown() {
        database.close();
    }

    @Test public void duplicateBusinessKeyDoesNotDuplicateMessageOrReactions() {
        DeletedMessage original = message(1, -20, 7, 3);
        original.mediaPath = "original-media";
        original.hqThumbPath = "original-thumb";
        DeletedMessage duplicate = message(1, -20, 7, 3);
        duplicate.mediaPath = "duplicate-media";
        duplicate.hqThumbPath = "duplicate-thumb";
        long first = dao.insertIfAbsent(original, Collections.singletonList(reaction()));
        long second = dao.insertIfAbsent(duplicate, Collections.singletonList(reaction()));
        assertTrue(first > 0);
        assertEquals(-first, second);
        assertEquals(1, dao.getTotalCount());
        assertEquals(1, reactionCount());
        List<String> reclaimed = new ArrayList<>();
        dao.reclaimAttachments(Arrays.asList(duplicate.mediaPath, duplicate.hqThumbPath), reclaimed::add);
        assertEquals(Arrays.asList("duplicate-media", "duplicate-thumb"), reclaimed);
        dao.reclaimAttachments(Arrays.asList(original.mediaPath, original.hqThumbPath), reclaimed::add);
        assertEquals(Arrays.asList("duplicate-media", "duplicate-thumb"), reclaimed);
        dao.insertIfAbsent(message(2, -20, 7, 3), Collections.singletonList(reaction()));
        assertEquals(2, dao.getTotalCount());
    }

    @Test public void reactionFailureRollsBackMessageInsert() {
        DeletedMessageReaction existing = reaction();
        existing.fakeReactionId = 55;
        dao.insertReaction(existing);
        DeletedMessageReaction conflict = reaction();
        conflict.fakeReactionId = 55;
        try {
            dao.insertIfAbsent(message(1, -20, 0, 3), Collections.singletonList(conflict));
            fail("Reaction conflict must abort the whole archive insert");
        } catch (RuntimeException expected) {
            assertEquals(0, dao.getTotalCount());
            assertEquals(1, reactionCount());
        }
        dao.deleteOrphanReactions();
        assertEquals(0, reactionCount());
    }

    @Test public void concurrentSavesShareOneRowAndReaction() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return dao.insertIfAbsent(message(1, -20, 7, 3), Collections.singletonList(reaction()));
                }));
            }
            start.countDown();
            long first = results.get(0).get(5, TimeUnit.SECONDS);
            long second = results.get(1).get(5, TimeUnit.SECONDS);
            assertTrue(first != 0);
            assertEquals(-first, second);
            assertEquals(1, dao.getTotalCount());
            assertEquals(1, reactionCount());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test public void lastMessageUsesSecretDialogTypeRatherThanMessageSign() {
        long secret = 0x4000000000000000L | 8;
        dao.insert(message(1, secret, 0, -10));
        dao.insert(message(1, secret, 0, -20));
        dao.insert(message(1, -20, 0, -10));
        dao.insert(message(1, -20, 0, -20));
        dao.insert(message(1, -21, 7, 10));
        dao.insert(message(1, -21, 7, 20));
        dao.insert(message(2, secret, 0, -30));

        List<DeletedMessageFull> last = dao.getLastMessages(1);
        assertEquals(3, last.size());
        for (DeletedMessageFull item : last) {
            int expected = item.message.dialogId == secret ? -20 : item.message.dialogId == -20 ? -10 : 20;
            assertEquals(expected, item.message.messageId);
        }
    }

    @Test public void everyMessageDeletionEntryDeletesReactions() {
        for (int variant = 0; variant < 4; variant++) {
            dao.insertIfAbsent(message(1, -20, 7, 3), Collections.singletonList(reaction()));
            assertEquals(1, reactionCount());
            switch (variant) {
                case 0: dao.delete(1, -20, 3); break;
                case 1: dao.deleteMessages(1, -20, Collections.singletonList(3)); break;
                case 2: dao.deleteByDialogTopic(1, -20, 7); break;
                default: dao.delete(1, -20); break;
            }
            assertEquals(0, dao.getTotalCount());
            assertEquals(0, reactionCount());
        }
    }

    @Test public void dialogCleanupIncludesEditedOnlyMediaAndThumbsAndPreservesOtherTopics() {
        DeletedMessage deleted = message(1, -20, 7, 3);
        deleted.mediaPath = "deleted-media";
        deleted.hqThumbPath = "deleted-thumb";
        dao.insertIfAbsent(deleted, Collections.singletonList(reaction()));
        EditedMessage edited = new EditedMessage();
        edited.fakeId = 10;
        edited.userId = 1;
        edited.dialogId = -20;
        edited.topicId = 7;
        edited.messageId = 4;
        edited.mediaPath = "edited-only-media";
        edited.hqThumbPath = "edited-only-thumb";
        database.editedMessageDao().insert(edited);
        dao.insert(message(1, -20, 8, 5));

        List<String> paths = dao.deleteArchiveDialog(1, -20, 0, 7);
        assertTrue(paths.containsAll(Arrays.asList("deleted-media", "deleted-thumb", "edited-only-media", "edited-only-thumb")));
        assertFalse(database.editedMessageDao().hasAnyRevisions(1, -20, 4));
        assertEquals(0, reactionCount());
        assertEquals(1, dao.getTotalCount());
    }

    @Test public void revisionReclaimChecksOtherAccountsAndBothPathColumns() {
        EditedMessage edited = new EditedMessage();
        edited.fakeId = 10;
        edited.userId = 1;
        edited.dialogId = -20;
        edited.mediaPath = "shared";
        edited.hqThumbPath = "revision-thumb";
        database.editedMessageDao().insert(edited);
        DeletedMessage otherAccount = message(2, -21, 0, 3);
        otherAccount.hqThumbPath = "shared";
        dao.insert(otherAccount);

        List<String> paths = dao.deleteArchiveRevision(10);
        List<String> unlinked = new ArrayList<>();
        dao.reclaimAttachments(paths, unlinked::add);
        assertEquals(Collections.singletonList("revision-thumb"), unlinked);
        dao.delete(2, -21, 3);
        dao.reclaimAttachments(Collections.singletonList("shared"), unlinked::add);
        assertEquals(Arrays.asList("revision-thumb", "shared"), unlinked);
    }

    @Test public void failedAttachmentCleanupRollsBackReferenceRelease() throws Exception {
        DeletedMessage message = message(1, -20, 0, 3);
        message.mediaPath = "shared";
        message.hqThumbPath = "shared";
        dao.insert(message);
        try {
            dao.runAttachmentCleanup(() -> {
                dao.clearAttachmentReferences("shared");
                throw new IOException("unlink failed");
            });
            fail("Cleanup must propagate the failure");
        } catch (IllegalStateException expected) {
            // Room's coroutine bridge may re-wrap the exception, so look down the cause chain.
            Throwable cause = expected;
            while (cause != null && !(cause instanceof IOException)) {
                cause = cause.getCause();
            }
            assertTrue(cause instanceof IOException);
            assertTrue(dao.hasAttachmentReference("shared"));
            assertEquals("shared", dao.getMessage(1, -20, 3).message.hqThumbPath);
        }
    }

    @Test public void bulkCleanupBatchesPathBindingsAndDeletesEveryReaction() {
        List<Integer> ids = new ArrayList<>();
        for (int id = 1; id <= 300; id++) {
            ids.add(id);
            DeletedMessage message = message(1, -20, 0, id);
            message.mediaPath = "media-" + id;
            dao.insertIfAbsent(message, Collections.singletonList(reaction()));
        }
        assertEquals(300L, dao.deleteArchiveMessages(1, -20, ids, true).stream().filter(path -> path != null).count());
        assertEquals(0, dao.getTotalCount());
        assertEquals(0, reactionCount());
    }

    private DeletedMessage message(long user, long dialog, long topic, int id) {
        DeletedMessage message = new DeletedMessage();
        message.userId = user;
        message.dialogId = dialog;
        message.topicId = topic;
        message.messageId = id;
        return message;
    }

    private DeletedMessageReaction reaction() {
        DeletedMessageReaction reaction = new DeletedMessageReaction();
        reaction.emoticon = "x";
        reaction.count = 1;
        return reaction;
    }

    private int reactionCount() {
        try (Cursor cursor = database.getOpenHelper().getReadableDatabase().query("SELECT COUNT(*) FROM DeletedMessageReaction")) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }
}
