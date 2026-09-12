package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.persistence.entity.AlarmEvaluationEntity;
import com.socp.soar.web.persistence.repository.AlarmEvaluationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** Coverage for event evaluation, the in-progress guard, and failure redaction. */
@ExtendWith(MockitoExtension.class)
class AlarmEvaluationServiceBranchCoverageTest {

    @Mock
    private PlaybookExecutor executor;
    @Mock
    private AlarmEvaluationRepository repository;
    @Mock
    private SoarAutomationRuleService automationRules;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluationBuildsEnvelopeAndDelegatesToAutomationRules() {
        SoarRuntimeProperties properties = new SoarRuntimeProperties();
        properties.setEvaluationEnabled(true);
        AlarmEvaluationService service = new AlarmEvaluationService(executor, repository,
                automationRules, properties);
        given(repository.findByIdAndTenantIdForUpdate(any(), any())).willReturn(Optional.empty());
        given(repository.findByIdAndTenantId(any(), any())).willReturn(Optional.empty());
        given(repository.saveAndFlush(any(AlarmEvaluationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(repository.save(any(AlarmEvaluationEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(automationRules.evaluate(any())).willReturn(Map.of("matchedRuns", 2));
        Map<String, Object> alarm = new java.util.LinkedHashMap<>();
        alarm.put("id", "AL-1");
        alarm.put("occurredAt", "2026-01-01T00:00:00Z");
        alarm.put("triggerEventId", "TE-9");
        alarm.put("entities", List.of(Map.of("type", "host", "id", "web-1")));
        alarm.put("evidence", List.of());

        Map<String, Object> result = service.evaluate(alarm);

        assertThat(result).containsKey("automation");
        assertThat((Map<String, Object>) result.get("automation")).containsEntry("matchedRuns", 2);
        ArgumentCaptor<Map<String, Object>> envelopeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(automationRules).evaluate(envelopeCaptor.capture());
        Map<String, Object> envelope = envelopeCaptor.getValue();
        assertThat(envelope).containsEntry("schemaVersion", "soar.event")
                .containsEntry("eventId", "alert:AL-1:created:1")
                .containsEntry("eventType", "alert.created")
                .containsEntry("tenantId", "tenant-a")
                .containsEntry("producer", "alert-web");
        assertThat((Map<String, Object>) envelope.get("data")).containsKey("alert")
                .containsKey("entities")
                .containsKey("evidence");
        assertThat((Map<String, Object>) envelope.get("trace"))
                .containsEntry("correlationId", "AL-1")
                .containsEntry("causationId", "TE-9")
                .containsEntry("automationDepth", 0);
        verify(repository).save(any(AlarmEvaluationEntity.class));
    }

    @Test
    void evaluationFailureIsRecordedWithRedactedErrorAndRethrown() {
        SoarRuntimeProperties properties = new SoarRuntimeProperties();
        properties.setEvaluationEnabled(true);
        AlarmEvaluationService service = new AlarmEvaluationService(executor, repository,
                automationRules, properties);
        given(repository.findByIdAndTenantIdForUpdate(any(), any())).willReturn(Optional.empty());
        given(repository.findByIdAndTenantId(any(), any())).willReturn(Optional.empty());
        given(repository.saveAndFlush(any(AlarmEvaluationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(automationRules.evaluate(any()))
                .willThrow(new RuntimeException("token=abc123 and bearer eyJ.qq boom"));

        assertThatThrownBy(() -> service.evaluate(Map.<String, Object>of("id", "AL-2")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("token=");

        ArgumentCaptor<AlarmEvaluationEntity> captor = ArgumentCaptor.forClass(AlarmEvaluationEntity.class);
        verify(repository).save(captor.capture());
        AlarmEvaluationEntity failed = captor.getValue();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getLastError())
                .contains("token=[REDACTED]")
                .contains("bearer [REDACTED]")
                .doesNotContain("abc123")
                .doesNotContain("eyJ.qq");
    }

    @Test
    void legacyExecutorPathRecordsFailureWithRedaction() {
        AlarmEvaluationService service = new AlarmEvaluationService(executor, repository);
        given(repository.findByIdAndTenantIdForUpdate(any(), any())).willReturn(Optional.empty());
        given(repository.findByIdAndTenantId(any(), any())).willReturn(Optional.empty());
        given(repository.saveAndFlush(any(AlarmEvaluationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(executor.evaluate(any()))
                .willThrow(new RuntimeException("password=hunter2 boom"));

        assertThatThrownBy(() -> service.evaluate(Map.<String, Object>of("id", "AL-3")))
                .hasMessageContaining("boom");

        ArgumentCaptor<AlarmEvaluationEntity> captor = ArgumentCaptor.forClass(AlarmEvaluationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getLastError()).contains("password=[REDACTED]");
    }

    @Test
    void eventEvaluationIsRejectedWhenDisabled() {
        SoarRuntimeProperties properties = new SoarRuntimeProperties();
        properties.setEvaluationEnabled(false);
        AlarmEvaluationService service = new AlarmEvaluationService(executor, repository,
                automationRules, properties);
        given(repository.findByIdAndTenantIdForUpdate(any(), any())).willReturn(Optional.empty());
        given(repository.findByIdAndTenantId(any(), any())).willReturn(Optional.empty());
        given(repository.saveAndFlush(any(AlarmEvaluationEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(repository.save(any(AlarmEvaluationEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.evaluate(Map.<String, Object>of("id", "AL-EVALUATION-OFF")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(failure -> assertThat(((org.springframework.web.server.ResponseStatusException) failure)
                        .getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        verify(automationRules, org.mockito.Mockito.never()).evaluate(any());
    }

    @Test
    void inProgressEvaluationIsRejectedUntilReceiptGoesStale() {
        AlarmEvaluationService service = new AlarmEvaluationService(executor, repository);
        AlarmEvaluationEntity processing = receipt("PROCESSING", Instant.now());
        given(repository.findByIdAndTenantIdForUpdate(any(), any()))
                .willReturn(Optional.of(processing));

        assertThatThrownBy(() -> service.evaluate(Map.<String, Object>of("id", "AL-4")))
                .isInstanceOf(AlarmEvaluationService.EvaluationInProgressException.class)
                .hasMessageContaining("already in progress");

        // A stale PROCESSING receipt (older than 2 minutes) is retried.
        AlarmEvaluationEntity stale = receipt("PROCESSING", Instant.now().minusSeconds(180));
        given(repository.findByIdAndTenantIdForUpdate(any(), any())).willReturn(Optional.of(stale));
        given(repository.saveAndFlush(any(AlarmEvaluationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(repository.save(any(AlarmEvaluationEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(executor.evaluate(any())).willReturn(Map.of("status", "DONE"));

        Map<String, Object> result = service.evaluate(Map.<String, Object>of("id", "AL-4"));

        assertThat(result).containsEntry("status", "DONE");
        assertThat(stale.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void completedEvaluationReturnsCachedResultMarkedDuplicate() {
        AlarmEvaluationService service = new AlarmEvaluationService(executor, repository);
        AlarmEvaluationEntity completed = receipt("COMPLETED", Instant.now());
        completed.setResultJson("{\"status\":\"DONE\"}");
        given(repository.findByIdAndTenantIdForUpdate(any(), any())).willReturn(Optional.of(completed));

        Map<String, Object> result = service.evaluate(Map.<String, Object>of("id", "AL-5"));

        assertThat(result).containsEntry("status", "DONE").containsEntry("duplicate", true);
        verify(repository, org.mockito.Mockito.never()).save(any(AlarmEvaluationEntity.class));
    }

    private static AlarmEvaluationEntity receipt(String status, Instant updatedAt) {
        AlarmEvaluationEntity row = new AlarmEvaluationEntity();
        row.setId("receipt-1");
        row.setTenantId("tenant-a");
        row.setAlarmId("AL-4");
        row.setStatus(status);
        row.setCreatedAt(updatedAt);
        row.setUpdatedAt(updatedAt);
        return row;
    }
}
