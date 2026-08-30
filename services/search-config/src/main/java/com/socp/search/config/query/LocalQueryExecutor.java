package com.socp.search.config.query;

import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.service.SplEngine;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Executes the same AST used by the OpenSearch compiler against the bounded local cache. */
public final class LocalQueryExecutor {
    private static final QuerySemanticAnalyzer SEMANTIC_ANALYZER = QuerySemanticAnalyzer.standard();

    public SplEngine.QueryResult execute(SearchQueryAst ast, List<SearchEvent> corpus) {
        SEMANTIC_ANALYZER.analyze(ast);
        long started = System.nanoTime();
        List<SearchEvent> matched = (corpus == null ? List.<SearchEvent>of() : corpus).stream()
                .filter(Objects::nonNull)
                .filter(ast.filter()::matches)
                .sorted(Comparator.comparing(SearchEvent::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SearchEvent::eventId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int total = matched.size();
        List<SearchEvent> working = afterCursor(matched, ast.cursor());
        SplEngine.QueryResult.Stat stat = null;
        for (PipelineCommand command : ast.pipeline()) {
            if (command instanceof PipelineCommand.Head head) working = working.stream().limit(head.limit()).toList();
            else if (command instanceof PipelineCommand.Limit limit) working = working.stream().limit(limit.limit()).toList();
            else if (command instanceof PipelineCommand.Sort sort) {
                Comparator<SearchEvent> comparator = Comparator.comparing(
                        event -> String.valueOf(event.get(sort.field())), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                if (sort.order() == PipelineCommand.SortOrder.DESC) comparator = comparator.reversed();
                working = working.stream().sorted(comparator.thenComparing(SearchEvent::eventId)).toList();
            } else if (command instanceof PipelineCommand.Top top) {
                stat = counts(working, "top", top.field(), top.limit());
            } else if (command instanceof PipelineCommand.CountBy countBy) {
                stat = counts(working, "count", countBy.field(), Integer.MAX_VALUE);
            } else if (command instanceof PipelineCommand.Timechart) {
                stat = timechart(working);
            }
        }
        boolean hasMore = working.size() > ast.pageSize();
        List<SearchEvent> page = working.stream().limit(ast.pageSize()).toList();
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.getLast()) : null;
        return new SplEngine.QueryResult(total, page, stat, "local-cache", false,
                freshest(page), null, nextCursor, elapsedMs(started));
    }

    private static List<SearchEvent> afterCursor(List<SearchEvent> events, String cursor) {
        if (cursor == null || cursor.isBlank()) return events;
        Cursor decoded = decodeCursor(cursor);
        return events.stream().filter(event -> {
            if (event.timestamp() == null) return false;
            int time = event.timestamp().compareTo(decoded.timestamp());
            return time < 0 || (time == 0 && String.valueOf(event.eventId()).compareTo(decoded.eventId()) < 0);
        }).toList();
    }

    private static SplEngine.QueryResult.Stat counts(List<SearchEvent> events, String type, String field, int limit) {
        Map<String, Long> values = events.stream().collect(Collectors.groupingBy(
                event -> String.valueOf(event.get(field)), LinkedHashMap::new, Collectors.counting()));
        List<Map<String, Object>> rows = values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> row(entry.getKey(), entry.getValue()))
                .toList();
        return new SplEngine.QueryResult.Stat(type, rows);
    }

    private static SplEngine.QueryResult.Stat timechart(List<SearchEvent> events) {
        Map<String, Long> values = events.stream().filter(event -> event.timestamp() != null)
                .collect(Collectors.groupingBy(event -> LocalDate.ofInstant(event.timestamp(), ZoneOffset.UTC).toString(),
                        LinkedHashMap::new, Collectors.counting()));
        List<Map<String, Object>> rows = values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> row(entry.getKey(), entry.getValue())).toList();
        return new SplEngine.QueryResult.Stat("timechart", rows);
    }

    private static Map<String, Object> row(String key, long count) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("count", count);
        return row;
    }

    public static String encodeCursor(SearchEvent event) {
        if (event == null || event.timestamp() == null || event.eventId() == null) return null;
        String raw = event.timestamp() + "|" + event.eventId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator <= 0) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(raw.substring(0, separator)), raw.substring(separator + 1));
        } catch (Exception failure) {
            throw new SplParseException("invalid search cursor", 0, failure);
        }
    }

    public record Cursor(Instant timestamp, String eventId) { }

    private static Instant freshest(List<SearchEvent> events) {
        return events.stream().map(SearchEvent::timestamp).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
