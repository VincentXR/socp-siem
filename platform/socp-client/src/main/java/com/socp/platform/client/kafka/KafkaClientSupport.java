package com.socp.platform.client.kafka;


import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Shared reliability defaults for raw Kafka clients used by SOCP event workers. */
public final class KafkaClientSupport {

    private KafkaClientSupport() {
    }

    public static Properties reliableConsumer(String bootstrap, String groupId,
                                               String offsetReset, int maxPollRecords) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Math.max(1, maxPollRecords));
        return properties;
    }

    public static Properties reliableProducer(String bootstrap) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        return properties;
    }

    public static void rewindBatch(KafkaConsumer<String, String> consumer,
                                   ConsumerRecords<String, String> records) {
        for (TopicPartition partition : records.partitions()) {
            var partitionRecords = records.records(partition);
            if (!partitionRecords.isEmpty()) {
                consumer.seek(partition, partitionRecords.getFirst().offset());
            }
        }
    }

    public static void sendAndAwait(KafkaProducer<String, String> producer, String topic,
                                    String key, String value, Duration timeout) {
        try {
            producer.send(new ProducerRecord<>(topic, key == null ? "unknown" : key, value))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka delivery interrupted", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("Kafka delivery was not acknowledged", failure);
        }
    }
}
