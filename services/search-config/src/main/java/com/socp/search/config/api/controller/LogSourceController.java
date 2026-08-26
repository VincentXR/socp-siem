package com.socp.search.config.api.controller;





import com.socp.search.config.persistence.store.*;
import com.socp.search.config.parser.*;
import com.socp.search.config.domain.*;
import com.socp.search.config.domain.*;
import com.socp.search.config.infrastructure.kafka.*;
import com.socp.search.config.infrastructure.opensearch.*;
import com.socp.search.config.infrastructure.serialization.*;
import com.socp.search.config.persistence.entity.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.store.*;
import com.socp.search.config.service.*;
import com.socp.search.config.api.request.*;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.config.IngestLimitsProperties;
import com.socp.search.config.config.VectorProperties;
import com.socp.search.config.render.VectorConfigRenderer;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.persistence.store.SinkTargetStore;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.socp.platform.auth.security.RequireRole;

/**
 * SEARCH 日志源 REST API——采集链路第一环的配置面 + 接收面。
 *
 * <p>端点（context-path /search-config）：
 *   GET    /api/v1/sources            列出全部日志源
 *   POST   /api/v1/sources            新增日志源（含输入细节/输出目标/解析规则）
 *   GET    /api/v1/sources/{id}       详情
 *   PUT    /api/v1/sources/{id}       更新
 *   DELETE /api/v1/sources/{id}       删除
 *   GET    /api/v1/sources/{id}/vector-config  渲染该源对应的 Vector 片段
 *   POST   /api/v1/render             渲染全部启用源为完整 vector.toml（下载用）
 *   POST   /api/v1/ingest             接收 Vector 投递的 NDJSON（解析/落 OpenSearch 为后续步骤）
 */
@RestController
@RequestMapping("/api/v1")
public class LogSourceController {

    private final LogSourceStore store;
    private final SinkTargetStore sinkStore;
    private final VectorConfigRenderer renderer;
    private final com.socp.search.config.service.IngestPipeline pipeline;
    private final IngestLimitsProperties ingestLimits;
    private final String vectorToken;

    public LogSourceController(LogSourceStore store, SinkTargetStore sinkStore,
                               com.socp.search.config.service.IngestPipeline pipeline,
                               IngestLimitsProperties ingestLimits) {
        this(store, sinkStore, pipeline, ingestLimits, new VectorProperties());
    }

    @Autowired
    public LogSourceController(LogSourceStore store, SinkTargetStore sinkStore,
                               com.socp.search.config.service.IngestPipeline pipeline,
                               IngestLimitsProperties ingestLimits,
                               VectorProperties vectorProperties) {
        this.store = store;
        this.sinkStore = sinkStore;
        this.renderer = new VectorConfigRenderer(null);
        this.pipeline = pipeline;
        this.ingestLimits = ingestLimits;
        this.vectorToken = vectorProperties.getToken();
    }

    @PostConstruct
    void seed() {
        // Bootstrap data is deliberately scoped to the default tenant. Request paths remain fail-closed.
        String previousTenant = TenantContext.get();
        TenantContext.set("default");
        try {
        // 起一个 demo 文件源，方便首次联调（等同 com.siem 的 demo/sample.log 旁路）
        if (store.list().isEmpty()) {
            store.save(LogSource.create("demo-auth-log", SourceType.FILE, ParseFormat.AUTO,
                    "demo/sample.log", null, null, "local", true));
        }
        // 2026-08-12：真实采集链路种子——Vector 监听文件尾部 + syslog TCP 5514，
        // 解析权归 SEARCH（parse_format=AUTO），与 agents/vector-pipeline/vector.toml 对齐
        if (store.list().stream().noneMatch(s -> "real-file".equals(s.id()))) {
            store.save(LogSource.create("real-file", SourceType.FILE, ParseFormat.AUTO,
                    "demo/sample.log", null, null, "local", true));
        }
        if (store.list().stream().noneMatch(s -> "real-syslog".equals(s.id()))) {
            store.save(LogSource.create("real-syslog", SourceType.SYSLOG, ParseFormat.AUTO,
                    null, "0.0.0.0:5514", null, "local", true));
        }
        // 输出目标：SEARCH ingest + 机机 token（渲染 Vector 配置时注入 Authorization 头）
        if (sinkStore.list().isEmpty()) {
            sinkStore.save(SinkTarget.create("search-ingest", "SEARCH",
                    "http://localhost:18081/search-config/api/v1/ingest",
                    "Bearer " + vectorToken, true));
        }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    @GetMapping("/sources")
    public List<LogSource> list() {
        return store.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/sources")
    public LogSource create(@Valid @RequestBody LogSourceRequest req) {
        return store.save(req.toNewDomain());
    }

    @GetMapping("/sources/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Optional<LogSource> s = store.get(id);
        if (s.isEmpty()) return Map.of("error", "not_found", "id", id);
        return Map.of("source", s.get());
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/sources/{id}")
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody LogSourceRequest req) {
        Optional<LogSource> exist = store.get(id);
        if (exist.isEmpty()) return Map.of("error", "not_found", "id", id);
        LogSource updated = req.toDomain(id, exist.get().createdAt());
        store.save(updated);
        return Map.of("source", updated);
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/sources/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean ok = store.delete(id);
        return Map.of("deleted", ok, "id", id);
    }

    @GetMapping(value = "/sources/{id}/vector-config", produces = "text/plain")
    public String vectorConfig(@PathVariable String id) {
        Optional<LogSource> s = store.get(id);
        if (s.isEmpty()) return "# not_found: " + id;
        return renderer.render(List.of(s.get()), sinkStore.list());
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping(value = "/render", produces = "text/plain")
    public String renderAll() {
        return renderer.render(store.enabled(), sinkStore.list());
    }

    /**
     * 接收 Vector NDJSON 批量投递：经采集管线做解析/归一化/富化，写入检索事件库，
     * 并 best-effort 转发归一化事件给 DETECT 规则引擎检测。
     * 匹配 Vector 契约：json codec + newline_delimited 投递时 Content-Type 为 application/x-ndjson。
     */
    @PostMapping(value = "/ingest", consumes = {
            MediaType.APPLICATION_JSON_VALUE,
            "application/x-ndjson",
            MediaType.TEXT_PLAIN_VALUE
    })
    public Map<String, Object> ingest(
            @RequestBody String body,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-SOCP-Collector", required = false)
            String collector) {
        // 采集器可用请求头显式声明身份；未声明则按每行的 collector 字段归属运行指标
        validateIngestBody(body);
        return pipeline.process(body, collector);
    }

    private void validateIngestBody(String body) {
        if (body == null) return;
        int bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bodyBytes > ingestLimits.getMaxBodyBytes()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "ingest body exceeds " + ingestLimits.getMaxBodyBytes() + " bytes");
        }
        long events = body.lines().filter(line -> !line.isBlank()).count();
        if (events > ingestLimits.getMaxEvents()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "ingest body exceeds " + ingestLimits.getMaxEvents() + " events");
        }
        boolean oversizedEvent = body.lines().anyMatch(line ->
                line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ingestLimits.getMaxEventBytes());
        if (oversizedEvent) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "ingest event exceeds " + ingestLimits.getMaxEventBytes() + " bytes");
        }
    }
}
