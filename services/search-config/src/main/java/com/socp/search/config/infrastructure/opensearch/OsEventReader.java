package com.socp.search.config.infrastructure.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.OpenSearchProperties;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.query.LocalQueryExecutor;
import com.socp.search.config.query.OpenSearchQueryCompiler;
import com.socp.search.config.query.PipelineCommand;
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
        return search(query, size, null);
    }

    /** Searches OpenSearch using the same AST semantics as the local executor. */
    public SplEngine.QueryResult search(String query, int size, String cursor) {
        int pageSize = Math.max(1, Math.min(100_000, size));
        SearchQueryAst ast = parser.parse(query).withPage(pageSize, cursor);
        if (!properties.isEnabled()) return null;
        try {
            String tenant = TenantContext.require();
            byte[] request = compiler.compile(ast, tenant, pageSize).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var response = transport.exchange("POST", "/" + properties.getSearchIndex() + "/_search",
                    "application/json", request);
            if (!response.successful()) {
                log.warn("OpenSearch search failed HTTP {}; local execution remains an explicit fallback", response.status());
                return null;
            }
            SplEngine.QueryResult result = parse(response.body(), ast, pageSize);
            log.debug("OpenSearch search matched={} returned={} query={}", result.total(), result.events().size(), query);
            return result;
        } catch (com.socp.search.config.query.SplParseException syntax) {
            throw syntax;
        } catch (Exception failure) {
            log.warn("OpenSearch search unavailable; local execution remains an explicit fallback: {}", failure.toString());
            return null;
        }
    }

    private SplEngine.QueryResult parse(byte[] body, SearchQueryAst ast, int size) throws Exception {
        long started = System.nanoTime();
        JsonNode root = MAPPER.readTree(body);
        JsonNode hits = root.path("hits").path("hits");
        List<SearchEvent> events = new ArrayList<>();
        String nextCursor = null;
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            if (source.isMissingNode()) continue;
            SearchEvent event = toEvent(source);
            if (event != null) {
                events.add(event);
                nextCursor = cursorFromHit(hit, event);
            }
        }
        int total = root.path("hits").path("total").path("value").asInt(events.size());
        if (total <= events.size()) nextCursor = null;
        SplEngine.QueryResult.Stat stat = parseStat(root.path("aggregations"), ast);
        return new SplEngine.QueryResult(total, events, stat, "opensearch", false,
                SplEngine.freshest(events), null, nextCursor,
                (System.nanoTime() - started) / 1_000_000L);
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
            return new SplEngine.QueryResult.Stat(type, rows);
        }
        return null;
    }

    private static String cursorFromHit(JsonNode hit, SearchEvent event) {
        JsonNode sort = hit.path("sort");
        if (sort.isArray() && sort.size() >= 2 && !sort.get(0).isNull()) {
            try {
                Instant timestamp = Instant.parse(sort.get(0).asText());
                String id = sort.get(1).asText();
                int separator = id.indexOf('|');
                if (separator >= 0) id = id.substring(separator + 1);
                return LocalQueryExecutor.encodeCursor(new SearchEvent(id, timestamp, event.source(), event.host(),
                        event.severity(), event.msg(), event.fields(), event.ecs()));
            } catch (Exception ignored) { /* fall back to _source identity */ }
        }
        return LocalQueryExecutor.encodeCursor(event);
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
}
