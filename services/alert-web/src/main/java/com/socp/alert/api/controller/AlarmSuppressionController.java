package com.socp.alert.api.controller;

import com.socp.alert.api.request.AlarmSuppressionRequest;
import com.socp.alert.service.AlarmSuppressionService;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Durable suppression windows: the analyst decision that outlives one alarm. */
@RestController
@RequestMapping({"/api/v1/suppressions", "/api/suppressions"})
public class AlarmSuppressionController {

    private final AlarmSuppressionService suppressionService;

    public AlarmSuppressionController(AlarmSuppressionService suppressionService) {
        this.suppressionService = suppressionService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(suppressionService.list());
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @AuditOperation(action = "RECORD_ALARM_SUPPRESSION", target = "t_alarm_suppression")
    @PostMapping
    public ApiResult<Map<String, Object>> record(@Valid @RequestBody AlarmSuppressionRequest request) {
        return ApiResult.ok(suppressionService.record(request.ruleId(), request.entity(),
                request.origin(), request.reason(), request.alarmId(),
                DispositionActor.resolve(request.actor()), request.windowSeconds(), request.ruleWide()));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @AuditOperation(action = "RELEASE_ALARM_SUPPRESSION", target = "t_alarm_suppression")
    @DeleteMapping
    public ApiResult<Void> release(@RequestParam String ruleId,
                                  @RequestParam(required = false) String entity) {
        suppressionService.release(ruleId, entity);
        return ApiResult.ok(null);
    }
}
