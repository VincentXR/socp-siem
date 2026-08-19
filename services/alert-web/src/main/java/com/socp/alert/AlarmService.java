package com.socp.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Comparator;
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
    private final OutboxRepository outboxRepo;
    private final AlarmEvidenceRepository evidenceRepo;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern DOMAIN = Pattern.compile("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b");

    public AlarmService(AlarmRepository repo, CkReporter ckReporter,
                        ThreatClient threatClient, NotifyClient notifyClient,
                        IncidentClient incidentClient, SoarClient soarClient,
                        OutboxRepository outboxRepo, AlarmEvidenceRepository evidenceRepo) {
        this.repo = repo;
        this.ckReporter = ckReporter;
        this.threatClient = threatClient;
        this.notifyClient = notifyClient;
        this.incidentClient = incidentClient;
        this.soarClient = soarClient;
        this.outboxRepo = outboxRepo;
        this.evidenceRepo = evidenceRepo;
    }

    public Alarm create(Alarm alarm) {
        return create(alarm, List.of());
    }

    @Transactional
    public Alarm create(Alarm alarm, List<AlarmEvidenceInput> evidence) {
        if (alarm.getTenantId() == null) {
            alarm.setTenantId(TenantContext.get() == null ? "default" : TenantContext.get());
        }
        if (alarm.getSourceAlertId() != null && !alarm.getSourceAlertId().isBlank()) {
            var existing = repo.findByTenantIdAndSourceAlertId(alarm.getTenantId(), alarm.getSourceAlertId());
            if (existing.isPresent()) return existing.get();
        }
        // 威胁评分：检测侧未给初评则本地算一次（此刻还没做情报富化，tiHits=0）
        if (alarm.getRiskScore() == null) {
            alarm.setRiskScore(computeRisk(alarm, 0).score());
        }
        alarm.setRiskLevel(com.socp.rule.score.RiskScorer.level(alarm.getRiskScore()));
        Alarm saved = repo.save(alarm);
        List<AlarmEvidenceInput> captured = evidence == null ? List.of() : evidence.stream()
                .filter(java.util.Objects::nonNull)
                .limit(200)
                .toList();
        if (!captured.isEmpty()) {
            String tenant = saved.getTenantId() == null ? TenantContext.get() : saved.getTenantId();
            evidenceRepo.saveAll(java.util.stream.IntStream.range(0, captured.size())
                    .mapToObj(i -> AlarmEvidence.from(saved.getId(), tenant, i, captured.get(i)))
                    .toList());
        }
        // P3 Outbox：同事务写告警事件（ALARM_CREATED），OutboxPublisher 发 Kafka socp-alarm-events，
        // 下游（CK/Incident/SOAR/Notify）由 AlarmEventConsumer 消费——失败可重放，不再跨系统双写。
        OutboxEvent oe = new OutboxEvent();
        oe.setAggregateId(saved.getId());
        oe.setEventType("ALARM_CREATED");
        oe.setPayload(alarmJson(saved, captured));
        oe.setStatus("PENDING");
        oe.setCreatedAt(java.time.Instant.now());
        outboxRepo.save(oe);
        // 异步富化（threat-web IOC 命中 → 二次修正风险分，落 t_alarm）；扇出由 Kafka 消费者负责
        Alarm finalSaved = saved;
        Thread.startVirtualThread(() -> enrichOnly(finalSaved));
        return saved;
    }

    @Transactional(readOnly = true)
    public AlarmEvidenceResponse evidence(String alarmId) {
        Alarm alarm = get(alarmId);
        String tenant = TenantContext.get() == null ? "default" : TenantContext.get();
        List<AlarmEvidenceView> items = evidenceRepo
                .findByTenantIdAndAlarmIdOrderByEvidenceOrderAscIdAsc(tenant, alarmId)
                .stream()
                .map(AlarmEvidence::view)
                .toList();
        String query = items.stream()
                .map(AlarmEvidenceView::eventId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> "eventId=" + id)
                .collect(java.util.stream.Collectors.joining(" OR "));
        return new AlarmEvidenceResponse(alarm.getId(), items.size(), !items.isEmpty(), query, items);
    }

    /**
     * 告警落库后的异步威胁情报富化（虚拟线程）：命中 IOC 修正风险分并落库。
     * 下游扇出（CK/Notify/Incident/SOAR）已在 P3 改为 Kafka 消费者（AlarmEventConsumer）驱动。
     */
    private void enrichOnly(Alarm a) {
        try {
            enrichWithThreatIntel(a);
        } catch (Exception e) {
            log.warn("告警情报富化异常 alarmId={} entity={} error={}", a.getId(), a.getEntity(), summary(e));
        }
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
        return query(severity, rule, null, q, "occurredAt", "descending");
    }

    public List<Alarm> query(Severity severity, String rule, String status, String q,
                             String sort, String order) {
        // 租户隔离：无上下文时按 default（机机约定），绝不 findAll 跨租户
        String tenant = TenantContext.get() == null ? "default" : TenantContext.get();
        List<Alarm> alarms = new ArrayList<>(repo.query(tenant, severity, rule, status, q));
        Comparator<Alarm> comparator = comparatorFor(sort);
        if ("descending".equalsIgnoreCase(order) || "desc".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }
        alarms.sort(comparator.thenComparing(Alarm::getId, Comparator.nullsLast(String::compareTo)));
        return alarms;
    }

    private static Comparator<Alarm> comparatorFor(String sort) {
        return switch (sort == null ? "occurredAt" : sort) {
            case "severity" -> Comparator.comparingInt(a -> severityRank(a.getSeverity()));
            case "ruleName" -> Comparator.comparing(Alarm::getRuleName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "entity" -> Comparator.comparing(Alarm::getEntity, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "status" -> Comparator.comparing(Alarm::getStatus, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "riskScore" -> Comparator.comparing(Alarm::getRiskScore, Comparator.nullsLast(Integer::compareTo));
            case "alertCreatedAt" -> Comparator.comparing(Alarm::getAlertCreatedAt,
                    Comparator.nullsLast(java.time.Instant::compareTo));
            default -> Comparator.comparing(Alarm::getOccurredAt, Comparator.nullsLast(java.time.Instant::compareTo));
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

    public Alarm get(String id) {
        // 租户隔离：只能读自己租户的告警
        String tenant = TenantContext.get() == null ? "default" : TenantContext.get();
        return repo.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> com.socp.platform.error.ApiException.notFound("告警不存在: " + id));
    }

    /** 聚合统计：默认返回当前租户全量；window=7d 时限定为近 7 个 UTC 自然日。 */
    public Map<String, Object> stats() {
        return stats(null);
    }

    public Map<String, Object> stats(String window) {
        String tenant = TenantContext.get() == null ? "default" : TenantContext.get();
        List<Alarm> all = repo.findByTenantId(tenant);
        if ("7d".equalsIgnoreCase(window)) {
            java.time.Instant windowStart = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                    .minusDays(6).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            all = all.stream()
                    .filter(a -> a.getOccurredAt() != null && !a.getOccurredAt().isBefore(windowStart))
                    .toList();
        }
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

    private String alarmJson(Alarm a, List<AlarmEvidenceInput> evidence) {
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
        m.put("triggerIngestedAt", a.getTriggerIngestedAt() == null ? null
                : DateTimeFormatter.ISO_INSTANT.format(a.getTriggerIngestedAt()));
        m.put("alertCreatedAt", a.getAlertCreatedAt() == null ? null
                : DateTimeFormatter.ISO_INSTANT.format(a.getAlertCreatedAt()));
        m.put("processingLatencyMs", a.getProcessingLatencyMs());
        m.put("triggerEventId", a.getTriggerEventId());
        m.put("evidence", evidence == null ? List.of() : evidence);
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
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
