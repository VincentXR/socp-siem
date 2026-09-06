package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SoarV2AutomationRuleCoverageTest {

    @Mock
    private SoarAutomationRuleRepository rules;
    @Mock
    private SoarV2Service soar;
    @Mock
    private SoarTriggerReceiptRepository receipts;
    @Mock
    private SoarRunRepository runs;

    private ObjectMapper mapper;
    private SoarV2AutomationRuleService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        mapper = new ObjectMapper();
        service = new SoarV2AutomationRuleService(rules, soar, mapper, receipts, runs);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------- create ----------

    @Test
    void createPersistsTenantScopedRuleAndClampsPriority() throws Exception {
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "PUBLISHED"));
        given(rules.save(any(SoarAutomationRuleEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        JsonNode actions = mapper.readTree("[{\"playbookVersionId\":\"pv-1\"}]");

        Map<String, Object> view = service.create(
                "  webhook rule  ", "alert.created", 99_999, true, null, actions, null);

        assertThat(view)
                .containsEntry("name", "webhook rule")
                .containsEntry("triggerType", "ALERT.CREATED")
                .containsEntry("priority", 10_000)
                .containsEntry("enabled", true)
                .containsEntry("revision", 1)
                .containsEntry("createdBy", "operator")
                .containsEntry("conflictStrategy", "QUEUE");

        ArgumentCaptor<SoarAutomationRuleEntity> captor =
                ArgumentCaptor.forClass(SoarAutomationRuleEntity.class);
        verify(rules).save(captor.capture());
        SoarAutomationRuleEntity saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo("tenant-a");
        assertThat(saved.getTriggerType()).isEqualTo("ALERT.CREATED");
        assertThat(saved.getActionsJson()).contains("pv-1");
        assertThat(saved.getSuppressionJson()).isEqualTo("{}");
        assertThat(saved.getCreatedBy()).isEqualTo("operator");
    }

    @Test
    void createRejectsInvalidBasicFieldsAndInlineSecrets() throws Exception {
        JsonNode actions = mapper.readTree("[{\"playbookVersionId\":\"pv-1\"}]");
        JsonNode secretConditions = mapper.readTree("{\"password\":\"hunter2\"}");

        assertThatThrownBy(() -> service.create(null, "alert.created", 1, true, null, actions, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("name is required");

        assertThatThrownBy(() -> service.create("rule", "1bad!", 1, true, null, actions, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("triggerType is invalid");

        assertThatThrownBy(() -> service.create("rule", "alert.created", 1, true, null, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("1..32");

        assertThatThrownBy(() -> service.create("rule", "alert.created", 1, true,
                secretConditions, actions, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("inline secrets");

        verify(rules, never()).save(any(SoarAutomationRuleEntity.class));
    }

    @Test
    void createRejectsInvalidSuppressionPolicy() throws Exception {
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "PUBLISHED"));
        JsonNode actions = mapper.readTree("[{\"playbookVersionId\":\"pv-1\"}]");

        assertThatThrownBy(() -> service.create("rule", "alert.created", 1, true, null, actions,
                mapper.readTree("{\"groupBy\":\"9bad\"}")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("groupBy");

        assertThatThrownBy(() -> service.create("rule", "alert.created", 1, true, null, actions,
                mapper.readTree("{\"conflictStrategy\":\"INVALID\"}")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("QUEUE or SUPPRESS");

        assertThatThrownBy(() -> service.create("rule", "alert.created", 1, true, null, actions,
                mapper.readTree("{\"cooldownSeconds\":-5}")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("bounded non-negative integer");

        assertThatThrownBy(() -> service.create("rule", "alert.created", 1, true, null, actions,
                mapper.readTree("{\"validFrom\":\"2026-01-02T00:00:00Z\","
                        + "\"validUntil\":\"2026-01-01T00:00:00Z\"}")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("validUntil must be after validFrom");
    }

    @Test
    void createRejectsUnpublishedTargetVersion() throws Exception {
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "DRAFT"));
        JsonNode actions = mapper.readTree("[{\"playbookVersionId\":\"pv-1\"}]");

        assertThatThrownBy(() -> service.create("rule", "alert.created", 1, true, null, actions, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("published version");
        verify(rules, never()).save(any(SoarAutomationRuleEntity.class));
    }

    // ---------- get / list ----------

    @Test
    void getReturnsViewOrThrowsNotFound() {
        given(rules.findByTenantIdAndId("tenant-a", "r-1"))
                .willReturn(Optional.of(rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]")));
        given(rules.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());

        Map<String, Object> view = service.get("r-1");
        assertThat(view).containsEntry("id", "r-1").containsEntry("name", "rule-r-1");

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("not found");
    }

    @Test
    void listReturnsTenantScopedViewsAndSupportsPaging() {
        SoarAutomationRuleEntity first = rule("r-1", "ALERT.CREATED", 1, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        SoarAutomationRuleEntity second = rule("r-2", "DNS.QUERY", 2, "{}", "[{\"playbookVersionId\":\"pv-2\"}]");
        given(rules.findByTenantIdOrderByPriorityAscUpdatedAtDesc("tenant-a"))
                .willReturn(List.of(first, second));
        assertThat(service.list()).hasSize(2);

        Pageable pageable = PageRequest.of(0, 10);
        given(rules.findByTenantIdOrderByPriorityAscUpdatedAtDesc("tenant-a", pageable))
                .willReturn(new PageImpl<>(List.of(first), pageable, 1));
        var page = service.list(pageable);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0)).containsEntry("id", "r-1");
    }

    // ---------- update ----------

    @Test
    void updateAppliesChangesAndBumpsRevision() throws Exception {
        SoarAutomationRuleEntity entity =
                rule("r-1", "ALERT.CREATED", 10, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r-1")).willReturn(Optional.of(entity));
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "PUBLISHED"));
        given(rules.save(any(SoarAutomationRuleEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        JsonNode actions = mapper.readTree("[{\"playbookVersionId\":\"pv-1\"}]");

        Map<String, Object> view = service.update("r-1", "new name", "dns.query", 20, true,
                null, actions, null, null);

        assertThat(view)
                .containsEntry("name", "new name")
                .containsEntry("triggerType", "DNS.QUERY")
                .containsEntry("priority", 20)
                .containsEntry("revision", 2);
        verify(rules).save(entity);
    }

    @Test
    void updateRejectsStaleRowVersionWithConflict() {
        SoarAutomationRuleEntity entity =
                rule("r-1", "ALERT.CREATED", 10, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        entity.setRowVersion(3L);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r-1")).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update("r-1", "new name", "alert.created", 10, true,
                null, null, null, 2L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("changed by another operator");
        verify(rules, never()).save(any(SoarAutomationRuleEntity.class));
    }

    // ---------- patch ----------

    @Test
    void patchMergesPartialBodyWithExistingRule() {
        SoarAutomationRuleEntity entity =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        entity.setRowVersion(7L);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r-1")).willReturn(Optional.of(entity));
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "PUBLISHED"));
        given(rules.save(any(SoarAutomationRuleEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> view = service.patch("r-1", Map.of("name", "patched", "rowVersion", 7));

        assertThat(view)
                .containsEntry("name", "patched")
                .containsEntry("priority", 5)
                .containsEntry("enabled", true)
                .containsEntry("revision", 2);
    }

    @Test
    void patchRejectsStaleRowVersion() {
        SoarAutomationRuleEntity entity =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        entity.setRowVersion(7L);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r-1")).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.patch("r-1", Map.of("rowVersion", 6)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("changed by another operator");
    }

    // ---------- remove / setEnabled ----------

    @Test
    void removeTombstonesRuleByDisablingIt() {
        SoarAutomationRuleEntity entity =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r-1")).willReturn(Optional.of(entity));

        Map<String, Object> view = service.remove("r-1");

        assertThat(view).containsEntry("enabled", false);
        assertThat(entity.isEnabled()).isFalse();
        verify(rules).save(entity);
    }

    @Test
    void setEnabledRevalidatesPublishedTargetBeforeEnabling() {
        SoarAutomationRuleEntity entity =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        entity.setEnabled(false);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r-1")).willReturn(Optional.of(entity));
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "DRAFT"));

        assertThatThrownBy(() -> service.setEnabled("r-1", true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("published version");
        assertThat(entity.isEnabled()).isFalse();
    }

    @Test
    void setEnabledSucceedsWhenTargetVersionIsPublished() {
        SoarAutomationRuleEntity entity =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        entity.setEnabled(false);
        given(rules.findByTenantIdAndIdForUpdate("tenant-a", "r-1")).willReturn(Optional.of(entity));
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "PUBLISHED"));

        Map<String, Object> view = service.setEnabled("r-1", true);

        assertThat(view).containsEntry("enabled", true);
        verify(rules, times(1)).save(entity);
    }

    // ---------- evaluate ----------

    @Test
    void evaluateAcceptsMatchingRuleAndStartsRun() {
        SoarAutomationRuleEntity rule =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a")).willReturn(List.of(rule));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                "tenant-a", "evt-1", "r-1", 1)).willReturn(Optional.empty());
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "PUBLISHED"));
        given(soar.queueManualRun(any(), any(), any(), any()))
                .willReturn(Map.of("runId", "run-1", "status", "QUEUED"));

        Map<String, Object> result = service.evaluate(event("evt-1", Map.of("riskLevel", "HIGH")));

        assertThat(result).containsEntry("eventId", "evt-1").containsEntry("matchedRuns", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runsView = (List<Map<String, Object>>) result.get("runs");
        assertThat(runsView).hasSize(1);
        assertThat(runsView.get(0)).containsEntry("runId", "run-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receiptsView = (List<Map<String, Object>>) result.get("receipts");
        assertThat(receiptsView).hasSize(1);
        assertThat(receiptsView.get(0))
                .containsEntry("status", "ACCEPTED")
                .containsEntry("runId", "run-1")
                .containsEntry("ruleId", "r-1");

        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputs = ArgumentCaptor.forClass(Map.class);
        verify(soar).queueManualRun(requestId.capture(), eq("pv-1"), any(), inputs.capture());
        assertThat(requestId.getValue()).startsWith("rule-r-1-");
        assertThat(inputs.getValue()).containsEntry("eventId", "evt-1").containsEntry("automationDepth", 1);

        ArgumentCaptor<SoarTriggerReceiptEntity> saved = ArgumentCaptor.forClass(SoarTriggerReceiptEntity.class);
        verify(receipts).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(saved.getValue().getEventId()).isEqualTo("evt-1");
        assertThat(saved.getValue().getStatus()).isEqualTo("ACCEPTED");
        assertThat(saved.getValue().getRunId()).isEqualTo("run-1");
    }

    @Test
    void evaluateSkipsRuleThatDoesNotMatchTriggerOrCondition() {
        SoarAutomationRuleEntity wrongTrigger =
                rule("r-1", "DNS.QUERY", 1, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        SoarAutomationRuleEntity wrongCondition = rule("r-2", "ALERT.CREATED", 2,
                "{\"field\":\"data.riskLevel\",\"operator\":\"equals\",\"value\":\"LOW\"}",
                "[{\"playbookVersionId\":\"pv-1\"}]");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a"))
                .willReturn(List.of(wrongTrigger, wrongCondition));

        Map<String, Object> result = service.evaluate(event("evt-1", Map.of("riskLevel", "HIGH")));

        assertThat(result).containsEntry("matchedRuns", 0);
        assertThat((List<?>) result.get("receipts")).isEmpty();
        assertThat((List<?>) result.get("runs")).isEmpty();
        verify(soar, never()).queueManualRun(any(), any(), any(), any());
        verify(receipts, never()).save(any(SoarTriggerReceiptEntity.class));
    }

    @Test
    void evaluateReturnsExistingReceiptForDuplicateEvent() {
        SoarAutomationRuleEntity rule =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        SoarTriggerReceiptEntity existing = receipt("rc-1", "evt-1", "r-1");
        existing.setStatus("ACCEPTED");
        existing.setRunId("run-old");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a")).willReturn(List.of(rule));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                "tenant-a", "evt-1", "r-1", 1)).willReturn(Optional.of(existing));

        Map<String, Object> result = service.evaluate(event("evt-1", Map.of("riskLevel", "HIGH")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receiptsView = (List<Map<String, Object>>) result.get("receipts");
        assertThat(receiptsView).hasSize(1);
        assertThat(receiptsView.get(0))
                .containsEntry("id", "rc-1")
                .containsEntry("status", "ACCEPTED")
                .containsEntry("runId", "run-old");
        assertThat(result).containsEntry("matchedRuns", 0);
        verify(soar, never()).queueManualRun(any(), any(), any(), any());
        verify(receipts, never()).save(any(SoarTriggerReceiptEntity.class));
    }

    @Test
    void evaluateSuppressesDuplicateEventIdWithinDedupWindow() {
        SoarAutomationRuleEntity rule =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        rule.setDedupWindowSeconds(600L);
        SoarTriggerReceiptEntity prior = receipt("rc-0", "evt-1", "r-1");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a")).willReturn(List.of(rule));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                "tenant-a", "evt-1", "r-1", 1)).willReturn(Optional.empty());
        given(receipts.findByTenantIdAndAutomationRuleIdAndCreatedAtAfter(
                eq("tenant-a"), eq("r-1"), any(Instant.class))).willReturn(List.of(prior));
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "PUBLISHED"));

        Map<String, Object> result = service.evaluate(event("evt-1", Map.of("riskLevel", "HIGH")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receiptsView = (List<Map<String, Object>>) result.get("receipts");
        assertThat(receiptsView.get(0))
                .containsEntry("status", "SUPPRESSED")
                .containsEntry("reason", "DEDUP_WINDOW");
        ArgumentCaptor<SoarTriggerReceiptEntity> saved = ArgumentCaptor.forClass(SoarTriggerReceiptEntity.class);
        verify(receipts).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("SUPPRESSED");
        assertThat(saved.getValue().getReason()).isEqualTo("DEDUP_WINDOW");
        verify(soar, never()).queueManualRun(any(), any(), any(), any());
    }

    @Test
    void evaluateSuppressesDuringCooldownForSameGroupKey() {
        SoarAutomationRuleEntity rule =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        rule.setCooldownSeconds(300L);
        rule.setGroupBy("data.host");
        SoarTriggerReceiptEntity prior = receipt("rc-0", "evt-0", "r-1");
        prior.setStatus("ACCEPTED");
        prior.setGroupKey("host-1");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a")).willReturn(List.of(rule));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                "tenant-a", "evt-1", "r-1", 1)).willReturn(Optional.empty());
        given(receipts.findByTenantIdAndAutomationRuleIdAndCreatedAtAfter(
                eq("tenant-a"), eq("r-1"), any(Instant.class))).willReturn(List.of(prior));
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "PUBLISHED"));

        Map<String, Object> result = service.evaluate(event("evt-1", Map.of("host", "host-1")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receiptsView = (List<Map<String, Object>>) result.get("receipts");
        assertThat(receiptsView.get(0))
                .containsEntry("status", "SUPPRESSED")
                .containsEntry("reason", "COOLDOWN")
                .containsEntry("groupKey", "host-1");
        verify(soar, never()).queueManualRun(any(), any(), any(), any());
    }

    @Test
    void evaluateSuppressesWhenCapacityReachedUnderSupressStrategy() {
        SoarAutomationRuleEntity rule =
                rule("r-1", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        rule.setMaxConcurrentRuns(1);
        rule.setConflictStrategy("SUPPRESS");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a")).willReturn(List.of(rule));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                "tenant-a", "evt-1", "r-1", 1)).willReturn(Optional.empty());
        given(soar.getVersionById("pv-1")).willReturn(Map.of("status", "PUBLISHED"));
        given(runs.countByTenantIdAndPlaybookVersionIdInAndStatusIn(
                eq("tenant-a"), any(), any())).willReturn(1L);

        Map<String, Object> result = service.evaluate(event("evt-1", Map.of("riskLevel", "HIGH")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receiptsView = (List<Map<String, Object>>) result.get("receipts");
        assertThat(receiptsView.get(0))
                .containsEntry("status", "SUPPRESSED")
                .containsEntry("reason", "CAPACITY");
        verify(soar, never()).queueManualRun(any(), any(), any(), any());
    }

    @Test
    void evaluateSuppressesEventsBeyondAutomationDepthLimit() {
        Map<String, Object> event = event("evt-deep", Map.of("riskLevel", "HIGH"));
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("automationDepth", 6);
        event.put("trace", trace);

        Map<String, Object> result = service.evaluate(event);

        assertThat(result).containsEntry("matchedRuns", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> receiptsView = (List<Map<String, Object>>) result.get("receipts");
        assertThat(receiptsView).hasSize(1);
        assertThat(receiptsView.get(0))
                .containsEntry("status", "SUPPRESSED")
                .containsEntry("reason", "AUTOMATION_DEPTH_EXCEEDED");
        verify(rules, never()).findByTenantIdAndEnabledTrueOrderByPriorityAsc(anyString());
        verify(soar, never()).queueManualRun(any(), any(), any(), any());
    }

    @Test
    void evaluateRejectsCrossTenantAndMalformedEvents() {
        Map<String, Object> foreignTenant = event("evt-1", Map.of());
        foreignTenant.put("tenantId", "tenant-b");
        assertThatThrownBy(() -> service.evaluate(foreignTenant))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("tenant does not match");

        assertThatThrownBy(() -> service.evaluate(Map.of("eventType", "alert.created")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("eventId");

        assertThatThrownBy(() -> service.evaluate(Map.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("event envelope is required");
    }

    @Test
    void evaluateAppliesPriorityOrderAcrossMatchingRules() {
        SoarAutomationRuleEntity high =
                rule("r-high", "ALERT.CREATED", 1, "{}", "[{\"playbookVersionId\":\"pv-high\"}]");
        SoarAutomationRuleEntity low =
                rule("r-low", "ALERT.CREATED", 99, "{}", "[{\"playbookVersionId\":\"pv-low\"}]");
        given(rules.findByTenantIdAndEnabledTrueOrderByPriorityAsc("tenant-a")).willReturn(List.of(high, low));
        given(receipts.findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
                anyString(), anyString(), anyString(), anyInt())).willReturn(Optional.empty());
        given(soar.getVersionById(anyString())).willReturn(Map.of("status", "PUBLISHED"));
        given(soar.queueManualRun(any(), any(), any(), any()))
                .willAnswer(invocation -> Map.of("runId", "run-" + invocation.getArgument(1)));

        Map<String, Object> result = service.evaluate(event("evt-1", Map.of("riskLevel", "HIGH")));

        assertThat(result).containsEntry("matchedRuns", 2);
        InOrder inOrder = inOrder(soar);
        inOrder.verify(soar).queueManualRun(anyString(), eq("pv-high"), any(), any());
        inOrder.verify(soar).queueManualRun(anyString(), eq("pv-low"), any(), any());
    }

    // ---------- explain ----------

    @Test
    void explainReportsMatchDetailsWithoutSideEffects() {
        SoarAutomationRuleEntity noCondition =
                rule("r-1", "ALERT.CREATED", 1, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        SoarAutomationRuleEntity compoundCondition = rule("r-2", "ALERT.CREATED", 2,
                "{\"all\":[{\"field\":\"data.riskLevel\",\"operator\":\"not_equals\",\"value\":\"LOW\"},"
                        + "{\"field\":\"data.tag\",\"operator\":\"in\",\"value\":[\"x\",\"y\"]}]}",
                "[{\"playbookVersionId\":\"pv-1\"}]");
        SoarAutomationRuleEntity unknownShape =
                rule("r-3", "ALERT.CREATED", 3, "{\"unknown\":\"shape\"}", "[{\"playbookVersionId\":\"pv-1\"}]");
        SoarAutomationRuleEntity wrongTrigger =
                rule("r-4", "DNS.QUERY", 4, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        SoarAutomationRuleEntity disabled =
                rule("r-5", "ALERT.CREATED", 5, "{}", "[{\"playbookVersionId\":\"pv-1\"}]");
        disabled.setEnabled(false);
        given(rules.findByTenantIdOrderByPriorityAscUpdatedAtDesc("tenant-a"))
                .willReturn(List.of(noCondition, compoundCondition, unknownShape, wrongTrigger, disabled));

        List<Map<String, Object>> result = service.explain(event("evt-9", Map.of("riskLevel", "HIGH", "tag", "x")));

        assertThat(result).hasSize(5);
        assertThat(result.get(0))
                .containsEntry("ruleId", "r-1")
                .containsEntry("triggerMatched", true)
                .containsEntry("conditionMatched", true)
                .containsEntry("matched", true);
        assertThat(result.get(1)).containsEntry("ruleId", "r-2").containsEntry("matched", true);
        assertThat(result.get(2))
                .containsEntry("ruleId", "r-3")
                .containsEntry("conditionMatched", false)
                .containsEntry("matched", false);
        assertThat(result.get(3))
                .containsEntry("ruleId", "r-4")
                .containsEntry("triggerMatched", false)
                .containsEntry("matched", false);
        assertThat(result.get(4))
                .containsEntry("ruleId", "r-5")
                .containsEntry("enabled", false)
                .containsEntry("matched", false);
        verify(soar, never()).queueManualRun(any(), any(), any(), any());
        verify(receipts, never()).save(any(SoarTriggerReceiptEntity.class));
    }

    // ---------- helpers ----------

    private static Map<String, Object> event(String eventId, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "alert.created");
        event.put("data", data);
        return event;
    }

    private static SoarAutomationRuleEntity rule(String id, String triggerType, int priority,
                                                 String conditionJson, String actionsJson) {
        SoarAutomationRuleEntity rule = new SoarAutomationRuleEntity();
        rule.setId(id);
        rule.setTenantId("tenant-a");
        rule.setName("rule-" + id);
        rule.setTriggerType(triggerType);
        rule.setPriority(priority);
        rule.setEnabled(true);
        rule.setConditionJson(conditionJson);
        rule.setActionsJson(actionsJson);
        rule.setSuppressionJson("{}");
        rule.setRevision(1);
        rule.setConflictStrategy("QUEUE");
        rule.setCreatedBy("operator");
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(Instant.now());
        rule.setRowVersion(1L);
        return rule;
    }

    private static SoarTriggerReceiptEntity receipt(String id, String eventId, String ruleId) {
        SoarTriggerReceiptEntity receipt = new SoarTriggerReceiptEntity();
        receipt.setId(id);
        receipt.setTenantId("tenant-a");
        receipt.setEventId(eventId);
        receipt.setAutomationRuleId(ruleId);
        receipt.setRuleRevision(1);
        receipt.setStatus("EVALUATING");
        receipt.setGroupKey("");
        receipt.setCreatedAt(Instant.now());
        receipt.setUpdatedAt(Instant.now());
        return receipt;
    }
}
