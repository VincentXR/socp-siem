package com.socp.alert.api.controller;

import com.socp.alert.api.request.CreateAlarmRequest;
import com.socp.alert.api.request.AlarmBatchRequest;
import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.Severity;
import com.socp.alert.api.response.AlarmEvidenceResponse;
import com.socp.alert.service.AlarmService;
import com.socp.alert.service.AlertPerformanceMetrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.ratelimit.api.RateLimit;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/alarms", "/api/alarms"})
public class AlarmController {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /** Export streams the tenant alarm set in database-side pages of this size; nothing larger is held in memory. */
    static final int EXPORT_BATCH_SIZE = 500;
    private static final String CSV_HEADER =
            "id,ruleId,title,ruleName,severity,entity,mitre,riskScore,status,occurredAt,message\n";
    private final AlarmService service;
    private final AlertPerformanceMetrics performanceMetrics;

    @org.springframework.beans.factory.annotation.Autowired
    public AlarmController(AlarmService service, AlertPerformanceMetrics performanceMetrics) {
        this.service = service;
        this.performanceMetrics = performanceMetrics;
    }

    /** Unit-test/source compatibility constructor. */
    public AlarmController(AlarmService service) {
        this.service = service;
        this.performanceMetrics = null;
    }

    /** 写入告警（接入→检测→分析 的产物落 t_alarm）。带审计注解，结果进 Kafka socp-audit（Docker 环境）。 */
    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @AuditOperation(action = "CREATE_ALARM", target = "t_alarm")
    @PostMapping
    public ApiResult<Alarm> create(@Valid @RequestBody CreateAlarmRequest req) {
        AlertPerformanceMetrics.Sample sample = performanceMetrics == null
                ? null : performanceMetrics.requestReceived(req.detectionOutboxClaimedAt());
        Alarm a = new Alarm(req.ruleId(), req.ruleName(), req.severity(), req.message(), req.entity(),
                req.mitre(), null);
        a.setTitle(req.title());
        a.setSourceAlertId(req.sourceAlertId());
        // 采集侧可能延迟上报，尊重入参的事件发生时间；缺省才用服务端 now（Alarm 字段默认值）
        if (req.occurredAt() != null) {
            a.setOccurredAt(req.occurredAt());
        }
        // 检测侧（DETECT）已给出初评时先沿用；随后 THREAT 富化会二次修正
        a.setRiskScore(req.riskScore());
        a.setTriggerIngestedAt(req.triggerIngestedAt());
        a.setAlertCreatedAt(req.alertCreatedAt());
        a.setProcessingLatencyMs(req.processingLatencyMs());
        a.setTriggerEventId(req.triggerEventId());
        try {
            Alarm saved = service.create(a, req.evidence() == null ? List.of() : req.evidence());
            if (performanceMetrics != null) {
                performanceMetrics.committed(sample, req.triggerIngestedAt());
            }
            return ApiResult.ok(saved);
        } catch (RuntimeException failure) {
            if (performanceMetrics != null) performanceMetrics.failed();
            throw failure;
        }
    }

    /**
     * Batch materialization used by high-volume detection consumers. The
     * caller must provide an idempotency key because each item can emit an
     * alarm outbox event and downstream delivery receipt.
     */
    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @AuditOperation(action = "CREATE_ALARM_BATCH", target = "t_alarm")
    @PostMapping("/batch")
    public ApiResult<Map<String, Object>> createBatch(
            @Valid @RequestBody AlarmBatchRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResult.ok(service.createBatch(request.alarms(), idempotencyKey));
    }

    /** 查询告警：支持 severity / rule / q 过滤 + 分页（page 从 1 起，size 缺省 20）。
     *  只传 size 返回切片 List（兼容 verify 的 ?size=200 全量拉取）；
     *  传 page 返回分页结构 {items,total,page,size}。带限流（每租户 10/s）。 */
    @RateLimit(permits = 10, seconds = 1)
    @GetMapping
    public Object list(
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String rule,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "occurredAt") String sort,
            @RequestParam(defaultValue = "descending") String order,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int sz = size == null || size <= 0 ? 20 : Math.min(size, 500);
        int pg = page == null || page < 1 ? 1 : page;
        if (page != null || size != null) {
            var result = service.page(severity, rule, status, q, sort, order, pg, sz);
            if (page == null) {
                return ApiResult.ok(result.getContent());
            }
            return ApiResult.ok(Map.of(
                    "items", result.getContent(),
                    "total", result.getTotalElements(),
                    "page", pg,
                    "size", sz));
        }

        // Keep the legacy array response for callers that omit pagination, but
        // never materialize the entire tenant alarm table in the JVM. Older
        // versions called service.query() here, which made a large tenant turn
        // a harmless refresh into an OutOfMemoryError.
        return ApiResult.ok(service.page(severity, rule, status, q, sort, order, 1, sz).getContent());
    }

    /** 下钻单条告警 */
    @GetMapping("/{id}")
    public ApiResult<Alarm> get(@PathVariable String id) {
        return ApiResult.ok(service.get(id));
    }

    /** Reverse lookup used to connect a log event to alert/case/SOAR context. */
    @RateLimit(permits = 20, seconds = 1)
    @GetMapping("/by-event")
    public ApiResult<List<Alarm>> byEvent(@RequestParam String eventId) {
        return ApiResult.ok(service.byEvent(eventId));
    }

    /** Return source-event snapshots captured when the alert was created. */
    @RateLimit(permits = 20, seconds = 1)
    @GetMapping("/{id}/evidence")
    public ApiResult<AlarmEvidenceResponse> evidence(@PathVariable String id) {
        return ApiResult.ok(service.evidence(id));
    }

    /** Durable downstream receipt state for audit/chaos evidence. */
    @GetMapping("/{id}/deliveries")
    public ApiResult<List<Map<String, Object>>> deliveries(@PathVariable String id) {
        return ApiResult.ok(service.deliveryStatus(id));
    }

    /** Bounded same-rule/entity candidates for investigation expansion. */
    @GetMapping("/{id}/similar")
    public ApiResult<List<Alarm>> similar(@PathVariable String id,
                                          @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(service.similar(id, limit));
    }

    /** 告警聚合统计：默认全量；window=7d 时返回近 7 个自然日数据。 */
    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats(@RequestParam(defaultValue = "all") String window) {
        return ApiResult.ok(service.stats(window));
    }

    /**
     * 归档导出：告警按 CSV 或 JSON 下载（数据带不走问题的解法）。
     * 分批流式写出：每批只从数据库取 EXPORT_BATCH_SIZE 条并即刻写回响应，
     * 不再全量物化租户告警，超大租户也不会把 JVM 推向 OOM。
     */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String rule,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "occurredAt") String sort,
            @RequestParam(defaultValue = "descending") String order,
            HttpServletResponse response) throws IOException {
        boolean json = "json".equalsIgnoreCase(format);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + (json ? "alarms.json" : "alarms.csv") + "\"");
        response.setContentType(json ? "application/json" : "text/csv; charset=utf-8");
        PrintWriter writer = response.getWriter();
        if (json) {
            writer.write('[');
        } else {
            writer.write(CSV_HEADER);
        }
        int page = 1;
        boolean first = true;
        while (true) {
            var result = service.page(severity, rule, status, q, sort, order, page, EXPORT_BATCH_SIZE);
            List<Alarm> batch = result.getContent();
            for (Alarm alarm : batch) {
                if (!first) writer.write(json ? "," : "\n");
                writer.write(json ? toJson(alarm) : csvRow(alarm));
                first = false;
            }
            if (batch.size() < EXPORT_BATCH_SIZE || !result.hasNext()) break;
            page++;
        }
        if (json) writer.write(']');
        writer.flush();
    }

    private static String csvRow(Alarm a) {
        return csv(a.getId()) + ',' + csv(a.getRuleId()) + ',' + csv(a.getTitle())
                + ',' + csv(a.getRuleName())
                + ',' + a.getSeverity() + ',' + csv(a.getEntity())
                + ',' + csv(a.getMitre()) + ',' + (a.getRiskScore() == null ? "" : a.getRiskScore())
                + ',' + a.getStatus() + ',' + a.getOccurredAt()
                + ',' + csv(a.getMessage());
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String toJson(Alarm alarm) {
        try {
            return MAPPER.writeValueAsString(alarm);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("cannot serialize alarm export", failure);
        }
    }

    /** CreateAlarmRequest is defined in api.request. */
            /** 关联的 MITRE ATT&CK 技术 ID（由检测规则带入） */
            /** 事件实际发生时间（ISO-8601，如 2026-08-06T10:00:00Z）；不传则取服务端接收时间 */
            /** 检测侧初评的威胁评分 0~100，可空（空则由 ALERT 自行计算） */
}
