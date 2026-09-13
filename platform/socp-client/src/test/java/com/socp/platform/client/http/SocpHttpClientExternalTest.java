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
