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
import com.socp.rule.model.Alert;
import com.socp.rule.model.Severity;
import com.socp.rule.engine.AlertSink;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.Watchlists;
import com.socp.rule.engine.WatchlistStateStore;
import com.socp.rule.rules.PatternRule;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void watchlistOutageBeyondRuleFuseThresholdLeavesJournalPendingUntilRealEvaluationRecovers() {
        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100);
        WatchlistStateStore watchlistStore = mock(WatchlistStateStore.class);
        given(watchlistStore.find("default", "accounts"))
                .willThrow(new CannotCreateTransactionException("watchlist database unavailable"));
        Watchlists.installStateStore(watchlistStore);
        List<Alert> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
        AlertSink sink = new AlertSink() {
            @Override public void publish(Alert alert) { delivered.add(alert); }
            @Override public void close() { }
        };
        PatternRule rule = new PatternRule("WATCHLIST", "watchlist",
                event -> Watchlists.contains(event.tenantId(), "accounts", "alice"),
                Severity.HIGH, "membership", "membership");
        ConsumerRecord<String, String> record = validRecord(3, 60L, "watchlist-retry-h2", "198.51.100.9");
        try (RuleEngine evaluator = new RuleEngine(List.of(rule), List.of(sink))) {
            evaluator.start();
            given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                    .willAnswer(invocation -> evaluator.ingestAndAwait(invocation.getArgument(0)));
            DetectionRecordProcessor processor = new DetectionRecordProcessor(engine, journal, null);
            for (int attempt = 0; attempt < 10; attempt++) {
                var failure = assertThrows(DetectionRecordProcessor.RetryableDetectionFailure.class,
                        () -> processor.process(record.topic(), record.partition(), record.offset(),
                                record.key(), record.value()));
                assertThat(failure.category()).isEqualTo(DetectionRecordProcessor.FailureCategory.DEPENDENCY);
                DetectionEventEntity pending = row("default", "watchlist-retry-h2");
                assertThat(pending.getStatus()).isEqualTo(DetectionEventStatus.PENDING.name());
                assertThat(pending.getKafkaPartition()).isEqualTo(3);
                assertThat(pending.getKafkaOffset()).isEqualTo(60L);
                assertThat(delivered).isEmpty();
            }
            org.mockito.Mockito.doReturn(new WatchlistStateStore.State(Set.of("alice"), false))
                    .when(watchlistStore).find("default", "accounts");
            processor.process(record.topic(), record.partition(), record.offset(), record.key(), record.value());
            assertThat(row("default", "watchlist-retry-h2").getStatus())
                    .isEqualTo(DetectionEventStatus.COMPLETED.name());
            assertThat(delivered).hasSize(1);
            assertThat(delivered.getFirst().evidence().getFirst().id()).isEqualTo("watchlist-retry-h2");
            verify(watchlistStore, times(11)).find("default", "accounts");
        } finally {
            Watchlists.clear();
            TenantContext.runWith("default", () -> journal.remove("default", "watchlist-retry-h2"));
        }
    }

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
        ReflectionTestUtils.setField(consumer, "pendingReplayMax", prefetchLimit);

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

            // The durable COMPLETED write precedes PendingWork.finally, which
            // releases memory. Join each lane before checking its accounting.
            Map<?, ThreadPoolExecutor> lanes =
                    (Map<?, ThreadPoolExecutor>) ReflectionTestUtils.getField(consumer, "partitionLanes");
            for (ThreadPoolExecutor lane : lanes.values()) {
                lane.submit(() -> { }).get(5, TimeUnit.SECONDS);
            }
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
