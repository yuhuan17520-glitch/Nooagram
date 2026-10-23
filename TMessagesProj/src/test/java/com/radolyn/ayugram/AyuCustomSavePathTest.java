package com.radolyn.ayugram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AyuCustomSavePathTest {
    @Test
    public void emptyPathKeepsHistoricalDirectoryWithoutRewritingExplicitPaths() {
        assertEquals("Nagram", AyuCustomSavePath.normalize(null));
        assertEquals("Nagram", AyuCustomSavePath.normalize(""));
        assertEquals("Nagram", AyuCustomSavePath.normalize("   "));
        assertEquals("Nagram", AyuCustomSavePath.normalize("Nagram"));
        assertEquals("Nooagram", AyuCustomSavePath.normalize("Nooagram"));
        assertEquals("Archive", AyuCustomSavePath.normalize(" Archive "));
    }
}
