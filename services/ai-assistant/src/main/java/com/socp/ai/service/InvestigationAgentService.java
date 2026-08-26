package com.socp.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.ai.config.InvestigationProperties;
import com.socp.ai.infrastructure.llm.LlmChatClient;
import com.socp.ai.persistence.entity.InvestigationEntity;
import com.socp.ai.persistence.repository.InvestigationRepository;
import com.socp.platform.audit.model.AuditRecord;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.SearchClient;
import com.socp.platform.client.service.ThreatClient;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bounded, evidence-first alert investigation. It reads facts through the
 * tenant-aware service clients and never executes a containment action.
 */
@Service
public class InvestigationAgentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final InvestigationRepository repository;
    private final AlertClient alertClient;
    private final SearchClient searchClient;
    private final IncidentClient incidentClient;
    private final ThreatClient threatClient;
    private final LlmChatClient llmClient;
    private final AuditSink auditSink;
    private final InvestigationProperties properties;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public InvestigationAgentService(InvestigationRepository repository, AlertClient alertClient,
                                     SearchClient searchClient, IncidentClient incidentClient,
                                     ThreatClient threatClient, LlmChatClient llmClient, AuditSink auditSink,
                                     InvestigationProperties properties) {
        this.repository = repository;
        this.alertClient = alertClient;
        this.searchClient = searchClient;
        this.incidentClient = incidentClient;
        this.threatClient = threatClient;
        this.llmClient = llmClient;
        this.auditSink = auditSink;
        this.properties = properties;
    }

    /** The deterministic receipt ID makes repeated clicks return one result. */
    @Transactional
    public Map<String, Object> investigate(String alertId) {
        String tenant = TenantContext.require();
        String normalizedAlertId = normalizeAlertId(alertId);
        String investigationId = idFor(tenant, normalizedAlertId);
        Object lock = locks.computeIfAbsent(investigationId, ignored -> new Object());
        synchronized (lock) {
            try {
                InvestigationEntity existing = repository.findByTenantIdAndAlertId(tenant, normalizedAlertId)
                        .orElse(null);
                if (existing != null && "COMPLETED".equals(existing.getStatus())) {
                    Map<String, Object> cached = read(existing.getResultJson());
                    cached.put("duplicate", true);
                    return cached;
                }

                long started = System.nanoTime();
                long deadline = started + properties.getTimeoutMs() * 1_000_000L;
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                List<String> degraded = new ArrayList<>();

                Map<String, Object> alert = requiredObject(normalizedAlertId, "alert.get",
                        () -> alertClient.getAlarm(normalizedAlertId), toolCalls, deadline);
                Map<String, Object> evidenceResponse = requiredObject(normalizedAlertId, "alert.evidence",
                        () -> alertClient.evidence(normalizedAlertId), toolCalls, deadline);
                List<Map<String, Object>> evidence = listValue(evidenceResponse, "items");
                if (evidence.size() > properties.getMaxEvidence()) {
                    evidence = new ArrayList<>(evidence.subList(0, properties.getMaxEvidence()));
                }

                String searchQuery = buildSearchQuery(alert, evidence);
                Map<String, Object> searchResponse = optionalObject(normalizedAlertId, "search.related-events",
                        () -> searchClient.search(searchQuery), toolCalls, deadline, degraded);
                List<Map<String, Object>> relatedEvents = listValue(searchResponse, "events");
                if (relatedEvents.size() > properties.getMaxRelatedEvents()) {
                    relatedEvents = new ArrayList<>(relatedEvents.subList(0, properties.getMaxRelatedEvents()));
                }

                List<Map<String, Object>> incidents = optionalList(normalizedAlertId, "incident.related",
                        () -> incidentClient.list(), toolCalls, deadline, degraded);
                List<Map<String, Object>> relatedIncidents = incidents.stream()
                        .filter(item -> containsAlarm(item, normalizedAlertId))
                        .limit(20)
                        .toList();

                List<String> iocValues = extractIocValues(alert, evidence);
                Map<String, Object> iocResponse = iocValues.isEmpty() ? Map.of()
                        : optionalObject(normalizedAlertId, "threat.match-iocs",
                        () -> threatClient.matchIocs(write(iocValues)), toolCalls, deadline, degraded);
                Map<String, Object> iocMatches = objectMap(iocResponse.get("hits"));

                List<Map<String, Object>> citations = citations(normalizedAlertId, alert, evidence,
                        relatedEvents, relatedIncidents, iocMatches);
                List<Map<String, Object>> timeline = timeline(alert, evidence, relatedEvents);
                List<Map<String, Object>> hypotheses = hypotheses(alert, citations, degraded);
                List<Map<String, Object>> nextActions = nextActions(alert, searchQuery);
                String deterministicAnalysis = deterministicAnalysis(alert, evidence, relatedEvents, degraded);
                String analysis = maybeLlmAnalysis(alert, evidence, relatedEvents, deterministicAnalysis,
                        normalizedAlertId, toolCalls, deadline, degraded);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("investigationId", investigationId);
                result.put("alertId", normalizedAlertId);
                result.put("status", degraded.isEmpty() ? "COMPLETED" : "PARTIAL");
                result.put("generatedAt", Instant.now().toString());
                result.put("alert", alert);
                result.put("evidence", evidence);
                result.put("relatedEvents", relatedEvents);
                result.put("relatedIncidents", relatedIncidents);
                result.put("iocValues", iocValues);
                result.put("iocMatches", iocMatches);
                result.put("timeline", timeline);
                result.put("analysis", analysis);
                result.put("hypotheses", hypotheses);
                result.put("recommendedSpl", searchQuery);
                result.put("nextActions", nextActions);
                result.put("citations", citations);
                result.put("toolCalls", toolCalls);
                result.put("degradedSources", degraded);
                result.put("budget", Map.of("maxToolCalls", properties.getMaxToolCalls(),
                        "usedToolCalls", toolCalls.size(), "elapsedMs", elapsedMs(started),
                        "timeoutMs", properties.getTimeoutMs()));

                InvestigationEntity receipt = existing == null ? new InvestigationEntity() : existing;
                receipt.setId(investigationId);
                receipt.setTenantId(tenant);
                receipt.setAlertId(normalizedAlertId);
                receipt.setStatus(degraded.isEmpty() ? "COMPLETED" : "PARTIAL");
                receipt.setResultJson(write(result));
                if (receipt.getCreatedAt() == null) receipt.setCreatedAt(Instant.now());
                receipt.setUpdatedAt(Instant.now());
                repository.save(receipt);
                audit(normalizedAlertId, "AI_INVESTIGATION", "SUCCESS tools=" + toolCalls.size());
                return result;
            } finally {
                locks.remove(investigationId, lock);
            }
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String investigationId) {
        String tenant = TenantContext.require();
        InvestigationEntity entity = repository.findByIdAndTenantId(investigationId, tenant)
                .orElseThrow(() -> ApiException.notFound("Investigation does not exist: " + investigationId));
        return read(entity.getResultJson());
    }

    /**
     * Writes only an analyst-readable summary to Incident. The call is
     * idempotent: once appended, a replay returns the same incident ID.
     */
    @Transactional
    public Map<String, Object> appendToIncident(String investigationId, String requestedIncidentId) {
        String tenant = TenantContext.require();
        InvestigationEntity entity = repository.findByIdAndTenantId(investigationId, tenant)
                .orElseThrow(() -> ApiException.notFound("Investigation does not exist: " + investigationId));
        Map<String, Object> result = read(entity.getResultJson());
        if (entity.getAppendedAt() != null) {
            result.put("duplicate", true);
            result.put("summaryAppended", true);
            result.put("incidentId", entity.getIncidentId());
            return result;
        }

        String incidentId = blankToNull(requestedIncidentId);
        if (incidentId == null) {
            ServiceCall created = incidentClient.createFromAlarm(write(objectMap(result.get("alert"))));
            auditCall(result.get("alertId"), "incident.create", created);
            if (created == null || !created.ok()) {
                throw new IllegalStateException("Incident case creation failed: "
                        + (created == null ? "no response" : created.failureReason()));
            }
            Map<String, Object> createdBody = parseBody(created);
            incidentId = text(createdBody.get("caseId"));
            if (incidentId == null) incidentId = text(createdBody.get("id"));
            if (incidentId == null) throw new IllegalStateException("Incident response did not contain caseId");
        }

        String summary = summary(result);
        ServiceCall note = incidentClient.addNote(incidentId, "ai-investigation", summary);
        auditCall(result.get("alertId"), "incident.append-summary", note);
        if (note == null || !note.ok()) {
            throw new IllegalStateException("Incident timeline append failed: "
                    + (note == null ? "no response" : note.failureReason()));
        }
        Instant appended = Instant.now();
        entity.setIncidentId(incidentId);
        entity.setAppendedAt(appended);
        entity.setUpdatedAt(appended);
        result.put("summaryAppended", true);
        result.put("incidentId", incidentId);
        result.put("summaryAppendedAt", appended.toString());
        entity.setResultJson(write(result));
        repository.save(entity);
        audit(result.get("alertId"), "AI_INVESTIGATION_APPEND", "SUCCESS incidentId=" + incidentId);
        return result;
    }

    private Map<String, Object> requiredObject(String alertId, String tool, Supplier<ServiceCall> call,
                                                List<Map<String, Object>> calls, long deadline) {
        checkBudget(calls, deadline);
        ServiceCall result = invoke(alertId, tool, call, calls);
        if (result == null || !result.ok()) {
            throw new IllegalStateException(tool + " failed: "
                    + (result == null ? "no response" : result.failureReason()));
        }
        return parseBody(result);
    }

    private Map<String, Object> optionalObject(String alertId, String tool, Supplier<ServiceCall> call,
                                               List<Map<String, Object>> calls, long deadline,
                                               List<String> degraded) {
        try {
            checkBudget(calls, deadline);
            ServiceCall result = invoke(alertId, tool, call, calls);
            if (result == null || !result.ok()) throw new IllegalStateException("tool failed");
            return parseBody(result);
        } catch (RuntimeException failure) {
            degraded.add(tool);
            return Map.of();
        }
    }

    private List<Map<String, Object>> optionalList(String alertId, String tool, Supplier<ServiceCall> call,
                                                   List<Map<String, Object>> calls, long deadline,
                                                   List<String> degraded) {
        try {
            checkBudget(calls, deadline);
            ServiceCall result = invoke(alertId, tool, call, calls);
            if (result == null || !result.ok()) throw new IllegalStateException("tool failed");
            String body = result.body();
            if (body == null || body.isBlank()) return List.of();
            Object parsed = MAPPER.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map && map.get("data") instanceof List<?> data) {
                return castList(data);
            }
            return parsed instanceof List<?> list ? castList(list) : List.of();
        } catch (Exception failure) {
            degraded.add(tool);
            return List.of();
        }
    }

    private ServiceCall invoke(String alertId, String tool, Supplier<ServiceCall> operation,
                               List<Map<String, Object>> calls) {
        long start = System.nanoTime();
        ServiceCall result = null;
        String error = null;
        try {
            result = operation.get();
        } catch (RuntimeException failure) {
            error = failure.getMessage();
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("tool", tool);
        audit.put("success", result != null && result.ok());
        audit.put("status", result == null ? -1 : result.status());
        audit.put("elapsedMs", elapsedMs(start));
        if (error != null) audit.put("error", truncate(error, 256));
        calls.add(audit);
        audit(alertId, "AI_TOOL_CALL", tool + " success=" + (result != null && result.ok()));
        return result;
    }

    private void checkBudget(List<Map<String, Object>> calls, long deadline) {
        if (calls.size() >= properties.getMaxToolCalls()) {
            throw new IllegalStateException("investigation tool-call budget exceeded");
        }
        if (System.nanoTime() > deadline) throw new IllegalStateException("investigation timeout exceeded");
    }

    private String maybeLlmAnalysis(Map<String, Object> alert, List<Map<String, Object>> evidence,
                                    List<Map<String, Object>> related, String fallback, String alertId,
                                    List<Map<String, Object>> toolCalls, long deadline,
                                    List<String> degraded) {
        if (llmClient == null || !llmClient.isEnabled()) return fallback;
        try {
            checkBudget(toolCalls, deadline);
            long started = System.nanoTime();
            String prompt = "Analyze this SOCP alert using only the supplied evidence. Cite event IDs; "
                    + "state uncertainty and do not propose automatic containment.\nalert="
                    + truncate(write(alert), 6000) + "\nevidence=" + truncate(write(evidence), 10000)
                    + "\nrelated=" + truncate(write(related), 6000);
            var response = llmClient.chat(prompt);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("tool", "llm.analysis");
            call.put("success", response.isPresent());
            call.put("elapsedMs", elapsedMs(started));
            toolCalls.add(call);
            audit(alertId, "AI_TOOL_CALL", "llm.analysis success=" + response.isPresent());
            if (response.isPresent()) return response.get();
        } catch (RuntimeException failure) {
            degraded.add("llm.analysis");
        }
        return fallback;
    }

    private static String buildSearchQuery(Map<String, Object> alert, List<Map<String, Object>> evidence) {
        List<String> terms = new ArrayList<>();
        for (Map<String, Object> item : evidence) {
            String eventId = text(item.get("eventId"));
            if (eventId != null) terms.add("eventId=" + eventId);
        }
        String entity = text(alert.get("entity"));
        if (entity != null) terms.add("host=" + entity);
        return terms.isEmpty() ? "" : String.join(" OR ", terms);
    }

    private static List<Map<String, Object>> citations(String alertId, Map<String, Object> alert,
                                                       List<Map<String, Object>> evidence,
                                                       List<Map<String, Object>> related,
                                                       List<Map<String, Object>> incidents,
                                                       Map<String, Object> iocMatches) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("id", "alert:" + alertId, "source", "alert-web",
                "locator", "/alert-web/api/alarms/" + alertId, "description", "alert fact"));
        int index = 0;
        for (Map<String, Object> item : evidence) {
            String eventId = text(item.get("eventId"));
            if (eventId == null) continue;
            out.add(Map.of("id", "evidence:" + eventId, "source", "alert-web",
                    "locator", "/alert-web/api/alarms/" + alertId + "/evidence#" + eventId,
                    "description", "captured source event " + (++index)));
        }
        for (Map<String, Object> event : related) {
            String eventId = text(event.get("eventId"));
            if (eventId != null) out.add(Map.of("id", "search:" + eventId, "source", "search-config",
                    "locator", "/search-config/api/v1/search?eventId=" + eventId,
                    "description", "related original event"));
        }
        for (Map<String, Object> incident : incidents) {
            String id = text(incident.get("id"));
            if (id != null) out.add(Map.of("id", "incident:" + id, "source", "incident-web",
                    "locator", "/incident-web/api/v1/incidents/" + id, "description", "related incident"));
        }
        for (String value : iocMatches.keySet()) {
            out.add(Map.of("id", "ioc:" + value, "source", "threat-web",
                    "locator", "/threat-web/api/v1/iocs/match?value=" + value,
                    "description", "matched IOC"));
        }
        return out;
    }

    private static List<String> extractIocValues(Map<String, Object> alert,
                                                  List<Map<String, Object>> evidence) {
        Set<String> values = new LinkedHashSet<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(alert);
        sources.addAll(evidence);
        for (Map<String, Object> source : sources) {
            for (String key : List.of("src_ip", "dst_ip", "sourceIp", "destinationIp",
                    "ip", "domain", "url", "sha256", "ioc", "iocValue")) {
                String value = text(source.get(key));
                if (value != null) values.add(value);
            }
            Object nested = source.get("iocs");
            if (nested instanceof List<?> list) {
                list.forEach(item -> {
                    String value = text(item);
                    if (value != null) values.add(value);
                });
            }
        }
        return new ArrayList<>(values);
    }

    private static List<Map<String, Object>> timeline(Map<String, Object> alert,
                                                      List<Map<String, Object>> evidence,
                                                      List<Map<String, Object>> related) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(timelineItem(alert.get("occurredAt"), "ALERT", alert.get("message"),
                "alert:" + alert.get("id")));
        for (Map<String, Object> item : evidence) {
            out.add(timelineItem(item.get("timestamp"), "EVIDENCE", item.get("raw"),
                    "evidence:" + item.getOrDefault("eventId", "unknown")));
        }
        for (Map<String, Object> item : related) {
            Object message = item.containsKey("msg") ? item.get("msg") : item.get("message");
            out.add(timelineItem(item.get("timestamp"), "RELATED_EVENT", message,
                    "search:" + item.getOrDefault("eventId", "unknown")));
        }
        out.sort(java.util.Comparator.comparing(item -> String.valueOf(item.get("timestamp"))));
        return out;
    }

    private static Map<String, Object> timelineItem(Object timestamp, String type,
                                                    Object message, String citation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("timestamp", text(timestamp) == null ? "" : text(timestamp));
        item.put("type", type);
        item.put("message", truncate(text(message), 512));
        item.put("citation", citation);
        return item;
    }

    private static List<Map<String, Object>> hypotheses(Map<String, Object> alert,
                                                        List<Map<String, Object>> citations,
                                                        List<String> degraded) {
        String rule = text(alert.get("ruleId"));
        String entity = text(alert.get("entity"));
        String support = citations.stream().filter(item -> String.valueOf(item.get("id")).startsWith("evidence:"))
                .map(item -> String.valueOf(item.get("id"))).findFirst().orElse("alert:" + alert.get("id"));
        double confidence = degraded.isEmpty() ? 0.72 : 0.55;
        return List.of(Map.of("hypothesis", "The alert is consistent with " + (rule == null ? "the detected behavior" : rule)
                        + " affecting " + (entity == null ? "the reported entity" : entity),
                "supporting", List.of(support),
                "contradicting", List.of("No contrary evidence was observed in the bounded query"),
                "confidence", confidence,
                "confidenceNote", "Confidence is bounded by captured evidence and is not a verdict"));
    }

    private static List<Map<String, Object>> nextActions(Map<String, Object> alert, String searchQuery) {
        return List.of(
                Map.of("type", "PRESERVE_EVIDENCE", "status", "RECOMMENDED",
                        "description", "Preserve the cited raw events and host timeline"),
                Map.of("type", "RUN_SPL", "status", "RECOMMENDED", "query", searchQuery),
                Map.of("type", "SOAR_SUGGESTION", "status", "REQUIRES_HUMAN_APPROVAL",
                        "executable", false, "description", "Review containment or credential-reset actions before execution")
        );
    }

    private static String deterministicAnalysis(Map<String, Object> alert, List<Map<String, Object>> evidence,
                                                List<Map<String, Object>> related, List<String> degraded) {
        return "Evidence-first assessment for alert " + alert.getOrDefault("id", "unknown")
                + ": captured=" + evidence.size() + ", relatedEvents=" + related.size()
                + ". This is an analyst aid, not an automatic containment decision."
                + (degraded.isEmpty() ? "" : " Sources unavailable: " + String.join(", ", degraded) + ".");
    }

    private static String summary(Map<String, Object> result) {
        StringBuilder out = new StringBuilder("SOCP AI investigation summary\n");
        out.append("Alert: ").append(result.get("alertId")).append('\n');
        out.append("Assessment: ").append(result.get("analysis")).append('\n');
        out.append("Recommended SPL: ").append(result.get("recommendedSpl")).append('\n');
        out.append("Citations: ").append(result.get("citations"));
        return truncate(out.toString(), 8000);
    }

    private void audit(Object alertId, String action, String result) {
        auditSink.publish(AuditRecord.of(action, "alert:" + alertId, truncate(result, 512)));
    }

    private void auditCall(Object alertId, String tool, ServiceCall call) {
        audit(alertId, "AI_TOOL_CALL", tool + " success=" + (call != null && call.ok()));
    }

    private static Map<String, Object> parseBody(ServiceCall call) {
        try {
            Map<String, Object> root = MAPPER.readValue(call.body(), MAP);
            Object data = root.get("data");
            return data instanceof Map<?, ?> ? objectMap(data) : root;
        } catch (Exception failure) {
            throw new IllegalStateException("invalid service response", failure);
        }
    }

    private static List<Map<String, Object>> listValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof List<?> list) return castList(list);
        return List.of();
    }

    private static List<Map<String, Object>> castList(List<?> values) {
        return values.stream().filter(item -> item instanceof Map<?, ?>).map(InvestigationAgentService::objectMap).toList();
    }

    private static boolean containsAlarm(Map<String, Object> incident, String alertId) {
        Object ids = incident.get("alarmIds");
        return ids instanceof List<?> list && list.stream().anyMatch(id -> alertId.equals(String.valueOf(id)));
    }

    private static Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private static Map<String, Object> read(String json) {
        try { return new LinkedHashMap<>(MAPPER.readValue(json, MAP)); }
        catch (Exception failure) { throw new IllegalStateException("invalid investigation receipt", failure); }
    }

    private static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("cannot serialize investigation", failure); }
    }

    private static String normalizeAlertId(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) throw new IllegalArgumentException("alert id is required");
        return normalized;
    }

    private static String idFor(String tenant, String alertId) {
        return UUID.nameUUIDFromBytes((tenant + "\u0000investigation\u0000" + alertId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value);
        return result.isBlank() ? null : result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
