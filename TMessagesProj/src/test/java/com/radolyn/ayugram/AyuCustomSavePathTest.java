package com.radolyn.ayugram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AyuCustomSavePathTest {
    @Test
    public void emptyPathUsesLowercaseDefaultWithoutRewritingExplicitPaths() {
        assertEquals("nooagram", AyuCustomSavePath.normalize(null));
        assertEquals("nooagram", AyuCustomSavePath.normalize(""));
        assertEquals("nooagram", AyuCustomSavePath.normalize("   "));
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

        assertEquals("nooagram", AyuCustomSavePath.validateInput("../Private", "nooagram"));
        assertEquals("My Archive", AyuCustomSavePath.validateInput(" My Archive ", "nooagram"));
    }
}
