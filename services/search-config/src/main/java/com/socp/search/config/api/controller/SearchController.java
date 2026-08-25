package com.socp.search.config.api.controller;





import com.socp.search.config.persistence.store.*;
import com.socp.search.config.parser.*;
import com.socp.search.config.domain.*;
import com.socp.search.config.domain.*;
import com.socp.search.config.infrastructure.kafka.*;
import com.socp.search.config.infrastructure.opensearch.*;
import com.socp.search.config.infrastructure.serialization.*;
import com.socp.search.config.persistence.entity.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.store.*;
import com.socp.search.config.service.*;
import com.socp.search.config.api.request.*;
import com.socp.platform.error.exception.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * SPL search and export. OpenSearch is the authoritative source; the bounded local
 * cache is only an explicitly marked degraded fallback and is never merged with it.
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private static final int SEARCH_LIMIT = 200;
    private static final int EXPORT_LIMIT = 500;

    private final SplEngine engine;
    private final SearchStore store;
    private final OsEventReader osReader;

    public SearchController(SplEngine engine, SearchStore store, OsEventReader osReader) {
        this.engine = engine;
        this.store = store;
        this.osReader = osReader;
    }

    @GetMapping
    public SplEngine.QueryResult search(@RequestParam(value = "q", defaultValue = "") String q) {
        return resolve(q, SEARCH_LIMIT);
    }

    /** Export follows the same source-selection policy as interactive search. */
    @GetMapping("/export")
    public ResponseEntity<String> export(
            @RequestParam(value = "q", defaultValue = "") String q,
            @RequestParam(defaultValue = "json") String format) {
        SplEngine.QueryResult result = resolve(q, EXPORT_LIMIT);
        String filename;
        String contentType;
        String body;
        if ("csv".equalsIgnoreCase(format)) {
            filename = "search.csv";
            contentType = "text/csv; charset=utf-8";
            body = toCsv(result);
        } else {
            filename = "search.json";
            contentType = "application/json";
            body = SearchEventJson.toJson(result.events());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-SOCP-Search-Source", result.source())
                .header("X-SOCP-Search-Degraded", String.valueOf(result.degraded()))
                .header("X-SOCP-Search-Total", String.valueOf(result.total()));
        if (result.freshness() != null) {
            response.header("X-SOCP-Search-Freshness", result.freshness().toString());
        }
        return response.contentType(MediaType.parseMediaType(contentType)).body(body);
    }

    private SplEngine.QueryResult resolve(String q, int limit) {
        SplEngine.QueryResult authoritative = osReader.search(q, limit);
        if (authoritative != null) {
            return authoritative.limitEvents(limit).withSource("opensearch", false,
                    freshest(authoritative.events()), null);
        }

        List<SearchEvent> localEvents;
        try {
            localEvents = store.all();
        } catch (RuntimeException failure) {
            throw ApiException.of(503,
                    "Search is unavailable: OpenSearch did not return a result and the local cache is unavailable");
        }
        if (localEvents == null) {
            throw ApiException.of(503,
                    "Search is unavailable: OpenSearch did not return a result and the local cache is unavailable");
        }
        SplEngine.QueryResult fallback = engine.execute(q, localEvents).limitEvents(limit);
        return fallback.withSource("local-cache", true, freshest(fallback.events()),
                "OpenSearch did not return a result; data is limited to the local cache");
    }

    private static Instant freshest(List<SearchEvent> events) {
        return events.stream().map(SearchEvent::timestamp).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private static String toCsv(SplEngine.QueryResult result) {
        StringBuilder sb = new StringBuilder("timestamp,source,host,severity,msg\n");
        for (SearchEvent event : result.events()) {
            sb.append(event.timestamp()).append(',').append(csv(event.source())).append(',').append(csv(event.host()))
                    .append(',').append(event.severity()).append(',').append(csv(event.msg())).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
