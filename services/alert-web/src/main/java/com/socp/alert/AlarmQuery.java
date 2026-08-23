package com.socp.alert;

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
        boolean ascending) {

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
