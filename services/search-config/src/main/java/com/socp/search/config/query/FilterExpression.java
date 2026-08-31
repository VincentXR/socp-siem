package com.socp.search.config.query;

import com.socp.search.config.domain.SearchEvent;

import java.util.List;
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
            return TypedFieldValues.matches(this, event);
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
