package com.radolyn.ayugram.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AyuAttachmentReclaimTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void unlinkRejectsCacheFilesAndUnregisteredFiles() throws Exception {
        AyuAttachmentStore store = store();
        File cache = write(temporary.getRoot(), "telegram-cache.jpg");
        File unknown = write(store.getDirectory(), "unknown.jpg");
        File owned = write(store.getDirectory(), "owned.jpg");
        store.remember(owned);

        assertFalse(store.deleteOwned(cache));
        assertFalse(store.deleteOwned(unknown));
        assertTrue(store.deleteOwned(owned));
        assertTrue(cache.exists());
        assertTrue(unknown.exists());
        assertFalse(store.owns(owned));
        write(store.getDirectory(), "owned.jpg");
        assertFalse(store.deleteOwned(owned));
    }

    @Test
    public void referenceVetoKeepsAnOwnedFileUntilReleased() throws Exception {
        AyuAttachmentStore store = store();
        File owned = write(store.getDirectory(), "shared.jpg");
        store.remember(owned);

        assertEquals(0, store.clear(file -> false, file -> { }));
        assertTrue(owned.exists());
        assertEquals(1, store.clear(file -> true, file -> { }));
        assertFalse(owned.exists());
    }

    @Test
    public void batchedTrimProtectsEveryCompletedSave() throws Exception {
        AyuAttachmentStore store = store();
        File first = write(store.getDirectory(), "first.jpg");
        File second = write(store.getDirectory(), "second.jpg");
        File old = write(store.getDirectory(), "old.jpg");
        for (File file : Arrays.asList(first, second, old)) store.remember(file);

        assertEquals(1, store.trimKeeping(0, new HashSet<>(Arrays.asList(first, second)), file -> true, file -> { }));
        assertTrue(first.exists());
        assertTrue(second.exists());
        assertFalse(old.exists());
        assertEquals(2, store.trimKeeping(0, Collections.emptySet(), file -> true, file -> { }));
    }

    @Test
    public void aSaveThatReplacesTheScannedFileIsRecheckedInsideTheDeletionGuard() throws Exception {
        AyuAttachmentStore store = store();
        File owned = write(store.getDirectory(), "replaced.jpg");
        store.remember(owned);

        assertEquals(0, store.trimUsing(0, Collections.emptySet(), file -> true, (file, unlink) -> {
            Files.write(file.toPath(), new byte[]{2, 3});
            store.remember(file);
            return unlink.delete(file);
        }, file -> { }));
        assertTrue(owned.exists());
        assertTrue(store.owns(owned));
    }

    private AyuAttachmentStore store() throws Exception {
        AyuAttachmentStore store = new AyuAttachmentStore(temporary.newFolder("attachments"),
                temporary.newFolder("records"), "test-owner");
        store.prepare();
        return store;
    }

    private File write(File parent, String name) throws Exception {
        File file = new File(parent, name);
        Files.write(file.toPath(), new byte[]{1});
        return file;
    }
}
