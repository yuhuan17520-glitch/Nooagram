package tw.nekomimi.nekogram.filters;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;

public class AyuFilterCacheTest {
    private final TLRPC.User[] previousUsers = new TLRPC.User[2];
    private final int[] previousLoginTimes = new int[2];
    private Field currentUser;

    @Before
    public void setupAccounts() throws Exception {
        currentUser = UserConfig.class.getDeclaredField("currentUser");
        currentUser.setAccessible(true);
        for (int account = 0; account < 2; account++) {
            UserConfig config = UserConfig.getInstance(account);
            previousUsers[account] = config.getCurrentUser();
            previousLoginTimes[account] = config.loginTime;
            login(account, 1000L + account, 100);
        }
        AyuFilterCache.clearAll();
    }

    @After
    public void clear() throws Exception {
        AyuFilterCache.clearAll();
        for (int account = 0; account < 2; account++) {
            currentUser.set(UserConfig.getInstance(account), previousUsers[account]);
            UserConfig.getInstance(account).loginTime = previousLoginTimes[account];
        }
    }

    @Test
    public void editingAnAlbumInvalidatesEveryMemberAndTheGroup() {
        MessageObject first = message(1, 50L);
        MessageObject second = message(2, 50L);
        MessageObject.GroupedMessages group = new MessageObject.GroupedMessages();
        group.groupId = 50L;
        AyuFilterCache.DialogKey key = AyuFilterCache.keyFor(-1L, first);
        AyuFilterCache.DialogKey otherDialog = AyuFilterCache.keyFor(-2L, first);
        AyuFilterCache.put(key, first, group, false);
        AyuFilterCache.put(key, second, group, false);
        AyuFilterCache.put(otherDialog, message(1, 0L), null, true);

        AyuFilter.onMessageEdited(second.getId(), -1L);

        assertNull(AyuFilterCache.get(key, first, group));
        assertNull(AyuFilterCache.get(key, first, null));
        assertNull(AyuFilterCache.get(key, second, group));
        assertNull(AyuFilterCache.get(key, second, null));
        assertEquals(Boolean.TRUE, AyuFilterCache.get(otherDialog, message(1, 0L), null));

        AyuFilterCache.put(key, first, group, true);
        AyuFilter.onMessageEdited(first.getId(), -1L);
        assertNull(AyuFilterCache.get(key, second, group));
    }

    @Test
    public void accountsWithIdenticalMessageAndAlbumIdsHaveIndependentResults() {
        MessageObject first = message(1, 50L);
        MessageObject second = message(1, 50L);
        second.currentAccount = 1;
        MessageObject.GroupedMessages group = new MessageObject.GroupedMessages();
        group.groupId = 50L;
        AyuFilterCache.DialogKey firstKey = AyuFilterCache.keyFor(-1L, first);
        AyuFilterCache.DialogKey secondKey = AyuFilterCache.keyFor(-1L, second);
        assertNotEquals(firstKey, secondKey);
        AyuFilterCache.put(firstKey, first, group, true);
        assertNull(AyuFilterCache.get(secondKey, second, group));
        AyuFilterCache.put(secondKey, second, group, false);
        assertEquals(Boolean.TRUE, AyuFilterCache.get(firstKey, first, group));
        assertEquals(Boolean.FALSE, AyuFilterCache.get(secondKey, second, group));
        assertEquals(Boolean.TRUE, AyuFilterCache.get(firstKey, first, null));
        assertEquals(Boolean.FALSE, AyuFilterCache.get(secondKey, second, null));
        AyuFilterCache.invalidate(-1L, 1);
        assertNull(AyuFilterCache.get(firstKey, first, group));
        assertNull(AyuFilterCache.get(secondKey, second, null));
    }

    @Test
    public void replacementLoginCannotReadOrWriteThePreviousUsersCache() throws Exception {
        MessageObject message = message(1, 0L);
        AyuFilterCache.DialogKey oldKey = AyuFilterCache.keyFor(-1L, message);
        AyuFilterCache.put(oldKey, message, null, true);
        login(0, 2000, 200);
        AyuFilterCache.DialogKey newKey = AyuFilterCache.keyFor(-1L, message);
        assertNull(AyuFilterCache.get(newKey, message, null));
        assertNull(AyuFilterCache.get(oldKey, message, null));
        AyuFilterCache.put(oldKey, message, null, true);
        assertNull(AyuFilterCache.get(newKey, message, null));
        AyuFilterCache.put(newKey, message, null, false);
        assertEquals(Boolean.FALSE, AyuFilterCache.get(newKey, message, null));
    }

    @Test
    public void reloginByTheSameUserAndLoggedOutSlotsDoNotReuseCachedResults() throws Exception {
        MessageObject message = message(1, 0L);
        AyuFilterCache.DialogKey oldKey = AyuFilterCache.keyFor(-1L, message);
        AyuFilterCache.put(oldKey, message, null, true);
        login(0, 1000, 200);
        assertNull(AyuFilterCache.get(AyuFilterCache.keyFor(-1L, message), message, null));
        currentUser.set(UserConfig.getInstance(0), null);
        assertNull(AyuFilterCache.keyFor(-1L, message));
        AyuFilterCache.put(null, message, null, true);
        assertNull(AyuFilterCache.get(null, message, null));
    }

    private void login(int account, long userId, int loginTime) throws Exception {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = userId;
        UserConfig config = UserConfig.getInstance(account);
        currentUser.set(config, user);
        config.loginTime = loginTime;
    }

    private static MessageObject message(int id, long groupId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.grouped_id = groupId;
        return new MessageObject(0, message, "", null, null, false, false, false, false);
    }
}
