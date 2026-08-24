package com.socp.soar.web.temporal;

import com.socp.soar.web.model.PlaybookActionStatus;
import com.socp.soar.web.model.PlaybookActionType;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 剧本编排 Workflow 实现。
 *
 * <p>与进程内 {@code PlaybookExecutor.run()} 逐动作循环保持同一套状态机语义：
 * 主动作失败 → 阻断后续主动作、仅执行补偿动作；末态 SUCCESS / SIMULATED /
 * COMPENSATING / FAILED。
 * 每个动作由 Activity 执行（内部含重试），保证 Temporal 模式与进程内模式行为一致。
 */
public class PlaybookWorkflowImpl implements PlaybookWorkflow {

    private final PlaybookActivity activity = Workflow.newActivityStub(PlaybookActivity.class,
            ActivityOptions.newBuilder()
                    // webhook 3s×3 尝试 + 调度余量；本地切片足够，Temporal 侧超时可放宽
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    @Override
    public Map<String, Object> executePlaybook(PlaybookExecRequest req) {
        List<Map<String, Object>> results = new ArrayList<>();
        boolean previousFailed = false;
        int retryCount = 0;
        String firstError = null;
        boolean compensating = false;

        int actionIndex = 0;
        for (String action : req.actions()) {
            Map<String, Object> r = activity.executeAction(action, req.alarm(), previousFailed, actionIndex++);
            results.add(r);
            if (PlaybookActionType.compensation(action)) {
                previousFailed = false;
                compensating = true;
            } else {
                boolean ok = PlaybookActionStatus.isSuccessful(String.valueOf(r.get("status")));
                if (!ok) {
                    previousFailed = true;
                    Object at = r.get("attempts");
                    retryCount += at instanceof Number n ? n.intValue() : 1;
                    if (firstError == null) {
                        firstError = String.valueOf(r.getOrDefault("error", "action failed"));
                    }
                } else {
                    previousFailed = false;
                }
            }
        }

        boolean anyFailed = results.stream()
                .anyMatch(r -> PlaybookActionStatus.isFailed(String.valueOf(r.get("status"))));
        boolean anySimulated = results.stream()
                .anyMatch(r -> PlaybookActionStatus.SIMULATED.wireValue().equals(r.get("status")));
        String execStatus = anyFailed
                ? (compensating ? "COMPENSATING" : "FAILED")
                : (anySimulated ? "SIMULATED" : "SUCCESS");

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("executionId", "EXEC-" + Workflow.randomUUID().toString().substring(0, 8).toUpperCase());
        exec.put("playbookId", req.playbookId());
        exec.put("playbook", req.playbookName());
        exec.put("trigger", req.trigger());
        exec.put("status", execStatus);
        exec.put("retryCount", retryCount);
        if (firstError != null) exec.put("error", firstError);
        exec.put("results", results);
        // Temporal Workflow 内禁止非确定性时间源，用 Workflow.currentTimeMillis()
        exec.put("ts", java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString());
        exec.put("executor", "temporal");
        return exec;
    }
}
