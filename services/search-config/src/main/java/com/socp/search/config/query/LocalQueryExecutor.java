package com.socp.search.config.query;

import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.service.SplEngine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Executes the same AST used by the OpenSearch compiler against the bounded local cache. */
public final class LocalQueryExecutor {
    private static final QuerySemanticAnalyzer SEMANTIC_ANALYZER = QuerySemanticAnalyzer.standard();
    private static final FieldCatalog FIELD_CATALOG = FieldCatalog.standard();

    public SplEngine.QueryResult execute(SearchQueryAst ast, List<SearchEvent> corpus) {
        long started = System.nanoTime();
        SEMANTIC_ANALYZER.analyze(ast);
        List<SearchEvent> matched = (corpus == null ? List.<SearchEvent>of() : corpus).stream()
                .filter(Objects::nonNull)
                .filter(ast.filter()::matches)
                .sorted(Comparator.comparing(SearchEvent::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SearchEvent::eventId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int total = matched.size();
        List<SearchEvent> working = afterCursor(matched, ast);
        SplEngine.QueryResult.Stat stat = null;
        for (PipelineCommand command : ast.pipeline()) {
            if (command instanceof PipelineCommand.Head head) working = working.stream().limit(head.limit()).toList();
            else if (command instanceof PipelineCommand.Limit limit) working = working.stream().limit(limit.limit()).toList();
            else if (command instanceof PipelineCommand.Sort sort) {
                Comparator<SearchEvent> comparator = TypedFieldValues.comparator(sort.field());
                if (sort.order() == PipelineCommand.SortOrder.DESC) comparator = comparator.reversed();
                working = working.stream().sorted(comparator.thenComparing(
                        SearchEvent::eventId, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            } else if (command instanceof PipelineCommand.Top top) {
                stat = counts(working, "top", top.field(), top.limit());
            } else if (command instanceof PipelineCommand.CountBy countBy) {
                stat = counts(working, "count", countBy.field(),
                        QuerySemanticAnalyzer.MAX_AGGREGATION_BUCKETS);
            } else if (command instanceof PipelineCommand.Timechart) {
                stat = timechart(working);
            }
        }
        boolean hasMore = working.size() > ast.pageSize();
        List<SearchEvent> page = working.stream().limit(ast.pageSize()).toList();
        String nextCursor = hasMore && !page.isEmpty()
                ? QueryCursorCodec.encode(ast, page.getLast()) : null;
        return new SplEngine.QueryResult(total, page, stat, "local-cache", false,
                freshest(page), null, nextCursor, elapsedMs(started), null);
    }

    private static List<SearchEvent> afterCursor(List<SearchEvent> events, SearchQueryAst ast) {
        if (ast.cursor() == null || ast.cursor().isBlank()) return events;
        QueryCursorCodec.Cursor decoded = QueryCursorCodec.decode(ast.cursor(), ast);
        return events.stream().filter(event -> {
            if (event.timestamp() == null) return false;
            int time = event.timestamp().compareTo(decoded.timestamp());
            return time < 0 || (time == 0 && String.valueOf(event.eventId()).compareTo(decoded.eventId()) > 0);
        }).toList();
    }

    private static SplEngine.QueryResult.Stat counts(List<SearchEvent> events, String type, String field, int limit) {
        FieldDescriptor descriptor = FIELD_CATALOG.resolve(field);
        Map<String, Long> values = events.stream()
                .map(event -> event.get(field))
                .filter(Objects::nonNull)
                .map(value -> TypedFieldValues.aggregationKey(descriptor, value))
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
        List<Map.Entry<String, Long>> ordered = values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .toList();
        List<Map<String, Object>> rows = ordered.stream().limit(limit)
                .map(entry -> row(entry.getKey(), entry.getValue()))
                .toList();
        long sumOtherDocCount = ordered.stream().skip(limit).mapToLong(Map.Entry::getValue).sum();
        return new SplEngine.QueryResult.Stat(type, rows, sumOtherDocCount > 0, sumOtherDocCount);
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

    private static Instant freshest(List<SearchEvent> events) {
        return events.stream().map(SearchEvent::timestamp).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
