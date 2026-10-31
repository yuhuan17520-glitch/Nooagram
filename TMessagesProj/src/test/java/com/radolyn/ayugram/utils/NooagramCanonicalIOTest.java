package com.radolyn.ayugram.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = NooagramCanonicalDownloadTest.TestApplication.class)
public class NooagramCanonicalIOTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    private static final class CancellingInput extends ByteArrayInputStream {
        final AtomicBoolean cancelled;
        int reads;

        CancellingInput(AtomicBoolean cancelled) {
            super(new byte[128 * 1024]);
            this.cancelled = cancelled;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            reads++;
            int count = super.read(buffer, offset, length);
            cancelled.set(true);
            return count;
        }
    }

    @Test
    public void digestAndCopyStopBeforeReadingTheNextChunkAfterCancellation() throws Exception {
        for (boolean copy : new boolean[]{false, true}) {
            AtomicBoolean cancelled = new AtomicBoolean();
            CancellingInput input = new CancellingInput(cancelled);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            assertThrows(IOException.class, () -> {
                if (copy) {
                    NooagramCanonicalDownload.copyChecked(input, new ByteArrayOutputStream(), digest, cancelled::get);
                } else {
                    NooagramCanonicalDownload.readIntoDigest(input, digest, cancelled::get);
                }
            });
            assertEquals(1, input.reads);
        }
    }

    @Test
    public void configuredCacheRootsAcceptChildrenButRejectSiblingPrefixesAndTraversal() throws Exception {
        File root = files.newFolder("configured-cache");
        assertTrue(NooagramCanonicalDownload.isUnderRoot(new File(root, "documents/source.bin"), root));
        assertFalse(NooagramCanonicalDownload.isUnderRoot(new File(files.getRoot(), "configured-cache-other/source.bin"), root));
        assertFalse(NooagramCanonicalDownload.isUnderRoot(new File(root, "../outside/source.bin"), root));
    }

    @Test
    public void recoveryReadsAlsoHonorThreadInterruptionWithoutACancellationSupplier() throws Exception {
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> NooagramCanonicalDownload.readIntoDigest(
                    new ByteArrayInputStream(new byte[1]), MessageDigest.getInstance("SHA-256"), null));
        } finally {
            Thread.interrupted();
        }
    }
}
