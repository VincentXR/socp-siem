package com.socp.alert;

import com.socp.platform.tenant.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Tenant-scoped alarm retrieval, sorting, and database pagination. */
@Component
public class AlarmQueryService {

    private final AlarmRepository repository;

    public AlarmQueryService(AlarmRepository repository) {
        this.repository = repository;
    }

    List<Alarm> query(Severity severity, String rule, String status, String text,
                      String sort, String order) {
        List<Alarm> alarms = new ArrayList<>(repository.query(tenant(), severity, rule, status, text));
        boolean descending = "descending".equalsIgnoreCase(order) || "desc".equalsIgnoreCase(order);
        Comparator<Alarm> comparator = comparatorFor(sort, descending);
        if (descending) comparator = comparator.reversed();
        alarms.sort(comparator.thenComparing(Alarm::getId, Comparator.nullsLast(String::compareTo)));
        return alarms;
    }

    Page<Alarm> pageByTimestamp(String sort, String order, int page, int size) {
        boolean ascending = "ascending".equalsIgnoreCase(order) || "asc".equalsIgnoreCase(order);
        var pageable = PageRequest.of(Math.max(0, page - 1), size);
        if ("alertCreatedAt".equals(sort)) {
            return ascending
                    ? repository.pageByAlertCreatedAtAsc(tenant(), pageable)
                    : repository.pageByAlertCreatedAtDesc(tenant(), pageable);
        }
        return ascending
                ? repository.pageByOccurredAtAsc(tenant(), pageable)
                : repository.pageByOccurredAtDesc(tenant(), pageable);
    }

    Alarm get(String id) {
        return repository.findByTenantIdAndId(tenant(), id)
                .orElseThrow(() -> com.socp.platform.error.ApiException.notFound("Alarm does not exist: " + id));
    }

    private static Comparator<Alarm> comparatorFor(String sort, boolean descending) {
        Comparator<Instant> instantOrder = descending
                ? Comparator.nullsFirst(Instant::compareTo)
                : Comparator.nullsLast(Instant::compareTo);
        Comparator<String> stringOrder = descending
                ? Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)
                : Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<Integer> integerOrder = descending
                ? Comparator.nullsFirst(Integer::compareTo)
                : Comparator.nullsLast(Integer::compareTo);
        return switch (sort == null ? "occurredAt" : sort) {
            case "severity" -> Comparator.comparingInt(alarm -> severityRank(alarm.getSeverity()));
            case "ruleName" -> Comparator.comparing(Alarm::getRuleName, stringOrder);
            case "entity" -> Comparator.comparing(Alarm::getEntity, stringOrder);
            case "status" -> Comparator.comparing(Alarm::getStatus, stringOrder);
            case "riskScore" -> Comparator.comparing(Alarm::getRiskScore, integerOrder);
            case "alertCreatedAt" -> Comparator.comparing(Alarm::getAlertCreatedAt, instantOrder);
            default -> Comparator.comparing(Alarm::getOccurredAt, instantOrder);
        };
    }

    private static int severityRank(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 5;
            case HIGH -> 4;
            case MEDIUM -> 3;
            case LOW -> 2;
            case INFO -> 1;
            case null -> 0;
        };
    }

    static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}
