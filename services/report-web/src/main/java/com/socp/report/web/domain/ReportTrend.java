package com.socp.report.web.domain;
import java.time.Instant;
import java.util.List;

/** Seven-day trend data plus explicit provenance and degradation state. */
public record ReportTrend(
        List<String> days,
        List<Integer> counts,
        String source,
        boolean degraded,
        Instant freshness,
        String degradationReason,
        Instant generatedAt,
        String queryWindow,
        String contentVersion
) {
    public ReportTrend(List<String> days, List<Integer> counts) {
        this(days, counts, "unspecified", false, null, null, Instant.now(), "unspecified", "unknown");
    }

    public ReportTrend(List<String> days, List<Integer> counts, String source, boolean degraded,
                       Instant freshness, String degradationReason) {
        this(days, counts, source, degraded, freshness, degradationReason,
                Instant.now(), "7d", "unknown");
    }
}
