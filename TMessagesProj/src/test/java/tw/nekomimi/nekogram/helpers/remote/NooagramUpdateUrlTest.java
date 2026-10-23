package tw.nekomimi.nekogram.helpers.remote;

import org.junit.Test;

import static org.junit.Assert.*;

public class NooagramUpdateUrlTest {
    private static final String RELEASE = "https://github.com/yuhuan17520-glitch/Nooagram/releases/download/v1.3.0/Nooagram-arm64-v8a.apk";

    @Test public void acceptsOnlyOurHttpsReleaseAssets() {
        assertTrue(NooagramUpdateHelper.isTrustedReleaseUrl(RELEASE));
        assertTrue(NooagramUpdateHelper.isTrustedReleaseUrl(RELEASE.replace("github.com/", "GITHUB.COM:443/")));
    }

    @Test public void rejectsOtherRepositoriesAndSubstringSpoofs() {
        for (String url : new String[]{null, "", "not a URL",
                RELEASE.replace("https:", "http:"),
                RELEASE.replace("github.com/", "github.com.attacker.invalid/"),
                RELEASE.replace("github.com/", "github.com@attacker.invalid/"),
                RELEASE.replace("github.com/", "attacker@github.com/"),
                RELEASE.replace("github.com/", "github.com:444/"),
                RELEASE.replace("yuhuan17520-glitch", "another-owner"),
                RELEASE.replace("/Nooagram/", "/Nooagram-other/"),
                RELEASE.replace("/releases/download/", "/raw/"),
                RELEASE + "?next=Nooagram", RELEASE + "#Nooagram",
                RELEASE.replace("v1.3.0/", "../"),
                RELEASE.replace("v1.3.0/", "%2e%2e/"),
                RELEASE.replace("v1.3.0/", "v1%2fother/"),
                RELEASE.replace(".apk", ".zip"),
                RELEASE.replace("Nooagram-arm64-v8a.apk", "%2fother.apk")}) {
            assertFalse("Accepted untrusted URL: " + url, NooagramUpdateHelper.isTrustedReleaseUrl(url));
        }
    }
}
