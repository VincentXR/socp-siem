package com.socp.search.config.query;

import java.util.List;

/** Parsed SPL query. It deliberately contains no OpenSearch or JPA types. */
public record SearchQueryAst(
        FilterExpression filter,
        List<PipelineCommand> pipeline,
        int pageSize,
        String cursor
) {
    public SearchQueryAst {
        filter = filter == null ? FilterExpression.MatchAll.INSTANCE : filter;
        pipeline = List.copyOf(pipeline == null ? List.of() : pipeline);
        if (pageSize < 1 || pageSize > 100_000) {
            throw new IllegalArgumentException("page size out of range");
        }
    }

    public SearchQueryAst(FilterExpression filter, List<PipelineCommand> pipeline) {
        this(filter, pipeline, 200, null);
    }

    public SearchQueryAst withPage(int size, String nextCursor) {
        return new SearchQueryAst(filter, pipeline, size, nextCursor);
    }

    public boolean hasAggregation() {
        return pipeline.stream().anyMatch(command -> command instanceof PipelineCommand.Top
                || command instanceof PipelineCommand.CountBy
                || command instanceof PipelineCommand.Timechart);
    }
}
