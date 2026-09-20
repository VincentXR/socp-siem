package com.socp.detect.web.engine;

import com.socp.detect.web.persistence.entity.DetectionEventEntity;
import com.socp.detect.web.persistence.repository.DetectionEventRepository;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.DetectionEventJournal;
import com.socp.detect.web.persistence.store.DetectionEventStatus;
import com.socp.detect.web.persistence.store.PendingDetectionEvent;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.SecurityEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Real journal evidence for retry and bounded PENDING recovery. Execution or
 * dependency failures are not poison records: only malformed input is eligible
 * for DLQ hand-off.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectionDeadLetterJournalTest {

    @Autowired
    private DetectionEventRepository repository;

    private final DetectEngineService engine = mock(DetectEngineService.class);

    @Test
    @Timeout(30)
    void validDependencyFailureRemainsPendingThenCompletesAfterRecovery() {
        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(
                        CompletableFuture.failedFuture(
                                new CannotCreateTransactionException("sink database unavailable")),
                        CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, journal);
        configureFastRetries(consumer);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        ConsumerRecord<String, String> record =
                validRecord(3, 50L, "recover-h2-1", "198.51.100.7");

        try {
            consumer.processWithRetry(record, 1L);

            assertThat(dlq).isEmpty();
            DetectionEventEntity row = row("default", "recover-h2-1");
            assertThat(row.getStatus()).isEqualTo(DetectionEventStatus.COMPLETED.name());
            assertThat(row.getKafkaPartition()).isEqualTo(3);
            assertThat(row.getKafkaOffset()).isEqualTo(50L);
            assertThat(journal.pendingCount("default")).isZero();
            verify(engine, times(2)).ingestFromKafkaAndAwait(
                    any(SecurityEvent.class), anyString(), any(), any());
        } finally {
            consumer.stop();
            TenantContext.runWith("default", () -> journal.remove("default", "recover-h2-1"));
        }
    }

    @Test
    @Timeout(30)
    @SuppressWarnings("unchecked")
    void pendingBeyondPrefetchLimitStaysBoundedAndEventuallyCompletes() throws Exception {
        int prefetchLimit = 2;
        DetectionEventJournal journal = new DetectionEventJournal(
                repository, "24h", 100, "7d", "90d", 1_000, 10, prefetchLimit, "socp-events");
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, journal);
        configureFastRetries(consumer);
        ReflectionTestUtils.setField(consumer, "replayWindow", Duration.ofHours(24));

        DetectionRecordProcessor parser = new DetectionRecordProcessor(engine, journal, null);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                ConsumerRecord<String, String> record =
                        validRecord(3, 100L + i, "pending-h2-" + i, "198.51.100." + (10 + i));
                records.add(record);
                DetectionRecordProcessor.NormalizedDetectionRecord normalized =
                        parser.parse(3, 100L + i, record.key(), record.value());
                TenantContext.runWith("default", () -> assertThat(journal.claim(
                        normalized.event(), 3, record.offset(), normalized.routingKey()))
                        .isEqualTo(DetectionEventClaim.NEW));
            }

            List<PendingDetectionEvent> firstPage;
            try (TenantContext.Scope ignored = TenantContext.openSystem()) {
                firstPage = journal.pendingRecordsForPartitions(Set.of(3), Duration.ofHours(24));
            }
            assertThat(firstPage).hasSize(prefetchLimit);

            Method replayPending = KafkaEventConsumer.class.getDeclaredMethod("replayPending", Set.class);
            replayPending.setAccessible(true);
            try (TenantContext.Scope ignored = TenantContext.openSystem()) {
                replayPending.invoke(consumer, Set.of(3));
            }
            awaitPendingCount(journal, 3L);

            // Only the bounded prefetch was materialized; the remainder is recovered
            // by the same normal Kafka record path once polling reaches those offsets.
            for (int i = prefetchLimit; i < records.size(); i++) {
                consumer.processWithRetry(records.get(i), 20L + i);
            }
            awaitPendingCount(journal, 0L);

            Map<?, AtomicLong> pendingBytes =
                    (Map<?, AtomicLong>) ReflectionTestUtils.getField(consumer, "pendingBytes");
            assertThat(pendingBytes.values()).allSatisfy(bytes -> assertThat(bytes.get()).isZero());
            assertThat(journal.pendingCount("default")).isZero();
        } finally {
            consumer.stop();
            TenantContext.runWith("default", () -> {
                for (int i = 0; i < 5; i++) {
                    journal.remove("default", "pending-h2-" + i);
                }
            });
        }
    }

    private static void configureFastRetries(KafkaEventConsumer consumer) {
        ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 1);
        ReflectionTestUtils.setField(consumer, "processingRetryInitialDelayMs", 1L);
        ReflectionTestUtils.setField(consumer, "dlqHandoffRetryDelayMs", 1L);
    }

    private static void awaitPendingCount(DetectionEventJournal journal, long expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (journal.pendingCount("default") == expected) return;
            Thread.sleep(10L);
        }
        assertThat(journal.pendingCount("default")).isEqualTo(expected);
    }

    private static ConsumerRecord<String, String> validRecord(int partition, long offset,
                                                               String eventId, String ip) {
        return new ConsumerRecord<>("socp-events", partition, offset,
                "default|src_ip|" + ip,
                "{\"eventId\":\"" + eventId + "\",\"tenantId\":\"default\",\"source\":\"auth\","
                        + "\"host\":\"web-1\",\"msg\":\"login failed\","
                        + "\"fields\":{\"src_ip\":\"" + ip + "\"}}");
    }

    private DetectionEventEntity row(String tenant, String eventId) {
        return repository.findByTenantIdAndSourceEventId(tenant, eventId).orElseThrow(
                () -> new AssertionError("no detection journal row for " + tenant + "/" + eventId));
    }
}
