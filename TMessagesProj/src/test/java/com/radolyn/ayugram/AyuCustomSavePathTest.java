package com.radolyn.ayugram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AyuCustomSavePathTest {
    @Test
    public void emptyPathUsesTheNewDefaultWithoutRewritingExplicitPaths() {
        assertEquals("Nooagram", AyuCustomSavePath.normalize(null));
        assertEquals("Nooagram", AyuCustomSavePath.normalize(""));
        assertEquals("Nooagram", AyuCustomSavePath.normalize("   "));
        assertEquals("Nagram", AyuCustomSavePath.normalize("Nagram"));
        assertEquals("Archive", AyuCustomSavePath.normalize(" Archive "));
    }
}
