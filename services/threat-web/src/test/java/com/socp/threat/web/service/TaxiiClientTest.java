package com.socp.threat.web.service;

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
    void followsBoundedSameHostPaginationAndSendsTaxiiHeaders() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collection", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = exchange.getRequestURI().getQuery() == null
                    ? "{\"objects\":[{\"id\":\"indicator--1\"}],\"next\":\"/collection?page=2\"}"
                    : "{\"objects\":[{\"id\":\"indicator--2\"}]}";
            respond(exchange, body);
        });
        server.start();
        URI collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/collection");

        List<String> pages = new TaxiiClient(Duration.ofSeconds(3), true)
                .fetchCollection(collection, "Bearer test-token");

        assertThat(pages).hasSize(2).allMatch(body -> body.contains("indicator--"));
        assertThat(authorization).hasValue("Bearer test-token");
    }

    @Test
    void rejectsNonHttpsCollectionWhenHttpEscapeHatchIsDisabled() {
        assertThatThrownBy(() -> new TaxiiClient(Duration.ofSeconds(1), false)
                .fetchCollection(URI.create("http://127.0.0.1:8080/collection"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsCrossHostPaginationBeforeFollowingIt() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collection", exchange -> respond(exchange,
                "{\"next\":\"http://localhost:" + server.getAddress().getPort() + "/collection?page=2\"}"));
        server.start();
        URI collection = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/collection");

        assertThatThrownBy(() -> new TaxiiClient(Duration.ofSeconds(3), true)
                .fetchCollection(collection, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured host");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
