package com.socp.search.config.search;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reliably drains canonical-event publication intents to Kafka. */
@Component
public class IngestionOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestionOutboxPublisher.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 12;
    private static final long DEFAULT_RETENTION_MS = Duration.ofDays(30).toMillis();
    private static final int MAX_ERROR_LENGTH = 1024;

    private final IngestionOutboxRepository repository;
    private final KafkaEventProducer producer;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor;
    private final int maxAttempts;
    private final long retentionMs;
    private Instant nextRecoveryAt = Instant.EPOCH;

    @Autowired
    public IngestionOutboxPublisher(IngestionOutboxRepository repository,
                                    KafkaEventProducer producer,
                                    MeterRegistry meterRegistry,
                                    @Value("${socp.ingest.outbox.delivery-concurrency:8}") int concurrency,
                                    @Value("${socp.ingest.outbox.max-attempts:12}") int maxAttempts,
                                    @Value("${socp.ingest.outbox.retention-ms:2592000000}") long retentionMs) {
        this.repository = repository;
        this.producer = producer;
        this.meterRegistry = meterRegistry;
        int bounded = Math.max(1, Math.min(32, concurrency));
        this.executor = Executors.newFixedThreadPool(bounded,
                Thread.ofVirtual().name("ingestion-outbox-", 0).factory());
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retentionMs = Math.max(Duration.ofMinutes(1).toMillis(), retentionMs);
    }

    IngestionOutboxPublisher(IngestionOutboxRepository repository, KafkaEventProducer producer) {
        this(repository, producer, null, 1, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETENTION_MS);
    }

    @Scheduled(fixedDelayString = "${socp.ingest.outbox.poll-interval-ms:500}",
            initialDelayString = "${socp.ingest.outbox.initial-delay-ms:1000}")
    public void publish() {
        if (!producer.isEnabled()) return;
        try {
            Instant now = Instant.now();
            int recovered = recoverStaleIfDue(now);
            if (recovered > 0) lifecycle("recovered", recovered);
            int exhausted = repository.markExhausted(maxAttempts, "retry limit reached", now);
            if (exhausted > 0) {
                log.error("Ingestion outbox rows moved to DEAD after retry limit count={}", exhausted);
                lifecycle("dead", exhausted);
            }
            List<IngestionOutboxEvent> pending =
                    repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", now);
            List<CompletableFuture<Void>> deliveries = pending.stream()
                    .map(event -> CompletableFuture.runAsync(() -> deliver(event), executor))
                    .toList();
            CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).join();
        } catch (Exception failure) {
            log.warn("Ingestion outbox scan failed; next scan will retry: {}", failure.getMessage());
        }
    }

    private int recoverStaleIfDue(Instant now) {
        if (now.isBefore(nextRecoveryAt)) return 0;
        int recovered = repository.recoverStale(now.minus(Duration.ofMinutes(2)), now);
        nextRecoveryAt = now.plus(Duration.ofSeconds(30));
        return recovered;
    }

    private void deliver(IngestionOutboxEvent event) {
        boolean claimed = false;
        try {
            if (repository.claim(event.getId(), Instant.now(), maxAttempts) != 1) return;
            claimed = true;
            if (!producer.sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent())) {
                scheduleRetry(event, "Kafka broker did not acknowledge the event");
                return;
            }
            if (repository.markPublished(event.getId(), Instant.now()) != 1) {
                log.warn("Ingestion outbox state changed after broker acknowledgement id={}", event.getId());
            }
        } catch (Exception failure) {
            if (claimed) {
                scheduleRetry(event, failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            log.warn("Ingestion outbox delivery failed id={}: {}", event.getId(), failure.getMessage());
        }
    }

    private void scheduleRetry(IngestionOutboxEvent event, String error) {
        int attempts = event.getAttempts() + 1;
        Instant now = Instant.now();
        String safeError = truncate(error);
        try {
            if (attempts >= maxAttempts) {
                if (repository.markDead(event.getId(), safeError, now) == 1) {
                    log.error("Ingestion outbox moved to DEAD id={} eventId={} attempts={} reason={}",
                            event.getId(), event.getEventId(), attempts, safeError);
                    lifecycle("dead", 1);
                }
                return;
            }
            long delaySeconds = Math.min(900, 1L << Math.min(10, Math.max(1, attempts)));
            Instant nextAttempt = now.plusSeconds(delaySeconds);
            if (repository.scheduleRetry(event.getId(), nextAttempt, safeError, now) == 1) {
                log.warn("Ingestion outbox retry scheduled id={} eventId={} attempts={} next={} reason={}",
                        event.getId(), event.getEventId(), attempts, nextAttempt, safeError);
                lifecycle("retry", 1);
            }
        } catch (RuntimeException stateFailure) {
            // Leave PROCESSING for stale-claim recovery when the database state update fails.
            log.warn("Ingestion outbox retry state update deferred id={}: {}",
                    event.getId(), stateFailure.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${socp.ingest.outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.ingest.outbox.cleanup-initial-delay-ms:60000}")
    void cleanupPublished() {
        try {
            int removed = repository.deletePublishedBefore(Instant.now().minusMillis(retentionMs));
            if (removed > 0) {
                log.info("Removed retained ingestion outbox rows count={}", removed);
                lifecycle("cleaned", removed);
            }
        } catch (RuntimeException failure) {
            log.warn("Ingestion outbox retention cleanup deferred: {}", failure.getMessage());
        }
    }

    private void lifecycle(String outcome, int count) {
        if (meterRegistry != null && count > 0) {
            meterRegistry.counter("socp.ingestion.outbox.lifecycle", "outcome", outcome).increment(count);
        }
    }

    private static String truncate(String error) {
        String value = error == null || error.isBlank() ? "unknown delivery failure" : error;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
