package tw.nekomimi.nekogram.helpers;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.filters.AyuFilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NooagramQuickFilterTest {

    @Test
    public void failureWithoutSpecificReasonCannotReportSuccess() throws Exception {
        Method factory = NooagramQuickFilter.Result.class.getDeclaredMethod("error", String.class);
        factory.setAccessible(true);
        NooagramQuickFilter.Result result = (NooagramQuickFilter.Result) factory.invoke(null, (Object) null);
        assertEquals("FAILED", result.error);
        assertNull(result.regex);
        assertFalse(result.duplicate);
        assertEquals("NO_TEXT", ((NooagramQuickFilter.Result) factory.invoke(null, "NO_TEXT")).error);
    }

    @Test
    public void excludesGenericPlatformLinksFromDomainRules() throws Exception {
        String source = "MGC88.cc 30.magic88.cc https://t.me/funapk";
        String regex = buildRegex(source);

        assertTrue(regex.contains("\\QMGC88.cc\\E"));
        assertFalse(regex.contains("\\Q30.magic88.cc\\E"));
        assertFalse(regex.contains("t.me"));
        assertFalse(regex.contains("|"));
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
        assertFalse(regex.contains("\\Q30.magic88.cc\\E"));
        assertFalse(regex.contains("MGC88.cc 30.magic88.cc"));
    }

    @Test
    public void reversedRuleCannotBeReused() {
        AyuFilter.FilterModel existing = new AyuFilter.FilterModel();
        existing.regex = Pattern.quote("MGC88.cc");
        existing.caseInsensitive = true;
        existing.reversed = true;
        existing.enabled = false;
        assertFalse(NooagramQuickFilter.canReuse(existing, existing.regex));
        existing.reversed = false;
        assertTrue(NooagramQuickFilter.canReuse(existing, existing.regex));
    }

    @Test
    public void metadataCannotBecomeAKeyword() {
        String source = "Hi\n\n<button>Go https://t.me/sample</button>\n\n<type>0</type>";
        String regex = NooagramQuickFilter.buildRegex(Arrays.asList("Hi\n", "Go", "https://t.me/sample"), source);
        assertEquals(Pattern.quote("Hi"), regex);
        assertMatches(regex, source);
        assertFalse(Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(
                "Weather is clear\n<button>Refresh</button>\n<type>0</type>").find());
    }

    @Test
    public void shortAndMultilineFallbacksMatchTheOriginalSource() throws Exception {
        for (String text : Arrays.asList("Hi", "A".repeat(70) + "\n" + "B".repeat(70),
                "A".repeat(70) + "\r\n" + "B".repeat(70), "Hi\u200BGo")) {
            assertMatches(buildRegex(text), text);
        }
    }

    @Test
    public void validatesAgainstTheActualSourceBeforeReturning() {
        assertNull(NooagramQuickFilter.buildRegex(Collections.singletonList("MGC88.cc"), "Weather is clear"));
        String regex = NooagramQuickFilter.buildRegex(Arrays.asList("MGC88.cc", "Hi"), "Hi\n<type>0</type>");
        assertEquals(Pattern.quote("Hi"), regex);
    }

    @Test
    public void refusesHandleOrGenericLinkOnlyMessages() throws Exception {
        for (String text : Arrays.asList("@sender_name", "@channel_name https://t.me/sample",
                "t.me/sample", "https://m.youtube.com/watch?v=sample", "https://github.com/project/repo")) {
            assertNull(text, buildRegex(text));
        }
    }

    @Test
    public void extractsOneProductWithoutSenderOrChannelHandles() throws Exception {
        String source = "@sender_name\nExampleVPN_2.3.apk\nhttps://t.me/channel_name";
        String regex = buildRegex(source);
        assertEquals(Pattern.quote("ExampleVPN"), regex);
        assertMatches(regex, source);
    }

    private static void assertMatches(String regex, String source) {
        assertNotNull(regex);
        assertTrue(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(source).find());
    }

    private static String buildRegex(String source) throws Exception {
        Method method = NooagramQuickFilter.class.getDeclaredMethod("buildRegex", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, source);
    }
}
