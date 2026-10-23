package com.radolyn.ayugram.utils;

import android.text.TextUtils;

import com.radolyn.ayugram.AyuConfig;
import com.radolyn.ayugram.AyuOptions;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.text.Normalizer;

/** Export folders are resolved from the message's account, never selectedAccount. */
public final class AyuMediaFolders {
    private AyuMediaFolders() {}

    public static String getFolderName(MessageObject message) {
        String folder = AyuOptions.getCustomSavePath();
        if (!TextUtils.isEmpty(folder)) {
            folder = sanitizeFolderName(folder, 0);
            if ("0".equals(folder)) folder = "Nooagram";
        }
        if (message != null && AyuConfig.INSTANCE.getSaveToChatSubfolder().Bool()) {
            String chat = getChatFolderName(message);
            folder = TextUtils.isEmpty(folder) ? chat : folder + File.separator + chat;
        }
        return folder;
    }

    public static String getChatFolderName(MessageObject message) {
        long peer = message.getDialogId();
        MessagesController controller = MessagesController.getInstance(message.currentAccount);
        String name = null;
        if (DialogObject.isUserDialog(peer)) {
            TLRPC.User user = controller.getUser(peer);
            if (user != null) name = UserObject.getUserName(user);
        } else if (DialogObject.isChatDialog(peer)) {
            TLRPC.Chat chat = controller.getChat(-peer);
            if (chat != null) name = chat.title;
        }
        if (name == null) name = String.valueOf(peer);
        return sanitizeFolderName(name, peer);
    }

    public static String sanitizeFolderName(String name, long fallbackDialogId) {
        if (name == null) return String.valueOf(fallbackDialogId);
        name = Normalizer.normalize(name, Normalizer.Form.NFKC);
        StringBuilder safe = new StringBuilder();
        // At most 60 code points, hence at most 240 UTF-8 bytes per path segment.
        for (int offset = 0, count = 0; offset < name.length() && count < 60; count++) {
            int point = name.codePointAt(offset);
            offset += Character.charCount(point);
            int type = Character.getType(point);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || point == 34 || point == 47 || point == 92 || point == 58
                    || point == 42 || point == 63 || point == 60 || point == 62 || point == 124) {
                safe.append('_');
            } else {
                safe.appendCodePoint(point);
            }
        }
        String result = safe.toString().trim();
        int start = 0, end = result.length();
        while (start < end && (result.charAt(start) == '.' || result.charAt(start) <= ' ')) start++;
        while (end > start && (result.charAt(end - 1) == '.' || result.charAt(end - 1) <= ' ')) end--;
        return start == end ? String.valueOf(fallbackDialogId) : result.substring(start, end);
    }
}
