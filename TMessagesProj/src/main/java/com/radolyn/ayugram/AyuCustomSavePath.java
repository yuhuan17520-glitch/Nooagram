package com.radolyn.ayugram;

import tw.nekomimi.nekogram.NekoConfig;

public final class AyuCustomSavePath {
    private AyuCustomSavePath() {
    }

    public static String normalize(String path) {
        path = path == null ? "" : path.trim();
        return path.isEmpty() ? NekoConfig.DEFAULT_CUSTOM_SAVE_PATH : path;
    }

    public static String validateInput(String input, String fallback) {
        if (input == null) return fallback;
        String path = input.trim();
        if (path.isEmpty()) return "";
        return isValid(path) ? path : fallback;
    }

    public static boolean isValid(String path) {
        if (path == null) return false;
        path = path.trim();
        if (path.isEmpty()) return true;
        if (path.length() > 255 || path.contains("..") || ".".equals(path)) return false;
        for (int offset = 0; offset < path.length(); ) {
            int codePoint = path.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                    || codePoint == '/' || codePoint == '\\' || codePoint == ':'
                    || codePoint == '*' || codePoint == '?' || codePoint == '"'
                    || codePoint == '<' || codePoint == '>' || codePoint == '|') {
                return false;
            }
        }
        return true;
    }
}
