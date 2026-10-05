package com.socp.detect.model.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.detect.model.persistence.entity.AnalyzedEntity;
import com.socp.detect.model.persistence.repository.AnalyzedRepository;
import com.socp.detect.model.persistence.store.AnalysisReceiptStore;
import com.socp.detect.model.service.AnalyzeService;
import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.persistence.store.DetectionAlertOutboxService;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.detect.web.service.EntityRiskStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.score.RiskScorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the actual durable producer JSON through the Kafka consumer and analyzer. */
class SecondaryAnalysisMessageContractTest {
    private final DetectionAlertOutboxService outbox = mock(DetectionAlertOutboxService.class);
    private final AnalyzedRepository analyzed = mock(AnalyzedRepository.class);
    private final AnalysisReceiptStore receipts = mock(AnalysisReceiptStore.class);
    private final AlarmConsumer consumer = new AlarmConsumer(
            new AnalyzeService(analyzed, new AlertWindowAggregator(), receipts));

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void forwardedWebAlertRetainsSourceTimeAndTriggerMessageAndIsDeduplicated() throws Exception {
        String payload = forward("web", "SQLi in query", Map.of("src_ip", "203.0.113.9"), 0);
        var json = new ObjectMapper().readTree(payload);
        String alarmId = json.get("id").asText();
        when(receipts.claim(eq("tenant-a"), eq(alarmId), anyString())).thenReturn(true, false);

        consumer.processRecord(alarmId, payload);
        consumer.processRecord(alarmId, payload);

        var row = ArgumentCaptor.forClass(AnalyzedEntity.class);
        verify(analyzed).save(row.capture());
        assertEquals("WEB-ATTACK", row.getValue().getRuleId());
        assertEquals(Instant.parse(json.get("occurredAt").asText()), row.getValue().getTs());
        assertTrue(row.getValue().getMessage().contains("SQLi in query"));
        assertEquals("203.0.113.9", row.getValue().getEntity());
        assertTrue(row.getValue().getMessage().contains("web-host"));
        assertNull(TenantContext.get());
    }

    @Test
    void forwardedFirewallAlertsUseTypedSourceIpInsteadOfThePrimaryHostEntity() throws Exception {
        when(receipts.claim(anyString(), anyString(), anyString())).thenReturn(true);
        for (int i = 0; i < 10; i++) {
            consumer.processRecord("ignored", forward("firewall", "blocked traffic",
                    Map.of("src_ip", "203.0.113.9", "action", "block"), i));
        }

        var row = ArgumentCaptor.forClass(AnalyzedEntity.class);
        verify(analyzed).save(row.capture());
        assertEquals("FW-SCAN", row.getValue().getRuleId());
        assertEquals("203.0.113.9", row.getValue().getEntity());
    }

    @Test
    void existingOutboxPayloadsCanUseTheMatchingEvidenceWithoutNewTriggerEnvelope() throws Exception {
        var mapper = new ObjectMapper();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                forward("web", "XSS in query", Map.of(), 0));
        payload.remove(List.of("triggerEvent", "source", "host"));
        when(receipts.claim(anyString(), anyString(), anyString())).thenReturn(true);

        consumer.processRecord("ignored", payload.toString());

        verify(analyzed).save(argThat(row -> "WEB-ATTACK".equals(row.getRuleId())));
    }

    @Test
    void hostEntityWithoutSourceIpIsNotFedIntoAnIpThreshold() throws Exception {
        when(receipts.claim(anyString(), anyString(), anyString())).thenReturn(true);
        for (int i = 0; i < 10; i++) {
            consumer.processRecord("ignored", forward("firewall", "blocked traffic", Map.of("action", "block"), i));
        }
        verify(analyzed, never()).save(any());
    }

    private String forward(String source, String raw, Map<String, String> fields, int index) {
        var rules = mock(RuleSpecStore.class);
        var risk = mock(EntityRiskStore.class);
        when(risk.recordForAlert(anyString(), anyString(), any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(new RiskScorer.Score(65, "HIGH", Map.of()));
        var canonical = new java.util.LinkedHashMap<>(fields);
        canonical.put("tenant_id", "tenant-a");
        SecurityEvent evidence = new SecurityEvent("event-" + index,
                Instant.parse("2026-09-20T00:00:00Z").plusSeconds(index), source,
                "web-host", raw, canonical, Severity.HIGH);
        Alert alert = new Alert("PRIMARY", "Primary rule", Severity.HIGH,
                "Summary with no raw attack text", "primary-host-entity", List.of(evidence));
        new AlertForwarder(rules, risk, outbox).forward(alert);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq(alert.id()), eq("tenant-a"), payload.capture());
        return payload.getValue();
    }
}
