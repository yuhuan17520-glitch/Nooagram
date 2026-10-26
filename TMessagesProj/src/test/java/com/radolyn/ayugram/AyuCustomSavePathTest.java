package com.radolyn.ayugram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AyuCustomSavePathTest {
    @Test
    public void fullStoragePathsAllowNestedFoldersAndRejectTraversalOrControls() {
        assertTrue(AyuCustomSavePath.isValidStoragePath("/sdcard/My Files/Videos"));
        assertTrue(AyuCustomSavePath.isValidStoragePath("/storage/ABCD-1234/Media"));
        assertTrue(AyuCustomSavePath.isValidStoragePath("/storage/emulated/0/下载目录"));
        assertFalse(AyuCustomSavePath.isValidStoragePath("/sdcard/../data"));
        assertFalse(AyuCustomSavePath.isValidStoragePath("/sdcard/./Media"));
        assertFalse(AyuCustomSavePath.isValidStoragePath("/sdcard/Media\nHidden"));
        assertFalse(AyuCustomSavePath.isValidStoragePath("/"));
    }
    @Test
    public void emptyPathUsesProductDefaultWithoutRewritingExplicitPaths() {
        assertEquals("Nooagram", AyuCustomSavePath.normalize(null));
        assertEquals("Nooagram", AyuCustomSavePath.normalize(""));
        assertEquals("Nooagram", AyuCustomSavePath.normalize("   "));
        assertEquals("Nagram", AyuCustomSavePath.normalize("Nagram"));
        assertEquals("Nooagram", AyuCustomSavePath.normalize("Nooagram"));
        assertEquals("Archive", AyuCustomSavePath.normalize(" Archive "));
    }

    @Test
    public void validationAcceptsFolderNamesAndRejectsTraversal() {
        assertTrue(AyuCustomSavePath.isValid(""));
        assertTrue(AyuCustomSavePath.isValid("My Archive"));
        assertTrue(AyuCustomSavePath.isValid("中文目录"));
        assertTrue(AyuCustomSavePath.isValid("folder_1-test"));

        assertFalse(AyuCustomSavePath.isValid("."));
        assertFalse(AyuCustomSavePath.isValid(".."));
        assertFalse(AyuCustomSavePath.isValid("../Archive"));
        assertFalse(AyuCustomSavePath.isValid("Archive/../Private"));
        assertFalse(AyuCustomSavePath.isValid("..\\Private"));
        assertFalse(AyuCustomSavePath.isValid("Archive/Child"));
        assertFalse(AyuCustomSavePath.isValid("C:Archive"));

        assertEquals("Nooagram", AyuCustomSavePath.validateInput("../Private", "Nooagram"));
        assertEquals("My Archive", AyuCustomSavePath.validateInput(" My Archive ", "Nooagram"));
    }
}
