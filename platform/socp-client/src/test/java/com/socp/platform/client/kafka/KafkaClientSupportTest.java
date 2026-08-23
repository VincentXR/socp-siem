package com.socp.platform.client.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
