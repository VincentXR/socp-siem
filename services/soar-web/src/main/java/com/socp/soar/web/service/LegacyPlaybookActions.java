package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.domain.PlaybookActionStatus;
import com.socp.soar.web.domain.PlaybookActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Compatibility adapter for persisted, unnamespaced playbook actions.
 *
 * <p>Owns only single-action tenant scope, idempotency, retries and handler
 * lifecycle. Both legacy runners and historical graph actions use it without
 * depending on the legacy scheduler, approvals or orchestration. New graph
 * definitions still require namespaced actions at validation.</p>
 */
@Component
public class LegacyPlaybookActions {

    private static final Logger log = LoggerFactory.getLogger(LegacyPlaybookActions.class);
    private static final int MAX_ATTEMPTS = 3;
    private final PlaybookActionHandlerRegistry handlerRegistry;
    private final boolean simulationEnabled;
    private final String activeProfiles;

    public LegacyPlaybookActions(PlaybookActionHandlerRegistry handlerRegistry,
                                 SoarRuntimeProperties properties, Environment environment) {
        this.handlerRegistry = handlerRegistry;
        this.simulationEnabled = properties != null && properties.isSimulationEnabled();
        this.activeProfiles = environment == null ? "" : String.join(",", environment.getActiveProfiles());
    }

    /** Execute one legacy action, preserving skip and retry semantics. */
    public Map<String, Object> executeAction(String action, Map<String, Object> alarm, boolean activeFailed) {
        return executeAction(action, alarm, activeFailed, 0);
    }

    /** Execute one action with a stable key shared by all retries of this action. */
    public Map<String, Object> executeAction(String action, Map<String, Object> alarm,
                                              boolean activeFailed, int actionIndex) {
        String previous = TenantContext.get();
        Object carried = alarm == null ? null : alarm.get("tenantId");
        if (carried == null && alarm != null) carried = alarm.get("tenant_id");
        String tenant = carried == null ? (previous == null ? TenantContext.require() : previous)
                : String.valueOf(carried);
        if (!TenantContext.isValid(tenant)) throw new IllegalArgumentException("invalid playbook tenant");
        return TenantContext.callWith(tenant, () -> executeActionScoped(action, alarm, activeFailed,
                idempotencyKey(tenant, alarm, action, actionIndex)));
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
            PlaybookActionContext context = new PlaybookActionContext(
                    action, alarm, idempotencyKey, simulationAllowed());
            Map<String, Object> validation = handler.validate(context);
            if (lifecycleFailure(validation)) return lifecycleResult(r, validation, "VALIDATION_FAILED");
            Map<String, Object> prepared = handler.prepare(context);
            if (lifecycleFailure(prepared)) return lifecycleResult(r, prepared, "PREPARATION_FAILED");
            Map<String, Object> approval = handler.requestApproval(context);
            if (approval != null && "approval_required".equalsIgnoreCase(
                    String.valueOf(approval.getOrDefault("status", "")))) {
                return lifecycleResult(r, approval, "CONNECTOR_APPROVAL_REQUIRED");
            }
            r.putAll(handler.execute(context));
        } catch (RuntimeException e) {
            log.warn("剧本动作执行异常 action={} alarmId={} error={}: {}",
                    action, alarm == null ? null : alarm.get("id"), e.getClass().getSimpleName(), e.getMessage());
            r.put("status", PlaybookActionStatus.FAILED.wireValue());
            r.put("mode", "NOT_EXECUTED");
            r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return r;
    }

    private static boolean lifecycleFailure(Map<String, Object> result) {
        if (result == null) return true;
        String status = String.valueOf(result.getOrDefault("status", ""));
        return "failed".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status)
                || "rejected".equalsIgnoreCase(status);
    }

    private static Map<String, Object> lifecycleResult(Map<String, Object> base,
                                                       Map<String, Object> lifecycle,
                                                       String defaultCode) {
        if (lifecycle != null) base.putAll(lifecycle);
        String status = String.valueOf(base.getOrDefault("status", "failed"));
        if ("approval_required".equalsIgnoreCase(status)) {
            base.put("status", "failed");
            base.put("errorCode", defaultCode);
        } else {
            base.put("status", PlaybookActionStatus.FAILED.wireValue());
            base.putIfAbsent("errorCode", defaultCode);
        }
        base.putIfAbsent("mode", "NOT_EXECUTED");
        return base;
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

    private static String idempotencyKey(String tenant, Map<String, Object> alarm,
                                         String action, int actionIndex) {
        String alarmId = alarm == null ? "" : String.valueOf(alarm.getOrDefault("id", ""));
        String playbookId = alarm == null ? "" : String.valueOf(alarm.getOrDefault("playbookId", ""));
        String material = tenant + "\u0000" + playbookId + "\u0000" + alarmId
                + "\u0000" + actionIndex + "\u0000" + (action == null ? "" : action.trim());
        return "soar-" + UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
