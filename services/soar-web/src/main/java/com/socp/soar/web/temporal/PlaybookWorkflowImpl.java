package com.socp.soar.web.temporal;

import com.socp.soar.web.temporal.request.PlaybookExecRequest;

import com.socp.soar.web.service.LegacyPlaybookResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.LinkedHashMap;
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
        LegacyPlaybookResult result = new LegacyPlaybookResult();
        int actionIndex = 0;
        for (String action : req.actions()) {
            result.accept(action, activity.executeAction(action, req.alarm(), result.previousFailed(), actionIndex++));
        }

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("executionId", "EXEC-" + Workflow.randomUUID().toString().substring(0, 8).toUpperCase());
        exec.put("playbookId", req.playbookId());
        exec.put("playbook", req.playbookName());
        exec.put("trigger", req.trigger());
        result.appendTo(exec);
        // Temporal Workflow 内禁止非确定性时间源，用 Workflow.currentTimeMillis()
        exec.put("ts", java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString());
        exec.put("executor", "temporal");
        return exec;
    }
}
