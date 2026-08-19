package com.socp.detect.web.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.detect.web.store.DetectionAlertOutboxService;
import com.socp.detect.web.store.RuleSpecStore;
import com.socp.detect.web.ueba.EntityRiskStore;
import com.socp.platform.tenant.TenantContext;
import com.socp.rule.model.Alert;
import com.socp.rule.score.RiskScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
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

    public AlertForwarder(RuleSpecStore ruleStore, EntityRiskStore riskStore,
                          DetectionAlertOutboxService outbox) {
        this.ruleStore = ruleStore;
        this.riskStore = riskStore;
        this.outbox = outbox;
    }

    /** Persist before the detection worker continues; remote delivery is retried asynchronously. */
    public void forward(Alert alert) {
        if (alert == null || alert.id() == null || alert.id().isBlank()) {
            log.warn("Cannot persist detection alert without a deterministic alert id");
            return;
        }
        String tenant = resolveTenant(alert);
        String previousTenant = TenantContext.get();
        try {
            // Kafka callbacks run on a worker thread, so the HTTP request's
            // ThreadLocal tenant is not available here. The canonical event
            // carries tenant_id specifically for this asynchronous boundary.
            TenantContext.set(tenant);
            Map<String, Object> spec = ruleStore.get(alert.ruleId());
            String mitre = spec == null ? "" : String.valueOf(spec.getOrDefault("mitre", ""));
            if (mitre.isBlank() || "null".equalsIgnoreCase(mitre)) mitre = null;

            RiskScorer.Score score = riskStore.record(
                    alert.entity(), alert.severity(), mitre, alert.ruleId(), alert.ruleName(), 0);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", alert.id());
            payload.put("sourceAlertId", alert.id());
            payload.put("ruleId", alert.ruleId());
            payload.put("ruleName", alert.ruleName());
            payload.put("severity", alert.severity().name());
            payload.put("message", alert.message());
            payload.put("entity", alert.entity());
            payload.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(alert.timestamp()));
            payload.put("riskScore", score.score());
            if (mitre != null) payload.put("mitre", mitre);
            payload.put("evidence", alert.evidence() == null ? List.of() : alert.evidence().stream()
                    .limit(200)
                    .map(AlertForwarder::evidencePayload)
                    .toList());

            outbox.enqueue(alert.id(), tenant, toJson(payload));
            log.debug("Detection alert payload persisted alertId={} tenant={}", alert.id(), tenant);
        } finally {
            if (previousTenant == null) TenantContext.clear();
            else TenantContext.set(previousTenant);
        }
    }

    private static String resolveTenant(Alert alert) {
        String current = TenantContext.get();
        if (current != null && !current.isBlank()) return current;
        if (alert.evidence() != null) {
            for (var event : alert.evidence()) {
                if (event == null || event.fields() == null) continue;
                String tenant = event.fields().get("tenant_id");
                if (tenant == null || tenant.isBlank()) tenant = event.fields().get("tenantId");
                if (tenant != null && !tenant.isBlank()) return tenant;
            }
        }
        return "default";
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
}
