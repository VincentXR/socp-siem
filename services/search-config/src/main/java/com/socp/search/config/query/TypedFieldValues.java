package com.socp.search.config.query;

import com.socp.search.config.domain.SearchEvent;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;

/** Shared typed value semantics used by local execution and OpenSearch compilation. */
final class TypedFieldValues {
    private static final FieldCatalog CATALOG = FieldCatalog.standard();

    private TypedFieldValues() {
    }

    static boolean matches(FilterExpression.Comparison comparison, SearchEvent event) {
        if (event == null) return false;
        FieldDescriptor descriptor = CATALOG.resolve(comparison.field());
        String actual = event.get(comparison.field());
        if (actual == null) return comparison.operator() == FilterExpression.Operator.NE;
        try {
            int compared = compare(descriptor, actual, comparison.value());
            return switch (comparison.operator()) {
                case EQ -> compared == 0;
                case NE -> compared != 0;
                case CONTAINS -> actual.toLowerCase(Locale.ROOT)
                        .contains(comparison.value().toLowerCase(Locale.ROOT));
                case GE -> compared >= 0;
                case GT -> compared > 0;
                case LE -> compared <= 0;
                case LT -> compared < 0;
            };
        } catch (RuntimeException invalidStoredValue) {
            return comparison.operator() == FilterExpression.Operator.NE;
        }
    }

    static Comparator<SearchEvent> comparator(String field) {
        FieldDescriptor descriptor = CATALOG.resolve(field);
        return (left, right) -> compareNullable(descriptor,
                left == null ? null : left.get(field), right == null ? null : right.get(field));
    }

    static String normalizedLiteral(FieldDescriptor descriptor, String value) {
        if (value == null) return null;
        if (descriptor.type() == FieldDescriptor.Type.SEVERITY) return value.toUpperCase(Locale.ROOT);
        return descriptor.caseInsensitive() ? value.toLowerCase(Locale.ROOT) : value;
    }

    static String aggregationKey(FieldDescriptor descriptor, String value) {
        return normalizedLiteral(descriptor, value);
    }

    private static int compareNullable(FieldDescriptor descriptor, String left, String right) {
        if (left == null) return right == null ? 0 : 1;
        if (right == null) return -1;
        try {
            return compare(descriptor, left, right);
        } catch (RuntimeException invalidStoredValue) {
            return 1;
        }
    }

    private static int compare(FieldDescriptor descriptor, String left, String right) {
        return switch (descriptor.type()) {
            case DATE -> Instant.parse(left).compareTo(Instant.parse(right));
            case INTEGER -> Long.compare(Long.parseLong(left), Long.parseLong(right));
            case IP -> compareIp(left, right);
            case SEVERITY -> Integer.compare(severity(left), severity(right));
            default -> descriptor.caseInsensitive()
                    ? left.compareToIgnoreCase(right) : left.compareTo(right);
        };
    }

    private static int compareIp(String left, String right) {
        try {
            return compareBytes(InetAddress.getByName(left).getAddress(),
                    InetAddress.getByName(right).getAddress());
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid IP value", failure);
        }
    }

    private static int compareBytes(byte[] left, byte[] right) {
        int length = Integer.compare(left.length, right.length);
        if (length != 0) return length;
        for (int i = 0; i < left.length; i++) {
            int compared = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static int severity(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "INFO" -> 1;
            case "LOW" -> 2;
            case "MEDIUM" -> 3;
            case "HIGH" -> 4;
            case "CRITICAL" -> 5;
            default -> throw new IllegalArgumentException("invalid severity");
        };
    }
}
