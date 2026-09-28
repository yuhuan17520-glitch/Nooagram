package tw.nekomimi.nekogram.helpers;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import tw.nekomimi.nekogram.helpers.remote.NooagramUpdateHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NooagramUpdateHelperTest {

    @Test
    public void testSameVersionDoesNotTriggerUpdate() {
        assertFalse(NooagramUpdateHelper.isNewVersionAvailable(
                "1.0.6-12.10.1.1450", 125000025,
                "1.0.6-12.10.1.1450", 125000025));

        // Even if remote versionCode is accidentally higher or timestamp drifted,
        // exact same version string must NEVER trigger update!
        assertFalse(NooagramUpdateHelper.isNewVersionAvailable(
                "1.0.6-12.10.1.1450", 125000030,
                "1.0.6-12.10.1.1450", 125000025));
    }

    @Test
    public void testOlderVersionDoesNotTriggerUpdate() {
        assertFalse(NooagramUpdateHelper.isNewVersionAvailable(
                "1.0.5-12.10.1.1450", 125000024,
                "1.0.6-12.10.1.1450", 125000025));
    }

    @Test
    public void testNewerPatchVersionTriggersUpdate() {
        assertTrue(NooagramUpdateHelper.isNewVersionAvailable(
                "1.0.7-12.10.1.1450", 125000026,
                "1.0.6-12.10.1.1450", 125000025));
    }

    @Test
    public void testNewerUpstreamVersionTriggersUpdate() {
        assertTrue(NooagramUpdateHelper.isNewVersionAvailable(
                "1.0.6-12.10.2.1451", 125000026,
                "1.0.6-12.10.1.1450", 125000025));
    }

    @Test
    public void testCompareSemVer() {
        assertEquals(0, NooagramUpdateHelper.compareSemVer("1.0.6-12.10.1.1450", "1.0.6-12.10.1.1450"));
        assertTrue(NooagramUpdateHelper.compareSemVer("1.0.7-12.10.1.1450", "1.0.6-12.10.1.1450") > 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("1.0.6-12.10.1.1450", "1.0.7-12.10.1.1450") < 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("1.1.0-12.10.1.1450", "1.0.6-12.10.1.1450") > 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("2.0.0-12.10.1.1450", "1.0.6-12.10.1.1450") > 0);
    }

    @Test
    public void testBuildMetadataDoesNotChangeSemVerPrecedence() {
        assertEquals(0, NooagramUpdateHelper.compareSemVer(
                "1.2.0-12.10.1.1251+125000043",
                "1.2.0-12.10.1.1251+125000044"));
        assertTrue(NooagramUpdateHelper.isNewVersionAvailable(
                "1.2.0-12.10.1.1251+125000044", 125000044,
                "1.2.0-12.10.1.1251+125000043", 125000043));
        assertFalse(NooagramUpdateHelper.isNewVersionAvailable(
                "1.2.0-12.10.1.1251+125000043", 125000044,
                "1.2.0-12.10.1.1251+125000043", 125000043));
    }

    @Test
    public void testSemVerIdentifiers() {
        assertTrue(NooagramUpdateHelper.compareSemVer("1.2.0-rc.10", "1.2.0-rc.2") > 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("1.2.0-rc", "1.2.0-beta") > 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("1.2.0-1", "1.2.0-alpha") < 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("1.2.0-alpha.0", "1.2.0-alpha") > 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("1.2.0", "1.2.0-rc") > 0);
        assertTrue(NooagramUpdateHelper.compareSemVer("1.2.0-99999999999999999999", "1.2.0-9") > 0);
    }

    @Test
    public void testExactAbiSelectionAndDeviceOrder() {
        assertEquals("x86_64", NooagramUpdateHelper.selectAbi(
                new String[]{"x86_64", "x86"}, Arrays.asList("arm64-v8a", "armeabi-v7a", "x86_64")));
        assertEquals("arm64-v8a", NooagramUpdateHelper.selectAbi(
                new String[]{"arm64-v8a", "armeabi-v7a"}, Arrays.asList("armeabi-v7a", "arm64-v8a")));
        assertEquals("armeabi-v7a", NooagramUpdateHelper.selectAbi(
                new String[]{"arm64-v8a", "armeabi-v7a"}, Collections.singletonList("armeabi-v7a")));
    }

    @Test
    public void testUnsupportedAbiNeverFallsBackToArm() {
        assertNull(NooagramUpdateHelper.selectAbi(new String[]{"x86_64"}, Arrays.asList("arm64-v8a", "armeabi-v7a")));
        assertNull(NooagramUpdateHelper.selectAbi(new String[]{"armeabi-v7a"}, Collections.singletonList("arm64-v8a")));
        assertNull(NooagramUpdateHelper.selectAbi(new String[]{"arm64-v8a"}, Collections.emptyList()));
        assertNull(NooagramUpdateHelper.selectAbi(null, Collections.singletonList("arm64-v8a")));
    }
}
