package com.socp.detect.web.engine;

import com.socp.detect.web.persistence.entity.DetectionEventEntity;
import com.socp.detect.web.persistence.repository.DetectionEventRepository;
import com.socp.detect.web.persistence.store.DetectionEventJournal;
import com.socp.detect.web.persistence.store.DetectionEventStatus;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.SecurityEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Real-journal evidence for the terminal dead-letter hand-off. A mocked store
 * cannot show the defect this locks down: the lane thread has no tenant scope
 * left when the hand-off runs, so the production journal silently skipped the
 * DEAD_LETTERED receipt and left the claimed row PENDING forever.
 */
@DataJpaTest
class DetectionDeadLetterJournalTest {

    @Autowired
    private DetectionEventRepository repository;

    private final DetectEngineService engine = mock(DetectEngineService.class);

    @Test
    @Timeout(30)
    void exhaustedRetriesWriteOneTerminalRowAndStopReplayingTheRecord() {
        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("durable sink rejected this event")));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, journal);
        ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 1);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        ConsumerRecord<String, String> record = poison(3, 50L, "poison-h2-1", "198.51.100.7");

        consumer.processWithRetry(record, 1L);

        assertThat(dlq).singleElement().satisfies(entry -> {
            assertThat(entry.getKey()).isEqualTo("poison-h2-1");
            assertThat(entry.getValue()).isEqualTo(record.value());
        });
        DetectionEventEntity row = row("default", "poison-h2-1");
        assertThat(row.getStatus()).isEqualTo(DetectionEventStatus.DEAD_LETTERED.name());
        assertThat(row.getKafkaPartition()).isEqualTo(3);
        assertThat(row.getKafkaOffset()).isEqualTo(50L);
        assertThat(row.getStatusReason()).contains("processing attempts exhausted");
        assertThat(journal.pendingCount("default")).isZero();
        // replayPending only selects PENDING rows, so the durable terminal receipt
        // is what stops a rebalance from dead-lettering the same record again.
        TenantContext.runAsSystem(() -> assertThat(
                journal.pendingRecordsForPartitions(Set.of(3), Duration.ofHours(24))).isEmpty());

        consumer.processWithRetry(record, 2L);

        assertThat(dlq).hasSize(1);
    }

    @Test
    @Timeout(30)
    void twoPoisonRecordsForOneRoutingKeyKeepSeparateTerminalRecords() {
        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("durable sink rejected this event")));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, journal);
        ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 1);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        // One entity, two events: the shared Kafka key used to collapse both into
        // a single DLQ key and a single journal identity.
        consumer.processWithRetry(poison(4, 60L, "poison-h2-a", "198.51.100.8"), 1L);
        consumer.processWithRetry(poison(4, 61L, "poison-h2-b", "198.51.100.8"), 1L);

        assertThat(dlq).extracting(Map.Entry::getKey).containsExactly("poison-h2-a", "poison-h2-b");
        assertThat(row("default", "poison-h2-a").getStatus()).isEqualTo(DetectionEventStatus.DEAD_LETTERED.name());
        assertThat(row("default", "poison-h2-b").getStatus()).isEqualTo(DetectionEventStatus.DEAD_LETTERED.name());
        assertThat(journal.pendingCount("default")).isZero();
    }

    @Test
    @Timeout(30)
    void anUnreachableDeadLetterLeavesTheRowPendingForReplay() {
        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("durable sink rejected this event")));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, journal);
        ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 1);
        ReflectionTestUtils.setField(consumer, "dlqHandoffMaxAttempts", 1);
        consumer.setDlqSink((eventId, raw) -> {
            throw new IllegalStateException("broker unreachable");
        });

        consumer.processWithRetry(poison(5, 70L, "poison-h2-3", "198.51.100.9"), 1L);

        // Neither durable write succeeded, so neither the offset nor the journal
        // may claim the record is terminal.
        assertThat(row("default", "poison-h2-3").getStatus()).isEqualTo(DetectionEventStatus.PENDING.name());
        assertThat(journal.pendingCount("default")).isEqualTo(1L);
    }

    private static ConsumerRecord<String, String> poison(int partition, long offset, String eventId,
                                                         String ip) {
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
