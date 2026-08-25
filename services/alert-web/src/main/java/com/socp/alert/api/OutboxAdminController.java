package com.socp.alert.api;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.socp.platform.audit.AuditOperation;
import com.socp.platform.auth.RequireRole;
import com.socp.platform.error.ApiResult;
import com.socp.platform.ratelimit.RateLimit;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Privileged, auditable recovery endpoints for terminal durable deliveries. */
@RestController
@RequestMapping("/api/admin/outbox")
public class OutboxAdminController {

    private final OutboxReplayService replayService;

    public OutboxAdminController(OutboxReplayService replayService) {
        this.replayService = replayService;
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
}
