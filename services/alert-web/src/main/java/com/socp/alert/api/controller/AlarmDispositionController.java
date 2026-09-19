package com.socp.alert.api.controller;

import com.socp.alert.api.request.AlarmAssignmentRequest;
import com.socp.alert.api.request.AlarmNoteRequest;
import com.socp.alert.api.request.AlarmStatusRequest;
import com.socp.alert.api.request.AlarmTagRequest;
import com.socp.alert.service.AlarmDispositionService;
import com.socp.alert.service.AlarmService;

import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
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
 * 挂载到告警资源下，与告警族其它控制器保持 canonical(v1) + legacy 双挂。
 */
@RestController
@RequestMapping({"/api/v1/alarms/{id}", "/api/alarms/{id}"})
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
    @AuditOperation(action = "UPDATE_ALARM_STATUS", target = "t_alarm_disposition")
    @PutMapping("/status")
    public ApiResult<AlarmDispositionService.Disposition> setStatus(@PathVariable String id, @Valid @RequestBody AlarmStatusRequest body) {
        alarmService.get(id);
        return ApiResult.ok(disp.setStatus(id, body.status()));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @AuditOperation(action = "ASSIGN_ALARM", target = "t_alarm_disposition")
    @PostMapping("/assign")
    public ApiResult<AlarmDispositionService.Disposition> assign(@PathVariable String id, @Valid @RequestBody AlarmAssignmentRequest body) {
        alarmService.get(id);
        String assignee = body.assignee();
        if (assignee == null || assignee.isBlank()) throw ApiException.badRequest("assignee 必填");
        return ApiResult.ok(disp.assign(id, assignee));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @AuditOperation(action = "ADD_ALARM_NOTE", target = "t_alarm_disposition")
    @PostMapping("/notes")
    public ApiResult<AlarmDispositionService.Disposition> addNote(@PathVariable String id, @Valid @RequestBody AlarmNoteRequest body,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        alarmService.get(id);
        return ApiResult.ok(disp.addNote(id, DispositionActor.resolve(body.author()), body.content(), idempotencyKey));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @AuditOperation(action = "ADD_ALARM_TAG", target = "t_alarm_disposition")
    @PostMapping("/tags")
    public ApiResult<AlarmDispositionService.Disposition> addTag(@PathVariable String id,
                                                       @Valid @RequestBody AlarmTagRequest body) {
        alarmService.get(id);
        return ApiResult.ok(disp.addTag(id, body.tag()));
    }
}
