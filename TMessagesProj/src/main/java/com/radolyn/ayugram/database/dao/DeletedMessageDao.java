/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;
import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Dao
public interface DeletedMessageDao {
    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId")
    DeletedMessageFull getMessage(long userId, long dialogId, int messageId);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND :startId <= messageId AND messageId <= :endId ORDER BY messageId LIMIT :limit")
    List<DeletedMessageFull> getMessages(long userId, long dialogId, long startId, long endId, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND topicId = :topicId AND :startId <= messageId AND messageId <= :endId ORDER BY messageId LIMIT :limit")
    List<DeletedMessageFull> getTopicMessages(long userId, long dialogId, long topicId, long startId, long endId, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND replyMessageId = :threadMessageId AND :startId <= messageId AND messageId <= :endId ORDER BY messageId LIMIT :limit")
    List<DeletedMessageFull> getThreadMessages(long userId, long dialogId, long threadMessageId, long startId, long endId, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND :startId <= messageId AND messageId <= :endId ORDER BY messageId DESC LIMIT :limit")
    List<DeletedMessageFull> getMessagesDescending(long userId, long dialogId, long startId, long endId, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND topicId = :topicId AND :startId <= messageId AND messageId <= :endId ORDER BY messageId DESC LIMIT :limit")
    List<DeletedMessageFull> getTopicMessagesDescending(long userId, long dialogId, long topicId, long startId, long endId, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND replyMessageId = :threadMessageId AND :startId <= messageId AND messageId <= :endId ORDER BY messageId DESC LIMIT :limit")
    List<DeletedMessageFull> getThreadMessagesDescending(long userId, long dialogId, long threadMessageId, long startId, long endId, int limit);

    @Query("SELECT COALESCE(MAX(CASE WHEN date <= :date THEN messageId END), MIN(messageId)) " +
            "FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId " +
            "AND (:topicId = 0 OR topicId = :topicId) AND (:threadMessageId = 0 OR replyMessageId = :threadMessageId) " +
            "AND messageId > 0")
    Integer getMessageIdAtDate(long userId, long dialogId, long topicId, long threadMessageId, int date);

    @Query("SELECT COALESCE(MIN(CASE WHEN date <= :date THEN messageId END), MAX(messageId)) " +
            "FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId < 0")
    Integer getEncryptedMessageIdAtDate(long userId, long dialogId, int date);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND groupedId = :groupedId ORDER BY messageId")
    List<DeletedMessageFull> getMessagesGrouped(long userId, long dialogId, long groupedId);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND groupedId IN (:groupedIds) ORDER BY messageId")
    List<DeletedMessageFull> getMessagesGroupedIn(long userId, long dialogId, List<Long> groupedIds);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId")
    List<DeletedMessageFull> getMessagesByDialog(long userId, long dialogId);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId ORDER BY messageId DESC LIMIT :limit")
    List<DeletedMessageFull> getLatestMessages(long userId, long dialogId, int limit);

    @Transaction
    // Match DialogObject.isEncryptedDialog(), including the sign-bit exclusion for ordinary groups.
    @Query("SELECT t.* FROM deletedmessage t " +
            "JOIN (SELECT dialogId, topicId, CASE WHEN (dialogId & 4611686018427387904) != 0 AND dialogId >= 0 " +
            "THEN MIN(messageId) ELSE MAX(messageId) END AS max_messageId " +
            "FROM deletedmessage WHERE userId = :userId GROUP BY dialogId, topicId) m " +
            "ON t.dialogId = m.dialogId AND t.topicId = m.topicId AND t.messageId = m.max_messageId AND t.userId = :userId")
    List<DeletedMessageFull> getLastMessages(long userId);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId < :before ORDER BY messageId DESC LIMIT :limit")
    List<DeletedMessageFull> getOlderMessagesBefore(long userId, long dialogId, int before, int limit);

    @Transaction
    @Query("SELECT messageId FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId IN (:messageIds)")
    List<Integer> getExistingMessageIds(long userId, long dialogId, List<Integer> messageIds);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId IN (:messageIds)")
    List<DeletedMessageFull> getMessagesByIds(long userId, long dialogId, List<Integer> messageIds);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId " +
            "AND text IS NOT NULL AND text != '' AND text LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY messageId DESC LIMIT :limit")
    List<DeletedMessageFull> searchByText(long userId, long dialogId, String query, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId " +
            "AND (:topicId = 0 OR topicId = :topicId) " +
            "AND text IS NOT NULL AND text != '' AND text LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY messageId DESC LIMIT :limit")
    List<DeletedMessageFull> searchByTextTopic(long userId, long dialogId, long topicId, String query, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND (:topicId = 0 OR topicId = :topicId) " +
            "AND date BETWEEN :startDate AND :endDate ORDER BY messageId")
    List<DeletedMessageFull> getMessagesByDate(long userId, long dialogId, long topicId, int startDate, int endDate);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId " +
            "AND (:topicId = 0 OR topicId = :topicId) AND messageId < :beforeId " +
            "AND (:query = '' OR text LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR mediaPath LIKE '%' || :query || '%' ESCAPE '\\' OR fwdName LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY messageId DESC LIMIT :limit")
    List<DeletedMessageFull> getMessagesForScroll(long userId, long dialogId, long topicId, String query, int beforeId, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId " +
            "AND (:topicId = 0 OR topicId = :topicId) AND messageId BETWEEN :minId AND :maxId " +
            "AND (:query = '' OR text LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR mediaPath LIKE '%' || :query || '%' ESCAPE '\\' OR fwdName LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY messageId DESC LIMIT :limit")
    List<DeletedMessageFull> getMessagesForScrollDescending(long userId, long dialogId, long topicId, String query, int minId, int maxId, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId " +
            "AND (:topicId = 0 OR topicId = :topicId) AND messageId BETWEEN :minId AND :maxId " +
            "AND (:query = '' OR text LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR mediaPath LIKE '%' || :query || '%' ESCAPE '\\' OR fwdName LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY messageId ASC LIMIT :limit")
    List<DeletedMessageFull> getMessagesForScrollAscending(long userId, long dialogId, long topicId, String query, int minId, int maxId, int limit);

    @Transaction
    @Query("SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId " +
            "AND (:topicId = 0 OR topicId = :topicId)")
    List<DeletedMessageFull> getMessagesByDialogTopic(long userId, long dialogId, long topicId);

    @Query("SELECT COUNT(*) FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId " +
            "AND (:topicId = 0 OR topicId = :topicId) " +
            "AND (:query = '' OR text LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR mediaPath LIKE '%' || :query || '%' ESCAPE '\\' OR fwdName LIKE '%' || :query || '%' ESCAPE '\\')")
    int countByDialogTopic(long userId, long dialogId, long topicId, String query);

    @Query("DELETE FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND (:topicId = 0 OR topicId = :topicId)")
    void deleteRowsByDialogTopic(long userId, long dialogId, long topicId);

    @Query("DELETE FROM deletedmessagereaction WHERE deletedMessageId IN (SELECT fakeId FROM deletedmessage " +
            "WHERE userId = :userId AND dialogId = :dialogId AND (:topicId = 0 OR topicId = :topicId))")
    void deleteReactionsByDialogTopic(long userId, long dialogId, long topicId);

    @Transaction
    default void deleteByDialogTopic(long userId, long dialogId, long topicId) {
        deleteReactionsByDialogTopic(userId, dialogId, topicId);
        deleteRowsByDialogTopic(userId, dialogId, topicId);
    }

    @Insert
    long insert(DeletedMessage msg);

    @Insert
    void insertReaction(DeletedMessageReaction reaction);

    @Query("SELECT fakeId FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId " +
            "AND topicId = :topicId AND messageId = :messageId LIMIT 1")
    Long findMessageId(long userId, long dialogId, long topicId, int messageId);

    /** Returns the inserted ID, or the negated existing ID when the business key is already archived. */
    @Transaction
    default long insertIfAbsent(DeletedMessage message, List<DeletedMessageReaction> reactions) {
        Long existing = findMessageId(message.userId, message.dialogId, message.topicId, message.messageId);
        if (existing != null) {
            return -existing;
        }
        long id = insert(message);
        for (DeletedMessageReaction reaction : reactions) {
            reaction.deletedMessageId = id;
            insertReaction(reaction);
        }
        return id;
    }

    @Query("SELECT COUNT(*) FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId")
    int countByDialog(long userId, long dialogId);

    @Query("SELECT COUNT(*) FROM deletedmessage")
    int getTotalCount();

    @Query("SELECT EXISTS(SELECT * FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND topicId = :topicId AND messageId = :msgId)")
    boolean exists(long userId, long dialogId, long topicId, int msgId);

    @Query("DELETE FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId = :msgId")
    void deleteRows(long userId, long dialogId, int msgId);

    @Query("DELETE FROM deletedmessagereaction WHERE deletedMessageId IN (SELECT fakeId FROM deletedmessage " +
            "WHERE userId = :userId AND dialogId = :dialogId AND messageId IN (:messageIds))")
    void deleteReactions(long userId, long dialogId, List<Integer> messageIds);

    @Transaction
    default void delete(long userId, long dialogId, int msgId) {
        deleteReactions(userId, dialogId, java.util.Collections.singletonList(msgId));
        deleteRows(userId, dialogId, msgId);
    }

    @Transaction
    default void delete(long userId, long dialogId) {
        deleteByDialogTopic(userId, dialogId, 0);
    }

    @Query("DELETE FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId IN (:messageIds)")
    void deleteMessageRows(long userId, long dialogId, List<Integer> messageIds);

    @Transaction
    default void deleteMessages(long userId, long dialogId, List<Integer> messageIds) {
        deleteReactions(userId, dialogId, messageIds);
        deleteMessageRows(userId, dialogId, messageIds);
    }

    @Query("SELECT mediaPath FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId IN (:ids) " +
            "UNION SELECT hqThumbPath FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId IN (:ids) " +
            "UNION SELECT mediaPath FROM editedmessage WHERE :revisions AND userId = :userId AND dialogId = :dialogId AND messageId IN (:ids) " +
            "UNION SELECT hqThumbPath FROM editedmessage WHERE :revisions AND userId = :userId AND dialogId = :dialogId AND messageId IN (:ids)")
    List<String> getCleanupPaths(long userId, long dialogId, List<Integer> ids, boolean revisions);

    @Query("DELETE FROM editedmessage WHERE userId = :userId AND dialogId = :dialogId AND messageId IN (:ids)")
    void deleteEditedMessages(long userId, long dialogId, List<Integer> ids);

    @Transaction
    default List<String> deleteArchiveMessages(long userId, long dialogId, List<Integer> ids, boolean revisions) {
        List<String> paths = new ArrayList<>();
        // Each ID is bound four times by the path UNION; stay below older SQLite bind limits.
        for (int start = 0; start < ids.size(); start += 200) {
            List<Integer> batch = ids.subList(start, Math.min(start + 200, ids.size()));
            paths.addAll(getCleanupPaths(userId, dialogId, batch, revisions));
            deleteMessages(userId, dialogId, batch);
            if (revisions) {
                deleteEditedMessages(userId, dialogId, batch);
            }
        }
        return paths;
    }

    @Query("SELECT mediaPath FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND (:topicId = 0 OR topicId = :topicId) " +
            "UNION SELECT hqThumbPath FROM deletedmessage WHERE userId = :userId AND dialogId = :dialogId AND (:topicId = 0 OR topicId = :topicId) " +
            "UNION SELECT mediaPath FROM editedmessage WHERE userId = :userId AND dialogId = :dialogId AND (:topicId = 0 OR topicId = :topicId) " +
            "UNION SELECT hqThumbPath FROM editedmessage WHERE userId = :userId AND dialogId = :dialogId AND (:topicId = 0 OR topicId = :topicId)")
    List<String> getDialogCleanupPaths(long userId, long dialogId, long topicId);

    @Query("DELETE FROM editedmessage WHERE userId = :userId AND dialogId = :dialogId AND (:topicId = 0 OR topicId = :topicId)")
    void deleteEditedDialog(long userId, long dialogId, long topicId);

    @Transaction
    default List<String> deleteArchiveDialog(long userId, long dialogId, long mergeDialogId, long topicId) {
        List<String> paths = new ArrayList<>(getDialogCleanupPaths(userId, dialogId, topicId));
        deleteByDialogTopic(userId, dialogId, topicId);
        deleteEditedDialog(userId, dialogId, topicId);
        if (mergeDialogId != 0) {
            paths.addAll(getDialogCleanupPaths(userId, mergeDialogId, topicId));
            deleteByDialogTopic(userId, mergeDialogId, topicId);
            deleteEditedDialog(userId, mergeDialogId, topicId);
        }
        return paths;
    }

    @Query("SELECT mediaPath FROM editedmessage WHERE fakeId = :fakeId UNION SELECT hqThumbPath FROM editedmessage WHERE fakeId = :fakeId")
    List<String> getRevisionCleanupPaths(long fakeId);

    @Query("DELETE FROM editedmessage WHERE fakeId = :fakeId")
    void deleteRevisionRow(long fakeId);

    @Transaction
    default List<String> deleteArchiveRevision(long fakeId) {
        List<String> paths = getRevisionCleanupPaths(fakeId);
        deleteRevisionRow(fakeId);
        return paths;
    }

    @Query("SELECT EXISTS(SELECT 1 FROM deletedmessage WHERE mediaPath = :path OR hqThumbPath = :path " +
            "UNION ALL SELECT 1 FROM editedmessage WHERE mediaPath = :path OR hqThumbPath = :path)")
    boolean hasAttachmentReference(String path);

    @Query("UPDATE deletedmessage SET mediaPath = CASE WHEN mediaPath = :path THEN NULL ELSE mediaPath END, " +
            "hqThumbPath = CASE WHEN hqThumbPath = :path THEN NULL ELSE hqThumbPath END WHERE mediaPath = :path OR hqThumbPath = :path")
    void clearDeletedAttachmentReferences(String path);

    @Query("UPDATE editedmessage SET mediaPath = CASE WHEN mediaPath = :path THEN NULL ELSE mediaPath END, " +
            "hqThumbPath = CASE WHEN hqThumbPath = :path THEN NULL ELSE hqThumbPath END WHERE mediaPath = :path OR hqThumbPath = :path")
    void clearEditedAttachmentReferences(String path);

    @Transaction
    default void clearAttachmentReferences(String path) {
        clearDeletedAttachmentReferences(path);
        clearEditedAttachmentReferences(path);
    }

    /** Keep the reference check and unlink serialized with all Room writers and database replacement. */
    @Transaction
    default void reclaimAttachments(List<String> paths, Consumer<String> unlink) {
        for (String path : paths) {
            if (path != null && !path.isEmpty() && !hasAttachmentReference(path)) {
                unlink.accept(path);
            }
        }
    }

    @Transaction
    // Room's generated override cannot declare checked exceptions; a wrapped failure still rolls back.
    default boolean runAttachmentCleanup(Callable<Boolean> unlink) {
        try {
            return unlink.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Query("DELETE FROM deletedmessagereaction WHERE NOT EXISTS (SELECT 1 FROM deletedmessage WHERE fakeId = deletedMessageId)")
    void deleteOrphanReactions();

    @Query("UPDATE deletedmessage SET mediaPath = :newPath WHERE userId = :userId AND dialogId = :dialogId AND messageId = :messageId AND (mediaPath IS NULL OR mediaPath = '')")
    void updateMediaPathIfEmpty(long userId, long dialogId, int messageId, String newPath);

    @Query("UPDATE deletedmessage SET mediaPath = NULL WHERE mediaPath = :mediaPath")
    void clearMediaPath(String mediaPath);

    @Query("UPDATE deletedmessage SET mediaPath = NULL, documentType = 0 WHERE mediaPath IS NOT NULL")
    void clearAllMediaPaths();

    @Query("SELECT mediaPath FROM deletedmessage WHERE mediaPath IS NOT NULL AND mediaPath != '' AND mediaPath != '/' " +
            "UNION SELECT hqThumbPath FROM deletedmessage WHERE hqThumbPath IS NOT NULL AND hqThumbPath != '' AND hqThumbPath != '/'")
    List<String> getAllMediaPaths();
}
