package com.socp.report.web.model;

/**
 * 报表数据模型（对应前端 REPORT 看板）。
 */
public record ReportSummary(
        String date,
        int total,
        java.util.Map<String, Integer> bySeverity,
        java.util.List<RuleCount> byRule
) {
    public record RuleCount(String rule, int count) {
    }
}
