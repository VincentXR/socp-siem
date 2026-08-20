package com.socp.search.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.ReferenceSet;
import com.socp.search.config.search.SearchEvent;
import com.socp.search.config.search.IngestionCommitService;
import com.socp.search.config.store.ParseRuleStore;
import com.socp.search.config.store.ReferenceSetStore;
import com.socp.platform.client.DetectClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.tenant.TenantContext;
import com.socp.rule.partition.DetectionRoutingKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集管线：把 Vector/采集器投递的 NDJSON 行做解析、归一化、查找表富化，
 * 写入检索事件库（真实数据），并 best-effort 转发归一化事件给 DETECT 规则引擎做检测。
 * 这是"采集 → 解析 → 检测 → 告警"链路的第一、二环（与之前只计数的桩不同）。
 */
@Service
public class IngestPipeline {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 攒批转发阈值：攒够一批才向 DETECT 发一次 HTTP，避免逐条转发的 RTT 成为吞吐瓶颈 */
    private static final int BATCH = 200;
    private final ParsePreviewService preview;
    private final ParseRuleStore parseRules;
    private final ReferenceSetStore refSets;
    private final IngestionCommitService ingestionCommitService;
    private final IngestTaskMonitor monitor;
    private final DetectClient detectClient;
    private final com.socp.search.config.parser.ParserRegistry parserRegistry;

    // ---- 可观测指标（2026-08-11，暴露真实 ingest 压力而非固定 queueLoad=0.0） ----
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final io.micrometer.core.instrument.Counter acceptedCounter;
    private final io.micrometer.core.instrument.Counter skippedCounter;
    private final io.micrometer.core.instrument.Counter forwardedCounter;
    private final java.util.concurrent.atomic.AtomicReference<Double> epsRef = new java.util.concurrent.atomic.AtomicReference<>(0.0);

    /**
     * HTTP 直连 DETECT 转发开关（2026-08-11 架构收敛）：
     * 正式模式只有一条 Detection 主链 {@code search-config → Kafka socp-events → detect-web}，
     * HTTP 直连不再是 ingestion 主路径（避免同一事件双写进 Detection）。
     * 仅当 {@code SOCP_INGEST_FORWARD_HTTP=true} 时开启——留给本地调试/压测，不用于生产。
     */
    @org.springframework.beans.factory.annotation.Value("${socp.ingest.forward-http:false}")
    private boolean forwardHttp;

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    public IngestPipeline(ParsePreviewService preview, ParseRuleStore parseRules,
                          ReferenceSetStore refSets, IngestionCommitService ingestionCommitService,
                          IngestTaskMonitor monitor,
                          DetectClient detectClient,
                          com.socp.search.config.parser.ParserRegistry parserRegistry,
                          io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.preview = preview;
        this.parseRules = parseRules;
        this.refSets = refSets;
        this.ingestionCommitService = ingestionCommitService;
        this.monitor = monitor;
        this.detectClient = detectClient;
        this.parserRegistry = parserRegistry;
        this.meterRegistry = meterRegistry;
        this.acceptedCounter = io.micrometer.core.instrument.Counter.builder("socp_ingest_events_total")
                .tag("outcome", "accepted").register(meterRegistry);
        this.skippedCounter = io.micrometer.core.instrument.Counter.builder("socp_ingest_events_total")
                .tag("outcome", "skipped").register(meterRegistry);
        this.forwardedCounter = io.micrometer.core.instrument.Counter.builder("socp_ingest_events_total")
                .tag("outcome", "forwarded").register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("socp_ingest_eps", epsRef, java.util.concurrent.atomic.AtomicReference::get)
                .register(meterRegistry);
    }

    /** 处理一批 NDJSON。返回 accepted/skipped/forwarded 统计，并按采集器记录运行指标。 */
    public Map<String, Object> process(String body) {
        return process(body, null);
    }

    /**
     * @param defaultCollector 调用方显式指定的采集器标识；为空时按每行的 {@code collector}
     *                         字段归属（Vector 渲染配置会带上这个标签）
     */
    public Map<String, Object> process(String body, String defaultCollector) {
        int accepted = 0, skipped = 0, forwarded = 0;
        if (body == null || body.isBlank()) {
            return Map.of("accepted", 0, "skipped", 0, "forwarded", 0);
        }
        // 按采集器分别累计：[accepted, skipped, forwarded, bytes]
        Map<String, long[]> perCollector = new LinkedHashMap<>();
        List<Map<String, Object>> pending = new ArrayList<>();
        var lines = body.lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            String t = line.trim();
            if (t.isEmpty()) continue;
            long bytes = t.length();
            try {
                Map<String, Object> norm = normalize(t, defaultCollector);
                if (norm == null) {
                    skipped++;
                    bump(perCollector, defaultCollector, 0, 1, 0, bytes);
                    continue;
                }
                accepted++;
                // 攒批：检索库与 DETECT 转发都在 flush 时批量落（减少逐条 H2 insert 与 HTTP 往返）
                pending.add(norm);
                bump(perCollector, collectorOf(norm, defaultCollector), 1, 0, 0, bytes);
                if (pending.size() >= BATCH) {
                    forwarded += flush(pending, perCollector, defaultCollector);
                }
            } catch (Exception e) {
                skipped++;
                bump(perCollector, defaultCollector, 0, 1, 0, bytes);
            }
        }
        forwarded += flush(pending, perCollector, defaultCollector);
        perCollector.forEach((k, v) ->
                monitor.record(k, (int) v[0], (int) v[1], (int) v[2], v[3]));
        // 真实指标：累加计数器 + 更新 EPS（parse failure rate 由 skipped/total 得出）
        acceptedCounter.increment(accepted);
        skippedCounter.increment(skipped);
        forwardedCounter.increment(forwarded);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("skipped", skipped);
        out.put("forwarded", forwarded);
        // search-config 同步解析、无内部队列——queueLoad 不适用（0 表示无排队）。
        // 真实压力指标：parseFailureRate（解析失败占比）、eps1m（近 1 分钟吞吐，来自 monitor）
        out.put("queueLoad", 0.0);
        double total = accepted + skipped;
        out.put("parseFailureRate", total > 0 ? Math.round(skipped * 1000.0 / total) / 10.0 : 0.0);
        if (defaultCollector != null) {
            Object eps = monitor.runtime(defaultCollector, true).get("eps1m");
            if (eps instanceof Number n) {
                epsRef.set(n.doubleValue());
                out.put("eps1m", n.doubleValue());
            }
        }
        out.put("collectors", perCollector.keySet());
        return out;
    }

    private static void bump(Map<String, long[]> m, String key, long a, long s, long f, long bytes) {
        long[] v = m.computeIfAbsent(key == null || key.isBlank() ? "unknown" : key, k -> new long[4]);
        v[0] += a;
        v[1] += s;
        v[2] += f;
        v[3] += bytes;
    }

    @SuppressWarnings("unchecked")
    private static String collectorOf(Map<String, Object> norm, String fallback) {
        Object f = norm.get("fields");
        if (f instanceof Map<?, ?> map) {
            Object c = ((Map<String, Object>) map).get("collector");
            if (c != null && !String.valueOf(c).isBlank()) return String.valueOf(c);
        }
        return fallback;
    }

    /** 解析+归一化+富化单行。返回含 source/host/severity/msg/fields/timestamp 的 Map。 */
    private Map<String, Object> normalize(String line, String collectorHint) {
        // 1) Parser Pipeline：Source Router（vendor/特征）选解析器 → canonical ECS 字段
        Map<String, String> canonical = parserRegistry.parse(line, collectorHint);
        // canonical（带 . 的 ECS 键）拆到独立 ecs 命名空间，避免与 fields 的 text 键
        // （source/host/severity...）在 OpenSearch mapping 上冲突
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, String> ecs = new LinkedHashMap<>();
        for (var en : canonical.entrySet()) {
            if (en.getKey().contains(".")) ecs.put(en.getKey(), en.getValue());
            else fields.put(en.getKey(), en.getValue());
        }
        String rawLog = canonical.getOrDefault(com.socp.search.config.parser.CanonicalEvent.EVENT_MESSAGE, line);

        // 2) 解析规则兜底抽取：仅当 canonical 未结构化（只有 message）时尝试，
        //    命中第一条即停——不再每条日志全量扫描全部规则
        if (fields.size() <= 1 && ecs.size() <= 1) {
            for (var rule : parseRules.list()) {
                Map<String, Object> pr = preview.preview(rule.id(), null, null, rawLog);
                if (Boolean.TRUE.equals(pr.get("matched"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> extracted = (Map<String, Object>) pr.get("fields");
                    if (extracted != null) fields.putAll(extracted);
                    break;
                }
            }
        }
        // 3) canonical → 兼容键桥接（Detection 规则 keyField 用 src_ip/user 等顶层）
        bridge(fields, canonical);

        // 4) 查找表富化：标注命中核心资产/关键人员/封禁名单
        enrich(fields);

        String source = pick(fields, "source", "type", "app", "vendor");
        String category = canonical.get(com.socp.search.config.parser.CanonicalEvent.EVENT_CATEGORY);
        if ((source.isBlank() || "sshd".equalsIgnoreCase(source))
                && "authentication".equalsIgnoreCase(category)) {
            source = "auth";
        }
        String host = pick(fields, "host", "hostname", "device", com.socp.search.config.parser.CanonicalEvent.HOST_NAME);
        String severity = pick(fields, "severity", "level", com.socp.search.config.parser.CanonicalEvent.EVENT_SEVERITY);
        if (severity.isBlank()) severity = "INFO";
        String msg = rawLog;
        String ts = pick(fields, "timestamp", "@timestamp", "time");
        String eventId = firstNonBlank(
                canonical.get("eventId"),
                canonical.get("event.id"),
                canonical.get("id"),
                fields.get("eventId"),
                fields.get("event_id"));
        if (eventId == null) eventId = java.util.UUID.randomUUID().toString();
        fields.putIfAbsent("tenant_id", TenantContext.get() == null ? "default" : TenantContext.get());
        Map<String, String> routingFields = stringFields(fields);
        fields.put("detection_routing_field", DetectionRoutingKey.field(source, host, routingFields));
        fields.put("detection_routing_value", DetectionRoutingKey.value(source, host, routingFields));
        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("eventId", eventId);
        norm.put("source", source.isBlank() ? "unknown" : source);
        norm.put("host", host.isBlank() ? "unknown" : host);
        norm.put("severity", severity.toUpperCase());
        norm.put("msg", msg);
        norm.put("timestamp", parseTs(ts));
        norm.put("fields", fields);
        if (!ecs.isEmpty()) norm.put("ecs", ecs);
        return norm;
    }

    /** canonical（ECS 键）→ Detection 规则习惯的兼容键（src_ip/user/host/msg...），缺失才补。 */
    private static void bridge(Map<String, Object> fields, Map<String, String> c) {
        putIfAbsent(fields, "src_ip", c.get(com.socp.search.config.parser.CanonicalEvent.SOURCE_IP));
        putIfAbsent(fields, "dst_ip", c.get(com.socp.search.config.parser.CanonicalEvent.DESTINATION_IP));
        putIfAbsent(fields, "src_port", c.get(com.socp.search.config.parser.CanonicalEvent.SOURCE_PORT));
        putIfAbsent(fields, "dst_port", c.get(com.socp.search.config.parser.CanonicalEvent.DESTINATION_PORT));
        putIfAbsent(fields, "user", c.get(com.socp.search.config.parser.CanonicalEvent.USER_NAME));
        putIfAbsent(fields, "host", c.get(com.socp.search.config.parser.CanonicalEvent.HOST_NAME));
        putIfAbsent(fields, "msg", c.get(com.socp.search.config.parser.CanonicalEvent.EVENT_MESSAGE));
        putIfAbsent(fields, "severity", c.get(com.socp.search.config.parser.CanonicalEvent.EVENT_SEVERITY));
        putIfAbsent(fields, "action", c.get(com.socp.search.config.parser.CanonicalEvent.EVENT_ACTION));
        putIfAbsent(fields, "category", c.get(com.socp.search.config.parser.CanonicalEvent.EVENT_CATEGORY));
        putIfAbsent(fields, "process", c.get(com.socp.search.config.parser.CanonicalEvent.PROCESS_NAME));
        putIfAbsent(fields, "pid", c.get(com.socp.search.config.parser.CanonicalEvent.PROCESS_PID));
    }

    private static void putIfAbsent(Map<String, Object> m, String key, Object v) {
        if (v != null && m.get(key) == null) {
            m.put(key, v);
        }
    }

    private static Map<String, String> stringFields(Map<String, Object> fields) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            if (entry.getValue() != null) out.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return out;
    }

    private void enrich(Map<String, Object> fields) {
        for (String key : new String[]{"src_ip", "ip", "user", "host", "dst_ip"}) {
            Object v = fields.get(key);
            if (v == null) continue;
            List<String> hits = refSets.matchedSets(String.valueOf(v));
            if (!hits.isEmpty()) {
                fields.put("watchlist", String.join(",", hits));
                if (hits.stream().anyMatch(h -> h.contains("封禁"))) fields.put("blocked", "true");
                if (hits.stream().anyMatch(h -> h.contains("核心资产"))) fields.put("asset_critical", "true");
                if (hits.stream().anyMatch(h -> h.contains("关键人员"))) fields.put("user_vip", "true");
            }
        }
    }

    private SearchEvent toEvent(Map<String, Object> norm) {
        @SuppressWarnings("unchecked")
        Map<String, Object> f = (Map<String, Object>) norm.get("fields");
        @SuppressWarnings("unchecked")
        Map<String, String> fs = new LinkedHashMap<>();
        if (f != null) for (var e : f.entrySet()) fs.put(e.getKey(), String.valueOf(e.getValue()));
        @SuppressWarnings("unchecked")
        Map<String, Object> eo = (Map<String, Object>) norm.get("ecs");
        @SuppressWarnings("unchecked")
        Map<String, String> ecs = new LinkedHashMap<>();
        if (eo != null) for (var e : eo.entrySet()) ecs.put(e.getKey(), String.valueOf(e.getValue()));
        return new SearchEvent(
                String.valueOf(norm.get("eventId")),
                Instant.parse(String.valueOf(norm.get("timestamp"))),
                String.valueOf(norm.get("source")),
                String.valueOf(norm.get("host")),
                String.valueOf(norm.get("severity")),
                String.valueOf(norm.get("msg")),
                Map.copyOf(fs),
                Map.copyOf(ecs));
    }

    /**
     * 批量转发一批归一化事件给 DETECT（NDJSON 一次 POST），按响应中的 accepted 统计成功条数，
     * 并把成功数按序记入对应采集器的 forwarded 指标。失败整批记 0（上游 Vector 靠 503 语义重试）。
     */
    private int flush(List<Map<String, Object>> batch, Map<String, long[]> perCollector, String defaultCollector) {
        if (batch.isEmpty()) return 0;
        // Canonical event and Kafka publication intent commit atomically. A broker
        // outage leaves durable PENDING rows instead of creating a send-loss window.
        List<SearchEvent> evs = batch.stream().map(this::toEvent).toList();
        ingestionCommitService.commit(evs);
        // HTTP 直连 DETECT 仅调试用（默认关闭）：同一事件不再双写进 Detection
        int fl = 0;
        if (forwardHttp) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> m : batch) sb.append(toJson(m)).append('\n');
            ServiceCall call = detectClient.ingestBulk(sb.toString());
            if (call.ok() && call.body() != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = MAPPER.readValue(call.body(), Map.class);
                    Object a = r.get("accepted");
                    fl = a instanceof Number ? ((Number) a).intValue() : 0;
                } catch (Exception ignored) {
                    fl = 0;
                }
            } else if (!call.ok()) {
                log.warn("归一化事件转发 DETECT 失败 原因={}", call.failureReason());
            }
        }
        int n = Math.min(fl, batch.size());
        for (int i = 0; i < n; i++) {
            bump(perCollector, collectorOf(batch.get(i), defaultCollector), 0, 0, 1, 0);
        }
        batch.clear();
        return fl;
    }

    private static String pick(Map<String, Object> fields, String... keys) {
        for (String k : keys) {
            if (fields.get(k) != null) return String.valueOf(fields.get(k));
        }
        return "";
    }

    private static String firstNonBlank(Object... values) {
        for (Object candidate : values) {
            if (candidate == null) continue;
            String value = String.valueOf(candidate);
            if (!value.isBlank() && !"null".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static String parseTs(String s) {
        if (s == null || s.isBlank()) return Instant.now().toString();
        try {
            return Instant.parse(s).toString();
        } catch (Exception e) {
            try {
                return Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(s)).toString();
            } catch (Exception e2) {
                return Instant.now().toString();
            }
        }
    }

    private static String toJson(Map<String, Object> m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
