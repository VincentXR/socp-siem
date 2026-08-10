package com.socp.report.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.AlertClient;
import com.socp.platform.client.ServiceCall;
import com.socp.report.web.model.ReportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REPORT 报表聚合服务：从 ALERT 告警聚合统计（真实数据）生成日报与趋势。
 * 2026-08-08 接线：报表聚合优先查 ClickHouse（alert_agg.alarm_detail 明细表），
 * CK 不可用时回退到 ALERT stats（原逻辑兜底）。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AlertClient alertClient;

    public ReportService(AlertClient alertClient) {
        this.alertClient = alertClient;
    }

    @Value("${socp.ck.url:http://localhost:8123}")
    private String ckUrl;

    @Value("${socp.ck.user:default}")
    private String ckUser;

    @Value("${socp.ck.password:socp}")
    private String ckPassword;

    /** 拉取 ALERT 统计；不可用或解析失败回退到空报表，避免前端报错（但一定留日志）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchStats() {
        ServiceCall call = alertClient.stats();
        if (!call.ok()) {
            log.warn("报表回退数据源不可用：alert-web 统计拉取失败，本次报表将为空 原因={}", call.failureReason());
            return Map.of();
        }
        String body = call.body();
        if (body == null || body.isBlank()) return Map.of();
        try {
            Map<String, Object> m = MAPPER.readValue(body, Map.class);
            if (m.containsKey("data")) {
                Object d = m.get("data");
                if (d instanceof Map<?, ?> dm) return (Map<String, Object>) dm;
            }
            return m;
        } catch (Exception e) {
            log.warn("alert-web 统计响应解析失败，本次报表将为空 error={}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * 执行 CK 查询，成功返回结果行（TSV 每行一个字符串）；失败返回 null（触发回退到 alert-web）。
     *
     * <p>CK 不可用是**预期内**的降级路径（演示环境常不起 ClickHouse），因此记 debug 而非 warn；
     * 真正的数据缺失会在 {@link #fetchStats()} 那一层以 warn 暴露。
     */
    private List<String> ckQuery(String sql) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(ckUrl).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            String auth = Base64.getEncoder().encodeToString(
                    (ckUser + ":" + ckPassword).getBytes(StandardCharsets.UTF_8));
            c.setRequestProperty("Authorization", "Basic " + auth);
            try (OutputStream os = c.getOutputStream()) {
                os.write(sql.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                log.debug("ClickHouse 查询非 2xx，回退 alert-web 统计 status={} url={}", code, ckUrl);
                return null;
            }
            InputStream is = c.getInputStream();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            c.disconnect();
            List<String> lines = new ArrayList<>();
            for (String line : body.split("\n")) {
                if (!line.isBlank()) lines.add(line.trim());
            }
            return lines;
        } catch (Exception e) {
            log.debug("ClickHouse 不可达，回退 alert-web 统计 url={} error={}: {}",
                    ckUrl, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    public ReportSummary dailyReport() {
        // 优先 ClickHouse（明细表实时聚合）
        List<String> sevRows = ckQuery("SELECT severity, count() FROM alert_agg.alarm_detail WHERE ts >= today() GROUP BY severity");
        if (sevRows != null) {
            Map<String, Integer> bySeverity = new LinkedHashMap<>();
            int total = 0;
            for (String row : sevRows) {
                String[] parts = row.split("\t");
                if (parts.length >= 2) {
                    int cnt = Integer.parseInt(parts[1]);
                    bySeverity.put(parts[0], bySeverity.getOrDefault(parts[0], 0) + cnt);
                    total += cnt;
                }
            }
            List<ReportSummary.RuleCount> byRule = new ArrayList<>();
            List<String> ruleRows = ckQuery("SELECT rule_id, rule_name, count() c FROM alert_agg.alarm_detail WHERE ts >= today() GROUP BY rule_id, rule_name ORDER BY c DESC LIMIT 10");
            if (ruleRows != null) {
                for (String row : ruleRows) {
                    String[] parts = row.split("\t");
                    if (parts.length >= 3) {
                        byRule.add(new ReportSummary.RuleCount(parts[0] + " " + parts[1], Integer.parseInt(parts[2])));
                    }
                }
            }
            return new ReportSummary(LocalDate.now().toString(), total, bySeverity, byRule);
        }
        // 回退：ALERT stats
        Map<String, Object> stats = fetchStats();
        int total = ((Number) stats.getOrDefault("total", 0)).intValue();
        Map<String, Object> sevRaw = (Map<String, Object>) stats.getOrDefault("bySeverity", Map.of());
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        for (var e : sevRaw.entrySet()) {
            bySeverity.put(e.getKey(), ((Number) e.getValue()).intValue());
        }
        List<ReportSummary.RuleCount> byRule = new ArrayList<>();
        Object topRaw = stats.get("topRules");
        if (topRaw instanceof List<?> top) {
            for (Object o : top) {
                if (o instanceof Map<?, ?> rm) {
                    Object ruleId = rm.get("ruleId");
                    Object countObj = rm.get("count");
                    int count = countObj instanceof Number ? ((Number) countObj).intValue() : 0;
                    byRule.add(new ReportSummary.RuleCount(String.valueOf(ruleId), count));
                }
            }
        }
        return new ReportSummary(LocalDate.now().toString(), total, bySeverity, byRule);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> trend7d() {
        // 优先 ClickHouse（按天聚合 7 天趋势）
        List<String> rows = ckQuery("SELECT toDate(ts) d, count() FROM alert_agg.alarm_detail WHERE ts >= now() - INTERVAL 7 DAY GROUP BY d ORDER BY d");
        if (rows != null && !rows.isEmpty()) {
            List<String> days = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (String row : rows) {
                String[] parts = row.split("\t");
                if (parts.length >= 2) {
                    days.add(parts[0].substring(5));
                    counts.add(Integer.parseInt(parts[1]));
                }
            }
            return Map.of("days", days, "counts", counts);
        }
        // 回退：ALERT stats
        Map<String, Object> stats = fetchStats();
        Map<String, Object> trendRaw = (Map<String, Object>) stats.getOrDefault("trend7d", Map.of());
        List<String> days = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (var e : trendRaw.entrySet()) {
            days.add(e.getKey().substring(5));
            counts.add(((Number) e.getValue()).intValue());
        }
        if (days.isEmpty()) {
            for (int i = 6; i >= 0; i--) {
                days.add(LocalDate.now().minusDays(i).toString().substring(5));
                counts.add(0);
            }
        }
        return Map.of("days", days, "counts", counts);
    }
}
