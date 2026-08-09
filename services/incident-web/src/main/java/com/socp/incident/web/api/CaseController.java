package com.socp.incident.web.api;

import com.socp.incident.web.domain.Case;
import com.socp.incident.web.service.CaseService;
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
    @PostMapping("/incidents/from-alarm")
    public Map<String, Object> fromAlarm(@RequestBody Map<String, Object> alarm) {
        return service.fromAlarm(alarm);
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

    @PostMapping("/incidents/{id}/status")
    public Map<String, Object> status(@PathVariable String id,
                                       @RequestParam String status,
                                       @RequestParam(required = false) String assignee) {
        return service.setStatus(id, status, assignee);
    }

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
}
