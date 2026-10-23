package com.radolyn.ayugram.utils;
public final class AyuHttpClient {
    public static final AyuHttpClient INSTANCE = new AyuHttpClient();
    private final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
        .connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30,java.util.concurrent.TimeUnit.SECONDS).build();
    public okhttp3.OkHttpClient getInstance() {return client;}
}
