package com.socp.alert.api.controller;

import com.socp.alert.api.request.AlarmAssignmentRequest;
import com.socp.alert.api.request.AlarmNoteRequest;
import com.socp.alert.api.request.AlarmStatusRequest;
import com.socp.alert.api.request.AlarmTagRequest;
import com.socp.alert.service.AlarmDispositionService;
import com.socp.alert.service.AlarmService;

import com.socp.platform.error.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.error.api.ApiResult;

/**
 * 告警处置 API（工单化）：状态流转 / 分配 / 备注。
 * 挂载到告警资源下：/api/alarms/{id}/disposition
 */
@RestController
@RequestMapping("/api/alarms/{id}")
public class AlarmDispositionController {

    private final AlarmService alarmService;
    private final AlarmDispositionService disp;

    public AlarmDispositionController(AlarmService alarmService, AlarmDispositionService disp) {
        this.alarmService = alarmService;
        this.disp = disp;
    }

    @GetMapping("/disposition")
    public ApiResult<AlarmDispositionService.Disposition> get(@PathVariable String id) {
        alarmService.get(id); // 校验存在
        return ApiResult.ok(disp.get(id));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @PutMapping("/status")
    public ApiResult<AlarmDispositionService.Disposition> setStatus(@PathVariable String id, @Valid @RequestBody AlarmStatusRequest body) {
        alarmService.get(id);
        return ApiResult.ok(disp.setStatus(id, body.status()));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @PostMapping("/assign")
    public ApiResult<AlarmDispositionService.Disposition> assign(@PathVariable String id, @Valid @RequestBody AlarmAssignmentRequest body) {
        alarmService.get(id);
        String assignee = body.assignee();
        if (assignee == null || assignee.isBlank()) throw ApiException.badRequest("assignee 必填");
        return ApiResult.ok(disp.assign(id, assignee));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @PostMapping("/notes")
    public ApiResult<AlarmDispositionService.Disposition> addNote(@PathVariable String id, @Valid @RequestBody AlarmNoteRequest body,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        alarmService.get(id);
        String author = body.author() == null || body.author().isBlank() ? "operator" : body.author();
        return ApiResult.ok(disp.addNote(id, author, body.content(), idempotencyKey));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @PostMapping("/tags")
    public ApiResult<AlarmDispositionService.Disposition> addTag(@PathVariable String id,
                                                       @Valid @RequestBody AlarmTagRequest body) {
        alarmService.get(id);
        return ApiResult.ok(disp.addTag(id, body.tag()));
    }
}
