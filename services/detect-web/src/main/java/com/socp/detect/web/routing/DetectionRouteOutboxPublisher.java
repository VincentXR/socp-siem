package com.socp.detect.web.routing;

import com.socp.detect.web.config.DetectRuntimeRole;
import com.socp.detect.web.persistence.entity.DetectionRouteOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionRouteOutboxRepository;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Durable publisher for route-outbox rows. Duplicate sends are safe by deliveryId. */
@Component
@DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
public class DetectionRouteOutboxPublisher {

    private final DetectionRouteOutboxRepository repository;
    private final String bootstrap;
    private final boolean enabled;
    private final int maxAttempts;
    private final Duration publishedRetention;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatches;
    private volatile KafkaProducer<String, String> producer;

    public DetectionRouteOutboxPublisher(
            DetectionRouteOutboxRepository repository,
            @Value("${socp.kafka.bootstrap:localhost:9092}") String bootstrap,
            @Value("${socp.detect.routing.publisher-enabled:false}") boolean enabled,
            @Value("${socp.detect.routing.outbox-max-attempts:0}") int maxAttempts,
            @Value("${socp.detect.routing.published-retention:7d}") String publishedRetention,
            @Value("${socp.detect.routing.cleanup-batch-size:1000}") int cleanupBatchSize,
            @Value("${socp.detect.routing.cleanup-max-batches:10}") int cleanupMaxBatches) {
        this.repository = repository;
        this.bootstrap = bootstrap;
        this.enabled = enabled;
        // 0 means retry forever. Route publication must not become a silent loss.
        this.maxAttempts = Math.max(0, maxAttempts);
        this.publishedRetention = parseDuration(publishedRetention, Duration.ofDays(7));
        this.cleanupBatchSize = Math.max(1, Math.min(10_000, cleanupBatchSize));
        this.cleanupMaxBatches = Math.max(1, Math.min(100, cleanupMaxBatches));
    }

    @Scheduled(
            fixedDelayString = "${socp.detect.routing.outbox-poll-ms:250}",
            initialDelayString = "${socp.detect.routing.outbox-initial-delay-ms:250}")
    @TenantSystemJob
    public void publishDue() {
        if (!enabled) return;
        recoverStale();
        Instant now = Instant.now();
        for (DetectionRouteOutboxEntity row :
                repository.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        "PENDING", now)) {
            int attemptLimit = maxAttempts == 0 ? Integer.MAX_VALUE : maxAttempts;
            if (repository.claim(row.getDeliveryId(), now, attemptLimit) != 1) continue;
            row.setAttempts(row.getAttempts() + 1);
            row.setStatus("PROCESSING");
            row.setUpdatedAt(now);
            publish(row);
        }
    }

    private void publish(DetectionRouteOutboxEntity row) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    row.getDeliveryTopic(), row.getRoutingKey(), row.getPayload());
            record.headers().add("socp-delivery-id",
                    row.getDeliveryId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var metadata = producer().send(record).get(10, TimeUnit.SECONDS);
            markPublished(row, metadata.partition(), metadata.offset());
        } catch (Exception failure) {
            markFailed(row, failure);
        }
    }

    @Transactional
    void markPublished(DetectionRouteOutboxEntity row, int partition, long offset) {
        row.setDeliveryPartition(partition);
        row.setDeliveryOffset(offset);
        row.setStatus("PUBLISHED");
        row.setPublishedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setLastError(null);
        repository.saveAndFlush(row);
    }

    @Transactional
    void markFailed(DetectionRouteOutboxEntity row, Exception failure) {
        Instant now = Instant.now();
        boolean exhausted = maxAttempts > 0 && row.getAttempts() >= maxAttempts;
        row.setStatus(exhausted ? "DEAD" : "PENDING");
        long backoff = Math.min(60_000L, 250L << Math.min(8, Math.max(0, row.getAttempts() - 1)));
        row.setNextAttemptAt(exhausted ? now : now.plusMillis(backoff));
        row.setUpdatedAt(now);
        String detail = failure == null ? "unknown route publish failure"
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        row.setLastError(detail.length() <= 1024 ? detail : detail.substring(0, 1024));
        repository.saveAndFlush(row);
    }

    @Transactional
    void recoverStale() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(2));
        for (DetectionRouteOutboxEntity row :
                repository.findByStatusAndUpdatedAtBefore("PROCESSING", cutoff)) {
            row.setStatus("PENDING");
            row.setNextAttemptAt(Instant.now());
            row.setUpdatedAt(Instant.now());
            repository.save(row);
        }
    }

    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> current = producer;
        if (current != null) return current;
        synchronized (this) {
            if (producer == null) {
                Properties props = new Properties();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.ACKS_CONFIG, "all");
                props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
                props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
                props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
                props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
                producer = new KafkaProducer<>(props);
            }
            return producer;
        }
    }

    @Scheduled(
            fixedDelayString = "${socp.detect.routing.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.detect.routing.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    void cleanupPublished() {
        if (!enabled) return;
        Instant cutoff = Instant.now().minus(publishedRetention);
        for (int batch = 0; batch < cleanupMaxBatches; batch++) {
            int deleted = repository.deletePublishedBatchBefore(cutoff, cleanupBatchSize);
            if (deleted < cleanupBatchSize) break;
        }
    }

    private static Duration parseDuration(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            Duration parsed;
            if (value.startsWith("p")) parsed = Duration.parse(raw.trim().toUpperCase(java.util.Locale.ROOT));
            else if (value.endsWith("d")) parsed = Duration.ofDays(Long.parseLong(value.substring(0, value.length() - 1)));
            else if (value.endsWith("h")) parsed = Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
            else if (value.endsWith("m")) parsed = Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            else if (value.endsWith("s")) parsed = Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            else parsed = Duration.ofSeconds(Long.parseLong(value));
            return parsed.isNegative() || parsed.isZero() ? fallback : parsed;
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    @PreDestroy
    void close() {
        KafkaProducer<String, String> current = producer;
        if (current != null) current.close(Duration.ofSeconds(5));
    }
}
