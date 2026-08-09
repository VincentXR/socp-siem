package com.socp.report.web.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 极简同步 HTTP 客户端（REPORT 拉取 ALERT 聚合统计）。 */
public final class Http {
    public static String get(String url, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Authorization", "Bearer " + token());
            c.setRequestProperty("X-Tenant-Id", "default");
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            c.disconnect();
            return bos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
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
