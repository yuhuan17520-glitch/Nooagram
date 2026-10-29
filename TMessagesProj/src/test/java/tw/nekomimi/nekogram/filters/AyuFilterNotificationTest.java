package tw.nekomimi.nekogram.filters;

import com.radolyn.ayugram.AyuConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class AyuFilterNotificationTest {
    private static final long DIALOG_ID = -100L;

    private boolean previousEnabled;
    private boolean previousMask;
    private TLRPC.User previousUser;
    private int previousLoginTime;
    private Field currentUser;

    @Before
    public void setUp() throws Exception {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        previousEnabled = AyuConfig.INSTANCE.getRegexFiltersEnabled().Bool();
        previousMask = AyuConfig.INSTANCE.getRegexFiltersMaskMessages().Bool();
        currentUser = UserConfig.class.getDeclaredField("currentUser");
        currentUser.setAccessible(true);
        UserConfig config = UserConfig.getInstance(0);
        previousUser = config.getCurrentUser();
        previousLoginTime = config.loginTime;
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = 4242L;
        currentUser.set(config, user);
        config.loginTime = 300;

        AyuConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(true);
        AyuConfig.INSTANCE.getRegexFiltersMaskMessages().setConfigBool(false);
        installFilters();
    }

    // Config writes call AyuFilter.onPreferencesChanged(), which drops loaded filters; reinstall after them.
    private static void installFilters() throws Exception {
        AyuFilter.FilterModel filter = new AyuFilter.FilterModel();
        filter.id = "spam";
        filter.regex = "VPN";
        filter.caseInsensitive = true;
        filter.enabled = true;
        filter.buildPattern();
        ArrayList<AyuFilter.FilterModel> filters = new ArrayList<>();
        filters.add(filter);
        setStatic("filterModels", filters);
        setStatic("chatFilterEntries", new ArrayList<AyuFilter.ChatFilterEntry>());
        setStatic("excludedSharedFilterIdsByDialog", new HashMap<Long, java.util.HashSet<String>>());
        AyuFilterCache.clearAll();
    }

    @After
    public void tearDown() throws Exception {
        AyuConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(previousEnabled);
        AyuConfig.INSTANCE.getRegexFiltersMaskMessages().setConfigBool(previousMask);
        setStatic("filterModels", null);
        setStatic("chatFilterEntries", null);
        setStatic("excludedSharedFilterIdsByDialog", null);
        AyuFilterCache.clearAll();
        currentUser.set(UserConfig.getInstance(0), previousUser);
        UserConfig.getInstance(0).loginTime = previousLoginTime;
    }

    @Test
    public void filteredMessageIsSuppressedInHideMode() {
        assertTrue(AyuFilter.shouldSuppressNotification(message(1, "free vpn here", false)));
    }

    @Test
    public void filteredMessageIsSuppressedInMaskMode() throws Exception {
        AyuConfig.INSTANCE.getRegexFiltersMaskMessages().setConfigBool(true);
        installFilters();
        assertTrue(AyuFilter.shouldSuppressNotification(message(1, "free vpn here", false)));
    }

    @Test
    public void unmatchedMessageStillNotifies() {
        assertFalse(AyuFilter.shouldSuppressNotification(message(1, "hello", false)));
    }

    @Test
    public void disabledFiltersDoNotSuppress() throws Exception {
        AyuConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(false);
        installFilters();
        assertFalse(AyuFilter.shouldSuppressNotification(message(1, "free vpn here", false)));
    }

    @Test
    public void outgoingMessageIsNeverSuppressed() {
        MessageObject outgoing = message(1, "free vpn here", false);
        outgoing.messageOwner.out = true;
        outgoing.isOutOwnerCached = true;
        assertFalse(AyuFilter.shouldSuppressNotification(outgoing));
    }

    @Test
    public void filteredPushPreviewIsSuppressedWithoutCachingItsResult() {
        assertTrue(AyuFilter.shouldSuppressNotification(message(7, "free vpn here", true)));
        // The real message with the same id must be evaluated on its own text.
        assertFalse(AyuFilter.isFiltered(message(7, "hello", false), null));
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = AyuFilter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static MessageObject message(int id, String text, boolean fcm) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.message = text;
        message.dialog_id = DIALOG_ID;
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = -DIALOG_ID;
        message.peer_id = peer;
        message.date = 1000 + id;
        // The lightweight constructor avoids Theme/native init; localType 0 marks a regular message.
        MessageObject messageObject = new MessageObject(0, message, text, null, null, false, true, false, false);
        if (!fcm) {
            messageObject.localType = 0;
        }
        // Skip the channel lookup in isOutOwner(), which needs native MessagesController.
        messageObject.isOutOwnerCached = false;
        // Caption text is matched the same way, without the LocaleController lookup messageText needs.
        messageObject.messageText = null;
        messageObject.caption = text;
        return messageObject;
    }
}
