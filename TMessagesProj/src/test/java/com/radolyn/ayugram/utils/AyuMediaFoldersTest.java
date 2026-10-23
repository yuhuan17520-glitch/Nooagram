package com.radolyn.ayugram.utils;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AyuMediaFoldersTest {
    @Test public void normalNamesAndUnicodeArePreserved() {
        assertEquals("abc 中文聊天", AyuMediaFolders.sanitizeFolderName("abc 中文聊天", -10));
        assertEquals("ABC", AyuMediaFolders.sanitizeFolderName("ＡＢＣ", -10));
    }

    @Test public void separatorsAndControlsCannotEscapeTheExportFolder() {
        assertEquals("a_b_c_d", AyuMediaFolders.sanitizeFolderName("a/b" + (char) 92 + "c:d", -10));
        assertEquals("a_b_c", AyuMediaFolders.sanitizeFolderName("a" + (char) 10 + "b" + (char) 0x202E + "c", -10));
        assertEquals("_secret", AyuMediaFolders.sanitizeFolderName("../secret", -10));
        assertEquals("chat", AyuMediaFolders.sanitizeFolderName(".. chat. .", -10));
    }

    @Test public void emptyAndDotOnlyNamesUseStableDialogIds() {
        for (String name : new String[]{null, "", "   ", ".", "..", " . . "}) {
            assertEquals("-101", AyuMediaFolders.sanitizeFolderName(name, -101));
        }
    }

    @Test public void longNamesFitInOneUtf8SegmentWithoutSplittingSurrogates() {
        String result = AyuMediaFolders.sanitizeFolderName("😀".repeat(300), -10);
        assertEquals(60, result.codePointCount(0, result.length()));
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 240);
        assertEquals("😀".repeat(60), result);
    }
}
