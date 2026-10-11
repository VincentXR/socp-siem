package com.socp.soar.web.service;

import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.persistence.store.PlaybookStore;
import com.socp.soar.web.persistence.entity.ExecutionEntity;
import com.socp.soar.web.persistence.repository.ExecutionRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Historical linear-playbook admission, approval and execution records.
 * New graph runs enter the durable outbox path. Single-action compatibility
 * lives in {@link LegacyPlaybookActions}, independent of this orchestrator.
 */
@Service
public class PlaybookExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlaybookExecutor.class);

    private final PlaybookStore store;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Only used by direct unit-test construction; Spring production always injects JPA. */
    private final List<Map<String, Object>> transientExecutions = new CopyOnWriteArrayList<>();
    private final TemporalExecutor temporalExecutor;
    private final ExecutionRepository executionRepository;
    private final LegacyPlaybookActions actions;
    /** Lazy to avoid a constructor cycle: ApprovalService delegates approved execution here. */
    private ApprovalService approvalService;

    /** Compatibility constructor for isolated callers; Spring shares the action adapter. */
    public PlaybookExecutor(PlaybookStore store, NotifyClient notifyClient,
                            IncidentClient incidentClient, SocpHttpClient http,
                            TemporalExecutor temporalExecutor, ExecutionRepository executionRepository) {
        this(store, temporalExecutor, executionRepository,
                new LegacyPlaybookActions(new PlaybookActionHandlerRegistry(notifyClient, incidentClient, http),
                        new SoarRuntimeProperties(), null));
    }

    @Autowired
    public PlaybookExecutor(PlaybookStore store, TemporalExecutor temporalExecutor,
                            ExecutionRepository executionRepository, LegacyPlaybookActions actions) {
        this.store = store;
        this.temporalExecutor = temporalExecutor;
        this.executionRepository = executionRepository;
        this.actions = actions;
    }

    /** 按 ID 手动触发执行（忽略启用状态与触发条件）。 */
    public Map<String, Object> runById(String id, Map<String, Object> context) {
        return runByIdInternal(id, context, false);
    }

    /**
     * Entry point used only after a durable approval has been verified. It is
     * deliberately separate from {@link #runById(String, Map)} so callers
     * cannot bypass the policy by adding a flag to a request body.
     */
    public Map<String, Object> runApprovedById(String id, Map<String, Object> context) {
        return runByIdInternal(id, context, true);
    }

    private Map<String, Object> runByIdInternal(String id, Map<String, Object> context,
                                                  boolean approved) {
        Playbook pb = store.get(id);
        if (pb == null) {
            return Map.of("error", "playbook not found", "playbookId", id);
        }
        Map<String, Object> alarm = new LinkedHashMap<>(context == null ? Map.of() : context);
        alarm.putIfAbsent("ruleId", pb.trigger());
        alarm.putIfAbsent("severity", "HIGH");
        Object approvalId = alarm.get("approvalId");
        alarm.putIfAbsent("id", approvalId == null
                ? "manual-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                : "approval-" + approvalId);
        return run(pb, alarm, approved);
    }

    /** 评估并编排执行。返回本次触发的剧本与动作结果。 */
    public Map<String, Object> evaluate(Map<String, Object> alarm) {
        String ruleId = str(alarm, "ruleId");
        String severity = str(alarm, "severity").toUpperCase();
        int sevLevel = sevLevel(severity);
        List<Map<String, Object>> triggered = new ArrayList<>();
        for (Playbook pb : store.list()) {
            if (!pb.enabled()) continue;
            if (!matches(pb.trigger(), ruleId, severity, sevLevel)) continue;
            Map<String, Object> exec = run(pb, alarm, false);
            triggered.add(exec);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alarmId", alarm.get("id"));
        out.put("triggered", triggered.size());
        out.put("playbooks", triggered);
        return out;
    }

    private Map<String, Object> run(Playbook pb, Map<String, Object> alarm, boolean approved) {
        if (!approved && ApprovalPolicy.requiresApproval(pb)) {
            return requestApproval(pb, alarm);
        }
        // Historical playbook compatibility only: admission never enters this
        // synchronous path when Temporal is unavailable.
        if (temporalExecutor.isAvailable()) {
            Map<String, Object> exec = temporalExecutor.run(pb, alarm);
            recordExecution(exec);
            return exec;
        }
        LegacyPlaybookResult result = new LegacyPlaybookResult();
        Map<String, Object> actionAlarm = new LinkedHashMap<>(alarm);
        actionAlarm.putIfAbsent("playbookId", pb.id());
        int actionIndex = 0;
        for (String action : pb.actions()) {
            result.accept(action, executeAction(action, actionAlarm, result.previousFailed(), actionIndex++));
        }
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("executionId", "EXEC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        exec.put("playbookId", pb.id());
        exec.put("playbook", pb.name());
        exec.put("trigger", pb.trigger());
        result.appendTo(exec);
        exec.put("ts", Instant.now().toString());
        recordExecution(exec);
        return exec;
    }

    private Map<String, Object> requestApproval(Playbook playbook, Map<String, Object> alarm) {
        if (approvalService == null) {
            return Map.of("status", "APPROVAL_REQUIRED", "playbookId", playbook.id(),
                    "reason", "high-risk SOAR action requires human approval");
        }
        String requestedBy = str(alarm, "requestedBy");
        if (requestedBy.isBlank()) requestedBy = "system";
        String reason = str(alarm, "reason");
        if (reason.isBlank()) reason = "alarm-triggered high-risk action";
        Map<String, Object> requested = approvalService.request(playbook.id(), alarm, requestedBy, reason);
        Map<String, Object> out = new LinkedHashMap<>(requested);
        out.put("status", "APPROVAL_REQUIRED");
        out.put("playbookId", playbook.id());
        return out;
    }

    @Autowired(required = false)
    void setApprovalService(@Lazy ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /** 记录一次执行结果（进程内与 Temporal 模式共用，前端 /executions 可见）。 */
    private void recordExecution(Map<String, Object> exec) {
        if (executionRepository == null) {
            if (transientExecutions.size() >= 200) transientExecutions.remove(0);
            Map<String, Object> tenantExecution = new LinkedHashMap<>(exec);
            tenantExecution.put("tenantId", tenant());
            transientExecutions.add(tenantExecution);
            return;
        }
        try {
            ExecutionEntity row = new ExecutionEntity();
            row.setExecutionId(str(exec, "executionId"));
            row.setPlaybookId(str(exec, "playbookId"));
            row.setPlaybook(str(exec, "playbook"));
            row.setStatus(str(exec, "status"));
            row.setTrigger(str(exec, "trigger"));
            Object retries = exec.get("retryCount");
            row.setRetryCount(retries instanceof Number n ? n.intValue() : 0);
            row.setError(exec.get("error") == null ? null : String.valueOf(exec.get("error")));
            row.setResultsJson(MAPPER.writeValueAsString(exec.getOrDefault("results", List.of())));
            row.setTs(parseInstant(exec.get("ts")));
            row.setTenantId(tenant());
            executionRepository.save(row);
        } catch (com.fasterxml.jackson.core.JsonProcessingException
                 | org.springframework.dao.DataAccessException e) {
            // The action itself has already completed; expose persistence loss
            // loudly while keeping the response path available.
            log.warn("SOAR 执行记录持久化失败 executionId={}: {}", exec.get("executionId"), e.getMessage());
        }
    }

    /** Retained Java entry point for compatibility callers; no orchestration is involved. */
    public Map<String, Object> executeAction(String action, Map<String, Object> alarm, boolean activeFailed) {
        return actions.executeAction(action, alarm, activeFailed);
    }

    public Map<String, Object> executeAction(String action, Map<String, Object> alarm,
                                            boolean activeFailed, int actionIndex) {
        return actions.executeAction(action, alarm, activeFailed, actionIndex);
    }

    /**
     * 把一次服务调用的结果落进剧本执行记录。
     *
     * <p>失败原因会同时写进执行记录（前端可见）和日志（{@code socp-client} 已记 WARN），
     * 不再出现「剧本显示 failed，但没人知道为什么」。
     */
    private boolean matches(String trigger, String ruleId, String severity, int sevLevel) {
        if (trigger == null) return false;
        String t = trigger.toLowerCase();
        if (t.contains("定时") || t.contains("schedule")) return false; // 定时类由调度器触发，不在告警路径
        // 规则 ID 子串匹配
        if (ruleId != null && !ruleId.isBlank() && t.contains(ruleId.toLowerCase())) return true;
        // 严重级别匹配：触发含 ">= HIGH" 之类
        if (t.contains("severity") || t.contains("级别") || t.contains("高危")) {
            for (String lvl : new String[]{"CRITICAL", "HIGH", "MEDIUM", "LOW"}) {
                if (t.contains(lvl.toLowerCase())) {
                    return sevLevel >= sevLevel(lvl);
                }
            }
        }
        return false;
    }

    public List<Map<String, Object>> executions() {
        if (executionRepository == null) {
            String tenant = tenant();
            return transientExecutions.stream()
                    .filter(execution -> tenant.equals(execution.get("tenantId")))
                    .map(execution -> {
                        Map<String, Object> copy = new LinkedHashMap<>(execution);
                        copy.remove("tenantId");
                        return copy;
                    })
                    .toList();
        }
        return executionRepository.findTop200ByTenantIdOrderByTsDesc(tenant()).stream()
                .map(PlaybookExecutor::fromEntity)
                .toList();
    }

    private static int sevLevel(String s) {
        return switch (s) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private String tenant() {
        return TenantContext.require();
    }

    private static Map<String, Object> fromEntity(ExecutionEntity row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executionId", row.getExecutionId());
        out.put("playbookId", row.getPlaybookId());
        out.put("playbook", row.getPlaybook());
        out.put("status", row.getStatus());
        out.put("trigger", row.getTrigger());
        out.put("retryCount", row.getRetryCount());
        if (row.getError() != null) out.put("error", row.getError());
        try {
            out.put("results", MAPPER.readValue(row.getResultsJson(), new TypeReference<List<Map<String, Object>>>() {}));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidStoredResult) {
            out.put("results", List.of());
        }
        out.put("ts", row.getTs() == null ? null : row.getTs().toString());
        return out;
    }

    private static Instant parseInstant(Object value) {
        if (value == null) return Instant.now();
        try { return Instant.parse(String.valueOf(value)); }
        catch (java.time.format.DateTimeParseException invalid) { return Instant.now(); }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

}
