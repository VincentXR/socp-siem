package com.socp.search.config.search;

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

/** Reliably drains canonical-event publication intents to Kafka. */
@Component
public class IngestionOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestionOutboxPublisher.class);

    private final IngestionOutboxRepository repository;
    private final KafkaEventProducer producer;
    private final ExecutorService executor;

    @Autowired
    public IngestionOutboxPublisher(IngestionOutboxRepository repository,
                                    KafkaEventProducer producer,
                                    @Value("${socp.ingest.outbox.delivery-concurrency:8}") int concurrency) {
        this.repository = repository;
        this.producer = producer;
        int bounded = Math.max(1, Math.min(32, concurrency));
        this.executor = Executors.newFixedThreadPool(bounded,
                Thread.ofVirtual().name("ingestion-outbox-", 0).factory());
    }

    IngestionOutboxPublisher(IngestionOutboxRepository repository, KafkaEventProducer producer) {
        this(repository, producer, 1);
    }

    @Scheduled(fixedDelayString = "${socp.ingest.outbox.poll-interval-ms:500}",
            initialDelayString = "${socp.ingest.outbox.initial-delay-ms:1000}")
    public void publish() {
        try {
            Instant now = Instant.now();
            repository.recoverStale(now.minus(Duration.ofMinutes(2)), now);
            List<IngestionOutboxEvent> pending =
                    repository.findTop200ByStatusOrderByCreatedAtAsc("PENDING");
            List<CompletableFuture<Void>> deliveries = pending.stream()
                    .map(event -> CompletableFuture.runAsync(() -> deliver(event), executor))
                    .toList();
            CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).join();
        } catch (Exception failure) {
            log.warn("Ingestion outbox scan failed; next scan will retry: {}", failure.getMessage());
        }
    }

    private void deliver(IngestionOutboxEvent event) {
        boolean claimed = false;
        try {
            if (repository.claim(event.getId(), Instant.now()) != 1) return;
            claimed = true;
            if (!producer.sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent())) {
                repository.release(event.getId(), Instant.now());
                return;
            }
            if (repository.markPublished(event.getId(), Instant.now()) != 1) {
                log.warn("Ingestion outbox state changed after broker acknowledgement id={}", event.getId());
            }
        } catch (Exception failure) {
            if (claimed) {
                try {
                    repository.release(event.getId(), Instant.now());
                } catch (Exception releaseFailure) {
                    log.warn("Ingestion outbox claim release deferred id={}: {}",
                            event.getId(), releaseFailure.getMessage());
                }
            }
            log.warn("Ingestion outbox delivery failed id={}: {}", event.getId(), failure.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
