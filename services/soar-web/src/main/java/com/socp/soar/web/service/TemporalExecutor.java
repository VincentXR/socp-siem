package com.socp.soar.web.service;

import com.socp.platform.tenant.TenantContext;
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

    public TemporalExecutor(WorkflowClient workflowClient,
                            @org.springframework.beans.factory.annotation.Value("${socp.temporal.enabled:true}") boolean enabled,
                            @org.springframework.beans.factory.annotation.Value("${socp.temporal.target:localhost:7233}") String target) {
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
        Map<String, Object> tenantAlarm = new LinkedHashMap<>(alarm);
        String tenant = TenantContext.get();
        tenantAlarm.putIfAbsent("tenantId", tenant == null || tenant.isBlank() ? "default" : tenant);
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
}
