package com.socp.detect.web.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.detect.web.persistence.store.DetectionAlertOutboxService;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.detect.web.service.EntityRiskStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.score.RiskScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Materializes detection alerts into the durable Detection -> Alert Web
 * outbox.  The detection worker never performs a remote HTTP call: the
 * scheduled outbox publisher owns retries, tenant propagation, and the
 * optional detect-model fan-out after Alert Web acknowledges the payload.
 */
@Component
public class AlertForwarder {

    private static final Logger log = LoggerFactory.getLogger(AlertForwarder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RuleSpecStore ruleStore;
    private final EntityRiskStore riskStore;
    private final DetectionAlertOutboxService outbox;
    private final DetectionStateStore stateStore;

    @org.springframework.beans.factory.annotation.Autowired
    public AlertForwarder(RuleSpecStore ruleStore, EntityRiskStore riskStore,
                          DetectionAlertOutboxService outbox, DetectionStateStore stateStore) {
        this.ruleStore = ruleStore;
        this.riskStore = riskStore;
        this.outbox = outbox;
        this.stateStore = stateStore;
    }

    /** Source-compatible constructor used by focused unit tests. */
    public AlertForwarder(RuleSpecStore ruleStore, EntityRiskStore riskStore,
                          DetectionAlertOutboxService outbox) {
        this(ruleStore, riskStore, outbox, null);
    }

    /** Persist before the detection worker continues; remote delivery is retried asynchronously. */
    public void forward(Alert alert) {
        forwardAll((SecurityEvent) null, alert == null ? List.of() : List.of(alert));
    }

    /**
     * Persist all alerts emitted by one source event and complete that event in
     * the same database transaction. An empty alert list is a valid result.
     */
    @Transactional
    public void forwardAll(SecurityEvent sourceEvent, List<Alert> alerts) {
        if (alerts == null) alerts = List.of();
        for (Alert alert : alerts) forwardOne(alert);
        if (stateStore != null && sourceEvent != null) {
            stateStore.markCompleted(sourceEvent);
        }
    }

    /** Compatibility overload for focused tests and non-Kafka callers. */
    public void forwardAll(String eventId, List<Alert> alerts) {
        if (alerts == null) alerts = List.of();
        for (Alert alert : alerts) forwardOne(alert);
        if (stateStore != null && eventId != null && !eventId.isBlank()) {
            stateStore.markCompleted(TenantContext.require(), eventId);
        }
    }

    private void forwardOne(Alert alert) {
        if (alert == null || alert.id() == null || alert.id().isBlank()) {
            log.warn("Cannot persist detection alert without a deterministic alert id");
            return;
        }
        String tenant = resolveTenant(alert);
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            // Kafka callbacks run on a worker thread, so the HTTP request's
            // ThreadLocal tenant is not available here. The canonical event
            // carries tenant_id specifically for this asynchronous boundary.
            Map<String, Object> spec = ruleStore.get(alert.ruleId());
            String mitre = spec == null ? "" : String.valueOf(spec.getOrDefault("mitre", ""));
            if (mitre.isBlank() || "null".equalsIgnoreCase(mitre)) mitre = null;

            RiskScorer.Score score = riskStore.recordForAlert(
                    alert.id(), alert.entity(), alert.severity(), mitre,
                    alert.ruleId(), alert.ruleName(), 0);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", alert.id());
            payload.put("tenantId", tenant);
            payload.put("sourceAlertId", alert.id());
            payload.put("ruleId", alert.ruleId());
            payload.put("ruleName", alert.ruleName());
            payload.put("severity", alert.severity().name());
            payload.put("message", alert.message());
            payload.put("entity", alert.entity());
            payload.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(alert.timestamp()));
            Instant alertCreatedAt = Instant.now();
            payload.put("alertCreatedAt", DateTimeFormatter.ISO_INSTANT.format(alertCreatedAt));
            var trigger = triggerEvidence(alert);
            if (trigger != null) {
                payload.put("triggerEventId", trigger.id());
                Instant ingestedAt = triggerIngestedAt(trigger);
                if (ingestedAt != null) {
                    payload.put("triggerIngestedAt", DateTimeFormatter.ISO_INSTANT.format(ingestedAt));
                    long latency = Math.max(0L, alertCreatedAt.toEpochMilli() - ingestedAt.toEpochMilli());
                    payload.put("processingLatencyMs", latency);
                }
            }
            payload.put("riskScore", score.score());
            if (mitre != null) payload.put("mitre", mitre);
            payload.put("evidence", alert.evidence() == null ? List.of() : alert.evidence().stream()
                    .limit(200)
                    .map(AlertForwarder::evidencePayload)
                    .toList());

            outbox.enqueue(alert.id(), tenant, toJson(payload));
            log.debug("Detection alert payload persisted alertId={} tenant={}", alert.id(), tenant);
        }
    }

    private static String resolveTenant(Alert alert) {
        // Evidence is the durable identity carried across asynchronous
        // boundaries. A leftover ThreadLocal must never re-home an alert.
        if (alert.evidence() != null) {
            for (var event : alert.evidence()) {
                if (event == null || event.fields() == null) continue;
                String tenant = event.fields().get("tenant_id");
                if (tenant == null || tenant.isBlank()) tenant = event.fields().get("tenantId");
                if (tenant != null && !tenant.isBlank()) return tenant;
            }
        }
        String current = TenantContext.get();
        if (current != null && !current.isBlank()) return current;
        return TenantContext.require();
    }

    private static String toJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize detection alert " + payload.get("id"), ex);
        }
    }

    private static Map<String, Object> evidencePayload(com.socp.rule.model.SecurityEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", event.id());
        out.put("timestamp", event.timestamp());
        out.put("source", event.source());
        out.put("host", event.host());
        out.put("severity", event.severity() == null ? null : event.severity().name());
        out.put("raw", event.raw());
        out.put("fields", event.fields() == null ? Map.of() : event.fields());
        return out;
    }

    private static com.socp.rule.model.SecurityEvent triggerEvidence(Alert alert) {
        if (alert.evidence() == null) return null;
        for (int i = alert.evidence().size() - 1; i >= 0; i--) {
            if (alert.evidence().get(i) != null) return alert.evidence().get(i);
        }
        return null;
    }

    private static Instant triggerIngestedAt(com.socp.rule.model.SecurityEvent event) {
        if (event.fields() == null) return null;
        for (String key : List.of("socp_bench_ingest_time", "ingested_at", "ingest_time", "socp.ingest_time")) {
            String value = event.fields().get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Instant.parse(value);
            } catch (Exception ignored) {
                // Preserve alert creation even when an external collector has
                // an invalid optional ingest timestamp.
            }
        }
        return null;
    }
}
