package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmQuery;
import com.socp.alert.domain.Severity;
import com.socp.alert.repository.AlarmRepository;


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

    List<Alarm> query(Severity severity, String rule, String status, String text,
                      String sort, String order) {
        return repository.list(tenant(), criteria(severity, rule, status, text, sort, order));
    }

    Page<Alarm> page(Severity severity, String rule, String status, String text,
                     String sort, String order, int page, int size) {
        return repository.page(tenant(), criteria(severity, rule, status, text, sort, order),
                PageRequest.of(Math.max(0, page - 1), size));
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
