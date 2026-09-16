package com.socp.platform.client.kafka;


import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaClientSupportTest {

    @Test
    void appliesAtLeastOnceConsumerAndIdempotentProducerDefaults() {
        var consumer = KafkaClientSupport.reliableConsumer("kafka:9092", "group", "earliest", 200);
        var producer = KafkaClientSupport.reliableProducer("kafka:9092");

        assertEquals("false", consumer.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals("read_committed", consumer.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));
        assertEquals("all", producer.get(ProducerConfig.ACKS_CONFIG));
        assertEquals("true", String.valueOf(producer.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)));
    }

    @Test
    void sendsARecordThatAlreadyCarriesItsHeaders() {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        ProducerRecord<String, String> record = new ProducerRecord<>("topic-dlq", "key-1", "{}");
        record.headers().add("traceparent",
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01".getBytes(StandardCharsets.UTF_8));
        given(producer.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.completedFuture(mock(RecordMetadata.class)));

        KafkaClientSupport.sendAndAwait(producer, record, Duration.ofSeconds(1));

        // The headers must survive: a dead-letter entry is only traceable if the
        // context of the record it replaces travels with it.
        assertEquals("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8));
        verify(producer).send(record);
    }

    @Test
    void reportsAnUnacknowledgedDeliveryRatherThanSwallowingIt() {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        given(producer.send(any(ProducerRecord.class))).willReturn(
                CompletableFuture.failedFuture(new IllegalStateException("broker unreachable")));

        assertThrows(IllegalStateException.class, () -> KafkaClientSupport.sendAndAwait(producer,
                new ProducerRecord<>("topic-dlq", "key-1", "{}"), Duration.ofSeconds(1)));
    }
}
