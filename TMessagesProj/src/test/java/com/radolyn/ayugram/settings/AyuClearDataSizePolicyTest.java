package com.radolyn.ayugram.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AyuClearDataSizePolicyTest {
    @Test
    public void clearSheetUsesOnlyNonNegativeCachedSizes() {
        assertEquals(0L, AyuClearDataSizePolicy.fromCache(-1L));
        assertEquals(0L, AyuClearDataSizePolicy.fromCache(0L));
        assertEquals(4096L, AyuClearDataSizePolicy.fromCache(4096L));
    }
}
