package com.socp.platform.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 服务间调用的 token 提供者：向网关 {@code /auth/login} 换 JWT 并缓存。
 *
 * <p>原先这段逻辑在 8 个服务里各拷贝了一份 {@code Http.token()}，而且换 token 失败时
 * {@code catch (Exception ignored)} 什么都不说，直接退化成字符串 {@code "demo-token"}——
 * 在 dev-bypass 关闭的环境里，这意味着后续所有服务间调用静默 401。现在：
 * <ul>
 *   <li>只有一份实现；</li>
 *   <li>换 token 失败会打 WARN（含网关地址与原因），不再无声无息；</li>
 *   <li>失败时仍返回占位 token 维持 best-effort 语义，但调用结果会在
 *       {@link SocpHttpClient} 侧以 401 的形式被记录，不会被吞。</li>
 * </ul>
 */
@Component
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    /** dev-bypass 场景下的占位 token（网关不可用时的降级值）。 */
    static final String FALLBACK_TOKEN = "demo-token";

    private final ServiceEndpoints endpoints;
    private final SocpClientProperties props;
    private final HttpClient http;

    private volatile String cachedToken;
    private volatile long expireAt;
    private volatile long lastWarnAt;
    private final Object lock = new Object();

    public ServiceTokenProvider(ServiceEndpoints endpoints, SocpClientProperties props) {
        this.endpoints = endpoints;
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** 取当前可用 token；过期或首次调用时同步换取。 */
    public String token() {
        long now = System.currentTimeMillis();
        String t = cachedToken;
        if (t != null && now < expireAt) {
            return t;
        }
        synchronized (lock) {
            now = System.currentTimeMillis();
            if (cachedToken != null && now < expireAt) {
                return cachedToken;
            }
            String fresh = login();
            if (fresh != null) {
                cachedToken = fresh;
                expireAt = now + props.getTokenTtlMs();
            } else {
                cachedToken = FALLBACK_TOKEN;
                // 降级 token 只缓存 30 秒，网关一旦恢复就能马上换到真 token
                expireAt = now + 30_000L;
            }
            return cachedToken;
        }
    }

    /** 强制作废缓存（收到 401 时由 {@link SocpHttpClient} 调用，下次调用会重新登录）。 */
    public void invalidate() {
        synchronized (lock) {
            cachedToken = null;
            expireAt = 0L;
        }
    }

    private String login() {
        String url = endpoints.gatewayUrl() + "/auth/login";
        try {
            String payload = "{\"username\":\"" + esc(props.getUsername())
                    + "\",\"password\":\"" + esc(props.getPassword()) + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                String token = extractToken(resp.body());
                if (token != null) {
                    return token;
                }
                warn("网关登录响应里没有 token 字段 url={} body={}", url, truncate(resp.body()));
                return null;
            }
            warn("服务间换取 token 失败 url={} status={} body={}", url, resp.statusCode(), truncate(resp.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warn("服务间换取 token 被中断 url={}", url, "");
        } catch (Exception e) {
            warn("服务间换取 token 异常 url={} error={}", url, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /** 网关不可用时会被高频触发，做个 60 秒节流，避免刷屏但不静默。 */
    private void warn(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 60_000L) {
            return;
        }
        lastWarnAt = now;
        log.warn(fmt + "（60s 内不再重复；将降级使用占位 token，dev-bypass 关闭时下游会返回 401）", args);
    }

    private static String extractToken(String body) {
        if (body == null) return null;
        int i = body.indexOf("\"token\":\"");
        if (i < 0) return null;
        int s = i + 9;
        int e = body.indexOf('"', s);
        return e > s ? body.substring(s, e) : null;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
