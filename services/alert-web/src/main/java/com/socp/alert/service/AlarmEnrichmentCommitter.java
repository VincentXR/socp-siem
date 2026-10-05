package com.socp.alert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The enrichment snapshot and its independently replayable SOAR event commit atomically. */
@Service
public class AlarmEnrichmentCommitter {
    private final AlarmRepository alarms;
    private final AlarmDeliveryRegistrar deliveries;
    private final ObjectMapper json;

    public AlarmEnrichmentCommitter(AlarmRepository alarms, AlarmDeliveryRegistrar deliveries, ObjectMapper json) {
        this.alarms = alarms;
        this.deliveries = deliveries;
        this.json = json;
    }

    @Transactional
    public void complete(String tenant, String id, String hits, int score) {
        if (!tenant.equals(TenantContext.require())) throw new IllegalArgumentException("tenant mismatch");
        var alarm = alarms.findForDispositionUpdate(tenant, id)
                .orElseThrow(() -> new IllegalStateException("Missing enrichment alarm"));
        if (alarm.getEnrichedAt() != null) return;
        int currentRisk = Math.max(alarm.getRiskScore() == null ? 0 : alarm.getRiskScore(), score);
        alarm.setTiHits(hits);
        alarm.setRiskScore(currentRisk);
        alarm.setRiskLevel(com.socp.rule.score.RiskScorer.level(currentRisk));
        alarm.setEnrichedAt(Instant.now());
        alarms.save(alarm);
        if ("SUPPRESSED".equals(alarm.getStatus())) return;
        try {
            Map<String, Object> event = Map.of(
                    "schemaVersion", "soar.event",
                    "eventType", "alert.enriched", "eventId", "alert:" + id + ":enriched:1",
                    "tenantId", tenant, "occurredAt", alarm.getEnrichedAt().toString(),
                    "producer", "alert-web", "subject", Map.of("type", "alert", "id", id),
                    "data", AlarmPayloadCodec.read(AlarmPayloadCodec.write(alarm, List.of())),
                    "trace", Map.of("correlationId", id, "causationId", id, "automationDepth", 0));
            deliveries.registerEnriched(tenant, id, json.writeValueAsString(event));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode enrichment event", failure);
        }
    }
}
