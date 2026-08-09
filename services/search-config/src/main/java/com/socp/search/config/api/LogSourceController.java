package com.socp.search.config.api;

import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.render.VectorConfigRenderer;
import com.socp.search.config.store.LogSourceStore;
import com.socp.search.config.store.SinkTargetStore;
import jakarta.annotation.PostConstruct;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public LogSourceController(LogSourceStore store, SinkTargetStore sinkStore,
                               com.socp.search.config.service.IngestPipeline pipeline) {
        this.store = store;
        this.sinkStore = sinkStore;
        this.renderer = new VectorConfigRenderer(null);
        this.pipeline = pipeline;
    }

    @PostConstruct
    void seed() {
        // 起一个 demo 文件源，方便首次联调（等同 com.siem 的 demo/sample.log 旁路）
        if (store.list().isEmpty()) {
            store.save(LogSource.create("demo-auth-log", SourceType.FILE, ParseFormat.AUTO,
                    "demo/sample.log", null, null, "local", true));
        }
    }

    @GetMapping("/sources")
    public List<LogSource> list() {
        return store.list();
    }

    @PostMapping("/sources")
    public LogSource create(@RequestBody LogSource req) {
        LogSource src = LogSource.createFull(
                req.name(), req.type(), req.format(),
                req.path(), req.address(), req.topic(), req.env(), req.enabled(),
                req.readFrom(), req.multiline(), req.sinkTargetId(),
                req.parseRuleIds(), req.description(),
                req.protocol(), req.charset(), req.timeField(), req.timezone(),
                req.tags(), req.frequency(), req.categoryId(), req.groupId());
        return store.save(src);
    }

    @GetMapping("/sources/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Optional<LogSource> s = store.get(id);
        if (s.isEmpty()) return Map.of("error", "not_found", "id", id);
        return Map.of("source", s.get());
    }

    @PutMapping("/sources/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody LogSource req) {
        Optional<LogSource> exist = store.get(id);
        if (exist.isEmpty()) return Map.of("error", "not_found", "id", id);
        LogSource updated = new LogSource(id, req.name(), req.type(), req.format(),
                req.path(), req.address(), req.topic(), req.env(), req.enabled(),
                req.readFrom(), req.multiline(), req.sinkTargetId(),
                req.parseRuleIds(), req.description(),
                req.protocol(), req.charset(), req.timeField(), req.timezone(),
                req.tags(), req.frequency(), req.categoryId(), req.groupId(),
                exist.get().createdAt());
        store.save(updated);
        return Map.of("source", updated);
    }

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
        return pipeline.process(body, collector);
    }
}
