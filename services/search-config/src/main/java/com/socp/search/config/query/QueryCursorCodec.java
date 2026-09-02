package com.socp.search.config.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.socp.search.config.domain.SearchEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Opaque, integrity-checked cursor bound to the fixed sort contract and query fingerprint. */
public final class QueryCursorCodec {
    /** Physical OpenSearch sort contract used by cursor pages. */
    public static final String DEFAULT_SORT_SPEC = "timestamp:desc,_id:asc";
    /** Cursors issued before mixed-index sorting was hardened. */
    public static final String LEGACY_SORT_SPEC = "timestamp:desc,eventId:asc";
    private static final String DOMAIN = "socp-search-cursor-v1\n";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QueryCursorCodec() {
    }

    public static String encode(SearchQueryAst ast, SearchEvent event) {
        if (event == null || event.timestamp() == null || event.eventId() == null) return null;
        // Local execution sorts by the logical event id.  Keep that cursor
        // explicitly on the legacy contract; the OpenSearch adapter can
        // translate it to the tenant-scoped physical _id when needed.
        return encode(ast, List.of(event.timestamp().toString(), event.eventId()), LEGACY_SORT_SPEC);
    }

    public static String encode(SearchQueryAst ast, List<?> sortValues) {
        return encode(ast, sortValues, DEFAULT_SORT_SPEC);
    }

    private static String encode(SearchQueryAst ast, List<?> sortValues, String sortSpec) {
        if (ast == null || sortValues == null || sortValues.size() != 2) return null;
        if (!DEFAULT_SORT_SPEC.equals(sortSpec) && !LEGACY_SORT_SPEC.equals(sortSpec)) return null;
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("version", 1);
            payload.put("query", fingerprint(ast));
            payload.put("sortSpec", sortSpec);
            ArrayNode values = payload.putArray("sort");
            sortValues.forEach(value -> values.addPOJO(value));
            byte[] bytes = MAPPER.writeValueAsBytes(payload);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return encoded + "." + digest(bytes);
        } catch (Exception failure) {
            throw new IllegalStateException("unable to encode search cursor", failure);
        }
    }

    public static Cursor decode(String token, SearchQueryAst ast) {
        try {
            if (token == null || ast == null) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            if (!MessageDigest.isEqual(digest(payload).getBytes(StandardCharsets.US_ASCII),
                    parts[1].getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException();
            }
            JsonNode root = MAPPER.readTree(payload);
            String sortSpec = root.path("sortSpec").asText();
            if (root.path("version").asInt() != 1
                    || (!DEFAULT_SORT_SPEC.equals(sortSpec) && !LEGACY_SORT_SPEC.equals(sortSpec))
                    || !fingerprint(ast).equals(root.path("query").asText())) {
                throw new IllegalArgumentException();
            }
            JsonNode sort = root.path("sort");
            if (!sort.isArray() || sort.size() != 2) throw new IllegalArgumentException();
            List<Object> values = new ArrayList<>(2);
            for (JsonNode value : sort) {
                if (value.isIntegralNumber()) values.add(value.longValue());
                else if (value.isFloatingPointNumber()) values.add(value.doubleValue());
                else if (value.isTextual()) values.add(value.textValue());
                else throw new IllegalArgumentException();
            }
            Cursor cursor = new Cursor(List.copyOf(values), sortSpec, root.path("query").asText());
            cursor.timestamp();
            if (cursor.eventId().isBlank()) throw new IllegalArgumentException();
            return cursor;
        } catch (Exception failure) {
            throw new SplParseException("invalid search cursor", 0, failure);
        }
    }

    public static String fingerprint(SearchQueryAst ast) {
        StringBuilder canonical = new StringBuilder();
        appendFilter(canonical, ast.filter());
        canonical.append('|');
        ast.pipeline().forEach(command -> canonical.append(command).append(';'));
        return digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendFilter(StringBuilder out, FilterExpression expression) {
        if (expression == null || expression instanceof FilterExpression.MatchAll) {
            out.append('*');
        } else if (expression instanceof FilterExpression.Comparison comparison) {
            out.append('(').append(comparison.field()).append(':').append(comparison.operator())
                    .append(':').append(comparison.value().length()).append(':')
                    .append(comparison.value()).append(')');
        } else {
            List<FilterExpression> terms;
            if (expression instanceof FilterExpression.And and) {
                out.append("AND[");
                terms = and.terms();
            } else {
                out.append("OR[");
                terms = ((FilterExpression.Or) expression).terms();
            }
            terms.forEach(term -> appendFilter(out, term));
            out.append(']');
        }
    }

    private static String digest(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    public record Cursor(List<Object> sortValues, String sortSpec, String queryFingerprint) {
        public Cursor {
            sortValues = List.copyOf(sortValues);
        }

        public Instant timestamp() {
            Object value = sortValues.getFirst();
            return value instanceof Number number
                    ? Instant.ofEpochMilli(number.longValue()) : Instant.parse(String.valueOf(value));
        }

        /** Raw second sort value, suitable for OpenSearch search_after. */
        public String tieBreaker() {
            return String.valueOf(sortValues.get(1));
        }

        /**
         * Logical event id used by the local executor.  OpenSearch uses the
         * writer's tenant-scoped document id (tenant|eventId) as the physical
         * tie-breaker; strip only that writer-owned prefix when present.
         */
        public String eventId() {
            String value = tieBreaker();
            if (LEGACY_SORT_SPEC.equals(sortSpec)) return value;
            int separator = value.indexOf('|');
            return separator >= 0 ? value.substring(separator + 1) : value;
        }
    }
}
