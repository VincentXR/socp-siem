package com.socp.alert;

import com.socp.platform.error.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @PutMapping("/status")
    public AlarmDispositionService.Disposition setStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        alarmService.get(id);
        return disp.setStatus(id, body.getOrDefault("status", ""));
    }

    @PostMapping("/assign")
    public AlarmDispositionService.Disposition assign(@PathVariable String id, @RequestBody Map<String, String> body) {
        alarmService.get(id);
        String assignee = body.get("assignee");
        if (assignee == null || assignee.isBlank()) throw ApiException.badRequest("assignee 必填");
        return disp.assign(id, assignee);
    }

    @PostMapping("/notes")
    public AlarmDispositionService.Disposition addNote(@PathVariable String id, @RequestBody Map<String, String> body) {
        alarmService.get(id);
        return disp.addNote(id, body.getOrDefault("author", "operator"), body.getOrDefault("content", ""));
    }
}
