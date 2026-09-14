package tw.nekomimi.nekogram.helpers;

import org.junit.Test;
import tw.nekomimi.nekogram.helpers.remote.NooagramUpdateHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
