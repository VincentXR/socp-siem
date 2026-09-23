package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.config.TemporalProperties;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.temporal.request.PlaybookExecRequest;
import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import com.socp.soar.web.temporal.PlaybookWorkflow;
import com.socp.soar.web.temporal.SoarWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.errordetails.v1.NotFoundFailure;
import io.temporal.serviceclient.StatusUtils;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
 * Temporal 工作流分发器。SOAR 在中间件不可用时保留 durable outbox，
 * 不回退到进程内副作用执行；历史 playbook 调用保留独立的兼容入口。
 */
@Component
public class TemporalExecutor {

    // SOAR dispatch is fail-closed: an unavailable Temporal endpoint leaves the
    // durable outbox entry queued. The run(...) method below remains for
    // explicitly supported historical playbook execution.

    private static final Logger log = LoggerFactory.getLogger(TemporalExecutor.class);

    private final WorkflowClient workflowClient;
    private final boolean enabled;
    private final String target;
    /** 可用性缓存：连接探测有 2s 网络等待，5s 内复用上次结果，避免每次编排都阻塞。 */
    private volatile Boolean cachedAvailable;
    private volatile long cachedAt;

    public enum WorkflowState { OPEN, CLOSED, NOT_FOUND, UNKNOWN }

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
            log.debug("Temporal unavailable; SOAR durable dispatch remains queued: {}", e.getMessage());
            ok = false;
        }
        cachedAvailable = ok;
        cachedAt = System.currentTimeMillis();
        if (!ok) {
            log.warn("Temporal unavailable; SOAR durable dispatch remains queued (no repeated probe for 5s)");
        }
        return ok;
    }

    /** 用 Temporal Workflow 执行剧本，返回与进程内结构一致的执行结果。 */
    /** Execute a historical playbook through Temporal using startWorkflow(...). */
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

    /** Start a SOAR workflow asynchronously; the HTTP transaction never waits for completion. */
    public WorkflowExecution startWorkflow(SoarWorkflowRequest request, String workflowId) {
        if (!isAvailable()) {
            throw new IllegalStateException("Temporal is not available; SOAR runs stay in the outbox");
        }
        SoarWorkflow stub = workflowClient.newWorkflowStub(SoarWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setWorkflowIdReusePolicy(io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .setTaskQueue(SoarWorkflow.TASK_QUEUE)
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

    /** Send a durable cancellation signal to a running SOAR workflow. */
    public void cancelWorkflow(String workflowId) {
        workflowClient.getWorkflowServiceStubs().blockingStub()
                .withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS)
                .signalWorkflowExecution(SignalWorkflowExecutionRequest.newBuilder()
                        .setNamespace(workflowClient.getOptions().getNamespace())
                        .setIdentity(workflowClient.getOptions().getIdentity())
                        .setWorkflowExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                        .setRequestId(UUID.randomUUID().toString()).setSignalName("cancel").build());
    }

    public void decide(String workflowId, boolean approve) {
        SoarWorkflow stub = workflowClient.newWorkflowStub(SoarWorkflow.class, workflowId);
        if (approve) stub.approve();
        else stub.reject();
    }

    /** Deliver a gate-scoped decision; stale signals for a prior node are
     * ignored by the workflow instead of changing the next gate's outcome. */
    public void decideGate(String workflowId, boolean approve, String approvalKey, boolean expired) {
        SoarWorkflow stub = workflowClient.newWorkflowStub(SoarWorkflow.class, workflowId);
        if (expired) stub.expireGate(approvalKey);
        else if (approve) stub.approveGate(approvalKey);
        else stub.rejectGate(approvalKey);
    }

    public void completeManualTask(String workflowId, String inputJson) {
        SoarWorkflow stub = workflowClient.newWorkflowStub(SoarWorkflow.class, workflowId);
        stub.completeManualTask(inputJson == null ? "{}" : inputJson);
    }

    public void completeManualTaskForNode(String workflowId, String nodeId, String inputJson) {
        SoarWorkflow stub = workflowClient.newWorkflowStub(SoarWorkflow.class, workflowId);
        stub.completeManualTaskForNode(nodeId, inputJson == null ? "{}" : inputJson);
    }

    public void resolveUnknown(String workflowId, String nodeId, String resolution,
                               String evidence, String reason) {
        SoarWorkflow stub = workflowClient.newWorkflowStub(SoarWorkflow.class, workflowId);
        stub.resolveUnknown(nodeId, resolution, evidence, reason);
    }

    /**
     * Describe a SOAR workflow for projection recovery. A failed describe is
     * deliberately UNKNOWN rather than CLOSED: recovery must never mark a run
     * terminal while Temporal itself is unreachable.
     */
    public WorkflowState describeWorkflow(String workflowId) {
        if (workflowId == null || workflowId.isBlank() || !enabled) return WorkflowState.UNKNOWN;
        try {
            var description = workflowClient.getWorkflowServiceStubs().blockingStub()
                    .withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS)
                    .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(workflowClient.getOptions().getNamespace())
                            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId)).build());
            return switch (description.getWorkflowExecutionInfo().getStatus()) {
                case WORKFLOW_EXECUTION_STATUS_RUNNING, WORKFLOW_EXECUTION_STATUS_PAUSED,
                     WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW -> WorkflowState.OPEN;
                case WORKFLOW_EXECUTION_STATUS_COMPLETED, WORKFLOW_EXECUTION_STATUS_FAILED,
                     WORKFLOW_EXECUTION_STATUS_CANCELED, WORKFLOW_EXECUTION_STATUS_TERMINATED,
                     WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> WorkflowState.CLOSED;
                default -> WorkflowState.UNKNOWN;
            };
        } catch (StatusRuntimeException failure) {
            // Namespace errors, transport failures and untyped NOT_FOUND are
            // not evidence that this workflow is absent. A standby cluster's
            // negative response is likewise not authoritative during failover.
            NotFoundFailure missing = StatusUtils.getFailure(failure, NotFoundFailure.class);
            if (failure.getStatus().getCode() == Status.Code.NOT_FOUND && missing != null
                    && missing.getCurrentCluster().equals(missing.getActiveCluster())) {
                return WorkflowState.NOT_FOUND;
            }
            return WorkflowState.UNKNOWN;
        } catch (RuntimeException failure) {
            log.debug("Unable to describe Temporal workflow {} during projection recovery: {}",
                    workflowId, failure.getMessage());
            return WorkflowState.UNKNOWN;
        }
    }
}
