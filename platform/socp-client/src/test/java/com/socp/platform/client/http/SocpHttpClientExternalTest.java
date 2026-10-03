package com.socp.platform.client.http;

import com.socp.platform.client.config.ServiceEndpoints;
import com.socp.platform.client.config.SocpClientProperties;
import com.socp.platform.tenant.context.TenantContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.StandardEnvironment;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SocpHttpClientExternalTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void irreversibleConnectorDoesNotUseConfiguredRetries() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            SocpClientProperties properties = new SocpClientProperties();
            properties.setExternalAllowedHosts(List.of("localhost"));
            properties.setExternalHttpsOnly(false);
            properties.setExternalAllowPrivateNetworks(true);
            properties.setMaxAttempts(3);
            properties.setRetryBackoffMs(0);
            ObjectProvider registry = mock(ObjectProvider.class);
            SocpHttpClient client = new SocpHttpClient(new ServiceEndpoints(new StandardEnvironment()),
                    mock(ServiceTokenProvider.class), properties, registry, mock(ServiceRequestSigner.class), new ExternalEndpointPolicy(properties));
            var result = client.postExternalOnce("http://localhost:" + server.getAddress().getPort() + "/hook", "{}", SocpHttpClient.JSON, 2000);
            assertThat(result.ok()).isFalse();
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(hits.get()).isEqualTo(1);
            // Existing callers keep their explicitly configured transport policy.
            client.postExternal("http://localhost:" + server.getAddress().getPort() + "/hook", "{}", SocpHttpClient.JSON, 2000);
            assertThat(hits.get()).isEqualTo(4);
            ServiceCall scoped = client.postExternalOnce("http://localhost:" + server.getAddress().getPort() + "/hook",
                    "{}", SocpHttpClient.JSON, 2000, java.util.Map.of(), List.of("localhost"));
            assertThat(scoped.attempts()).isEqualTo(1);
            assertThat(hits.get()).isEqualTo(5);
        } finally { server.stop(0); }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void connectsUsingValidationResultAndRejectsRequestBytesBeforeOpeningASocket() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet(); exchange.sendResponseHeaders(200, 2);
            try (var output = exchange.getResponseBody()) { output.write("ok".getBytes(StandardCharsets.UTF_8)); }
        }); server.start();
        try {
            var properties = new SocpClientProperties(); properties.setRequestBodyLimitBytes(4);
            var policy = new ExternalEndpointPolicy(properties) {
                @Override public PinnedEndpoint validatePinned(String url, List<String> hosts, boolean https, boolean privateNetworks) {
                    try { return PinnedEndpoint.resolved("pinned-only.invalid", new java.net.InetAddress[] { java.net.InetAddress.getByName("127.0.0.1") }); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
            };
            var client = new SocpHttpClient(new ServiceEndpoints(new StandardEnvironment()), mock(ServiceTokenProvider.class),
                    properties, mock(ObjectProvider.class), mock(ServiceRequestSigner.class), policy);
            String url = "http://pinned-only.invalid:" + server.getAddress().getPort();
            assertThat(client.postExternal(url, "{}", SocpHttpClient.JSON, 2000).ok()).isTrue();
            ServiceCall denied = client.postExternal(url, "中文", SocpHttpClient.JSON, 2000);
            assertThat(denied.ok()).isFalse(); assertThat(denied.retryable()).isFalse();
            assertThat(denied.attempts()).isZero(); assertThat(hits).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void externalBodyCapDoesNotTruncateAcceptedInternalBulkRequests() throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bulk", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes().length);
            exchange.sendResponseHeaders(200, 2);
            try (var output = exchange.getResponseBody()) { output.write("ok".getBytes(StandardCharsets.UTF_8)); }
        }); server.start();
        try {
            var properties = new SocpClientProperties();
            var endpoints = mock(ServiceEndpoints.class);
            var target = com.socp.platform.client.service.SocpService.ALERT;
            when(endpoints.url(target, "/bulk")).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/bulk");
            var tokens = mock(ServiceTokenProvider.class); when(tokens.token()).thenReturn("fixture-token");
            var client = new SocpHttpClient(endpoints, tokens, properties, mock(ObjectProvider.class),
                    mock(ServiceRequestSigner.class), new ExternalEndpointPolicy(properties));
            TenantContext.set("fixture");
            String payload = "x".repeat(1_048_577);
            assertThat(client.postJson(target, "/bulk", payload).ok()).isTrue();
            assertThat(received).hasValue(payload.length());
        } finally { TenantContext.clear(); server.stop(0); }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void externalCallDoesNotCarryPlatformCredentials() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            tenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            SocpClientProperties properties = new SocpClientProperties();
            properties.setExternalAllowedHosts(List.of("localhost"));
            properties.setExternalHttpsOnly(false);
            properties.setExternalAllowPrivateNetworks(true);
            ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
            ServiceRequestSigner signer = mock(ServiceRequestSigner.class);
            ObjectProvider registry = mock(ObjectProvider.class);
            when(registry.getIfAvailable()).thenReturn(null);
            SocpHttpClient client = new SocpHttpClient(new ServiceEndpoints(new StandardEnvironment()), tokens,
                    properties, registry, signer, new ExternalEndpointPolicy(properties));
            TenantContext.set("tenant-a");

            ServiceCall result = client.postExternal("http://localhost:" + server.getAddress().getPort()
                    + "/hook", "{}", SocpHttpClient.JSON, 2000);

            assertThat(result.ok()).isTrue();
            assertThat(authorization.get()).isNull();
            assertThat(tenant.get()).isNull();
            verifyNoInteractions(tokens, signer);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void oversizedResponseBodiesFailWithoutReadingThemAndAreNotRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        byte[] payload = new byte[8192];
        payload[0] = 'o';
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            SocpClientProperties properties = new SocpClientProperties();
            properties.setExternalAllowedHosts(List.of("localhost"));
            properties.setExternalHttpsOnly(false);
            properties.setExternalAllowPrivateNetworks(true);
            properties.setResponseBodyLimitBytes(1024);
            properties.setMaxAttempts(3);
            properties.setRetryBackoffMs(0);
            ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
            ServiceRequestSigner signer = mock(ServiceRequestSigner.class);
            ObjectProvider registry = mock(ObjectProvider.class);
            when(registry.getIfAvailable()).thenReturn(null);
            SocpHttpClient client = new SocpHttpClient(new ServiceEndpoints(new StandardEnvironment()), tokens,
                    properties, registry, signer, new ExternalEndpointPolicy(properties));

            ServiceCall result = client.postExternal("http://localhost:" + server.getAddress().getPort()
                    + "/hook", "{}", SocpHttpClient.JSON, 2000);

            assertThat(result.ok()).isFalse();
            assertThat(result.status()).isEqualTo(-1);
            assertThat(result.error()).contains("exceeds");
            assertThat(result.retryable()).isFalse();
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(hits.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
