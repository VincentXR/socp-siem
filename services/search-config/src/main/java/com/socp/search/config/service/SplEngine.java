package com.socp.search.config.service;

import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.query.LocalQueryExecutor;
import com.socp.search.config.query.SearchQueryAst;
import com.socp.search.config.query.SplParser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * SPL facade. Parsing is storage-independent; the local executor is only one
 * implementation of the shared AST and is also the explicit degraded path.
 */
@Service
public class SplEngine {
    private static final int DEFAULT_PAGE_SIZE = 200;

    private final SplParser parser;
    private final LocalQueryExecutor localExecutor;

    public SplEngine() {
        this(new SplParser(), new LocalQueryExecutor());
    }

    public SplEngine(SplParser parser, LocalQueryExecutor localExecutor) {
        this.parser = parser;
        this.localExecutor = localExecutor;
    }

    /** Search result plus source provenance and page execution metadata. */
    public record QueryResult(int total, List<SearchEvent> events, Stat stat,
                              String source, boolean degraded, Instant freshness,
                              String degradationReason, String nextCursor, long elapsedMs) {
        public QueryResult(int total, List<SearchEvent> events, Stat stat) {
            this(total, events, stat, "unspecified", false, null, null, null, 0L);
        }

        public QueryResult(int total, List<SearchEvent> events, Stat stat,
                           String source, boolean degraded, Instant freshness,
                           String degradationReason) {
            this(total, events, stat, source, degraded, freshness, degradationReason, null, 0L);
        }

        public QueryResult {
            events = List.copyOf(events == null ? List.of() : events);
            source = source == null || source.isBlank() ? "unspecified" : source;
            if (total < 0) throw new IllegalArgumentException("total must not be negative");
            if (elapsedMs < 0) throw new IllegalArgumentException("elapsedMs must not be negative");
        }

        public QueryResult withSource(String source, boolean degraded, Instant freshness,
                                      String degradationReason) {
            return new QueryResult(total, events, stat, source, degraded, freshness,
                    degradationReason, nextCursor, elapsedMs);
        }

        public QueryResult withPageMetadata(String nextCursor, long elapsedMs) {
            return new QueryResult(total, events, stat, source, degraded, freshness,
                    degradationReason, nextCursor, elapsedMs);
        }

        public QueryResult limitEvents(int limit) {
            if (limit < 1 || events.size() <= limit) return this;
            return new QueryResult(total, events.stream().limit(limit).toList(), stat,
                    source, degraded, freshness, degradationReason,
                    LocalQueryExecutor.encodeCursor(events.get(limit - 1)), elapsedMs);
        }

        public record Stat(String type, List<java.util.Map<String, Object>> rows) {
            public Stat {
                rows = List.copyOf(rows == null ? List.of() : rows);
            }
        }
    }

    public SearchQueryAst parse(String query) {
        return parser.parse(query);
    }

    public QueryResult execute(String query, List<SearchEvent> corpus) {
        SearchQueryAst ast = parser.parse(query).withPage(DEFAULT_PAGE_SIZE, null);
        return localExecutor.execute(ast, corpus);
    }

    public QueryResult execute(SearchQueryAst ast, List<SearchEvent> corpus) {
        return localExecutor.execute(ast, corpus);
    }

    /** Shared deterministic freshness helper for source adapters. */
    public static Instant freshest(List<SearchEvent> events) {
        return (events == null ? List.<SearchEvent>of() : events).stream()
                .map(SearchEvent::timestamp).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }
}
