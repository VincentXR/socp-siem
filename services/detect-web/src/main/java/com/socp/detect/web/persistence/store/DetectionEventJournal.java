package com.socp.detect.web.persistence.store;


import com.socp.detect.web.persistence.repository.DetectionEventRepository;
import com.socp.detect.web.persistence.entity.DetectionEventEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.engine.DetectionResult;
import com.socp.rule.model.Severity;
import com.socp.rule.partition.DetectionDelivery;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * PostgreSQL/H2-backed journal for Detection event claims and state recovery.
 *
 * <p>Only COMPLETED events rebuild hot rule windows. PENDING rows are replayed
 * as live work after a restart/rebalance, while DEAD_LETTERED rows are
 * terminal and never silently re-enter the detection engine. The two terminal
 * states are mutually exclusive: neither overwrites the other, so a late
 * dead-letter receipt cannot undo a durable completion and a completion cannot
 * undo recorded dead-letter evidence.</p>
 */
@Component
public class DetectionEventJournal implements DetectionStateStore {

    private static final Logger log = LoggerFactory.getLogger(DetectionEventJournal.class);
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {};

    private final DetectionEventRepository repository;
    private final Duration retention;
    private final Duration completedRetention;
    private final Duration deadLetterRetention;
    private final int replayPageSize;
    private final int replayPendingMax;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatches;
    private final String inputTopic;
    private TransactionTemplate replayPageTransaction;

    @Autowired
    void configureReplayPageTransaction(PlatformTransactionManager manager) {
        replayPageTransaction = new TransactionTemplate(manager);
        replayPageTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        replayPageTransaction.setReadOnly(true);
    }

    /** Spring constructor keeps replay and terminal-retention policies explicit. */
    @Autowired
    public DetectionEventJournal(DetectionEventRepository repository,
                                 @Value("${socp.detect.state.retention:24h}") String retention,
                                 @Value("${socp.detect.state.replay-page-size:1000}") int replayPageSize,
                                 @Value("${socp.detect.state.completed-retention:7d}") String completedRetention,
                                 @Value("${socp.detect.state.dead-letter-retention:90d}") String deadLetterRetention,
                                 @Value("${socp.detect.state.cleanup-batch-size:1000}") int cleanupBatchSize,
                                 @Value("${socp.detect.state.cleanup-max-batches:10}") int cleanupMaxBatches,
                                 @Value("${socp.detect.state.replay-pending-max:100}") int replayPendingMax,
                                 @Value("${socp.detect.input-topic:${socp.kafka.topic:socp-events}}") String inputTopic) {
        this.repository = repository;
        this.retention = parsePositiveDuration(retention, Duration.ofHours(24));
        Duration configuredCompletedRetention = parsePositiveDuration(
                completedRetention, Duration.ofDays(7));
        // Completed rows are the source of truth for rebuilding the configured
        // replay window. Never let maintenance delete that source earlier than
        // the window it promises to recover.
        this.completedRetention = configuredCompletedRetention.compareTo(this.retention) < 0
                ? this.retention : configuredCompletedRetention;
        this.deadLetterRetention = parsePositiveDuration(deadLetterRetention, Duration.ofDays(90));
        this.replayPageSize = Math.max(100, Math.min(10_000, replayPageSize));
        this.replayPendingMax = Math.max(1, Math.min(10_000, replayPendingMax));
        this.cleanupBatchSize = Math.max(1, Math.min(10_000, cleanupBatchSize));
        this.cleanupMaxBatches = Math.max(1, Math.min(100, cleanupMaxBatches));
        this.inputTopic = inputTopic == null || inputTopic.isBlank() ? "socp-events" : inputTopic.trim();
    }

    /** Compatibility constructor used by focused policy tests. */
    public DetectionEventJournal(DetectionEventRepository repository, String retention,
                                 int replayPageSize, String completedRetention,
                                 String deadLetterRetention, int cleanupBatchSize,
                                 int cleanupMaxBatches) {
        this(repository, retention, replayPageSize, completedRetention, deadLetterRetention,
                cleanupBatchSize, cleanupMaxBatches, 100, "socp-events");
    }

    /** Compatibility constructor used by focused policy tests. */
    public DetectionEventJournal(DetectionEventRepository repository, String retention,
                                 int replayPageSize, String completedRetention,
                                 String deadLetterRetention) {
        this(repository, retention, replayPageSize, completedRetention, deadLetterRetention,
                1_000, 10, 100, "socp-events");
    }

    /** Compatibility constructor used by focused unit tests and local callers. */
    public DetectionEventJournal(DetectionEventRepository repository, String retention,
                                 int replayPageSize) {
        this(repository, retention, replayPageSize, "7d", "90d");
    }

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Override
    public boolean supportsBatchClaims() { return entityManager != null; }

    @Override
    @Transactional
    public List<DetectionEventClaim> preclaimBatch(List<DetectionStateStore.Input> inputs) {
        if (inputs == null || inputs.size() > 64) throw new IllegalArgumentException("journal batch limit is 64");
        Map<String, java.util.List<DetectionStateStore.Input>> tenants = new java.util.TreeMap<>();
        for (var input : inputs) {
            if (input.event() == null || input.event().id() == null || input.event().id().isBlank()) {
                throw new IllegalArgumentException("event id is required");
            }
            tenants.computeIfAbsent(input.event().requireTenantId(), ignored -> new java.util.ArrayList<>()).add(input);
        }
        Map<String, DetectionEventClaim> claims = new java.util.HashMap<>();
        org.hibernate.Session session = entityManager.unwrap(org.hibernate.Session.class);
        Integer previousBatchSize = session.getJdbcBatchSize();
        session.setJdbcBatchSize(32);
        try {
            for (var tenant : tenants.entrySet()) {
                try (var ignored = com.socp.platform.tenant.context.TenantContext.open(tenant.getKey())) {
                    var deliveries = new java.util.TreeMap<String, DetectionStateStore.Input>();
                    tenant.getValue().forEach(input -> deliveries.putIfAbsent(DetectionDelivery.deliveryId(input.event()), input));
                    var existing = new java.util.HashMap<String, DetectionEventEntity>();
                    repository.findByTenantIdAndDeliveryIdIn(tenant.getKey(), deliveries.keySet())
                            .forEach(row -> existing.put(row.getDeliveryId(), row));
                    for (var delivery : deliveries.entrySet()) {
                        var row = existing.get(delivery.getKey());
                        if (row == null) {
                            var input = delivery.getValue();
                            repository.save(journalRow(input.event(), input.topic(), input.partition(), input.offset(), input.routingKey()));
                        }
                        claims.put(tenant.getKey() + "|" + delivery.getKey(), row == null ? DetectionEventClaim.NEW : claimOf(row));
                    }
                    repository.flush();
                }
            }
        } finally {
            session.setJdbcBatchSize(previousBatchSize);
        }
        java.util.Set<String> issued = new java.util.HashSet<>();
        return inputs.stream().map(input -> {
            String key = input.event().requireTenantId() + "|" + DetectionDelivery.deliveryId(input.event());
            DetectionEventClaim claim = claims.get(key);
            return claim == DetectionEventClaim.NEW && !issued.add(key) ? DetectionEventClaim.PENDING : claim;
        }).toList();
    }

    private DetectionEventEntity journalRow(SecurityEvent event, String deliveryTopic,
                                            Integer partition, Long offset, String routingKey) {
        try {
            String fields = com.socp.rule.util.Json.mapper().writeValueAsString(event.fields());
            return new DetectionEventEntity(event.requireTenantId(), DetectionDelivery.deliveryId(event),
                    DetectionDelivery.sourceEventId(event), DetectionDelivery.routingVersion(event),
                    safe(event.source(), "unknown", 128), safe(event.host(), "unknown", 255),
                    safe(event.raw(), "", 8192), fields,
                    event.severity() == null ? Severity.INFO.name() : event.severity().name(),
                    event.timestamp() == null ? Instant.now() : event.timestamp(),
                    DetectionDelivery.sourceTopic(event), DetectionDelivery.sourcePartition(event),
                    DetectionDelivery.sourceOffset(event), deliveryTopic, partition, offset, routingKey);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("unable to encode detection journal event", failure);
        }
    }

    @Override
    @Transactional
    public DetectionEventClaim claim(SecurityEvent event) {
        return claim(event, inputTopic, null, null, null);
    }

    @Override
    @Transactional
    public DetectionEventClaim claim(SecurityEvent event, Integer partition, Long offset,
                                     String routingKey) {
        return claim(event, inputTopic, partition, offset, routingKey);
    }

    @Override
    @Transactional
    public DetectionEventClaim claim(SecurityEvent event, String deliveryTopic,
                                     Integer partition, Long offset, String routingKey) {
        if (event == null || event.id() == null || event.id().isBlank()) {
            throw new IllegalArgumentException("event id is required");
        }
        String tenant = event.requireTenantId();
        String deliveryId = DetectionDelivery.deliveryId(event);
        var existing = repository.findByTenantIdAndDeliveryId(tenant, deliveryId);
        if (existing.isPresent()) return claimOf(existing.get());

        try {
            repository.saveAndFlush(journalRow(event, deliveryTopic, partition, offset, routingKey));
            return DetectionEventClaim.NEW;
        } catch (DataIntegrityViolationException duplicate) {
            // The primary key is the final arbiter when two consumers race.
            // The caller retries; the next transaction observes the durable row.
            throw duplicate;
        } catch (Exception ex) {
            throw new IllegalStateException("unable to write detection event journal: " + ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional
    public List<DetectionEventClaim> claimBatch(List<DetectionEventInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > com.socp.rule.engine.RuleEngine.MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("bounded journal batch required");
        }
        String tenant = inputs.getFirst().event().requireTenantId();
        var ids = new java.util.LinkedHashSet<String>();
        for (DetectionEventInput input : inputs) {
            if (input.event().id() == null || input.event().id().isBlank()
                    || !tenant.equals(input.event().requireTenantId())
                    || !ids.add(DetectionDelivery.deliveryId(input.event()))) {
                throw new IllegalArgumentException("journal batch requires one tenant and unique deliveries");
            }
        }
        Map<String, DetectionEventEntity> existing = new java.util.HashMap<>();
        repository.findByTenantIdAndDeliveryIdIn(tenant, ids).forEach(row -> existing.put(row.getDeliveryId(), row));
        List<DetectionEventEntity> insertions = new ArrayList<>();
        List<DetectionEventClaim> claims = new ArrayList<>();
        for (DetectionEventInput input : inputs) {
            DetectionEventEntity row = existing.get(DetectionDelivery.deliveryId(input.event()));
            if (row != null) claims.add(claimOf(row));
            else {
                try { insertions.add(journalRow(input.event(), input.topic(), input.partition(), input.offset(), input.routingKey())); }
                catch (Exception failure) { throw new IllegalStateException("unable to encode detection journal batch", failure); }
                claims.add(DetectionEventClaim.NEW);
            }
        }
        repository.saveAll(insertions);
        repository.flush();
        return List.copyOf(claims);
    }

    @Override
    public boolean recordIfNew(SecurityEvent event) {
        return claim(event) == DetectionEventClaim.NEW;
    }

    @Override
    public boolean recordIfNew(SecurityEvent event, Integer partition, Long offset,
                               String routingKey) {
        return claim(event, partition, offset, routingKey) == DetectionEventClaim.NEW;
    }

    @Override
    @Transactional
    public void markCompleted(String eventId) {
        markCompleted(com.socp.platform.tenant.context.TenantContext.require(), eventId);
    }

    @Override
    @Transactional
    public void markCompleted(String tenantId, String eventId) {
        markCompletedInternal(tenantId, eventId, null);
    }

    @Override
    @Transactional
    public void markCompleted(DetectionResult result) {
        if (result == null || result.event() == null) return;
        markCompletedInternal(result.event().requireTenantId(),
                DetectionDelivery.deliveryId(result.event()), result);
    }

    @Override
    @Transactional
    public void markCompletedBatch(List<DetectionResult> results) {
        if (results == null || results.isEmpty() || results.size() > com.socp.rule.engine.RuleEngine.MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("bounded completion batch required");
        }
        String tenant = results.getFirst().event().requireTenantId();
        if (results.stream().anyMatch(result -> !tenant.equals(result.event().requireTenantId()))) {
            throw new IllegalArgumentException("completion batch must belong to one tenant");
        }
        var deliveries = new java.util.HashSet<String>();
        if (results.stream().anyMatch(result -> !deliveries.add(DetectionDelivery.deliveryId(result.event())))) {
            throw new IllegalArgumentException("completion batch requires unique deliveries");
        }
        // Distinct PostgreSQL microseconds retain evaluation order during journal replay.
        Instant completedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var summaries = results.stream().map(result -> {
            try { return com.socp.rule.util.Json.mapper().writeValueAsString(result.auditSummary()); }
            catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("unable to encode detection result", failure);
            }
        }).toList();
        entityManager.flush();
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
            // Keep the unique delivery identity as the lookup predicate. A status
            // predicate can choose a tiny estimated PENDING index and scan a large
            // newly admitted backlog before statistics catch up.
            String sql = "update t_detection_event set "
                    + "completed_at=case when status='PENDING' then ? else completed_at end, "
                    + "status_reason=case when status='PENDING' then null else status_reason end, "
                    + "result_json=case when status='PENDING' then ? else result_json end, "
                    + "status=case when status='PENDING' then 'COMPLETED' else status end "
                    + "where tenant_id=? and delivery_id=?";
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < results.size(); i++) {
                    statement.setTimestamp(1, java.sql.Timestamp.from(completedAt.plusNanos(i * 1_000L)));
                    statement.setString(2, summaries.get(i));
                    statement.setString(3, tenant);
                    statement.setString(4, DetectionDelivery.deliveryId(results.get(i).event()));
                    statement.addBatch();
                }
                for (int changed : statement.executeBatch()) {
                    if (changed != 1) throw new IllegalStateException("missing detection journal receipt during completion");
                }
            }
        });
    }

    private void markCompletedInternal(String tenantId, String eventId, DetectionResult result) {
        if (eventId == null || eventId.isBlank()) return;
        if (result != null) {
            try {
                String summary = com.socp.rule.util.Json.mapper().writeValueAsString(result.auditSummary());
                if (repository.completeResult(tenantId, eventId, summary, Instant.now()) == 1) return;
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("unable to persist detection result summary", failure);
            }
        }
        repository.findByTenantIdAndDeliveryId(tenantId, eventId).ifPresent(row -> {
            if (completeRow(row, result)) repository.saveAndFlush(row);
        });
    }

    private static boolean completeRow(DetectionEventEntity row, DetectionResult result) {
        return completeRow(row, result, Instant.now());
    }

    private static boolean completeRow(DetectionEventEntity row, DetectionResult result, Instant now) {
        // The result sink and the consumer's fenced finalization can both
        // observe completion. A terminal receipt is immutable: avoid a
        // second UPDATE/flush and keep its original completion timestamp.
        if (DetectionEventStatus.DEAD_LETTERED.name().equals(row.getStatus())
                || DetectionEventStatus.COMPLETED.name().equals(row.getStatus())) return false;
        row.setStatus(DetectionEventStatus.COMPLETED.name());
        row.setCompletedAt(now);
        row.setStatusReason(null);
        if (result != null) {
            try {
                row.setResultJson(com.socp.rule.util.Json.mapper()
                        .writeValueAsString(result.auditSummary()));
            } catch (Exception failure) {
                throw new IllegalStateException("unable to persist detection result summary", failure);
            }
        }
        return true;
    }

    @Override
    @Transactional
    public void markDeadLettered(String eventId, String reason) {
        markDeadLettered(com.socp.platform.tenant.context.TenantContext.require(), eventId, reason);
    }

    @Override
    @Transactional
    public void markDeadLettered(String tenantId, String eventId, String reason) {
        if (eventId == null || eventId.isBlank()) return;
        repository.findByTenantIdAndDeliveryId(tenantId, eventId).ifPresent(row -> {
            if (DetectionEventStatus.COMPLETED.name().equals(row.getStatus())) {
                // Completion is the durable receipt of every required write for
                // this event. A late hand-off - possibly from a replica that lost
                // the partition - must never re-terminalise it as a failure.
                log.info("Detection dead-letter receipt skipped for completed eventId={}", eventId);
                return;
            }
            Instant now = Instant.now();
            row.setStatus(DetectionEventStatus.DEAD_LETTERED.name());
            row.setDeadLetteredAt(now);
            row.setStatusReason(truncate(reason));
            repository.saveAndFlush(row);
        });
    }

    @Override
    @Transactional
    public void recordDeadLettered(String eventId, String raw, Integer partition, Long offset,
                                   String reason) {
        if (eventId == null || eventId.isBlank() || "null".equalsIgnoreCase(eventId)) return;
        String tenant = com.socp.platform.tenant.context.TenantContext.get();
        if (tenant == null || tenant.isBlank()) {
            log.warn("Skipping terminal Detection journal row without a tenant eventId={}", eventId);
            return;
        }
        var existing = repository.findByTenantIdAndDeliveryId(tenant, eventId);
        if (existing.isPresent()) {
            markDeadLettered(tenant, eventId, reason);
            return;
        }
        DetectionEventEntity row = new DetectionEventEntity(
                tenant, eventId, "unknown", "unknown", safe(raw, "", 8192), "{}", Severity.INFO.name(),
                Instant.now(), partition, offset, null);
        row.setStatus(DetectionEventStatus.DEAD_LETTERED.name());
        row.setDeadLetteredAt(Instant.now());
        row.setStatusReason(truncate(reason));
        repository.saveAndFlush(row);
    }

    @Override
    @Transactional
    public void remove(String eventId) {
        remove(com.socp.platform.tenant.context.TenantContext.require(), eventId);
    }

    @Override
    @Transactional
    public void remove(String tenantId, String eventId) {
        if (eventId == null || eventId.isBlank()) return;
        repository.findByTenantIdAndDeliveryId(tenantId, eventId).ifPresent(repository::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> recent(Duration window) {
        if (TenantContext.isSystemScope()) {
            return readPages((page, size) -> repository
                    .findByStatusAndDeliveryTopicAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                            DetectionEventStatus.COMPLETED.name(), inputTopic, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), true);
        }
        String tenant = TenantContext.require();
        return readPages((page, size) -> repository
                .findByTenantIdAndStatusAndDeliveryTopicAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                        tenant, DetectionEventStatus.COMPLETED.name(), inputTopic, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> recentForPartitions(Set<Integer> partitions, Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        if (TenantContext.isSystemScope()) {
            return readPages((page, size) -> repository
                    .findByStatusAndTopicAndKafkaPartitionInAfter(
                            DetectionEventStatus.COMPLETED.name(), inputTopic, partitions, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), false);
        }
        String tenant = TenantContext.require();
        return readPages((page, size) -> repository
                .findByTenantStatusTopicAndKafkaPartitionInAfter(
                        tenant, DetectionEventStatus.COMPLETED.name(), inputTopic, partitions, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), false);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void replayRecent(Duration window, Consumer<List<SecurityEvent>> batchConsumer) {
        if (TenantContext.isSystemScope()) {
            replayPages((page, size) -> repository
                    .findByStatusAndDeliveryTopicAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                            DetectionEventStatus.COMPLETED.name(), inputTopic, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
            return;
        }
        String tenant = TenantContext.require();
        replayPages((page, size) -> repository
                .findByTenantIdAndStatusAndDeliveryTopicAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                        tenant, DetectionEventStatus.COMPLETED.name(), inputTopic, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void replayRecentForPartitions(Set<Integer> partitions, Duration window,
                                          Consumer<List<SecurityEvent>> batchConsumer) {
        if (partitions == null || partitions.isEmpty()) return;
        if (TenantContext.isSystemScope()) {
            replayPages((page, size) -> repository
                    .findByStatusAndTopicAndKafkaPartitionInAfter(
                            DetectionEventStatus.COMPLETED.name(), inputTopic, partitions, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
            return;
        }
        String tenant = TenantContext.require();
        replayPages((page, size) -> repository
                .findByTenantStatusTopicAndKafkaPartitionInAfter(
                        tenant, DetectionEventStatus.COMPLETED.name(), inputTopic, partitions, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void replayRecentForTenant(String tenantId, Duration window,
                                      Consumer<List<SecurityEvent>> batchConsumer) {
        replayPages((page, size) -> repository
                .findByTenantIdAndStatusAndDeliveryTopicAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                        tenantId, DetectionEventStatus.COMPLETED.name(), inputTopic, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> pendingForPartitions(Set<Integer> partitions, Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        if (TenantContext.isSystemScope()) {
            return readPages((page, size) -> repository
                    .findByStatusAndTopicAndKafkaPartitionInAfter(
                            DetectionEventStatus.PENDING.name(), inputTopic, partitions, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), false);
        }
        String tenant = TenantContext.require();
        return readPages((page, size) -> repository
                .findByTenantStatusTopicAndKafkaPartitionInAfter(
                        tenant, DetectionEventStatus.PENDING.name(), inputTopic, partitions, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingDetectionEvent> pendingRecordsForPartitions(Set<Integer> partitions,
                                                                    Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        String tenant = TenantContext.isSystemScope() ? null : TenantContext.require();
        org.springframework.data.domain.Pageable request =
                org.springframework.data.domain.PageRequest.of(0, replayPendingMax);
        List<DetectionEventEntity> rows = tenant == null
                ? repository.findByStatusAndTopicAndKafkaPartitionInAfter(
                            DetectionEventStatus.PENDING.name(), inputTopic, partitions, cutoff(window), request)
                : repository.findByTenantStatusTopicAndKafkaPartitionInAfter(
                        tenant, DetectionEventStatus.PENDING.name(), inputTopic, partitions, cutoff(window), request);
        return rows.stream().map(this::pendingRow).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * True per-page streaming PENDING replay: hands each bounded page to the
     * consumer instead of materialising the window, and stops at
     * {@code maxRecords}. Anything past the cap stays behind uncommitted Kafka
     * offsets for redelivery, so heap use is one page, not the retention window.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void replayPendingPages(Set<Integer> partitions, Duration window, int maxRecords,
                                   Consumer<List<PendingDetectionEvent>> batchConsumer) {
        if (partitions == null || partitions.isEmpty() || batchConsumer == null) return;
        int cap = Math.max(1, Math.min(maxRecords, 10_000));
        String tenant = TenantContext.isSystemScope() ? null : TenantContext.require();
        int dispatched = 0;
        for (int page = 0; dispatched < cap; page++) {
            org.springframework.data.domain.Pageable request =
                    org.springframework.data.domain.PageRequest.of(page, replayPageSize);
            List<DetectionEventEntity> rows = readReplayPage(() -> tenant == null
                    ? repository.findByStatusAndTopicAndKafkaPartitionInAfter(
                            DetectionEventStatus.PENDING.name(), inputTopic, partitions,
                            cutoff(window), request)
                    : repository.findByTenantStatusTopicAndKafkaPartitionInAfter(
                            tenant, DetectionEventStatus.PENDING.name(), inputTopic, partitions,
                            cutoff(window), request));
            List<PendingDetectionEvent> batch = new ArrayList<>();
            for (DetectionEventEntity row : rows) {
                PendingDetectionEvent pending = pendingRow(row);
                if (pending == null) continue;
                if (dispatched + batch.size() >= cap) break;
                batch.add(pending);
            }
            if (!batch.isEmpty()) {
                dispatched += batch.size();
                batchConsumer.accept(batch);
            }
            if (rows.size() < replayPageSize) break;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingCount() {
        return TenantContext.isSystemScope()
                ? repository.countByStatusAndDeliveryTopic(DetectionEventStatus.PENDING.name(), inputTopic)
                : repository.countByTenantIdAndStatusAndDeliveryTopic(
                        TenantContext.require(), DetectionEventStatus.PENDING.name(), inputTopic);
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingCount(String tenantId) {
        return repository.countByTenantIdAndStatusAndDeliveryTopic(
                tenantId, DetectionEventStatus.PENDING.name(), inputTopic);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean replayRecoveryTenants(Set<Integer> partitions, Duration window, Consumer<String> consumer) {
        String tenant = TenantContext.isSystemScope() ? null : TenantContext.require();
        Instant after = cutoff(window);
        for (int page = 0; ; page++) {
            var request = org.springframework.data.domain.PageRequest.of(page, 32);
            List<String> tenants = readReplayPage(() -> partitions == null || partitions.isEmpty()
                    ? repository.recoveryTenants(DetectionEventStatus.COMPLETED.name(), inputTopic, after, tenant, request)
                    : repository.recoveryTenantsForPartitions(DetectionEventStatus.COMPLETED.name(), inputTopic,
                            after, tenant, partitions, request));
            tenants.forEach(consumer);
            if (tenants.size() < 32) return true;
        }
    }

    @Override
    public String recoveryWindow() {
        return retention.toString();
    }

    @Override
    public boolean supportsCheckpointReplay() {
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void replayCompletedAfter(String tenantId, Instant checkpoint,
                                     Set<Integer> partitions,
                                     Consumer<List<SecurityEvent>> batchConsumer) {
        if (tenantId == null || tenantId.isBlank() || checkpoint == null || batchConsumer == null) return;
        for (int page = 0; ; page++) {
            org.springframework.data.domain.Pageable request =
                    org.springframework.data.domain.PageRequest.of(page, replayPageSize);
            List<DetectionEventEntity> rows = readReplayPage(() -> partitions == null || partitions.isEmpty()
                    ? repository.findByTenantStatusTopicCompletedAfter(
                            tenantId, DetectionEventStatus.COMPLETED.name(), inputTopic, checkpoint, request)
                    : repository.findByTenantStatusTopicPartitionsCompletedAfter(
                            tenantId, DetectionEventStatus.COMPLETED.name(), inputTopic,
                            partitions, checkpoint, request));
            List<SecurityEvent> events = fromRows(rows);
            if (!events.isEmpty()) batchConsumer.accept(events);
            if (rows.size() < replayPageSize) break;
        }
    }

    /**
     * Replay only records whose Kafka position is newer than the checkpoint
     * vector.  The vector is the complete correctness boundary: completion
     * timestamps are deliberately not used as a lower bound because producer
     * and database clocks can make a newer offset look older in wall-clock
     * order. The journal retention policy keeps this scan bounded.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void replayCompletedAfter(String tenantId, Instant checkpoint,
                                     Set<Integer> partitions, Map<Integer, Long> offsets,
                                     Consumer<List<SecurityEvent>> batchConsumer) {
        if (offsets == null || offsets.isEmpty()) {
            replayCompletedAfter(tenantId, checkpoint, partitions, batchConsumer);
            return;
        }
        if (tenantId == null || tenantId.isBlank() || batchConsumer == null) return;
        Set<Integer> owned = partitions == null || partitions.isEmpty()
                ? new java.util.TreeSet<>(readReplayPage(() -> repository.completedPartitions(
                        tenantId, DetectionEventStatus.COMPLETED.name(), inputTopic)))
                : new java.util.TreeSet<>(partitions);
        // Push the checkpoint cut into SQL. Reading every payload and filtering
        // in Java makes a nearly current checkpoint as expensive as full replay.
        for (int partition : owned) {
            long cut = offsets.getOrDefault(partition, -1L);
            for (int page = 0; ; page++) {
                var request = org.springframework.data.domain.PageRequest.of(page, replayPageSize);
                List<DetectionEventEntity> rows = readReplayPage(() -> repository.partitionTail(
                        tenantId, DetectionEventStatus.COMPLETED.name(), inputTopic, partition, cut, request));
                List<SecurityEvent> events = fromRows(rows);
                if (!events.isEmpty()) batchConsumer.accept(events);
                if (rows.size() < replayPageSize) break;
            }
        }
    }

    public Duration retention() {
        return retention;
    }

    /**
     * Keep retention maintenance out of the per-event claim transaction.
     * PENDING rows are deliberately excluded: they represent work whose Kafka
     * offset cannot advance and must remain recoverable regardless of age.
     * COMPLETED and DEAD_LETTERED rows have independent retention clocks. A
     * terminal failure is retained longer because it is the durable evidence
     * needed to explain why an input was not evaluated.
     */
    @Scheduled(
            fixedDelayString = "${socp.detect.state.cleanup-interval-ms:600000}",
            initialDelayString = "${socp.detect.state.cleanup-initial-delay-ms:600000}")
    @TenantSystemJob
    public void cleanupExpiredTerminalEvents() {
        try {
            Instant now = Instant.now();
            long completed = deleteCompletedBatches(now.minus(completedRetention));
            long deadLettered = deleteDeadLetteredBatches(now.minus(deadLetterRetention));
            if (completed > 0 || deadLettered > 0) {
                log.info("Expired Detection journal rows removed completed={} deadLettered={}",
                        completed, deadLettered);
            }
        } catch (Exception failure) {
            log.warn("Detection journal retention cleanup deferred: {}", failure.getMessage());
        }
    }

    private long deleteCompletedBatches(Instant cutoff) {
        long deleted = 0;
        for (int batch = 0; batch < cleanupMaxBatches; batch++) {
            int count = repository.deleteCompletedBatchBefore(
                    DetectionEventStatus.COMPLETED.name(), cutoff, cleanupBatchSize);
            deleted += count;
            if (count < cleanupBatchSize) break;
        }
        return deleted;
    }

    private long deleteDeadLetteredBatches(Instant cutoff) {
        long deleted = 0;
        for (int batch = 0; batch < cleanupMaxBatches; batch++) {
            int count = repository.deleteDeadLetteredBatchBefore(
                    DetectionEventStatus.DEAD_LETTERED.name(), cutoff, cleanupBatchSize);
            deleted += count;
            if (count < cleanupBatchSize) break;
        }
        return deleted;
    }

    private List<SecurityEvent> readPages(PageReader reader, boolean sortByTimestamp) {
        List<DetectionEventEntity> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            List<DetectionEventEntity> batch = reader.read(page, replayPageSize);
            rows.addAll(batch);
            if (batch.size() < replayPageSize) break;
        }
        List<SecurityEvent> out = fromRows(rows);
        if (sortByTimestamp) out.sort(Comparator.comparing(SecurityEvent::timestamp));
        return out;
    }

    /**
     * Streaming replay entry points suspend any caller transaction. In particular,
     * a rule reload has a catalogue transaction: joining it would retain every
     * journal entity in Hibernate's first-level cache despite the bounded pages.
     * Suspension alone still creates an empty Spring synchronization scope that
     * can retain an EntityManager. Each page therefore has its own read-only
     * transaction, closed before the callback, so both actual transactions and
     * synchronization-only scopes remain bounded. Caller writes stay managed.
     */
    private void replayPages(PageReader reader, Consumer<List<SecurityEvent>> batchConsumer) {
        for (int page = 0; ; page++) {
            int pageNumber = page;
            List<DetectionEventEntity> rows = readReplayPage(() -> reader.read(pageNumber, replayPageSize));
            List<SecurityEvent> events = fromRows(rows);
            if (!events.isEmpty()) batchConsumer.accept(events);
            if (rows.size() < replayPageSize) break;
        }
    }

    private <T> List<T> readReplayPage(Supplier<List<T>> reader) {
        // Directly constructed policy/unit-test stores have no persistence context.
        return replayPageTransaction == null ? reader.get()
                : replayPageTransaction.execute(ignored -> reader.get());
    }

    private List<SecurityEvent> fromRows(List<DetectionEventEntity> rows) {
        List<SecurityEvent> out = new ArrayList<>();
        for (DetectionEventEntity row : rows) {
            try {
                Map<String, String> fields = com.socp.rule.util.Json.mapper()
                        .readValue(row.getFieldsJson(), FIELDS);
                fields.putIfAbsent("tenant_id", row.getTenantId());
                fields.putIfAbsent(DetectionDelivery.DELIVERY_ID_FIELD, row.getDeliveryId());
                fields.putIfAbsent(DetectionDelivery.SOURCE_EVENT_ID_FIELD, row.getSourceEventId());
                fields.putIfAbsent(DetectionDelivery.ROUTING_VERSION_FIELD, row.getRoutingVersion());
                if (row.getSourceTopic() != null) {
                    fields.putIfAbsent(DetectionDelivery.SOURCE_TOPIC_FIELD, row.getSourceTopic());
                }
                if (row.getSourcePartition() != null) {
                    fields.putIfAbsent(DetectionDelivery.SOURCE_PARTITION_FIELD,
                            String.valueOf(row.getSourcePartition()));
                }
                if (row.getSourceOffset() != null) {
                    fields.putIfAbsent(DetectionDelivery.SOURCE_OFFSET_FIELD,
                            String.valueOf(row.getSourceOffset()));
                }
                Severity severity;
                try {
                    severity = Severity.valueOf(row.getSeverity().toUpperCase());
                } catch (Exception ignored) {
                    severity = Severity.INFO;
                }
                out.add(new SecurityEvent(row.getEventId(), row.getOccurredAt(), row.getSource(),
                        row.getHost(), row.getRaw(), fields, severity));
            } catch (Exception ex) {
                log.warn("Unable to restore detection event eventId={}: {}",
                        row.getEventId(), ex.getMessage());
            }
        }
        return out;
    }

    private PendingDetectionEvent pendingRow(DetectionEventEntity row) {
        List<SecurityEvent> events = fromRows(List.of(row));
        if (events.isEmpty()) return null;
        return new PendingDetectionEvent(events.get(0), row.getKafkaPartition(), row.getKafkaOffset());
    }

    private static DetectionEventClaim claimOf(DetectionEventEntity row) {
        if (DetectionEventStatus.PENDING.name().equals(row.getStatus())) {
            return DetectionEventClaim.PENDING;
        }
        if (DetectionEventStatus.DEAD_LETTERED.name().equals(row.getStatus())) {
            return DetectionEventClaim.DEAD_LETTERED;
        }
        return DetectionEventClaim.COMPLETED;
    }

    private Instant cutoff(Duration window) {
        Duration requested = window == null || window.isNegative() || window.isZero()
                ? retention : window;
        return Instant.now().minus(requested.compareTo(retention) > 0 ? retention : requested);
    }

    private static String safe(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String truncate(String value) {
        return safe(value, "unknown", 1024);
    }

    private static Duration parseDuration(String value) {
        return parseDuration(value, Duration.ofHours(24));
    }

    private static Duration parseDuration(String value, Duration fallback) {
        if (value == null || value.isBlank()) return fallback;
        String s = value.trim().toLowerCase();
        try {
            if (s.endsWith("d")) return Duration.ofDays(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException | ArithmeticException ex) {
            return fallback;
        }
    }

    private static Duration parsePositiveDuration(String value, Duration fallback) {
        Duration parsed = parseDuration(value, fallback);
        return parsed.isNegative() || parsed.isZero() ? fallback : parsed;
    }

    @FunctionalInterface
    private interface PageReader {
        List<DetectionEventEntity> read(int page, int size);
    }
}
