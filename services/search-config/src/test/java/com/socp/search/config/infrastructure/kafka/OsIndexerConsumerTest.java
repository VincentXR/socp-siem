package com.socp.search.config.infrastructure.kafka;

import com.socp.search.config.config.KafkaProperties;
import com.socp.search.config.config.OpenSearchIndexerProperties;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.infrastructure.opensearch.OsEventWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class OsIndexerConsumerTest {

    private static final String EVENT = """
            {"eventId":"event-1","timestamp":"2026-08-21T00:00:00Z","source":"auth",
             "host":"host-1","severity":"HIGH","msg":"failed","fields":{"count":3,"tenant_id":"tenant-a"}}
            """;

    @Test
    void completesPartitionOnlyAfterOpenSearchAcknowledgesEveryItem() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenReturn(true);
        OsIndexerConsumer consumer = new OsIndexerConsumer(writer);

        boolean completed = consumer.processPartition(List.of(
                new ConsumerRecord<>("socp-events", 2, 42L, "event-1", EVENT)));

        assertTrue(completed);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SearchEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(writer).writeEventsAndAwait(events.capture());
        assertEquals("event-1", events.getValue().getFirst().eventId());
        assertEquals("3", events.getValue().getFirst().fields().get("count"));
    }

    @Test
    void leavesPartitionUncompletedWhenOpenSearchFails() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenReturn(false);
        OsIndexerConsumer consumer = new OsIndexerConsumer(writer);

        assertFalse(consumer.processPartition(List.of(
                new ConsumerRecord<>("socp-events", 0, 10L, "event-1", EVENT))));
    }

    @Test
    void commitsTheNextOffsetOnlyAfterDurableIndexing() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenReturn(true);
        OsIndexerConsumer indexer = new OsIndexerConsumer(writer);
        @SuppressWarnings("unchecked")
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("socp-events", 2);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(
                new ConsumerRecord<>("socp-events", 2, 42L, "event-1", EVENT))));

        indexer.processRecords(kafka, records);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> offsets = ArgumentCaptor.forClass(Map.class);
        verify(kafka).commitSync(offsets.capture());
        assertEquals(43L, offsets.getValue().get(partition).offset());
        verify(kafka, never()).seek(partition, 42L);
        InOrder order = inOrder(writer, kafka);
        order.verify(writer).writeEventsAndAwait(anyList());
        order.verify(kafka).commitSync(org.mockito.ArgumentMatchers.<TopicPartition, OffsetAndMetadata>anyMap());
    }

    @Test
    void rewindsAndDoesNotCommitWhenDurableIndexingFails() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenReturn(false);
        OsIndexerConsumer indexer = new OsIndexerConsumer(writer);
        @SuppressWarnings("unchecked")
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("socp-events", 0);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(
                new ConsumerRecord<>("socp-events", 0, 10L, "event-1", EVENT))));

        indexer.processRecords(kafka, records);

        verify(kafka).seek(partition, 10L);
        verify(kafka, never()).commitSync(org.mockito.ArgumentMatchers.<Map<TopicPartition, OffsetAndMetadata>>any());
    }

    @Test
    void exposesRecordReconciliationCounters() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OsIndexerConsumer indexer = new OsIndexerConsumer(writer, new KafkaProperties(),
                new OpenSearchIndexerProperties(), registry);
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("socp-events", 0);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(
                new ConsumerRecord<>("socp-events", 0, 10L, "event-1", EVENT))));

        indexer.processRecords(kafka, records);

        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "consume").counter().count());
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "write").counter().count());
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "commit").counter().count());
    }

    @Test
    void recordsCommitFailureWithoutClaimingTheOffset() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OsIndexerConsumer indexer = new OsIndexerConsumer(writer, new KafkaProperties(),
                new OpenSearchIndexerProperties(), registry);
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        doThrow(new RuntimeException("commit unavailable")).when(kafka)
                .commitSync(org.mockito.ArgumentMatchers.<Map<TopicPartition, OffsetAndMetadata>>any());
        TopicPartition partition = new TopicPartition("socp-events", 0);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(
                new ConsumerRecord<>("socp-events", 0, 10L, "event-1", EVENT))));

        assertThrows(RuntimeException.class, () -> indexer.processRecords(kafka, records));
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "commit_failed").counter().count());
    }

    @Test
    void recordsMalformedInputAndDurableDlqAcknowledgement() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OsIndexerConsumer indexer = new OsIndexerConsumer(mock(OsEventWriter.class), new KafkaProperties(),
                new OpenSearchIndexerProperties(), registry) {
            @Override
            boolean sendToDlqAndAwait(String eventId, String raw) {
                return true;
            }
        };
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("socp-events", 0);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(
                new ConsumerRecord<>("socp-events", 0, 10L, "bad-1", "not-json"))));

        assertTrue(indexer.processRecords(kafka, records));

        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "consume").counter().count());
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "drop").counter().count());
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "dlq").counter().count());
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "commit").counter().count());
    }

    @Test
    void keepsMalformedInputUncommittedWhenDlqFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OsIndexerConsumer indexer = new OsIndexerConsumer(mock(OsEventWriter.class),
                new KafkaProperties(), new OpenSearchIndexerProperties(), registry) {
            @Override
            boolean sendToDlqAndAwait(String eventId, String raw) {
                return false;
            }
        };
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("socp-events", 0);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(
                new ConsumerRecord<>("socp-events", 0, 10L, "bad-1", "not-json"))));

        assertFalse(indexer.processRecords(kafka, records));

        verify(kafka).seek(partition, 10L);
        verify(kafka, never()).commitSync(org.mockito.ArgumentMatchers.<Map<TopicPartition, OffsetAndMetadata>>any());
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "dlq_failed").counter().count());
    }
}
