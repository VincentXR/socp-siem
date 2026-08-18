package com.socp.detect.web.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import com.socp.platform.auth.RequireRole;

/**
 * DETECT 检测 API：规则 CRUD + 热更新 + 事件摄取（背压 503）+ 告警/统计查询。
 * 契约与 SEARCH ingest 一致：队列满回 503 + Retry-After，上游 Vector/调度器据此重试。
 */
@RestController
@RequestMapping("/api/v1")
public class RuleController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DetectEngineService engine;
    private final com.socp.detect.web.engine.AlertStreamHub streamHub;

    public RuleController(DetectEngineService engine, com.socp.detect.web.engine.AlertStreamHub streamHub) {
        this.engine = engine;
        this.streamHub = streamHub;
    }

    // ---------- 规则管理 ----------

    @GetMapping("/rules")
    public java.util.List<Map<String, Object>> listRules() {
        return engine.listRules();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules")
    public Map<String, Object> addRule(@RequestBody Map<String, Object> spec) {
        return engine.addRule(spec);
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/rules/{id}")
    public Map<String, Object> updateRule(@PathVariable String id, @RequestBody Map<String, Object> spec) {
        spec.put("id", id);
        return engine.updateRule(spec);
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("removed", engine.deleteRule(id));
        return body;
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules/reload")
    public Map<String, Object> reload() {
        engine.reload();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reloaded", true);
        body.put("rules", engine.listRules().size());
        return body;
    }

    // ---------- 事件摄取（生产由 Kafka 订阅驱动，此处为本地验证入口） ----------

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody Map<String, Object> ev) {
        SecurityEvent event = toEvent(ev);
        boolean accepted = engine.ingest(event);
        if (!accepted) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "queue_full");
            body.put("accepted", false);
            body.put("queueLoad", engine.stats().get("queueLoad"));
            return ResponseEntity.status(503).header("Retry-After", "2").body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("queueLoad", engine.stats().get("queueLoad"));
        return ResponseEntity.ok(body);
    }

    /**
     * 批量摄取（NDJSON）：SEARCH 攒批转发入口，避免逐条 HTTP 的 RTT 成为吞吐瓶颈。
     * 逐行解析并入引擎队列，返回 accepted/rejected 统计；上游按 accepted 记成功数。
     */
    @PostMapping(value = "/ingest/bulk", consumes = {
            MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson", MediaType.TEXT_PLAIN_VALUE
    })
    public Map<String, Object> ingestBulk(@RequestBody String body) {
        int accepted = 0, rejected = 0;
        if (body != null) {
            for (String line : body.split("\n", -1)) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = MAPPER.readValue(t, new TypeReference<Map<String, Object>>() {
                    });
                    if (engine.ingest(toEvent(m))) accepted++;
                    else rejected++;
                } catch (Exception e) {
                    rejected++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("rejected", rejected);
        out.put("queueLoad", engine.stats().get("queueLoad"));
        return out;
    }

    // ---------- 观测 ----------

    @GetMapping("/alerts")
    public java.util.List<Alert> alerts() {
        return engine.recentAlerts();
    }

    /** SSE 实时告警流：前端 EventSource 订阅，新告警即时推送（event: alert, data: JSON）。
     *  手动 Servlet 输出流实现（阻塞 + 3s 心跳），客户端断开时自动结束。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(jakarta.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        java.io.PrintWriter out = resp.getWriter();
        out.write(": socp connected\n\n");
        out.flush();
        streamHub.add(out);
        try {
            while (true) {
                Thread.sleep(3000);
                out.write(": ping\n\n");
                out.flush();
            }
        } catch (Exception e) {
            // 客户端断开/超时，正常结束
        } finally {
            streamHub.remove(out);
        }
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return engine.stats();
    }

    private static SecurityEvent toEvent(Map<String, Object> m) {
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) m.getOrDefault("fields", Map.of());
        String raw = m.get("raw") == null ? String.valueOf(m.getOrDefault("msg", "")) : String.valueOf(m.get("raw"));
        String source = m.get("source") == null ? "unknown" : String.valueOf(m.get("source"));
        String host = m.get("host") == null ? "unknown" : String.valueOf(m.get("host"));
        Severity severity = Severity.INFO;
        if (m.get("severity") != null) {
            try {
                severity = Severity.valueOf(String.valueOf(m.get("severity")).toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 缺省 INFO
            }
        }
        Instant ts = Instant.now();
        if (m.get("timestamp") != null) {
            try {
                ts = Instant.parse(String.valueOf(m.get("timestamp")));
            } catch (Exception ignored) {
                // 解析失败用 now()
            }
        }
        // msg 并入 fields，保证 RuleSpec 的 msg 条件可命中
        if (m.containsKey("msg") && !fields.containsKey("msg")) {
            fields = new LinkedHashMap<>(fields);
            fields.put("msg", String.valueOf(m.get("msg")));
        }
        String eventId = m.get("eventId") == null ? null : String.valueOf(m.get("eventId"));
        if (eventId == null || eventId.isBlank() || "null".equalsIgnoreCase(eventId)) {
            eventId = java.util.UUID.randomUUID().toString();
        }
        return new SecurityEvent(eventId, ts, source, host, raw, fields, severity);
    }
}
