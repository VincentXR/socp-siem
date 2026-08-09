package com.socp.asset.collect.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 极简同步 HTTP 客户端（资产上报用）。 */
public final class Http {
    public static int post(String url, String json, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + token());
            String _tp = com.socp.platform.obs.TraceIdFilter.buildTraceparent();
            if (_tp != null) c.setRequestProperty("traceparent", _tp);
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            c.disconnect();
            return code;
        } catch (Exception e) {
            return -1;
        }
    }


    /** 服务间调用鉴权 token：懒加载从网关 /auth/login 换取并缓存（25 分钟过期前刷新）。 */
    private static volatile String cachedToken;
    private static volatile long tokenExpireAt;
    private static final Object TOKEN_LOCK = new Object();

    static String token() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAt) {
            return cachedToken;
        }
        synchronized (TOKEN_LOCK) {
            if (cachedToken != null && now < tokenExpireAt) {
                return cachedToken;
            }
            try {
                String gw = System.getenv().getOrDefault("SOCP_GATEWAY_URL", "http://localhost:18092");
                HttpURLConnection c = (HttpURLConnection) new URL(gw + "/auth/login").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                String payload = "{\"username\":\"demo\",\"password\":\"demo123\"}";
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }
                if (c.getResponseCode() >= 200 && c.getResponseCode() < 300) {
                    try (InputStream is = c.getInputStream()) {
                        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        int i = body.indexOf("\"token\":\"");
                        if (i > 0) {
                            int s = i + 9;
                            int e = body.indexOf('"', s);
                            cachedToken = body.substring(s, e);
                            tokenExpireAt = now + 25 * 60 * 1000L;
                        }
                    }
                }
                c.disconnect();
            } catch (Exception ignored) {
            }
            if (cachedToken == null) {
                cachedToken = "demo-token";
            }
            return cachedToken;
        }
    }

    private Http() {
    }
}
