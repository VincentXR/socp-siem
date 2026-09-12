package com.socp.rule;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in Stage-B evidence using a real Kafka broker. It intentionally uses
 * isolated topics and a fresh local state directory on restart, so the second
 * Streams process must rebuild the threshold window from its changelog before
 * it can emit the third-event alert.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class KafkaStreamsBrokerRecoveryComparisonTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @TempDir
    Path stateDirectory;

    @Test
    void changelogRestoresStateInASecondStreamsProcess() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String ruleTopic = "comparison-rules-" + suffix;
        String eventTopic = "comparison-events-" + suffix;
        String alertTopic = "comparison-alerts-" + suffix;
        String applicationId = "socp-detection-broker-recovery-" + suffix;
        createTopics(ruleTopic, eventTopic, alertTopic);

        Topology topology = KafkaStreamsDetectionComparisonTest.topology(
                ruleTopic, eventTopic, alertTopic);
        KafkaStreams first = start(topology, properties(applicationId,
                stateDirectory.resolve("first")));
        try {
            produce(ruleTopic, "threshold", "3");
            awaitIntegerStore(first, "threshold-config", "threshold", 3);
            produce(eventTopic, "host-a", "1000|e1");
            produce(eventTopic, "host-a", "2000|e2");
            awaitStringStore(first, "threshold-state", "host-a", "1000,e1;2000,e2");
        } finally {
            first.close(Duration.ofSeconds(20));
        }
        awaitConsumerGroupQuiesced(applicationId);

        KafkaStreams second = start(topology, properties(applicationId,
                stateDirectory.resolve("second")));
        try {
            awaitIntegerStore(second, "threshold-config", "threshold", 3);
            awaitStringStore(second, "threshold-state", "host-a", "1000,e1;2000,e2");

            try (KafkaConsumer<String, String> consumer = consumer(alertTopic)) {
                consumer.poll(Duration.ofSeconds(2));
                produce(eventTopic, "host-a", "3000|e3");
                assertEquals(List.of("e3|3"), pollValues(consumer, Duration.ofSeconds(20)));
            }
        } finally {
            second.close(Duration.ofSeconds(20));
        }
    }

    /**
     * Closing Kafka Streams finishes the local shutdown before the broker has
     * necessarily completed the consumer-group transition. Waiting for the
     * group to become empty avoids a restart racing the broker's rebalance,
     * which otherwise leaves the second process in REBALANCING on CI brokers.
     */
    private static void awaitConsumerGroupQuiesced(String groupId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int emptyObservations = 0;
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            while (System.nanoTime() < deadline) {
                try {
                    boolean listed = admin.listConsumerGroups().all()
                            .get(5, TimeUnit.SECONDS).stream()
                            .anyMatch(group -> groupId.equals(group.groupId()));
                    if (!listed) return;

                    var description = admin.describeConsumerGroups(List.of(groupId)).all()
                            .get(5, TimeUnit.SECONDS).get(groupId);
                    if (description != null
                            && (description.state() == ConsumerGroupState.EMPTY
                            || description.state() == ConsumerGroupState.DEAD)) {
                        if (++emptyObservations >= 2) return;
                    } else {
                        emptyObservations = 0;
                    }
                } catch (ExecutionException | TimeoutException ignored) {
                    emptyObservations = 0;
                }
                Thread.sleep(250);
            }
        }
        fail("timed out waiting for Kafka consumer group to quiesce: " + groupId);
    }

    private void createTopics(String... topics) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(java.util.Arrays.stream(topics)
                    .map(topic -> new NewTopic(topic, 1, (short) 1)).toList())
                    .all().get(30, TimeUnit.SECONDS);
        }
    }

    private static KafkaStreams start(Topology topology, Properties properties) throws Exception {
        KafkaStreams streams = new KafkaStreams(topology, properties);
        CountDownLatch running = new CountDownLatch(1);
        streams.setStateListener((newState, oldState) -> {
            if (newState == KafkaStreams.State.RUNNING) running.countDown();
        });
        streams.start();
        if (!running.await(30, TimeUnit.SECONDS)) {
            streams.close(Duration.ofSeconds(5));
            fail("Kafka Streams did not reach RUNNING: " + streams.state());
        }
        return streams;
    }

    private static Properties properties(String applicationId, Path stateDirectory) {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(StreamsConfig.STATE_DIR_CONFIG, stateDirectory.toString());
        properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                org.apache.kafka.common.serialization.Serdes.StringSerde.class);
        properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                org.apache.kafka.common.serialization.Serdes.StringSerde.class);
        properties.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1);
        properties.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
        properties.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        return properties;
    }

    private static void produce(String topic, String key, String value) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(20, TimeUnit.SECONDS);
        }
    }

    private static KafkaConsumer<String, String> consumer(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "comparison-output-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static List<String> pollValues(KafkaConsumer<String, String> consumer, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> values = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            for (var record : consumer.poll(Duration.ofMillis(500))) values.add(record.value());
            if (!values.isEmpty()) return values;
        }
        return values;
    }

    private static void awaitStringStore(KafkaStreams streams, String name,
                                         String key, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ReadOnlyKeyValueStore<String, String> store = streams.store(
                        StoreQueryParameters.fromNameAndType(name, QueryableStoreTypes.keyValueStore()));
                if (expected.equals(store.get(key))) return;
            } catch (InvalidStateStoreException ignored) {
                // The store becomes queryable after the task restores.
            }
            Thread.sleep(100);
        }
        fail("timed out waiting for " + name + "=" + expected);
    }

    private static void awaitIntegerStore(KafkaStreams streams, String name,
                                          String key, Integer expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ReadOnlyKeyValueStore<String, Integer> store = streams.store(
                        StoreQueryParameters.fromNameAndType(name, QueryableStoreTypes.keyValueStore()));
                if (expected.equals(store.get(key))) return;
            } catch (InvalidStateStoreException ignored) {
                // The store becomes queryable after the task restores.
            }
            Thread.sleep(100);
        }
        fail("timed out waiting for " + name + "=" + expected);
    }
}
