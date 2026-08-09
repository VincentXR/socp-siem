package com.socp.alert;

import com.socp.platform.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 告警业务逻辑：创建（写入 t_alarm）+ 查询（按租户/级别/规则/关键字过滤）。
 * 创建后异步做威胁情报富化（threat-web）并联动通知（notify-web）/案件（incident-web）。
 */
@Service
public class AlarmService {
    private final AlarmRepository repo;
    private final CkReporter ckReporter;

    @Value("${socp.threat.url:http://localhost:18094}")
    private String tiUrl;
    @Value("${socp.notify.url:http://localhost:18096}")
    private String notifyUrl;
    @Value("${socp.incident.url:http://localhost:18097}")
    private String caseUrl;
    @Value("${socp.soar.url:http://localhost:18083}")
    private String soarUrl;

    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern DOMAIN = Pattern.compile("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b");

    public AlarmService(AlarmRepository repo, CkReporter ckReporter) {
        this.repo = repo;
        this.ckReporter = ckReporter;
    }

    @Transactional
    public Alarm create(Alarm alarm) {
        if (alarm.getTenantId() == null) {
            alarm.setTenantId(TenantContext.get());
        }
        // 威胁评分：检测侧未给初评则本地算一次（此刻还没做情报富化，tiHits=0）
        if (alarm.getRiskScore() == null) {
            alarm.setRiskScore(computeRisk(alarm, 0).score());
        }
        alarm.setRiskLevel(com.socp.rule.score.RiskScorer.level(alarm.getRiskScore()));
        Alarm saved = repo.save(alarm);
        // 报表层：ClickHouse 异步写明细（best-effort，失败静默）
        ckReporter.reportAlarm(saved);
        // 异步富化 + 联动（best-effort，不阻塞写入路径）
        Alarm finalSaved = saved;
        Thread.startVirtualThread(() -> enrichAndDispatch(finalSaved));
        return saved;
    }

    private void enrichAndDispatch(Alarm a) {
        try {
            // 1) 威胁情报富化
            List<String> candidates = new ArrayList<>();
            if (a.getEntity() != null && !a.getEntity().isBlank()) candidates.add(a.getEntity());
            if (a.getMessage() != null) {
                Matcher im = IP.matcher(a.getMessage());
                while (im.find()) candidates.add(im.group());
                Matcher dm = DOMAIN.matcher(a.getMessage().toLowerCase());
                while (dm.find()) candidates.add(dm.group());
            }
            if (!candidates.isEmpty()) {
                String resp = com.socp.alert.util.Http.postWithBody(
                        tiUrl + "/threat-web/api/v1/iocs/match", toJsonArray(candidates), 3000);
                String hits = parseHits(resp);
                if (hits != null) {
                    a.setTiHits(hits);
                    // 情报命中后二次修正风险分：命中 IOC 是最强的加权信号之一
                    int hitCount = countHits(hits);
                    var s = computeRisk(a, hitCount);
                    a.setRiskScore(s.score());
                    a.setRiskLevel(s.level());
                    repo.save(a);
                }
            }
            // 2) 联动通知 / 案件 / SOAR 自动编排
            String alarmJson = alarmJson(a);
            com.socp.alert.util.Http.post(notifyUrl + "/notify-web/api/v1/notify/alert", alarmJson, 3000);
            com.socp.alert.util.Http.post(caseUrl + "/incident-web/api/v1/incidents/from-alarm", alarmJson, 3000);
            com.socp.alert.util.Http.post(soarUrl + "/soar-web/api/v1/playbooks/evaluate", alarmJson, 3000);
        } catch (Exception ignored) {
            // best-effort：任一外部服务不可用不影响告警落库
        }
    }

    /**
     * 计算告警威胁评分。与 DETECT 检测侧共用 {@link com.socp.rule.score.RiskScorer}，
     * 保证同一条告警在检测侧和分析侧算出的分一致。
     */
    private com.socp.rule.score.RiskScorer.Score computeRisk(Alarm a, int tiHits) {
        com.socp.rule.model.Severity sev;
        try {
            sev = a.getSeverity() == null
                    ? com.socp.rule.model.Severity.INFO
                    : com.socp.rule.model.Severity.valueOf(a.getSeverity().name());
        } catch (IllegalArgumentException e) {
            sev = com.socp.rule.model.Severity.INFO;
        }
        int recent = 0;
        try {
            if (a.getEntity() != null && !a.getEntity().isBlank()) {
                recent = (int) repo.countRecentByEntity(
                        a.getEntity(), java.time.Instant.now().minus(java.time.Duration.ofHours(1)));
            }
        } catch (Exception ignored) {
            // 统计失败不影响评分主流程
        }
        return com.socp.rule.score.RiskScorer.score(sev, a.getMitre(), tiHits, recent, 0);
    }

    /** 粗略数一下 tiHits JSON 数组里有几条命中（避免为计数再解析一次完整对象） */
    private static int countHits(String hitsJson) {
        if (hitsJson == null || hitsJson.length() < 3) return 0;
        int n = 0;
        for (int i = 0; i < hitsJson.length(); i++) {
            if (hitsJson.charAt(i) == '{') n++;
        }
        return n;
    }

    private static String parseHits(String resp) {
        if (resp == null) return null;
        int i = resp.indexOf('|');
        String body = i < 0 ? resp : resp.substring(i + 1);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, Map.class);
            Object hits = m.get("hits");
            if (hits == null) return null;
            // hits 是 Map<value,Ioc>，序列化为数组便于前端展示
            if (hits instanceof Map) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object v : ((Map<?, ?>) hits).values()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(v));
                }
                return sb.append("]").toString();
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(hits);
        } catch (Exception e) {
            return null;
        }
    }

    public List<Alarm> query(Severity severity, String rule, String q) {
        String tenant = TenantContext.get();
        if (tenant == null) {
            return repo.findAll();
        }
        return repo.query(tenant, severity, rule, q);
    }

    public Alarm get(String id) {
        return repo.findById(id).orElseThrow(() -> com.socp.platform.error.ApiException.notFound("告警不存在: " + id));
    }

    /** 聚合统计：级别分布 / 近 7 天趋势 / 规则 Top。 */
    public Map<String, Object> stats() {
        List<Alarm> all = repo.findAll();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        Map<String, Long> byRule = new LinkedHashMap<>();
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (int d = 6; d >= 0; d--) {
            byDay.put(java.time.LocalDate.now().minusDays(d).toString(), 0L);
        }
        for (Alarm a : all) {
            String sev = a.getSeverity() == null ? "UNKNOWN" : a.getSeverity().name();
            bySeverity.merge(sev, 1L, Long::sum);
            byRule.merge(a.getRuleId() == null ? "?" : a.getRuleId(), 1L, Long::sum);
            if (a.getOccurredAt() != null) {
                String day = a.getOccurredAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
                // 只统计最近 7 天（byDay 已预填 7 个日期）；旧告警不入趋势，避免混入历史日期
                if (byDay.containsKey(day)) byDay.merge(day, 1L, Long::sum);
            }
        }
        List<Map<String, Object>> topRules = byRule.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ruleId", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                }).toList();
        // 风险分布 + 均值 + 最该处置的 Top 告警（态势大屏用）
        Map<String, Long> byRiskLevel = new LinkedHashMap<>();
        for (String l : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) byRiskLevel.put(l, 0L);
        long riskSum = 0;
        long riskCount = 0;
        for (Alarm a : all) {
            if (a.getRiskScore() == null) continue;
            riskSum += a.getRiskScore();
            riskCount++;
            String lvl = a.getRiskLevel() == null
                    ? com.socp.rule.score.RiskScorer.level(a.getRiskScore()) : a.getRiskLevel();
            byRiskLevel.merge(lvl, 1L, Long::sum);
        }
        List<Map<String, Object>> topRisk = all.stream()
                .filter(a -> a.getRiskScore() != null)
                .sorted((x, y) -> Integer.compare(y.getRiskScore(), x.getRiskScore()))
                .limit(10)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("ruleName", a.getRuleName());
                    m.put("entity", a.getEntity());
                    m.put("severity", a.getSeverity() == null ? null : a.getSeverity().name());
                    m.put("mitre", a.getMitre());
                    m.put("riskScore", a.getRiskScore());
                    m.put("riskLevel", a.getRiskLevel());
                    return m;
                }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("bySeverity", bySeverity);
        out.put("trend7d", byDay);
        out.put("topRules", topRules);
        out.put("byRiskLevel", byRiskLevel);
        out.put("avgRisk", riskCount == 0 ? 0 : Math.round((double) riskSum / riskCount * 10) / 10.0);
        out.put("topRisk", topRisk);
        return out;
    }

    private String alarmJson(Alarm a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("ruleId", a.getRuleId());
        m.put("ruleName", a.getRuleName());
        m.put("severity", a.getSeverity() == null ? null : a.getSeverity().name());
        m.put("message", a.getMessage());
        m.put("entity", a.getEntity());
        m.put("mitre", a.getMitre());
        m.put("riskScore", a.getRiskScore());
        m.put("riskLevel", a.getRiskLevel());
        m.put("occurredAt", a.getOccurredAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(a.getOccurredAt()));
        return toJson(new ArrayList<>(m.entrySet()));
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("]").toString();
    }

    private static String toJson(List<Map.Entry<String, Object>> entries) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : entries) {
            if (e.getValue() == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(e.getKey()).append("\":\"")
              .append(String.valueOf(e.getValue()).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("}").toString();
    }
}
