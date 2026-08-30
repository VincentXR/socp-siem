package com.socp.platform.client.http;


import com.socp.platform.client.config.ServiceEndpoints;
import com.socp.platform.client.config.SocpClientProperties;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.obs.web.TraceIdFilter;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 服务间调用的唯一出口。
 *
 * <p><b>它替代了什么</b>：此前 8 个服务各自拷了一份 {@code util/Http.java}
 * （{@code HttpURLConnection} + {@code catch (Exception e) { return -1; }}），调用方还普遍
 * 不接返回值。结果就是 detect-web 把告警推给 alert-web 失败时，日志里一个字都没有——
 * 攻击命中了，告警凭空消失。
 *
 * <p><b>它保证什么</b>：
 * <ul>
 *   <li><b>失败可见</b>：非 2xx 或异常一律 WARN，带上 target / url / status / 耗时 /
 *       attempts / traceId / tenant / retryable / 响应体片段，可直接定位；</li>
 *   <li><b>超时可控</b>：连接与读超时都有默认值，不会挂死在热路径上；</li>
 *   <li><b>可观测</b>：{@code socp.client.calls}（tag: target/outcome/status）与
 *       {@code socp.client.latency}（tag: target），Prometheus 直接可查扇出成功率；</li>
 *   <li><b>链路透传</b>：自动带 W3C {@code traceparent}，Jaeger 里是一条完整链路；</li>
 *   <li><b>鉴权</b>：自动附 {@code Authorization: Bearer}（{@link ServiceTokenProvider} 缓存），
 *       401 时作废缓存以便下次重新登录；</li>
 *   <li><b>租户透传</b>：带上当前 {@link TenantContext}，而不是所有调用都硬编码 default；</li>
 *   <li><b>重试位点</b>：{@code socp.client.max-attempts} 默认 1（非幂等扇出不重试），
 *       将来接 Resilience4j / Sentinel 也只需要改这一个类。</li>
 * </ul>
 *
 * <p>调用方仍可以选择「不处理返回值」的 best-effort 语义——业务不会被外部服务拖垮，
 * 但失败一定留下痕迹。这是 best-effort 与静默失败的区别。
 */
@Component
public class SocpHttpClient {

    private static final Logger log = LoggerFactory.getLogger(SocpHttpClient.class);

    public static final String JSON = "application/json";
    public static final String NDJSON = "application/x-ndjson";

    private final ServiceEndpoints endpoints;
    private final ServiceTokenProvider tokens;
    private final SocpClientProperties props;
    private final ObjectProvider<MeterRegistry> registry;
    private final ServiceRequestSigner requestSigner;
    private final ExternalEndpointPolicy externalEndpointPolicy;
    private final HttpClient http;

    public SocpHttpClient(ServiceEndpoints endpoints,
                          ServiceTokenProvider tokens,
                          SocpClientProperties props,
                          ObjectProvider<MeterRegistry> registry,
                          ServiceRequestSigner requestSigner,
                          ExternalEndpointPolicy externalEndpointPolicy) {
        this.endpoints = endpoints;
        this.tokens = tokens;
        this.props = props;
        this.registry = registry;
        this.requestSigner = requestSigner;
        this.externalEndpointPolicy = externalEndpointPolicy;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ------------------------------------------------------------------ 常用入口

    /** POST JSON 到内部服务的业务路径（context-path 自动补齐）。 */
    public ServiceCall postJson(SocpService target, String apiPath, String json) {
        return post(target, apiPath, json, JSON, props.getRequestTimeoutMs());
    }

    /** POST JSON，自定义超时。 */
    public ServiceCall postJson(SocpService target, String apiPath, String json, int timeoutMs) {
        return post(target, apiPath, json, JSON, timeoutMs);
    }

    /** POST 任意 content-type（例如批量摄取的 NDJSON）。 */
    public ServiceCall post(SocpService target, String apiPath, String body, String contentType, int timeoutMs) {
        return execute("POST", target, endpoints.url(target, apiPath), body, contentType, timeoutMs);
    }

    /** GET 内部服务。 */
    public ServiceCall get(SocpService target, String apiPath) {
        return get(target, apiPath, props.getRequestTimeoutMs());
    }

    /** GET 内部服务，自定义超时。 */
    public ServiceCall get(SocpService target, String apiPath, int timeoutMs) {
        return execute("GET", target, endpoints.url(target, apiPath), null, null, timeoutMs);
    }

    /**
     * POST 到平台外部的绝对地址（SOAR 剧本里的 webhook、通知渠道的回调地址等）。
     *
     * <p>这类地址由用户在界面上配置，不属于 {@link SocpService} 拓扑，因此 target 记为
     * {@code external}；其余（超时 / 状态码检查 / 日志 / 指标）与内部调用完全一致。
     */
    public ServiceCall postExternal(String absoluteUrl, String body, String contentType, int timeoutMs) {
        String policyError = externalEndpointPolicy.validate(absoluteUrl);
        if (policyError != null) {
            ServiceCall denied = new ServiceCall(null, absoluteUrl, false, -1, "",
                    "External endpoint blocked: " + policyError, 0, false, 0);
            record(denied);
            return denied;
        }
        return execute("POST", null, absoluteUrl, body, contentType == null ? JSON : contentType, timeoutMs);
    }

    /** 解析服务地址（少数场景需要自己拼 URL，例如 SSE 长连接）。 */
    public ServiceEndpoints endpoints() {
        return endpoints;
    }

    // ------------------------------------------------------------------ 核心实现

    private ServiceCall execute(String method, SocpService target, String url,
                                String body, String contentType, int timeoutMs) {
        long start = System.nanoTime();
        int attempts = 0;
        int status = -1;
        String respBody = "";
        String error = null;
        boolean retryable = false;
        int max = Math.max(1, props.getMaxAttempts());

        while (attempts < max) {
            attempts++;
            try {
                HttpRequest req = build(method, target, url, body, contentType, timeoutMs);
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                status = resp.statusCode();
                respBody = resp.body() == null ? "" : resp.body();
                error = null;
                if (target != null && (status == 401 || status == 403)) {
                    // token 可能过期/失效，作废缓存让下一次调用重新登录
                    tokens.invalidate();
                }
                retryable = status == 429 || status >= 500;
                if (status >= 200 && status < 300) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "Interrupted";
                retryable = false;
                break;
            } catch (Exception e) {
                status = -1;
                respBody = "";
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                // 连不上 / 超时 / 连接被重置，都属于「过一会儿可能就好了」
                retryable = true;
            }
            if (attempts < max && retryable) {
                sleep(props.getRetryBackoffMs());
            } else {
                break;
            }
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        boolean ok = status >= 200 && status < 300;
        ServiceCall call = new ServiceCall(target, url, ok, status, respBody, error, durationMs, retryable, attempts);
        record(call);
        return call;
    }

    private HttpRequest build(String method, SocpService target, String url,
                              String body, String contentType, int timeoutMs) {
        URI uri = URI.create(url);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs > 0 ? timeoutMs : props.getRequestTimeoutMs()));
        if (target != null) {
            String tenant = tenant();
            b.header("Authorization", "Bearer " + tokens.token())
                    .header("X-Tenant-Id", tenant);
            requestSigner.sign(b, method, uri, tenant);
        }
        String traceparent = TraceIdFilter.buildTraceparent();
        if (traceparent != null) {
            b.header("traceparent", traceparent);
        }
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.header("Content-Type", contentType == null ? JSON : contentType);
            b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return b.build();
    }

    /**
     * 当前租户：优先取请求上下文；服务间的异步扇出（虚拟线程里）拿不到上下文时退回 default，
     * 与网关的机机约定一致。
     */
    private static String tenant() {
        return TenantContext.require();
    }

    private static String tenantForLog() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "-" : tenant;
    }

    private void record(ServiceCall call) {
        String targetLabel = call.targetLabel();
        String outcome = call.ok() ? "success" : (call.status() < 0 ? "error" : "http_" + (call.status() / 100) + "xx");

        MeterRegistry mr = registry.getIfAvailable();
        if (mr != null) {
            mr.counter("socp.client.calls", "target", targetLabel, "outcome", outcome).increment();
            Timer.builder("socp.client.latency")
                    .tag("target", targetLabel)
                    .register(mr)
                    .record(call.durationMs(), TimeUnit.MILLISECONDS);
        }

        if (call.ok()) {
            if (log.isDebugEnabled()) {
                log.debug("服务间调用成功 target={} url={} status={} cost={}ms", targetLabel, call.url(), call.status(), call.durationMs());
            }
            return;
        }

        // 关键：失败必须留痕，且要带够定位信息（这正是原来 catch(Exception ignored) 缺失的部分）
        log.warn("服务间调用失败 target={} url={} status={} attempts={} cost={}ms tenant={} traceId={} retryable={} error={} body={}",
                targetLabel,
                call.url(),
                call.status() < 0 ? "-" : call.status(),
                call.attempts(),
                call.durationMs(),
                tenantForLog(),
                MDC.get("traceId"),
                call.retryable(),
                call.error() == null ? "-" : call.error(),
                truncate(call.body()));
    }

    private String truncate(String s) {
        if (s == null || s.isEmpty()) return "-";
        int limit = props.getBodyLogLimit();
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= limit ? one : one.substring(0, limit) + "...";
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
