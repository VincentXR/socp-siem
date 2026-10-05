package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.persistence.repository.AlarmRepository;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.ThreatClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durable threat-intelligence lookup; delivery retries survive process restarts. */
@Component
public class AlarmEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(AlarmEnrichmentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern DOMAIN = Pattern.compile("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b");

    private final AlarmRepository repository;
    private final ThreatClient threatClient;
    private final MeterRegistry meterRegistry;

    private final AlarmEnrichmentCommitter committer;

    public AlarmEnrichmentService(AlarmRepository repository, ThreatClient threatClient,
                                  AlarmEnrichmentCommitter committer, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.threatClient = threatClient;
        this.committer = committer;
        this.meterRegistry = meterRegistry;
    }

    /** Remote lookup is outside the patch transaction; failures retain the durable delivery. */
    public void enrichDurably(String tenantId, String alarmId) {
        Alarm alarm = repository.findByTenantIdAndId(tenantId, alarmId)
                .orElseThrow(() -> new IllegalStateException("Missing enrichment alarm"));
        if (alarm.getEnrichedAt() != null) return;
        List<String> candidates = candidates(alarm);
        ThreatHits hits = new ThreatHits("[]", 0);
        if (!candidates.isEmpty()) {
            ServiceCall call = threatClient.matchIocs(json(candidates));
            if (call == null || !call.ok()) throw new IllegalStateException("Threat enrichment unavailable");
            hits = threatHits(call.body());
            if (hits == null) throw new IllegalStateException("Invalid threat enrichment response");
        }
        int risk = Math.max(alarm.getInitialRiskScore() == null ? 0 : alarm.getInitialRiskScore(),
                score(alarm, hits.count()).score());
        committer.complete(tenantId, alarmId, hits.json(), risk);
        enrichment("success");
    }

    com.socp.rule.score.RiskScorer.Score score(Alarm alarm, int threatHits) {
        com.socp.rule.model.Severity severity;
        try {
            severity = alarm.getSeverity() == null
                    ? com.socp.rule.model.Severity.INFO
                    : com.socp.rule.model.Severity.valueOf(alarm.getSeverity().name());
        } catch (IllegalArgumentException invalid) {
            severity = com.socp.rule.model.Severity.INFO;
        }
        int recent = 0;
        try {
            if (alarm.getEntity() != null && !alarm.getEntity().isBlank()) {
                recent = (int) repository.countRecentByEntity(alarm.getTenantId(), alarm.getEntity(),
                        java.time.Instant.now().minus(Duration.ofHours(1)));
            }
        } catch (RuntimeException failure) {
            log.debug("Recent alarm count unavailable; risk scoring uses zero entity={} reason={}",
                    alarm.getEntity(), failure.toString());
        }
        return com.socp.rule.score.RiskScorer.score(
                severity, alarm.getMitre(), threatHits, recent, 0);
    }

    private void enrichment(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("socp.alert.enrichment", "outcome", outcome).increment();
        }
    }

    private static List<String> candidates(Alarm alarm) {
        List<String> values = new ArrayList<>();
        if (alarm.getEntity() != null && !alarm.getEntity().isBlank()) values.add(alarm.getEntity());
        if (alarm.getMessage() == null) return values;
        Matcher ip = IP.matcher(alarm.getMessage());
        while (ip.find()) values.add(ip.group());
        Matcher domain = DOMAIN.matcher(alarm.getMessage().toLowerCase(java.util.Locale.ROOT));
        while (domain.find()) values.add(domain.group());
        return values.stream().distinct().limit(100).toList();
    }

    private static ThreatHits threatHits(String body) {
        if (body == null || body.isBlank() || body.length() > 512_000) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.has("code") && root.path("code").asInt(-1) != 0) return null;
            // threat-web 响应已包 ApiResult 信封：{code,message,data:{hits}}
            if (root.isObject() && root.has("code") && root.has("data") && root.get("data").isObject()) {
                root = root.get("data");
            }
            if (root.has("code") && root.path("code").asInt(-1) != 0) return null;
            JsonNode hits = root.get("hits");
            if (hits == null || hits.isNull()) return null;
            JsonNode normalized = hits;
            if (hits.isObject()) {
                var array = MAPPER.createArrayNode();
                hits.elements().forEachRemaining(array::add);
                normalized = array;
            }
            if (!normalized.isArray() || normalized.size() > 100) return null;
            int count = normalized.size();
            var bounded = MAPPER.createArrayNode();
            if (normalized.isArray()) {
                for (JsonNode hit : normalized) {
                    bounded.add(hit);
                }
            }
            return new ThreatHits(MAPPER.writeValueAsString(bounded), count);
        } catch (JsonProcessingException invalidResponse) {
            log.warn("Threat service returned invalid JSON: {}", invalidResponse.getOriginalMessage());
            return null;
        }
    }

    private static String json(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot serialize threat candidates", impossible);
        }
    }

    private record ThreatHits(String json, int count) {
    }
}
