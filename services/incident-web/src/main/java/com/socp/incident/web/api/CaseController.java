package com.socp.incident.web.api;

import com.socp.incident.web.domain.Case;
import com.socp.incident.web.service.CaseService;
import com.socp.platform.audit.AuditOperation;
import com.socp.platform.auth.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

import java.util.List;
import java.util.Map;

/**
 * 案件与调查 REST API（context-path /incident-web）。
 * 建案/归并、列表、时间线、状态流转、备注。
 */
@RestController
@RequestMapping("/api/v1")
public class CaseController {

    private final CaseService service;

    public CaseController(CaseService service) {
        this.service = service;
    }

    /** 由告警自动建案/归并（alert-web 创建告警时调用，或 SOAR 触发）。 */
    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "CREATE_INCIDENT", target = "case")
    @PostMapping("/incidents/from-alarm")
    public Map<String, Object> fromAlarm(@Valid @RequestBody AlarmRequest alarm) {
        return service.fromAlarm(alarm.asMap());
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "CREATE_INCIDENT", target = "case")
    @PostMapping("/incidents")
    public Map<String, Object> create(@Valid @RequestBody CreateCaseRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "案件标题不能为空");
        }
        return Map.of("case", service.create(request.title(), request.entity(), request.severity(), request.assignee()));
    }

    @GetMapping("/incidents")
    public List<Case> list() {
        return service.list();
    }

    /** 归档导出：全部案件（含时间线）按 JSON 下载。 */
    @GetMapping("/incidents/export")
    public ResponseEntity<String> export() {
        String json = service.exportJson();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cases.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @GetMapping("/incidents/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Case c = service.get(id);
        return Map.of("found", c != null, "case", c == null ? Map.of() : c);
    }

    @GetMapping("/incidents/{id}/timeline")
    public Map<String, Object> timeline(@PathVariable String id) {
        Case c = service.get(id);
        return Map.of("caseId", id, "timeline", c == null ? List.of() : c.timeline());
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "UPDATE_INCIDENT_STATUS", target = "case")
    @PostMapping("/incidents/{id}/status")
    public Map<String, Object> status(@PathVariable String id,
                                       @RequestParam String status,
                                       @RequestParam(required = false) String assignee) {
        return service.setStatus(id, status, assignee);
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/incidents/{id}/notes")
    public Map<String, Object> note(@PathVariable String id,
                                     @RequestParam String author,
                                     @RequestParam String content) {
        return service.addNote(id, author, content);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    public record CreateCaseRequest(
            @NotBlank @Size(max = 256) String title,
            @Size(max = 256) String entity,
            @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|INFO") @Size(max = 32) String severity,
            @Size(max = 128) String assignee) {
    }

    public record AlarmRequest(
            @NotBlank @Size(max = 128) String id,
            @Size(max = 128) String ruleId,
            @Size(max = 256) String ruleName,
            @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|INFO") @Size(max = 32) String severity,
            @Size(max = 256) String entity,
            @Size(max = 4096) String message,
            @Size(max = 128) String mitre,
            @Size(max = 64) String occurredAt) {

        public Map<String, Object> asMap() {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            put(out, "id", id); put(out, "ruleId", ruleId); put(out, "ruleName", ruleName);
            put(out, "severity", severity); put(out, "entity", entity); put(out, "message", message);
            put(out, "mitre", mitre); put(out, "occurredAt", occurredAt);
            return out;
        }

        private static void put(Map<String, Object> out, String key, String value) {
            if (value != null && !value.isBlank()) out.put(key, value);
        }
    }
}
