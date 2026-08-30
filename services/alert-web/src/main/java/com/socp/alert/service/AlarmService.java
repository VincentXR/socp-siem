package com.socp.alert.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmEvidence;
import com.socp.alert.domain.AlarmEvidenceInput;
import com.socp.alert.domain.AlarmEvidenceView;
import com.socp.alert.domain.OutboxEvent;
import com.socp.alert.domain.Severity;
import com.socp.alert.api.request.CreateAlarmRequest;
import com.socp.alert.api.response.AlarmEvidenceResponse;
import com.socp.alert.persistence.entity.AlarmBatchIdempotency;
import com.socp.alert.repository.AlarmEvidenceRepository;
import com.socp.alert.repository.AlarmBatchIdempotencyRepository;
import com.socp.alert.repository.AlarmRepository;
import com.socp.alert.repository.OutboxRepository;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates the transactional alarm write boundary and delegates read/enrichment concerns. */
@Service
public class AlarmService {

    private static final ObjectMapper BATCH_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final AlarmRepository repository;
    private final OutboxRepository outboxRepository;
    private final AlarmEvidenceRepository evidenceRepository;
    private final AlarmDeliveryRegistrar deliveryRegistrar;
    private final AlarmEnrichmentService enrichmentService;
    private final AlarmQueryService queryService;
    private final AlarmStatisticsService statisticsService;
    private final OutboxPublisher outboxPublisher;
    private final AlarmDeliveryPublisher deliveryPublisher;
    private final AlarmBatchIdempotencyRepository batchIdempotencyRepository;

    @Autowired
    public AlarmService(AlarmRepository repository, OutboxRepository outboxRepository,
                        AlarmEvidenceRepository evidenceRepository,
                        AlarmDeliveryRegistrar deliveryRegistrar,
                        AlarmEnrichmentService enrichmentService,
                        AlarmQueryService queryService,
                        AlarmStatisticsService statisticsService,
                        OutboxPublisher outboxPublisher,
                        AlarmDeliveryPublisher deliveryPublisher,
                        AlarmBatchIdempotencyRepository batchIdempotencyRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.evidenceRepository = evidenceRepository;
        this.deliveryRegistrar = deliveryRegistrar;
        this.enrichmentService = enrichmentService;
        this.queryService = queryService;
        this.statisticsService = statisticsService;
        this.outboxPublisher = outboxPublisher;
        this.deliveryPublisher = deliveryPublisher;
        this.batchIdempotencyRepository = batchIdempotencyRepository;
    }

    public AlarmService(AlarmRepository repository, OutboxRepository outboxRepository,
                        AlarmEvidenceRepository evidenceRepository,
                        AlarmDeliveryRegistrar deliveryRegistrar,
                        AlarmEnrichmentService enrichmentService,
                        AlarmQueryService queryService,
                        AlarmStatisticsService statisticsService) {
        this(repository, outboxRepository, evidenceRepository, deliveryRegistrar,
                enrichmentService, queryService, statisticsService, null, null, null);
    }

    public Alarm create(Alarm alarm) {
        return create(alarm, List.of());
    }

    @Transactional
    public Alarm create(Alarm alarm, List<AlarmEvidenceInput> evidence) {
        return createInternal(alarm, evidence);
    }

    /**
     * Atomically materialize a bounded alarm batch.  The idempotency record is
     * written in the same transaction as the alarms and their outbox rows, so
     * a replay can return the original response without re-emitting side
     * effects.  A changed payload with the same key is rejected with 409.
     */
    @Transactional
    public Map<String, Object> createBatch(List<CreateAlarmRequest> requests, String idempotencyKey) {
        String tenant = AlarmQueryService.tenant();
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isBlank() || key.length() > 255) {
            throw com.socp.platform.error.exception.ApiException.badRequest(
                    "Idempotency-Key is required and must be at most 255 characters");
        }
        if (requests == null || requests.isEmpty() || requests.size() > 500) {
            throw com.socp.platform.error.exception.ApiException.badRequest(
                    "alarms batch must contain between 1 and 500 items");
        }
        String requestHash = batchHash(requests);
        if (batchIdempotencyRepository != null) {
            var existing = batchIdempotencyRepository.findByTenantIdAndIdempotencyKey(tenant, key);
            if (existing.isPresent()) {
                AlarmBatchIdempotency row = existing.get();
                if (!requestHash.equals(row.getRequestHash())) {
                    throw com.socp.platform.error.exception.ApiException.of(
                            409, "Idempotency-Key was already used with a different request");
                }
                return readBatchResponse(row.getResponseJson());
            }
        }

        List<String> accepted = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        List<Map<String, Object>> alarms = new ArrayList<>();
        for (CreateAlarmRequest request : requests) {
            Alarm candidate = fromRequest(request);
            boolean duplicate = candidate.getSourceAlertId() != null
                    && !candidate.getSourceAlertId().isBlank()
                    && repository.findByTenantIdAndSourceAlertId(tenant, candidate.getSourceAlertId()).isPresent();
            Alarm saved = createInternal(candidate,
                    request.evidence() == null ? List.of() : request.evidence());
            String alarmId = saved.getId();
            if (alarmId != null) {
                (duplicate ? duplicates : accepted).add(alarmId);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", alarmId);
            item.put("sourceAlertId", saved.getSourceAlertId());
            item.put("duplicate", duplicate);
            alarms.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("idempotencyKey", key);
        response.put("accepted", List.copyOf(accepted));
        response.put("duplicates", List.copyOf(duplicates));
        response.put("count", requests.size());
        response.put("alarms", List.copyOf(alarms));
        if (batchIdempotencyRepository != null) {
            AlarmBatchIdempotency row = new AlarmBatchIdempotency();
            row.setId(java.util.UUID.randomUUID().toString());
            row.setTenantId(tenant);
            row.setIdempotencyKey(key);
            row.setRequestHash(requestHash);
            row.setResponseJson(writeBatchResponse(response));
            batchIdempotencyRepository.saveAndFlush(row);
        }
        return response;
    }

    private Alarm createInternal(Alarm alarm, List<AlarmEvidenceInput> evidence) {
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
        outboxRepository.save(pendingEvent(tenant, saved.getId(), deliveryPayload));
        enrichmentService.scheduleAfterCommit(saved);
        scheduleOutboxTrigger();
        return saved;
    }

    private static Alarm fromRequest(CreateAlarmRequest req) {
        if (req == null) throw com.socp.platform.error.exception.ApiException.badRequest("alarm item is required");
        Alarm alarm = new Alarm(req.ruleId(), req.ruleName(), req.severity(), req.message(), req.entity(),
                req.mitre(), null);
        alarm.setSourceAlertId(req.sourceAlertId());
        if (req.occurredAt() != null) alarm.setOccurredAt(req.occurredAt());
        alarm.setRiskScore(req.riskScore());
        alarm.setTriggerIngestedAt(req.triggerIngestedAt());
        alarm.setAlertCreatedAt(req.alertCreatedAt());
        alarm.setProcessingLatencyMs(req.processingLatencyMs());
        alarm.setTriggerEventId(req.triggerEventId());
        return alarm;
    }

    private static String batchHash(List<CreateAlarmRequest> requests) {
        try {
            byte[] bytes = BATCH_MAPPER.writeValueAsBytes(requests);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("unable to hash alarm batch", failure);
        }
    }

    private static String writeBatchResponse(Map<String, Object> response) {
        try {
            return BATCH_MAPPER.writeValueAsString(response);
        } catch (Exception failure) {
            throw new IllegalStateException("unable to persist alarm batch response", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBatchResponse(String responseJson) {
        try {
            return BATCH_MAPPER.readValue(responseJson, LinkedHashMap.class);
        } catch (Exception failure) {
            throw new IllegalStateException("stored alarm batch response is corrupt", failure);
        }
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

    /** Return bounded same-rule/entity candidates for an investigation drill-down. */
    @Transactional(readOnly = true)
    public List<Alarm> similar(String alarmId, int limit) {
        String tenant = AlarmQueryService.tenant();
        Alarm source = repository.findByTenantIdAndId(tenant, alarmId)
                .orElseThrow(() -> com.socp.platform.error.exception.ApiException.notFound(
                        "Alarm does not exist: " + alarmId));
        int bounded = Math.max(1, Math.min(100, limit));
        return repository.findSimilar(tenant, source.getId(), source.getRuleId(), source.getEntity(),
                PageRequest.of(0, bounded));
    }

    /** Exposes the durable downstream receipt state without exposing payloads. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deliveryStatus(String alarmId) {
        String tenant = AlarmQueryService.tenant();
        repository.findByTenantIdAndId(tenant, alarmId)
                .orElseThrow(() -> com.socp.platform.error.exception.ApiException.notFound(
                        "Alarm does not exist: " + alarmId));
        return deliveryRegistrar.status(tenant, alarmId);
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

    private static OutboxEvent pendingEvent(String tenantId, String alarmId, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setTenantId(tenantId);
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
