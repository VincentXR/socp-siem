package com.socp.search.config.infrastructure.kafka;

import com.socp.search.config.config.KafkaProperties;
import com.socp.search.config.config.OpenSearchIndexerProperties;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.infrastructure.opensearch.BulkWriteResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OsIndexerConsumerTest {

    private static final String EVENT = """
            {"eventId":"event-1","timestamp":"2026-08-21T00:00:00Z","source":"auth",
             "host":"host-1","severity":"HIGH","msg":"failed","fields":{"count":3,"tenant_id":"tenant-a"}}
            """;

    @Test
    void completesPartitionOnlyAfterOpenSearchAcknowledgesEveryItem() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenReturn(acknowledged("event-1"));
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
        when(writer.writeEventsAndAwait(anyList())).thenReturn(retryable("event-1"));
        OsIndexerConsumer consumer = new OsIndexerConsumer(writer);

        assertFalse(consumer.processPartition(List.of(
                new ConsumerRecord<>("socp-events", 0, 10L, "event-1", EVENT))));
    }

    @Test
    void commitsTheNextOffsetOnlyAfterDurableIndexing() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenReturn(acknowledged("event-1"));
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
        when(writer.writeEventsAndAwait(anyList())).thenReturn(retryable("event-1"));
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
        when(writer.writeEventsAndAwait(anyList())).thenReturn(acknowledged("event-1"));
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
        when(writer.writeEventsAndAwait(anyList())).thenReturn(acknowledged("event-1"));
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
            boolean sendToDlqAndAwait(ConsumerRecord<String, String> record, InvalidRecord failure) {
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
            boolean sendToDlqAndAwait(ConsumerRecord<String, String> record, InvalidRecord failure) {
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

    @Test
    void routesUnsupportedSchemaToDurableDlqBeforeIndexing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OsIndexerConsumer indexer = new OsIndexerConsumer(mock(OsEventWriter.class),
                new KafkaProperties(), new OpenSearchIndexerProperties(), registry) {
            @Override
            boolean sendToDlqAndAwait(ConsumerRecord<String, String> record, InvalidRecord failure) {
                return true;
            }
        };
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("socp-events", 0);
        String unsupported = EVENT.replace("{\"eventId\"", "{\"schemaVersion\":\"2.0\",\"eventId\"");
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(
                new ConsumerRecord<>("socp-events", 0, 10L, "event-1", unsupported))));

        assertTrue(indexer.processRecords(kafka, records));
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "schema_rejected").counter().count());
        verify(kafka).commitSync(org.mockito.ArgumentMatchers.<Map<TopicPartition, OffsetAndMetadata>>any());
    }

    @Test
    void commitsOnlyTheContiguousAcknowledgedOrDlqPrefixOfAMixedBulk() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenReturn(new BulkWriteResult(
                List.of("event-1", "event-4"),
                List.of(new BulkWriteResult.Failure(2, "event-3",
                        "es_rejected_execution_exception", "busy", 429)),
                List.of(new BulkWriteResult.Failure(1, "event-2",
                        "mapper_parsing_exception", "bad mapping", 400)), 12L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OsIndexerConsumer indexer = new OsIndexerConsumer(writer, new KafkaProperties(),
                new OpenSearchIndexerProperties(), registry) {
            @Override
            boolean sendToDlqAndAwait(ConsumerRecord<String, String> record, InvalidRecord failure) {
                return true;
            }
        };
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("socp-events", 0);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(partition, List.of(
                record(10L, "event-1"), record(11L, "event-2"),
                record(12L, "event-3"), record(13L, "event-4"))));

        assertFalse(indexer.processRecords(kafka, records));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> offsets = ArgumentCaptor.forClass(Map.class);
        verify(kafka).commitSync(offsets.capture());
        assertEquals(12L, offsets.getValue().get(partition).offset());
        verify(kafka).seek(partition, 12L);
        assertEquals(2.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "write").counter().count());
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "bulk_partial_failure").counter().count());
        assertEquals(1.0, registry.get("socp.opensearch.indexer.records")
                .tag("stage", "dlq").counter().count());
    }

    @Test
    void failedPartitionDoesNotBlockAnotherPartitionsCommit() {
        OsEventWriter writer = mock(OsEventWriter.class);
        when(writer.writeEventsAndAwait(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<SearchEvent> events = invocation.getArgument(0, List.class);
            return "event-1".equals(events.getFirst().eventId())
                    ? retryable("event-1") : acknowledged("event-2");
        });
        OsIndexerConsumer indexer = new OsIndexerConsumer(writer);
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition failed = new TopicPartition("socp-events", 0);
        TopicPartition succeeded = new TopicPartition("socp-events", 1);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(
                failed, List.of(new ConsumerRecord<>("socp-events", 0, 10L, "event-1", EVENT)),
                succeeded, List.of(new ConsumerRecord<>("socp-events", 1, 20L, "event-2",
                        eventJson("event-2")))));

        assertFalse(indexer.processRecords(kafka, records));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> offsets = ArgumentCaptor.forClass(Map.class);
        verify(kafka).commitSync(offsets.capture());
        assertThat(offsets.getValue()).containsOnlyKeys(succeeded);
        assertEquals(21L, offsets.getValue().get(succeeded).offset());
        verify(kafka).seek(failed, 10L);
    }

    @Test
    void diagnosticDlqEnvelopeCarriesSourceIdentityAndFailureContext() throws Exception {
        ConsumerRecord<String, String> record = record(42L, "event-1");
        OsIndexerConsumer.InvalidRecord failure = new OsIndexerConsumer.InvalidRecord(
                "event-1", "tenant-a", "1.0", "mapper_parsing_exception",
                "bad mapping\nvalue");

        String envelope = OsIndexerConsumer.dlqEnvelope(record, failure);

        assertThat(envelope).contains(
                "\"originalTopic\":\"socp-events\"", "\"partition\":0", "\"offset\":42",
                "\"key\":\"event-1\"", "\"eventId\":\"event-1\"",
                "\"tenant\":\"tenant-a\"", "\"schemaVersion\":\"1.0\"",
                "\"reasonCode\":\"mapper_parsing_exception\"", "\"originalPayload\""
        ).contains("\"failedAt\"");
    }

    private static BulkWriteResult acknowledged(String... eventIds) {
        return new BulkWriteResult(List.of(eventIds), List.of(), List.of(), 1L);
    }

    private static BulkWriteResult retryable(String eventId) {
        return new BulkWriteResult(List.of(), List.of(new BulkWriteResult.Failure(
                0, eventId, "bulk_transport_failure", "unavailable", 503)), List.of(), 1L);
    }

    private static ConsumerRecord<String, String> record(long offset, String eventId) {
        return new ConsumerRecord<>("socp-events", 0, offset, eventId, eventJson(eventId));
    }

    private static String eventJson(String eventId) {
        return EVENT.replace("event-1", eventId);
    }
}
