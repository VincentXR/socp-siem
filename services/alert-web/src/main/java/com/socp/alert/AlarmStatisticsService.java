package com.socp.alert;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes tenant-scoped alarm dashboard projections. */
@Component
public class AlarmStatisticsService {

    private final AlarmRepository repository;

    public AlarmStatisticsService(AlarmRepository repository) {
        this.repository = repository;
    }

    Map<String, Object> stats(String window) {
        List<Alarm> alarms = repository.findByTenantId(AlarmQueryService.tenant());
        if ("7d".equalsIgnoreCase(window)) {
            var start = LocalDate.now(ZoneOffset.UTC).minusDays(6)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            alarms = alarms.stream()
                    .filter(alarm -> alarm.getOccurredAt() != null && !alarm.getOccurredAt().isBefore(start))
                    .toList();
        }
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        Map<String, Long> byRule = new LinkedHashMap<>();
        Map<String, Long> byDay = lastSevenDays();
        Map<String, Long> byRiskLevel = riskLevels();
        long riskSum = 0;
        long riskCount = 0;
        for (Alarm alarm : alarms) {
            bySeverity.merge(alarm.getSeverity() == null ? "UNKNOWN" : alarm.getSeverity().name(), 1L, Long::sum);
            byRule.merge(alarm.getRuleId() == null ? "?" : alarm.getRuleId(), 1L, Long::sum);
            if (alarm.getOccurredAt() != null) {
                String day = alarm.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
                if (byDay.containsKey(day)) byDay.merge(day, 1L, Long::sum);
            }
            if (alarm.getRiskScore() != null) {
                riskSum += alarm.getRiskScore();
                riskCount++;
                String level = alarm.getRiskLevel() == null
                        ? com.socp.rule.score.RiskScorer.level(alarm.getRiskScore()) : alarm.getRiskLevel();
                byRiskLevel.merge(level, 1L, Long::sum);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", alarms.size());
        result.put("bySeverity", bySeverity);
        result.put("trend7d", byDay);
        result.put("topRules", topRules(byRule));
        result.put("byRiskLevel", byRiskLevel);
        result.put("avgRisk", riskCount == 0 ? 0 : Math.round((double) riskSum / riskCount * 10) / 10.0);
        result.put("topRisk", topRisk(alarms));
        return result;
    }

    private static Map<String, Long> lastSevenDays() {
        Map<String, Long> days = new LinkedHashMap<>();
        for (int offset = 6; offset >= 0; offset--) {
            days.put(LocalDate.now(ZoneOffset.UTC).minusDays(offset).toString(), 0L);
        }
        return days;
    }

    private static Map<String, Long> riskLevels() {
        Map<String, Long> levels = new LinkedHashMap<>();
        for (String level : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) levels.put(level, 0L);
        return levels;
    }

    private static List<Map<String, Object>> topRules(Map<String, Long> byRule) {
        return byRule.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(10)
                .map(entry -> Map.<String, Object>of("ruleId", entry.getKey(), "count", entry.getValue()))
                .toList();
    }

    private static List<Map<String, Object>> topRisk(List<Alarm> alarms) {
        return alarms.stream()
                .filter(alarm -> alarm.getRiskScore() != null)
                .sorted((left, right) -> Integer.compare(right.getRiskScore(), left.getRiskScore()))
                .limit(10)
                .map(AlarmStatisticsService::riskView)
                .toList();
    }

    private static Map<String, Object> riskView(Alarm alarm) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", alarm.getId());
        view.put("ruleName", alarm.getRuleName());
        view.put("entity", alarm.getEntity());
        view.put("severity", alarm.getSeverity() == null ? null : alarm.getSeverity().name());
        view.put("mitre", alarm.getMitre());
        view.put("riskScore", alarm.getRiskScore());
        view.put("riskLevel", alarm.getRiskLevel());
        return view;
    }
}
