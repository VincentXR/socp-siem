package com.socp.search.config.infrastructure.opensearch;

import com.sun.net.httpserver.HttpServer;
import com.socp.search.config.config.OpenSearchProperties;
import com.socp.search.config.domain.SearchEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsEventWriterTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void usesStableEventIdAndRequiresEveryBulkItemToSucceed() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/_bulk", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        installTemplateEndpoint();
        server.start();
        OsEventWriter writer = writer();

        assertTrue(writer.writeEventsAndAwait(List.of(event())).fullyAcknowledged(1));
        assertTrue(request.get().contains("\"_id\":\"tenant-a|event-1\""));
        assertTrue(request.get().contains("socp-events-2026.08.20"));
    }

    @Test
    void rejectsHttpSuccessThatContainsAnItemFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/_bulk", exchange -> {
            byte[] response = ("{\"errors\":true,\"items\":[{\"index\":{\"status\":400,"
                    + "\"error\":{\"reason\":\"mapping conflict\"}}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        installTemplateEndpoint();
        server.start();

        var result = writer().writeEventsAndAwait(List.of(event()));
        assertFalse(result.fullyAcknowledged(1));
        assertThat(result.permanentFailures()).hasSize(1);
    }

    @Test
    void rejectsHttpSuccessWithAIncompleteItemsArray() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/_bulk", exchange -> {
            byte[] response = "{\"errors\":false,\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        installTemplateEndpoint();
        server.start();

        var result = writer().writeEventsAndAwait(List.of(event()));
        assertFalse(result.fullyAcknowledged(1));
        assertThat(result.retryableFailures()).hasSize(1);
    }

    @Test
    void returnsPerItemAcknowledgedPermanentAndRetryableOutcomes() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/_bulk", exchange -> {
            byte[] response = """
                    {"took":12,"errors":true,"items":[
                      {"index":{"status":201}},
                      {"index":{"status":400,"error":{"type":"mapper_parsing_exception","reason":"bad mapping value"}}},
                      {"index":{"status":429,"error":{"type":"es_rejected_execution_exception","reason":"busy"}}}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        installTemplateEndpoint();
        server.start();

        var result = writer().writeEventsAndAwait(List.of(event("event-1"), event("event-2"), event("event-3")));

        assertThat(result.acknowledgedIds()).containsExactly("event-1");
        assertThat(result.permanentFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.itemIndex()).isEqualTo(1);
            assertThat(failure.reasonCode()).isEqualTo("mapper_parsing_exception");
            assertThat(failure.reason()).isEqualTo("bad mapping value");
        });
        assertThat(result.retryableFailures()).singleElement()
                .satisfies(failure -> assertThat(failure.itemIndex()).isEqualTo(2));
        assertThat(result.tookMs()).isEqualTo(12L);
    }

    @Test
    void doesNotAttemptBulkWhenTheProductionTemplateIsNotReady() throws Exception {
        AtomicInteger bulkRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(OpenSearchIndexTemplate.PATH, exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/_bulk", exchange -> {
            bulkRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        var result = writer().writeEventsAndAwait(List.of(event()));

        assertThat(result.retryableFailures()).singleElement()
                .satisfies(failure -> assertThat(failure.reasonCode()).isEqualTo("template_not_ready"));
        assertThat(bulkRequests).hasValue(0);
    }

    @Test
    void classifiesWholeRequestClientRejectionAsPermanent() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/_bulk", exchange -> {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        installTemplateEndpoint();
        server.start();

        var result = writer().writeEventsAndAwait(List.of(event()));

        assertThat(result.permanentFailures()).singleElement()
                .satisfies(failure -> assertThat(failure.reasonCode()).isEqualTo("bulk_http_400"));
        assertThat(result.retryableFailures()).isEmpty();
    }

    private OsEventWriter writer() {
        OpenSearchProperties properties = new OpenSearchProperties();
        properties.setUrl("http://localhost:" + server.getAddress().getPort());
        properties.setUsername("test");
        properties.setPassword("test");
        properties.setEnabled(true);
        return new OsEventWriter(properties);
    }

    private void installTemplateEndpoint() {
        server.createContext(OpenSearchIndexTemplate.PATH, exchange -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean sharedContract = payload.equals(OpenSearchIndexTemplate.payload())
                    && payload.contains("\"src_ip\": { \"type\": \"ip\" }")
                    && payload.contains("\"exact\"")
                    && payload.contains("socp_lowercase");
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(sharedContract ? 200 : 400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
    }

    private static SearchEvent event() {
        return event("event-1");
    }

    private static SearchEvent event(String id) {
        return new SearchEvent(id, Instant.parse("2026-08-20T23:59:59Z"),
                "auth", "host-1", "HIGH", "failed", Map.of("tenant_id", "tenant-a"), Map.of());
    }
}
