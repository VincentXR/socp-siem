package com.socp.alert.service;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.api.response.AlarmEvidenceResponse;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Coordinates the transactional alarm write boundary and delegates read/enrichment concerns. */
@Service
public class AlarmService {

    private final AlarmRepository repository;
    private final OutboxRepository outboxRepository;
    private final AlarmEvidenceRepository evidenceRepository;
    private final AlarmDeliveryRegistrar deliveryRegistrar;
    private final AlarmEnrichmentService enrichmentService;
    private final AlarmQueryService queryService;
    private final AlarmStatisticsService statisticsService;
    private final OutboxPublisher outboxPublisher;
    private final AlarmDeliveryPublisher deliveryPublisher;

    @Autowired
    public AlarmService(AlarmRepository repository, OutboxRepository outboxRepository,
                        AlarmEvidenceRepository evidenceRepository,
                        AlarmDeliveryRegistrar deliveryRegistrar,
                        AlarmEnrichmentService enrichmentService,
                        AlarmQueryService queryService,
                        AlarmStatisticsService statisticsService,
                        OutboxPublisher outboxPublisher,
                        AlarmDeliveryPublisher deliveryPublisher) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.evidenceRepository = evidenceRepository;
        this.deliveryRegistrar = deliveryRegistrar;
        this.enrichmentService = enrichmentService;
        this.queryService = queryService;
        this.statisticsService = statisticsService;
        this.outboxPublisher = outboxPublisher;
        this.deliveryPublisher = deliveryPublisher;
    }

    public AlarmService(AlarmRepository repository, OutboxRepository outboxRepository,
                        AlarmEvidenceRepository evidenceRepository,
                        AlarmDeliveryRegistrar deliveryRegistrar,
                        AlarmEnrichmentService enrichmentService,
                        AlarmQueryService queryService,
                        AlarmStatisticsService statisticsService) {
        this(repository, outboxRepository, evidenceRepository, deliveryRegistrar,
                enrichmentService, queryService, statisticsService, null, null);
    }

    public Alarm create(Alarm alarm) {
        return create(alarm, List.of());
    }

    @Transactional
    public Alarm create(Alarm alarm, List<AlarmEvidenceInput> evidence) {
        String tenant = AlarmQueryService.tenant();
        if (alarm.getTenantId() != null && !tenant.equals(alarm.getTenantId())) {
            throw new IllegalArgumentException("alarm tenant does not match authenticated tenant");
        }
        alarm.setTenantId(tenant);
        if (alarm.getSourceAlertId() != null && !alarm.getSourceAlertId().isBlank()) {
            var existing = repository.findByTenantIdAndSourceAlertId(tenant, alarm.getSourceAlertId());
            if (existing.isPresent()) return existing.get();
        }
        if (alarm.getRiskScore() == null) alarm.setRiskScore(initialRisk(alarm));
        alarm.setRiskLevel(com.socp.rule.score.RiskScorer.level(alarm.getRiskScore()));
        Alarm saved = repository.save(alarm);

        List<AlarmEvidenceInput> captured = evidence == null ? List.of() : evidence.stream()
                .filter(java.util.Objects::nonNull)
                .limit(200)
                .toList();
        persistEvidence(saved, captured);

        String deliveryPayload = AlarmPayloadCodec.write(saved, captured);
        deliveryRegistrar.register(tenant, saved.getId(), deliveryPayload);
        outboxRepository.save(pendingEvent(saved.getId(), deliveryPayload));
        enrichmentService.scheduleAfterCommit(saved);
        scheduleOutboxTrigger();
        return saved;
    }

    private void scheduleOutboxTrigger() {
        Runnable trigger = () -> {
            if (outboxPublisher != null) outboxPublisher.triggerAsync();
            if (deliveryPublisher != null) deliveryPublisher.triggerAsync();
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            trigger.run();
                        }
                    });
        } else {
            trigger.run();
        }
    }

    @Transactional(readOnly = true)
    public AlarmEvidenceResponse evidence(String alarmId) {
        Alarm alarm = repository.findByTenantIdAndId(AlarmQueryService.tenant(), alarmId)
                .orElseThrow(() -> com.socp.platform.error.exception.ApiException.notFound(
                        "Alarm does not exist: " + alarmId));
        List<AlarmEvidenceView> items = evidenceRepository
                .findByTenantIdAndAlarmIdOrderByEvidenceOrderAscIdAsc(
                        AlarmQueryService.tenant(), alarmId)
                .stream()
                .map(AlarmEvidence::view)
                .toList();
        String drilldown = items.stream()
                .map(AlarmEvidenceView::eventId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> "eventId=" + id)
                .collect(java.util.stream.Collectors.joining(" OR "));
        return new AlarmEvidenceResponse(alarm.getId(), items.size(), !items.isEmpty(), drilldown, items);
    }

    public List<Alarm> query(Severity severity, String rule, String text) {
        return queryService.query(severity, rule, null, text, "occurredAt", "descending");
    }

    public List<Alarm> query(Severity severity, String rule, String status, String text,
                             String sort, String order) {
        return queryService.query(severity, rule, status, text, sort, order);
    }

    public Page<Alarm> pageByTimestamp(String sort, String order, int page, int size) {
        return queryService.page(null, null, null, null, sort, order, page, size);
    }

    public Page<Alarm> page(Severity severity, String rule, String status, String text,
                            String sort, String order, int page, int size) {
        return queryService.page(severity, rule, status, text, sort, order, page, size);
    }

    public Alarm get(String id) {
        return queryService.get(id);
    }

    public Map<String, Object> stats() {
        return statisticsService.stats(null);
    }

    public Map<String, Object> stats(String window) {
        return statisticsService.stats(window);
    }

    private void persistEvidence(Alarm alarm, List<AlarmEvidenceInput> evidence) {
        if (evidence.isEmpty()) return;
        evidenceRepository.saveAll(java.util.stream.IntStream.range(0, evidence.size())
                .mapToObj(index -> AlarmEvidence.from(
                        alarm.getId(), alarm.getTenantId(), index, evidence.get(index)))
                .toList());
    }

    private static OutboxEvent pendingEvent(String alarmId, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateId(alarmId);
        event.setEventType("ALARM_CREATED");
        event.setPayload(payload);
        event.setStatus("PENDING");
        event.setAttempts(0);
        Instant now = Instant.now();
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        return event;
    }

    private int initialRisk(Alarm alarm) {
        com.socp.rule.model.Severity severity = alarm.getSeverity() == null
                ? com.socp.rule.model.Severity.INFO
                : com.socp.rule.model.Severity.valueOf(alarm.getSeverity().name());
        int recent = 0;
        if (alarm.getEntity() != null && !alarm.getEntity().isBlank()) {
            try {
                recent = (int) repository.countRecentByEntity(
                        alarm.getTenantId(), alarm.getEntity(), Instant.now().minus(Duration.ofHours(1)));
            } catch (RuntimeException ignoredProjectionFailure) {
                // Recent-count enrichment is optional; the base severity score remains deterministic.
            }
        }
        return com.socp.rule.score.RiskScorer.score(
                severity, alarm.getMitre(), 0, recent, 0).score();
    }

    /** Test/source compatibility; lifecycle ownership now lives in AlarmEnrichmentService. */
    void stopEnrichment() {
        enrichmentService.stop();
    }
}
