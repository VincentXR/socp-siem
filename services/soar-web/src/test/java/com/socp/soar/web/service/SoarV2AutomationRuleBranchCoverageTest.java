package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarAutomationRuleEntity;
import com.socp.soar.web.persistence.entity.SoarTriggerReceiptEntity;
import com.socp.soar.web.persistence.repository.SoarAutomationRuleRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarTriggerReceiptRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Branch coverage for automation-rule validation, suppression, and fan-out. */
@ExtendWith(MockitoExtension.class)
class SoarV2AutomationRuleBranchCoverageTest {

    private static final String ACTIONS = "[{\"playbookVersionId\":\"v1\"}]";

    @Mock
    private SoarAutomationRuleRepository rules;
    @Mock
    private SoarV2Service soar;
    @Mock
    private SoarTriggerReceiptRepository receipts;
    @Mock
    private SoarRunRepository runs;

    private final ObjectMapper mapper = new ObjectMapper();
    private SoarV2AutomationRuleService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        service = new SoarV2AutomationRuleService(rules, soar, mapper, receipts, runs);
        service.setMetrics(mock(SoarMetrics.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- create() validation branches ----

    @Test
    void createRejectsMissingAndMalformedTriggerType() {
        assertThatThrownBy(() -> service.create("rule", "  ", 1, true, null, actions(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("automation triggerType is required (max 64)");
        assertThatThrownBy(() -> service.create("rule", "9bad-type", 1, true, null, actions(), null))
                .hasMessageContaining("automation triggerType is invalid");
    }

    @Test
    void createRejectsNonObjectSuppression() {
        given(soar.getVersionById("v1")).willReturn(Map.of("status", "PUBLISHED"));
        assertThatThrownBy(() -> service.create("rule", "ALERT.CREATED", 1, true, null,
                actions(), mapper.createArrayNode()))
                .hasMessageContaining("suppression must be a JSON object");
    }

    @Test
    void createRejectsUnboundedSuppressionWindows() {
        given(soar.getVersionById("v1")).willReturn(Map.of("status", "PUBLISHED"));
        ObjectNode tooManyRuns = mapper.createObjectNode().put("maxConcurrentRuns", 20000);
        assertThatThrownBy(() -> service.create("rule", "ALERT.CREATED", 1, true, null, actions(), tooManyRuns))
                .hasMessageContaining("maxConcurrentRuns must be a bounded non-negative integer");
        ObjectNode tooLongCooldown = mapper.createObjectNode().put("cooldownSeconds", 3_000_000);
        assertThatThrownBy(() -> service.create("rule", "ALERT.CREATED", 1, true, null, actions(), tooLongCooldown))
                .hasMessageContaining("cooldownSeconds must be a bounded non-negative integer");
    }

    @Test
    void createRejectsNonTextualAndMalformedInstants() {
        given(soar.getVersionById("v1")).willReturn(Map.of("status", "PUBLISHED"));
        ObjectNode numericValidFrom = mapper.createObjectNode().put("validFrom", 42);
        assertThatThrownBy(() -> service.create("rule", "ALERT.CREATED", 1, true, null, actions(), numericValidFrom))
                .hasMessageContaining("validFrom must be an ISO-8601 instant");
        ObjectNode garbageValidUntil = mapper.createObjectNode().put("validUntil", "yesterday");
        assertThatThrownBy(() -> service.create("rule", "ALERT.CREATED", 1, true, null, actions(), garbageValidUntil))
                .hasMessageContaining("validUntil must be an ISO-8601 instant");
    }

    @Test
    void createProjectsBoundedSuppressionPolicyOntoRule() {
        given(soar.getVersionById("v1")).willReturn(Map.of("status", "PUBLISHED"));
        given(rules.save(any(SoarAutomationRuleEntity.class))).willAnswer(inv -> inv.getArgument(0));
        ObjectNode suppression = mapper.createObjectNode();
        suppression.put("dedupWindowSeconds", 60);
        suppression.put("cooldownSeconds", 120);
        suppression.put("maxConcurrentRuns", 5);
        suppression.put("groupBy", "data.host");
        suppression.put("conflictStrategy", "suppress");
        suppression.put("validFrom", "2026-01-01T00:00:00Z");
        suppression.put("validUntil", "2027-01-01T00:00:00Z");

        Map<String, Object> view = service.create("contain-host", "alert.created", 20, true, null,
                actions(), suppression);

        assertThat(view).containsEntry("dedupWindowSeconds", 60L)
                .containsEntry("cooldownSeconds", 120L)
                .containsEntry("maxConcurrentRuns", 5)
                .containsEntry("conflictStrategy", "SUPPRESS")
                .containsEntry("priority", 20)
                .containsEntry("triggerType", "ALERT.CREATED");
        assertThat(view.get("validFrom")).isNotNull();
        assertThat(view.get("validUntil")).isNotNull();
        verify(rules).save(any(SoarAutomationRuleEntity.class));
    }

    // ---- update / patch branches ----

    @Test
    void updateRejectsInvalidBasicFields() {
        SoarAutomationRuleEntity row = rule("r1", "ALERT.CREATED", "{}", ACTIONS);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r1")).willReturn(Optional.of(row));

        assertThatThrownBy(() -> service.update("r1", "  ", "ALERT.CREATED", 1, true, null, actions(), null))
                .hasMessageContaining("invalid automation rule fields");
        assertThatThrownBy(() -> service.update("r1", "rule", "9bad", 1, true, null, actions(), null))
                .hasMessageContaining("automation triggerType is invalid");
        assertThatThrownBy(() -> service.update("r1", "rule", "ALERT.CREATED", 1, true, null,
                mapper.createArrayNode(), null))
                .hasMessageContaining("1..32 playbook version references");
    }

    @Test
    void updateOverloadDelegatesAndDetectsRowVersionConflict() {
        SoarAutomationRuleEntity row = rule("r1", "ALERT.CREATED", "{}", ACTIONS);
        row.setRowVersion(7L);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r1")).willReturn(Optional.of(row));
        given(soar.getVersionById("v1")).willReturn(Map.of("status", "PUBLISHED"));

        assertThatThrownBy(() -> service.update("r1", "rule", "ALERT.CREATED", 5, true, null,
                actions(), null, 6L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed by another operator");

        Map<String, Object> view = service.update("r1", "rule", "alert.created", 5, true, null,
                actions(), null);
        assertThat(view).containsEntry("triggerType", "ALERT.CREATED")
                .containsEntry("rowVersion", 7L)
                .containsEntry("priority", 5);
    }

    @Test
    void controlPlaneFallsBackToUnlockedReadWhenLockQueryMisses() {
        SoarAutomationRuleEntity row = rule("r1", "ALERT.CREATED", "{}", ACTIONS);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r1")).willReturn(Optional.empty());
        given(rules.findByTenantIdAndId("tenant-a", "r1")).willReturn(Optional.of(row));
        given(soar.getVersionById("v1")).willReturn(Map.of("status", "PUBLISHED"));

        Map<String, Object> view = service.setEnabled("r1", true);

        assertThat(view).containsEntry("enabled", true);
        verify(rules).save(row);
    }

    @Test
    void patchCoercesPriorityAndEnabledTypes() {
        SoarAutomationRuleEntity row = rule("r1", "ALERT.CREATED", "{}", ACTIONS);
        row.setPriority(10);
        row.setEnabled(true);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r1")).willReturn(Optional.of(row));
        given(soar.getVersionById("v1")).willReturn(Map.of("status", "PUBLISHED"));

        assertThat(service.patch("r1", Map.<String, Object>of("priority", 9))).containsEntry("priority", 9);
        assertThat(service.patch("r1", Map.<String, Object>of("priority", "12"))).containsEntry("priority", 12);
        assertThat(service.patch("r1", Map.<String, Object>of("priority", "bogus"))).containsEntry("priority", 12);
        assertThat(service.patch("r1", Map.<String, Object>of("enabled", false))).containsEntry("enabled", false);
        assertThat(service.patch("r1", Map.<String, Object>of("enabled", "true"))).containsEntry("enabled", true);
    }

    @Test
    void patchRowVersionCoercionAndConflict() {
        SoarAutomationRuleEntity row = rule("r1", "ALERT.CREATED", "{}", ACTIONS);
        row.setRowVersion(3L);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r1")).willReturn(Optional.of(row));
        given(soar.getVersionById("v1")).willReturn(Map.of("status", "PUBLISHED"));

        assertThatThrownBy(() -> service.patch("r1", Map.<String, Object>of("rowVersion", "2")))
                .hasMessageContaining("changed by another operator");
        assertThat(service.patch("r1", Map.<String, Object>of("rowVersion", "nope")))
                .containsEntry("name", "rule-r1");
    }

    // ---- evaluate() branches ----

    @Test
    void evaluateRejectsOversizedAndUnserializableEvents() {
        Map<String, Object> oversized = event("E-BIG");
        oversized.put("data", Map.of("blob", "x".repeat(300_000)));
        assertThatThrownBy(() -> service.evaluate(oversized))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE))
                .hasMessageContaining("event exceeds 256 KiB");

        Map<String, Object> unserializable = event("E-OBJ");
        unserializable.put("data", Map.of("blob", new Object()));
        assertThatThrownBy(() -> service.evaluate(unserializable))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("event must be JSON serializable");
    }

    @Test
    void evaluateReturnsDepthSuppressionReceipt() {
        Map<String, Object> deep = event("E-DEEP");
        deep.put("trace", Map.of("automationDepth", 6));

        Map<String, Object> result = service.evaluate(deep);

        assertThat(result).containsEntry("matchedRuns", 0);
        assertThat(receiptViews(result)).hasSize(1);
        assertThat(receiptViews(result).get(0))
                .containsEntry("status", "SUPPRESSED")
                .containsEntry("reason", "AUTOMATION_DEPTH_EXCEEDED");
    }

    @Test
    void evaluateSuppressesWithDistinctTargetUnavailableReasons() {
        SoarAutomationRuleEntity deprecated = rule("r1", "ALERT.CREATED", "{}",
                "[{\"playbookVersionId\":\"v-dep\"}]");
        SoarAutomationRuleEntity archived = rule("r2", "ALERT.CREATED", "{}",
                "[{\"playbookVersionId\":\"v-arch\"}]");
        SoarAutomationRuleEntity missing = rule("r3", "ALERT.CREATED", "{}",
                "[{\"playbookVersionId\":\"v-gone\"}]");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a"))
                .willReturn(List.of(deprecated, archived, missing));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                any(), any(), any(), anyInt())).willReturn(Optional.empty());
        given(soar.getVersionById("v-dep")).willReturn(Map.of("status", "DEPRECATED"));
        given(soar.getVersionById("v-arch")).willReturn(
                Map.of("status", "PUBLISHED", "playbookStatus", "ARCHIVED"));
        given(soar.getVersionById("v-gone"))
                .willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "version gone"));

        Map<String, Object> result = service.evaluate(event("E-1"));

        assertThat(result).containsEntry("matchedRuns", 0);
        assertThat(receiptViews(result)).extracting(view -> view.get("reason"))
                .containsExactly("VERSION_NOT_PUBLISHED", "PLAYBOOK_ARCHIVED", "VERSION_UNAVAILABLE");
        assertThat(receiptViews(result)).extracting(view -> view.get("status"))
                .containsOnly("SUPPRESSED");
    }

    @Test
    void evaluateAppliesDedupCooldownAndCapacitySuppression() {
        SoarAutomationRuleEntity dedup = rule("r1", "ALERT.CREATED", "{}", ACTIONS);
        dedup.setDedupWindowSeconds(300L);
        SoarAutomationRuleEntity cooldown = rule("r2", "ALERT.CREATED", "{}", ACTIONS);
        cooldown.setCooldownSeconds(600L);
        SoarAutomationRuleEntity capacity = rule("r3", "ALERT.CREATED", "{}", ACTIONS);
        capacity.setMaxConcurrentRuns(1);
        capacity.setConflictStrategy("SUPPRESS");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a"))
                .willReturn(List.of(dedup, cooldown, capacity));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                any(), any(), any(), anyInt())).willReturn(Optional.empty());
        SoarTriggerReceiptEntity recentSameEvent = new SoarTriggerReceiptEntity();
        recentSameEvent.setEventId("E-2");
        given(receipts.findByTenantIdAndAutomationRuleIdAndCreatedAtAfter(eq("tenant-a"), eq("r1"), any()))
                .willReturn(List.of(recentSameEvent));
        SoarTriggerReceiptEntity recentSameGroup = new SoarTriggerReceiptEntity();
        recentSameGroup.setGroupKey("");
        recentSameGroup.setStatus("ACCEPTED");
        given(receipts.findByTenantIdAndAutomationRuleIdAndCreatedAtAfter(eq("tenant-a"), eq("r2"), any()))
                .willReturn(List.of(recentSameGroup));
        given(runs.countByTenantIdAndPlaybookVersionIdInAndStatusIn(eq("tenant-a"), any(), any()))
                .willReturn(1L);

        Map<String, Object> result = service.evaluate(event("E-2"));

        assertThat(result).containsEntry("matchedRuns", 0);
        assertThat(receiptViews(result)).extracting(view -> view.get("reason"))
                .containsExactly("DEDUP_WINDOW", "COOLDOWN", "CAPACITY");
    }

    @Test
    void evaluateMarksRuleFailedWhenActionsJsonIsNotAnArray() {
        SoarAutomationRuleEntity broken = rule("r1", "ALERT.CREATED", "{}", "not-json{");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a"))
                .willReturn(List.of(broken));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                any(), any(), any(), anyInt())).willReturn(Optional.empty());

        Map<String, Object> result = service.evaluate(event("E-9"));

        assertThat(result).containsEntry("matchedRuns", 0);
        assertThat(receiptViews(result)).hasSize(1);
        assertThat(receiptViews(result).get(0))
                .containsEntry("status", "FAILED")
                .containsEntry("reason", "RULE_ACTIONS_INVALID");
    }

    @Test
    void evaluateQueuesRunAndAcceptsReceiptForMatchingRule() {
        SoarAutomationRuleEntity matched = rule("r1", "ALERT.CREATED", "{}", ACTIONS);
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a"))
                .willReturn(List.of(matched));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                any(), any(), any(), anyInt())).willReturn(Optional.empty());
        given(soar.getVersionById("v1")).willReturn(Map.of("status", "PUBLISHED"));
        given(soar.queueManualRun(anyString(), eq("v1"), any(), any()))
                .willReturn(Map.of("runId", "RUN-1", "status", "QUEUED"));

        Map<String, Object> result = service.evaluate(event("E-3"));

        assertThat(result).containsEntry("matchedRuns", 1);
        assertThat(receiptViews(result)).hasSize(1);
        assertThat(receiptViews(result).get(0))
                .containsEntry("status", "ACCEPTED")
                .containsEntry("runId", "RUN-1");
        verify(receipts).save(any(SoarTriggerReceiptEntity.class));
    }

    // ---- explain() condition operator branches ----

    @Test
    void explainCoversConditionOperatorBranches() {
        List<SoarAutomationRuleEntity> ruleSet = List.of(
                ruleWithCondition("c1", "{\"field\":\"data.host\",\"operator\":\"contains\",\"value\":\"web\"}"),
                ruleWithCondition("c2", "{\"field\":\"data.missing\",\"operator\":\"exists\"}"),
                ruleWithCondition("c3", "{\"field\":\"data.host\",\"operator\":\"in\",\"value\":[\"windows\",\"mac\"]}"),
                ruleWithCondition("c4", "{\"any\":[{\"field\":\"data.host\",\"value\":\"nope\"},{\"field\":\"data.host\",\"value\":\"web-1\"}]}"),
                ruleWithCondition("c5", "{\"any\":[{\"field\":\"data.host\",\"value\":\"nope\"}]}"),
                ruleWithCondition("c6", "{\"expression\":\"data.host == 'web-1'\"}"),
                ruleWithCondition("c7", "{\"expression\":\"java.lang.Runtime.exec()\"}"),
                ruleWithCondition("c8", "{\"field\":\"data.host\",\"operator\":\"not_equals\",\"value\":\"linux\"}"),
                ruleWithCondition("c9", "{\"all\":[{\"field\":\"data.host\",\"value\":\"web-1\"},{\"field\":\"data.host\",\"value\":\"nope\"}]}"));
        given(rules.findByTenantIdOrderByPriorityAscUpdatedAtDesc("tenant-a")).willReturn(ruleSet);

        List<Map<String, Object>> rows = service.explain(event("E-X"));

        assertThat(rows).extracting(row -> row.get("conditionMatched"))
                .containsExactly(true, false, false, true, false, true, false, true, false);
        assertThat(rows).extracting(row -> row.get("triggerMatched"))
                .containsOnly(true);
        assertThat(rows).extracting(row -> row.get("groupKey"))
                .containsOnly("");
    }

    // ---- helpers ----

    private com.fasterxml.jackson.databind.JsonNode actions() {
        return mapper.valueToTree(List.of(Map.of("playbookVersionId", "v1")));
    }

    private static Map<String, Object> event(String eventId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "alert.created");
        event.put("data", Map.of("host", "web-1"));
        return event;
    }

    private static SoarAutomationRuleEntity rule(String id, String trigger, String conditionJson,
                                                 String actionsJson) {
        SoarAutomationRuleEntity row = new SoarAutomationRuleEntity();
        row.setId(id);
        row.setTenantId("tenant-a");
        row.setName("rule-" + id);
        row.setEnabled(true);
        row.setPriority(10);
        row.setTriggerType(trigger);
        row.setConditionJson(conditionJson);
        row.setActionsJson(actionsJson);
        row.setSuppressionJson("{}");
        row.setRevision(1);
        row.setRowVersion(1L);
        row.setConflictStrategy("QUEUE");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        return row;
    }

    private static SoarAutomationRuleEntity ruleWithCondition(String id, String conditionJson) {
        return rule(id, "ANY", conditionJson, ACTIONS);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> receiptViews(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("receipts");
    }
}
