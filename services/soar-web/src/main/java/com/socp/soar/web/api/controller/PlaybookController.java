package com.socp.soar.web.api.controller;

import com.socp.soar.web.api.request.AlarmEvaluationRequest;
import com.socp.soar.web.api.request.CreatePlaybookRequest;
import com.socp.soar.web.api.request.PlaybookExecutionRequest;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.persistence.store.PlaybookStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.security.RequireRole;
import com.socp.soar.web.service.AlarmEvaluationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import com.socp.soar.web.service.ApprovalService;

/**
 * SOAR 剧本 API：CRUD + 启停。剧本元数据持久化；执行由
 * {@code PlaybookExecutor} 在 Temporal 与本地补偿模式之间分发。
 */
@RestController
@RequestMapping("/api/v1/playbooks")
public class PlaybookController {

    private final PlaybookStore store;
    private final com.socp.soar.web.service.PlaybookExecutor executor;
    private final AlarmEvaluationService evaluationService;
    @Autowired(required = false)
    private ApprovalService approvalService;

    public PlaybookController(PlaybookStore store, com.socp.soar.web.service.PlaybookExecutor executor,
                              AlarmEvaluationService evaluationService) {
        this.store = store;
        this.executor = executor;
        this.evaluationService = evaluationService;
    }

    @GetMapping
    public List<Playbook> list() {
        return store.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping
    public Playbook create(@Valid @RequestBody CreatePlaybookRequest req) {
        Playbook pb = Playbook.create(req.name(), req.trigger(), req.actions(), req.enabled());
        return store.save(pb);
    }

    @GetMapping("/{id}")
    public Playbook get(@PathVariable String id) {
        return store.get(id);
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/{id}/toggle")
    public Playbook toggle(@PathVariable String id) {
        return store.toggle(id);
    }

    /** 评估并编排执行：接收告警，命中启用的剧本触发条件则执行动作。 */
    @com.socp.platform.auth.security.RequireService
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@Valid @RequestBody AlarmEvaluationRequest alarm) {
        try {
            return evaluationService.evaluate(alarm.asMap());
        } catch (AlarmEvaluationService.EvaluationInProgressException inProgress) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, inProgress.getMessage(), inProgress);
        }
    }

    /** 手动触发指定剧本执行（忽略触发条件，直接跑 actions）。 */
    @RequireRole({"admin", "analyst"})
    @com.socp.platform.auth.security.RequirePermission("soar:execute")
    @PostMapping("/{id}/execute")
    public Map<String, Object> execute(@PathVariable String id,
                                       @RequestBody(required = false) PlaybookExecutionRequest request) {
        Map<String, Object> context = request == null ? Map.of() : request.context();
        validateMap(context, "context");
        if (approvalService != null && approvalService.requiresApproval(id)) {
            String requestedBy = String.valueOf(context.getOrDefault("requestedBy", "operator"));
            String reason = String.valueOf(context.getOrDefault("reason", "manual high-risk action"));
            return approvalService.request(id, context, requestedBy, reason);
        }
        return executor.runById(id, context);
    }

    @com.socp.platform.auth.security.RequirePermission("soar:approve")
    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals() {
        return approvalService == null ? List.of() : approvalService.list();
    }

    @RequireRole("admin")
    @com.socp.platform.auth.security.RequirePermission("soar:approve")
    @PostMapping("/approvals/{approvalId}/approve")
    public Map<String, Object> approve(@PathVariable String approvalId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        if (approvalService == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "approval service is unavailable");
        String approver = body == null ? "operator" : String.valueOf(body.getOrDefault("approver", "operator"));
        String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        return approvalService.approve(approvalId, approver, reason);
    }

    @RequireRole("admin")
    @com.socp.platform.auth.security.RequirePermission("soar:execute")
    @PostMapping("/approvals/{approvalId}/execute")
    public Map<String, Object> executeApproved(@PathVariable String approvalId) {
        if (approvalService == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "approval service is unavailable");
        return approvalService.execute(approvalId);
    }

    /** 执行历史（最近 200 条）。 */
    @com.socp.platform.auth.security.RequirePermission("soar:execute")
    @GetMapping("/executions")
    public List<Map<String, Object>> executions() {
        return executor.executions();
    }

    private static void validateMap(Map<String, Object> body, String name) {
        if (body == null) return;
        if (body.size() > 128) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    name + " contains too many fields");
        }
        int approxBytes = body.toString().length();
        if (approxBytes > 256 * 1024) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    name + " exceeds the 256 KiB limit");
        }
    }
}
