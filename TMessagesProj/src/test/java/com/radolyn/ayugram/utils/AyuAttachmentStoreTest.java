package com.radolyn.ayugram.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AyuAttachmentStoreTest {
    private Path fixture;
    private File parent;
    private File records;
    private AyuAttachmentStore store;

    @Before
    public void setUp() throws IOException {
        // The standalone runner points java.io.tmpdir at its workspace output directory.
        fixture = Files.createTempDirectory("ayu-attachment-test-");
        parent = Files.createDirectory(fixture.resolve("selected")).toFile();
        records = fixture.resolve("private-records").toFile();
        store = new AyuAttachmentStore(parent, records, "installation-one");
        store.prepare();
    }

    @Test
    public void selectedParentAndLegacyFilesAreNeverAdopted() throws IOException {
        File legacy = write(parent, "legacy.jpg", 15);
        File unrelated = write(parent, "personal.txt", 25);
        File saved = save("saved.jpg", 5, 1000);
        List<File> deleted = new ArrayList<>();

        assertEquals(parent, store.getDirectory().getParentFile());
        store.remember(legacy);
        assertFalse(store.owns(legacy));
        assertEquals(5L, store.clear(deleted::add));
        assertEquals(Arrays.asList(saved), deleted);
        assertTrue(parent.isDirectory());
        assertTrue(legacy.isFile());
        assertTrue(unrelated.isFile());
        assertTrue(store.getDirectory().isDirectory());
        assertTrue(new File(store.getDirectory(), ".nomedia").isFile());
    }

    @Test
    public void unrecordedFilesAndSubdirectoriesInsideChildArePreserved() throws IOException {
        File unrelated = write(store.getDirectory(), "personal.txt", 20);
        File nestedDir = Files.createDirectory(store.getDirectory().toPath().resolve("nested")).toFile();
        File nestedFile = write(nestedDir, "saved.jpg", 30);
        store.remember(nestedFile);
        save("saved.jpg", 5, 1000);

        assertEquals(5L, store.clear(file -> { }));
        assertTrue(unrelated.isFile());
        assertTrue(nestedFile.isFile());
        assertFalse(store.owns(nestedFile));
    }

    @Test
    public void privateOwnershipSurvivesReopeningTheStore() throws IOException {
        File saved = save("saved.jpg", 5, 1000);
        AyuAttachmentStore reopened = new AyuAttachmentStore(parent, records, "installation-one");

        assertTrue(reopened.owns(saved));
        assertEquals(5L, reopened.clear(file -> { }));
        assertFalse(saved.exists());
    }

    @Test
    public void otherInstallationsAndOtherParentsHaveSeparateOwnership() throws IOException {
        File saved = save("saved.jpg", 5, 1000);
        AyuAttachmentStore otherInstallation = new AyuAttachmentStore(parent, records, "installation-two");
        otherInstallation.prepare();
        File otherParent = Files.createDirectory(fixture.resolve("other-selected")).toFile();
        AyuAttachmentStore otherStore = new AyuAttachmentStore(otherParent, records, "installation-one");
        otherStore.prepare();
        File unrecorded = write(otherStore.getDirectory(), "saved.jpg", 5);
        Files.setLastModifiedTime(unrecorded.toPath(), FileTime.fromMillis(1000));

        assertEquals(0L, otherInstallation.clear(file -> { }));
        assertEquals(0L, otherStore.clear(file -> { }));
        assertFalse(otherInstallation.owns(saved));
        assertFalse(otherStore.owns(unrecorded));
        assertTrue(saved.isFile());
        assertTrue(unrecorded.isFile());
    }

    @Test
    public void reselectingTheParentDoesNotNestOrLoseOwnedFiles() throws IOException {
        File saved = save("saved.jpg", 5, 1000);
        AyuAttachmentStore reselected = new AyuAttachmentStore(parent, records, "installation-one");
        reselected.prepare();

        assertEquals(store.getDirectory(), reselected.getDirectory());
        assertTrue(reselected.owns(saved));
        assertTrue(saved.isFile());
    }

    @Test
    public void quotaIgnoresUnrelatedBytesAndKeepsFilesAtTheLimit() throws IOException {
        File first = save("first.jpg", 5, 2000);
        File second = save("second.jpg", 5, 1000);
        File unrelated = write(store.getDirectory(), "personal.txt", 100);

        assertEquals(0L, store.trim(10, null, file -> fail("No eviction at the limit")));
        assertEquals(0L, store.trim(Long.MAX_VALUE, null, file -> fail("Unlimited quota")));
        assertTrue(first.isFile());
        assertTrue(second.isFile());
        assertTrue(unrelated.isFile());
    }

    @Test
    public void quotaEvictsOldestOwnedFilesAndProtectsTheCompletedSave() throws IOException {
        File newest = save("newest.jpg", 5, 3000);
        File oldest = save("oldest.jpg", 5, 1000);
        File middle = save("middle.jpg", 5, 2000);
        List<File> deleted = new ArrayList<>();

        assertEquals(5L, store.trim(10, null, deleted::add));
        assertEquals(Arrays.asList(oldest), deleted);
        assertEquals(5L, store.trim(0, newest, deleted::add));
        assertEquals(Arrays.asList(oldest, middle), deleted);
        assertTrue(newest.isFile());
        assertEquals(0L, store.trim(0, newest, deleted::add));
    }

    @Test
    public void clearIncludesEmptyOwnedFilesAndNotifiesOnlyDeletedPaths() throws IOException {
        File empty = save("empty.bin", 0, 1000);
        File missing = save("missing.bin", 5, 1000);
        Files.delete(missing.toPath());
        List<File> deleted = new ArrayList<>();

        assertEquals(0L, store.clear(deleted::add));
        assertEquals(Arrays.asList(empty), deleted);
        assertFalse(empty.exists());
    }

    @Test
    public void externallyChangedOrReplacedFilesLoseOwnership() throws IOException {
        File changed = save("changed.bin", 5, 1000);
        Files.write(changed.toPath(), new byte[10]);
        File replaced = save("replaced.bin", 5, 1000);
        Files.move(replaced.toPath(), fixture.resolve("old-file"));
        write(store.getDirectory(), replaced.getName(), 5);

        assertFalse(store.owns(changed));
        assertFalse(store.owns(replaced));
        assertEquals(0L, store.clear(file -> fail("Changed files must be retained")));
        assertTrue(changed.isFile());
        assertTrue(replaced.isFile());
    }

    @Test
    public void corruptOrMissingOwnershipRecordsFailClosed() throws IOException {
        File saved = save("saved.jpg", 5, 1000);
        File[] recordDirectories = records.listFiles();
        assertEquals(1, recordDirectories.length);
        File[] entries = recordDirectories[0].listFiles();
        assertEquals(1, entries.length);
        Files.write(entries[0].toPath(), new byte[]{1, 2});

        assertEquals(0L, store.clear(file -> fail("Corrupt record must not authorize deletion")));
        Files.delete(entries[0].toPath());
        assertEquals(0L, store.clear(file -> fail("Missing record must not authorize deletion")));
        assertTrue(saved.isFile());
    }

    @Test
    public void pathTraversalPrefixSiblingsAndCanonicalFailuresAreNotOwned() throws IOException {
        File sibling = Files.createDirectory(Path.of(store.getDirectory().getPath() + "-sibling")).toFile();
        File unrelated = write(sibling, "personal.txt", 5);
        File escaped = new File(store.getDirectory(), ".." + File.separator + "legacy.bin");
        write(parent, "legacy.bin", 5);
        File failingCanonical = new File(store.getDirectory(), "saved.jpg") {
            @Override
            public Path toPath() {
                throw new SecurityException("Simulated path resolution failure");
            }
        };
        save("saved.jpg", 5, 1000);

        store.remember(unrelated);
        store.remember(escaped);
        assertFalse(store.owns(unrelated));
        assertFalse(store.owns(escaped));
        assertFalse(store.owns(failingCanonical));
        assertFalse(store.owns(store.getDirectory()));
        assertEquals(5L, store.clear(file -> { }));
        assertTrue(unrelated.isFile());
        assertTrue(escaped.isFile());
    }

    @Test
    public void directoryReplacingAnOwnedFileIsNeverRecursivelyDeleted() throws IOException {
        File saved = save("saved.jpg", 5, 1000);
        Files.delete(saved.toPath());
        Files.createDirectory(saved.toPath());
        File nested = write(saved, "personal.txt", 5);

        assertEquals(0L, store.clear(file -> fail("Directories are never attachments")));
        assertTrue(nested.isFile());
    }

    @Test
    public void ownershipIsRecheckedBetweenDeletions() throws IOException {
        File first = save("first.jpg", 5, 1000);
        File second = save("second.jpg", 5, 2000);
        List<File> deleted = new ArrayList<>();

        assertEquals(5L, store.trim(0, null, file -> {
            deleted.add(file);
            try {
                Files.write(second.toPath(), new byte[10]);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }));
        assertEquals(Arrays.asList(first), deleted);
        assertTrue(second.isFile());
    }

    @Test
    public void symlinkFileAndItsTargetAreRetained() throws IOException {
        File target = write(parent, "personal.txt", 5);
        File link = save("link.jpg", 5, 1000);
        Files.delete(link.toPath());
        createSymbolicLink(link.toPath(), target.toPath());

        store.remember(link);
        assertFalse(store.owns(link));
        assertEquals(0L, store.clear(file -> fail("Symlinks are not owned files")));
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertTrue(target.isFile());
    }

    @Test
    public void symlinkDirectoryIsRejectedEvenWhenItPointsInsideTheParent() throws IOException {
        AyuAttachmentStore linkedStore = new AyuAttachmentStore(parent, records, "linked-installation");
        createSymbolicLink(linkedStore.getDirectory().toPath(), store.getDirectory().toPath());

        try {
            linkedStore.prepare();
            fail("Linked directory must be rejected");
        } catch (IOException expected) {
            assertEquals(0L, linkedStore.clear(file -> fail("Linked directory must not be traversed")));
        }
    }

    @Test
    public void directoryReplacedByALinkBetweenDeletionsStopsCleanup() throws IOException {
        File first = save("first.jpg", 5, 1000);
        save("second.jpg", 5, 2000);
        Path replacement = Files.createDirectory(fixture.resolve("replacement"));
        File unrelated = write(replacement.toFile(), "second.jpg", 5);
        Path probeLink = fixture.resolve("link-capability-probe");
        createSymbolicLink(probeLink, replacement);
        Path original = fixture.resolve("original");
        List<File> deleted = new ArrayList<>();

        assertEquals(5L, store.trim(0, null, file -> {
            deleted.add(file);
            try {
                Files.move(store.getDirectory().toPath(), original);
                Files.createSymbolicLink(store.getDirectory().toPath(), replacement);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }));
        assertEquals(Arrays.asList(first), deleted);
        assertTrue(unrelated.isFile());
        assertTrue(original.resolve("second.jpg").toFile().isFile());
    }

    @Test
    public void changedParentSymlinkCannotRedirectCleanup() throws IOException {
        Path original = Files.createDirectory(fixture.resolve("original-parent"));
        Path replacement = Files.createDirectory(fixture.resolve("replacement-parent"));
        Path alias = fixture.resolve("parent-alias");
        createSymbolicLink(alias, original);
        AyuAttachmentStore aliasedStore = new AyuAttachmentStore(alias.toFile(), records, "alias-installation");
        aliasedStore.prepare();
        File saved = write(aliasedStore.getDirectory(), "saved.jpg", 5);
        aliasedStore.remember(saved);
        File replacementDirectory = Files.createDirectory(replacement.resolve(aliasedStore.getDirectory().getName())).toFile();
        File unrelated = write(replacementDirectory, "saved.jpg", 5);
        Files.delete(alias);
        createSymbolicLink(alias, replacement);

        assertFalse(aliasedStore.owns(saved));
        assertEquals(0L, aliasedStore.clear(file -> fail("Changed parent must not be traversed")));
        try {
            aliasedStore.prepare();
            fail("Changed parent must not be prepared");
        } catch (IOException expected) {
            assertFalse(new File(replacementDirectory, ".nomedia").exists());
        }
        assertTrue(unrelated.isFile());
        assertTrue(original.resolve(aliasedStore.getDirectory().getName()).resolve("saved.jpg").toFile().isFile());
    }

    private File save(String name, int size, long modified) throws IOException {
        File file = write(store.getDirectory(), name, size);
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modified));
        store.remember(file);
        return file;
    }

    private static File write(File directory, String name, int size) throws IOException {
        return Files.write(new File(directory, name).toPath(), new byte[size]).toFile();
    }

    private static void createSymbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.Assume.assumeNoException("Host cannot create symbolic links", e);
        }
    }
}
