package com.socp.detect.web.api.controller;

import com.socp.detect.web.service.DetectionOutboxAdminService;
import com.socp.detect.web.api.request.OutboxDiscardRequest;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.data.outbox.DeadOutboxRecord;
import com.socp.platform.data.outbox.OutboxAdminResult;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.ratelimit.api.RateLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/outbox")
public class DetectionOutboxAdminController {

    private final DetectionOutboxAdminService service;

    public DetectionOutboxAdminController(DetectionOutboxAdminService service) {
        this.service = service;
    }

    @RequireRole({"admin"})
    @RateLimit(permits = 30, seconds = 60)
    @GetMapping("/detection-alerts/dead")
    public ApiResult<List<DeadOutboxRecord>> deadAlerts() {
        return ApiResult.ok(service.deadAlerts());
    }

    @RequireRole({"admin"})
    @RateLimit(permits = 30, seconds = 60)
    @GetMapping("/rule-changes/dead")
    public ApiResult<List<DeadOutboxRecord>> deadRuleChanges() {
        return ApiResult.ok(service.deadRuleChanges());
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "REQUEUE_DEAD_DETECTION_ALERT", target = "detection_alert_outbox")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/detection-alerts/{id}/requeue")
    public ApiResult<OutboxAdminResult> requeueAlert(@PathVariable String id) {
        return ApiResult.ok(service.requeueAlert(id));
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "REQUEUE_DEAD_RULE_CHANGE", target = "rule_change_outbox")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/rule-changes/{id}/requeue")
    public ApiResult<OutboxAdminResult> requeueRuleChange(@PathVariable String id) {
        return ApiResult.ok(service.requeueRuleChange(id));
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "DISCARD_DEAD_DETECTION_ALERT", target = "detection_alert_outbox")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/detection-alerts/{id}/discard")
    public ApiResult<OutboxAdminResult> discardAlert(
            @PathVariable String id, @Valid @RequestBody OutboxDiscardRequest request) {
        return ApiResult.ok(service.discardAlert(id, request.reason()));
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "DISCARD_DEAD_RULE_CHANGE", target = "rule_change_outbox")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/rule-changes/{id}/discard")
    public ApiResult<OutboxAdminResult> discardRuleChange(
            @PathVariable String id, @Valid @RequestBody OutboxDiscardRequest request) {
        return ApiResult.ok(service.discardRuleChange(id, request.reason()));
    }
}
