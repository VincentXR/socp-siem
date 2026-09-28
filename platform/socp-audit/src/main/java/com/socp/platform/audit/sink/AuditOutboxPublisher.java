package com.socp.platform.audit.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Claims durable audit rows and publishes them with replay-safe event IDs as Kafka keys. */
public final class AuditOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration claimTimeout;

    public AuditOutboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                KafkaTemplate<String, String> kafka, String topic,
                                int batchSize, int maxAttempts, Duration claimTimeout) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
        this.maxAttempts = Math.max(0, maxAttempts);
        this.claimTimeout = claimTimeout == null || claimTimeout.isNegative() || claimTimeout.isZero()
                ? Duration.ofMinutes(5) : claimTimeout;
    }

    public void publishPending() {
        for (Claim claim : claimBatch()) {
            try {
                kafka.send(topic, claim.eventId(), claim.payload()).get(5, TimeUnit.SECONDS);
                int updated = jdbc.update("""
                        UPDATE t_audit_outbox
                           SET status='PUBLISHED', published_at=?, claim_token=NULL,
                               claimed_at=NULL, last_error=NULL
                         WHERE event_id=? AND status='PROCESSING' AND claim_token=?
                        """, Timestamp.from(Instant.now()), claim.eventId(), claim.token());
                if (updated != 1) {
                    log.warn("Audit outbox acknowledgement lost claim eventId={}", claim.eventId());
                }
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                release(claim, failure);
            }
        }
    }

    List<Claim> claimBatch() {
        List<Claim> claimed = transactions.execute(status -> {
            Instant now = Instant.now();
            List<Row> rows = jdbc.query("""
                    SELECT event_id, payload, attempt_count
                      FROM t_audit_outbox
                     WHERE (status='PENDING' AND next_attempt_at <= ?)
                        OR (status='PROCESSING' AND claimed_at < ?)
                     ORDER BY created_at
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                    """, (rs, rowNum) -> new Row(
                            rs.getString("event_id"), rs.getString("payload"),
                            rs.getInt("attempt_count")),
                    Timestamp.from(now), Timestamp.from(now.minus(claimTimeout)), batchSize);
            List<Claim> result = new ArrayList<>(rows.size());
            for (Row row : rows) {
                String token = UUID.randomUUID().toString();
                int updated = jdbc.update("""
                        UPDATE t_audit_outbox
                           SET status='PROCESSING', claim_token=?, claimed_at=?
                         WHERE event_id=?
                        """, token, Timestamp.from(now), row.eventId());
                if (updated == 1) result.add(new Claim(row.eventId(), row.payload(), row.attempts(), token));
            }
            return result;
        });
        return claimed == null ? List.of() : List.copyOf(claimed);
    }

    private void release(Claim claim, Exception failure) {
        int attempts = claim.attempts() + 1;
        boolean dead = maxAttempts > 0 && attempts >= maxAttempts;
        Instant next = Instant.now().plusSeconds(Math.min(300L, 1L << Math.min(attempts, 8)));
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > 1000) message = message.substring(0, 1000);
        jdbc.update("""
                UPDATE t_audit_outbox
                   SET status=?, attempt_count=?, next_attempt_at=?, claim_token=NULL,
                       claimed_at=NULL, last_error=?
                 WHERE event_id=? AND status='PROCESSING' AND claim_token=?
                """, dead ? "DEAD" : "PENDING", attempts, Timestamp.from(next), message,
                claim.eventId(), claim.token());
        log.warn("Audit outbox delivery failed eventId={} attempt={} terminal={}: {}",
                claim.eventId(), attempts, dead, message);
    }

    record Claim(String eventId, String payload, int attempts, String token) {
    }

    private record Row(String eventId, String payload, int attempts) {
    }
}
