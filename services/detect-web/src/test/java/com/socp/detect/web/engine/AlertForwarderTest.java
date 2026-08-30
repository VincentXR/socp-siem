package com.socp.detect.web.engine;

import com.socp.detect.web.persistence.store.DetectionAlertOutboxService;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.detect.web.service.EntityRiskStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.score.RiskScorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AlertForwarderTest {

    @Mock
    private RuleSpecStore ruleStore;

    @Mock
    private EntityRiskStore riskStore;

    @Mock
    private DetectionAlertOutboxService outbox;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void startWithoutLeakedTenant() {
        TenantContext.clear();
    }

    @Test
    void resolvesTenantFromCanonicalEvidenceOnKafkaWorkerThread() {
        when(ruleStore.get("AUTH-PRIVESC")).thenReturn(Map.of("mitre", "T1548"));
        when(riskStore.recordForAlert(anyString(), anyString(), eq(Severity.HIGH), eq("T1548"),
                eq("AUTH-PRIVESC"), eq("Privilege escalation"), anyInt()))
                .thenReturn(new RiskScorer.Score(65, "HIGH", Map.of()));

        SecurityEvent event = new SecurityEvent(
                "event-tenant-1", Instant.parse("2026-08-19T12:00:00Z"), "auth", "host-1",
                "sudo: probe", Map.of("tenant_id", "tenant-b"), Severity.HIGH);
        Alert alert = new Alert("AUTH-PRIVESC", "Privilege escalation", Severity.HIGH,
                "probe", "host-1", List.of(event));

        new AlertForwarder(ruleStore, riskStore, outbox).forward(alert);

        verify(outbox).enqueue(eq(alert.id()), eq("tenant-b"), anyString());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq(alert.id()), eq("tenant-b"), payload.capture());
        assertTrue(payload.getValue().contains("\"tenantId\":\"tenant-b\""));
        assertNull(TenantContext.get());
    }

    @Test
    void canonicalEvidenceTakesPrecedenceAndRequestTenantIsRestored() {
        TenantContext.set("tenant-request");
        when(ruleStore.get("AUTH-PRIVESC")).thenReturn(Map.of());
        when(riskStore.recordForAlert(anyString(), anyString(), eq(Severity.HIGH), eq(null),
                eq("AUTH-PRIVESC"), eq("Privilege escalation"), anyInt()))
                .thenReturn(new RiskScorer.Score(45, "MEDIUM", Map.of()));

        SecurityEvent event = new SecurityEvent(
                "event-tenant-2", Instant.now(), "auth", "host-2", "probe",
                Map.of("tenant_id", "tenant-evidence"), Severity.HIGH);
        Alert alert = new Alert("AUTH-PRIVESC", "Privilege escalation", Severity.HIGH,
                "probe", "host-2", List.of(event));

        new AlertForwarder(ruleStore, riskStore, outbox).forward(alert);

        verify(outbox).enqueue(eq(alert.id()), eq("tenant-evidence"), anyString());
        assertEquals("tenant-request", TenantContext.get());
    }

    @Test
    void persistsTriggerIngestTimestampForPipelineLatencyEvidence() {
        when(ruleStore.get("AUTH-PRIVESC")).thenReturn(Map.of());
        when(riskStore.recordForAlert(anyString(), anyString(), eq(Severity.HIGH), eq(null),
                eq("AUTH-PRIVESC"), eq("Privilege escalation"), anyInt()))
                .thenReturn(new RiskScorer.Score(45, "MEDIUM", Map.of()));
        Instant ingested = Instant.parse("2026-08-19T12:00:00Z");
        SecurityEvent event = new SecurityEvent(
                "event-bench-1", Instant.parse("2026-08-19T12:00:01Z"), "auth", "host-1",
                "sudo: probe", Map.of("tenant_id", "default",
                        "socp_bench_ingest_time", ingested.toString()), Severity.HIGH);
        Alert alert = new Alert("AUTH-PRIVESC", "Privilege escalation", Severity.HIGH,
                "probe", "host-1", List.of(event));

        new AlertForwarder(ruleStore, riskStore, outbox).forward(alert);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq(alert.id()), eq("default"), payload.capture());
        assertTrue(payload.getValue().contains("\"triggerEventId\":\"event-bench-1\""));
        assertTrue(payload.getValue().contains("\"triggerIngestedAt\":\"2026-08-19T12:00:00Z\""));
        assertTrue(payload.getValue().contains("\"alertCreatedAt\":"));
        assertTrue(payload.getValue().contains("\"processingLatencyMs\":"));
    }

    @Test
    void restoresSourceTenantAcrossZeroAlertDurableCompletion() {
        DetectionStateStore stateStore = mock(DetectionStateStore.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertEquals("tenant-worker", TenantContext.require());
            return null;
        }).when(stateStore).markCompleted("tenant-worker", "event-zero-alert");

        SecurityEvent event = new SecurityEvent(
                "event-zero-alert", Instant.now(), "system", "host-1", "heartbeat",
                Map.of("tenant_id", "tenant-worker"), Severity.INFO);

        new AlertForwarder(ruleStore, riskStore, outbox, stateStore)
                .forwardAll(event, List.of());

        verify(stateStore).markCompleted("tenant-worker", "event-zero-alert");
        assertNull(TenantContext.get());
    }

    @Test
    void fallsBackToExistingTenantForLegacyEvidenceWithoutTenant() {
        TenantContext.set("tenant-request");
        when(ruleStore.get("AUTH-PRIVESC")).thenReturn(Map.of());
        when(riskStore.recordForAlert(anyString(), anyString(), eq(Severity.HIGH), eq(null),
                eq("AUTH-PRIVESC"), eq("Privilege escalation"), anyInt()))
                .thenReturn(new RiskScorer.Score(45, "MEDIUM", Map.of()));

        SecurityEvent legacyEvent = new SecurityEvent(
                "event-legacy", Instant.now(), "auth", "host-legacy", "probe",
                Map.of(), Severity.HIGH);
        Alert alert = new Alert("AUTH-PRIVESC", "Privilege escalation", Severity.HIGH,
                "probe", "host-legacy", List.of(legacyEvent));

        new AlertForwarder(ruleStore, riskStore, outbox).forward(alert);

        verify(outbox).enqueue(eq(alert.id()), eq("tenant-request"), anyString());
        assertEquals("tenant-request", TenantContext.get());
    }
}
