package com.socp.rule;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.ThresholdRule;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.common.serialization.Serdes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-B evidence only. This deliberately does not replace the production
 * consumer: it compares a narrow Kafka Streams topology with the current
 * threshold rule for event-time ordering, live configuration, and materialized
 * state. TopologyTestDriver deletes local task state on close, so a
 * broker-backed changelog failover test is still required before choosing a
 * production runtime.
 */
class KafkaStreamsDetectionComparisonTest {

    static final String RULE_TOPIC = "comparison-rule-config";
    static final String EVENT_TOPIC = "comparison-events";
    static final String ALERT_TOPIC = "comparison-alerts";
    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();

    @TempDir
    Path stateDirectory;

    @Test
    void streamsTopologyMatchesCurrentThresholdForOutOfOrderEventsAndSupportsRuleReload() {
        Topology topology = topology();
        Properties properties = properties(stateDirectory.resolve("reload"), "reload");
        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties)) {
            TestInputTopic<String, String> rules = driver.createInputTopic(
                    RULE_TOPIC, new StringSerializer(), new StringSerializer());
            TestInputTopic<String, String> events = driver.createInputTopic(
                    EVENT_TOPIC, new StringSerializer(), new StringSerializer());
            TestOutputTopic<String, String> alerts = driver.createOutputTopic(
                    ALERT_TOPIC, new StringDeserializer(), new StringDeserializer());

            rules.pipeInput("threshold", "3");
            events.pipeInput("host-a", "1000|e1");
            events.pipeInput("host-a", "3000|e2");
            events.pipeInput("host-a", "2000|e3");

            List<String> streamAlerts = alerts.readValuesToList();
            List<Alert> currentAlerts = runCurrentThreshold(List.of(
                    new Sample(1000L, "e1"), new Sample(3000L, "e2"), new Sample(2000L, "e3")), 3);
            assertEquals(1, currentAlerts.size());
            assertEquals(List.of("e3|3"), streamAlerts);
            assertEquals(currentAlerts.getFirst().evidence().size(), Integer.parseInt(
                    streamAlerts.getFirst().substring(streamAlerts.getFirst().lastIndexOf('|') + 1)));

            // The KTable changes the active threshold without rebuilding the
            // topology, while the existing window remains attached to host-a.
            rules.pipeInput("threshold", "2");
            events.pipeInput("host-a", "4000|e4");
            events.pipeInput("host-a", "5000|e5");
            assertEquals(List.of("e5|2"), alerts.readValuesToList());
        }
    }

    @Test
    void streamsTopologyMaterializesEntityStateForRecovery() {
        Properties properties = properties(stateDirectory.resolve("materialized"), "materialized");
        try (TopologyTestDriver driver = new TopologyTestDriver(topology(), properties)) {
            TestInputTopic<String, String> rules = driver.createInputTopic(
                    RULE_TOPIC, new StringSerializer(), new StringSerializer());
            TestInputTopic<String, String> events = driver.createInputTopic(
                    EVENT_TOPIC, new StringSerializer(), new StringSerializer());
            rules.pipeInput("threshold", "3");
            events.pipeInput("host-a", "1000|e1");
            events.pipeInput("host-a", "2000|e2");

            KeyValueStore<String, String> state = driver.getKeyValueStore("threshold-state");
            assertEquals("1000,e1;2000,e2", state.get("host-a"));
            assertTrue(driver.createOutputTopic(ALERT_TOPIC, new StringDeserializer(), new StringDeserializer())
                    .isEmpty());
        }
    }

    static Topology topology() {
        return topology(RULE_TOPIC, EVENT_TOPIC, ALERT_TOPIC);
    }

    static Topology topology(String ruleTopic, String eventTopic, String alertTopic) {
        StreamsBuilder builder = new StreamsBuilder();
        KTable<String, Integer> thresholds = builder
                .stream(ruleTopic, Consumed.with(org.apache.kafka.common.serialization.Serdes.String(),
                        org.apache.kafka.common.serialization.Serdes.String()))
                .filter((key, value) -> "threshold".equals(key))
                .mapValues(value -> Integer.parseInt(value))
                .toTable(Materialized.<String, Integer, KeyValueStore<Bytes, byte[]>>as("threshold-config")
                        .withKeySerde(org.apache.kafka.common.serialization.Serdes.String())
                        .withValueSerde(org.apache.kafka.common.serialization.Serdes.Integer()));

        StoreBuilder<KeyValueStore<String, String>> stateStore = Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("threshold-state"),
                org.apache.kafka.common.serialization.Serdes.String(),
                org.apache.kafka.common.serialization.Serdes.String())
                .withLoggingEnabled(Map.of());
        // Register the store before connecting the transform node to it.
        builder.addStateStore(stateStore);

        KStream<String, String> eventStream = builder
                .stream(eventTopic, Consumed.with(org.apache.kafka.common.serialization.Serdes.String(),
                        org.apache.kafka.common.serialization.Serdes.String()))
                .map((host, raw) -> KeyValue.pair("threshold", host + "|" + raw));
        eventStream.join(thresholds, (event, threshold) -> event + "|" + threshold)
                .transform(() -> new ThresholdTransformer(), "threshold-state")
                .to(alertTopic, Produced.with(org.apache.kafka.common.serialization.Serdes.String(),
                        org.apache.kafka.common.serialization.Serdes.String()));

        return builder.build();
    }

    private static Properties properties(Path stateDirectory, String suffix) {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "socp-detection-comparison-" + suffix);
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        properties.put(StreamsConfig.STATE_DIR_CONFIG, stateDirectory.toString());
        properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        properties.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1);
        return properties;
    }

    private static List<Alert> runCurrentThreshold(List<Sample> samples, int threshold) {
        ThresholdRule rule = new ThresholdRule("comparison", "comparison", event -> true,
                SecurityEvent::host, threshold, Duration.ofMinutes(1), Severity.HIGH, "comparison");
        List<Alert> alerts = new ArrayList<>();
        for (Sample sample : samples) {
            rule.accept(new SecurityEvent(sample.id(), Instant.ofEpochMilli(sample.timestamp()),
                    "auth", "host-a", sample.id(), Map.of("tenant_id", "default"), Severity.INFO));
            alerts.addAll(rule.drain());
        }
        return alerts;
    }

    private record Sample(long timestamp, String id) {
    }

    private static final class ThresholdTransformer
            implements org.apache.kafka.streams.kstream.Transformer<String, String, KeyValue<String, String>> {
        private KeyValueStore<String, String> state;

        @SuppressWarnings("unchecked")
        @Override
        public void init(org.apache.kafka.streams.processor.ProcessorContext context) {
            state = (KeyValueStore<String, String>) context.getStateStore("threshold-state");
        }

        @Override
        public KeyValue<String, String> transform(String ignored, String value) {
            String[] parts = value.split("\\|", 4);
            String host = parts[0];
            long timestamp = Long.parseLong(parts[1]);
            String eventId = parts[2];
            int threshold = Integer.parseInt(parts[3]);
            List<Sample> window = decode(state.get(host));
            long watermark = window.stream().mapToLong(Sample::timestamp).max().orElse(timestamp);
            watermark = Math.max(watermark, timestamp);
            long cutoff = watermark - WINDOW_MILLIS;
            window.removeIf(sample -> sample.timestamp() < cutoff);
            window.add(new Sample(timestamp, eventId));
            if (window.size() < threshold) {
                state.put(host, encode(window));
                return null;
            }
            state.put(host, "");
            return KeyValue.pair(host, eventId + "|" + window.size());
        }

        @Override
        public void close() {
        }

        private static List<Sample> decode(String encoded) {
            if (encoded == null || encoded.isBlank()) return new ArrayList<>();
            List<Sample> result = new ArrayList<>();
            for (String item : encoded.split(";")) {
                String[] parts = item.split(",", 2);
                result.add(new Sample(Long.parseLong(parts[0]), parts[1]));
            }
            return result;
        }

        private static String encode(List<Sample> samples) {
            return samples.stream().map(sample -> sample.timestamp() + "," + sample.id())
                    .reduce((left, right) -> left + ";" + right).orElse("");
        }
    }
}
