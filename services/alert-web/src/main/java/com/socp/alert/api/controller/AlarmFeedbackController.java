package com.socp.alert.api.controller;

import com.socp.alert.api.request.AlarmFeedbackRequest;
import com.socp.alert.service.AlarmFeedbackService;
import com.socp.alert.service.AlarmService;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Analyst feedback API for false positives and expiring rule exceptions. */
@RestController
@RequestMapping({"/api/v1/alarms/{id}/feedback", "/api/alarms/{id}/feedback"})
public class AlarmFeedbackController {

    private final AlarmService alarmService;
    private final AlarmFeedbackService feedbackService;

    public AlarmFeedbackController(AlarmService alarmService, AlarmFeedbackService feedbackService) {
        this.alarmService = alarmService;
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@PathVariable("id") String id) {
        alarmService.get(id);
        return ApiResult.ok(feedbackService.list(id));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("alarm:triage")
    @AuditOperation(action = "ADD_ALARM_FEEDBACK", target = "t_alarm_feedback")
    @PostMapping
    public ApiResult<Map<String, Object>> save(@PathVariable("id") String id,
                                                @Valid @RequestBody AlarmFeedbackRequest request) {
        alarmService.get(id);
        return ApiResult.ok(feedbackService.save(id, request.kind(), request.reason(),
                request.expiresAt(), request.actor()));
    }
}
