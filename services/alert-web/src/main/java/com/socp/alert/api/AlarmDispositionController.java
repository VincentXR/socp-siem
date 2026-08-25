package com.socp.alert.api;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.socp.alert.api.AlarmAssignmentRequest;
import com.socp.alert.api.AlarmNoteRequest;
import com.socp.alert.api.AlarmStatusRequest;
import com.socp.platform.error.ApiException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.RequireRole;

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
    public AlarmDispositionService.Disposition get(@PathVariable String id) {
        alarmService.get(id); // 校验存在
        return disp.get(id);
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/status")
    public AlarmDispositionService.Disposition setStatus(@PathVariable String id, @Valid @RequestBody AlarmStatusRequest body) {
        alarmService.get(id);
        return disp.setStatus(id, body.status());
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/assign")
    public AlarmDispositionService.Disposition assign(@PathVariable String id, @Valid @RequestBody AlarmAssignmentRequest body) {
        alarmService.get(id);
        String assignee = body.assignee();
        if (assignee == null || assignee.isBlank()) throw ApiException.badRequest("assignee 必填");
        return disp.assign(id, assignee);
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/notes")
    public AlarmDispositionService.Disposition addNote(@PathVariable String id, @Valid @RequestBody AlarmNoteRequest body) {
        alarmService.get(id);
        String author = body.author() == null || body.author().isBlank() ? "operator" : body.author();
        return disp.addNote(id, author, body.content());
    }
}
