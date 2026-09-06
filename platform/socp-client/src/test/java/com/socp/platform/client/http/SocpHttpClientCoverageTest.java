package com.socp.platform.client.http;

import com.socp.platform.client.config.ServiceEndpoints;
import com.socp.platform.client.config.SocpClientProperties;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Direct exercises of {@link SocpHttpClient}. The class builds its own
 * {@code java.net.http.HttpClient} (no injection seam), so the internal-call
 * paths are driven against an ephemeral loopback {@code com.sun.net.httpserver}
 * server — the same deterministic pattern already used by
 * {@code SocpHttpClientExternalTest} in this module.
 */
@ExtendWith(MockitoExtension.class)
class SocpHttpClientCoverageTest {

    @Mock
    private ServiceTokenProvider tokens;
    @Mock
    private ServiceRequestSigner signer;
    @Mock
    private ObjectProvider<MeterRegistry> registry;

    private SocpClientProperties properties;
    private CapturingServer server;

    /** Minimal loopback stub that records requests and answers a scripted status. */
    private static final class CapturingServer {
        private final HttpServer httpServer;
        private final ConcurrentLinkedQueue<Map<String, String>> requests = new ConcurrentLinkedQueue<>();
        private final AtomicInteger calls = new AtomicInteger();

        CapturingServer(int firstStatus, int nextStatus, String body) throws IOException {
            httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            httpServer.createContext("/", exchange -> {
                Map<String, String> headers = new java.util.HashMap<>();
                exchange.getRequestHeaders().forEach((key, values) -> headers.put(key.toLowerCase(), values.getFirst()));
                StringBuilder payload = new StringBuilder();
                try (InputStream in = exchange.getRequestBody()) {
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) > -1) {
                        payload.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
                String query = exchange.getRequestURI().getQuery();
                requests.add(Map.of(
                        "method", exchange.getRequestMethod(),
                        "path", exchange.getRequestURI().getPath(),
                        "query", query == null ? "" : query,
                        "content-type", headers.getOrDefault("content-type", ""),
                        "authorization", headers.getOrDefault("authorization", ""),
                        "tenant", headers.getOrDefault("x-tenant-id", ""),
                        "idempotency", headers.getOrDefault("idempotency-key", ""),
                        "custom", headers.getOrDefault("x-custom", ""),
                        "body", payload.toString()));
                int status = calls.incrementAndGet() == 1 ? firstStatus : nextStatus;
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
                if (status == 200) {
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            });
            httpServer.start();
        }

        int port() {
            return httpServer.getAddress().getPort();
        }

        void stop() {
            httpServer.stop(0);
        }
    }

    private SocpHttpClient client(SocpClientProperties props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("socp.alert.url", "http://localhost:" + server.port())));
        return new SocpHttpClient(new ServiceEndpoints(env), tokens, props, registry, signer,
                new ExternalEndpointPolicy(props));
    }

    @BeforeEach
    void setUp() throws IOException {
        TenantContext.set("tenant-a");
        server = new CapturingServer(200, 200, "created");
        properties = new SocpClientProperties();
        properties.setRequestTimeoutMs(2000);
        properties.setRetryBackoffMs(0);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        server.stop();
    }

    @Test
    void internalPostCarriesTenantTokenAndActivityHeaders() {
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = client(properties);

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{\"a\":1}",
                Map.of("Idempotency-Key", "key-1", "X-Custom", "v", "X-Blank", " "));

        Map<String, String> request = server.requests.poll();
        assertThat(request.get("method")).isEqualTo("POST");
        assertThat(request.get("path")).isEqualTo("/alert-web/api/alarms");
        assertThat(request.get("body")).isEqualTo("{\"a\":1}");
        assertThat(request.get("content-type")).isEqualTo("application/json");
        assertThat(request.get("authorization")).isEqualTo("Bearer svc-token");
        assertThat(request.get("tenant")).isEqualTo("tenant-a");
        assertThat(request.get("idempotency")).isEqualTo("key-1");
        assertThat(request.get("custom")).isEqualTo("v");
        assertThat(call.ok()).isTrue();
        assertThat(call.status()).isEqualTo(200);
        assertThat(call.body()).isEqualTo("created");
        assertThat(call.error()).isNull();
        assertThat(call.attempts()).isEqualTo(1);
        assertThat(call.retryable()).isFalse();
        assertThat(call.targetLabel()).isEqualTo("alert-web");
        assertThat(call.failureReason()).isNull();
        verify(signer).sign(any(), eq("POST"), any(URI.class), eq("tenant-a"));
    }

    @Test
    void internalGetUsesGetMethodWithoutBody() {
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = client(properties);

        ServiceCall call = client.get(SocpService.ALERT, "/api/alarms/stats");

        Map<String, String> request = server.requests.poll();
        assertThat(request.get("method")).isEqualTo("GET");
        assertThat(request.get("path")).isEqualTo("/alert-web/api/alarms/stats");
        assertThat(request.get("body")).isEmpty();
        assertThat(request.get("authorization")).isEqualTo("Bearer svc-token");
        assertThat(call.ok()).isTrue();
    }

    @Test
    void internalPutMapsMethodAndBody() {
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = client(properties);

        ServiceCall call = client.putJson(SocpService.ALERT, "/api/alarms/a1/status",
                "{\"status\":\"RESOLVED\"}");

        Map<String, String> request = server.requests.poll();
        assertThat(request.get("method")).isEqualTo("PUT");
        assertThat(request.get("body")).isEqualTo("{\"status\":\"RESOLVED\"}");
        assertThat(request.get("content-type")).isEqualTo("application/json");
        assertThat(call.ok()).isTrue();
    }

    @Test
    void unauthorizedResponseInvalidatesTheTokenCacheWithoutRetrying() {
        server.stop();
        try {
            server = new CapturingServer(401, 200, "denied");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        given(tokens.token()).willReturn("expired-token");
        SocpHttpClient client = client(properties);

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{}");

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(401);
        assertThat(call.retryable()).isFalse();
        assertThat(call.attempts()).isEqualTo(1);
        assertThat(call.failureReason()).isEqualTo("HTTP 401");
        verify(tokens).invalidate();
    }

    @Test
    void serverErrorRetriesAndThenSucceedsWithoutBackoffDelay() {
        server.stop();
        try {
            server = new CapturingServer(500, 200, "recovered");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        properties.setMaxAttempts(2);
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = client(properties);

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{}");

        assertThat(call.ok()).isTrue();
        assertThat(call.attempts()).isEqualTo(2);
        assertThat(call.status()).isEqualTo(200);
        assertThat(server.requests).hasSize(2);
    }

    @Test
    void serverErrorWithoutRetryIsMarkedRetryable() {
        server.stop();
        try {
            server = new CapturingServer(500, 500, "boom");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = client(properties);

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{}");

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(500);
        assertThat(call.retryable()).isTrue();
        assertThat(call.attempts()).isEqualTo(1);
        assertThat(call.failureReason()).isEqualTo("HTTP 500");
    }

    @Test
    void connectionFailureIsRecordedAsRetryableError() {
        int deadPort = server.port();
        server.stop();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("socp.alert.url", "http://localhost:" + deadPort)));
        SocpHttpClient client = new SocpHttpClient(new ServiceEndpoints(env), tokens, properties,
                registry, signer, new ExternalEndpointPolicy(properties));

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{}");

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(-1);
        assertThat(call.error()).isNotBlank();
        assertThat(call.retryable()).isTrue();
        assertThat(call.body()).isEmpty();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void externalPostIsBlockedByTheEgressPolicyWithoutAnyNetworkCall() {
        properties.setExternalAllowedHosts(List.of("localhost"));
        properties.setExternalHttpsOnly(false);
        properties.setExternalAllowPrivateNetworks(false);
        SocpHttpClient client = client(properties);

        ServiceCall call = client.postExternal("http://localhost:" + server.port() + "/hook",
                "{}", "application/json", 2000);

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(-1);
        assertThat(call.error()).startsWith("External endpoint blocked");
        assertThat(call.retryable()).isFalse();
        assertThat(call.attempts()).isEqualTo(0);
        assertThat(call.target()).isNull();
        assertThat(call.targetLabel()).isEqualTo("external");
        assertThat(call.failureReason()).contains("private or reserved");
        assertThat(server.requests).isEmpty();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void externalSuccessIsRecordedOnTheMeterRegistry() {
        properties.setExternalAllowedHosts(List.of("localhost"));
        properties.setExternalHttpsOnly(false);
        properties.setExternalAllowPrivateNetworks(true);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        given(registry.getIfAvailable()).willReturn(meters);
        SocpHttpClient client = client(properties);

        ServiceCall call = client.postExternal("http://localhost:" + server.port() + "/hook",
                "{}", null, 2000, Map.of(), List.of("localhost"));

        assertThat(call.ok()).isTrue();
        assertThat(call.targetLabel()).isEqualTo("external");
        assertThat(meters.get("socp.client.calls").tags("target", "external", "outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(meters.get("socp.client.latency").tags("target", "external")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void serviceEndpointsAreExposedForSpecialCases() {
        ServiceEndpoints endpoints = client(properties).endpoints();
        assertThat(endpoints.url(SocpService.ALERT, "api/alarms"))
                .isEqualTo("http://localhost:" + server.port() + "/alert-web/api/alarms");
    }
}
