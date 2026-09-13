package com.socp.incident.web.api.controller;
import com.socp.incident.web.api.request.AlarmRequest;
import com.socp.incident.web.api.request.CreateCaseRequest;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.service.CaseService;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import com.socp.platform.error.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Page;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 案件与调查 REST API（context-path /incident-web）。
 * 建案/归并、列表、时间线、状态流转、备注。
 */
@RestController
@RequestMapping("/api/v1")
public class CaseController {

    static final int EXPORT_BATCH_SIZE = 500;
    static final int EXPORT_DEFAULT_LIMIT = 10_000;
    static final int EXPORT_MAX_LIMIT = 100_000;

    private final CaseService service;
    private final int maxListSize;
    private final ObjectMapper objectMapper;

    public CaseController(CaseService service,
                          @Value("${socp.web.list-max-size:500}") int maxListSize,
                          ObjectMapper objectMapper) {
        this.service = service;
        this.maxListSize = maxListSize;
        this.objectMapper = objectMapper;
    }

    /** 由告警自动建案/归并（alert-web 创建告警时调用，或 SOAR 触发）。 */
    @RequireRole({"admin", "analyst"})
    @RequirePermission("case:write")
    @AuditOperation(action = "CREATE_INCIDENT", target = "case")
    @PostMapping("/incidents/from-alarm")
    public ApiResult<Map<String, Object>> fromAlarm(@Valid @RequestBody AlarmRequest alarm) {
        return ApiResult.ok(service.fromAlarm(alarm.asMap()));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("case:write")
    @AuditOperation(action = "CREATE_INCIDENT", target = "case")
    @PostMapping("/incidents")
    public ApiResult<Map<String, Object>> create(@Valid @RequestBody CreateCaseRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "案件标题不能为空");
        }
        return ApiResult.ok(Map.of("case", service.create(request.title(), request.entity(), request.severity(), request.assignee())));
    }

    /** 案件列表：租户级分页（page 从 1 起，size 上限 socp.web.list-max-size，默认 500）。 */
    @GetMapping("/incidents")
    public ApiResult<PageResponse<Case>> list(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "500") int size,
                                              @RequestParam(defaultValue = "") String q,
                                              @RequestParam(required = false) String status) {
        requireValidRange(page, size);
        Page<Case> result = service.page(page, size, normalizeQuery(q), normalizeStatus(status));
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(),
                result.getNumber() + 1, result.getSize(), result.getTotalPages()));
    }

    /** 归档导出：按数据库页流式写出案件摘要；时间线通过独立分页资源读取。 */
    @RequireRole({"admin", "analyst"})
    @GetMapping("/incidents/export")
    public void export(@RequestParam(defaultValue = "10000") int limit,
                       HttpServletResponse response) throws IOException {
        if (limit < 1 || limit > EXPORT_MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + EXPORT_MAX_LIMIT);
        }
        long total = service.count();
        if (total > limit) {
            throw ApiException.of(413, "export exceeds the requested limit; narrow the query before exporting");
        }

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cases.json\"");
        var writer = response.getWriter();
        writer.write('[');
        boolean first = true;
        int exported = 0;
        int page = 1;
        while (exported < total && exported < limit) {
            int batchSize = Math.min(EXPORT_BATCH_SIZE, limit - exported);
            Page<Case> result = service.page(page, batchSize, "", "");
            if (result.isEmpty()) {
                break;
            }
            for (Case incident : result.getContent()) {
                if (!first) {
                    writer.write(',');
                }
                // Serialize one row at a time; writeValue(Writer, ...) closes
                // the generator (and therefore the servlet writer) by default.
                writer.write(objectMapper.writeValueAsString(incident));
                first = false;
                exported++;
            }
            page++;
        }
        writer.write(']');
        writer.flush();
    }

    @GetMapping("/incidents/{id}")
    public ApiResult<Map<String, Object>> get(@PathVariable String id) {
        Case c = service.get(id);
        return ApiResult.ok(Map.of("found", c != null, "case", c == null ? Map.of() : c));
    }

    /** 案件时间线：page 从 1 起（存储层为 0-based，这里做换算）。 */
    @GetMapping("/incidents/{id}/timeline")
    public ApiResult<PageResponse<TimelineEvent>> timeline(@PathVariable String id,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "100") int size) {
        requireValidRange(page, size);
        Map<String, Object> raw = service.timeline(id, page - 1, size);
        @SuppressWarnings("unchecked")
        List<TimelineEvent> items = (List<TimelineEvent>) raw.get("timeline");
        long total = raw.get("total") instanceof Number number ? number.longValue() : 0L;
        return ApiResult.ok(PageResponse.of(items, total, page, size));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("case:write")
    @AuditOperation(action = "UPDATE_INCIDENT_STATUS", target = "case")
    @PostMapping("/incidents/{id}/status")
    public ApiResult<Map<String, Object>> status(@PathVariable String id,
                                                 @RequestParam String status,
                                                 @RequestParam(required = false) String assignee) {
        return ApiResult.ok(service.setStatus(id, status, assignee));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("case:write")
    @PostMapping("/incidents/{id}/notes")
    public ApiResult<Map<String, Object>> note(@PathVariable String id,
                                               @RequestParam String author,
                                               @RequestParam String content,
                                               @RequestParam(required = false) String idempotencyKey) {
        return ApiResult.ok(service.addNote(id, author, content, idempotencyKey));
    }

    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats() {
        return ApiResult.ok(service.stats());
    }

    private void requireValidRange(int page, int size) {
        if (page < 1 || size < 1 || size > maxListSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数非法：page 从 1 起，size 上限 " + maxListSize);
        }
    }

    private static String normalizeQuery(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q length must not exceed 128 characters");
        }
        return normalized;
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim();
    }

}
