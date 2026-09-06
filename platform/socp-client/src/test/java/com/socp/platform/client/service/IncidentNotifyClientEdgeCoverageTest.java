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
 * End-to-end exercises of {@link IncidentClient} and {@link NotifyClient}
 * idempotency/query-string branches against a loopback HTTP stub.
 */
@ExtendWith(MockitoExtension.class)
class IncidentNotifyClientEdgeCoverageTest {

    @Mock
    private ServiceTokenProvider tokens;
    @Mock
    private ServiceRequestSigner signer;
    @Mock
    private ObjectProvider<MeterRegistry> registry;

    private SocpClientProperties properties;
    private LoopbackServer server;
    private IncidentClient incident;
    private NotifyClient notify;

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
                Map.of("socp.incident.url", "http://localhost:" + server.port(),
                        "socp.notify.url", "http://localhost:" + server.port())));
        SocpHttpClient http = new SocpHttpClient(new ServiceEndpoints(env), tokens, properties, registry,
                signer, new ExternalEndpointPolicy(properties));
        incident = new IncidentClient(http);
        notify = new NotifyClient(http);
        given(tokens.token()).willReturn("svc-token");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        server.stop();
    }

    @Test
    void createFromAlarmForwardsTheIdempotencyHeaderOnlyWhenPresent() {
        assertThat(incident.createFromAlarm("{\"alarmId\":1}", "run-1").ok()).isTrue();
        assertThat(incident.createFromAlarm("{\"alarmId\":2}", null).ok()).isTrue();
        assertThat(incident.createFromAlarm("{\"alarmId\":3}", "  ").ok()).isTrue();

        List<CapturedRequest> sent = server.all();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).method()).isEqualTo("POST");
        assertThat(sent.get(0).path()).isEqualTo("/incident-web/api/v1/incidents/from-alarm");
        assertThat(sent.get(0).body()).isEqualTo("{\"alarmId\":1}");
        assertThat(sent.get(0).header("idempotency-key")).isEqualTo("run-1");
        assertThat(sent.get(1).header("idempotency-key")).isEmpty();
        assertThat(sent.get(2).header("idempotency-key")).isEmpty();
    }

    @Test
    void setStatusEncodesTheAssigneeOnlyWhenPresent() {
        assertThat(incident.setStatus("c1", "CLOSED", "bob").ok()).isTrue();
        assertThat(incident.setStatus("c1", "CLOSED", null).ok()).isTrue();
        assertThat(incident.setStatus("c1", "CLOSED", "  ").ok()).isTrue();

        List<CapturedRequest> sent = server.all();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).method()).isEqualTo("POST");
        assertThat(sent.get(0).path()).isEqualTo("/incident-web/api/v1/incidents/c1/status");
        assertThat(sent.get(0).query()).isEqualTo("status=CLOSED&assignee=bob");
        assertThat(sent.get(1).query()).isEqualTo("status=CLOSED");
        assertThat(sent.get(2).query()).isEqualTo("status=CLOSED");
    }

    @Test
    void notifyAlertForwardsTheIdempotencyHeaderOnlyWhenPresent() {
        assertThat(notify.notifyAlert("{\"alarmId\":1}", "run-9").ok()).isTrue();
        assertThat(notify.notifyAlert("{\"alarmId\":2}", null).ok()).isTrue();
        assertThat(notify.notifyAlert("{\"alarmId\":3}", "  ").ok()).isTrue();

        List<CapturedRequest> sent = server.all();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).method()).isEqualTo("POST");
        assertThat(sent.get(0).path()).isEqualTo("/notify-web/api/v1/notify/alert");
        assertThat(sent.get(0).body()).isEqualTo("{\"alarmId\":1}");
        assertThat(sent.get(0).header("idempotency-key")).isEqualTo("run-9");
        assertThat(sent.get(1).header("idempotency-key")).isEmpty();
        assertThat(sent.get(2).header("idempotency-key")).isEmpty();
    }
}
