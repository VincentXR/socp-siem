package com.socp.soar.web.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.Map;

/**
 * 剧本编排 Workflow——接收 {@link PlaybookExecRequest}，按动作列表编排执行。
 * 语义与进程内 {@code PlaybookExecutor.run()} 一致：主动作失败阻断后续主动作、
 * 仅执行补偿动作（前缀"补偿:"），末态映射 SUCCESS / COMPENSATING / FAILED。
 */
@WorkflowInterface
public interface PlaybookWorkflow {

    String TASK_QUEUE = "SOCP_SOAR_TASK_QUEUE";

    @WorkflowMethod
    Map<String, Object> executePlaybook(PlaybookExecRequest request);
}
