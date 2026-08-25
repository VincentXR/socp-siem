package com.socp.alert.service;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes tenant-scoped dashboard projections without loading every alarm into memory. */
@Component
public class AlarmStatisticsService {

    private final AlarmRepository repository;

    public AlarmStatisticsService(AlarmRepository repository) {
        this.repository = repository;
    }

    Map<String, Object> stats(String window) {
        String tenant = AlarmQueryService.tenant();
        Instant since = windowStart(window);

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (AlarmSeverityCount count : repository.countBySeverityForStatistics(tenant, since)) {
            String key = count.severity() == null ? "UNKNOWN" : count.severity().name();
            bySeverity.put(key, count.count());
        }

        Map<String, Long> byDay = lastSevenDays(tenant);
        Map<String, Long> byRiskLevel = riskLevels();
        for (AlarmRiskLevelCount count : repository.countByRiskLevelForStatistics(tenant, since)) {
            byRiskLevel.put(count.level(), count.count());
        }

        List<Map<String, Object>> topRules = repository
                .topRulesForStatistics(tenant, since, PageRequest.of(0, 10))
                .stream()
                .map(count -> Map.<String, Object>of(
                        "ruleId", count.ruleId() == null ? "?" : count.ruleId(),
                        "count", count.count()))
                .toList();

        Double average = repository.averageRiskForStatistics(tenant, since);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", repository.countForStatistics(tenant, since));
        result.put("bySeverity", bySeverity);
        result.put("trend7d", byDay);
        result.put("topRules", topRules);
        result.put("byRiskLevel", byRiskLevel);
        result.put("avgRisk", average == null ? 0 : Math.round(average * 10) / 10.0);
        result.put("topRisk", repository.topRiskForStatistics(tenant, since, PageRequest.of(0, 10))
                .stream().map(AlarmStatisticsService::riskView).toList());
        return result;
    }

    private Map<String, Long> lastSevenDays(String tenant) {
        Map<String, Long> days = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int offset = 6; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            days.put(day.toString(), repository
                    .countByTenantIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(tenant, start, end));
        }
        return days;
    }

    private static Instant windowStart(String window) {
        if (window == null || window.isBlank() || "all".equalsIgnoreCase(window)) return null;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if ("today".equalsIgnoreCase(window) || "1d".equalsIgnoreCase(window)) {
            return today.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if ("7d".equalsIgnoreCase(window)) {
            return today.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return null;
    }

    private static Map<String, Long> riskLevels() {
        Map<String, Long> levels = new LinkedHashMap<>();
        for (String level : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) levels.put(level, 0L);
        return levels;
    }

    private static Map<String, Object> riskView(Alarm alarm) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", alarm.getId());
        view.put("ruleName", alarm.getRuleName());
        view.put("entity", alarm.getEntity());
        view.put("severity", alarm.getSeverity() == null ? null : alarm.getSeverity().name());
        view.put("mitre", alarm.getMitre());
        view.put("riskScore", alarm.getRiskScore());
        view.put("riskLevel", alarm.getRiskLevel() == null && alarm.getRiskScore() != null
                ? com.socp.rule.score.RiskScorer.level(alarm.getRiskScore()) : alarm.getRiskLevel());
        return view;
    }
}
