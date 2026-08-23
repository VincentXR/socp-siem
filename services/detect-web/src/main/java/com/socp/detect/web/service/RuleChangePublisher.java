package com.socp.detect.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.platform.tenant.TenantContext;
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

    private final RuleChangeOutboxRepository repository;

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.rule-topic:socp-rule-changes}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private volatile KafkaProducer<String, String> producer;
    private Instant nextRecoveryAt = Instant.EPOCH;

    public RuleChangePublisher(RuleChangeOutboxRepository repository) {
        this.repository = repository;
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
    public void flush() {
        if (!enabled) return;
        try {
            Instant now = Instant.now();
            recoverStaleIfDue(now);
            List<RuleChangeOutbox> pending = repository
                    .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", now);
            for (RuleChangeOutbox row : pending) deliver(row);
        } catch (RuntimeException failure) {
            log.warn("Rule-change outbox scan failed; next scan will retry: {}", failure.getMessage());
        }
    }

    private void deliver(RuleChangeOutbox row) {
        Instant now = Instant.now();
        if (repository.claim(row.getId(), now) != 1) return;
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
            repository.markPublished(row.getId(), Instant.now());
        } catch (Exception failure) {
            scheduleRetry(row, failure);
        }
    }

    private void scheduleRetry(RuleChangeOutbox row, Exception failure) {
        int attempt = row.getAttempts() + 1;
        long delay = Math.min(300, 1L << Math.min(8, Math.max(1, attempt)));
        String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (error.length() > 1024) error = error.substring(0, 1024);
        Instant now = Instant.now();
        repository.scheduleRetry(row.getId(), now.plusSeconds(delay), error, now);
        log.warn("Rule-change delivery scheduled for retry tenant={} ruleId={} attempt={}: {}",
                row.getTenantId(), row.getRuleId(), attempt, error);
    }

    private void recoverStaleIfDue(Instant now) {
        if (now.isBefore(nextRecoveryAt)) return;
        int recovered = repository.recoverStale(now.minus(Duration.ofMinutes(2)), now);
        if (recovered > 0) log.warn("Recovered stale rule-change outbox rows count={}", recovered);
        nextRecoveryAt = now.plusSeconds(30);
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
