package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmQuery;
import com.socp.alert.domain.Severity;
import com.socp.alert.persistence.repository.AlarmRepository;


import com.socp.platform.tenant.context.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/** Tenant-scoped alarm retrieval with database-side filters, sort, and pagination. */
@Component
public class AlarmQueryService {

    private final AlarmRepository repository;

    public AlarmQueryService(AlarmRepository repository) {
        this.repository = repository;
    }

    Page<Alarm> page(Severity severity, String rule, String status, String text,
                     String sort, String order, int page, int size) {
        return repository.page(tenant(), criteria(severity, rule, status, text, sort, order),
                PageRequest.of(Math.max(0, page - 1), size));
    }

    Page<Alarm> investigationPage(Severity severity, String rule, String status, String text,
            String sort, String order, int page, int size, String owner, String entity, java.time.Instant from, java.time.Instant to, String technique, String severityGroup) {
        return investigationPage(severity, rule, status, text, sort, order, page, size, owner, entity, from, to, technique, severityGroup, null);
    }

    Page<Alarm> investigationPage(Severity severity, String rule, String status, String text,
            String sort, String order, int page, int size, String owner, String entity, java.time.Instant from,
            java.time.Instant to, String technique, String severityGroup, String assignee) {
        String resolvedOwner = "mine".equals(owner) ? com.socp.platform.tenant.context.AuthenticatedIdentityContext.current()
                .map(com.socp.platform.tenant.context.AuthenticatedIdentity::subject)
                .orElseThrow(() -> com.socp.platform.error.exception.ApiException.of(401, "Authenticated owner required")) : blankToNull(owner);
        return repository.page(tenant(), criteria(severity, rule, status, text, sort, order).withInvestigation(resolvedOwner, blankToNull(entity), from, to, technique, severityGroup).withAssignee(assignee),
                PageRequest.of(Math.max(0, page - 1), size));
    }

    long count(Severity severity, String rule, String status, String text,
               String sort, String order) {
        return repository.count(tenant(), criteria(severity, rule, status, text, sort, order));
    }

    Page<Alarm> page(Severity severity, String rule, String status, String text,
                     String sort, String order, int page, int size, String assignee,
                     java.time.Instant from, java.time.Instant to) {
        return repository.page(tenant(), criteria(severity, rule, status, text, sort, order)
                .withScope(assignee, from, to), PageRequest.of(Math.max(0, page - 1), size));
    }

    long count(Severity severity, String rule, String status, String text, String sort,
               String order, String assignee, java.time.Instant from, java.time.Instant to) {
        return repository.count(tenant(), criteria(severity, rule, status, text, sort, order)
                .withScope(assignee, from, to));
    }

    Alarm get(String id) {
        return repository.findByTenantIdAndId(tenant(), id)
                .orElseThrow(() -> com.socp.platform.error.exception.ApiException.notFound("Alarm does not exist: " + id));
    }

    private static AlarmQuery criteria(Severity severity, String rule, String status, String text,
                                       String sort, String order) {
        boolean ascending = "ascending".equalsIgnoreCase(order) || "asc".equalsIgnoreCase(order);
        return new AlarmQuery(severity, blankToNull(rule), blankToNull(status), blankToNull(text),
                sortField(sort), ascending);
    }

    private static AlarmQuery.SortField sortField(String sort) {
        return switch (sort == null ? "occurredAt" : sort) {
            case "severity" -> AlarmQuery.SortField.SEVERITY;
            case "ruleName" -> AlarmQuery.SortField.RULE_NAME;
            case "entity" -> AlarmQuery.SortField.ENTITY;
            case "status" -> AlarmQuery.SortField.STATUS;
            case "riskScore" -> AlarmQuery.SortField.RISK_SCORE;
            case "alertCreatedAt" -> AlarmQuery.SortField.ALERT_CREATED_AT;
            default -> AlarmQuery.SortField.OCCURRED_AT;
        };
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static String tenant() {
        return TenantContext.require();
    }
}
