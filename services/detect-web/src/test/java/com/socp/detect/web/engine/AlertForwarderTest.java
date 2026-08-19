package com.socp.detect.web.engine;

import com.socp.detect.web.store.DetectionAlertOutboxService;
import com.socp.detect.web.store.RuleSpecStore;
import com.socp.detect.web.ueba.EntityRiskStore;
import com.socp.platform.tenant.TenantContext;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.score.RiskScorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void resolvesTenantFromCanonicalEvidenceOnKafkaWorkerThread() {
        when(ruleStore.get("AUTH-PRIVESC")).thenReturn(Map.of("mitre", "T1548"));
        when(riskStore.record(anyString(), eq(Severity.HIGH), eq("T1548"),
                eq("AUTH-PRIVESC"), eq("Privilege escalation"), anyInt()))
                .thenReturn(new RiskScorer.Score(65, "HIGH", Map.of()));

        SecurityEvent event = new SecurityEvent(
                "event-tenant-1", Instant.parse("2026-08-19T12:00:00Z"), "auth", "host-1",
                "sudo: probe", Map.of("tenant_id", "tenant-b"), Severity.HIGH);
        Alert alert = new Alert("AUTH-PRIVESC", "Privilege escalation", Severity.HIGH,
                "probe", "host-1", List.of(event));

        new AlertForwarder(ruleStore, riskStore, outbox).forward(alert);

        verify(outbox).enqueue(eq(alert.id()), eq("tenant-b"), anyString());
        assertNull(TenantContext.get());
    }

    @Test
    void requestTenantTakesPrecedenceAndIsRestored() {
        TenantContext.set("tenant-request");
        when(ruleStore.get("AUTH-PRIVESC")).thenReturn(Map.of());
        when(riskStore.record(anyString(), eq(Severity.HIGH), eq(null),
                eq("AUTH-PRIVESC"), eq("Privilege escalation"), anyInt()))
                .thenReturn(new RiskScorer.Score(45, "MEDIUM", Map.of()));

        SecurityEvent event = new SecurityEvent(
                "event-tenant-2", Instant.now(), "auth", "host-2", "probe",
                Map.of("tenant_id", "tenant-evidence"), Severity.HIGH);
        Alert alert = new Alert("AUTH-PRIVESC", "Privilege escalation", Severity.HIGH,
                "probe", "host-2", List.of(event));

        new AlertForwarder(ruleStore, riskStore, outbox).forward(alert);

        verify(outbox).enqueue(eq(alert.id()), eq("tenant-request"), anyString());
        assertEquals("tenant-request", TenantContext.get());
    }
}
