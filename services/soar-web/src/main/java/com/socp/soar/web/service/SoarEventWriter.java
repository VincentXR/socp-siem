package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import static com.socp.soar.web.service.SoarService.error;
import static com.socp.soar.web.service.SoarService.limit;
import static com.socp.soar.web.service.SoarService.redactFreeText;

/** Durable event sequencing and idempotent signals inside the facade transaction. */
final class SoarEventWriter {
    private final SoarRunRepository runs;
    private final SoarRunEventRepository events;
    private final SoarSignalOutboxRepository signals;
    private final ObjectMapper mapper;
    private final SoarJson json;
    SoarEventWriter(SoarRunRepository runs, SoarRunEventRepository events,
            SoarSignalOutboxRepository signals, ObjectMapper mapper, SoarJson json) {
        this.runs = runs; this.events = events; this.signals = signals; this.mapper = mapper; this.json = json;
    }

    void enqueueSignal(SoarRunEntity run, String type, Map<String, Object> payload) {
        if (signals == null) return;
        Instant now = Instant.now();
        String signalKey = signalKey(type, payload);
        String encoded = json.write(payload);
        try { SoarSignalPayload.parse(mapper, type, signalKey, encoded); }
        catch (SoarSignalPayload.Invalid invalid) {
            // Approval expiry deliberately commits ResponseStatusException;
            // invalid signal creation must instead roll back the decision too.
            throw com.socp.platform.error.exception.ApiException.badRequest("SOAR_INVALID_SIGNAL: " + invalid.getMessage());
        }
        java.util.Optional<SoarSignalOutboxEntity> existing = signals
                .findByTenantIdAndRunIdAndSignalTypeAndSignalKey(
                        run.getTenantId(), run.getId(), type, signalKey);
        // Isolated compatibility tests and rows written by V10 may not expose
        // the keyed projection. Reuse the legacy singleton only for the empty
        // key; keyed gates must never overwrite one another.
        if ((existing == null || existing.isEmpty()) && signalKey.isBlank()) {
            existing = signals.findByTenantIdAndRunIdAndSignalType(run.getTenantId(), run.getId(), type);
        }
        SoarSignalOutboxEntity signal = (existing == null ? java.util.Optional.<SoarSignalOutboxEntity>empty() : existing)
                .orElseGet(() -> {
                    SoarSignalOutboxEntity created = new SoarSignalOutboxEntity();
                    created.setId(UUID.randomUUID().toString()); created.setTenantId(run.getTenantId());
                    created.setRunId(run.getId()); created.setSignalType(type); created.setSignalKey(signalKey);
                    created.setAttempts(0);
                    created.setCreatedAt(now); return created;
                });
        signal.setPayloadJson(encoded); signal.setStatus("PENDING");
        signal.setNextAttemptAt(now); signal.setUpdatedAt(now); signals.save(signal);
    }

    private static String signalKey(String type, Map<String, Object> payload) {
        if (payload == null) return "";
        String field = switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "APPROVAL" -> "approvalKey";
            case "MANUAL_TASK", "UNKNOWN_RESOLUTION" -> "nodeId";
            default -> "signalKey";
        };
        Object value = payload.get(field);
        if (value == null && "APPROVAL".equalsIgnoreCase(type)) value = payload.get("approvalId");
        return value == null ? "" : limit(String.valueOf(value).trim(), 255);
    }

    protected void appendEvent(String runId, String type, String actor, String summary, Map<String, Object> detail) {
        String tenant = TenantContext.require();
        // Event sequence numbers are part of the public SSE cursor contract.
        // Lock the owning run before reading the current tail so concurrent
        // activity completions cannot allocate the same (tenant, run, seq).
        java.util.Optional<SoarRunEntity> locked = runs.findByTenantIdAndIdForUpdate(tenant, runId);
        if (locked == null || locked.isEmpty()) locked = runs.findByTenantIdAndId(tenant, runId);
        (locked == null ? java.util.Optional.<SoarRunEntity>empty() : locked)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "run not found"));
        SoarRunEventEntity event = new SoarRunEventEntity();
        event.setId(UUID.randomUUID().toString());
        event.setTenantId(tenant);
        event.setRunId(runId);
        long previousSequence = events.findTopByTenantIdAndRunIdOrderBySequenceNoDesc(tenant, runId)
                .map(SoarRunEventEntity::getSequenceNo)
                .orElse(0L);
        event.setSequenceNo(previousSequence + 1);
        event.setEventType(type);
        event.setActor(actor);
        event.setSummary(redactFreeText(limit(summary, 1024), 1024));
        event.setDetailJson(json.write(SoarRedaction.structured(detail == null ? Map.of() : detail)));
        event.setCreatedAt(Instant.now());
        events.save(event);
    }
}
