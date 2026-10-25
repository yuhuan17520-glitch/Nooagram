package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class NotificationDismissStateTest {

    private static final String PENDING_INTENT_ACTION = "org.telegram.messenger.test.NOTIFICATION_DISMISS";
    private static final String PENDING_INTENT_SCHEME = "nooagram";

    private Context context;
    private CopyOnWriteArraySet<Integer> previousActiveAccounts;
    private SparseArray<UserConfig> previousUserConfigs;
    private SparseArray<MessagesController> previousMessagesControllers;
    private SparseArray<NotificationsController> previousNotificationsControllers;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        previousActiveAccounts = SharedConfig.activeAccounts;
        previousUserConfigs = ReflectionHelpers.getStaticField(UserConfig.class, "Instance");
        previousMessagesControllers = ReflectionHelpers.getStaticField(MessagesController.class, "Instance");
        previousNotificationsControllers = ReflectionHelpers.getStaticField(NotificationsController.class, "Instance");
        SharedConfig.activeAccounts = new CopyOnWriteArraySet<>();
        ReflectionHelpers.setStaticField(UserConfig.class, "Instance", new SparseArray<UserConfig>());
        ReflectionHelpers.setStaticField(MessagesController.class, "Instance", new SparseArray<MessagesController>());
        ReflectionHelpers.setStaticField(NotificationsController.class, "Instance", new SparseArray<NotificationsController>());
        preferences("Notifications").edit().clear().commit();
        preferences("Notifications1").edit().clear().commit();
    }

    @After
    public void tearDown() {
        SharedConfig.activeAccounts = previousActiveAccounts;
        ReflectionHelpers.setStaticField(UserConfig.class, "Instance", previousUserConfigs);
        ReflectionHelpers.setStaticField(MessagesController.class, "Instance", previousMessagesControllers);
        ReflectionHelpers.setStaticField(NotificationsController.class, "Instance", previousNotificationsControllers);
    }

    @Test
    public void groupedSummaryDismissalAppliesAcrossDialogsUntilNewerMessage() {
        SharedPreferences preferences = preferences("Notifications");

        NotificationDismissState.dismiss(preferences, 0, 200);

        assertTrue(NotificationDismissState.isDismissed(preferences, 101, 199, false));
        assertTrue(NotificationDismissState.isDismissed(preferences, -102, 200, false));
        assertFalse(NotificationDismissState.isDismissed(preferences, 101, 201, false));
    }

    @Test
    public void childDismissalIsScopedToItsDialog() {
        SharedPreferences preferences = preferences("Notifications");

        NotificationDismissState.dismiss(preferences, 101, 200);

        assertTrue(NotificationDismissState.isDismissed(preferences, 101, 200, false));
        assertFalse(NotificationDismissState.isDismissed(preferences, 202, 200, false));
        assertFalse(NotificationDismissState.isDismissed(preferences, 101, 201, false));
    }

    @Test
    public void dismissalIsScopedToAccount() {
        SharedPreferences account0 = preferences("Notifications");
        SharedPreferences account1 = preferences("Notifications1");

        NotificationDismissState.dismiss(account0, 101, 200);

        assertTrue(NotificationDismissState.isDismissed(account0, 101, 200, false));
        assertFalse(NotificationDismissState.isDismissed(account1, 101, 200, false));
    }

    @Test
    public void committedDismissalSurvivesFreshSettingsLookup() {
        NotificationDismissState.dismiss(preferences("Notifications"), 101, 200);

        SharedPreferences reloaded = preferences("Notifications");

        assertEquals(200, NotificationDismissState.getDismissDate(reloaded, 101));
        assertTrue(NotificationDismissState.isDismissed(reloaded, 101, 200, false));
        assertFalse(NotificationDismissState.isDismissed(reloaded, 101, 201, false));
    }

    @Test
    public void olderDismissalDoesNotMoveCutoffBackwards() {
        SharedPreferences preferences = preferences("Notifications");
        NotificationDismissState.dismiss(preferences, 101, 200);

        NotificationDismissState.dismiss(preferences, 101, 150);

        assertEquals(200, NotificationDismissState.getDismissDate(preferences, 101));
    }

    @Test
    public void storiesAreNotSuppressedByMessageDismissal() {
        SharedPreferences preferences = preferences("Notifications");
        NotificationDismissState.dismiss(preferences, 0, 200);

        assertFalse(NotificationDismissState.isDismissed(preferences, 101, 100, true));
    }

    @Test
    public void dismissIntentIdentityIsStableAndDiscriminatesAccountAndDialog() {
        assertEquals(
                NotificationDismissState.getIntentData(0, 101),
                NotificationDismissState.getIntentData(0, 101));
        assertNotEquals(
                NotificationDismissState.getIntentData(0, 101),
                NotificationDismissState.getIntentData(1, 101));
        assertNotEquals(
                NotificationDismissState.getIntentData(0, 101),
                NotificationDismissState.getIntentData(0, 102));
    }

    @Test
    public void receiverPersistsSummaryAndChildDismissalsToAccountPreferences() {
        installAccount(0);
        installAccount(1);
        NotificationDismissReceiver receiver = new NotificationDismissReceiver();

        receiver.onReceive(context, dismissIntent(0, 101, 200));
        receiver.onReceive(context, dismissIntent(1, 0, 300));

        assertEquals(200, preferences("Notifications").getInt("dismissDate101", 0));
        assertEquals(0, preferences("Notifications").getInt("dismissDate", 0));
        assertEquals(0, preferences("Notifications1").getInt("dismissDate101", 0));
        assertEquals(300, preferences("Notifications1").getInt("dismissDate", 0));
    }

    @Test
    public void receiverPersistsColdDismissalWithoutStartingMessagesController() {
        preferences("userconfig1").edit().putString("user", "serialized-user").commit();
        NotificationDismissReceiver receiver = new NotificationDismissReceiver();

        receiver.onReceive(context, dismissIntent(1, -123, 200));

        assertTrue(NotificationDismissState.isDismissed(preferences("Notifications1"), -123, 200, false));
        SparseArray<MessagesController> controllers = ReflectionHelpers.getStaticField(MessagesController.class, "Instance");
        assertEquals(0, controllers.size());
    }

    @Test
    public void receiverWakesControllerForColdMixedStorySummaryCleanup() {
        preferences("userconfig1").edit().putString("user", "serialized-user").commit();
        NotificationDismissReceiver receiver = new NotificationDismissReceiver();
        Intent intent = dismissIntent(1, 0, 200);
        intent.putExtra("story", true);
        intent.putExtra(NotificationDismissState.EXTRA_DISMISS_MESSAGES, true);
        StubNotificationsControllerRegistry controllers = new StubNotificationsControllerRegistry();
        ReflectionHelpers.setStaticField(NotificationsController.class, "Instance", controllers);
        DispatchQueue queue = ReflectionHelpers.getStaticField(NotificationsController.class, "notificationsQueue");
        Handler previousHandler = ReflectionHelpers.getField(queue, "handler");
        Handler pausedHandler = new Handler(Looper.getMainLooper());
        ReflectionHelpers.setField(queue, "handler", pausedHandler);

        try {
            receiver.onReceive(context, intent);
        } finally {
            pausedHandler.removeCallbacksAndMessages(null);
            ReflectionHelpers.setField(queue, "handler", previousHandler);
        }

        assertTrue(NotificationDismissState.isDismissed(preferences("Notifications1"), 0, 200, false));
        assertEquals(2, controllers.lookupCount);
    }

    @Test
    public void pendingIntentIdentityKeepsAccountDialogAndExtrasIndependent() throws Exception {
        List<Intent> delivered = new ArrayList<>();
        BroadcastReceiver captureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                delivered.add(new Intent(intent));
            }
        };
        IntentFilter filter = new IntentFilter(PENDING_INTENT_ACTION);
        filter.addDataScheme(PENDING_INTENT_SCHEME);
        context.registerReceiver(captureReceiver, filter);
        try {
            PendingIntent account0Dialog101 = dismissPendingIntent(1, 0, 101, 200);
            PendingIntent account1Dialog101 = dismissPendingIntent(1, 1, 101, 300);
            PendingIntent account0Dialog102 = dismissPendingIntent(1, 0, 102, 400);

            account0Dialog101.send();
            account1Dialog101.send();
            account0Dialog102.send();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        } finally {
            context.unregisterReceiver(captureReceiver);
        }

        assertEquals(3, delivered.size());
        assertDismissExtras(delivered.get(0), 0, 101, 200);
        assertDismissExtras(delivered.get(1), 1, 101, 300);
        assertDismissExtras(delivered.get(2), 0, 102, 400);
    }

    private void installAccount(int account) {
        preferences(account == 0 ? "userconfing" : "userconfig" + account).edit()
                .putString("user", "serialized-user")
                .commit();
        preferences(account).edit().clear().commit();
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = account + 1;
        UserConfig userConfig = allocateWithoutConstructor(UserConfig.class);
        ReflectionHelpers.setField(userConfig, "sync", new Object());
        ReflectionHelpers.setField(userConfig, "currentUser", user);
        SparseArray<UserConfig> userConfigs = ReflectionHelpers.getStaticField(UserConfig.class, "Instance");
        userConfigs.put(account, userConfig);
        SharedConfig.activeAccounts.add(account);

        MessagesController messagesController = allocateWithoutConstructor(MessagesController.class);
        ReflectionHelpers.setField(messagesController, "notificationsPreferences", preferences(account));
        SparseArray<MessagesController> messagesControllers = ReflectionHelpers.getStaticField(MessagesController.class, "Instance");
        messagesControllers.put(account, messagesController);
    }

    private static <T> T allocateWithoutConstructor(Class<T> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            return type.cast(allocateInstance.invoke(theUnsafe.get(null), type));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to allocate " + type.getName() + " without its constructor", e);
        }
    }

    private PendingIntent dismissPendingIntent(int requestCode, int account, long dialogId, int date) {
        Intent intent = dismissIntent(account, dialogId, date);
        intent.setAction(PENDING_INTENT_ACTION);
        intent.setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Intent dismissIntent(int account, long dialogId, int date) {
        Intent intent = new Intent();
        intent.setData(NotificationDismissState.getIntentData(account, dialogId));
        intent.putExtra("currentAccount", account);
        intent.putExtra("dialogId", dialogId);
        intent.putExtra("messageDate", date);
        return intent;
    }

    private static void assertDismissExtras(Intent intent, int account, long dialogId, int date) {
        assertEquals(account, intent.getIntExtra("currentAccount", -1));
        assertEquals(dialogId, intent.getLongExtra("dialogId", Long.MIN_VALUE));
        assertEquals(date, intent.getIntExtra("messageDate", -1));
        assertEquals(NotificationDismissState.getIntentData(account, dialogId), intent.getData());
    }

    private static final class StubNotificationsControllerRegistry extends SparseArray<NotificationsController> {
        private final NotificationsController controller = allocateWithoutConstructor(NotificationsController.class);
        private int lookupCount;

        @Override
        public NotificationsController get(int key) {
            lookupCount++;
            return controller;
        }
    }

    private SharedPreferences preferences(int account) {
        return context.getSharedPreferences(account == 0 ? "Notifications" : "Notifications" + account, Context.MODE_PRIVATE);
    }

    private SharedPreferences preferences(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
