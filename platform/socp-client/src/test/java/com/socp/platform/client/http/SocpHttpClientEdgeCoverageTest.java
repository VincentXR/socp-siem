package com.socp.platform.client.http;

import com.socp.platform.client.config.ServiceEndpoints;
import com.socp.platform.client.config.SocpClientProperties;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Edge-branch exercises of {@link SocpHttpClient} that the existing coverage
 * tests do not reach: every convenience overload, the activity-header filter,
 * the success/failure log branches (debug success line, redaction, URL
 * sanitising and body truncation) and the external-egress guard rails.  Uses
 * the same deterministic loopback {@code com.sun.net.httpserver} stub pattern
 * as {@code SocpHttpClientCoverageTest}.
 */
@ExtendWith(MockitoExtension.class)
class SocpHttpClientEdgeCoverageTest {

    @Mock
    private ServiceTokenProvider tokens;
    @Mock
    private ServiceRequestSigner signer;
    @Mock
    private ObjectProvider<MeterRegistry> registry;

    private SocpClientProperties properties;
    private StubServer server;
    private ListAppender<ILoggingEvent> logEvents;
    private Level originalLevel;

    private record StubbedResponse(int status, String body) {
    }

    private record CapturedRequest(String method, String path, String query,
                                   Map<String, String> headers, String body) {
        String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }
    }

    /** Loopback stub that records every request and replays scripted responses. */
    private static final class StubServer {
        private final HttpServer httpServer;
        private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final StubbedResponse[] scripted;

        StubServer(StubbedResponse... scripted) throws IOException {
            this.scripted = scripted;
            httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            httpServer.createContext("/", exchange -> {
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders().forEach((key, values) ->
                        headers.put(key.toLowerCase(Locale.ROOT), values.getFirst()));
                StringBuilder payload = new StringBuilder();
                try (InputStream in = exchange.getRequestBody()) {
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) > -1) {
                        payload.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
                String query = exchange.getRequestURI().getQuery();
                requests.add(new CapturedRequest(exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(), query == null ? "" : query,
                        Map.copyOf(headers), payload.toString()));
                int index = Math.min(calls.getAndIncrement(), scripted.length - 1);
                StubbedResponse response = scripted[index];
                byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                if (bytes.length == 0) {
                    exchange.sendResponseHeaders(response.status(), -1);
                } else {
                    exchange.sendResponseHeaders(response.status(), bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
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

    private SocpHttpClient client() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("socp.alert.url", "http://localhost:" + server.port())));
        return new SocpHttpClient(new ServiceEndpoints(env), tokens, properties, registry, signer,
                new ExternalEndpointPolicy(properties));
    }

    private SocpHttpClient clientAgainst(String baseUrl) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("socp.alert.url", baseUrl)));
        return new SocpHttpClient(new ServiceEndpoints(env), tokens, properties, registry, signer,
                new ExternalEndpointPolicy(properties));
    }

    @BeforeEach
    void setUp() throws IOException {
        TenantContext.set("tenant-a");
        server = new StubServer(new StubbedResponse(200, "created"));
        properties = new SocpClientProperties();
        properties.setRequestTimeoutMs(2000);
        properties.setRetryBackoffMs(0);
        Logger logger = (Logger) LoggerFactory.getLogger(SocpHttpClient.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logEvents = new ListAppender<>();
        logEvents.start();
        logger.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(SocpHttpClient.class);
        logger.detachAppender(logEvents);
        logger.setLevel(originalLevel);
        TenantContext.clear();
        MDC.remove("traceId");
        server.stop();
    }

    @Test
    void convenienceOverloadsRouteMethodBodyContentTypeAndHeaderFiltering() {
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = client();

        assertThat(client.postJson(SocpService.ALERT, "/api/alarms", "{}").ok()).isTrue();
        assertThat(client.postJson(SocpService.ALERT, "/api/alarms", "{}",
                Map.of("X-Custom", "v", "X-Blank", " ")).ok()).isTrue();
        assertThat(client.postJson(SocpService.ALERT, "/api/alarms", "{}", 1234,
                Map.of("Idempotency-Key", "k-1")).ok()).isTrue();
        assertThat(client.putJson(SocpService.ALERT, "/api/alarms/a1", "{}").ok()).isTrue();
        assertThat(client.putJson(SocpService.ALERT, "/api/alarms/a1", "{}",
                Map.of("Idempotency-Key", "k-2")).ok()).isTrue();
        assertThat(client.put(SocpService.ALERT, "/api/alarms/a1", "{}", "application/json", 1234).ok()).isTrue();
        assertThat(client.put(SocpService.ALERT, "/api/alarms/a1", "{}", "application/json", 1234, null).ok())
                .isTrue();
        assertThat(client.post(SocpService.ALERT, "/api/ingest", "{\"n\":1}", SocpHttpClient.NDJSON, 1234).ok())
                .isTrue();
        assertThat(client.post(SocpService.ALERT, "/api/ingest", "{\"n\":1}", SocpHttpClient.NDJSON, 1234, null)
                .ok()).isTrue();
        assertThat(client.get(SocpService.ALERT, "/api/alarms/stats", 1234).ok()).isTrue();

        List<CapturedRequest> sent = new ArrayList<>(server.requests);
        assertThat(sent).hasSize(10);
        assertThat(sent.get(1).header("x-custom")).isEqualTo("v");
        assertThat(sent.get(1).header("x-blank")).isEmpty();
        assertThat(sent.get(2).header("idempotency-key")).isEqualTo("k-1");
        assertThat(sent.get(4).header("idempotency-key")).isEqualTo("k-2");
        assertThat(sent.get(6).method()).isEqualTo("PUT");
        assertThat(sent.get(7).header("content-type")).isEqualTo(SocpHttpClient.NDJSON);
        assertThat(sent.get(8).method()).isEqualTo("POST");
        assertThat(sent.get(8).body()).isEqualTo("{\"n\":1}");
        assertThat(sent.get(9).method()).isEqualTo("GET");
        assertThat(sent.get(9).header("authorization")).isEqualTo("Bearer svc-token");
        assertThat(sent.get(9).header("x-tenant-id")).isEqualTo("tenant-a");
    }

    @Test
    void successfulCallsEmitADebugLogWithTargetAndStatus() {
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = client();

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{}");

        assertThat(call.ok()).isTrue();
        assertThat(logEvents.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("target=alert-web").contains("status=200");
        });
    }

    @Test
    void forbiddenResponseInvalidatesTheTokenCacheAndIsNotRetryable() {
        server.stop();
        try {
            server = new StubServer(new StubbedResponse(403, ""));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        given(tokens.token()).willReturn("stale-token");
        SocpHttpClient client = client();

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{}");

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(403);
        assertThat(call.retryable()).isFalse();
        assertThat(call.attempts()).isEqualTo(1);
        assertThat(call.failureReason()).isEqualTo("HTTP 403");
        verify(tokens).invalidate();
    }

    @Test
    void rateLimitedResponseIsMarkedRetryableButNotRetriedByDefault() {
        server.stop();
        try {
            server = new StubServer(new StubbedResponse(429, ""));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = client();

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{}");

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(429);
        assertThat(call.retryable()).isTrue();
        assertThat(call.attempts()).isEqualTo(1);
        assertThat(call.failureReason()).isEqualTo("HTTP 429");
        assertThat(server.requests).hasSize(1);
    }

    @Test
    void failedCallWarnLogRedactsSecretsAndTruncatesTheBody() {
        server.stop();
        try {
            server = new StubServer(new StubbedResponse(500,
                    "{\"note\":\"Bearer abc123\",\"password\":\"hunter2\"}"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        properties.setBodyLogLimit(40);
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = client();

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{}");

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(500);
        assertThat(call.body()).contains("hunter2");
        assertThat(logEvents.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            String message = event.getFormattedMessage();
            assertThat(message).contains("target=alert-web").contains("status=500").contains("tenant=tenant-a");
            assertThat(message).contains("error=-").contains("[REDACTED]").endsWith("...");
            assertThat(message).doesNotContain("hunter2").doesNotContain("abc123");
        });
    }

    @Test
    void connectionFailureWarnLogCarriesTheErrorAndNoHttpStatus() {
        int deadPort = server.port();
        server.stop();
        given(tokens.token()).willReturn("svc-token");
        SocpHttpClient client = clientAgainst("http://localhost:" + deadPort);

        ServiceCall call = client.postJson(SocpService.ALERT, "/api/alarms", "{}");

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(-1);
        assertThat(call.error()).isNotBlank();
        assertThat(call.retryable()).isTrue();
        assertThat(logEvents.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            String message = event.getFormattedMessage();
            assertThat(message).contains("status=-").contains("retryable=true");
            assertThat(message).contains("error=").doesNotContain("error=-");
        });
    }

    @Test
    void externalSuccessStripsTheQueryFromLoggedUrlsButNotFromTheRequest() {
        properties.setExternalAllowedHosts(List.of("localhost"));
        properties.setExternalHttpsOnly(false);
        properties.setExternalAllowPrivateNetworks(true);
        SocpHttpClient client = client();

        ServiceCall call = client.postExternal("http://localhost:" + server.port() + "/hook?run=1",
                "{}", "application/json", 2000);

        assertThat(call.ok()).isTrue();
        assertThat(call.target()).isNull();
        assertThat(call.targetLabel()).isEqualTo("external");
        CapturedRequest sent = server.requests.poll();
        assertThat(sent.query()).isEqualTo("run=1");
        assertThat(logEvents.list).anySatisfy(event -> {
            String message = event.getFormattedMessage();
            assertThat(message).contains("url=http://localhost:" + server.port() + "/hook");
            assertThat(message).doesNotContain("run=1");
        });
    }

    @Test
    void unparseableExternalUrlIsBlockedAndLoggedWithAnInvalidUrlMarker() {
        properties.setExternalHttpsOnly(false);
        properties.setExternalAllowPrivateNetworks(true);
        SocpHttpClient client = client();

        ServiceCall call = client.postExternal("http://local host/hook", "{}", "application/json", 2000);

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(-1);
        assertThat(call.attempts()).isEqualTo(0);
        assertThat(call.error()).startsWith("External endpoint blocked");
        assertThat(call.targetLabel()).isEqualTo("external");
        assertThat(server.requests).isEmpty();
        assertThat(logEvents.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("url=[INVALID_URL]");
        });
    }

    @Test
    void credentialQueryParametersAreRejectedByTheEgressPolicyBeforeAnyNetworkCall() {
        properties.setExternalAllowedHosts(List.of("localhost"));
        properties.setExternalHttpsOnly(false);
        properties.setExternalAllowPrivateNetworks(true);
        SocpHttpClient client = client();

        ServiceCall call = client.postExternal("http://localhost:" + server.port() + "/hook?token=abc",
                "{}", "application/json", 2000);

        assertThat(call.ok()).isFalse();
        assertThat(call.status()).isEqualTo(-1);
        assertThat(call.attempts()).isEqualTo(0);
        assertThat(call.error()).contains("credentials in the query");
        assertThat(call.retryable()).isFalse();
        assertThat(server.requests).isEmpty();
    }
}
