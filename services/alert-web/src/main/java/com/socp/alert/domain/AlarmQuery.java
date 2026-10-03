package com.socp.alert.domain;


/**
 * Normalized, tenant-independent alarm list criteria. Tenant ownership is supplied
 * separately by the service boundary so every repository query remains tenant-scoped.
 */
public record AlarmQuery(
        Severity severity,
        String rule,
        String status,
        String text,
        SortField sort,
        boolean ascending, String assignee, java.time.Instant from, java.time.Instant to) {

    public AlarmQuery(Severity severity, String rule, String status, String text, SortField sort, boolean ascending) {
        this(severity, rule, status, text, sort, ascending, null, null, null);
    }

    public AlarmQuery withScope(String owner, java.time.Instant start, java.time.Instant end) {
        if (owner != null && owner.length() > 128) throw com.socp.platform.error.exception.ApiException.badRequest("assignee exceeds 128 characters");
        if (start != null && end != null && start.isAfter(end)) throw com.socp.platform.error.exception.ApiException.badRequest("from must not be after to");
        return new AlarmQuery(severity, rule, status, text, sort, ascending,
                owner == null || owner.isBlank() ? null : owner.trim(), start, end);
    }

    public enum SortField {
        OCCURRED_AT,
        ALERT_CREATED_AT,
        SEVERITY,
        RULE_NAME,
        ENTITY,
        STATUS,
        RISK_SCORE
    }
}
