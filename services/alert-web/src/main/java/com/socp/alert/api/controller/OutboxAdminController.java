package com.socp.alert.api.controller;

import com.socp.alert.domain.OutboxReplayResult;
import com.socp.alert.api.request.OutboxDiscardRequest;
import com.socp.alert.service.OutboxReplayService;

import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.data.outbox.DeadOutboxRecord;
import com.socp.platform.data.outbox.OutboxAdminResult;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.ratelimit.api.RateLimit;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

/** Privileged, auditable recovery endpoints for terminal durable deliveries. */
@RestController
@RequestMapping("/api/admin/outbox")
public class OutboxAdminController {

    private final OutboxReplayService replayService;

    public OutboxAdminController(OutboxReplayService replayService) {
        this.replayService = replayService;
    }

    @RequireRole({"admin"})
    @RateLimit(permits = 30, seconds = 60)
    @GetMapping("/alarm-events/dead")
    public ApiResult<java.util.List<DeadOutboxRecord>> deadAlarmEvents() {
        return ApiResult.ok(replayService.deadAlarmEvents());
    }

    @RequireRole({"admin"})
    @RateLimit(permits = 30, seconds = 60)
    @GetMapping("/alarm-deliveries/dead")
    public ApiResult<java.util.List<DeadOutboxRecord>> deadAlarmDeliveries() {
        return ApiResult.ok(replayService.deadAlarmDeliveries());
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "REQUEUE_DEAD_ALARM_EVENT", target = "outbox_event")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/alarm-events/{id}/requeue")
    public ApiResult<OutboxReplayResult> requeueAlarmEvent(@PathVariable String id) {
        return ApiResult.ok(replayService.requeueAlarmEvent(id));
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "REQUEUE_DEAD_ALARM_DELIVERY", target = "alarm_delivery")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/alarm-deliveries/{id}/requeue")
    public ApiResult<OutboxReplayResult> requeueAlarmDelivery(@PathVariable String id) {
        return ApiResult.ok(replayService.requeueAlarmDelivery(id));
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "DISCARD_DEAD_ALARM_EVENT", target = "outbox_event")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/alarm-events/{id}/discard")
    public ApiResult<OutboxAdminResult> discardAlarmEvent(
            @PathVariable String id, @Valid @RequestBody OutboxDiscardRequest request) {
        return ApiResult.ok(replayService.discardAlarmEvent(id, request.reason()));
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "DISCARD_DEAD_ALARM_DELIVERY", target = "alarm_delivery")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/alarm-deliveries/{id}/discard")
    public ApiResult<OutboxAdminResult> discardAlarmDelivery(
            @PathVariable String id, @Valid @RequestBody OutboxDiscardRequest request) {
        return ApiResult.ok(replayService.discardAlarmDelivery(id, request.reason()));
    }
}
