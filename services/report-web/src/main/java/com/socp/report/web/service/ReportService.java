package com.socp.report.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.report.web.domain.ReportSummary;
import com.socp.report.web.domain.ReportTrend;
import com.socp.report.web.config.ClickHouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces reports from ClickHouse first, with an explicitly marked alert-web fallback.
 * A source failure is never represented as an all-zero report: when neither source can
 * provide data the caller receives a 503 instead.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AlertClient alertClient;
    private final ClickHouseProperties clickHouse;

    public ReportService(AlertClient alertClient) {
        this(alertClient, new ClickHouseProperties());
    }

    @Autowired
    public ReportService(AlertClient alertClient, ClickHouseProperties clickHouse) {
        this.alertClient = alertClient;
        this.clickHouse = clickHouse;
    }

    @SuppressWarnings("unchecked")
    private StatsFetch fetchStats(String window) {
        ServiceCall call = alertClient.stats(window);
        if (!call.ok()) {
            return StatsFetch.unavailable("alert-web statistics unavailable: " + call.failureReason());
        }
        String body = call.body();
        if (body == null || body.isBlank()) {
            return StatsFetch.unavailable("alert-web statistics response was empty");
        }
        try {
            Map<String, Object> envelope = MAPPER.readValue(body, Map.class);
            Object data = envelope.containsKey("data") ? envelope.get("data") : envelope;
            if (!(data instanceof Map<?, ?> map)) {
                return StatsFetch.unavailable("alert-web statistics response was invalid");
            }
            Map<String, Object> stats = (Map<String, Object>) map;
            if (!(stats.get("total") instanceof Number)) {
                return StatsFetch.unavailable("alert-web statistics response did not contain total");
            }
            return StatsFetch.available(stats);
        } catch (Exception failure) {
            log.warn("Cannot parse alert-web statistics response: {}: {}",
                    failure.getClass().getSimpleName(), failure.getMessage());
            return StatsFetch.unavailable("alert-web statistics response was invalid");
        }
    }

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();

    /** Executes a ClickHouse query and preserves the reason for a possible fallback. */
    private CkFetch ckQuery(String sql) {
        if (!clickHouse.isEnabled()) return CkFetch.unavailable("ClickHouse reporting is disabled");
        try {
            String auth = Base64.getEncoder().encodeToString(
                    (clickHouse.getUser() + ":" + clickHouse.getPassword()).getBytes(StandardCharsets.UTF_8));
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(clickHouse.getUrl()))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .header("Authorization", "Basic " + auth)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                    .build();

            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                log.debug("ClickHouse query returned HTTP {}", code);
                return CkFetch.unavailable("ClickHouse returned HTTP " + code);
            }
            String body = response.body();
            List<String> lines = new ArrayList<>();
            if (body != null) {
                for (String line : body.split("\\n")) {
                    if (!line.isBlank()) lines.add(line.trim());
                }
            }
            return CkFetch.available(lines);
        } catch (Exception failure) {
            log.debug("ClickHouse query unavailable: {}: {}",
                    failure.getClass().getSimpleName(), failure.getMessage());
            return CkFetch.unavailable("ClickHouse is unavailable");
        }
    }

    public ReportSummary dailyReport() {
        String predicate = "tenant_id = '" + sqlLiteral(tenant()) + "'";
        CkFetch severityRows = ckQuery("SELECT severity, uniqExact(alarm_id) FROM alert_agg.alarm_detail WHERE "
                + predicate + " AND ts >= today() GROUP BY severity");
        if (!severityRows.available()) {
            return fallbackDaily(severityRows.reason());
        }

        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        int total = 0;
        for (String row : severityRows.rows()) {
            String[] parts = row.split("\\t");
            if (parts.length < 2) continue;
            int count = parseCount(parts[1]);
            bySeverity.merge(parts[0], count, Integer::sum);
            total = Math.addExact(total, count);
        }

        CkFetch ruleRows = ckQuery("SELECT rule_id, rule_name, uniqExact(alarm_id) c FROM alert_agg.alarm_detail WHERE "
                + predicate + " AND ts >= today() GROUP BY rule_id, rule_name ORDER BY c DESC LIMIT 10");
        List<ReportSummary.RuleCount> byRule;
        String source = "clickhouse";
        boolean degraded = false;
        String degradationReason = null;
        if (ruleRows.available()) {
            byRule = ruleCounts(ruleRows.rows());
        } else {
            StatsFetch fallback = fetchStats("today");
            byRule = fallback.available() ? ruleCounts(fallback.stats()) : List.of();
            source = fallback.available() ? "clickhouse+alert-web" : "clickhouse";
            degraded = true;
            degradationReason = "ClickHouse top-rule query failed"
                    + (fallback.available() ? "; top rules came from alert-web" : "; top rules are unavailable");
        }
        return new ReportSummary(LocalDate.now(ZoneOffset.UTC).toString(), total, bySeverity, byRule,
                source, degraded, ckFreshness(predicate), degradationReason, Instant.now(),
                "today", contentVersion());
    }

    public ReportTrend trend7d() {
        String predicate = "tenant_id = '" + sqlLiteral(tenant()) + "'";
        CkFetch rows = ckQuery("SELECT toDate(ts) d, uniqExact(alarm_id) FROM alert_agg.alarm_detail WHERE "
                + predicate + " AND ts >= now() - INTERVAL 7 DAY GROUP BY d ORDER BY d");
        if (rows.available()) {
            Map<String, Integer> countsByDay = new LinkedHashMap<>();
            for (String row : rows.rows()) {
                String[] parts = row.split("\\t");
                if (parts.length >= 2) countsByDay.put(parts[0], parseCount(parts[1]));
            }
            return trendFromCounts(countsByDay, "clickhouse", false, ckFreshness(predicate), null);
        }

        StatsFetch fallback = fetchStats("7d");
        if (!fallback.available()) {
            throw unavailable("seven-day trend", rows.reason(), fallback.reason());
        }
        return trendFromCounts(trendCounts(fallback.stats()), "alert-web", true, null,
                "ClickHouse unavailable; result was computed by alert-web");
    }

    private ReportSummary fallbackDaily(String clickHouseReason) {
        StatsFetch fallback = fetchStats("today");
        if (!fallback.available()) {
            throw unavailable("daily report", clickHouseReason, fallback.reason());
        }
        Map<String, Object> stats = fallback.stats();
        Map<String, Integer> bySeverity = numberMap(stats.get("bySeverity"));
        int total = ((Number) stats.get("total")).intValue();
        return new ReportSummary(LocalDate.now(ZoneOffset.UTC).toString(), total, bySeverity,
                ruleCounts(stats), "alert-web", true, null,
                "ClickHouse unavailable; result was computed by alert-web", Instant.now(),
                "today", contentVersion());
    }

    private Instant ckFreshness(String predicate) {
        CkFetch freshness = ckQuery("SELECT max(toUnixTimestamp64Milli(ts)) FROM alert_agg.alarm_detail WHERE " + predicate);
        if (!freshness.available() || freshness.rows().isEmpty()) return null;
        try {
            long millis = Long.parseLong(freshness.rows().getFirst());
            return millis <= 0 ? null : Instant.ofEpochMilli(millis);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static ReportTrend trendFromCounts(Map<String, Integer> countsByDay, String source,
                                                boolean degraded, Instant freshness, String reason) {
        List<String> days = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int offset = 6; offset >= 0; offset--) {
            String day = today.minusDays(offset).toString();
            days.add(day.substring(5));
            counts.add(countsByDay.getOrDefault(day, 0));
        }
        return new ReportTrend(days, counts, source, degraded, freshness, reason, Instant.now(), "7d",
                contentVersion());
    }

    private static String contentVersion() {
        String commit = System.getProperty("socp.build.commit");
        if (commit == null || commit.isBlank()) commit = System.getenv("GITHUB_SHA");
        return commit == null || commit.isBlank() ? "unknown" : commit;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> numberMap(Object raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) return result;
        for (var entry : ((Map<String, Object>) map).entrySet()) {
            if (entry.getValue() instanceof Number count) result.put(entry.getKey(), count.intValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> trendCounts(Map<String, Object> stats) {
        return numberMap(stats.get("trend7d"));
    }

    @SuppressWarnings("unchecked")
    private static List<ReportSummary.RuleCount> ruleCounts(Map<String, Object> stats) {
        Object raw = stats.get("topRules");
        if (!(raw instanceof List<?> rows)) return List.of();
        List<ReportSummary.RuleCount> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> values)) continue;
            Object id = values.get("ruleId");
            Object count = values.get("count");
            if (count instanceof Number number) {
                result.add(new ReportSummary.RuleCount(String.valueOf(id), number.intValue()));
            }
        }
        return result;
    }

    private static List<ReportSummary.RuleCount> ruleCounts(List<String> rows) {
        List<ReportSummary.RuleCount> result = new ArrayList<>();
        for (String row : rows) {
            String[] parts = row.split("\\t");
            if (parts.length >= 3) {
                result.add(new ReportSummary.RuleCount(parts[0] + " " + parts[1], parseCount(parts[2])));
            }
        }
        return result;
    }

    private static int parseCount(String value) {
        return Math.toIntExact(Long.parseLong(value));
    }

    private static ApiException unavailable(String report, String primaryReason, String fallbackReason) {
        return ApiException.of(503, report + " is unavailable: " + primaryReason + "; " + fallbackReason);
    }

    private static String tenant() {
        return TenantContext.require();
    }

    private static String sqlLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private record CkFetch(List<String> rows, String reason) {
        static CkFetch available(List<String> rows) {
            return new CkFetch(List.copyOf(rows), null);
        }

        static CkFetch unavailable(String reason) {
            return new CkFetch(List.of(), reason);
        }

        boolean available() {
            return reason == null;
        }
    }

    private record StatsFetch(Map<String, Object> stats, String reason) {
        static StatsFetch available(Map<String, Object> stats) {
            return new StatsFetch(Map.copyOf(stats), null);
        }

        static StatsFetch unavailable(String reason) {
            return new StatsFetch(Map.of(), reason);
        }

        boolean available() {
            return reason == null;
        }
    }
}
