package com.socp.incident.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.incident.web.api.request.CaseAlarmAssociationRequest;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.CaseState;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.persistence.entity.AlarmCaseLinkEntity;
import com.socp.incident.web.persistence.entity.CaseMutationEntity;
import com.socp.incident.web.persistence.repository.AlarmCaseLinkRepository;
import com.socp.incident.web.persistence.repository.CaseMutationRepository;
import com.socp.incident.web.persistence.repository.CaseRepository;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Shares the automatic alarm lock; association, both timelines and receipt commit together. */
@Service
public class CaseAlarmAssociationService {
    private final CaseStore store;
    private final CaseRepository cases;
    private final AlarmCaseLinkRepository links;
    private final CaseMutationRepository receipts;
    private final IncidentAggregationLock locks;
    private final AlertClient alerts;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final ObjectMapper json;

    public CaseAlarmAssociationService(CaseStore store, CaseRepository cases, AlarmCaseLinkRepository links,
            CaseMutationRepository receipts, IncidentAggregationLock locks, AlertClient alerts,
            JdbcTemplate jdbc, EntityManager entities, ObjectMapper json) {
        this.store = store; this.cases = cases; this.links = links; this.receipts = receipts;
        this.locks = locks; this.alerts = alerts; this.jdbc = jdbc; this.entities = entities; this.json = json;
    }

    @Transactional
    public Map<String, Object> change(String caseId, String actor, CaseAlarmAssociationRequest command) {
        String tenant = TenantContext.require();
        String target = "MOVE".equals(command.operation()) ? command.targetCaseId() : null;
        if (!Set.of("ATTACH", "DETACH", "MOVE").contains(command.operation())
                || command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                || command.reason() == null || command.reason().isBlank()) throw ApiException.badRequest("Invalid association command");
        if ("MOVE".equals(command.operation()) && (target == null || target.isBlank() || target.equals(caseId)))
            throw ApiException.badRequest("MOVE requires a different target case");
        locks.lockAll("alarm:" + command.alarmId());
        for (String id : new TreeSet<>(target == null ? List.of(caseId) : List.of(caseId, target))) {
            cases.lockByTenantIdAndId(tenant, id).orElseThrow(() -> ApiException.notFound("Case not found"));
        }
        String fingerprint = fingerprint(actor, command);
        var previous = receipts.findByTenantIdAndCaseIdAndRequestKey(tenant, caseId, command.idempotencyKey());
        if (previous.isPresent()) {
            if (!previous.get().getFingerprint().equals(fingerprint)) throw ApiException.conflict("Idempotency key was used for a different command");
            return response(caseId, target, true);
        }
        Case source = store.getMetadata(caseId);
        requireVersion(source, command.expectedVersion());
        if (target != null) requireVersion(store.getMetadata(target), command.targetExpectedVersion());
        if (!"DETACH".equals(command.operation())) {
            Case destination = target == null ? source : store.getMetadata(target);
            if (!CaseState.openNames().contains(destination.status())) throw ApiException.conflict("Reopen the destination case before associating alarms");
        }
        // The authoritative alert service checks ownership. A guessed foreign ID is never stored.
        var call = alerts.getAlarm(command.alarmId());
        if (call == null || !call.ok()) {
            if (call != null && call.status() == 404) throw ApiException.notFound("Alarm not found");
            throw ApiException.of(503, "Alarm ownership could not be verified");
        }
        String ruleId;
        try {
            var envelope = json.readTree(call.body());
            var alarm = envelope.has("data") ? envelope.get("data") : envelope;
            if ((envelope.has("code") && envelope.path("code").asInt(-1) != 0)
                    || !command.alarmId().equals(alarm.path("id").asText())
                    || !tenant.equals(alarm.path("tenantId").asText())) throw ApiException.notFound("Alarm not found");
            ruleId = alarm.path("ruleId").asText("");
        } catch (ApiException failure) { throw failure; }
        catch (Exception invalid) { throw ApiException.of(503, "Alarm ownership response was invalid"); }
        AlarmCaseLinkEntity link = links.findByTenantIdAndAlarmId(tenant, command.alarmId()).orElse(null);
        if ("ATTACH".equals(command.operation())) {
            if (link != null) throw ApiException.conflict("Alarm already belongs to a case; use MOVE");
            jdbc.update("delete from t_incident_alarm_exclusion where tenant_id = ? and alarm_id = ?", tenant, command.alarmId());
            link = new AlarmCaseLinkEntity();
            link.setId(UUID.nameUUIDFromBytes((tenant + "\u0000" + command.alarmId()).getBytes(StandardCharsets.UTF_8)).toString());
            link.setTenantId(tenant); link.setAlarmId(command.alarmId()); link.setCaseId(caseId); link.setCreatedAt(Instant.now());
            links.saveAndFlush(link);
            store.recordRuleAssociation(caseId, ruleId);
        } else {
            if (link == null || !caseId.equals(link.getCaseId())) throw ApiException.conflict("Alarm association changed; refresh before retrying");
            if (target != null) {
                link.setCaseId(target); links.saveAndFlush(link);
                store.recordRuleAssociation(target, ruleId);
            } else {
                links.delete(link); links.flush();
                jdbc.update("insert into t_incident_alarm_exclusion (tenant_id, alarm_id, created_at) values (?, ?, current_timestamp)", tenant, command.alarmId());
            }
        }
        String message = actor + ": " + command.operation() + " alarm " + command.alarmId()
                + (target == null ? "" : " to " + target) + "\nReason: " + command.reason().trim();
        boolean sourceHistory = store.appendTimeline(caseId, new TimelineEvent(Instant.now(), "ALARM_" + command.operation(), message,
                "analyst", command.alarmId(), historyKey(caseId, fingerprint, "source")));
        if (!sourceHistory) throw ApiException.conflict("Association history already exists; refresh before retrying");
        if (target != null) {
            boolean targetHistory = store.appendTimeline(target, new TimelineEvent(Instant.now(), "ALARM_ATTACH",
                    actor + ": moved alarm " + command.alarmId() + " from " + caseId + "\nReason: " + command.reason().trim(),
                    "analyst", command.alarmId(), historyKey(caseId, fingerprint, "destination")));
            if (!targetHistory) throw ApiException.conflict("Association history already exists; refresh before retrying");
        }
        receipts.save(new CaseMutationEntity(tenant, caseId, command.idempotencyKey(), fingerprint));
        entities.flush(); entities.clear();
        return response(caseId, target, false);
    }

    private Map<String, Object> response(String id, String target, boolean duplicate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", store.getMetadata(id)); result.put("duplicate", duplicate); result.put("changed", !duplicate);
        if (target != null) result.put("targetCase", store.getMetadata(target));
        return result;
    }
    private String historyKey(String sourceCase, String fingerprint, String side) {
        try {
            return "association:v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(List.of(sourceCase, fingerprint, side))));
        } catch (Exception invalid) { throw new IllegalStateException("Cannot identify association history", invalid); }
    }

    private String fingerprint(String actor, Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsBytes(List.of(actor, "association", value)))); }
        catch (Exception invalid) { throw new IllegalStateException("Cannot fingerprint association command", invalid); }
    }
    private static void requireVersion(Case incident, Long version) {
        if (version == null || version < 0) throw ApiException.badRequest("expectedVersion is required");
        if (incident.rowVersion() != version) throw ApiException.conflict("Case changed; refresh before retrying");
    }
}
