package com.socp.search.config.search;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class OsIndexerConsumerTest {

    private static final String EVENT = """
            {"eventId":"event-1","timestamp":"2026-08-21T00:00:00Z","source":"auth",
             "host":"host-1","severity":"HIGH","msg":"failed","fields":{"count":3}}
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
}
