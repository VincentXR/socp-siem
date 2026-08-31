package com.socp.search.config.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.OpenSearchProperties;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.query.LocalQueryExecutor;
import com.socp.search.config.query.SplParseException;
import com.socp.search.config.query.SplParser;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real OpenSearch semantic parity checks. They are opt-in locally and run in
 * CI's integration job with SOCP_TESTCONTAINERS=true.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class OsEventReaderOpenSearchContainerTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
    private LocalQueryExecutor localExecutor;
    private SplParser parser;
    private List<SearchEvent> tenantAEvents;

    @BeforeEach
    void setUp() throws Exception {
        index = "socp-events-test-" + UUID.randomUUID();
        OpenSearchProperties properties = new OpenSearchProperties();
        properties.setEnabled(true);
        properties.setUrl("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        properties.setSearchIndex(index);
        reader = new OsEventReader(properties);
        localExecutor = new LocalQueryExecutor();
        parser = new SplParser();
        request(OpenSearchIndexTemplate.PATH, "PUT", OpenSearchIndexTemplate.payload(), 200);
        tenantAEvents = List.of(
                event(TENANT_A, "evt-1", "2026-08-30T00:00:00Z", "10.0.0.10",
                        "AUTH", "HIGH", "User *BLOCKED? login", "10"),
                event(TENANT_A, "evt-2", "2026-08-30T00:00:00Z", "10.0.0.2",
                        "auth", "LOW", "allowed", "2"),
                event(TENANT_A, "evt-3", "2026-08-31T00:00:00Z", null,
                        "dns", "CRITICAL", "blocked dns", "20"),
                event(TENANT_A, "evt-5", "2026-08-29T00:00:00Z", "10.0.0.2",
                        "auth", "MEDIUM", "ordinary login", "5"));
        for (SearchEvent event : tenantAEvents) {
            put(documentPath(TENANT_A, event.eventId()), MAPPER.writeValueAsString(event));
        }
        SearchEvent tenantB = event(TENANT_B, "evt-4", "2026-08-30T00:02:00Z", "10.0.0.10",
                "auth", "CRITICAL", "tenant b blocked", "99");
        put(documentPath(TENANT_B, tenantB.eventId()), MAPPER.writeValueAsString(tenantB));
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
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.events()).extracting(event -> event.eventId())
                .containsExactly("evt-1", "evt-2", "evt-5")
                .doesNotContain("evt-4");
    }

    @Test
    void topAggregationReturnsNonEmptyCounts() {
        SplEngine.QueryResult result = reader.search("* | top src_ip 2", 100);

        assertThat(result.stat()).isNotNull();
        assertThat(result.stat().type()).isEqualTo("top");
        assertThat(result.stat().rows()).isNotEmpty();
        assertThat(result.stat().rows().getFirst()).containsEntry("key", "10.0.0.2")
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
        SplEngine.QueryResult third = reader.search("source=auth", 1, second.nextCursor());

        assertThat(second.events()).hasSize(1);
        assertThat(List.of(first.events().getFirst().eventId(), second.events().getFirst().eventId(),
                third.events().getFirst().eventId())).containsExactly("evt-1", "evt-2", "evt-5");
        assertThat(third.nextCursor()).isNull();
        assertThatThrownBy(() -> reader.search("source=dns", 1, first.nextCursor()))
                .isInstanceOf(SplParseException.class)
                .hasMessageContaining("cursor");
    }

    @Test
    void localAndOpenSearchResultsMatchAcrossTypedFiltersAndAggregations() {
        List<String> queries = List.of(
                "source=auth",
                "msg contains \"*blocked?\"",
                "severity>=HIGH",
                "count>2",
                "timestamp>=2026-08-30T00:00:00Z",
                "src_ip>10.0.0.2",
                "missing=value",
                "missing!=value",
                "* | top src_ip 2",
                "* | count by source",
                "* | timechart");

        for (String query : queries) {
            SplEngine.QueryResult local = localExecutor.execute(
                    parser.parse(query).withPage(100, null), tenantAEvents);
            SplEngine.QueryResult remote = reader.search(query, 100);

            assertThat(remote).as(query).isNotNull();
            assertThat(remote.total()).as(query).isEqualTo(local.total());
            assertThat(remote.events()).as(query).extracting(SearchEvent::eventId)
                    .containsExactlyElementsOf(local.events().stream().map(SearchEvent::eventId).toList());
            assertThat(remote.stat()).as(query).isEqualTo(local.stat());
        }
    }

    private String documentPath(String tenant, String eventId) {
        String documentId = URLEncoder.encode(tenant + "|" + eventId, StandardCharsets.UTF_8);
        return "/" + index + "/_doc/" + documentId + "?refresh=true";
    }

    private static SearchEvent event(String tenant, String id, String timestamp, String srcIp,
                                     String source, String severity, String msg, String count) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("tenant_id", tenant);
        fields.put("count", count);
        fields.put("category", source);
        if (srcIp != null) fields.put("src_ip", srcIp);
        return new SearchEvent(id, Instant.parse(timestamp), source, "web-1", severity, msg,
                Map.copyOf(fields), Map.of("event.code", "login"));
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
        assertThat(java.util.Arrays.stream(statuses).boxed().toList())
                .withFailMessage("OpenSearch HTTP %s for %s %s: %s",
                        response.statusCode(), method, path, response.body())
                .contains(response.statusCode());
        return response.body();
    }
}
