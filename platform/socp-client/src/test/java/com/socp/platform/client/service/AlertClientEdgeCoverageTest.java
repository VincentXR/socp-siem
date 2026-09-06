package com.socp.platform.client.service;

import com.socp.platform.client.config.ServiceEndpoints;
import com.socp.platform.client.config.SocpClientProperties;
import com.socp.platform.client.http.ExternalEndpointPolicy;
import com.socp.platform.client.http.ServiceTokenProvider;
import com.socp.platform.client.http.ServiceRequestSigner;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Typed {@link AlertClient} calls driven end-to-end against a loopback HTTP
 * stub, so the real request building (paths, query strings, JSON escaping and
 * Idempotency-Key header placement) is exercised rather than a mocked
 * {@link SocpHttpClient}.
 */
@ExtendWith(MockitoExtension.class)
class AlertClientEdgeCoverageTest {

    @Mock
    private ServiceTokenProvider tokens;
    @Mock
    private ServiceRequestSigner signer;
    @Mock
    private ObjectProvider<MeterRegistry> registry;

    private SocpClientProperties properties;
    private LoopbackServer server;
    private AlertClient client;

    private record CapturedRequest(String method, String path, String query,
                                   Map<String, String> headers, String body) {
        String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }
    }

    /** Loopback stub answering 200 with a fixed JSON body on every path. */
    private static final class LoopbackServer {
        private final HttpServer httpServer;
        private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();

        LoopbackServer() throws IOException {
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
                byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            httpServer.start();
        }

        int port() {
            return httpServer.getAddress().getPort();
        }

        List<CapturedRequest> all() {
            return new ArrayList<>(requests);
        }

        void stop() {
            httpServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        TenantContext.set("tenant-a");
        server = new LoopbackServer();
        properties = new SocpClientProperties();
        properties.setRequestTimeoutMs(2000);
        properties.setRetryBackoffMs(0);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("socp.alert.url", "http://localhost:" + server.port())));
        SocpHttpClient http = new SocpHttpClient(new ServiceEndpoints(env), tokens, properties, registry,
                signer, new ExternalEndpointPolicy(properties));
        client = new AlertClient(http);
        given(tokens.token()).willReturn("svc-token");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        server.stop();
    }

    @Test
    void forwardAlarmAndStatsWindowVariantsBuildTheExpectedRequests() {
        assertThat(client.forwardAlarm("{\"id\":1}").ok()).isTrue();
        assertThat(client.stats().ok()).isTrue();
        assertThat(client.stats("7d").ok()).isTrue();
        assertThat(client.stats("  ").ok()).isTrue();

        List<CapturedRequest> sent = server.all();
        assertThat(sent).hasSize(4);
        assertThat(sent.get(0).method()).isEqualTo("POST");
        assertThat(sent.get(0).path()).isEqualTo("/alert-web/api/alarms");
        assertThat(sent.get(0).body()).isEqualTo("{\"id\":1}");
        assertThat(sent.get(1).path()).isEqualTo("/alert-web/api/alarms/stats");
        assertThat(sent.get(1).query()).isEmpty();
        assertThat(sent.get(2).path()).isEqualTo("/alert-web/api/alarms/stats");
        assertThat(sent.get(2).query()).isEqualTo("window=7d");
        assertThat(sent.get(3).path()).isEqualTo("/alert-web/api/alarms/stats");
        assertThat(sent.get(3).query()).isEmpty();
    }

    @Test
    void addNoteOverloadsEscapeJsonAndTreatBlankKeysAsAbsent() {
        assertThat(client.addNote("a1", "alice", "hello").ok()).isTrue();
        assertThat(client.addNote("a1", "a\\b\"c\r\nd", "hi", "key-1").ok()).isTrue();
        assertThat(client.addNote("a1", "alice", null, "key-2").ok()).isTrue();

        List<CapturedRequest> sent = server.all();
        assertThat(sent.get(0).method()).isEqualTo("POST");
        assertThat(sent.get(0).path()).isEqualTo("/alert-web/api/alarms/a1/notes");
        assertThat(sent.get(0).body()).isEqualTo("{\"author\":\"alice\",\"content\":\"hello\"}");
        assertThat(sent.get(0).header("idempotency-key")).isEmpty();
        assertThat(sent.get(1).body()).isEqualTo("{\"author\":\"a\\\\b\\\"c\\r\\nd\",\"content\":\"hi\"}");
        assertThat(sent.get(1).header("idempotency-key")).isEqualTo("key-1");
        assertThat(sent.get(2).body()).isEqualTo("{\"author\":\"alice\",\"content\":null}");
        assertThat(sent.get(2).header("idempotency-key")).isEqualTo("key-2");
    }

    @Test
    void assignAndSetStatusOverloadsMapToPostAndPutWithOptionalKeys() {
        assertThat(client.assign("a1", "bob").ok()).isTrue();
        assertThat(client.assign("a1", "bob", "key-1").ok()).isTrue();
        assertThat(client.setStatus("a1", "RESOLVED").ok()).isTrue();
        assertThat(client.setStatus("a1", "RESOLVED", "key-2").ok()).isTrue();

        List<CapturedRequest> sent = server.all();
        assertThat(sent.get(0).method()).isEqualTo("POST");
        assertThat(sent.get(0).path()).isEqualTo("/alert-web/api/alarms/a1/assign");
        assertThat(sent.get(0).body()).isEqualTo("{\"assignee\":\"bob\"}");
        assertThat(sent.get(0).header("idempotency-key")).isEmpty();
        assertThat(sent.get(1).header("idempotency-key")).isEqualTo("key-1");
        assertThat(sent.get(2).method()).isEqualTo("PUT");
        assertThat(sent.get(2).path()).isEqualTo("/alert-web/api/alarms/a1/status");
        assertThat(sent.get(2).body()).isEqualTo("{\"status\":\"RESOLVED\"}");
        assertThat(sent.get(2).header("idempotency-key")).isEmpty();
        assertThat(sent.get(3).method()).isEqualTo("PUT");
        assertThat(sent.get(3).header("idempotency-key")).isEqualTo("key-2");
    }

    @Test
    void addTagOverloadsTreatBlankIdempotencyKeysAsAbsent() {
        assertThat(client.addTag("a1", "prod").ok()).isTrue();
        assertThat(client.addTag("a1", "prod", "  ").ok()).isTrue();

        List<CapturedRequest> sent = server.all();
        assertThat(sent.get(0).method()).isEqualTo("POST");
        assertThat(sent.get(0).path()).isEqualTo("/alert-web/api/alarms/a1/tags");
        assertThat(sent.get(0).body()).isEqualTo("{\"tag\":\"prod\"}");
        assertThat(sent.get(0).header("idempotency-key")).isEmpty();
        assertThat(sent.get(1).body()).isEqualTo("{\"tag\":\"prod\"}");
        assertThat(sent.get(1).header("idempotency-key")).isEmpty();
    }

    @Test
    void alarmLookupsInterpolateIdsIntoLookupPaths() {
        // Production interpolates the raw id into the lookup path (no extra
        // encoding layer); the stub accepts both slash and space characters.
        assertThat(client.getAlarm("alarm/1 x").ok()).isTrue();
        assertThat(client.evidence("alarm/1 x").ok()).isTrue();

        List<CapturedRequest> sent = server.all();
        assertThat(sent.get(0).method()).isEqualTo("GET");
        assertThat(sent.get(0).path()).isEqualTo("/alert-web/api/alarms/alarm/1 x");
        assertThat(sent.get(1).path()).isEqualTo("/alert-web/api/alarms/alarm/1 x/evidence");
    }
}
