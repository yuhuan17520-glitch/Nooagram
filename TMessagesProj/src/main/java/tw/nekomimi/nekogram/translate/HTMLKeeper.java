/*
 * This is the source code of OctoGram for Android
 * It is licensed under GNU GPL v2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright OctoGram, 2023-2025.
 */

package tw.nekomimi.nekogram.translate;

import android.graphics.Typeface;
import android.text.Html;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.text.util.Linkify;

import androidx.core.text.HtmlCompat;
import androidx.core.util.Pair;

import org.apache.commons.text.StringEscapeUtils;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.TextStyleSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** @noinspection UnnecessaryUnicodeEscape, SequencedCollectionMethodCanBeUsed */
public class HTMLKeeper {
    final private static String[] list_html_params = new String[]{"b", "i", "u", "s", "tt", "a", "q", "tg-emoji", "blockquote", "tg-pre"};

    private static final Pattern PATTERN_A_HREF = Pattern.compile("<a href=\".*?\">");
    private static final Pattern PATTERN_SPAN_COLOR_TO_Q = Pattern.compile("<span style=\"color:.*?;\">(.*?)</span>");
    private static final Pattern PATTERN_Q_TO_SPAN_COLOR = Pattern.compile("<q>(.*?)</q>");
    private static final Pattern PATTERN_TRAILING_NEWLINE = Pattern.compile("[\n\r]$");
    private static final Pattern PATTERN_NEWLINE_TO_BR = Pattern.compile("\n");

    private static void toHtml(StringBuilder out, Spanned text, int end, boolean includeLinks) {
        ArrayList<CharacterStyle> openStack = new ArrayList<>();

        int next;
        for (int i = 0; i < end; i = next) {
            next = text.nextSpanTransition(i, end, CharacterStyle.class);
            CharacterStyle[] spans = text.getSpans(i, next, CharacterStyle.class);
            Arrays.sort(spans, (o1, o2) -> {
                int priority1 = 0;
                if (o1 instanceof PreSpan || o1 instanceof BlockquoteSpan) {
                    priority1 = 2;
                } else if (o1 instanceof URLSpan) {
                    priority1 = 1;
                }
                int priority2 = 0;
                if (o2 instanceof PreSpan || o2 instanceof BlockquoteSpan) {
                    priority2 = 2;
                } else if (o2 instanceof URLSpan) {
                    priority2 = 1;
                }
                return Integer.compare(priority2, priority1);
            });

            ArrayList<CharacterStyle> currentActiveSpans = new ArrayList<>(Arrays.asList(spans));

            int shared = 0;
            while (shared < openStack.size() && shared < currentActiveSpans.size()
                    && sameSpan(openStack.get(shared), currentActiveSpans.get(shared))) {
                shared++;
            }
            for (int j = openStack.size() - 1; j >= shared; j--) {
                closeTagFor(out, openStack.remove(j));
            }
            for (int j = shared; j < currentActiveSpans.size(); j++) {
                CharacterStyle currentSpan = currentActiveSpans.get(j);
                openTagFor(out, currentSpan, includeLinks);
                openStack.add(currentSpan);
            }

            out.append(StringEscapeUtils.escapeHtml4(text.subSequence(i, next).toString()));
        }

        for (int j = openStack.size() - 1; j >= 0; j--) {
            closeTagFor(out, openStack.get(j));
        }
    }

    private static boolean sameSpan(CharacterStyle first, CharacterStyle second) {
        return first == second || first instanceof EntityURLSpan left && second instanceof EntityURLSpan right
                && left.entityIndex >= 0 && left.entityIndex == right.entityIndex;
    }

    private static void openTagFor(StringBuilder out, CharacterStyle span, boolean includeLinks) {
        if (span instanceof StyleSpan) {
            int style = ((StyleSpan) span).getStyle();
            if ((style & Typeface.BOLD) != 0) {
                out.append("<b>");
            }
            if ((style & Typeface.ITALIC) != 0) {
                out.append("<i>");
            }
        } else if (span instanceof TypefaceSpan) {
            out.append("<tt>");
        } else if (span instanceof UnderlineSpan) {
            out.append("<u>");
        } else if (span instanceof StrikethroughSpan) {
            out.append("<s>");
        } else if (span instanceof EntityURLSpan entitySpan) {
            out.append("<a data-nooagram-entity=\"").append(entitySpan.entityIndex).append("\" href=\"")
                    .append(includeLinks ? StringEscapeUtils.escapeHtml4(entitySpan.getURL()) : "https://telegram.org/")
                    .append("\">");
        } else if (span instanceof URLSpan) {
            out.append("<a href=\"").append(StringEscapeUtils.escapeHtml4(((URLSpan) span).getURL())).append("\">");
        } else if (span instanceof ForegroundColorSpan) {
            out.append("<q>");
        } else if (span instanceof BlockquoteSpan) {
            out.append("<blockquote>");
        } else if (span instanceof PreSpan) {
            String language = ((PreSpan) span).language;
            out.append("<tg-pre language=\"").append(StringEscapeUtils.escapeHtml4(language != null ? language : "")).append("\">");
        }
    }

    private static void closeTagFor(StringBuilder out, CharacterStyle span) {
        if (span instanceof StyleSpan) {
            int style = ((StyleSpan) span).getStyle();
            if ((style & Typeface.BOLD) != 0) {
                out.append("</b>");
            }
            if ((style & Typeface.ITALIC) != 0) {
                out.append("</i>");
            }
        } else if (span instanceof TypefaceSpan) {
            out.append("</tt>");
        } else if (span instanceof UnderlineSpan) {
            out.append("</u>");
        } else if (span instanceof StrikethroughSpan) {
            out.append("</s>");
        } else if (span instanceof URLSpan) {
            out.append("</a>");
        } else if (span instanceof ForegroundColorSpan) {
            out.append("</q>");
        } else if (span instanceof BlockquoteSpan) {
            out.append("</blockquote>");
        } else if (span instanceof PreSpan) {
            out.append("</tg-pre>");
        }
    }

    public static String entitiesToHtml(String text, ArrayList<TLRPC.MessageEntity> entities, boolean includeLink) {
        if (text == null || entities == null) {
            return text;
        }
        text = text.replace("\n", "\u2029");
        if (!includeLink) {
            text = text.replace("<", "\u2027");
        }
        SpannableStringBuilder messSpan = SpannableStringBuilder.valueOf(text);

        ArrayList<TLRPC.MessageEntity> entitiesForOldLogic = new ArrayList<>();
        ArrayList<TLRPC.MessageEntity> entitiesForNewLogic = new ArrayList<>();

        for (TLRPC.MessageEntity entity : entities) {
            if (entity instanceof TLRPC.TL_messageEntityBlockquote || entity instanceof TLRPC.TL_messageEntityPre pre && pre.offset + pre.length == text.length()) { // TODO: Handle Pre Entities that are not at the end
                entitiesForNewLogic.add(entity);
            } else {
                entitiesForOldLogic.add(entity);
            }
        }

        MediaDataController.addTextStyleRuns(entitiesForOldLogic, text, messSpan);
        CharacterStyle[] mSpans = messSpan.getSpans(0, messSpan.length(), CharacterStyle.class);
        for (CharacterStyle mSpan : mSpans) {
            if (mSpan instanceof TextStyleSpan) {
                int start = messSpan.getSpanStart(mSpan);
                int end = messSpan.getSpanEnd(mSpan);
                boolean isBold = (((TextStyleSpan) mSpan).getStyleFlags() & TextStyleSpan.FLAG_STYLE_BOLD) > 0;
                boolean isItalic = (((TextStyleSpan) mSpan).getStyleFlags() & TextStyleSpan.FLAG_STYLE_ITALIC) > 0;
                if (isBold && !isItalic || isBold && !includeLink) {
                    messSpan.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (!isBold && isItalic || isItalic && !includeLink) {
                    messSpan.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (isBold && isItalic && includeLink) {
                    messSpan.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if ((((TextStyleSpan) mSpan).getStyleFlags() & TextStyleSpan.FLAG_STYLE_MONO) > 0) {
                    messSpan.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if ((((TextStyleSpan) mSpan).getStyleFlags() & TextStyleSpan.FLAG_STYLE_UNDERLINE) > 0) {
                    messSpan.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if ((((TextStyleSpan) mSpan).getStyleFlags() & TextStyleSpan.FLAG_STYLE_STRIKE) > 0) {
                    messSpan.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if ((((TextStyleSpan) mSpan).getStyleFlags() & TextStyleSpan.FLAG_STYLE_SPOILER) > 0) {
                    messSpan.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_chat_messagePanelText)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if ((((TextStyleSpan) mSpan).getStyleFlags() & TextStyleSpan.FLAG_STYLE_URL) > 0) {
                    String url = ((TextStyleSpan) mSpan).getTextStyleRun().urlEntity.url;
                    if (url != null || !includeLink) {
                        int entityIndex = entities.indexOf(((TextStyleSpan) mSpan).getTextStyleRun().urlEntity);
                        messSpan.setSpan(new EntityURLSpan(url, entityIndex), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                if ((((TextStyleSpan) mSpan).getStyleFlags() & TextStyleSpan.FLAG_STYLE_MENTION) > 0) {
                    if (((TextStyleSpan) mSpan).getTextStyleRun().urlEntity instanceof TLRPC.TL_messageEntityMentionName) {
                        long id = ((TLRPC.TL_messageEntityMentionName) ((TextStyleSpan) mSpan).getTextStyleRun().urlEntity).user_id;
                        int entityIndex = entities.indexOf(((TextStyleSpan) mSpan).getTextStyleRun().urlEntity);
                        messSpan.setSpan(new EntityURLSpan("tg://user?id=" + id, entityIndex), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
        }

        for (TLRPC.MessageEntity entity : entitiesForNewLogic) {
            if (entity == null || entity.length <= 0 || entity.offset < 0 || entity.offset >= messSpan.length() || (entity.offset + entity.length) > messSpan.length()) {
                continue;
            }
            int start = entity.offset;
            int end = entity.offset + entity.length;
            if (entity instanceof TLRPC.TL_messageEntityBlockquote) {
                messSpan.setSpan(new BlockquoteSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (entity instanceof TLRPC.TL_messageEntityPre) {
                messSpan.setSpan(new PreSpan(entity.language), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        StringBuilder out = new StringBuilder();
        toHtml(out, messSpan, messSpan.length(), includeLink);
        String html_result = out.toString();

        if (!includeLink) {
            html_result = PATTERN_A_HREF.matcher(html_result).replaceAll("<a>");
            html_result = PATTERN_SPAN_COLOR_TO_Q.matcher(html_result).replaceAll("<q>$1</q>");
            html_result = StringEscapeUtils.unescapeHtml4(html_result);
        } else {
            html_result = html_result.replace("&#8233;", "\u2029");
        }
        html_result = html_result.replace("\u2029", "\n");
        return html_result;
    }

    public static Pair<String, ArrayList<TLRPC.MessageEntity>> htmlToEntities(String text, ArrayList<TLRPC.MessageEntity> entities, boolean internalLinks) {
        return htmlToEntities(text, entities, internalLinks, true);
    }

    public static Pair<String, ArrayList<TLRPC.MessageEntity>> htmlToEntities(String text, ArrayList<TLRPC.MessageEntity> entities, boolean internalLinks, boolean withFixes) {
        ArrayList<TLRPC.MessageEntity> returnEntities = new ArrayList<>();
        ArrayList<TLRPC.MessageEntity> copyEntities = null;
        if (entities != null) {
            copyEntities = new ArrayList<>(entities);
        }
        if (withFixes) {
            text = fixDoubleSpace(text);
            text = fixDoubleHtmlElement(text);
            text = fixStrangeSpace(text);
            text = fixHtmlCorrupted(text);
            text = text.replace("<a>", "<a href=\"https://telegram.org/\">");
            text = PATTERN_Q_TO_SPAN_COLOR.matcher(text).replaceAll("<span style=\"color:#000000;\">$1</span>");
            text = text.replace("\u2027", "&lt;");
            text = text.replace("\u0327", "<");
        }
        text = PATTERN_TRAILING_NEWLINE.matcher(text).replaceAll("");
        text = text.replace("\n", "<br/>");
        text = PATTERN_NEWLINE_TO_BR.matcher(text).replaceAll("<br/>");
        SpannableString htmlParsed = new SpannableString(fromHtml("<inject>" + text + "</inject>", new HTMLTagAttributesHandler(new CustomElementHandler())));
        if (internalLinks) {
            AndroidUtilities.addLinksSafe(htmlParsed, Linkify.ALL, false, true);
        }
        CharacterStyle[] mSpans = htmlParsed.getSpans(0, htmlParsed.length(), CharacterStyle.class);
        for (CharacterStyle mSpan : mSpans) {
            int start = htmlParsed.getSpanStart(mSpan);
            int end = htmlParsed.getSpanEnd(mSpan);
            TLRPC.MessageEntity entity = null;
            if (mSpan instanceof URLSpan urlSpan) {
                if (copyEntities != null) {
                    int identifiedIndex = mSpan instanceof EntityURLSpan identified ? identified.entityIndex : -1;
                    entity = mapLinkEntity(entities, copyEntities, identifiedIndex, urlSpan.getURL());
                } else {
                    entity = new TLRPC.TL_messageEntityTextUrl();
                    entity.url = urlSpan.getURL();
                }
            } else if (mSpan instanceof StyleSpan styleSpan) {
                entity = switch (styleSpan.getStyle()) {
                    case TextStyleSpan.FLAG_STYLE_BOLD -> new TLRPC.TL_messageEntityBold();
                    case TextStyleSpan.FLAG_STYLE_ITALIC -> new TLRPC.TL_messageEntityItalic();
                    default -> null;
                };
            } else if (mSpan instanceof TypefaceSpan) {
                entity = new TLRPC.TL_messageEntityCode();
            } else if (mSpan instanceof UnderlineSpan) {
                entity = new TLRPC.TL_messageEntityUnderline();
            } else if (mSpan instanceof StrikethroughSpan) {
                entity = new TLRPC.TL_messageEntityStrike();
            } else if (mSpan instanceof BlockquoteSpan) {
                entity = new TLRPC.TL_messageEntityBlockquote();
                entity.collapsed = true;
            } else if (mSpan instanceof PreSpan) {
                entity = new TLRPC.TL_messageEntityPre();
                entity.language = ((PreSpan) mSpan).language;
            } else if (mSpan instanceof ForegroundColorSpan) {
                entity = new TLRPC.TL_messageEntitySpoiler();
            } else if (mSpan instanceof AnimatedEmojiSpan animatedEmojiSpan) {
                TLRPC.TL_messageEntityCustomEmoji customEmoji = new TLRPC.TL_messageEntityCustomEmoji();
                customEmoji.document_id = animatedEmojiSpan.documentId;
                entity = customEmoji;
            }
            if (entity != null) {
                entity.offset = start;
                entity.length = end - start;
                returnEntities.add(entity);
            }
        }
        return Pair.create(htmlParsed.toString(), returnEntities);
    }

    static TLRPC.MessageEntity originalLink(ArrayList<TLRPC.MessageEntity> entities, int index, String url) {
        if (index >= 0 && index < entities.size()) return entities.get(index);
        if (url != null && !"https://telegram.org/".equals(url)) {
            for (TLRPC.MessageEntity original : entities) {
                if (original instanceof TLRPC.TL_messageEntityTextUrl && url.equals(original.url)) return original;
            }
        }
        return null;
    }

    static TLRPC.MessageEntity mapLinkEntity(ArrayList<TLRPC.MessageEntity> originals,
                                             ArrayList<TLRPC.MessageEntity> fallback,
                                             int identifiedIndex, String url) {
        TLRPC.MessageEntity identified = originalLink(originals, identifiedIndex, url);
        if (identified != null) {
            TLRPC.MessageEntity mapped = copyLinkEntity(identified);
            if (mapped != null) {
                removeByIdentity(fallback, identified);
            }
            return mapped;
        }
        for (int i = 0; i < fallback.size(); i++) {
            TLRPC.MessageEntity mapped = copyLinkEntity(fallback.get(i));
            if (mapped != null) {
                fallback.remove(i);
                return mapped;
            }
        }
        return null;
    }

    private static TLRPC.MessageEntity copyLinkEntity(TLRPC.MessageEntity oldEntity) {
        TLRPC.MessageEntity entity = null;
        if (oldEntity instanceof TLRPC.TL_messageEntityMentionName) {
            entity = new TLRPC.TL_messageEntityMentionName();
            ((TLRPC.TL_messageEntityMentionName) entity).user_id = ((TLRPC.TL_messageEntityMentionName) oldEntity).user_id;
        } else if (oldEntity instanceof TLRPC.TL_inputMessageEntityMentionName) {
            entity = new TLRPC.TL_inputMessageEntityMentionName();
            ((TLRPC.TL_inputMessageEntityMentionName) entity).user_id = ((TLRPC.TL_inputMessageEntityMentionName) oldEntity).user_id;
        } else if (oldEntity instanceof TLRPC.TL_messageEntityTextUrl) {
            entity = new TLRPC.TL_messageEntityTextUrl();
            entity.url = oldEntity.url;
        } else if (oldEntity instanceof TLRPC.TL_messageEntityUrl) {
            entity = new TLRPC.TL_messageEntityUrl();
        } else if (oldEntity instanceof TLRPC.TL_messageEntityMention) {
            entity = new TLRPC.TL_messageEntityMention();
        } else if (oldEntity instanceof TLRPC.TL_messageEntityBotCommand) {
            entity = new TLRPC.TL_messageEntityBotCommand();
        } else if (oldEntity instanceof TLRPC.TL_messageEntityHashtag) {
            entity = new TLRPC.TL_messageEntityHashtag();
        } else if (oldEntity instanceof TLRPC.TL_messageEntityCashtag) {
            entity = new TLRPC.TL_messageEntityCashtag();
        } else if (oldEntity instanceof TLRPC.TL_messageEntityEmail) {
            entity = new TLRPC.TL_messageEntityEmail();
        } else if (oldEntity instanceof TLRPC.TL_messageEntityBankCard) {
            entity = new TLRPC.TL_messageEntityBankCard();
        } else if (oldEntity instanceof TLRPC.TL_messageEntityPhone) {
            entity = new TLRPC.TL_messageEntityPhone();
        }
        return entity;
    }

    private static void removeByIdentity(ArrayList<TLRPC.MessageEntity> entities, TLRPC.MessageEntity target) {
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i) == target) {
                entities.remove(i);
                return;
            }
        }
    }

    // VARIOUS HTML FIXERS
    private static String fixStrangeSpace(String string) {
        for (String list_param : list_html_params) {
            String fixedStart = String.format(Locale.US, "<%s>", list_param);
            String fixedEnd = String.format(Locale.US, "</%s>", list_param);
            string = string.replace(String.format(Locale.US, "< %s>", list_param), fixedStart);
            string = string.replace(String.format(Locale.US, "<%s >", list_param), fixedStart);
            string = string.replace(String.format(Locale.US, "< %s >", list_param), fixedStart);
            string = string.replace(String.format(Locale.US, "</ %s>", list_param), fixedEnd);
            string = string.replace(String.format(Locale.US, "< / %s>", list_param), fixedEnd);
            string = string.replace(String.format(Locale.US, "< /%s>", list_param), fixedEnd);
            string = string.replace(String.format(Locale.US, "< /%s >", list_param), fixedEnd);
            string = string.replace(String.format(Locale.US, "</%s >", list_param), fixedEnd);
            string = string.replace(String.format(Locale.US, "< / %s >", list_param), fixedEnd);
        }
        return string;
    }

    private static String fixDoubleSpace(String string) {
        for (String list_param : list_html_params) {
            string = string.replace(" <" + list_param + "> ", " <" + list_param + ">");
            string = string.replace(" </" + list_param + "> ", "</" + list_param + "> ");
        }
        string = string.replace("<a> ", "<a>");
        string = string.replace(" </a>", "</a> ");
        return string;
    }

    private static String fixDoubleHtmlElement(String string) {
        for (String list_param : list_html_params) {
            for (String list_param2 : list_html_params) {
                string = string.replace("<" + list_param + "-" + list_param2 + ">", "<" + list_param + "><" + list_param2 + ">");
                string = string.replace("</" + list_param + "-" + list_param2 + ">", "</" + list_param2 + "></" + list_param + ">");
            }
        }
        return string;
    }

    private static String fixHtmlCorrupted(String string) {
        ArrayList<String> listUnclosedTags = new ArrayList<>();
        ArrayList<String> listUnopenedTags = new ArrayList<>();
        HTMLTagStack stack = new HTMLTagStack();
        stack.parse(string);
        for (int i = 0; i < stack.stack.size(); i++) {
            HTMLTagPosition tagPosition = stack.stack.get(i);
            String tag = tagPosition.tag().trim();
            int attributes = tag.indexOf(' ');
            if (attributes >= 0) tag = tag.substring(0, attributes);
            if (!tag.contains("/")) {
                listUnclosedTags.add(0, tag);
                listUnopenedTags.add(0, tag);
            } else {
                tag = tag.replace("/", "");
                if (listUnclosedTags.contains(tag)) {
                    listUnclosedTags.remove(0);
                    listUnopenedTags.remove(0);
                } else if (!listUnclosedTags.isEmpty()) {
                    boolean isValidData = new ArrayList<>(Arrays.asList(list_html_params)).contains(tag);
                    String tagToReplace;
                    if (!listUnclosedTags.isEmpty()) {
                        tagToReplace = "/" + listUnclosedTags.get(0);
                        listUnclosedTags.remove(0);
                    } else if (!listUnopenedTags.isEmpty() && isValidData) {
                        tagToReplace = listUnopenedTags.get(0);
                        listUnopenedTags.remove(0);
                    } else {
                        continue;
                    }
                    stack.replace(tagPosition.start(), tagPosition.end(), "<" + tagToReplace + ">");
                }
            }
        }
        return stack.text;
    }

    public static Spanned fromHtml(String source) {
        return fromHtml(source, null);
    }

    public static Spanned fromHtml(String source, Html.TagHandler tagHandler) {
        return HtmlCompat.fromHtml(source, HtmlCompat.FROM_HTML_MODE_LEGACY, null, tagHandler);
    }

    public static class BlockquoteSpan extends CharacterStyle {
        @Override
        public void updateDrawState(TextPaint ds) {
        }
    }

    public static class EntityURLSpan extends URLSpan {
        public final int entityIndex;

        public EntityURLSpan(String url, int entityIndex) {
            super(url);
            this.entityIndex = entityIndex;
        }
    }

    public static class PreSpan extends CharacterStyle {
        public final String language;

        public PreSpan(String language) {
            this.language = language;
        }

        @Override
        public void updateDrawState(TextPaint ds) {
        }
    }

    private record HTMLTagPosition(int start, int end, String tag) {
    }

    private static class HTMLTagStack {
        private final ArrayList<HTMLTagPosition> stack = new ArrayList<>();
        private String text;

        public void parse(String string) {
            stack.clear();
            int start;
            int end = 0;
            while (true) {
                start = string.indexOf("<", end);
                if (start == -1) {
                    break;
                }
                end = string.indexOf(">", start);
                if (end == -1) {
                    break;
                }
                String tag = string.substring(start + 1, end);
                stack.add(new HTMLTagPosition(start, end + 1, tag));
            }
            text = string;
        }

        public void replace(int start, int end, String string) {
            text = text.substring(0, start) + string + text.substring(end);
            parse(text);
        }
    }
}
