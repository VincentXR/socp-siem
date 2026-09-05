package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.config.TemporalProperties;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.temporal.request.PlaybookExecRequest;
import com.socp.soar.web.temporal.PlaybookWorkflow;
import com.socp.soar.web.temporal.v2.SoarV2Workflow;
import com.socp.soar.web.temporal.v2.SoarV2WorkflowRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
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
    private final String target;
    /** 可用性缓存：连接探测有 2s 网络等待，5s 内复用上次结果，避免每次编排都阻塞。 */
    private volatile Boolean cachedAvailable;
    private volatile long cachedAt;

    public enum V2WorkflowState { OPEN, CLOSED, UNKNOWN }

    @org.springframework.beans.factory.annotation.Autowired
    public TemporalExecutor(WorkflowClient workflowClient,
                            TemporalProperties properties) {
        this.workflowClient = workflowClient;
        this.enabled = properties.isEnabled();
        this.target = properties.getTarget();
    }

    public TemporalExecutor(WorkflowClient workflowClient, boolean enabled, String target) {
        this.workflowClient = workflowClient;
        this.enabled = enabled;
        this.target = target;
    }

    /** Temporal 可用性探测：开关关闭或连不上（blockUntilConnected）都视为不可用。 */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        if (cachedAvailable != null && System.currentTimeMillis() - cachedAt < 5_000L) {
            return cachedAvailable;
        }
        boolean ok;
        try {
            // 真实连接探测：TCP 连 Temporal 服务端（grpc 调用可能内部等待，TCP 探测 2s 内出结果）
            String hp = target.trim();
            int idx = hp.indexOf(':');
            String host = idx > 0 ? hp.substring(0, idx) : hp;
            int port = idx > 0 ? Integer.parseInt(hp.substring(idx + 1)) : 7233;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(host, port), 2000);
                ok = true;
            }
        } catch (Exception e) {
            log.debug("Temporal unavailable; durable V2 dispatch remains queued: {}", e.getMessage());
            ok = false;
        }
        cachedAvailable = ok;
        cachedAt = System.currentTimeMillis();
        if (!ok) {
            log.warn("Temporal unavailable; durable V2 dispatch remains queued (no repeated probe for 5s)");
        }
        return ok;
    }

    /** 用 Temporal Workflow 执行剧本，返回与进程内结构一致的执行结果。 */
    public Map<String, Object> run(Playbook pb, Map<String, Object> alarm) {
        Map<String, Object> tenantAlarm = new LinkedHashMap<>(alarm);
        String tenant = TenantContext.get();
        if (tenant == null || tenant.isBlank()) {
            Object carried = tenantAlarm.get("tenantId");
            if (carried == null) carried = tenantAlarm.get("tenant_id");
            tenant = carried == null ? TenantContext.require() : String.valueOf(carried).trim();
        }
        if (!TenantContext.isValid(tenant)) throw new IllegalArgumentException("invalid playbook tenant");
        tenantAlarm.put("tenantId", tenant);
        tenantAlarm.putIfAbsent("playbookId", pb.id());
        PlaybookExecRequest req = new PlaybookExecRequest(
                pb.id(), pb.name(), pb.trigger(), pb.actions(),
                java.util.Collections.unmodifiableMap(tenantAlarm));
        PlaybookWorkflow stub = workflowClient.newWorkflowStub(PlaybookWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("playbook-" + pb.id() + "-" + UUID.randomUUID())
                        .setTaskQueue(PlaybookWorkflow.TASK_QUEUE)
                        .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
                        .build());
        log.info("剧本 {} 提交 Temporal 编排（workflowId={}）", pb.id(), "playbook-" + pb.id());
        return stub.executePlaybook(req);
    }

    /** Start a V2 workflow asynchronously; the HTTP transaction never waits for completion. */
    public WorkflowExecution startV2(SoarV2WorkflowRequest request, String workflowId) {
        if (!isAvailable()) {
            throw new IllegalStateException("Temporal is not available; V2 runs stay in the outbox");
        }
        SoarV2Workflow stub = workflowClient.newWorkflowStub(SoarV2Workflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(SoarV2Workflow.TASK_QUEUE)
                        // The published definition enforces a deterministic
                        // 1 second..30 day execution deadline.  Temporal's
                        // outer timeout must be slightly larger so a valid
                        // 30-day run is not killed at the historical 24-hour
                        // default before the workflow can project its own
                        // EXECUTION_TIMEOUT outcome.
                        .setWorkflowExecutionTimeout(Duration.ofDays(31))
                        .build());
        return WorkflowClient.start(stub::execute, request);
    }

    /** Send a durable cancellation signal to a running V2 workflow. */
    public void cancelV2(String workflowId) {
        SoarV2Workflow stub = workflowClient.newWorkflowStub(SoarV2Workflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId)
                        .setTaskQueue(SoarV2Workflow.TASK_QUEUE).build());
        stub.cancel();
    }

    public void decideV2(String workflowId, boolean approve) {
        SoarV2Workflow stub = workflowClient.newWorkflowStub(SoarV2Workflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId)
                        .setTaskQueue(SoarV2Workflow.TASK_QUEUE).build());
        if (approve) stub.approve();
        else stub.reject();
    }

    /** Deliver a gate-scoped decision; stale signals for a prior node are
     * ignored by the workflow instead of changing the next gate's outcome. */
    public void decideGateV2(String workflowId, boolean approve, String approvalKey, boolean expired) {
        SoarV2Workflow stub = workflowClient.newWorkflowStub(SoarV2Workflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId)
                        .setTaskQueue(SoarV2Workflow.TASK_QUEUE).build());
        if (expired) stub.expireGate(approvalKey);
        else if (approve) stub.approveGate(approvalKey);
        else stub.rejectGate(approvalKey);
    }

    public void completeManualTask(String workflowId, String inputJson) {
        SoarV2Workflow stub = workflowClient.newWorkflowStub(SoarV2Workflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId)
                        .setTaskQueue(SoarV2Workflow.TASK_QUEUE).build());
        stub.completeManualTask(inputJson == null ? "{}" : inputJson);
    }

    public void completeManualTaskForNode(String workflowId, String nodeId, String inputJson) {
        SoarV2Workflow stub = workflowClient.newWorkflowStub(SoarV2Workflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId)
                        .setTaskQueue(SoarV2Workflow.TASK_QUEUE).build());
        stub.completeManualTaskForNode(nodeId, inputJson == null ? "{}" : inputJson);
    }

    public void resolveUnknown(String workflowId, String nodeId, String resolution,
                               String evidence, String reason) {
        SoarV2Workflow stub = workflowClient.newWorkflowStub(SoarV2Workflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId)
                        .setTaskQueue(SoarV2Workflow.TASK_QUEUE).build());
        stub.resolveUnknown(nodeId, resolution, evidence, reason);
    }

    /**
     * Describe a V2 workflow for projection recovery.  A failed describe is
     * deliberately UNKNOWN rather than CLOSED: recovery must never mark a run
     * terminal while Temporal itself is unreachable.
     */
    public V2WorkflowState describeV2(String workflowId) {
        if (workflowId == null || workflowId.isBlank() || !enabled) return V2WorkflowState.UNKNOWN;
        try {
            WorkflowExecutionDescription description = workflowClient
                    .newUntypedWorkflowStub(workflowId).describe();
            String status = description.getStatus() == null ? "" : description.getStatus().name();
            return "WORKFLOW_EXECUTION_STATUS_RUNNING".equals(status)
                    || "WORKFLOW_EXECUTION_STATUS_PAUSED".equals(status)
                    ? V2WorkflowState.OPEN : V2WorkflowState.CLOSED;
        } catch (RuntimeException failure) {
            log.debug("Unable to describe Temporal workflow {} during projection recovery: {}",
                    workflowId, failure.getMessage());
            return V2WorkflowState.UNKNOWN;
        }
    }
}
