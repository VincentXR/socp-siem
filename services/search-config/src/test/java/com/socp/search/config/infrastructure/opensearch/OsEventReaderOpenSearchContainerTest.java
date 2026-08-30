package com.socp.search.config.infrastructure.opensearch;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.OpenSearchProperties;
import com.socp.search.config.service.SplEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real OpenSearch semantic parity checks. They are opt-in locally and run in
 * CI's integration job with SOCP_TESTCONTAINERS=true.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class OsEventReaderOpenSearchContainerTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:2.11.1"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200));

    private String index;
    private OsEventReader reader;

    @BeforeEach
    void setUp() throws Exception {
        index = "socp-events-test-" + UUID.randomUUID();
        OpenSearchProperties properties = new OpenSearchProperties();
        properties.setEnabled(true);
        properties.setUrl("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        properties.setSearchIndex(index);
        reader = new OsEventReader(properties);
        put(documentPath(TENANT_A, "evt-1"), event(TENANT_A, "evt-1",
                "2026-08-30T00:00:00Z", "10.0.0.1", "auth", "HIGH"));
        put(documentPath(TENANT_A, "evt-2"), event(TENANT_A, "evt-2",
                "2026-08-30T00:01:00Z", "10.0.0.1", "auth", "LOW"));
        put(documentPath(TENANT_A, "evt-3"), event(TENANT_A, "evt-3",
                "2026-08-31T00:00:00Z", "10.0.0.2", "dns", "MEDIUM"));
        put(documentPath(TENANT_B, "evt-4"), event(TENANT_B, "evt-4",
                "2026-08-30T00:02:00Z", "10.0.0.1", "auth", "CRITICAL"));
        TenantContext.set(TENANT_A);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void equalityQueryAlwaysIncludesTenantBoundary() {
        SplEngine.QueryResult result = reader.search("source=auth", 100);

        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.events()).extracting(event -> event.eventId())
                .containsExactlyInAnyOrder("evt-1", "evt-2");
    }

    @Test
    void topAggregationReturnsNonEmptyCounts() {
        SplEngine.QueryResult result = reader.search("* | top src_ip 2", 100);

        assertThat(result.stat()).isNotNull();
        assertThat(result.stat().type()).isEqualTo("top");
        assertThat(result.stat().rows()).isNotEmpty();
        assertThat(result.stat().rows().getFirst()).containsEntry("key", "10.0.0.1")
                .containsEntry("count", 2L);
    }

    @Test
    void countByAggregationReturnsGroups() {
        SplEngine.QueryResult result = reader.search("* | count by source", 100);

        assertThat(result.stat()).isNotNull();
        assertThat(result.stat().type()).isEqualTo("count");
        assertThat(result.stat().rows()).extracting(row -> row.get("key"))
                .containsExactlyInAnyOrder("auth", "dns");
    }

    @Test
    void timechartAggregationReturnsBuckets() {
        SplEngine.QueryResult result = reader.search("* | timechart", 100);

        assertThat(result.stat()).isNotNull();
        assertThat(result.stat().type()).isEqualTo("timechart");
        assertThat(result.stat().rows()).extracting(row -> row.get("count"))
                .contains(2L, 1L);
    }

    @Test
    void searchAfterCursorReturnsNextPageWithoutRepeatingTheFirstEvent() {
        SplEngine.QueryResult first = reader.search("source=auth", 1);
        assertThat(first.nextCursor()).isNotBlank();

        SplEngine.QueryResult second = reader.search("source=auth", 1, first.nextCursor());

        assertThat(second.events()).hasSize(1);
        assertThat(second.events().getFirst().eventId()).isNotEqualTo(first.events().getFirst().eventId());
    }

    private String documentPath(String tenant, String eventId) {
        String documentId = URLEncoder.encode(tenant + "|" + eventId, StandardCharsets.UTF_8);
        return "/" + index + "/_doc/" + documentId + "?refresh=true";
    }

    private static String event(String tenant, String id, String timestamp, String srcIp,
                                String source, String severity) {
        return "{\"schemaVersion\":\"1.0\",\"eventId\":\"" + id + "\","
                + "\"tenantId\":\"" + tenant + "\",\"timestamp\":\"" + timestamp + "\","
                + "\"source\":\"" + source + "\",\"host\":\"web-1\",\"severity\":\""
                + severity + "\",\"msg\":\"event\",\"fields\":{\"tenant_id\":\""
                + tenant + "\",\"src_ip\":\"" + srcIp + "\"}}";
    }

    private static void put(String path, String body) throws Exception {
        request(path, "PUT", body, 200, 201);
    }

    private static String request(String path, String method, String body, int... statuses) throws Exception {
        URI uri = URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200) + path);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(java.util.Arrays.stream(statuses).boxed().toList()).contains(response.statusCode());
        return response.body();
    }
}
