package com.socp.soar.web.service;

import com.socp.soar.web.model.Playbook;
import com.socp.soar.web.temporal.PlaybookExecRequest;
import com.socp.soar.web.temporal.PlaybookWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Temporal 双模式分发器（2026-08-12）。
 *
 * <p>项目双模式铁律：Temporal 可达（7233 能列 namespace）就用 Workflow 编排；
 * 不可达（容器没起/重启中）自动回退进程内执行器，绝不因编排中间件故障拖垮告警响应。
 */
@Component
public class TemporalExecutor {

    private static final Logger log = LoggerFactory.getLogger(TemporalExecutor.class);

    private final WorkflowClient workflowClient;
    private final boolean enabled;
    /** 可用性缓存：连接探测有 3s 网络等待，30s 内复用上次结果，避免每次编排都阻塞。 */
    private volatile Boolean cachedAvailable;
    private volatile long cachedAt;

    public TemporalExecutor(WorkflowClient workflowClient,
                            @org.springframework.beans.factory.annotation.Value("${socp.temporal.enabled:true}") boolean enabled) {
        this.workflowClient = workflowClient;
        this.enabled = enabled;
    }

    /** Temporal 可用性探测：开关关闭或连不上（blockUntilConnected）都视为不可用。 */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        if (cachedAvailable != null && System.currentTimeMillis() - cachedAt < 30_000L) {
            return cachedAvailable;
        }
        boolean ok;
        try {
            // 真实 RPC 探测：GetSystemInfo 成功即服务端可达（失败抛 grpc 异常）
            workflowClient.getWorkflowServiceStubs().blockingStub()
                    .getSystemInfo(io.temporal.api.workflowservice.v1.GetSystemInfoRequest.newBuilder().build());
            ok = true;
        } catch (Exception e) {
            log.debug("Temporal 不可达，回退进程内执行器: {}", e.getMessage());
            ok = false;
        }
        cachedAvailable = ok;
        cachedAt = System.currentTimeMillis();
        if (!ok) {
            log.warn("Temporal 不可达，回退进程内执行器（30s 内不再重复探测）");
        }
        return ok;
    }

    /** 用 Temporal Workflow 执行剧本，返回与进程内结构一致的执行结果。 */
    public Map<String, Object> run(Playbook pb, Map<String, Object> alarm) {
        PlaybookExecRequest req = new PlaybookExecRequest(pb.id(), pb.name(), pb.trigger(), pb.actions(), alarm);
        PlaybookWorkflow stub = workflowClient.newWorkflowStub(PlaybookWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("playbook-" + pb.id() + "-" + UUID.randomUUID())
                        .setTaskQueue(PlaybookWorkflow.TASK_QUEUE)
                        .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
                        .build());
        log.info("剧本 {} 提交 Temporal 编排（workflowId={}）", pb.id(), "playbook-" + pb.id());
        return stub.executePlaybook(req);
    }
}
