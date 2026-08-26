package com.socp.alert.api.controller;
import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.api.response.AlarmEvidenceResponse;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.ratelimit.api.RateLimit;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
    @AuditOperation(action = "CREATE_ALARM", target = "t_alarm")
    @PostMapping
    public ApiResult<Alarm> create(@Valid @RequestBody CreateAlarmRequest req) {
        AlertPerformanceMetrics.Sample sample = performanceMetrics == null
                ? null : performanceMetrics.requestReceived(req.detectionOutboxClaimedAt());
        Alarm a = new Alarm(req.ruleId(), req.ruleName(), req.severity(), req.message(), req.entity(),
                req.mitre(), null);
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

        List<Alarm> all = service.query(severity, rule, status, q, sort, order);
        if (page == null && size == null) {
            return ApiResult.ok(all);
        }
        if (page == null) {
            // 只传 size：按大小切片返回 List（保持旧契约）
            int to = Math.min(sz, all.size());
            return ApiResult.ok(all.subList(0, to));
        }
        int from = Math.min((pg - 1) * sz, all.size());
        int to = Math.min(from + sz, all.size());
        return ApiResult.ok(Map.of(
                "items", all.subList(from, to),
                "total", all.size(),
                "page", pg,
                "size", sz));
    }

    /** 下钻单条告警 */
    @GetMapping("/{id}")
    public ApiResult<Alarm> get(@PathVariable String id) {
        return ApiResult.ok(service.get(id));
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

    /** 告警聚合统计：默认全量；window=7d 时返回近 7 个自然日数据。 */
    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats(@RequestParam(defaultValue = "all") String window) {
        return ApiResult.ok(service.stats(window));
    }

    /** 归档导出：告警全量按 CSV 或 JSON 下载（数据带不走问题的解法）。 */
    @GetMapping("/export")
    public ResponseEntity<String> export(
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String rule,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "csv") String format) {
        List<Alarm> alarms = service.query(severity, rule, q);
        String fname, ctype, body;
        if ("json".equalsIgnoreCase(format)) {
            fname = "alarms.json";
            ctype = "application/json";
            body = toJson(alarms);
        } else {
            fname = "alarms.csv";
            ctype = "text/csv; charset=utf-8";
            body = toCsv(alarms);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .contentType(MediaType.parseMediaType(ctype))
                .body(body);
    }

    private static String toCsv(List<Alarm> alarms) {
        StringBuilder sb = new StringBuilder("id,ruleId,ruleName,severity,entity,mitre,riskScore,status,occurredAt,message\n");
        for (Alarm a : alarms) {
            sb.append(csv(a.getId())).append(',').append(csv(a.getRuleId())).append(',').append(csv(a.getRuleName()))
                    .append(',').append(a.getSeverity()).append(',').append(csv(a.getEntity()))
                    .append(',').append(csv(a.getMitre())).append(',').append(a.getRiskScore() == null ? "" : a.getRiskScore())
                    .append(',').append(a.getStatus()).append(',').append(a.getOccurredAt())
                    .append(',').append(csv(a.getMessage())).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String toJson(List<Alarm> alarms) {
        try {
            return MAPPER.writeValueAsString(alarms);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("cannot serialize alarm export", failure);
        }
    }

    /** CreateAlarmRequest is defined in api.request. */
            /** 关联的 MITRE ATT&CK 技术 ID（由检测规则带入） */
            /** 事件实际发生时间（ISO-8601，如 2026-08-06T10:00:00Z）；不传则取服务端接收时间 */
            /** 检测侧初评的威胁评分 0~100，可空（空则由 ALERT 自行计算） */
}
