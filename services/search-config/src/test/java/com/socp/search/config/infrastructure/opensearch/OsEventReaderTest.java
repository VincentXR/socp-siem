package com.socp.search.config.infrastructure.opensearch;


import com.sun.net.httpserver.HttpServer;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.OpenSearchProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OsEventReaderTest {

    private HttpServer server;
    private OsEventReader reader;
    private int responseStatus;
    private String responseBody;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            if (responseStatus >= 200 && responseStatus < 300) {
                exchange.sendResponseHeaders(responseStatus, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(responseStatus, -1);
            }
            exchange.close();
        });
        server.start();

        OpenSearchProperties properties = new OpenSearchProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setUsername("test");
        properties.setPassword("test");
        properties.setEnabled(true);
        properties.setSearchIndex("events-*");
        reader = new OsEventReader(properties);
        responseStatus = 503;
        responseBody = "";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        TenantContext.clear();
    }

    @Test
    void returnsNullWhenOpenSearchRespondsUnavailable() {
        assertNull(reader.search("source=auth", 50));
    }

    @Test
    void parsesEventsStatsAndSearchAfterCursor() {
        responseStatus = 200;
        responseBody = """
                {"hits":{"total":{"value":5},"hits":[
                  {"_source":{"eventId":"e1","timestamp":"2026-08-01T00:00:00Z","source":"auth","host":"h1","severity":"HIGH","msg":"failed","fields":{"src_ip":"10.0.0.1"},"ecs":{"event.code":"login"}},"sort":["2026-08-01T00:00:00Z","tenant-a|e1"]},
                  {"_source":{"eventId":"e2","timestamp":"not-a-time","source":"web","host":"h2","severity":"INFO","msg":"bad"}},
                  {"_source":{"eventId":"e3","timestamp":"2026-08-02T00:00:00Z","source":"web","host":"h3","severity":"LOW","msg":"ok","fields":{"src_ip":"10.0.0.2"}},"sort":["2026-08-02T00:00:00Z","e3"]},
                  {"_source":{"timestamp":"2026-08-03T00:00:00Z","source":"auth","host":"h4","severity":"INFO","msg":"generated id"}},
                  {"_id":"missing-source"}
                ]},"aggregations":{"top":{"buckets":[{"key":"10.0.0.1","doc_count":3},{"key_as_string":"10.0.0.2","doc_count":2}]}}}
                """;

        var result = TenantContext.callWith("tenant-a", () -> reader.search("* | top src_ip 5", 2));

        assertNotNull(result);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.events()).hasSize(3);
        assertThat(result.events().get(0).eventId()).isEqualTo("e1");
        assertThat(result.events().get(1).eventId()).isEqualTo("e3");
        assertThat(result.events().get(2).eventId()).isNotBlank();
        assertThat(result.stat().type()).isEqualTo("top");
        assertThat(result.stat().rows()).containsExactly(
                java.util.Map.of("key", "10.0.0.1", "count", 3L),
                java.util.Map.of("key", "10.0.0.2", "count", 2L));
        assertThat(result.nextCursor()).isNotBlank();
    }

    @Test
    void parsesCountAndTimechartAggregationsAndHandlesMalformedJson() {
        responseStatus = 200;
        responseBody = "{" +
                "\"hits\":{\"total\":{\"value\":1},\"hits\":[{" +
                "\"_source\":{\"eventId\":\"e1\",\"timestamp\":\"2026-08-01T00:00:00Z\",\"source\":\"auth\",\"host\":\"h\",\"severity\":\"INFO\",\"msg\":\"ok\"}}]}," +
                "\"aggregations\":{\"count_by\":{\"buckets\":[{\"key\":\"auth\",\"doc_count\":1}]}," +
                "\"timechart\":{\"buckets\":[{\"key_as_string\":\"2026-08-01\",\"doc_count\":1}]}}}";
        var count = TenantContext.callWith("tenant-a", () -> reader.search("* | count by source", 10));
        var chart = TenantContext.callWith("tenant-a", () -> reader.search("* | timechart", 10));
        assertThat(count.stat().type()).isEqualTo("count");
        assertThat(chart.stat().type()).isEqualTo("timechart");
        assertThat(count.nextCursor()).isNull();

        responseBody = "not-json";
        assertNull(TenantContext.callWith("tenant-a", () -> reader.search("*", 10)));
        assertNull(TenantContext.get(), "TenantContext.callWith must restore the caller scope");
    }
}
