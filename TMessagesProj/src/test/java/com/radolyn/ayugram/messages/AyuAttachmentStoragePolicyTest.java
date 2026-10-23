package com.radolyn.ayugram.messages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

public class AyuAttachmentStoragePolicyTest {
    private Path fixture;

    @Before
    public void setUp() throws IOException {
        fixture = Files.createTempDirectory("ayu-attachment-policy-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (fixture == null || !Files.exists(fixture)) {
            return;
        }
        Files.walk(fixture)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                });
    }

    @Test
    public void writeProbeCreatesAndRemovesItsFile() throws IOException {
        File directory = Files.createDirectory(fixture.resolve("selected")).toFile();

        assertTrue(AyuMessagesController.AttachmentStoragePolicy.probeWritable(directory));

        File[] remaining = directory.listFiles();
        assertTrue(remaining != null);
        assertEquals(0, remaining.length);
    }

    @Test
    public void writeProbeNeverCreatesAMissingDirectory() {
        File missing = fixture.resolve("missing").toFile();

        assertFalse(AyuMessagesController.AttachmentStoragePolicy.probeWritable(missing));
        assertFalse(missing.exists());
    }

    @Test
    public void writeProbeRejectsAFileAtTheTargetPath() throws IOException {
        File notDirectory = Files.write(fixture.resolve("selected"), new byte[]{1}).toFile();

        assertFalse(AyuMessagesController.AttachmentStoragePolicy.probeWritable(notDirectory));
        assertTrue(notDirectory.isFile());
        assertEquals(1L, notDirectory.length());
    }

    @Test
    public void failedProbeNeverCommitsTheNewFolderConfiguration() throws IOException {
        File validDirectory = Files.createDirectory(fixture.resolve("valid")).toFile();
        File missingDirectory = fixture.resolve("missing").toFile();
        AtomicInteger commits = new AtomicInteger();

        assertFalse(AyuMessagesController.AttachmentStoragePolicy.probeAndCommit(
                missingDirectory, commits::incrementAndGet));
        assertEquals(0, commits.get());
        assertFalse(missingDirectory.exists());

        assertTrue(AyuMessagesController.AttachmentStoragePolicy.probeAndCommit(
                validDirectory, commits::incrementAndGet));
        assertEquals(1, commits.get());
    }

    @Test
    public void targetResolutionRejectsTraversalAndKeepsChildrenContained() throws IOException {
        File directory = Files.createDirectory(fixture.resolve("selected")).toFile();

        File target = AyuMessagesController.AttachmentStoragePolicy.resolveTarget(directory, "saved.jpg");

        assertEquals(directory.getCanonicalFile(), target.getParentFile());
        assertRejected(directory, "../outside.jpg");
        assertRejected(directory, "nested/saved.jpg");
        assertRejected(directory, "..");
    }

    private static void assertRejected(File directory, String fileName) {
        try {
            AyuMessagesController.AttachmentStoragePolicy.resolveTarget(directory, fileName);
            fail("Expected invalid target: " + fileName);
        } catch (IOException expected) {
            // Expected fail-closed behavior.
        }
    }
}
