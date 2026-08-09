package com.socp.search.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.ReferenceSet;
import com.socp.search.config.search.SearchEvent;
import com.socp.search.config.search.SearchStore;
import com.socp.search.config.store.ParseRuleStore;
import com.socp.search.config.store.ReferenceSetStore;
import com.socp.search.config.util.Http;
import org.springframework.beans.factory.annotation.Value;
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
    private final SearchStore searchStore;
    private final IngestTaskMonitor monitor;
    private final com.socp.search.config.search.KafkaEventProducer kafkaProducer;

    @Value("${socp.detect.url:http://localhost:18082}")
    private String gasUrl;

    public IngestPipeline(ParsePreviewService preview, ParseRuleStore parseRules,
                          ReferenceSetStore refSets, SearchStore searchStore,
                          IngestTaskMonitor monitor,
                          com.socp.search.config.search.KafkaEventProducer kafkaProducer) {
        this.preview = preview;
        this.parseRules = parseRules;
        this.refSets = refSets;
        this.searchStore = searchStore;
        this.monitor = monitor;
        this.kafkaProducer = kafkaProducer;
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
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            long bytes = t.length();
            try {
                Map<String, Object> norm = normalize(t);
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("skipped", skipped);
        out.put("forwarded", forwarded);
        out.put("queueLoad", 0.0);
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
    private Map<String, Object> normalize(String line) {
        Map<String, Object> raw;
        String rawLog;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = MAPPER.readValue(line, Map.class);
            raw = obj;
            rawLog = obj.get("message") != null ? String.valueOf(obj.get("message")) : line;
        } catch (Exception e) {
            raw = Map.of();
            rawLog = line;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        // 1) JSON 行：展平为字段
        for (var en : raw.entrySet()) {
            if ("message".equals(en.getKey())) continue;
            fields.put(en.getKey(), String.valueOf(en.getValue()));
        }
        // 2) 非 JSON 或需结构化的行：尝试启用解析规则抽取（如 sshd 失败登录）
        if (fields.isEmpty() || rawLog.contains("Failed password") || rawLog.contains("authentication failure")) {
            for (var rule : parseRules.list()) {
                Map<String, Object> pr = preview.preview(rule.id(), null, null, rawLog);
                if (Boolean.TRUE.equals(pr.get("matched"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> extracted = (Map<String, Object>) pr.get("fields");
                    if (extracted != null) fields.putAll(extracted);
                    if (fields.get("source") == null && raw.get("source") != null)
                        fields.put("source", raw.get("source"));
                    break;
                }
            }
        }
        // 3) 查找表富化：标注命中核心资产/关键人员/封禁名单
        enrich(fields);

        String source = pick(fields, "source", raw, "source", "type", "app");
        String host = pick(fields, "host", raw, "host", "hostname", "device");
        String severity = pick(fields, "severity", raw, "severity", "level");
        if (severity.isBlank()) severity = "INFO";
        String msg = rawLog;
        String ts = pick(fields, "timestamp", raw, "timestamp", "@timestamp", "time");
        Map<String, Object> norm = new LinkedHashMap<>();
        norm.put("source", source.isBlank() ? "unknown" : source);
        norm.put("host", host.isBlank() ? "unknown" : host);
        norm.put("severity", severity.toUpperCase());
        norm.put("msg", msg);
        norm.put("timestamp", parseTs(ts));
        norm.put("fields", fields);
        return norm;
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
        return new SearchEvent(
                Instant.parse(String.valueOf(norm.get("timestamp"))),
                String.valueOf(norm.get("source")),
                String.valueOf(norm.get("host")),
                String.valueOf(norm.get("severity")),
                String.valueOf(norm.get("msg")),
                Map.copyOf(fs));
    }

    /**
     * 批量转发一批归一化事件给 DETECT（NDJSON 一次 POST），按响应中的 accepted 统计成功条数，
     * 并把成功数按序记入对应采集器的 forwarded 指标。失败整批记 0（上游 Vector 靠 503 语义重试）。
     */
    private int flush(List<Map<String, Object>> batch, Map<String, long[]> perCollector, String defaultCollector) {
        if (batch.isEmpty()) return 0;
        // 检索库批量落库（一次 saveAll + OpenSearch 异步索引）
        List<SearchEvent> evs = batch.stream().map(this::toEvent).toList();
        searchStore.ingestBatch(evs);
        // 事件总线：Kafka 异步发 socp-events 主题（DETECT 消费进规则引擎）
        kafkaProducer.sendEvents(evs);
        // NDJSON 批量转发给 DETECT
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : batch) sb.append(toJson(m)).append('\n');
        String resp = Http.postBody(gasUrl + "/detect-web/api/v1/ingest/bulk", sb.toString(),
                "application/x-ndjson", 5000);
        int fl = 0;
        if (resp != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = MAPPER.readValue(resp, Map.class);
                Object a = r.get("accepted");
                fl = a instanceof Number ? ((Number) a).intValue() : 0;
            } catch (Exception ignored) {
                fl = 0;
            }
        }
        int n = Math.min(fl, batch.size());
        for (int i = 0; i < n; i++) {
            bump(perCollector, collectorOf(batch.get(i), defaultCollector), 0, 0, 1, 0);
        }
        batch.clear();
        return fl;
    }

    private static String pick(Map<String, Object> fields, String defKey, Map<String, Object> raw, String... keys) {
        if (fields.get(defKey) != null) return String.valueOf(fields.get(defKey));
        for (String k : keys) {
            if (raw.get(k) != null) return String.valueOf(raw.get(k));
        }
        return "";
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
