package com.socp.search.config.query;

import com.socp.search.config.domain.SearchEvent;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The storage-independent filter portion of the supported SPL grammar. */
public sealed interface FilterExpression
        permits FilterExpression.MatchAll, FilterExpression.Comparison,
        FilterExpression.And, FilterExpression.Or {

    boolean matches(SearchEvent event);

    record MatchAll() implements FilterExpression {
        public static final MatchAll INSTANCE = new MatchAll();

        @Override
        public boolean matches(SearchEvent event) {
            return true;
        }
    }

    enum Operator {
        EQ, NE, CONTAINS, GE, GT, LE, LT
    }

    record Comparison(String field, Operator operator, String value, int position)
            implements FilterExpression {
        public Comparison {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("filter field is required");
            }
            Objects.requireNonNull(operator, "filter operator is required");
            Objects.requireNonNull(value, "filter value is required");
            field = field.trim();
        }

        public Comparison(String field, Operator operator, String value) {
            this(field, operator, value, 0);
        }

        @Override
        public boolean matches(SearchEvent event) {
            if (event == null) return false;
            String actual = event.get(field);
            if (actual == null) return operator == Operator.NE;
            String expected = value;
            return switch (operator) {
                case EQ -> actual.equalsIgnoreCase(expected);
                case NE -> !actual.equalsIgnoreCase(expected);
                case CONTAINS -> actual.toLowerCase(Locale.ROOT)
                        .contains(expected.toLowerCase(Locale.ROOT));
                case GE, GT, LE, LT -> compare(actual, expected, operator);
            };
        }

        private static boolean compare(String actual, String expected, Operator operator) {
            Integer actualSeverity = severity(actual);
            Integer expectedSeverity = severity(expected);
            if (actualSeverity != null && expectedSeverity != null) {
                return compareNumbers(actualSeverity, expectedSeverity, operator);
            }
            try {
                return compareNumbers(Double.parseDouble(actual), Double.parseDouble(expected), operator);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        private static boolean compareNumbers(double actual, double expected, Operator operator) {
            return switch (operator) {
                case GE -> actual >= expected;
                case GT -> actual > expected;
                case LE -> actual <= expected;
                case LT -> actual < expected;
                default -> false;
            };
        }

        private static Integer severity(String value) {
            return switch (value.toUpperCase(Locale.ROOT)) {
                case "INFO" -> 1;
                case "LOW" -> 2;
                case "MEDIUM" -> 3;
                case "HIGH" -> 4;
                case "CRITICAL" -> 5;
                default -> null;
            };
        }
    }

    record And(List<FilterExpression> terms) implements FilterExpression {
        public And {
            terms = List.copyOf(terms == null ? List.of() : terms);
        }

        @Override
        public boolean matches(SearchEvent event) {
            return terms.stream().allMatch(term -> term.matches(event));
        }
    }

    record Or(List<FilterExpression> terms) implements FilterExpression {
        public Or {
            terms = List.copyOf(terms == null ? List.of() : terms);
        }

        @Override
        public boolean matches(SearchEvent event) {
            return terms.stream().anyMatch(term -> term.matches(event));
        }
    }
}
