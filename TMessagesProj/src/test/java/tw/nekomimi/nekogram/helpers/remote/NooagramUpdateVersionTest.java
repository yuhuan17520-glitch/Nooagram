package tw.nekomimi.nekogram.helpers.remote;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class NooagramUpdateVersionTest {
    @Test public void versionCodePermitsHotfixWithUnchangedDisplayVersion() {
        assertTrue(NooagramUpdateHelper.isNewVersionAvailable("1.3.0", 125001001, "1.3.0", 125001000));
        assertFalse(NooagramUpdateHelper.isNewVersionAvailable("2.0.0", 125000081, "1.3.0", 125001000));
        assertFalse(NooagramUpdateHelper.isNewVersionAvailable("1.3.0", 125001000, "1.3.0", 125001000));
    }

    @Test public void semanticVersionDoesNotUseLexicographicNumbers() {
        assertTrue(NooagramUpdateHelper.compareSemVer("1.10.0", "1.9.0") > 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("1.3.0", "1.3.0-rc.1") > 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("1.3.0-rc.10", "1.3.0-rc.2") > 0);
        assertEquals(0, NooagramUpdateHelper.compareSemVer("1.3.0+100", "1.3.0+200"));
    }

    @Test public void unsupportedDeviceDoesNotFallBackToAnArbitraryArmApk() {
        assertEquals("arm64-v8a", NooagramUpdateHelper.selectAbi(
                new String[]{"arm64-v8a", "armeabi-v7a"}, List.of("armeabi-v7a", "arm64-v8a")));
        assertEquals("armeabi-v7a", NooagramUpdateHelper.selectAbi(
                new String[]{"arm64-v8a", "armeabi-v7a"}, List.of("armeabi-v7a")));
        assertNull(NooagramUpdateHelper.selectAbi(new String[]{"x86_64"}, List.of("arm64-v8a")));
        assertNull(NooagramUpdateHelper.selectAbi(null, List.of("arm64-v8a")));
    }
}
