package com.socp.search.config.query;

import java.util.Objects;

/** Storage-independent commands that may follow a filter expression. */
public sealed interface PipelineCommand
        permits PipelineCommand.Top, PipelineCommand.CountBy, PipelineCommand.Head,
        PipelineCommand.Timechart, PipelineCommand.Sort, PipelineCommand.Limit {

    record Top(String field, int limit) implements PipelineCommand {
        public Top {
            requireField(field);
            if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("top limit out of range");
        }

        public Top(String field) { this(field, 10); }
    }

    record CountBy(String field) implements PipelineCommand {
        public CountBy { requireField(field); }
    }

    record Head(int limit) implements PipelineCommand {
        public Head {
            if (limit < 1 || limit > 100_000) throw new IllegalArgumentException("head limit out of range");
        }
    }

    record Limit(int limit) implements PipelineCommand {
        public Limit {
            if (limit < 1 || limit > 100_000) throw new IllegalArgumentException("limit out of range");
        }
    }

    record Timechart() implements PipelineCommand { }

    enum SortOrder { ASC, DESC }

    record Sort(String field, SortOrder order) implements PipelineCommand {
        public Sort {
            requireField(field);
            Objects.requireNonNull(order, "sort order is required");
        }

        public Sort(String field) { this(field, SortOrder.ASC); }
    }

    private static void requireField(String field) {
        if (field == null || field.isBlank() || !field.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid query field: " + field);
        }
    }
}
