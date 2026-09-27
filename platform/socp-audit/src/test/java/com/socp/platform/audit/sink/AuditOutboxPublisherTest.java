package com.socp.platform.audit.sink;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditOutboxPublisherTest {

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:audit-publisher-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        kafka = mock(KafkaTemplate.class);
        jdbc.execute("""
                CREATE TABLE t_audit_outbox (
                  event_id VARCHAR(64) PRIMARY KEY, payload TEXT NOT NULL,
                  status VARCHAR(16) NOT NULL, attempt_count INTEGER NOT NULL,
                  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  claimed_at TIMESTAMP WITH TIME ZONE, claim_token VARCHAR(64),
                  published_at TIMESTAMP WITH TIME ZONE, last_error VARCHAR(1024),
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL)
                """);
    }

    @Test
    void publishesClaimedRowsAndAcknowledgesOnlyItsClaim() {
        insert("event-1", "PENDING", 0, null, null);
        when(kafka.send(eq("audit-topic"), eq("event-1"), eq("{\"event\":1}")))
                .thenReturn(CompletableFuture.completedFuture(sendResult()));
        AuditOutboxPublisher publisher = publisher(0, Duration.ofMinutes(5));

        publisher.publishPending();

        verify(kafka).send("audit-topic", "event-1", "{\"event\":1}");
        assertThat(value("status")).isEqualTo("PUBLISHED");
        assertThat(value("claim_token")).isNull();
        assertThat(value("published_at")).isNotNull();
        assertThat(publisher.claimBatch()).isEmpty();
    }

    @Test
    void releasesTransientFailureForRetryWithBoundedErrorText() {
        insert("event-retry", "PENDING", 0, null, null);
        String message = "x".repeat(1_100);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException(message));
        when(kafka.send("audit-topic", "event-retry", "{\"event\":1}"))
                .thenReturn(failed);

        publisher(0, Duration.ofMinutes(5)).publishPending();

        assertThat(value("status")).isEqualTo("PENDING");
        assertThat(number("attempt_count")).isEqualTo(1);
        assertThat(String.valueOf(value("last_error"))).hasSize(1_000);
        assertThat(value("claim_token")).isNull();
    }

    @Test
    void recoversAStaleClaimAndMovesItToDeadAtTheAttemptLimit() {
        insert("event-stale", "PROCESSING", 2,
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(30))), "abandoned");
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalArgumentException());
        when(kafka.send("audit-topic", "event-stale", "{\"event\":1}"))
                .thenReturn(failed);

        publisher(3, null).publishPending();

        assertThat(value("status")).isEqualTo("DEAD");
        assertThat(number("attempt_count")).isEqualTo(3);
        assertThat(value("last_error")).isEqualTo("java.lang.IllegalArgumentException");
    }

    private AuditOutboxPublisher publisher(int maxAttempts, Duration timeout) {
        return new AuditOutboxPublisher(jdbc, transactionManager, kafka, "audit-topic",
                5_000, maxAttempts, timeout);
    }

    private void insert(String id, String status, int attempts, Timestamp claimedAt, String claimToken) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO t_audit_outbox
                  (event_id, payload, status, attempt_count, next_attempt_at,
                   claimed_at, claim_token, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, "{\"event\":1}", status, attempts, Timestamp.from(now.minusSeconds(1)),
                claimedAt, claimToken, Timestamp.from(now.minusSeconds(2)));
    }

    private Object value(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM t_audit_outbox", Object.class);
    }

    private int number(String column) {
        Integer value = jdbc.queryForObject("SELECT " + column + " FROM t_audit_outbox", Integer.class);
        return value == null ? -1 : value;
    }

    private static SendResult<String, String> sendResult() {
        return new SendResult<>(new ProducerRecord<>("audit-topic", "event-1", "{}"), null);
    }
}
