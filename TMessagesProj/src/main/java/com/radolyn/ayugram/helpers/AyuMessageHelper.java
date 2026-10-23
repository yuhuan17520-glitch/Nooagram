package com.radolyn.ayugram.helpers;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import androidx.core.content.FileProvider;
import org.telegram.messenger.*;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteException;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_keyboard;
import java.io.*;
import java.util.*;
public final class AyuMessageHelper extends BaseController {
    private AyuMessageHelper(int account) { super(account); }
    public static AyuMessageHelper getInstance(int account) { return new AyuMessageHelper(account); }
    public static String getPathToMessage(MessageObject message) { return FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner).getAbsolutePath(); }
    public static String getTextOrBase64(byte[] data) {
        if(data==null) return "";
        try { return java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString(); }
        catch(java.nio.charset.CharacterCodingException e) { return android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP); }
    }
    private static void appendFilterPlainText(StringBuilder text, MessageObject messageObject) {
        if (text == null || messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        CharSequence messageText = messageObject.messageText;
        if (hasFilterText(messageText)
            && !TextUtils.equals(messageText, LocaleController.getString(R.string.AttachVideo))
            && !TextUtils.equals(messageText, LocaleController.getString(R.string.AttachPhoto))
            && !TextUtils.equals(messageText, LocaleController.getString(R.string.Album))) {
            text.append(messageText);
            text.append("\n");
        }
        if (hasFilterText(messageObject.caption)) {
            text.append(messageObject.caption);
            text.append("\n");
        }
        if (hasFilterText(messageObject.getVoiceTranscription())) {
            text.append(messageObject.getVoiceTranscription());
            text.append("\n");
        }
        String restrictionReason = messageObject.messageOwner.restriction_reason == null
                || messageObject.messageOwner.restriction_reason.isEmpty() ? null
                : MessagesController.getInstance(messageObject.currentAccount).getRestrictionReason(messageObject.messageOwner.restriction_reason);
        if (hasFilterText(restrictionReason)) {
            text.append(restrictionReason);
            text.append("\n");
        }
    }

    private static boolean hasFilterText(CharSequence text) {
        return text != null && text.length() > 0;
    }

    public static CharSequence getMessageFilterMatchText(MessageObject messageObject, MessageObject.GroupedMessages messageGroup) {
        return getMessageFilterText(messageObject, messageGroup).matchText;
    }

    public static final class FilterText {
        public final String matchText;
        public final ArrayList<String> content;

        private FilterText(String matchText, ArrayList<String> content) {
            this.matchText = matchText;
            this.content = content;
        }
    }

    public static FilterText getMessageFilterText(MessageObject messageObject, MessageObject.GroupedMessages messageGroup) {
        StringBuilder text = new StringBuilder();
        ArrayList<String> content = new ArrayList<>();
        ArrayList<MessageObject> messages = new ArrayList<>();
        if (messageGroup != null && messageGroup.messages != null && !messageGroup.messages.isEmpty()) {
            messages.addAll(messageGroup.messages);
        } else {
            messages.add(messageObject);
        }
        for (MessageObject member : messages) {
            StringBuilder plain = new StringBuilder();
            appendFilterPlainText(plain, member);
            text.append(plain);
            content.add(plain.toString());
        }
        // Keep the legacy match protocol, but expose only actual content to extraction.
        // Every album member contributes metadata regardless of the selected message.
        for (MessageObject member : messages) {
            appendFilterMetadata(text, content, member);
        }
        if (messageObject != null && messageObject.messageOwner != null) {
            text.append("\n<type>");
            text.append(messageObject.type);
            text.append("</type>");
        }
        return new FilterText(text.toString(), content);
    }

    private static void appendFilterMetadata(StringBuilder text, ArrayList<String> content, MessageObject messageObject) {
        if (messageObject != null && messageObject.messageOwner != null) {
            if (messageObject.isPoll()) {
                TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) messageObject.messageOwner.media).poll;
                StringBuilder pollText = new StringBuilder(poll.question.text).append("\n");
                for (TLRPC.PollAnswer answer : poll.answers) {
                    pollText.append("\n\uD83D\uDD18 ");
                    pollText.append(answer.text.text);
                }
                text.append(pollText);
                text.append("\n");
                content.add(pollText.toString());
            }

            ArrayList<TLRPC.MessageEntity> entities = messageObject.messageOwner.entities;
            if (entities != null && !entities.isEmpty()) {
                for (TLRPC.MessageEntity entity : entities) {
                    if (entity instanceof TLRPC.TL_messageEntityTextUrl && hasFilterText(entity.url)) {
                        text.append("\n");
                        text.append(entity.url);
                        content.add(entity.url);
                    }
                }
                text.append("\n");
            }

            if (messageObject.messageOwner.reply_markup instanceof TLRPC.TL_replyKeyboardMarkup keyboardMarkup) {
                if (!keyboardMarkup.rows.isEmpty()) {
                    text.append("\n");
                    for (TL_keyboard.KeyboardButtonRow row : keyboardMarkup.rows) {
                        if (row == null || row.buttons == null) {
                            continue;
                        }
                        for (TL_keyboard.KeyboardButton button : row.buttons) {
                            if (button == null) {
                                continue;
                            }
                            text.append("<button>");
                            if (hasFilterText(button.getText())) {
                                text.append(button.getText());
                                content.add(button.getText());
                            }
                            if (hasFilterText(button.getUrl())) {
                                text.append(" ");
                                text.append(button.getUrl());
                                content.add(button.getUrl());
                            }
                            text.append("</button>\n");
                        }
                    }
                }
            } else if (messageObject.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup inlineMarkup) {
                if (!inlineMarkup.rows.isEmpty()) {
                    text.append("\n");
                    for (TL_keyboard.KeyboardInlineButtonRow row : inlineMarkup.rows) {
                        if (row == null || row.buttons == null) {
                            continue;
                        }
                        for (TL_keyboard.KeyboardInlineButton button : row.buttons) {
                            if (button == null) {
                                continue;
                            }
                            text.append("<button>");
                            if (hasFilterText(button.getText())) {
                                text.append(button.getText());
                                content.add(button.getText());
                            }
                            if (hasFilterText(button.getUrl())) {
                                text.append(" ");
                                text.append(button.getUrl());
                                content.add(button.getUrl());
                            }
                            text.append("</button>\n");
                        }
                    }
                }
            }
        }
    }


    public static void addMessageToClipboard(MessageObject selectedObject, Runnable callback) {
        String path = getPathToMessage(selectedObject);
        if (!TextUtils.isEmpty(path)) {
            File file = new File(path);
            if (file.exists()) {
                addFileToClipboard(file, callback);
            }
        }
    }

    public static void addMessageToClipboardAsSticker(MessageObject selectedObject, Runnable callback) {
        String path = getPathToMessage(selectedObject);
        try {
            if (!TextUtils.isEmpty(path)) {
                Bitmap image = BitmapFactory.decodeFile(path);
                if (image != null) {
                    File file2 = path.endsWith(".jpg") ? new File(path.replace(".jpg", ".webp")) : new File(path + ".webp");
                    try (FileOutputStream stream = new FileOutputStream(file2)) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, stream);
                        } else {
                            image.compress(Bitmap.CompressFormat.WEBP, 100, stream);
                        }
                    } finally {
                        image.recycle();
                    }
                    addFileToClipboard(file2, callback);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void addFileToClipboard(File file, Runnable callback) {
        try {
            Context context = ApplicationLoader.applicationContext;
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
            ClipData clip = ClipData.newUri(context.getContentResolver(), "label", uri);
            clipboard.setPrimaryClip(clip);
            if (callback != null) callback.run();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }


    public ArrayList<TLRPC.Message> getMessagesStorageMessages(long dialogId, ArrayList<Integer> messageIds) {
        ArrayList<TLRPC.Message> messages = null;
        SQLiteCursor cursor = null;
        try {
            String ids = TextUtils.join(",", messageIds);
            cursor = getMessagesStorage().getDatabase().queryFinalized(String.format(Locale.US,"SELECT data FROM messages_v2 WHERE uid = %d AND mid IN (%s)", dialogId, ids));
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data != null) {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
                        if (messages == null) {
                            messages = new ArrayList<>();
                        }
                        messages.add(message);
                    }
                    data.reuse();
                }
            }
            cursor.dispose();
            cursor = null;
        } catch (SQLiteException e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return messages;
    }

}
