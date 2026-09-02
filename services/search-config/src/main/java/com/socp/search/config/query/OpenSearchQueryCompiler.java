package com.socp.search.config.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.socp.platform.tenant.context.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compiles the storage-independent query AST to a tenant-scoped OpenSearch DSL body. */
public final class OpenSearchQueryCompiler {
    private static final int MAX_TIMELINE_BUCKETS = 180;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SEVERITIES = List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final QuerySemanticAnalyzer SEMANTIC_ANALYZER = QuerySemanticAnalyzer.standard();
    private static final FieldCatalog FIELD_CATALOG = FieldCatalog.standard();

    public ObjectNode compile(SearchQueryAst ast, String tenantId) {
        return compile(ast, tenantId, ast == null ? 200 : ast.pageSize());
    }

    public ObjectNode compile(SearchQueryAst ast, String tenantId, int requestedSize) {
        return compile(ast, tenantId, requestedSize, false);
    }

    /**
     * Compiles a page query and optionally adds a bounded daily histogram. The
     * histogram is deliberately opt-in because it scans all matching documents,
     * while ordinary cursor pages only need the requested hits.
     */
    public ObjectNode compile(SearchQueryAst ast, String tenantId, int requestedSize,
                              boolean includeTimeline) {
        if (ast == null) throw new IllegalArgumentException("query AST is required");
        if (!TenantContext.isValid(tenantId)) throw new IllegalArgumentException("valid tenant is required");
        SEMANTIC_ANALYZER.analyze(ast);
        int size = Math.max(1, Math.min(QuerySemanticAnalyzer.MAX_RESULT_LIMIT + 1, requestedSize));
        for (PipelineCommand command : ast.pipeline()) {
            if (command instanceof PipelineCommand.Head head) size = Math.min(size, head.limit());
            else if (command instanceof PipelineCommand.Limit limit) size = Math.min(size, limit.limit());
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("size", size);
        root.put("track_total_hits", true);
        writeSort(root, ast);

        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        // The versioned envelope owns tenant identity.  Keep the legacy
        // fields.tenant_id branch for documents written before schema 1.0,
        // but never let a user-provided filter replace this mandatory scope.
        filters.add(tenantFilter(tenantId));
        bool.set("must", filter(ast.filter()));
        addAggregations(root, ast, includeTimeline);

        if (ast.cursor() != null && !ast.cursor().isBlank()) {
            QueryCursorCodec.Cursor cursor = QueryCursorCodec.decode(ast.cursor(), ast);
            ArrayNode after = root.putArray("search_after");
            for (int index = 0; index < cursor.sortValues().size(); index++) {
                Object value = cursor.sortValues().get(index);
                // A cursor issued by an older node contains the logical event
                // id, while the hardened physical sort uses tenant|eventId.
                // Translate it at the boundary so rolling upgrades do not
                // repeat the first page or silently skip rows.
                if (index == 1 && QueryCursorCodec.LEGACY_SORT_SPEC.equals(cursor.sortSpec())) {
                    value = tenantId + "|" + value;
                }
                after.addPOJO(value);
            }
        }
        return root;
    }

    private static ObjectNode tenantFilter(String tenantId) {
        ObjectNode scope = MAPPER.createObjectNode();
        ObjectNode bool = scope.putObject("bool");
        ArrayNode should = bool.putArray("should");
        should.addObject().putObject("term").put("tenantId", tenantId);
        should.addObject().putObject("term").put("fields.tenant_id.keyword", tenantId);
        bool.put("minimum_should_match", 1);
        return scope;
    }

    private static void writeSort(ObjectNode root, SearchQueryAst ast) {
        ArrayNode sort = root.putArray("sort");
        PipelineCommand.Sort explicit = ast.pipeline().stream()
                .filter(PipelineCommand.Sort.class::isInstance)
                .map(PipelineCommand.Sort.class::cast)
                .reduce((first, second) -> second).orElse(null);
        if (explicit == null) {
            sort.addObject().putObject("timestamp")
                    .put("order", "desc")
                    .put("unmapped_type", "date");
            // eventId was mapped as text in early indices and as keyword in
            // later indices.  Sorting that field across the wildcard index
            // therefore makes OpenSearch reject an otherwise valid query.
            // The writer owns a tenant-scoped stable document _id, which is
            // always sortable and remains deterministic across both schemas.
            sort.addObject().putObject("_id").put("order", "asc");
        } else {
            ObjectNode field = sort.addObject().putObject(fieldPath(explicit.field(), true));
            field.put("order", explicit.order() == PipelineCommand.SortOrder.DESC ? "desc" : "asc");
            sort.addObject().putObject("_id").put("order", "asc");
        }
    }

    private static void addAggregations(ObjectNode root, SearchQueryAst ast, boolean includeTimeline) {
        ObjectNode aggs = null;
        if (includeTimeline) {
            aggs = root.putObject("aggs");
            ObjectNode timeline = aggs.putObject("timeline");
            timeline.putObject("date_histogram")
                    .put("field", "timestamp")
                    .put("calendar_interval", "day")
                    .put("min_doc_count", 1)
                    .put("format", "yyyy-MM-dd");
            ObjectNode sort = timeline.putObject("aggs")
                    .putObject("keep_recent")
                    .putObject("bucket_sort");
            sort.putArray("sort").addObject().putObject("_key").put("order", "desc");
            // Request one sentinel bucket so the reader can tell whether the
            // bounded chart omitted older days and expose that fact to clients.
            sort.put("size", MAX_TIMELINE_BUCKETS + 1);
        }
        for (PipelineCommand command : ast.pipeline()) {
            if (command instanceof PipelineCommand.Top top) {
                if (aggs == null) aggs = root.putObject("aggs");
                aggs.putObject("top").putObject("terms")
                        .put("field", fieldPath(top.field(), true))
                        .put("size", top.limit())
                        .putObject("order").put("_count", "desc");
            } else if (command instanceof PipelineCommand.CountBy countBy) {
                if (aggs == null) aggs = root.putObject("aggs");
                aggs.putObject("count_by").putObject("terms")
                        .put("field", fieldPath(countBy.field(), true))
                        .put("size", QuerySemanticAnalyzer.MAX_AGGREGATION_BUCKETS)
                        .putObject("order").put("_count", "desc");
            } else if (command instanceof PipelineCommand.Timechart) {
                if (aggs == null) aggs = root.putObject("aggs");
                aggs.putObject("timechart").putObject("date_histogram")
                        .put("field", "timestamp")
                        .put("calendar_interval", "day")
                        .put("min_doc_count", 1)
                        .put("format", "yyyy-MM-dd");
            }
        }
    }

    private static ObjectNode filter(FilterExpression expression) {
        if (expression == null || expression instanceof FilterExpression.MatchAll) {
            ObjectNode root = MAPPER.createObjectNode();
            root.putObject("match_all");
            return root;
        }
        if (expression instanceof FilterExpression.Comparison comparison) return comparison(comparison);
        if (expression instanceof FilterExpression.And and) {
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode bool = root.putObject("bool");
            ArrayNode must = bool.putArray("must");
            and.terms().forEach(term -> must.add(filter(term)));
            return root;
        }
        FilterExpression.Or or = (FilterExpression.Or) expression;
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        or.terms().forEach(term -> should.add(filter(term)));
        bool.put("minimum_should_match", 1);
        return root;
    }

    private static ObjectNode comparison(FilterExpression.Comparison comparison) {
        FieldDescriptor descriptor = FIELD_CATALOG.resolve(comparison.field());
        boolean exact = comparison.operator() == FilterExpression.Operator.EQ
                || comparison.operator() == FilterExpression.Operator.NE
                || comparison.operator() == FilterExpression.Operator.CONTAINS;
        String field = exact ? descriptor.exactPath() : descriptor.searchPath();
        String value = TypedFieldValues.normalizedLiteral(descriptor, comparison.value());
        return switch (comparison.operator()) {
            case EQ -> term(field, value, descriptor.caseInsensitive());
            case NE -> {
                ObjectNode root = MAPPER.createObjectNode();
                ObjectNode bool = root.putObject("bool");
                bool.putArray("must_not").add(term(field, value, descriptor.caseInsensitive()));
                yield root;
            }
            case CONTAINS -> {
                ObjectNode root = MAPPER.createObjectNode();
                ObjectNode wildcard = root.putObject("wildcard").putObject(field);
                wildcard.put("value", "*" + escapeWildcard(value) + "*");
                wildcard.put("case_insensitive", descriptor.caseInsensitive());
                yield root;
            }
            case GE, GT, LE, LT -> {
                if ("severity".equals(comparison.field())) yield severityRange(comparison);
                ObjectNode root = MAPPER.createObjectNode();
                root.putObject("range").putObject(field)
                        .set(rangeOperator(comparison.operator()), MAPPER.valueToTree(numericOrString(value)));
                yield root;
            }
        };
    }

    private static ObjectNode term(String field, String value, boolean caseInsensitive) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode term = root.putObject("term").putObject(field);
        term.put("value", value);
        if (caseInsensitive) term.put("case_insensitive", true);
        return root;
    }

    private static String escapeWildcard(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    private static ObjectNode severityRange(FilterExpression.Comparison comparison) {
        int boundary = SEVERITIES.indexOf(comparison.value().toUpperCase(Locale.ROOT));
        if (boundary < 0) {
            ObjectNode root = MAPPER.createObjectNode();
            root.putObject("match_none");
            return root;
        }
        List<String> allowed = new ArrayList<>();
        for (int i = 0; i < SEVERITIES.size(); i++) {
            boolean include = switch (comparison.operator()) {
                case GE -> i >= boundary;
                case GT -> i > boundary;
                case LE -> i <= boundary;
                case LT -> i < boundary;
                default -> false;
            };
            if (include) allowed.add(SEVERITIES.get(i));
        }
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode values = root.putObject("terms").putArray("severity");
        allowed.forEach(values::add);
        return root;
    }

    private static String rangeOperator(FilterExpression.Operator operator) {
        return switch (operator) {
            case GE -> "gte";
            case GT -> "gt";
            case LE -> "lte";
            case LT -> "lt";
            default -> throw new IllegalArgumentException("not a range operator");
        };
    }

    private static Object numericOrString(String value) {
        try {
            if (value.contains(".")) return Double.parseDouble(value);
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            try { return Instant.parse(value).toString(); }
            catch (Exception ignoredAgain) { return value; }
        }
    }

    /** Resolves user-visible SPL field names to the explicit event mapping. */
    public static String fieldPath(String field, boolean exact) {
        FieldDescriptor descriptor = FIELD_CATALOG.resolve(field);
        return exact ? descriptor.exactPath() : descriptor.searchPath();
    }
}
