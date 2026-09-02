package com.socp.detect.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.persistence.repository.RuleChangeOutboxRepository;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.platform.data.outbox.OutboxRetryPolicy;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transactional outbox for tenant-scoped rule-change broadcasts. */
@Component
public class RuleChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(RuleChangePublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final int DEFAULT_MAX_ATTEMPTS = 12;
    private static final long DEFAULT_RETENTION_MS = Duration.ofDays(30).toMillis();
    private static final Duration DISCARDED_RETENTION = Duration.ofDays(30);
    private static final int DEFAULT_MAX_DRAIN_ROUNDS = 64;
    private static final long DEFAULT_MAX_DRAIN_DURATION_MS = 2_000L;
    private static final int DEFAULT_CLEANUP_BATCH_SIZE = 1_000;
    private static final int DEFAULT_CLEANUP_MAX_BATCHES = 10;

    private final RuleChangeOutboxRepository repository;
    private final DetectionPerformanceMetrics performanceMetrics;

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.rule-topic:socp-rule-changes}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled = true;

    @Value("${socp.kafka.rule-outbox.max-attempts:12}")
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    @Value("${socp.kafka.rule-outbox.retention-ms:2592000000}")
    private long retentionMs = DEFAULT_RETENTION_MS;

    @Value("${socp.kafka.rule-outbox.max-drain-rounds:64}")
    private int maxDrainRounds = DEFAULT_MAX_DRAIN_ROUNDS;

    @Value("${socp.kafka.rule-outbox.max-drain-duration-ms:2000}")
    private long maxDrainDurationMs = DEFAULT_MAX_DRAIN_DURATION_MS;

    @Value("${socp.kafka.rule-outbox.cleanup-batch-size:1000}")
    private int cleanupBatchSize = DEFAULT_CLEANUP_BATCH_SIZE;

    @Value("${socp.kafka.rule-outbox.cleanup-max-batches:10}")
    private int cleanupMaxBatches = DEFAULT_CLEANUP_MAX_BATCHES;

    private volatile KafkaProducer<String, String> producer;
    private Instant nextRecoveryAt = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public RuleChangePublisher(RuleChangeOutboxRepository repository,
                               DetectionPerformanceMetrics performanceMetrics) {
        this.repository = repository;
        this.performanceMetrics = performanceMetrics;
    }

    /** Unit-test/source compatibility constructor. */
    public RuleChangePublisher(RuleChangeOutboxRepository repository) {
        this(repository, null);
    }

    @Transactional
    public void publish(String ruleId, String action) {
        String tenant = TenantContext.require();
        Instant now = Instant.now();
        RuleChangeOutbox row = new RuleChangeOutbox();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setRuleId(ruleId);
        row.setAction(action);
        row.setStatus("PENDING");
        row.setAttempts(0);
        row.setNextAttemptAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        repository.save(row);
    }

    @Scheduled(fixedDelayString = "${socp.kafka.rule-publish-interval-ms:500}",
            initialDelayString = "${socp.kafka.rule-publish-initial-delay-ms:500}")
    @TenantSystemJob
    public void flush() {
        if (!enabled) {
            refreshBacklog();
            return;
        }
        try {
            Instant now = Instant.now();
            recoverStaleIfDue(now);
            int exhausted = repository.markExhausted(effectiveMaxAttempts(), "retry limit reached", now);
            if (exhausted > 0) {
                log.error("Rule-change outbox rows moved to DEAD after retry limit count={}", exhausted);
                lifecycle("dead", exhausted);
            }
            int rounds = 0;
            long deadline = System.nanoTime() + Duration.ofMillis(effectiveDrainDurationMs()).toNanos();
            int roundLimit = effectiveMaxDrainRounds();
            while (rounds < roundLimit && System.nanoTime() < deadline) {
                List<RuleChangeOutbox> pending = repository
                        .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", Instant.now());
                if (pending.isEmpty()) break;
                rounds++;
                for (RuleChangeOutbox row : pending) deliver(row);
                if (pending.size() < 100) break;
            }
        } catch (RuntimeException failure) {
            log.warn("Rule-change outbox scan failed; next scan will retry: {}", failure.getMessage());
        } finally {
            refreshBacklog();
        }
    }

    private void refreshBacklog() {
        if (performanceMetrics == null) return;
        try {
            performanceMetrics.outboxBacklog("rule_change",
                    repository.countByStatus("PENDING"),
                    repository.findOldestCreatedAtByStatus("PENDING"),
                    repository.countByStatus("DEAD"),
                    repository.findOldestUpdatedAtByStatus("DEAD"));
        } catch (RuntimeException failure) {
            log.warn("Rule-change outbox backlog metrics deferred: {}", failure.getMessage());
        }
    }

    private void deliver(RuleChangeOutbox row) {
        Instant now = Instant.now();
        if (repository.claim(row.getId(), now, effectiveMaxAttempts()) != 1) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", row.getId());
            event.put("tenantId", row.getTenantId());
            event.put("ruleId", row.getRuleId());
            event.put("action", row.getAction());
            event.put("timestamp", row.getCreatedAt());
            String value = MAPPER.writeValueAsString(event);
            KafkaClientSupport.sendAndAwait(kafkaProducer(), topic,
                    row.getTenantId() + ':' + row.getRuleId(), value, Duration.ofSeconds(10));
            if (repository.markPublished(row.getId(), Instant.now()) != 1) {
                log.warn("Rule-change outbox state changed before broker acknowledgement id={}", row.getId());
            }
        } catch (Exception failure) {
            scheduleRetry(row, failure);
        }
    }

    void scheduleRetry(RuleChangeOutbox row, Exception failure) {
        int attempt = row.getAttempts() + 1;
        String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        Instant now = Instant.now();
        var decision = OutboxRetryPolicy.afterClaim(attempt, effectiveMaxAttempts(), now, error, 300);
        try {
            if (decision.exhausted()) {
                if (repository.markDead(row.getId(), decision.error(), now) == 1) {
                    log.error("Rule-change outbox moved to DEAD tenant={} ruleId={} attempts={} reason={}",
                            row.getTenantId(), row.getRuleId(), decision.attempts(), decision.error());
                    lifecycle("dead", 1);
                }
                return;
            }
            if (repository.scheduleRetry(row.getId(), decision.nextAttemptAt(), decision.error(), now) == 1) {
                log.warn("Rule-change delivery scheduled for retry tenant={} ruleId={} attempt={} next={}: {}",
                        row.getTenantId(), row.getRuleId(), decision.attempts(), decision.nextAttemptAt(), decision.error());
                lifecycle("retry", 1);
            }
        } catch (RuntimeException stateFailure) {
            // Preserve the claim for stale recovery if the retry state write is unavailable.
            log.warn("Rule-change retry state update deferred id={}: {}", row.getId(), stateFailure.getMessage());
        }
    }

    private void recoverStaleIfDue(Instant now) {
        if (now.isBefore(nextRecoveryAt)) return;
        int recovered = repository.recoverStale(now.minus(Duration.ofMinutes(2)), now);
        if (recovered > 0) {
            log.warn("Recovered stale rule-change outbox rows count={}", recovered);
            lifecycle("recovered", recovered);
        }
        nextRecoveryAt = now.plusSeconds(30);
    }

    @Scheduled(fixedDelayString = "${socp.kafka.rule-outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.kafka.rule-outbox.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    void cleanupPublished() {
        try {
            long safeRetention = Math.max(Duration.ofMinutes(1).toMillis(), retentionMs);
            int removed = 0;
            Instant cutoff = Instant.now().minusMillis(safeRetention);
            int batchSize = effectiveCleanupBatchSize();
            for (int batch = 0; batch < effectiveCleanupMaxBatches(); batch++) {
                int deleted = repository.deletePublishedBatchBefore(cutoff, batchSize);
                removed += deleted;
                if (deleted < batchSize) break;
            }
            if (removed > 0) {
                log.info("Removed retained rule-change outbox rows count={} batchSize={} maxBatches={}",
                        removed, batchSize, effectiveCleanupMaxBatches());
                lifecycle("cleaned", removed);
            }
            int discarded = 0;
            Instant discardedCutoff = Instant.now().minus(DISCARDED_RETENTION);
            for (int batch = 0; batch < effectiveCleanupMaxBatches(); batch++) {
                int deleted = repository.deleteDiscardedBatchBefore(discardedCutoff, batchSize);
                discarded += deleted;
                if (deleted < batchSize) break;
            }
            if (discarded > 0) {
                log.info("Removed explicitly discarded rule-change outbox rows count={}", discarded);
                lifecycle("discarded_cleaned", discarded);
            }
        } catch (RuntimeException failure) {
            log.warn("Rule-change outbox retention cleanup deferred: {}", failure.getMessage());
        }
    }

    private int effectiveMaxAttempts() {
        return Math.max(1, maxAttempts);
    }

    private int effectiveMaxDrainRounds() {
        return Math.max(1, Math.min(1_000, maxDrainRounds));
    }

    private long effectiveDrainDurationMs() {
        return Math.max(10L, Math.min(60_000L, maxDrainDurationMs));
    }

    private int effectiveCleanupBatchSize() {
        return Math.max(1, Math.min(10_000, cleanupBatchSize));
    }

    private int effectiveCleanupMaxBatches() {
        return Math.max(1, Math.min(100, cleanupMaxBatches));
    }

    private void lifecycle(String outcome, int count) {
        if (performanceMetrics != null) performanceMetrics.outboxLifecycle("rule_change", outcome, count);
    }

    private KafkaProducer<String, String> kafkaProducer() {
        KafkaProducer<String, String> current = producer;
        if (current != null) return current;
        synchronized (this) {
            if (producer == null) {
                producer = new KafkaProducer<>(KafkaClientSupport.reliableProducer(bootstrap));
            }
            return producer;
        }
    }

    @PreDestroy
    void stop() {
        KafkaProducer<String, String> current = producer;
        if (current != null) current.close(Duration.ofSeconds(5));
    }
}
