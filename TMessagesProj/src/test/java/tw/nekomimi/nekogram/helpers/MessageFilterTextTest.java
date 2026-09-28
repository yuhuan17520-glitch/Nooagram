package tw.nekomimi.nekogram.helpers;

import org.junit.Test;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;

import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageFilterTextTest {
    @Test
    public void albumHiddenLinksMatchFromEitherSelectedMember() {
        MessageObject first = message("First photo", 1);
        MessageObject second = message("Go", 1);
        TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl();
        link.url = "https://MGC88.cc/entry";
        second.messageOwner.entities.add(link);
        MessageObject.GroupedMessages group = new MessageObject.GroupedMessages();
        group.messages.add(first);
        group.messages.add(second);

        MessageHelper.FilterText selected = MessageHelper.getMessageFilterText(second, group);
        CharSequence source = MessageHelper.getMessageFilterMatchText(first, group);
        assertEquals(selected.matchText, source);
        String regex = NooagramQuickFilter.buildRegex(selected.content, selected.matchText);
        assertEquals(Pattern.quote("MGC88.cc"), regex);
        assertTrue(Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(source).find());
    }

    @Test
    public void preservesManualMetadataRulesWithoutExtractingTags() {
        MessageObject message = message("Hi", 0);
        TLRPC.TL_replyInlineMarkup markup = new TLRPC.TL_replyInlineMarkup();
        TL_keyboard.TL_keyboardInlineButtonRow row = new TL_keyboard.TL_keyboardInlineButtonRow();
        TL_keyboard.TL_keyboardInlineButton button = new TL_keyboard.TL_keyboardInlineButton() {
            @Override
            public String getUrl() {
                return "https://t.me/sample";
            }
        };
        button.text = "Go";
        row.buttons.add(button);
        markup.rows.add(row);
        message.messageOwner.reply_markup = markup;

        MessageHelper.FilterText text = MessageHelper.getMessageFilterText(message, null);
        assertEquals("Hi\n\n<button>Go https://t.me/sample</button>\n\n<type>0</type>", text.matchText);
        assertTrue(Pattern.compile("<button>Go .*?</button>").matcher(text.matchText).find());
        assertTrue(Pattern.compile("<type>0</type>$").matcher(text.matchText).find());
        assertFalse(String.join("\n", text.content).contains("<button>"));
        assertFalse(String.join("\n", text.content).contains("<type>"));
        assertEquals(Pattern.quote("Hi"), NooagramQuickFilter.buildRegex(text.content, text.matchText));
    }

    private static MessageObject message(String caption, int type) {
        TLRPC.TL_message owner = new TLRPC.TL_message();
        MessageObject message = new MessageObject(0, owner, "", null, null, false, false, false, false);
        message.caption = caption;
        message.type = type;
        return message;
    }
}
