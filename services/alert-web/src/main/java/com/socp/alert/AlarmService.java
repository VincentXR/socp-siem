package com.socp.alert;

import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SoarClient;
import com.socp.platform.client.ThreatClient;
import com.socp.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    private final AlarmRepository repo;
    private final CkReporter ckReporter;
    private final ThreatClient threatClient;
    private final NotifyClient notifyClient;
    private final IncidentClient incidentClient;
    private final SoarClient soarClient;

    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern DOMAIN = Pattern.compile("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b");

    public AlarmService(AlarmRepository repo, CkReporter ckReporter,
                        ThreatClient threatClient, NotifyClient notifyClient,
                        IncidentClient incidentClient, SoarClient soarClient) {
        this.repo = repo;
        this.ckReporter = ckReporter;
        this.threatClient = threatClient;
        this.notifyClient = notifyClient;
        this.incidentClient = incidentClient;
        this.soarClient = soarClient;
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

    /**
     * 告警落库后的异步富化与扇出（虚拟线程执行，不阻塞写入路径）。
     *
     * <p>这里是 best-effort：任一下游不可用都不影响告警本身。但 <b>不等于可以吞异常</b>——
     * 每个下游调用的失败都由 {@code socp-client} 统一记 WARN + 指标，
     * 本方法只兜住「自己代码抛出的意外」，并且同样打日志，绝不 {@code catch (Exception ignored)}。
     */
    private void enrichAndDispatch(Alarm a) {
        try {
            enrichWithThreatIntel(a);
        } catch (Exception e) {
            log.warn("告警情报富化异常 alarmId={} entity={} error={}", a.getId(), a.getEntity(), summary(e));
        }
        String alarmJson;
        try {
            alarmJson = alarmJson(a);
        } catch (Exception e) {
            log.warn("告警序列化失败，联动扇出取消 alarmId={} error={}", a.getId(), summary(e));
            return;
        }
        // 联动通知 / 案件 / SOAR 自动编排：三条互不阻塞，任一失败不影响其余
        dispatch("notify-web", a, () -> notifyClient.notifyAlert(alarmJson));
        dispatch("incident-web", a, () -> incidentClient.createFromAlarm(alarmJson));
        dispatch("soar-web", a, () -> soarClient.evaluate(alarmJson));
    }

    /** 威胁情报富化：命中 IOC 后二次修正风险分。 */
    private void enrichWithThreatIntel(Alarm a) {
        List<String> candidates = new ArrayList<>();
        if (a.getEntity() != null && !a.getEntity().isBlank()) candidates.add(a.getEntity());
        if (a.getMessage() != null) {
            Matcher im = IP.matcher(a.getMessage());
            while (im.find()) candidates.add(im.group());
            Matcher dm = DOMAIN.matcher(a.getMessage().toLowerCase());
            while (dm.find()) candidates.add(dm.group());
        }
        if (candidates.isEmpty()) return;

        ServiceCall call = threatClient.matchIocs(toJsonArray(candidates));
        if (!call.ok()) {
            // 客户端已记录 target/url/status，这里补业务上下文：哪条告警没富化成功
            log.warn("告警情报富化跳过（threat-web 不可用）alarmId={} entity={} 原因={}",
                    a.getId(), a.getEntity(), call.failureReason());
            return;
        }
        String hits = parseHits(call.body());
        if (hits == null) return;
        a.setTiHits(hits);
        int hitCount = countHits(hits);
        var s = computeRisk(a, hitCount);
        a.setRiskScore(s.score());
        a.setRiskLevel(s.level());
        repo.save(a);
    }

    /** 统一扇出：吞掉异常但绝不吞掉日志。 */
    private void dispatch(String downstream, Alarm a, java.util.function.Supplier<ServiceCall> action) {
        try {
            ServiceCall call = action.get();
            if (!call.ok()) {
                log.warn("告警联动失败 downstream={} alarmId={} ruleId={} entity={} 原因={}",
                        downstream, a.getId(), a.getRuleId(), a.getEntity(), call.failureReason());
            }
        } catch (Exception e) {
            log.warn("告警联动异常 downstream={} alarmId={} error={}", downstream, a.getId(), summary(e));
        }
    }

    private static String summary(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
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
        } catch (Exception e) {
            // 统计失败不影响评分主流程，但要留痕（否则风险分为什么偏低会查不出来）
            log.debug("近 1 小时同实体告警计数失败，风险分按 recent=0 计算 entity={} error={}",
                    a.getEntity(), summary(e));
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

    /** 解析 threat-web 的匹配响应体，取出 hits 并转成数组形式供前端展示。 */
    private static String parseHits(String body) {
        if (body == null || body.isBlank()) return null;
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
        // 租户隔离：无上下文时按 default（机机约定），绝不 findAll 跨租户
        String tenant = TenantContext.get() == null ? "default" : TenantContext.get();
        return repo.query(tenant, severity, rule, q);
    }

    public Alarm get(String id) {
        // 租户隔离：只能读自己租户的告警
        String tenant = TenantContext.get() == null ? "default" : TenantContext.get();
        return repo.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> com.socp.platform.error.ApiException.notFound("告警不存在: " + id));
    }

    /** 聚合统计：级别分布 / 近 7 天趋势 / 规则 Top（按当前租户隔离）。 */
    public Map<String, Object> stats() {
        String tenant = TenantContext.get() == null ? "default" : TenantContext.get();
        List<Alarm> all = repo.findByTenantId(tenant);
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
