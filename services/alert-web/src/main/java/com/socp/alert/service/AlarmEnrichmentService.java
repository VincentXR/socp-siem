package com.socp.alert.service;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.config.AlertEnrichmentProperties;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.ThreatClient;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns optional threat-intelligence enrichment and risk recalculation. */
@Component
public class AlarmEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(AlarmEnrichmentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern DOMAIN = Pattern.compile("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b");

    private final AlarmRepository repository;
    private final ThreatClient threatClient;
    private final int concurrency;
    private final int queueCapacity;
    private volatile ExecutorService executor;

    @Autowired
    public AlarmEnrichmentService(AlarmRepository repository, ThreatClient threatClient,
                                  AlertEnrichmentProperties properties) {
        this(repository, threatClient, properties.getConcurrency(), properties.getQueueCapacity());
    }

    public AlarmEnrichmentService(AlarmRepository repository, ThreatClient threatClient,
                                  int concurrency, int queueCapacity) {
        this.repository = repository;
        this.threatClient = threatClient;
        this.concurrency = Math.max(1, Math.min(32, concurrency));
        this.queueCapacity = Math.max(100, Math.min(100_000, queueCapacity));
    }

    void scheduleAfterCommit(Alarm alarm) {
        Runnable submit = () -> submit(alarm);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
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

    void enrich(Alarm alarm) {
        List<String> candidates = candidates(alarm);
        if (candidates.isEmpty()) return;
        ServiceCall call = threatClient.matchIocs(json(candidates));
        if (call == null || !call.ok()) {
            log.warn("Threat enrichment unavailable alarmId={} reason={}", alarm.getId(),
                    call == null ? "no service result" : call.failureReason());
            return;
        }
        ThreatHits hits = threatHits(call.body());
        if (hits == null) return;
        alarm.setTiHits(hits.json());
        var risk = score(alarm, hits.count());
        alarm.setRiskScore(risk.score());
        alarm.setRiskLevel(risk.level());
        repository.save(alarm);
    }

    private void submit(Alarm alarm) {
        try {
            executor().execute(() -> {
                String previous = TenantContext.get();
                try {
                    TenantContext.set(alarm.getTenantId());
                    enrich(alarm);
                } catch (RuntimeException failure) {
                    log.warn("Threat enrichment failed alarmId={} entity={} reason={}",
                            alarm.getId(), alarm.getEntity(), failure.toString());
                } finally {
                    if (previous == null) TenantContext.clear();
                    else TenantContext.set(previous);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            log.warn("Threat enrichment queue is full; skipping optional enrichment alarmId={}", alarm.getId());
        }
    }

    private ExecutorService executor() {
        ExecutorService current = executor;
        if (current != null) return current;
        synchronized (this) {
            if (executor == null) {
                executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(queueCapacity),
                        Thread.ofVirtual().name("alert-enrichment-", 0).factory(),
                        new ThreadPoolExecutor.AbortPolicy());
            }
            return executor;
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
        return values.stream().distinct().toList();
    }

    private static ThreatHits threatHits(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode hits = MAPPER.readTree(body).get("hits");
            if (hits == null || hits.isNull()) return null;
            JsonNode normalized = hits;
            if (hits.isObject()) {
                var array = MAPPER.createArrayNode();
                hits.elements().forEachRemaining(array::add);
                normalized = array;
            }
            int count = normalized.isArray() ? normalized.size() : 1;
            return new ThreatHits(MAPPER.writeValueAsString(normalized), count);
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

    @PreDestroy
    void stop() {
        ExecutorService current = executor;
        if (current != null) current.shutdownNow();
    }

    private record ThreatHits(String json, int count) {
    }
}
