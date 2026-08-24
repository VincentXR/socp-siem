package com.socp.soar.web.service;

import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.SocpHttpClient;
import com.socp.soar.web.model.Playbook;
import com.socp.soar.web.model.PlaybookActionStatus;
import com.socp.soar.web.model.PlaybookActionType;
import com.socp.soar.web.store.PlaybookStore;
import com.socp.soar.web.store.ExecutionEntity;
import com.socp.soar.web.store.ExecutionRepository;
import com.socp.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 剧本执行器（SOAR 运行时）：收到告警后评估启用的剧本触发条件，命中则按 actions 执行。
 * 动作语义：
 *  - HTTP/URL 类 → 真实 webhook POST；
 *  - NOTIFY（通知） → 调 notify-web 告警通知；
 *  - CASE（建案）   → 调 incident-web 由告警自动建案；
 *  - TAG/演示动作  → 仅在显式开启 dry-run 时返回 simulated；
 *  - 未知动作      → 明确 failed，不允许隐式成功。
 */
@Service
public class PlaybookExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlaybookExecutor.class);

    private final PlaybookStore store;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Only used by direct unit-test construction; Spring production always injects JPA. */
    private final List<Map<String, Object>> transientExecutions = new CopyOnWriteArrayList<>();
    private final NotifyClient notifyClient;
    private final IncidentClient incidentClient;
    private final SocpHttpClient http;
    private final TemporalExecutor temporalExecutor;
    private final ExecutionRepository executionRepository;
    private final PlaybookActionHandlerRegistry handlerRegistry;

    /**
     * Dry-run actions are enabled for the local/integration demo, but they retain the
     * distinct SIMULATED terminal state. The prod profile forces this property off.
     */
    @Value("${socp.soar.simulation-enabled:false}")
    private boolean simulationEnabled;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    public PlaybookExecutor(PlaybookStore store, NotifyClient notifyClient,
                            IncidentClient incidentClient, SocpHttpClient http,
                            TemporalExecutor temporalExecutor, ExecutionRepository executionRepository) {
        this(store, notifyClient, incidentClient, http, temporalExecutor, executionRepository,
                new PlaybookActionHandlerRegistry(notifyClient, incidentClient, http));
    }

    @Autowired
    public PlaybookExecutor(PlaybookStore store, NotifyClient notifyClient,
                            IncidentClient incidentClient, SocpHttpClient http,
                            TemporalExecutor temporalExecutor, ExecutionRepository executionRepository,
                            PlaybookActionHandlerRegistry handlerRegistry) {
        this.store = store;
        this.notifyClient = notifyClient;
        this.incidentClient = incidentClient;
        this.http = http;
        this.temporalExecutor = temporalExecutor;
        this.executionRepository = executionRepository;
        this.handlerRegistry = handlerRegistry == null
                ? new PlaybookActionHandlerRegistry(notifyClient, incidentClient, http)
                : handlerRegistry;
    }

    /** 按 ID 手动触发执行（忽略启用状态与触发条件）。 */
    public Map<String, Object> runById(String id, Map<String, Object> context) {
        Playbook pb = store.get(id);
        if (pb == null) {
            return Map.of("error", "playbook not found", "playbookId", id);
        }
        Map<String, Object> alarm = new LinkedHashMap<>(context);
        alarm.putIfAbsent("ruleId", pb.trigger());
        alarm.putIfAbsent("severity", "HIGH");
        alarm.putIfAbsent("id", "manual-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        return run(pb, alarm);
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
            Map<String, Object> exec = run(pb, alarm);
            triggered.add(exec);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alarmId", alarm.get("id"));
        out.put("triggered", triggered.size());
        out.put("playbooks", triggered);
        return out;
    }

    private static final int MAX_ATTEMPTS = 3; // 每个动作最多尝试次数（重试 2 次）

    private Map<String, Object> run(Playbook pb, Map<String, Object> alarm) {
        // 双模式（2026-08-12）：Temporal 可用走分布式编排，不可用回退进程内
        if (temporalExecutor.isAvailable()) {
            Map<String, Object> exec = temporalExecutor.run(pb, alarm);
            recordExecution(exec);
            return exec;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        boolean previousFailed = false;
        int retryCount = 0;
        String firstError = null;
        boolean compensating = false;
        Map<String, Object> actionAlarm = new LinkedHashMap<>(alarm);
        actionAlarm.putIfAbsent("playbookId", pb.id());
        int actionIndex = 0;
        for (String action : pb.actions()) {
            Map<String, Object> r = executeAction(action, actionAlarm, previousFailed, actionIndex++);
            results.add(r);
            // 补偿动作（前缀"补偿:"）只在主动作失败后执行；主动作失败会阻断后续主动作
            if (PlaybookActionType.compensation(action)) {
                previousFailed = false; // 补偿已执行，视为完成该阶段
                compensating = true;
            } else {
                boolean ok = PlaybookActionStatus.isSuccessful(String.valueOf(r.get("status")));
                if (!ok) {
                    previousFailed = true; // 失败：后续只执行补偿动作
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
        // 执行级状态机：SUCCESS / COMPENSATING（部分失败已补偿）/ FAILED（未补偿或补偿也失败）
        boolean anyFailed = results.stream().anyMatch(r -> PlaybookActionStatus.isFailed(String.valueOf(r.get("status"))));
        boolean anySimulated = results.stream()
                .anyMatch(r -> PlaybookActionStatus.SIMULATED.wireValue().equals(r.get("status")));
        String execStatus = anyFailed
                ? (compensating ? "COMPENSATING" : "FAILED")
                : (anySimulated ? "SIMULATED" : "SUCCESS");
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("executionId", "EXEC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        exec.put("playbookId", pb.id());
        exec.put("playbook", pb.name());
        exec.put("trigger", pb.trigger());
        exec.put("status", execStatus);
        exec.put("retryCount", retryCount);
        if (firstError != null) exec.put("error", firstError);
        exec.put("results", results);
        exec.put("ts", Instant.now().toString());
        recordExecution(exec);
        return exec;
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

    /** 执行单个动作（含失败重试）；activeFailed 为 true 时跳过主动作、只允许补偿动作。
     *  public 供 Temporal Activity 复用，保证两种执行模式动作语义一致。 */
    public Map<String, Object> executeAction(String action, Map<String, Object> alarm, boolean activeFailed) {
        return executeAction(action, alarm, activeFailed, 0);
    }

    /** Execute one action with a stable key shared by all retries of this action. */
    public Map<String, Object> executeAction(String action, Map<String, Object> alarm,
                                              boolean activeFailed, int actionIndex) {
        String previous = TenantContext.get();
        Object carried = alarm == null ? null : alarm.get("tenantId");
        if (carried == null && alarm != null) carried = alarm.get("tenant_id");
        String tenant = carried == null ? (previous == null ? "default" : previous) : String.valueOf(carried);
        if (!TenantContext.isValid(tenant)) throw new IllegalArgumentException("invalid playbook tenant");
        try {
            TenantContext.set(tenant);
            return executeActionScoped(action, alarm, activeFailed,
                    idempotencyKey(tenant, alarm, action, actionIndex));
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.set(previous);
        }
    }

    private Map<String, Object> executeActionScoped(String action, Map<String, Object> alarm,
                                                    boolean activeFailed, String idempotencyKey) {
        Map<String, Object> r = new LinkedHashMap<>();
        String actionText = action == null ? "" : action.trim();
        PlaybookActionType actionType = PlaybookActionType.resolve(actionText);
        r.put("action", action);
        r.put("actionType", actionType.wireName());
        r.put("idempotencyKey", idempotencyKey);
        boolean isCompensate = PlaybookActionType.compensation(actionText);
        if (activeFailed && !isCompensate) {
            r.put("status", PlaybookActionStatus.SKIPPED.wireValue());
            r.put("mode", "NOT_EXECUTED");
            r.put("reason", "前置动作失败，本动作被跳过（仅执行补偿）");
            return r;
        }
        // 尝试执行（含重试）
        Map<String, Object> attempt = attempt(actionText, alarm, actionType, idempotencyKey);
        boolean retryableAction = actionType == PlaybookActionType.WEBHOOK
                || actionType == PlaybookActionType.NOTIFY
                || actionType == PlaybookActionType.CASE;
        if (retryableAction && PlaybookActionStatus.isFailed(String.valueOf(attempt.get("status")))) {
            int retries = 0;
            while (retries < MAX_ATTEMPTS - 1) {
                retries++;
                attempt = attempt(actionText, alarm, actionType, idempotencyKey);
                if (PlaybookActionStatus.isSuccessful(String.valueOf(attempt.get("status")))) break;
            }
            if (PlaybookActionStatus.isFailed(String.valueOf(attempt.get("status")))) {
                attempt.put("retried", retries);
            }
        }
        r.putAll(attempt);
        return r;
    }

    private Map<String, Object> attempt(String action, Map<String, Object> alarm,
                                        PlaybookActionType actionType, String idempotencyKey) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("actionType", actionType.wireName());
        r.put("idempotencyKey", idempotencyKey);
        try {
            PlaybookActionHandler handler = handlerRegistry.find(actionType);
            if (handler == null) {
                r.put("status", PlaybookActionStatus.FAILED.wireValue());
                r.put("mode", "NOT_EXECUTED");
                r.put("errorCode", "UNSUPPORTED_ACTION");
                r.put("error", "unsupported playbook action: " + action);
                return r;
            }
            r.putAll(handler.handle(new PlaybookActionContext(
                    action, alarm, idempotencyKey, simulationAllowed())));
        } catch (RuntimeException e) {
            log.warn("剧本动作执行异常 action={} alarmId={} error={}: {}",
                    action, alarm == null ? null : alarm.get("id"), e.getClass().getSimpleName(), e.getMessage());
            r.put("status", PlaybookActionStatus.FAILED.wireValue());
            r.put("mode", "NOT_EXECUTED");
            r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return r;
    }

    private boolean simulationAllowed() {
        if (!simulationEnabled) return false;
        String profiles = activeProfiles == null ? "" : activeProfiles.toLowerCase(java.util.Locale.ROOT);
        for (String profile : profiles.split("[,\\s]+")) {
            if (profile.equals("prod") || profile.equals("production")) {
                return false;
            }
        }
        return true;
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
        String t = TenantContext.get();
        return t == null ? "default" : t;
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

    private static String idempotencyKey(String tenant, Map<String, Object> alarm,
                                         String action, int actionIndex) {
        String alarmId = alarm == null ? "" : String.valueOf(alarm.getOrDefault("id", ""));
        String playbookId = alarm == null ? "" : String.valueOf(alarm.getOrDefault("playbookId", ""));
        String material = tenant + "\u0000" + playbookId + "\u0000" + alarmId
                + "\u0000" + actionIndex + "\u0000" + (action == null ? "" : action.trim());
        return "soar-" + UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
