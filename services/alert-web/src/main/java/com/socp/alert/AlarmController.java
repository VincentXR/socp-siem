package com.socp.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.platform.audit.AuditOperation;
import com.socp.platform.auth.RequireRole;
import com.socp.platform.error.ApiResult;
import com.socp.platform.ratelimit.RateLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    public AlarmController(AlarmService service) {
        this.service = service;
    }

    /** 写入告警（接入→检测→分析 的产物落 t_alarm）。带审计注解，结果进 Kafka socp-audit（Docker 环境）。 */
    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "CREATE_ALARM", target = "t_alarm")
    @PostMapping
    public ApiResult<Alarm> create(@Valid @RequestBody CreateAlarmRequest req) {
        Alarm a = new Alarm(req.ruleId(), req.ruleName(), req.severity(), req.message(), req.entity(),
                req.mitre(), null);
        // 采集侧可能延迟上报，尊重入参的事件发生时间；缺省才用服务端 now（Alarm 字段默认值）
        if (req.occurredAt() != null) {
            a.setOccurredAt(req.occurredAt());
        }
        // 检测侧（DETECT）已给出初评时先沿用；随后 THREAT 富化会二次修正
        a.setRiskScore(req.riskScore());
        return ApiResult.ok(service.create(a));
    }

    /** 查询告警：支持 severity / rule / q 过滤 + 分页（page 从 1 起，size 缺省 20）。
     *  只传 size 返回切片 List（兼容 verify 的 ?size=200 全量拉取）；
     *  传 page 返回分页结构 {items,total,page,size}。带限流（每租户 10/s）。 */
    @RateLimit(permits = 10, seconds = 1)
    @GetMapping
    public Object list(
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String rule,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        List<Alarm> all = service.query(severity, rule, q);
        if (page == null && size == null) {
            return ApiResult.ok(all);
        }
        int sz = size == null || size <= 0 ? 20 : Math.min(size, 500);
        if (page == null) {
            // 只传 size：按大小切片返回 List（保持旧契约）
            int to = Math.min(sz, all.size());
            return ApiResult.ok(all.subList(0, to));
        }
        int pg = page < 1 ? 1 : page;
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

    /** 告警聚合统计：总览 / 按级别 / 近 7 天趋势 / 按规则 Top（供 REPORT 报表）。 */
    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats() {
        return ApiResult.ok(service.stats());
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
        } catch (Exception e) {
            return "[]";
        }
    }

    public record CreateAlarmRequest(
            @NotBlank String ruleId,
            @NotBlank String ruleName,
            Severity severity,
            String message,
            String entity,
            /** 关联的 MITRE ATT&CK 技术 ID（由检测规则带入） */
            String mitre,
            /** 事件实际发生时间（ISO-8601，如 2026-08-06T10:00:00Z）；不传则取服务端接收时间 */
            Instant occurredAt,
            /** 检测侧初评的威胁评分 0~100，可空（空则由 ALERT 自行计算） */
            Integer riskScore) {
    }
}
