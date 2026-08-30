package com.socp.search.config.query;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validates field types and the deliberately bounded SPL pipeline contract. */
public final class QuerySemanticAnalyzer {
    public static final int MAX_RESULT_LIMIT = 5_000;
    public static final int MAX_AGGREGATION_BUCKETS = 1_000;
    private static final Set<String> SEVERITIES =
            Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final FieldCatalog catalog;

    public QuerySemanticAnalyzer(FieldCatalog catalog) {
        this.catalog = catalog == null ? FieldCatalog.standard() : catalog;
    }

    public static QuerySemanticAnalyzer standard() {
        return new QuerySemanticAnalyzer(FieldCatalog.standard());
    }

    public SearchQueryAst analyze(SearchQueryAst ast) {
        if (ast == null) throw new SplSemanticException("query AST is required", 0);
        if (ast.pageSize() > MAX_RESULT_LIMIT) {
            throw new SplSemanticException("page size must not exceed " + MAX_RESULT_LIMIT, 0);
        }
        validateFilter(ast.filter());
        validatePipeline(ast.pipeline(), ast.cursor());
        return ast;
    }

    private void validateFilter(FilterExpression expression) {
        if (expression == null || expression instanceof FilterExpression.MatchAll) return;
        if (expression instanceof FilterExpression.Comparison comparison) {
            validateComparison(comparison);
            return;
        }
        List<FilterExpression> terms = expression instanceof FilterExpression.And and
                ? and.terms() : ((FilterExpression.Or) expression).terms();
        terms.forEach(this::validateFilter);
    }

    private void validateComparison(FilterExpression.Comparison comparison) {
        FieldDescriptor field = catalog.resolve(comparison.field());
        switch (comparison.operator()) {
            case CONTAINS -> {
                if (!field.containsAllowed()) {
                    fail("field '" + field.name() + "' does not support contains", comparison.position());
                }
            }
            case GE, GT, LE, LT -> {
                if (!field.rangeAllowed()) {
                    fail("field '" + field.name() + "' does not support range comparisons",
                            comparison.position());
                }
                validateTypedLiteral(field, comparison.value(), comparison.position());
            }
            case EQ, NE -> validateTypedLiteral(field, comparison.value(), comparison.position());
        }
    }

    private void validatePipeline(List<PipelineCommand> pipeline, String cursor) {
        long aggregations = pipeline.stream().filter(QuerySemanticAnalyzer::isAggregation).count();
        long sorts = pipeline.stream().filter(PipelineCommand.Sort.class::isInstance).count();
        long limits = pipeline.stream().filter(command -> command instanceof PipelineCommand.Head
                || command instanceof PipelineCommand.Limit).count();
        if (aggregations > 1) fail("only one terminal aggregation is allowed", 0);
        if (sorts > 1) fail("only one sort command is allowed", 0);
        if (limits > 1) fail("only one head or limit command is allowed", 0);
        if (aggregations == 1 && limits > 0) {
            fail("head or limit cannot be combined with a terminal aggregation", 0);
        }
        if (aggregations == 1 && !isAggregation(pipeline.getLast())) {
            fail("an aggregation must be the final pipeline command", 0);
        }
        if (cursor != null && !cursor.isBlank() && (aggregations > 0 || sorts > 0)) {
            fail("cursor paging does not support aggregation or explicit sort", 0);
        }

        for (PipelineCommand command : pipeline) {
            if (command instanceof PipelineCommand.Top top) {
                requireAggregatable(top.field());
                if (top.limit() > MAX_AGGREGATION_BUCKETS) {
                    fail("top limit must not exceed " + MAX_AGGREGATION_BUCKETS, 0);
                }
            } else if (command instanceof PipelineCommand.CountBy countBy) {
                requireAggregatable(countBy.field());
            } else if (command instanceof PipelineCommand.Sort sort) {
                FieldDescriptor field = catalog.resolve(sort.field());
                if (!field.sortable()) fail("field '" + field.name() + "' is not sortable", 0);
            } else if (command instanceof PipelineCommand.Head head
                    && head.limit() > MAX_RESULT_LIMIT) {
                fail("head limit must not exceed " + MAX_RESULT_LIMIT, 0);
            } else if (command instanceof PipelineCommand.Limit limit
                    && limit.limit() > MAX_RESULT_LIMIT) {
                fail("limit must not exceed " + MAX_RESULT_LIMIT, 0);
            }
        }
    }

    private void requireAggregatable(String name) {
        FieldDescriptor field = catalog.resolve(name);
        if (!field.aggregatable()) fail("field '" + field.name() + "' is not aggregatable", 0);
    }

    private static boolean isAggregation(PipelineCommand command) {
        return command instanceof PipelineCommand.Top || command instanceof PipelineCommand.CountBy
                || command instanceof PipelineCommand.Timechart;
    }

    private static void validateTypedLiteral(FieldDescriptor field, String value, int position) {
        try {
            switch (field.type()) {
                case DATE -> Instant.parse(value);
                case INTEGER -> Long.parseLong(value);
                case IP -> parseIpLiteral(value);
                case SEVERITY -> {
                    if (!SEVERITIES.contains(value.toUpperCase(Locale.ROOT))) {
                        throw new IllegalArgumentException();
                    }
                }
                default -> { }
            }
        } catch (Exception invalid) {
            fail("invalid " + field.type().name().toLowerCase(Locale.ROOT)
                    + " value '" + value + "' for field '" + field.name() + "'", position);
        }
    }

    private static void parseIpLiteral(String value) throws Exception {
        if (value == null || (!value.contains(".") && !value.contains(":"))) {
            throw new IllegalArgumentException();
        }
        InetAddress.getByName(value);
    }

    private static void fail(String message, int position) {
        throw new SplSemanticException(message, position);
    }
}
