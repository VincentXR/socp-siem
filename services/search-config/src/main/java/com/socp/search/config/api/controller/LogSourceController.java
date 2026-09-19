package com.socp.search.config.api.controller;


import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.api.request.LogSourceRequest;
import com.socp.search.config.config.IngestLimitsProperties;
import com.socp.search.config.config.VectorProperties;
import com.socp.search.config.render.VectorConfigRenderer;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.persistence.store.SinkTargetStore;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.auth.security.RequireIngestIdentity;
import com.socp.platform.ratelimit.api.RateLimit;
import org.springframework.data.domain.PageRequest;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.socp.platform.auth.security.RequireRole;

/**
 * SEARCH 日志源 REST API——采集链路第一环的配置面 + 接收面。
 *
 * <p>端点（context-path /search-config）：
 *   GET    /api/v1/sources            列出日志源（数据库分页）
 *   POST   /api/v1/sources            新增日志源（admin/analyst）
 *   GET    /api/v1/sources/{id}       详情
 *   PUT    /api/v1/sources/{id}       更新（admin/analyst）
 *   DELETE /api/v1/sources/{id}       删除（admin/analyst）
 *   GET    /api/v1/sources/{id}/vector-config  渲染该源对应的 Vector 片段
 *                                          （admin/analyst；凭据默认脱敏）
 *   POST   /api/v1/render             渲染全部启用源为完整 vector.toml（下载用；同上凭据口径）
 *   POST   /api/v1/ingest             接收 Vector 投递的 NDJSON（解析/落 OpenSearch 为后续步骤）
 *
 * <p>缺资源一律抛 {@link ApiException#notFound}，统一由 GlobalExceptionHandler 出
 * 非零 code 信封；成功信封的 data 内不再携带 {@code error} 字段。
 */
@RestController
@com.socp.search.config.config.SearchRuntimeRole(
        com.socp.search.config.config.SearchRuntimeRole.Role.API)
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
        this.pipeline = pipeline;
        this.ingestLimits = ingestLimits;
        this.vectorToken = vectorProperties.getToken();
        this.renderer = new VectorConfigRenderer(this.vectorToken);
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
        if (store.list().stream().noneMatch(s -> "real-file".equals(s.name()))) {
            store.save(LogSource.create("real-file", SourceType.FILE, ParseFormat.AUTO,
                    "demo/sample.log", null, null, "local", true));
        }
        if (store.list().stream().noneMatch(s -> "real-syslog".equals(s.name()))) {
            store.save(LogSource.create("real-syslog", SourceType.SYSLOG, ParseFormat.AUTO,
                    null, "0.0.0.0:5514", null, "local", true));
        }
        // 输出目标不在这里播种：平台采集入口由 socp.vector.uri 提供，租户目标经 POST /outputs 落库。
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    @GetMapping("/sources")
    public ApiResult<?> list(@org.springframework.web.bind.annotation.RequestParam(required = false) Integer page,
                             @org.springframework.web.bind.annotation.RequestParam(required = false) Integer size) {
        int safeSize = size == null || size <= 0 ? 100 : Math.min(500, size);
        if (page == null) {
            return ApiResult.ok(store.page(PageRequest.of(0, safeSize)).getContent());
        }
        if (page < 1 || size != null && (size < 1 || size > 500)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "page must be >= 1 and size must be between 1 and 500");
        }
        var result = store.page(PageRequest.of(page - 1, safeSize));
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(),
                page, safeSize, result.getTotalPages()));
    }

    /** Source-compatible Java entry point for pre-pagination callers. */
    public ApiResult<List<LogSource>> list() {
        return ApiResult.ok(store.page(PageRequest.of(0, 500)).getContent());
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/sources")
    public ApiResult<LogSource> create(@Valid @RequestBody LogSourceRequest req) {
        return ApiResult.ok(store.save(req.toNewDomain()));
    }

    @GetMapping("/sources/{id}")
    public ApiResult<Map<String, Object>> get(@PathVariable String id) {
        Optional<LogSource> s = store.get(id);
        if (s.isEmpty()) throw ApiException.notFound("未找到日志源 " + id);
        return ApiResult.ok(Map.of("source", s.get()));
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/sources/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable String id, @Valid @RequestBody LogSourceRequest req) {
        LogSource existing = store.get(id).orElseThrow(() -> ApiException.notFound("未找到日志源 " + id));
        LogSource updated = req.toDomain(id, existing.createdAt());
        store.save(updated);
        return ApiResult.ok(Map.of("source", updated));
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/sources/{id}")
    public ApiResult<Map<String, Object>> delete(@PathVariable String id) {
        boolean ok = store.delete(id);
        return ApiResult.ok(Map.of("deleted", ok, "id", id));
    }

    /**
     * 渲染单个日志源的 Vector 片段。该产物内含采集入口凭据位，因此与 {@code POST /render}
     * 同权（admin/analyst），且默认把凭据渲染为占位符；仅管理员显式 includeSecret=true 才回填明文。
     */
    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "RENDER_VECTOR_CONFIG", target = "log_source")
    @GetMapping(value = "/sources/{id}/vector-config", produces = "text/plain")
    public String vectorConfig(@PathVariable String id,
                               @RequestParam(required = false, defaultValue = "false") boolean includeSecret) {
        LogSource source = store.get(id).orElseThrow(() -> ApiException.notFound("未找到日志源 " + id));
        return renderer.render(List.of(source), sinkStore::resolveForRendering,
                secretGrant(includeSecret));
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "RENDER_VECTOR_CONFIG_ALL", target = "log_source")
    @PostMapping(value = "/render", produces = "text/plain")
    public String renderAll(@RequestParam(required = false, defaultValue = "false") boolean includeSecret) {
        return renderer.render(store.enabled(), sinkStore::resolveForRendering,
                secretGrant(includeSecret));
    }

    /** Rendering secrets are admin-only; a non-admin request that asks for them is denied. */
    private static boolean secretGrant(boolean includeSecret) {
        if (!includeSecret) return false;
        AuthenticatedIdentity identity = AuthenticatedIdentityContext.current().orElse(null);
        if (identity == null || !"admin".equalsIgnoreCase(identity.role())) {
            throw ApiException.forbidden("仅管理员可取回含明文采集凭据的 Vector 配置");
        }
        return true;
    }

    /**
     * 接收 Vector NDJSON 批量投递：经采集管线做解析/归一化/富化，写入检索事件库，
     * 并 best-effort 转发归一化事件给 DETECT 规则引擎检测。
     * 匹配 Vector 契约：json codec + newline_delimited 投递时 Content-Type 为 application/x-ndjson。
     */
    @RequireIngestIdentity
    @RateLimit(permits = 120, seconds = 1)
    @PostMapping(value = "/ingest", consumes = {
            MediaType.APPLICATION_JSON_VALUE,
            "application/x-ndjson",
            MediaType.TEXT_PLAIN_VALUE
    })
    public ApiResult<Map<String, Object>> ingest(
            @RequestBody String body,
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // 采集器可用请求头显式声明身份；未声明则按每行的 collector 字段归属运行指标
        validateIngestBody(body);
        String trustedCollector = (String) request.getAttribute(
                com.socp.platform.auth.security.CollectorCredentialRegistry.COLLECTOR_ID_ATTRIBUTE);
        if (trustedCollector == null) {
            String service = (String) request.getAttribute(
                    com.socp.platform.auth.security.RequireService.SERVICE_ID_ATTRIBUTE);
            if (service != null && !service.isBlank()) trustedCollector = "service:" + service;
        }
        // The annotation guarantees one of these identities for real HTTP
        // requests. Idempotency-Key only stabilizes event IDs for safe client
        // retries; it is never used as the trusted collector identity.
        return ApiResult.ok(pipeline.process(body, trustedCollector, idempotencyKey));
    }

    /** Keeps direct Java integrations source-compatible with the pre-key ingress signature. */
    public Map<String, Object> ingest(String body, HttpServletRequest request) {
        return ingest(body, request, null).data();
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
