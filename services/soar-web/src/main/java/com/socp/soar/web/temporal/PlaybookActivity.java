package com.socp.soar.web.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.Map;

/**
 * 剧本单动作执行 Activity——由 Workflow 编排调用。
 * 语义与进程内 {@code PlaybookExecutor.executeAction} 保持一致（含重试），
 * 由 Temporal Worker 侧 Spring 托管的实现注入 notify/incident/webhook 客户端。
 */
@ActivityInterface
public interface PlaybookActivity {

    @ActivityMethod
    Map<String, Object> executeAction(String action, Map<String, Object> alarm, boolean activeFailed);
}
