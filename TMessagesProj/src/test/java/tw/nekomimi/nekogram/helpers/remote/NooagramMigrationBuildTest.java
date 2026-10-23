package tw.nekomimi.nekogram.helpers.remote;

import org.junit.Test;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.PushListenerController;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class NooagramMigrationBuildTest {
    @Test public void debugPackageCannotOverwriteProduction() {
        assertTrue(BuildConfig.MIGRATION_TEST_BUILD);
        assertEquals("fork.yuhuan.nooagram.migrationtest", BuildConfig.APPLICATION_ID);
    }

    @Test public void testBuildNeverFetchesOrOffersAProductionUpdate() {
        AtomicInteger callbacks = new AtomicInteger();
        NooagramUpdateHelper.check((update, error) -> {
            assertNull(update);
            assertNull(error);
            callbacks.incrementAndGet();
        }, true);
        assertEquals(1, callbacks.get());
    }

    @Test public void testBuildDoesNotRegisterFirebaseForAnotherPackage() {
        assertFalse(PushListenerController.getProvider().hasServices());
    }
}
