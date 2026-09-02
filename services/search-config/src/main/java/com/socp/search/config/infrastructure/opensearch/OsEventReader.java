package com.socp.search.config.infrastructure.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.OpenSearchProperties;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.query.OpenSearchQueryCompiler;
import com.socp.search.config.query.PipelineCommand;
import com.socp.search.config.query.QueryCursorCodec;
import com.socp.search.config.query.SearchQueryAst;
import com.socp.search.config.query.SplParser;
import com.socp.search.config.service.SplEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** OpenSearch adapter for the storage-independent SPL AST. */
@Component
public class OsEventReader {
    private static final Logger log = LoggerFactory.getLogger(OsEventReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TIMELINE_BUCKETS = 180;

    private final OpenSearchProperties properties;
    private final OpenSearchHttpTransport transport;
    private final SplParser parser;
    private final OpenSearchQueryCompiler compiler;

    public OsEventReader() { this(new OpenSearchProperties()); }

    public OsEventReader(OpenSearchProperties properties) {
        this(properties, new OpenSearchHttpTransport(properties));
    }

    @Autowired
    public OsEventReader(OpenSearchProperties properties, OpenSearchHttpTransport transport) {
        this(properties, transport, new SplParser(), new OpenSearchQueryCompiler());
    }

    public OsEventReader(OpenSearchProperties properties, OpenSearchHttpTransport transport,
                         SplParser parser, OpenSearchQueryCompiler compiler) {
        this.properties = properties;
        this.transport = transport;
        this.parser = parser;
        this.compiler = compiler;
    }

    /** Backwards-compatible first page. */
    public SplEngine.QueryResult search(String query, int size) {
        return search(query, size, null, false);
    }

    /** Searches OpenSearch using the same AST semantics as the local executor. */
    public SplEngine.QueryResult search(String query, int size, String cursor) {
        return search(query, size, cursor, false);
    }

    /** Searches a page and optionally requests a bounded full-result histogram. */
    public SplEngine.QueryResult search(String query, int size, String cursor,
                                        boolean includeTimeline) {
        long started = System.nanoTime();
        int pageSize = Math.max(1, Math.min(5_000, size));
        SearchQueryAst ast = parser.parse(query).withPage(pageSize, cursor);
        if (!properties.isEnabled()) return null;
        try {
            String tenant = TenantContext.require();
            int transportSize = Math.min(5_001, pageSize + 1);
            byte[] request = compiler.compile(ast, tenant, transportSize, includeTimeline).toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var response = transport.exchange("POST", "/" + properties.getSearchIndex() + "/_search",
                    "application/json", request);
            if (!response.successful()) {
                if (response.status() >= 400 && response.status() < 500 && response.status() != 429) {
                    throw new com.socp.search.config.query.SplParseException(
                            "OpenSearch rejected query: " + sanitizedError(response.body(), response.status()), 0);
                }
                log.warn("OpenSearch search failed HTTP {}; local execution remains an explicit fallback", response.status());
                return null;
            }
            SplEngine.QueryResult result = parse(response.body(), ast, pageSize, started, includeTimeline);
            log.debug("OpenSearch search matched={} returned={} query={}", result.total(), result.events().size(), query);
            return result;
        } catch (com.socp.search.config.query.SplParseException syntax) {
            throw syntax;
        } catch (Exception failure) {
            log.warn("OpenSearch search unavailable; local execution remains an explicit fallback: {}", failure.toString());
            return null;
        }
    }

    private SplEngine.QueryResult parse(byte[] body, SearchQueryAst ast, int size, long started,
                                        boolean includeTimeline) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode hits = root.path("hits").path("hits");
        List<SearchEvent> events = new ArrayList<>();
        List<List<Object>> sortValues = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            if (source.isMissingNode()) continue;
            SearchEvent event = toEvent(source);
            if (event != null) {
                events.add(event);
                sortValues.add(sortValues(hit, event));
            }
        }
        int total = root.path("hits").path("total").path("value").asInt(events.size());
        boolean hasMore = !ast.hasAggregation() && hits.size() > size && !events.isEmpty();
        if (events.size() > size) {
            events = new ArrayList<>(events.subList(0, size));
            sortValues = new ArrayList<>(sortValues.subList(0, size));
        }
        String nextCursor = hasMore
                ? QueryCursorCodec.encode(ast, sortValues.getLast()) : null;
        SplEngine.QueryResult.Stat stat = parseStat(root.path("aggregations"), ast);
        Timeline timeline = includeTimeline ? parseTimeline(root.path("aggregations")) : Timeline.empty();
        return new SplEngine.QueryResult(total, events, stat, "opensearch", false,
                SplEngine.freshest(events), null, nextCursor,
                (System.nanoTime() - started) / 1_000_000L,
                root.has("took") ? root.path("took").asLong() : null,
                timeline.rows(), timeline.approximate());
    }

    private static Timeline parseTimeline(JsonNode aggregations) {
        JsonNode buckets = aggregations == null ? null : aggregations.path("timeline").path("buckets");
        if (buckets == null || !buckets.isArray() || buckets.isEmpty()) return Timeline.empty();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode bucket : buckets) {
            String key = bucket.path("key_as_string").isMissingNode()
                    ? bucket.path("key").asText() : bucket.path("key_as_string").asText();
            if (key.isBlank()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("count", bucket.path("doc_count").asLong());
            rows.add(row);
        }
        rows.sort(java.util.Comparator.comparing(row -> String.valueOf(row.get("key"))));
        boolean approximate = rows.size() > MAX_TIMELINE_BUCKETS;
        if (approximate) rows = rows.subList(rows.size() - MAX_TIMELINE_BUCKETS, rows.size());
        return new Timeline(rows, approximate);
    }

    private static SplEngine.QueryResult.Stat parseStat(JsonNode aggregations, SearchQueryAst ast) {
        if (aggregations == null || !aggregations.isObject()) return null;
        for (PipelineCommand command : ast.pipeline()) {
            String name;
            String type;
            if (command instanceof PipelineCommand.Top) { name = "top"; type = "top"; }
            else if (command instanceof PipelineCommand.CountBy) { name = "count_by"; type = "count"; }
            else if (command instanceof PipelineCommand.Timechart) { name = "timechart"; type = "timechart"; }
            else continue;
            JsonNode buckets = aggregations.path(name).path("buckets");
            if (!buckets.isArray()) return new SplEngine.QueryResult.Stat(type, List.of());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode bucket : buckets) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", bucket.path("key_as_string").isMissingNode()
                        ? bucket.path("key").asText() : bucket.path("key_as_string").asText());
                row.put("count", bucket.path("doc_count").asLong());
                rows.add(row);
            }
            long sumOtherDocCount = aggregations.path(name).path("sum_other_doc_count").asLong(0L);
            return new SplEngine.QueryResult.Stat(type, rows,
                    sumOtherDocCount > 0, sumOtherDocCount);
        }
        return null;
    }

    private static List<Object> sortValues(JsonNode hit, SearchEvent event) {
        JsonNode sort = hit.path("sort");
        if (sort.isArray() && sort.size() == 2 && !sort.get(0).isNull() && !sort.get(1).isNull()) {
            List<Object> values = new ArrayList<>(2);
            for (JsonNode value : sort) {
                if (value.isIntegralNumber()) values.add(value.longValue());
                else if (value.isFloatingPointNumber()) values.add(value.doubleValue());
                else values.add(value.asText());
            }
            return List.copyOf(values);
        }
        return List.of(event.timestamp().toString(), event.eventId());
    }

    private static String sanitizedError(byte[] body, int status) {
        String reason = "HTTP " + status;
        try {
            JsonNode error = MAPPER.readTree(body).path("error");
            String candidate = error.path("reason").asText();
            if (candidate.isBlank() && error.path("root_cause").isArray()
                    && !error.path("root_cause").isEmpty()) {
                candidate = error.path("root_cause").get(0).path("reason").asText();
            }
            if (!candidate.isBlank()) reason += " - " + candidate;
        } catch (Exception ignored) {
            // Keep only the status when OpenSearch did not return structured JSON.
        }
        reason = reason.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return reason.length() <= 240 ? reason : reason.substring(0, 240);
    }

    private SearchEvent toEvent(JsonNode src) {
        try {
            String eventId = src.path("eventId").asText("");
            if (eventId.isBlank()) eventId = UUID.randomUUID().toString();
            String rawTimestamp = src.path("timestamp").asText("");
            Instant timestamp = rawTimestamp.isBlank() ? Instant.now() : Instant.parse(rawTimestamp);
            return new SearchEvent(eventId, timestamp, src.path("source").asText("unknown"),
                    src.path("host").asText("unknown"), src.path("severity").asText("INFO"),
                    src.path("msg").asText(""), strMap(src.path("fields")), strMap(src.path("ecs")));
        } catch (Exception failure) {
            log.warn("OpenSearch document parse failed: {}", failure.toString());
            return null;
        }
    }

    private static Map<String, String> strMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        }
        return result;
    }

    private record Timeline(List<Map<String, Object>> rows, boolean approximate) {
        private static Timeline empty() {
            return new Timeline(List.of(), false);
        }
    }
}
