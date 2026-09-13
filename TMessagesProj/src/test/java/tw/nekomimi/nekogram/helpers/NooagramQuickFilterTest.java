package tw.nekomimi.nekogram.helpers;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NooagramQuickFilterTest {

    @Test
    public void excludesGenericPlatformLinksFromDomainRules() throws Exception {
        String source = "MGC88.cc 30.magic88.cc https://t.me/funapk";
        String regex = buildRegex(source);

        assertTrue(regex.contains("\\QMGC88.cc\\E"));
        assertTrue(regex.contains("\\Q30.magic88.cc\\E"));
        assertFalse(regex.contains("t.me"));
        assertTrue(Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(source).find());
    }

    @Test
    public void doesNotExtractPathFromGenericPlatformLink() throws Exception {
        String source = "欢迎加入 https://t.me/funapk";
        String regex = buildRegex(source);

        assertFalse(regex.contains("\\Qfunapk\\E"));
        assertFalse(regex.contains("\\Qt.me\\E"));
        assertTrue(Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(source).find());
    }

    @Test
    public void keepsAdvertisementDomains() throws Exception {
        String source = "最新入口 MGC88.cc 备用 30.magic88.cc";
        String regex = buildRegex(source);

        assertTrue(regex.contains("\\QMGC88.cc\\E"));
        assertTrue(regex.contains("\\Q30.magic88.cc\\E"));
        assertFalse(regex.contains("MGC88.cc 30.magic88.cc"));
    }

    private static String buildRegex(String source) throws Exception {
        Method method = NooagramQuickFilter.class.getDeclaredMethod("buildRegex", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, source);
    }
}
