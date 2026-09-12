package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.filters.AyuFilter;
import xyz.nextalone.nagram.NaConfig;

public final class NooagramQuickFilter {
    private static final int MAX_CANDIDATES = 4;
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s]+");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9.-])(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?![A-Za-z0-9.-])");
    private static final Pattern CONTACT_PATTERN = Pattern.compile(
            "(?i)(?:telegram|t\\.me|tg|qq|\\u5fae\\u4fe1|vx|wechat)"
                    + "[\\s:\\uFF1A]*[A-Za-z0-9_./-]{3,}");
    private static final Pattern PRODUCT_MARKER_PATTERN = Pattern.compile(
            "(?i)vpn|proxy|加速器|机场|梯子|破解|资源库|软件库|约炮|包养|下分|分润"
                    + "|代理招商|首充|返利|彩金|赔率|博彩|娱乐城|包赔|卡密");
    private static final Pattern FILE_LINE_PATTERN = Pattern.compile(
            "(?i)^(.+?)\\.(?:apk|apks|xapk|exe|zip|rar|7z|ipa|dmg|pkg)\\s*$");
    private static final Pattern FILE_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])([\\p{L}\\p{N}][\\p{L}\\p{N}._\\- @]{1,96}?)"
                    + "\\.(?:apk|apks|xapk|exe|zip|rar|7z|ipa|dmg|pkg)(?![\\p{L}\\p{N}])");
    private static final Pattern HANDLE_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])@[\\p{L}\\p{N}_]{2,}");

    private NooagramQuickFilter() {}

    public static Result block(MessageObject message, MessageObject.GroupedMessages group) {
        CharSequence source = AyuFilter.getMessageText(message, group);
        if (TextUtils.isEmpty(source)) {
            return Result.error("NO_TEXT");
        }
        try {
            String regex = buildRegex(source.toString());
            ArrayList<AyuFilter.FilterModel> filters = new ArrayList<>(AyuFilter.getRegexFilters());
            boolean duplicate = false;
            for (AyuFilter.FilterModel filter : filters) {
                if (filter != null && filter.caseInsensitive && TextUtils.equals(filter.regex, regex)) {
                    filter.enabled = true;
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                AyuFilter.saveFilter(filters);
            } else {
                AyuFilter.addFilter(regex, true);
            }
            NaConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(true);
            NaConfig.INSTANCE.getRegexFiltersEnableInChats().setConfigBool(true);
            NaConfig.INSTANCE.getRegexFiltersMaskMessages().setConfigBool(false);
            return new Result(regex, duplicate, null);
        } catch (Throwable throwable) {
            FileLog.e("NooagramQuickFilter.block", throwable);
            return Result.error(null);
        }
    }

    private static String buildRegex(String rawText) {
        String source = rawText == null ? "" : rawText.replace('\r', '\n').trim();
        source = source.replace('\u200B', ' ');
        String text = source.replace('\n', ' ').trim();
        String candidateText = HANDLE_PATTERN.matcher(text).replaceAll(" ");
        ArrayList<Candidate> candidates = new ArrayList<>();
        collectFilenameCandidates(source, candidates);
        collectFileTokenCandidates(candidateText, candidates);
        collectProductCandidates(candidateText, candidates);
        if (candidates.size() < 2) {
            collectPatternCandidates(candidateText, DOMAIN_PATTERN, 65, candidates);
            collectPatternCandidates(candidateText, CONTACT_PATTERN, 60, candidates);
            collectPatternCandidates(candidateText, URL_PATTERN, 55, candidates);
        }
        if (candidates.size() < 2) {
            collectFallbackCandidates(candidateText, candidates);
        }
        removeRedundantCandidates(candidates);
        candidates.sort((left, right) -> {
            int score = Integer.compare(right.score, left.score);
            return score != 0 ? score : Integer.compare(left.position, right.position);
        });

        if (candidates.isEmpty()) {
            return Pattern.quote(truncate(text));
        }

        StringBuilder builder = new StringBuilder();
        int count = Math.min(MAX_CANDIDATES, candidates.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append(candidateRegex(candidates.get(i).value));
        }
        String regex = builder.toString();
        if (!Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find()) {
            return Pattern.quote(truncate(text));
        }
        return regex;
    }

    private static void removeRedundantCandidates(ArrayList<Candidate> candidates) {
        for (int i = candidates.size() - 1; i >= 0; i--) {
            Candidate candidate = candidates.get(i);
            String value = candidate.value.toLowerCase(Locale.ROOT);
            for (int j = 0; j < candidates.size(); j++) {
                if (i == j) {
                    continue;
                }
                Candidate other = candidates.get(j);
                String otherValue = other.value.toLowerCase(Locale.ROOT);
                if (otherValue.length() >= 4 && value.length() > otherValue.length()
                        && value.contains(otherValue) && candidate.score <= other.score) {
                    candidates.remove(i);
                    break;
                }
            }
        }
    }

    private static String candidateRegex(String value) {
        String normalized = value == null ? "" : value.trim();
        String[] tokens = normalized.split("[\\s_]+");
        if (tokens.length <= 1) {
            return Pattern.quote(normalized);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                builder.append("[\\s_-]*");
            }
            builder.append(Pattern.quote(tokens[i]));
        }
        return builder.toString();
    }

    private static String normalizeCandidateKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
    }

    private static void collectPatternCandidates(String text, Pattern pattern, int score,
                                                  ArrayList<Candidate> candidates) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            addCandidate(matcher.group(), score, matcher.start(), candidates);
        }
    }

    private static void collectProductCandidates(String text, ArrayList<Candidate> candidates) {
        Matcher matcher = PRODUCT_MARKER_PATTERN.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int remaining = 20;
            boolean separatorUsed = false;
            while (start > 0 && remaining > 0) {
                char previous = text.charAt(start - 1);
                if (isBrandChar(previous)) {
                    start--;
                    remaining--;
                    continue;
                }
                if ((previous == ' ' || previous == '\t' || previous == '-' || previous == '_')
                        && !separatorUsed && start > 1 && isBrandChar(text.charAt(start - 2))) {
                    start--;
                    remaining--;
                    separatorUsed = true;
                    continue;
                }
                break;
            }
            String value = cleanProductCandidate(text.substring(start, matcher.end()));
            if (value.length() > matcher.group().length() && !isGenericCandidate(value)) {
                addCandidate(value, 110, start, candidates);
            }
        }
    }

    private static void collectFilenameCandidates(String text, ArrayList<Candidate> candidates) {
        String[] lines = text.split("\\n+");
        int position = 0;
        for (String line : lines) {
            String value = line.trim();
            Matcher matcher = FILE_LINE_PATTERN.matcher(value);
            if (matcher.matches()) {
                String stem = cleanFilenameCandidate(matcher.group(1));
                addCandidate(stem, 130, position, candidates);
            }
            position += line.length() + 1;
        }
    }

    private static void collectFileTokenCandidates(String text, ArrayList<Candidate> candidates) {
        Matcher matcher = FILE_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String stem = cleanFilenameCandidate(matcher.group(1));
            addCandidate(stem, 130, matcher.start(1), candidates);
        }
    }

    private static String cleanFilenameCandidate(String value) {
        String cleaned = HANDLE_PATTERN.matcher(value == null ? "" : value).replaceAll(" ");
        cleaned = cleaned.replaceFirst("(?i)[\\s_-]+v?\\d+(?:[._-]\\d+)*(?:[\\s_-]+[\\p{L}\\p{N}]+)*$", "");
        cleaned = cleaned.replaceFirst("(?i)[\\s_-]+(?:android|安卓|客户端|新版|新内核)$", "");
        cleaned = cleaned.replace('_', ' ').replaceAll("\\s+", " ").trim();
        return trimCandidate(cleaned);
    }

    private static String cleanProductCandidate(String value) {
        String cleaned = HANDLE_PATTERN.matcher(value == null ? "" : value).replaceAll(" ");
        cleaned = cleaned.replaceAll("^[^\\p{L}\\p{N}\\u3400-\\u9FFF]+", "")
                .replaceAll("[\\s:：,，。！？!?]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    private static void collectFallbackCandidates(String text, ArrayList<Candidate> candidates) {
        String[] phrases = text.split("[\\n,，。！!？?；;：:|/\\[\\]【】()（）<>《》]+", -1);
        int position = 0;
        for (String phrase : phrases) {
            String value = phrase.replaceAll("^[#\\s]+|[\\s]+$", "").replaceAll("\\s+", " ");
            if (value.length() >= 6 && value.length() <= 32 && !isGenericCandidate(value)) {
                addCandidate(value, containsHan(value) ? 45 : 35, position, candidates);
            }
            position += phrase.length() + 1;
        }
    }

    private static void addCandidate(String value, int score, int position,
                                      ArrayList<Candidate> candidates) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        value = HANDLE_PATTERN.matcher(value).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();
        value = trimCandidate(value);
        if (value.length() < 3 || value.length() > 96 || isGenericCandidate(value)) {
            return;
        }
        String key = normalizeCandidateKey(value);
        for (Candidate existing : candidates) {
            if (normalizeCandidateKey(existing.value).equals(key)) {
                if (score > existing.score) {
                    existing.score = score;
                    existing.position = Math.min(existing.position, position);
                }
                return;
            }
        }
        candidates.add(new Candidate(value, score, position));
    }

    private static boolean isBrandChar(char value) {
        return Character.isLetterOrDigit(value) || value >= 0x3400 && value <= 0x9FFF;
    }

    private static String trimCandidate(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && !Character.isLetterOrDigit(value.charAt(start)) && value.charAt(start) != '@') {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(value.charAt(end - 1)) && value.charAt(end - 1) != '_') {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean containsHan(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x3400 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGenericCandidate(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        switch (normalized) {
            case "http":
            case "https":
            case "www":
            case "com":
            case "cn":
            case "net":
            case "org":
            case "telegram":
            case "tme":
            case "qq":
            case "vx":
            case "wechat":
            case "vpn":
            case "apk":
            case "安卓":
            case "下载":
            case "下载地址":
            case "点击下载":
            case "免费使用":
            case "永久免费":
            case "全球节点":
            case "极速稳定":
            case "不限流量":
            case "不限速度":
            case "一键连接":
            case "一键安装":
            case "6g":
            case "返利":
            case "分红":
            case "瓜分":
            case "天蛋":
            case "贵宾":
            case "赔率":
            case "彩金":
            case "娱乐":
            case "品牌":
            case "权威":
            case "信赖":
            case "优惠":
            case "福利":
            case "活动":
            case "资源":
            case "软件":
            case "安装":
            case "体验":
            case "联系":
            case "客服":
            case "咨询":
            case "官网":
            case "平台":
            case "更新":
            case "推荐":
            case "点击":
            case "查看":
            case "更多":
            case "官方频道":
            case "官方群组":
            case "官方交流群":
                return true;
            default:
                return false;
        }
    }

    private static String truncate(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    public static final class Result {
        public final String regex;
        public final boolean duplicate;
        public final String error;

        private Result(String regex, boolean duplicate, String error) {
            this.regex = regex;
            this.duplicate = duplicate;
            this.error = error;
        }

        private static Result error(String error) {
            return new Result(null, false, error);
        }
    }

    private static final class Candidate {
        final String value;
        int score;
        int position;

        Candidate(String value, int score, int position) {
            this.value = value;
            this.score = score;
            this.position = position;
        }
    }
}
