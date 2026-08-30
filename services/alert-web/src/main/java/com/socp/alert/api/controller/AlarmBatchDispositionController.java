package com.socp.alert.api.controller;

import com.socp.alert.api.request.AlarmBatchDispositionRequest;
import com.socp.alert.service.AlarmDispositionService;
import com.socp.alert.service.AlarmService;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Bounded batch triage boundary for analyst workflows. */
@RestController
@RequestMapping({"/api/v1/alarms", "/api/alarms"})
public class AlarmBatchDispositionController {

    private final AlarmService alarmService;
    private final AlarmDispositionService dispositionService;

    public AlarmBatchDispositionController(AlarmService alarmService,
                                            AlarmDispositionService dispositionService) {
        this.alarmService = alarmService;
        this.dispositionService = dispositionService;
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @AuditOperation(action = "BATCH_UPDATE_ALARM_DISPOSITION", target = "t_alarm_disposition")
    @PostMapping("/batch/disposition")
    public ApiResult<Map<String, Object>> update(@Valid @RequestBody AlarmBatchDispositionRequest request) {
        // Validate every ID through the tenant-scoped AlarmService before
        // creating a disposition row.  This prevents a typo from silently
        // materializing state for an alarm that does not exist.
        request.alarmIds().forEach(alarmService::get);
        return ApiResult.ok(dispositionService.batchUpdate(
                request.alarmIds(), request.status(), request.assignee(), request.reason()));
    }
}
