package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarAutomationRuleEntity;
import com.socp.soar.web.persistence.entity.SoarTriggerReceiptEntity;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarAutomationRuleRepository;
import com.socp.soar.web.persistence.repository.SoarTriggerReceiptRepository;
import com.socp.soar.web.definition.SoarExpressionEngine;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.Optional;

/** Automation-rule control plane and deterministic event-to-run fan-out. */
@Service
public class SoarV2AutomationRuleService {
    private static final java.util.concurrent.locks.ReentrantLock RECEIPT_LOCK = new java.util.concurrent.locks.ReentrantLock();
    private final SoarAutomationRuleRepository rules;
    private final SoarV2Service soar;
    private final ObjectMapper mapper;
    private final SoarTriggerReceiptRepository receipts;
    private final SoarRunRepository runs;
    private SoarMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarV2AutomationRuleService(SoarAutomationRuleRepository rules, SoarV2Service soar,
                                       ObjectMapper mapper, SoarTriggerReceiptRepository receipts,
                                       SoarRunRepository runs) {
        this.rules = rules;
        this.soar = soar;
        this.mapper = mapper;
        this.receipts = receipts;
        this.runs = runs;
    }

    /** Compatibility constructor for isolated rule-matching tests. */
    public SoarV2AutomationRuleService(SoarAutomationRuleRepository rules, SoarV2Service soar,
                                       ObjectMapper mapper) {
        this.rules = rules; this.soar = soar; this.mapper = mapper;
        this.receipts = null; this.runs = null;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMetrics(SoarMetrics metrics) { this.metrics = metrics; }

    @Transactional
    @AuditOperation(action = "SOAR_V2_CREATE_AUTOMATION_RULE", target = "t_soar_automation_rule")
    public Map<String, Object> create(String name, String triggerType, int priority, boolean enabled,
                                      JsonNode conditions, JsonNode actions, JsonNode suppression) {
        String tenant = TenantContext.require();
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "automation rule name is required (max 128)");
        }
        if (triggerType == null || triggerType.isBlank() || triggerType.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "automation triggerType is required (max 64)");
        }
        if (!"ANY".equalsIgnoreCase(triggerType)
                && !triggerType.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "automation triggerType is invalid");
        }
        if (actions == null || !actions.isArray() || actions.isEmpty() || actions.size() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "automation actions must contain 1..32 playbook version references");
        }
        rejectInlineSecrets(conditions, actions, suppression);
        validateActions(actions);
        Instant now = Instant.now();
        SoarAutomationRuleEntity row = new SoarAutomationRuleEntity();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setName(name.trim());
        row.setTriggerType(triggerType.trim().toUpperCase());
        row.setPriority(Math.max(0, Math.min(10000, priority)));
        row.setEnabled(enabled);
        row.setConditionJson(json(conditions));
        row.setActionsJson(json(actions));
        row.setSuppressionJson(suppression == null ? "{}" : json(suppression));
        row.setCreatedBy("operator");
        row.setRevision(1);
        JsonNode policy = suppression == null ? mapper.createObjectNode() : suppression;
        validateSuppressionPolicy(policy);
        row.setDedupWindowSeconds(longValue(policy, "dedupWindowSeconds", 0));
        row.setCooldownSeconds(longValue(policy, "cooldownSeconds", 0));
        row.setGroupBy(text(policy, "groupBy", null));
        row.setMaxConcurrentRuns(intValue(policy, "maxConcurrentRuns", 0));
        row.setConflictStrategy(text(policy, "conflictStrategy", "QUEUE").toUpperCase());
        row.setValidFrom(instant(policy, "validFrom"));
        row.setValidUntil(instant(policy, "validUntil"));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setRowVersion(0L);
        rules.save(row);
        return view(row);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return rules.findByTenantIdOrderByPriorityAscUpdatedAtDesc(TenantContext.require())
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> list(Pageable pageable) {
        return rules.findByTenantIdOrderByPriorityAscUpdatedAtDesc(TenantContext.require(), pageable)
                .map(this::view);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return view(find(id));
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_SET_AUTOMATION_RULE_ENABLED", target = "t_soar_automation_rule")
    public Map<String, Object> setEnabled(String id, boolean enabled) {
        SoarAutomationRuleEntity row = findForUpdate(id);
        if (enabled) {
            // Re-check the immutable target versions and their live connection
            // bindings every time a rule is enabled.  A connection may have
            // been disabled/soft-deleted (or a version deprecated) since the
            // last edit, so an old validation result is not sufficient.
            validateActions(read(row.getActionsJson()));
        }
        row.setEnabled(enabled);
        row.setUpdatedAt(Instant.now());
        rules.save(row);
        return view(row);
    }

    @Transactional
    public Map<String, Object> update(String id, String name, String triggerType, int priority,
                                      boolean enabled, JsonNode conditions, JsonNode actions, JsonNode suppression) {
        return update(id, name, triggerType, priority, enabled, conditions, actions, suppression, null);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_UPDATE_AUTOMATION_RULE", target = "t_soar_automation_rule")
    public Map<String, Object> update(String id, String name, String triggerType, int priority,
                                      boolean enabled, JsonNode conditions, JsonNode actions,
                                      JsonNode suppression, Long expectedRowVersion) {
        SoarAutomationRuleEntity row = findForUpdate(id);
        if (expectedRowVersion != null && !expectedRowVersion.equals(row.getRowVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "automation rule was changed by another operator");
        }
        validateBasic(name, triggerType, actions);
        rejectInlineSecrets(conditions, actions, suppression);
        validateActions(actions);
        JsonNode policy = suppression == null ? mapper.createObjectNode() : suppression;
        validateSuppressionPolicy(policy);
        row.setName(name.trim()); row.setTriggerType(triggerType.trim().toUpperCase());
        row.setPriority(Math.max(0, Math.min(10000, priority))); row.setEnabled(enabled);
        row.setConditionJson(json(conditions)); row.setActionsJson(json(actions));
        row.setSuppressionJson(json(policy)); row.setRevision(Math.max(1, row.getRevision()) + 1);
        row.setDedupWindowSeconds(longValue(policy, "dedupWindowSeconds", 0));
        row.setCooldownSeconds(longValue(policy, "cooldownSeconds", 0));
        row.setGroupBy(text(policy, "groupBy", null)); row.setMaxConcurrentRuns(intValue(policy, "maxConcurrentRuns", 0));
        row.setConflictStrategy(text(policy, "conflictStrategy", "QUEUE").toUpperCase());
        row.setValidFrom(instant(policy, "validFrom")); row.setValidUntil(instant(policy, "validUntil"));
        row.setUpdatedAt(Instant.now()); rules.save(row);
        return view(row);
    }

    /** Partial metadata/condition update with optimistic locking for UI forms. */
    @Transactional
    @AuditOperation(action = "SOAR_V2_UPDATE_AUTOMATION_RULE", target = "t_soar_automation_rule")
    public Map<String, Object> patch(String id, Map<String, Object> body) {
        SoarAutomationRuleEntity row = findForUpdate(id);
        Map<String, Object> payload = body == null ? Map.of() : body;
        String name = optionalString(payload.get("name"), row.getName());
        String triggerType = optionalString(payload.get("triggerType"), row.getTriggerType());
        int priority = optionalInt(payload.get("priority"), row.getPriority());
        boolean enabled = optionalBoolean(payload.get("enabled"), row.isEnabled());
        JsonNode conditions = payload.containsKey("conditions")
                ? mapper.valueToTree(payload.get("conditions")) : read(row.getConditionJson());
        JsonNode actions = payload.containsKey("actions")
                ? mapper.valueToTree(payload.get("actions")) : read(row.getActionsJson());
        JsonNode suppression = payload.containsKey("suppression")
                ? mapper.valueToTree(payload.get("suppression")) : read(row.getSuppressionJson());
        Long expected = payload.containsKey("rowVersion") ? longValue(payload.get("rowVersion")) : null;
        return updateLocked(row, name, triggerType, priority, enabled, conditions, actions, suppression, expected);
    }

    private Map<String, Object> updateLocked(SoarAutomationRuleEntity row, String name, String triggerType,
                                              int priority, boolean enabled, JsonNode conditions,
                                              JsonNode actions, JsonNode suppression, Long expectedRowVersion) {
        if (expectedRowVersion != null && !expectedRowVersion.equals(row.getRowVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "automation rule was changed by another operator");
        }
        validateBasic(name, triggerType, actions);
        rejectInlineSecrets(conditions, actions, suppression);
        validateActions(actions);
        JsonNode policy = suppression == null ? mapper.createObjectNode() : suppression;
        validateSuppressionPolicy(policy);
        row.setName(name.trim()); row.setTriggerType(triggerType.trim().toUpperCase());
        row.setPriority(Math.max(0, Math.min(10000, priority))); row.setEnabled(enabled);
        row.setConditionJson(json(conditions)); row.setActionsJson(json(actions));
        row.setSuppressionJson(json(policy)); row.setRevision(Math.max(1, row.getRevision()) + 1);
        row.setDedupWindowSeconds(longValue(policy, "dedupWindowSeconds", 0));
        row.setCooldownSeconds(longValue(policy, "cooldownSeconds", 0));
        row.setGroupBy(text(policy, "groupBy", null)); row.setMaxConcurrentRuns(intValue(policy, "maxConcurrentRuns", 0));
        row.setConflictStrategy(text(policy, "conflictStrategy", "QUEUE").toUpperCase());
        row.setValidFrom(instant(policy, "validFrom")); row.setValidUntil(instant(policy, "validUntil"));
        row.setUpdatedAt(Instant.now()); rules.save(row);
        return view(row);
    }

    /** Rules are retained for receipts/audit; delete is a disabled tombstone. */
    @Transactional
    @AuditOperation(action = "SOAR_V2_DISABLE_AUTOMATION_RULE", target = "t_soar_automation_rule")
    public Map<String, Object> remove(String id) {
        SoarAutomationRuleEntity row = findForUpdate(id);
        row.setEnabled(false); row.setUpdatedAt(Instant.now()); rules.save(row);
        return view(row);
    }

    /**
     * Evaluate all enabled rules in priority order. A request id is derived from
     * tenant/rule/event, so retries cannot create duplicate runs.
     */
    @Transactional
    @AuditOperation(action = "SOAR_V2_EVALUATE_AUTOMATION_RULES", target = "t_soar_trigger_receipt")
    public Map<String, Object> evaluate(Map<String, Object> event) {
        // The database unique key is the final cross-instance guard. The
        // short JVM lock prevents two local workers from both creating a run
        // between the receipt read and insert (and keeps the common path free
        // of avoidable unique-key rollbacks).
        RECEIPT_LOCK.lock();
        try {
            return evaluateLocked(event);
        } finally {
            RECEIPT_LOCK.unlock();
        }
    }

    private Map<String, Object> evaluateLocked(Map<String, Object> event) {
        String tenant = TenantContext.require();
        event = normalizeEvent(event, tenant);
        if (metrics != null) metrics.triggerReceived();
        String eventTenant = text(event, "tenantId");
        if (eventTenant == null) eventTenant = text(event, "tenant_id");
        if (eventTenant != null && !tenant.equals(eventTenant)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "event tenant does not match the authenticated tenant");
        }
        String eventId = text(event, "eventId", text(event, "id", null));
        if (eventId == null || eventId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "event id is required");
        }
        try {
            if (mapper.writeValueAsBytes(event == null ? Map.of() : event).length > 256 * 1024) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "event exceeds 256 KiB");
            }
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "event must be JSON serializable", failure);
        }
        int depth = automationDepth(event);
        List<Map<String, Object>> queued = new ArrayList<>();
        List<Map<String, Object>> receiptsView = new ArrayList<>();
        if (depth > 5) {
            if (metrics != null) metrics.triggerSuppressed();
            return Map.of("eventId", eventId, "matchedRuns", 0, "runs", List.of(),
                    "receipts", List.of(Map.of("status", "SUPPRESSED", "reason", "AUTOMATION_DEPTH_EXCEEDED")));
        }
        for (SoarAutomationRuleEntity rule : rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc(tenant)) {
            if (!activeAt(rule, Instant.now()) || !triggerMatches(rule.getTriggerType(), event)
                    || !conditionMatches(rule.getConditionJson(), event)) {
                continue;
            }
            String groupKey = groupKey(rule, event);
            SoarTriggerReceiptEntity existing = receipt(eventId, rule);
            if (existing != null) {
                receiptsView.add(receiptView(existing));
                continue;
            }
            String suppressedReason = suppressionReason(rule, eventId, groupKey, tenant);
            SoarTriggerReceiptEntity receipt = newReceipt(tenant, eventId, rule, groupKey);
            String targetUnavailable = targetUnavailableReason(rule);
            if (targetUnavailable != null) {
                if (metrics != null) metrics.triggerSuppressed();
                receipt.setStatus("SUPPRESSED");
                receipt.setReason(targetUnavailable);
                saveReceipt(receipt);
                receiptsView.add(receiptView(receipt));
                continue;
            }
            if (suppressedReason != null) {
                if (metrics != null) metrics.triggerSuppressed();
                receipt.setStatus("SUPPRESSED");
                receipt.setReason(suppressedReason);
                saveReceipt(receipt);
                receiptsView.add(receiptView(receipt));
                continue;
            }
            JsonNode actions = read(rule.getActionsJson());
            if (!actions.isArray()) {
                receipt.setStatus("FAILED");
                receipt.setReason("RULE_ACTIONS_INVALID");
                saveReceipt(receipt);
                receiptsView.add(receiptView(receipt));
                continue;
            }
            int actionIndex = 0;
            Map<String, Object> firstRun = null;
            for (JsonNode action : actions) {
                String versionId = action.isTextual() ? action.asText() : action.path("playbookVersionId").asText("");
                if (versionId.isBlank()) continue;
                String requestId = "rule-" + rule.getId() + "-" + shortHash(eventId + "#" + actionIndex++);
                Map<String, Object> subject = Map.of("type", rule.getTriggerType(), "id", eventId);
                Map<String, Object> inputs = automationInputs(event, depth + 1);
                Map<String, Object> run = soar.queueManualRun(requestId, versionId, subject, inputs);
                if (firstRun == null) firstRun = run;
                queued.add(run);
            }
            receipt.setStatus(queuedRunCreated(firstRun) ? "ACCEPTED" : "FAILED");
            receipt.setRunId(firstRun == null ? null : String.valueOf(firstRun.get("runId")));
            receipt.setReason(firstRun == null ? "RULE_ACTIONS_EMPTY" : null);
            saveReceipt(receipt);
            receiptsView.add(receiptView(receipt));
        }
        return Map.of("eventId", eventId, "matchedRuns", queued.size(), "runs", queued,
                "receipts", receiptsView);
    }

    /** Return a durable suppression reason when a previously valid target was
     * deprecated or its owning playbook was archived after rule creation. */
    private String targetUnavailableReason(SoarAutomationRuleEntity rule) {
        if (soar == null) return null;
        for (String versionId : referencedVersionIds(rule)) {
            try {
                Map<String, Object> version = soar.getVersionById(versionId);
                if (version == null || version.isEmpty()) continue; // compatibility test doubles
                Object status = version.get("status");
                if (status != null && !"PUBLISHED".equalsIgnoreCase(String.valueOf(status))) {
                    return "VERSION_NOT_PUBLISHED";
                }
                Object playbookStatus = version.get("playbookStatus");
                if (playbookStatus != null && "ARCHIVED".equalsIgnoreCase(String.valueOf(playbookStatus))) {
                    return "PLAYBOOK_ARCHIVED";
                }
            } catch (ResponseStatusException unavailable) {
                return "VERSION_UNAVAILABLE";
            }
        }
        return null;
    }

    /** Explain rule evaluation without creating receipts or runs. */
    @Transactional(readOnly = true)
    @AuditOperation(action = "SOAR_V2_TEST_AUTOMATION_RULES", target = "t_soar_automation_rule")
    public List<Map<String, Object>> explain(Map<String, Object> event) {
        String tenant = TenantContext.require();
        event = normalizeEvent(event, tenant);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SoarAutomationRuleEntity rule : rules.findByTenantIdOrderByPriorityAscUpdatedAtDesc(tenant)) {
            boolean trigger = triggerMatches(rule.getTriggerType(), event);
            boolean condition = conditionMatches(rule.getConditionJson(), event);
            result.add(Map.of("ruleId", rule.getId(), "priority", rule.getPriority(),
                    "enabled", rule.isEnabled(), "triggerMatched", trigger,
                    "conditionMatched", condition, "matched", rule.isEnabled() && trigger && condition,
                    "groupKey", groupKey(rule, event)));
        }
        return result;
    }

    private SoarTriggerReceiptEntity newReceipt(String tenant, String eventId,
                                                 SoarAutomationRuleEntity rule, String groupKey) {
        SoarTriggerReceiptEntity row = new SoarTriggerReceiptEntity();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setEventId(eventId);
        row.setAutomationRuleId(rule.getId());
        row.setRuleRevision(Math.max(1, rule.getRevision()));
        row.setStatus("EVALUATING");
        row.setGroupKey(groupKey);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        return row;
    }

    private SoarTriggerReceiptEntity receipt(String eventId, SoarAutomationRuleEntity rule) {
        if (receipts == null) return null;
        return receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                TenantContext.require(), eventId, rule.getId(), Math.max(1, rule.getRevision())).orElse(null);
    }

    private String suppressionReason(SoarAutomationRuleEntity rule, String eventId,
                                     String groupKey, String tenant) {
        Instant now = Instant.now();
        if (rule.getDedupWindowSeconds() != null && rule.getDedupWindowSeconds() > 0 && receipts != null) {
            List<SoarTriggerReceiptEntity> recent = receipts.findByTenantIdAndAutomationRuleIdAndCreatedAtAfter(
                    tenant, rule.getId(), now.minusSeconds(rule.getDedupWindowSeconds()));
            if (recent.stream().anyMatch(item -> eventId.equals(item.getEventId()))) return "DEDUP_WINDOW";
        }
        if (rule.getCooldownSeconds() != null && rule.getCooldownSeconds() > 0 && receipts != null) {
            List<SoarTriggerReceiptEntity> recent = receipts.findByTenantIdAndAutomationRuleIdAndCreatedAtAfter(
                    tenant, rule.getId(), now.minusSeconds(rule.getCooldownSeconds()));
            if (recent.stream().anyMatch(item -> java.util.Objects.equals(groupKey, item.getGroupKey())
                    && "ACCEPTED".equals(item.getStatus()))) return "COOLDOWN";
        }
        int max = rule.getMaxConcurrentRuns() == null ? 0 : rule.getMaxConcurrentRuns();
        if (max > 0 && runs != null) {
            // Count across every playbook version the rule can trigger, not just
            // the first action, so the capacity limit is honest for multi-action rules.
            List<String> versionIds = referencedVersionIds(rule);
            long active = versionIds.isEmpty() ? 0 : runs.countByTenantIdAndPlaybookVersionIdInAndStatusIn(tenant,
                    versionIds, Set.of("QUEUED", "DISPATCHING", "RUNNING", "WAITING_APPROVAL", "WAITING_INPUT"));
            if (active >= max && "SUPPRESS".equalsIgnoreCase(rule.getConflictStrategy())) return "CAPACITY";
        }
        return null;
    }

    private List<String> referencedVersionIds(SoarAutomationRuleEntity rule) {
        JsonNode actions = read(rule.getActionsJson());
        List<String> ids = new ArrayList<>();
        if (actions.isArray()) {
            for (JsonNode action : actions) {
                String versionId = action.isTextual() ? action.asText() : action.path("playbookVersionId").asText("");
                if (!versionId.isBlank()) ids.add(versionId);
            }
        }
        return ids;
    }

    private static boolean queuedRunCreated(Map<String, Object> run) {
        return run != null && run.get("runId") != null;
    }

    private Map<String, Object> receiptView(SoarTriggerReceiptEntity row) {
        return Map.of("id", row.getId(), "eventId", row.getEventId(), "ruleId", row.getAutomationRuleId(),
                "revision", row.getRuleRevision(), "status", row.getStatus(),
                "runId", row.getRunId() == null ? "" : row.getRunId(),
                "reason", row.getReason() == null ? "" : row.getReason(),
                "groupKey", row.getGroupKey() == null ? "" : row.getGroupKey(),
                "createdAt", row.getCreatedAt());
    }

    private SoarAutomationRuleEntity find(String id) {
        return rules.findByTenantIdAndId(TenantContext.require(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "automation rule not found"));
    }

    private SoarAutomationRuleEntity findForUpdate(String id) {
        String tenant = TenantContext.require();
        Optional<SoarAutomationRuleEntity> locked = rules.findByTenantIdAndIdForUpdate(tenant, id);
        if (locked != null && locked.isPresent()) return locked.get();
        return rules.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "automation rule not found"));
    }

    private void validateBasic(String name, String triggerType, JsonNode actions) {
        if (name == null || name.isBlank() || name.length() > 128
                || triggerType == null || triggerType.isBlank() || triggerType.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid automation rule fields");
        }
        if (!"ANY".equalsIgnoreCase(triggerType)
                && !triggerType.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "automation triggerType is invalid");
        }
        if (actions == null || !actions.isArray() || actions.isEmpty() || actions.size() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "automation actions must contain 1..32 playbook version references");
        }
    }

    /** Validate typed suppression controls before storing their denormalized projection. */
    private void validateSuppressionPolicy(JsonNode policy) {
        if (policy == null || policy.isNull()) return;
        if (!policy.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "suppression must be a JSON object");
        }
        for (String field : List.of("dedupWindowSeconds", "cooldownSeconds", "maxConcurrentRuns")) {
            JsonNode value = policy.get(field);
            if (value == null) continue;
            if (!value.isIntegralNumber() || value.asLong() < 0
                    || ("maxConcurrentRuns".equals(field) && value.asLong() > 10_000)
                    || (!"maxConcurrentRuns".equals(field) && value.asLong() > 30L * 24 * 3600)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + " must be a bounded non-negative integer");
            }
        }
        JsonNode groupBy = policy.get("groupBy");
        if (groupBy != null && (!groupBy.isTextual() || groupBy.asText().length() > 512
                || !groupBy.asText().matches("[A-Za-z][A-Za-z0-9_.-]{0,255}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "groupBy must be a simple event field path");
        }
        JsonNode strategy = policy.get("conflictStrategy");
        if (strategy != null && (!strategy.isTextual()
                || !Set.of("QUEUE", "SUPPRESS").contains(strategy.asText().trim().toUpperCase()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "conflictStrategy must be QUEUE or SUPPRESS");
        }
        Instant from = parseInstant(policy.get("validFrom"), "validFrom");
        Instant until = parseInstant(policy.get("validUntil"), "validUntil");
        if (from != null && until != null && !until.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "validUntil must be after validFrom");
        }
    }

    private static Instant parseInstant(JsonNode value, String field) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be an ISO-8601 instant");
        }
        try {
            return Instant.parse(value.asText().trim());
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be an ISO-8601 instant", failure);
        }
    }

    private void validateActions(JsonNode actions) {
        for (JsonNode action : actions) {
            String versionId = action.isTextual() ? action.asText() : action.path("playbookVersionId").asText("");
            if (versionId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "automation action requires playbookVersionId");
            Map<String, Object> version = soar == null ? Map.of("status", "PUBLISHED") : soar.getVersionById(versionId);
            if (!"PUBLISHED".equals(version.get("status"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "automation rule can only reference a published version");
            }
            if (soar != null) soar.validatePublishedVersionForAutomation(versionId);
        }
    }

    private Map<String, Object> view(SoarAutomationRuleEntity row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId());
        result.put("name", row.getName());
        result.put("triggerType", row.getTriggerType());
        result.put("priority", row.getPriority());
        result.put("enabled", row.isEnabled());
        result.put("conditions", read(row.getConditionJson()));
        result.put("actions", read(row.getActionsJson()));
        result.put("suppression", read(row.getSuppressionJson()));
        result.put("revision", row.getRevision());
        result.put("dedupWindowSeconds", row.getDedupWindowSeconds());
        result.put("cooldownSeconds", row.getCooldownSeconds());
        result.put("groupBy", row.getGroupBy());
        result.put("maxConcurrentRuns", row.getMaxConcurrentRuns());
        result.put("conflictStrategy", row.getConflictStrategy());
        result.put("validFrom", row.getValidFrom());
        result.put("validUntil", row.getValidUntil());
        result.put("createdBy", row.getCreatedBy());
        result.put("createdAt", row.getCreatedAt());
        result.put("updatedAt", row.getUpdatedAt());
        result.put("rowVersion", row.getRowVersion());
        return result;
    }

    private boolean triggerMatches(String trigger, Map<String, Object> event) {
        String type = text(event, "type");
        if (type == null) type = text(event, "eventType");
        return "ANY".equals(trigger) || (type != null && trigger.equalsIgnoreCase(type));
    }

    private boolean activeAt(SoarAutomationRuleEntity rule, Instant now) {
        return (rule.getValidFrom() == null || !now.isBefore(rule.getValidFrom()))
                && (rule.getValidUntil() == null || now.isBefore(rule.getValidUntil()));
    }

    private String groupKey(SoarAutomationRuleEntity rule, Map<String, Object> event) {
        String path = rule.getGroupBy();
        if (path == null || path.isBlank()) return "";
        String value = String.valueOf(valueAtPath(event, path));
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private int automationDepth(Map<String, Object> event) {
        Object trace = event == null ? null : event.get("trace");
        Object value = trace instanceof Map<?, ?> map ? map.get("automationDepth")
                : event == null ? null : event.get("automationDepth");
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    /** Carry a bounded hop counter through the durable run input so an
     * event-emitting playbook cannot recursively trigger itself forever. */
    private Map<String, Object> automationInputs(Map<String, Object> event, int nextDepth) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (event != null) inputs.putAll(event);
        Map<String, Object> trace = new LinkedHashMap<>();
        Object existing = inputs.get("trace");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((key, value) -> trace.put(String.valueOf(key), value));
        }
        trace.put("automationDepth", Math.max(0, Math.min(6, nextDepth)));
        inputs.put("trace", trace);
        inputs.put("automationDepth", Math.max(0, Math.min(6, nextDepth)));
        return inputs;
    }

    private static void rejectInlineSecrets(JsonNode conditions, JsonNode actions, JsonNode suppression) {
        if (containsSensitiveKey(conditions) || containsSensitiveKey(actions) || containsSensitiveKey(suppression)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "automation rules cannot persist inline secrets; use connection secretRef");
        }
    }

    private static boolean containsSensitiveKey(JsonNode value) {
        if (value == null || value.isNull()) return false;
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(java.util.Locale.ROOT);
                if (key.contains("secret") || key.contains("token") || key.contains("password")
                        || key.contains("authorization") || key.equals("cookie")) return true;
                if (containsSensitiveKey(field.getValue())) return true;
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) if (containsSensitiveKey(item)) return true;
        }
        return false;
    }

    /**
     * Normalize every trigger at the boundary to the documented
     * soar.event/v1 envelope. Legacy callers may omit optional envelope
     * fields, but they cannot change tenant identity, event type, or trace
     * depth by putting arbitrary values in a nested map.
     */
    private Map<String, Object> normalizeEvent(Map<String, Object> event, String tenant) {
        if (event == null || event.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "event envelope is required");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(event);
        String schemaVersion = text(event, "schemaVersion", "soar.event/v1");
        if (!"soar.event/v1".equals(schemaVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "schemaVersion must be soar.event/v1");
        }
        String eventId = text(event, "eventId", text(event, "id", null));
        if (eventId == null || eventId.isBlank() || eventId.length() > 255
                || containsControl(eventId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "eventId is required and must be at most 255 characters");
        }
        String eventType = text(event, "eventType", text(event, "type", null));
        if (eventType == null || !eventType.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "eventType is required and must be a namespaced event type");
        }
        String eventTenant = text(event, "tenantId", text(event, "tenant_id", null));
        if (eventTenant != null && !tenant.equals(eventTenant)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "event tenant does not match the authenticated tenant");
        }

        String occurredAt = text(event, "occurredAt", null);
        if (occurredAt == null) occurredAt = text(event, "occurred_at", null);
        if (occurredAt == null) occurredAt = Instant.now().toString();
        if (occurredAt.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "occurredAt is too long");
        }
        try { Instant.parse(occurredAt); }
        catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "occurredAt must be an ISO-8601 instant");
        }
        String producer = text(event, "producer", "soar-api");
        if (producer == null || producer.isBlank() || producer.length() > 128 || containsControl(producer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "producer must be a non-blank value of at most 128 characters");
        }

        Map<String, Object> subject = object(event.get("subject"), "subject");
        if (subject.isEmpty()) subject = new LinkedHashMap<>(Map.of("type", eventType, "id", eventId));
        String subjectType = stringValue(subject.get("type"));
        String subjectId = stringValue(subject.get("id"));
        if (subjectType == null || !subjectType.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}")
                || subjectId == null || subjectId.isBlank() || subjectId.length() > 255
                || containsControl(subjectId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "subject.type and subject.id are required and bounded");
        }
        Map<String, Object> data = object(event.get("data"), "data");
        Map<String, Object> traceInput = object(event.get("trace"), "trace");
        Map<String, Object> trace = new LinkedHashMap<>();
        copyTraceText(traceInput, trace, "traceparent", 256, null);
        copyTraceText(traceInput, trace, "correlationId", 255, eventId);
        copyTraceText(traceInput, trace, "causationId", 255, eventId);
        Object depthValue = traceInput.get("automationDepth");
        int depth = depthValue == null ? 0 : strictInteger(depthValue, "trace.automationDepth");
        if (depth < 0 || depth > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "trace.automationDepth must be between 0 and 100");
        }
        trace.put("automationDepth", depth);

        normalized.put("schemaVersion", schemaVersion);
        normalized.put("eventId", eventId);
        normalized.put("id", eventId);
        normalized.put("eventType", eventType);
        normalized.put("type", eventType);
        normalized.put("tenantId", tenant);
        if (event.containsKey("tenant_id")) normalized.put("tenant_id", tenant);
        normalized.put("occurredAt", occurredAt);
        normalized.put("producer", producer);
        normalized.put("subject", subject);
        normalized.put("data", data);
        normalized.put("trace", trace);
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }

    private static void copyTraceText(Map<String, Object> source, Map<String, Object> target,
                                      String field, int max, String fallback) {
        Object value = source.get(field);
        if (value == null) value = fallback;
        if (value == null) return;
        if (!(value instanceof CharSequence)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "trace." + field + " must be a string");
        }
        String text = value.toString().trim();
        if (text.length() > max || containsControl(text)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "trace." + field + " is too long or contains control characters");
        }
        target.put(field, text);
    }

    private static int strictInteger(Object value, String field) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be an integer");
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is out of range");
        }
        return (int) number;
    }

    private static boolean containsControl(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private void saveReceipt(SoarTriggerReceiptEntity row) {
        if (receipts != null) receipts.save(row);
    }

    private boolean conditionMatches(String json, Map<String, Object> event) {
        JsonNode condition = read(json);
        if (condition.isMissingNode() || condition.isNull() || condition.isEmpty()) return true;
        if (condition.has("all") && condition.get("all").isArray()) {
            for (JsonNode item : condition.get("all")) if (!conditionMatches(item.toString(), event)) return false;
            return true;
        }
        if (condition.has("any") && condition.get("any").isArray()) {
            for (JsonNode item : condition.get("any")) if (conditionMatches(item.toString(), event)) return true;
            return false;
        }
        String field = condition.path("field").asText("");
        if (condition.has("expression")) {
            String expression = condition.path("expression").asText("");
            return SoarExpressionEngine.isSafe(expression) && SoarExpressionEngine.evaluate(expression, event);
        }
        if (!field.isBlank()) {
            Object actual = valueAtPath(event, field);
            String operator = condition.path("operator").asText("equals").toLowerCase();
            JsonNode expected = condition.get("value");
            String expectedText = expected == null ? "" : expected.asText();
            String actualText = actual == null ? "" : String.valueOf(actual);
            switch (operator) {
                case "contains": return actualText.toLowerCase().contains(expectedText.toLowerCase());
                case "exists": return actual != null;
                case "not_equals", "not-equals": return !actualText.equalsIgnoreCase(expectedText);
                case "in": {
                    if (expected == null || !expected.isArray()) return false;
                    for (JsonNode item : expected) {
                        if (item.asText().equalsIgnoreCase(actualText)) return true;
                    }
                    return false;
                }
                default: return actualText.equalsIgnoreCase(expectedText);
            }
        }
        // Fail-closed (design §7.2): a non-empty condition the engine does not
        // understand must never silently match; unknown structure means no match.
        return false;
    }

    private JsonNode read(String json) {
        try { return mapper.readTree(json == null ? "{}" : json); }
        catch (Exception ignored) { return mapper.createObjectNode(); }
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value == null ? mapper.createObjectNode() : value); }
        catch (Exception failure) { throw new IllegalArgumentException("invalid automation rule JSON", failure); }
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String text(Map<String, Object> values, String key, String fallback) {
        String value = text(values, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.isObject() || !node.has(field)) return fallback;
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private static long longValue(JsonNode node, String field, long fallback) {
        if (node == null || !node.isObject() || !node.has(field)) return fallback;
        return Math.max(0, node.path(field).asLong(fallback));
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        if (node == null || !node.isObject() || !node.has(field)) return fallback;
        return Math.max(0, node.path(field).asInt(fallback));
    }

    private static String optionalString(Object value, String fallback) {
        if (value == null) return fallback;
        String result = String.valueOf(value).trim();
        return result.isBlank() ? fallback : result;
    }

    private static int optionalInt(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean optionalBoolean(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean flag) return flag;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field, null);
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (Exception ignored) { return null; }
    }

    private static Object valueAtPath(Map<String, Object> values, String path) {
        Object current = values;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (int i = 0; i < 16; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception failure) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
