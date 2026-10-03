package com.socp.alert.domain;

import com.socp.platform.error.exception.ApiException;
import java.time.Instant;

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
        boolean ascending,
        String owner, String entity, Instant from, Instant to, String technique, String severityGroup,
        String assignee, boolean inclusiveTo) {
    public AlarmQuery(Severity severity, String rule, String status, String text, SortField sort, boolean ascending) {
        this(severity, rule, status, text, sort, ascending, null, null, null, null, null, null, null, false);
    }

    /** Investigation drilldowns preserve the half-open [from, to) evidence window. */
    public AlarmQuery withInvestigation(String owner, String entity, Instant from, Instant to, String technique, String severityGroup) {
        if (severityGroup != null && !severityGroup.equals("high"))
            throw ApiException.badRequest("severityGroup must be high");
        if (severityGroup != null && severity != null)
            throw ApiException.badRequest("Use severity or severityGroup, not both");
        if (technique != null && !technique.matches("T[0-9]{4}(?:\\.[0-9]{3})?"))
            throw ApiException.badRequest("invalid technique");
        if (from != null && to != null && !from.isBefore(to))
            throw ApiException.badRequest("from must be before to");
        return new AlarmQuery(severity, rule, status, text, sort, ascending, normalizedAssignee(owner), entity,
                from, to, technique, severityGroup, assignee, false);
    }

    /** User-entered absolute ranges include both endpoints, including exact instants. */
    public AlarmQuery withScope(String assignee, Instant start, Instant end) {
        if (start != null && end != null && start.isAfter(end))
            throw ApiException.badRequest("from must not be after to");
        return new AlarmQuery(severity, rule, status, text, sort, ascending, owner, entity,
                start, end, technique, severityGroup, normalizedAssignee(assignee), true);
    }

    public AlarmQuery withAssignee(String assignee) {
        return new AlarmQuery(severity, rule, status, text, sort, ascending, owner, entity,
                from, to, technique, severityGroup, normalizedAssignee(assignee), inclusiveTo);
    }

    private static String normalizedAssignee(String assignee) {
        if (assignee != null && assignee.length() > 128)
            throw ApiException.badRequest("assignee exceeds 128 characters");
        return assignee == null || assignee.isBlank() ? null : assignee.trim();
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
