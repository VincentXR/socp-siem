package com.socp.search.config.api.controller;


import com.socp.search.config.domain.LogSource;
import com.socp.search.config.api.request.IngestTestRequest;
import com.socp.search.config.service.IngestPipeline;
import com.socp.search.config.service.IngestTaskMonitor;
import com.socp.search.config.persistence.store.LogSourceStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.socp.platform.auth.security.RequireRole;
import jakarta.validation.Valid;

/**
 * 接入任务管理 API：把"接入配置"和"运行指标"合成一个任务视图。
 *
 * <ul>
 *   <li>GET  /api/v1/ingest/tasks           —— 任务列表（配置 + EPS/累计量/健康状态）</li>
 *   <li>GET  /api/v1/ingest/tasks/summary   —— 全局接入摘要</li>
 *   <li>POST /api/v1/ingest/tasks/{id}/start|stop —— 启停任务（等价于切换 enabled 并重渲染 Vector 配置）</li>
 *   <li>POST /api/v1/ingest/tasks/{id}/test —— 灌一条样例日志走完整管线，回显解析结果</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class IngestTaskController {

    private final LogSourceStore store;
    private final IngestTaskMonitor monitor;
    private final IngestPipeline pipeline;

    public IngestTaskController(LogSourceStore store, IngestTaskMonitor monitor, IngestPipeline pipeline) {
        this.store = store;
        this.monitor = monitor;
        this.pipeline = pipeline;
    }

    @GetMapping("/ingest/tasks")
    public List<Map<String, Object>> tasks() {
        return store.list().stream().map(this::toTask).toList();
    }

    @GetMapping("/ingest/tasks/summary")
    public Map<String, Object> summary() {
        List<String> enabled = store.enabled().stream().map(LogSource::collectorTag).toList();
        Map<String, Object> m = new LinkedHashMap<>(monitor.summary(enabled));
        m.put("sources", store.list().size());
        m.put("enabledSources", enabled.size());
        return m;
    }

    @GetMapping("/ingest/tasks/{id}")
    public ResponseEntity<?> task(@PathVariable String id) {
        Optional<LogSource> s = store.get(id);
        return s.<ResponseEntity<?>>map(logSource -> ResponseEntity.ok(toTask(logSource)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "source_not_found", "id", id)));
    }

        @RequireRole({"admin", "analyst"})
@PostMapping("/ingest/tasks/{id}/start")
    public ResponseEntity<?> start(@PathVariable String id) {
        return toggle(id, true);
    }

        @RequireRole({"admin", "analyst"})
@PostMapping("/ingest/tasks/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable String id) {
        return toggle(id, false);
    }

    /** 接入连通性自测：灌一条样例日志走完整解析/富化/转发链路，回显管线结果 */
        @RequireRole({"admin", "analyst"})
@PostMapping("/ingest/tasks/{id}/test")
    public ResponseEntity<?> test(@PathVariable String id, @Valid @RequestBody(required = false) IngestTestRequest body) {
        Optional<LogSource> s = store.get(id);
        if (s.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "source_not_found", "id", id));
        }
        LogSource src = s.get();
        String sample = body == null || body.sample() == null
                ? defaultSample(src) : body.sample();
        Map<String, Object> result = pipeline.process(sample, src.collectorTag());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("collector", src.collectorTag());
        out.put("sample", sample);
        out.put("pipeline", result);
        out.put("ok", Integer.parseInt(String.valueOf(result.getOrDefault("accepted", 0))) > 0);
        return ResponseEntity.ok(out);
    }

    /** 造一条贴合该源类型的样例日志，让"测试"按钮开箱即用 */
    private static String defaultSample(LogSource s) {
        String collector = s.collectorTag();
        String host = s.env() == null || s.env().isBlank() ? "test-host" : s.env() + "-host";
        return """
               {"collector":"%s","host":"%s","source":"auth","severity":"WARN",\
               "message":"Failed password for invalid user admin from 203.0.113.66 port 51234 ssh2",\
               "src_ip":"203.0.113.66","user":"admin"}"""
                .formatted(collector, host);
    }

    private ResponseEntity<?> toggle(String id, boolean enabled) {
        Optional<LogSource> opt = store.get(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "source_not_found", "id", id));
        }
        LogSource s = opt.get();
        LogSource updated = new LogSource(s.id(), s.name(), s.type(), s.format(), s.path(), s.address(),
                s.topic(), s.env(), enabled, s.readFrom(), s.multiline(), s.sinkTargetId(),
                s.parseRuleIds(), s.description(), s.protocol(), s.charset(), s.timeField(),
                s.timezone(), s.tags(), s.frequency(), s.categoryId(), s.groupId(), s.createdAt());
        store.save(updated);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("enabled", enabled);
        out.put("task", toTask(updated));
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> toTask(LogSource s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("type", s.type() == null ? null : s.type().name());
        m.put("format", s.format() == null ? null : s.format().name());
        m.put("enabled", s.enabled());
        m.put("collector", s.collectorTag());
        m.put("target", target(s));
        m.put("env", s.env());
        m.put("tags", s.tags());
        m.put("categoryId", s.categoryId());
        m.put("sinkTargetId", s.sinkTargetId());
        m.put("parseRuleIds", s.parseRuleIds());
        m.put("createdAt", s.createdAt() == null ? null : s.createdAt().toString());
        m.put("runtime", monitor.runtime(s.collectorTag(), s.enabled()));
        return m;
    }

    /** 任务列表里给运维一眼能看懂的"从哪儿收"，避免让人回头翻配置详情 */
    private static String target(LogSource s) {
        if (s.path() != null && !s.path().isBlank()) return s.path();
        if (s.address() != null && !s.address().isBlank()) {
            return (s.protocol() == null ? "" : s.protocol() + "://") + s.address();
        }
        if (s.topic() != null && !s.topic().isBlank()) return "kafka:" + s.topic();
        return "-";
    }
}
