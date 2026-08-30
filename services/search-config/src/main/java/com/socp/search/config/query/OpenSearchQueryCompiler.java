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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SEVERITIES = List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");

    public ObjectNode compile(SearchQueryAst ast, String tenantId) {
        return compile(ast, tenantId, ast == null ? 200 : ast.pageSize());
    }

    public ObjectNode compile(SearchQueryAst ast, String tenantId, int requestedSize) {
        if (ast == null) throw new IllegalArgumentException("query AST is required");
        if (!TenantContext.isValid(tenantId)) throw new IllegalArgumentException("valid tenant is required");
        int size = Math.max(1, Math.min(100_000, requestedSize));
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
        addAggregations(root, ast);

        if (ast.cursor() != null && !ast.cursor().isBlank()) {
            LocalQueryExecutor.Cursor cursor = LocalQueryExecutor.decodeCursor(ast.cursor());
            ArrayNode after = root.putArray("search_after");
            after.add(cursor.timestamp().toString());
            after.add(tenantId + "|" + cursor.eventId());
        }
        return root;
    }

    private static ObjectNode tenantFilter(String tenantId) {
        ObjectNode scope = MAPPER.createObjectNode().putObject("bool");
        ArrayNode should = scope.putArray("should");
        should.addObject().putObject("term").put("tenantId", tenantId);
        should.addObject().putObject("term").put("fields.tenant_id.keyword", tenantId);
        scope.put("minimum_should_match", 1);
        return scope;
    }

    private static void writeSort(ObjectNode root, SearchQueryAst ast) {
        ArrayNode sort = root.putArray("sort");
        PipelineCommand.Sort explicit = ast.pipeline().stream()
                .filter(PipelineCommand.Sort.class::isInstance)
                .map(PipelineCommand.Sort.class::cast)
                .reduce((first, second) -> second).orElse(null);
        if (explicit == null) {
            sort.addObject().putObject("timestamp").put("order", "desc");
            sort.addObject().putObject("_id").put("order", "asc");
        } else {
            ObjectNode field = sort.addObject().putObject(fieldPath(explicit.field(), false));
            field.put("order", explicit.order() == PipelineCommand.SortOrder.DESC ? "desc" : "asc");
            sort.addObject().putObject("_id").put("order", "asc");
        }
    }

    private static void addAggregations(ObjectNode root, SearchQueryAst ast) {
        ObjectNode aggs = null;
        for (PipelineCommand command : ast.pipeline()) {
            if (command instanceof PipelineCommand.Top top) {
                if (aggs == null) aggs = root.putObject("aggs");
                aggs.putObject("top").putObject("terms")
                        .put("field", fieldPath(top.field(), true))
                        .put("size", top.limit())
                        .put("order", "_count");
            } else if (command instanceof PipelineCommand.CountBy countBy) {
                if (aggs == null) aggs = root.putObject("aggs");
                aggs.putObject("count_by").putObject("terms")
                        .put("field", fieldPath(countBy.field(), true))
                        .put("size", 10_000)
                        .put("order", "_count");
            } else if (command instanceof PipelineCommand.Timechart) {
                if (aggs == null) aggs = root.putObject("aggs");
                aggs.putObject("timechart").putObject("date_histogram")
                        .put("field", "timestamp")
                        .put("calendar_interval", "day")
                        .put("min_doc_count", 0)
                        .put("format", "yyyy-MM-dd");
            }
        }
    }

    private static ObjectNode filter(FilterExpression expression) {
        if (expression == null || expression instanceof FilterExpression.MatchAll) {
            return MAPPER.createObjectNode().putObject("match_all");
        }
        if (expression instanceof FilterExpression.Comparison comparison) return comparison(comparison);
        if (expression instanceof FilterExpression.And and) {
            ObjectNode bool = MAPPER.createObjectNode().putObject("bool");
            ArrayNode must = bool.putArray("must");
            and.terms().forEach(term -> must.add(filter(term)));
            return bool;
        }
        FilterExpression.Or or = (FilterExpression.Or) expression;
        ObjectNode bool = MAPPER.createObjectNode().putObject("bool");
        ArrayNode should = bool.putArray("should");
        or.terms().forEach(term -> should.add(filter(term)));
        bool.put("minimum_should_match", 1);
        return bool;
    }

    private static ObjectNode comparison(FilterExpression.Comparison comparison) {
        String field = fieldPath(comparison.field(), comparison.operator() == FilterExpression.Operator.EQ
                || comparison.operator() == FilterExpression.Operator.NE
                || comparison.operator() == FilterExpression.Operator.CONTAINS);
        String value = comparison.value();
        return switch (comparison.operator()) {
            case EQ -> {
                ObjectNode root = MAPPER.createObjectNode();
                root.putObject("term").put(field, value);
                yield root;
            }
            case NE -> {
                ObjectNode bool = MAPPER.createObjectNode().putObject("bool");
                bool.putArray("must_not").addObject().putObject("term").put(field, value);
                yield bool;
            }
            case CONTAINS -> {
                ObjectNode root = MAPPER.createObjectNode();
                root.putObject("match").put(field, value);
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
        if (field == null || !field.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid query field: " + field);
        }
        String path;
        if (field.equals("eventId") || field.equals("timestamp") || field.equals("source")
                || field.equals("host") || field.equals("severity") || field.equals("msg")
                || field.startsWith("ecs.")) path = field;
        else path = "fields." + field;
        // The canonical event mapping declares these fields as keyword values
        // directly (there is no implicit `.keyword` multi-field). Dynamic
        // fields under `fields` may still be mapped as text with a keyword
        // sub-field, hence the suffix is only added for those paths.
        if (!exact || path.equals("timestamp") || path.equals("msg")
                || path.equals("eventId") || path.equals("source")
                || path.equals("host") || path.equals("severity")
                || path.startsWith("ecs.")) return path;
        return path.endsWith(".keyword") ? path : path + ".keyword";
    }
}
