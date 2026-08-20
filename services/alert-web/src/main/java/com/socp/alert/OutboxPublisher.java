package com.socp.alert;

import jakarta.annotation.PreDestroy;
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

/**
 * Publishes the durable Alert outbox to the fan-out Kafka topic.
 *
 * <p>A bounded query prevents an accumulated backlog from being materialized
 * in one scan. Optimistic claims make multiple alert-web instances safe, and
 * the bounded delivery executor lets the Kafka producer batch requests without
 * turning the scheduler into unbounded concurrency. A crash can still repeat
 * a broker-acknowledged event before the database state update; downstream
 * consumers therefore retain at-least-once idempotency.</p>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepo;
    private final AlertKafkaPublisher kafkaPublisher;
    private final ExecutorService deliveryExecutor;

    @Autowired
    public OutboxPublisher(OutboxRepository outboxRepo, AlertKafkaPublisher kafkaPublisher,
                           @Value("${socp.alert.outbox.delivery-concurrency:4}") int concurrency) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        int bounded = Math.max(1, Math.min(32, concurrency));
        this.deliveryExecutor = Executors.newFixedThreadPool(bounded,
                Thread.ofVirtual().name("alert-outbox-delivery-", 0).factory());
    }

    /** Unit-test/source compatibility constructor. */
    OutboxPublisher(OutboxRepository outboxRepo, AlertKafkaPublisher kafkaPublisher) {
        this(outboxRepo, kafkaPublisher, 1);
    }

    @Scheduled(fixedDelayString = "${socp.alert.outbox.poll-interval-ms:1000}",
            initialDelayString = "${socp.alert.outbox.initial-delay-ms:1000}")
    public void publish() {
        try {
            Instant now = Instant.now();
            int recovered = outboxRepo.recoverStale(
                    now.minus(Duration.ofMinutes(2)), now);
            if (recovered > 0) {
                log.warn("Recovered stale Alert outbox claims count={}", recovered);
            }
            List<OutboxEvent> pending = outboxRepo
                    .findTop100ByStatusOrderByCreatedAtAsc("PENDING");
            if (pending.isEmpty()) return;
            if (!kafkaPublisher.isAvailable()) {
                log.warn("Kafka unavailable; Alert outbox remains pending count={}", pending.size());
                return;
            }
            List<CompletableFuture<Void>> deliveries = pending.stream()
                    .map(event -> CompletableFuture.runAsync(() -> deliver(event), deliveryExecutor))
                    .toList();
            CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).join();
        } catch (Exception failure) {
            log.warn("Alert outbox scan failed; next scan will retry: {}", failure.getMessage());
        }
    }

    private void deliver(OutboxEvent event) {
        boolean claimed = false;
        try {
            if (outboxRepo.claim(event.getId(), Instant.now()) != 1) return;
            claimed = true;
            if (!kafkaPublisher.sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload())) {
                outboxRepo.release(event.getId(), Instant.now());
                return;
            }
            if (outboxRepo.markPublished(event.getId(), Instant.now()) != 1) {
                log.warn("Alert outbox publish state changed unexpectedly id={}", event.getId());
            }
        } catch (Exception failure) {
            if (claimed) {
                try {
                    outboxRepo.release(event.getId(), Instant.now());
                } catch (Exception releaseFailure) {
                    log.warn("Alert outbox claim release deferred id={}: {}",
                            event.getId(), releaseFailure.getMessage());
                }
            }
            log.warn("Alert outbox delivery failed id={}: {}", event.getId(), failure.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        deliveryExecutor.shutdownNow();
    }
}
