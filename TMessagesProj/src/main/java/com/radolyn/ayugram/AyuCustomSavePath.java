package com.radolyn.ayugram;

final class AyuCustomSavePath {
    private AyuCustomSavePath() {
    }

    static String normalize(String path) {
        path = path == null ? "" : path.trim();
        return path.isEmpty() ? "Nagram" : path;
    }
}
