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
        String degradationReason
) {
    public ReportTrend(List<String> days, List<Integer> counts) {
        this(days, counts, "unspecified", false, null, null);
    }
}
