package com.socp.threat.web.service;

import com.socp.platform.client.config.SocpClientProperties;
import com.socp.platform.client.http.ExternalEndpointPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxiiClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void actualPaginatedFetchCarriesPinnedResolutionToEveryConnection() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/collection", exchange -> {
            hits.incrementAndGet();
            respond(exchange, exchange.getRequestURI().getQuery() == null
                    ? "{\"objects\":[],\"more\":true,\"next\":\"opaque /?&+ token\"}" : "{\"objects\":[]}");
        }); server.start();
        String host = "taxii-pinned-fixture.invalid";
        com.socp.platform.client.http.PinnedDnsResolverProvider.pin(host,
                new java.net.InetAddress[] { java.net.InetAddress.getByName("127.0.0.1") });
        try {
            var pages = new TaxiiClient(Duration.ofSeconds(2), true, localPolicy(host))
                    .fetchCollection(URI.create("http://" + host + ":" + server.getAddress().getPort() + "/collection"), null);
            assertThat(pages).hasSize(2); assertThat(hits).hasValue(2);
        } finally { com.socp.platform.client.http.PinnedDnsResolverProvider.unpin(host); }
    }

    @Test
    void followsBoundedSameHostPaginationAndSendsTaxiiHeaders() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collection", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = exchange.getRequestURI().getQuery() == null
                    ? "{\"objects\":[{\"id\":\"indicator--1\"}],\"more\":true,\"next\":\"opaque /?&+ token\"}"
                    : "{\"objects\":[{\"id\":\"indicator--2\"}]}";
            respond(exchange, body);
        });
        server.start();
        URI collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/collection");

        List<String> pages = new TaxiiClient(Duration.ofSeconds(3), true, localPolicy("127.0.0.1"))
                .fetchCollection(collection, "Bearer test-token");

        assertThat(pages).hasSize(2).allMatch(body -> body.contains("indicator--"));
        assertThat(authorization).hasValue("Bearer test-token");
    }

    @Test
    void invalidJsonRetainsSanitizedProtocolFailureRatherThanTransportFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collection", exchange -> respond(exchange, "not-json-with-private-content"));
        server.start();
        URI collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/collection");
        assertThatThrownBy(() -> new TaxiiClient(Duration.ofSeconds(3), true, localPolicy("127.0.0.1"))
                .fetchCollection(collection, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid TAXII response JSON");
    }

    @Test
    void rejectsNonHttpsCollectionWhenHttpEscapeHatchIsDisabled() {
        SocpClientProperties properties = new SocpClientProperties();
        properties.setExternalAllowedHosts(List.of("127.0.0.1"));
        assertThatThrownBy(() -> new TaxiiClient(Duration.ofSeconds(1), false,
                new ExternalEndpointPolicy(properties))
                .fetchCollection(URI.create("http://127.0.0.1:8080/collection"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void urlShapedOpaqueTokenCannotChangeHostPathOrOriginalFilters() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = new AtomicReference<String>();
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/collection", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, hits.incrementAndGet() == 1
                    ? "{\"more\":true,\"next\":\"https://attacker.invalid/p?q=secret\",\"objects\":[]}"
                    : "{\"more\":false,\"objects\":[]}");
        });
        server.start();
        URI collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/collection?match%5Btype%5D=indicator&limit=1");
        assertThat(new TaxiiClient(Duration.ofSeconds(3), true, localPolicy("127.0.0.1"))
                .fetchCollection(collection, null)).hasSize(2);
        assertThat(query.get()).startsWith("match%5Btype%5D=indicator&limit=1&next=");
        assertThat(java.net.URLDecoder.decode(query.get(), StandardCharsets.UTF_8))
                .contains("next=https://attacker.invalid/p?q=secret");
    }

    @Test
    void fallsBackToDateAddedWatermarkAndNeverReportsMissingPaginationAsComplete() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = new AtomicReference<String>();
        server.createContext("/collection", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            boolean next = query.get().contains("added_after");
            exchange.getResponseHeaders().set("X-TAXII-Date-Added-Last", "2026-01-01T00:00:00Z");
            respond(exchange, next ? "{\"objects\":[],\"more\":false}" : "{\"objects\":[],\"more\":true}");
        });
        server.createContext("/broken", exchange -> respond(exchange, "{\"more\":true,\"objects\":[]}"));
        server.start();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var client = new TaxiiClient(Duration.ofSeconds(3), true, localPolicy("127.0.0.1"));
        assertThat(client.fetchCollection(origin.resolve("/collection?limit=1"), null)).hasSize(2);
        assertThat(query.get()).isEqualTo("limit=1&added_after=2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> client.fetchCollection(origin.resolve("/broken"), null))
                .hasMessageContaining("more requires next");
    }

    @Test
    void rejectsRepeatedCursorWithoutAnUnboundedLoop() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/collection", exchange -> {
            hits.incrementAndGet();
            respond(exchange, "{\"more\":true,\"next\":\"same-token\",\"objects\":[]}");
        });
        server.start();
        URI collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/collection");
        assertThatThrownBy(() -> new TaxiiClient(Duration.ofSeconds(3), true, localPolicy("127.0.0.1"))
                .fetchCollection(collection, null)).hasMessageContaining("did not advance");
        assertThat(hits).hasValue(2);
    }

    @Test
    void rejectsAllowlistedHostsThatResolveToPrivateAddresses() {
        SocpClientProperties properties = new SocpClientProperties();
        properties.setExternalAllowedHosts(List.of("127.0.0.1"));
        properties.setExternalHttpsOnly(false);

        assertThatThrownBy(() -> new TaxiiClient(Duration.ofSeconds(1), true,
                new ExternalEndpointPolicy(properties))
                .fetchCollection(URI.create("http://127.0.0.1:8080/collection"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or reserved");
    }

    private static ExternalEndpointPolicy localPolicy(String... hosts) {
        SocpClientProperties properties = new SocpClientProperties();
        properties.setExternalAllowedHosts(List.of(hosts));
        properties.setExternalHttpsOnly(false);
        properties.setExternalAllowPrivateNetworks(true);
        return new ExternalEndpointPolicy(properties);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
