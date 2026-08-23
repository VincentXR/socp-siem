package com.socp.report.web.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Daily report data plus explicit provenance and degradation state. */
public record ReportSummary(
        String date,
        int total,
        Map<String, Integer> bySeverity,
        List<RuleCount> byRule,
        String source,
        boolean degraded,
        Instant freshness,
        String degradationReason
) {
    /** Keeps existing in-process callers source-compatible while adding response metadata. */
    public ReportSummary(String date, int total, Map<String, Integer> bySeverity, List<RuleCount> byRule) {
        this(date, total, bySeverity, byRule, "unspecified", false, null, null);
    }

    public record RuleCount(String rule, int count) {
    }
}
