package com.socp.search.config.infrastructure.kafka;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.CorrelationRule;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the durable key fence and the ordered detection primitive together.
 * A later same-key record must not reach correlation while an earlier delivery is
 * delayed or backing off, including when another publisher replica scans the table.
 */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IngestionOutboxOrderingPersistenceTest {

    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired IngestionOutboxRepository repository;
    @Autowired JdbcTemplate jdbc;

    private IngestionOutboxPublisher firstPublisher;
    private IngestionOutboxPublisher secondPublisher;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from t_ingestion_outbox");
    }

    @AfterEach
    void cleanUp() {
        if (firstPublisher != null) firstPublisher.stop();
        if (secondPublisher != null) secondPublisher.stop();
        jdbc.update("delete from t_ingestion_outbox");
    }

    @Test
    void retryAndCompetingReplicaPreserveTheSequenceSeenByCorrelation() throws Exception {
        String routingKey = "tenant-a|host|shared";
        String firstId = insert("event-failed", routingKey, "failed");
        String secondId = insert("event-accepted", routingKey, "accepted");

        CorrelationRule correlation = new CorrelationRule(
                "ordered-login", "Ordered login", SecurityEvent::host,
                List.of(event -> "failed".equals(event.get("phase")),
                        event -> "accepted".equals(event.get("phase"))),
                Duration.ofMinutes(5), Severity.HIGH, "ordered {key}");
        CopyOnWriteArrayList<String> delivered = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch retrySendEntered = new CountDownLatch(1);
        CountDownLatch releaseRetrySend = new CountDownLatch(1);

        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        when(producer.sendAndAwait(any(), any(), any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            String phase = invocation.getArgument(1, String.class);
            if (call == 1) return false;
            if (call == 2) {
                retrySendEntered.countDown();
                assertTrue(releaseRetrySend.await(5, TimeUnit.SECONDS), "retry send release timed out");
            }
            delivered.add(phase);
            correlation.accept(new SecurityEvent("delivery-" + call, BASE.plusSeconds(call),
                    "auth", "shared-host", phase,
                    Map.of("phase", phase, "tenant_id", "tenant-a"), Severity.INFO));
            return true;
        });

        firstPublisher = publisher(producer);
        secondPublisher = publisher(producer);

        // The first attempt fails and is moved into backoff. The second row is
        // due, but the durable predecessor fence keeps it from being published.
        firstPublisher.publish();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(status(firstId)).isEqualTo("PENDING");
        assertThat(status(secondId)).isEqualTo("PENDING");
        secondPublisher.publish();
        assertThat(calls.get()).isEqualTo(1);

        // Make the retry due and hold its broker acknowledgement. A competing
        // replica must still see neither the claimed predecessor nor its successor.
        jdbc.update("update t_ingestion_outbox set next_attempt_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), firstId);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var retry = workers.submit(firstPublisher::publish);
            assertTrue(retrySendEntered.await(5, TimeUnit.SECONDS), "retry send did not start");
            secondPublisher.publish();
            assertThat(calls.get()).isEqualTo(2);
            assertThat(status(secondId)).isEqualTo("PENDING");
            releaseRetrySend.countDown();
            retry.get(5, TimeUnit.SECONDS);
        } finally {
            releaseRetrySend.countDown();
        }

        secondPublisher.publish();

        assertThat(delivered).containsExactly("failed", "accepted");
        assertThat(status(firstId)).isEqualTo("PUBLISHED");
        assertThat(status(secondId)).isEqualTo("PUBLISHED");
        assertThat(correlation.drain()).singleElement().satisfies(alert ->
                assertThat(alert.evidence().stream().map(event -> event.get("phase")))
                        .containsExactly("failed", "accepted"));
    }

    @Test
    void onePollDrainsActualDatabaseHeadsInIngestionOrder() {
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 6; i++) ids.add(insert("hot-" + i, "tenant-a|host|hot", "payload-" + i));
        var delivered = new CopyOnWriteArrayList<String>();
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        when(producer.isEnabled()).thenReturn(true);
        when(producer.sendAndAwait(any(), any(), any())).thenAnswer(invocation -> {
            delivered.add(invocation.getArgument(1));
            return true;
        });
        firstPublisher = publisher(producer);

        firstPublisher.publish();

        assertThat(delivered).containsExactly("payload-0", "payload-1", "payload-2", "payload-3", "payload-4", "payload-5");
        for (String id : ids) assertThat(status(id)).isEqualTo("PUBLISHED");
    }

    private IngestionOutboxPublisher publisher(KafkaEventProducer producer) {
        return new IngestionOutboxPublisher(repository, producer, null,
                4, 12, Duration.ofDays(30).toMillis(), 100, 2, 8, 5_000);
    }

    private String insert(String eventId, String routingKey, String payload) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into t_ingestion_outbox "
                        + "(id, tenant_id, event_id, routing_key, payload, status, attempts, "
                        + "next_attempt_at, created_at, updated_at) "
                        + "values (?, 'tenant-a', ?, ?, ?, 'PENDING', 0, ?, ?, ?)",
                id, eventId, routingKey, payload,
                Timestamp.from(BASE), Timestamp.from(BASE), Timestamp.from(BASE));
        return id;
    }

    private String status(String id) {
        return jdbc.queryForObject("select status from t_ingestion_outbox where id = ?", String.class, id);
    }
}
