package com.socp.search.config.service;

import com.socp.search.config.domain.ParseFormat;

import java.util.List;

/** Trusted source configuration resolved before parsing one ingest record. */
public record IngestSourceContext(
        String collectorId,
        String sourceId,
        ParseFormat format,
        List<String> parseRuleIds,
        boolean resolved
) {

    public IngestSourceContext {
        format = format == null ? ParseFormat.AUTO : format;
        parseRuleIds = parseRuleIds == null ? List.of() : List.copyOf(parseRuleIds);
    }

    public static IngestSourceContext unresolved(String collectorId, ParseFormat format) {
        return new IngestSourceContext(collectorId, null, format, List.of(), false);
    }

    public boolean hasExplicitRules() {
        return !parseRuleIds.isEmpty();
    }
}
