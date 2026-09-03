package com.socp.search.config.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.search.config.config.KafkaProperties;
import com.socp.search.config.config.OpenSearchIndexerProperties;
import com.socp.search.config.config.OpenSearchProperties;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.infrastructure.opensearch.BulkWriteResult;
import com.socp.search.config.infrastructure.opensearch.OsEventWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real middleware failure and source-offset reconciliation evidence. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class OsIndexerFailureContainerTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:2.11.1"))
            .withNetwork(NETWORK)
            .withNetworkAliases("opensearch")
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200));

    @Test
    void reconcilesMixedPartialFailureByUniqueSourceOffset() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic = "indexer-mixed-" + suffix;
        String group = "indexer-mixed-group-" + suffix;
        publish(KAFKA.getBootstrapServers(), topic,
                eventJson("mixed-good-1-" + suffix, "2026-09-01T00:00:00Z", "10.0.0.1"),
                eventJson("mixed-bad-" + suffix, "2026-09-01T00:00:01Z", "not-an-ip"),
                eventJson("mixed-good-2-" + suffix, "2026-09-01T00:00:02Z", "10.0.0.2"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        KafkaProperties kafka = kafkaProperties(KAFKA.getBootstrapServers(), topic);
        createStrictRawIpMapping("socp-events-2026.09.01");
        OsIndexerConsumer indexer = new OsIndexerConsumer(realWriter(), kafka,
                new OpenSearchIndexerProperties(), metrics);

        TopicPartition sourcePartition;
        try (KafkaConsumer<String, String> source = consumer(KAFKA.getBootstrapServers(), group, topic)) {
            ConsumerRecords<String, String> records = pollAtLeast(source, 3);
            assertThat(records.count()).isEqualTo(3);
            sourcePartition = records.partitions().iterator().next();
            assertThat(indexer.processRecords(source, records)).isTrue();
        }

        refresh("socp-events-2026.09.01");
        long indexedUniqueDocs = count("socp-events-2026.09.01");
        List<ConsumerRecord<String, String>> dlq = consumeAtLeast(
                KAFKA.getBootstrapServers(), topic + "-dlq", 1);
        JsonNode envelope = MAPPER.readTree(dlq.getFirst().value());
        long committedSourceOffsets;
        try (KafkaConsumer<String, String> verifier = consumer(
                KAFKA.getBootstrapServers(), group, topic)) {
            verifier.unsubscribe();
            verifier.assign(List.of(sourcePartition));
            OffsetAndMetadata committed = verifier.committed(sourcePartition);
            committedSourceOffsets = committed.offset();
        }

        assertThat(indexedUniqueDocs).isEqualTo(2L);
        assertThat(dlq).hasSize(1);
        assertThat(envelope.path("originalTopic").asText()).isEqualTo(topic);
        assertThat(envelope.path("partition").asInt()).isEqualTo(sourcePartition.partition());
        assertThat(envelope.path("offset").asLong()).isEqualTo(1L);
        assertThat(envelope.path("reasonCode").asText()).isEqualTo("mapper_parsing_exception");
        assertThat(committedSourceOffsets).isEqualTo(indexedUniqueDocs + dlq.size()).isEqualTo(3L);
        assertThat(counter(metrics, "bulk_partial_failure")).isEqualTo(1.0);
        assertThat(counter(metrics, "commit")).isEqualTo(3.0);
        indexer.stop();
    }

    @Test
    void replaysWriteAcknowledgedBeforeCommitWithoutCreatingAnotherDocument() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic = "indexer-replay-" + suffix;
        String group = "indexer-replay-group-" + suffix;
        String eventId = "replay-event-" + suffix;
        publish(KAFKA.getBootstrapServers(), topic,
                eventJson(eventId, "2026-09-02T00:00:00Z", "10.0.0.3"));
        OsIndexerConsumer indexer = new OsIndexerConsumer(realWriter());

        try (KafkaConsumer<String, String> crashed = consumer(
                KAFKA.getBootstrapServers(), group, topic)) {
            ConsumerRecords<String, String> firstDelivery = pollAtLeast(crashed, 1);
            assertThat(indexer.processPartition(firstDelivery.records(
                    firstDelivery.partitions().iterator().next()))).isTrue();
            // Closing without commit models process exit after OpenSearch ack.
        }
        refresh("socp-events-2026.09.02");
        assertThat(count("socp-events-2026.09.02")).isEqualTo(1L);

        try (KafkaConsumer<String, String> restarted = consumer(
                KAFKA.getBootstrapServers(), group, topic)) {
            ConsumerRecords<String, String> replay = pollAtLeast(restarted, 1);
            assertThat(indexer.processRecords(restarted, replay)).isTrue();
        }
        refresh("socp-events-2026.09.02");

        assertThat(count("socp-events-2026.09.02")).isEqualTo(1L);
        assertThat(searchIds("socp-events-2026.09.02")).containsExactly("tenant-a|" + eventId);
    }

    @Test
    void keepsPermanentFailureUncommittedWhenDlqBrokerIsUnavailable() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic = "indexer-dlq-down-" + suffix;
        String group = "indexer-dlq-down-group-" + suffix;
        publish(KAFKA.getBootstrapServers(), topic,
                eventJson("dlq-down-" + suffix, "2026-09-03T00:00:00Z", "invalid-ip"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        KafkaProperties unavailableDlq = kafkaProperties("127.0.0.1:1", topic);
        createStrictRawIpMapping("socp-events-2026.09.03");
        OsIndexerConsumer indexer = new OsIndexerConsumer(realWriter(), unavailableDlq,
                new OpenSearchIndexerProperties(), metrics);

        try (KafkaConsumer<String, String> source = consumer(KAFKA.getBootstrapServers(), group, topic)) {
            ConsumerRecords<String, String> records = pollAtLeast(source, 1);
            TopicPartition partition = records.partitions().iterator().next();
            long offset = records.records(partition).getFirst().offset();

            assertThat(indexer.processRecords(source, records)).isFalse();
            assertThat(source.position(partition)).isEqualTo(offset);
            assertThat(source.committed(partition)).isNull();
        } finally {
            indexer.stop();
        }

        assertThat(counter(metrics, "dlq_failed")).isEqualTo(1.0);
        assertThat(metrics.find("socp.opensearch.indexer.records")
                .tag("stage", "commit").counter()).isNull();
    }

    @Test
    void retriesAnOpenSearch503ReturnedByARealFaultProxy() throws Exception {
        String nginx = """
                server {
                  listen 8080;
                  location = /_bulk { return 503; }
                  location / { proxy_pass http://opensearch:9200; }
                }
                """;
        try (GenericContainer<?> proxy = new GenericContainer<>(
                DockerImageName.parse("nginx:1.27-alpine"))
                .withNetwork(NETWORK)
                .withCopyToContainer(Transferable.of(nginx.getBytes(StandardCharsets.UTF_8)),
                        "/etc/nginx/conf.d/default.conf")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/_cluster/health").forPort(8080).forStatusCode(200))) {
            proxy.start();
            OpenSearchProperties properties = new OpenSearchProperties();
            properties.setEnabled(true);
            properties.setUrl("http://" + proxy.getHost() + ":" + proxy.getMappedPort(8080));
            OsEventWriter writer = new OsEventWriter(properties);

            BulkWriteResult result = writer.writeEventsAndAwait(List.of(new SearchEvent(
                    "proxy-503", java.time.Instant.parse("2026-09-05T00:00:00Z"),
                    "auth", "host-1", "HIGH", "failure evidence",
                    Map.of("tenant_id", "tenant-a", "src_ip", "10.0.0.5"), Map.of())));

            assertThat(result.acknowledgedIds()).isEmpty();
            assertThat(result.permanentFailures()).isEmpty();
            assertThat(result.retryableFailures()).singleElement()
                    .satisfies(failure -> {
                        assertThat(failure.status()).isEqualTo(503);
                        assertThat(failure.reasonCode()).isEqualTo("bulk_http_503");
                    });
        }
    }

    @Test
    void recordsRealCommitFailureAfterOpenSearchAcknowledgement() throws Exception {
        try (KafkaContainer failingKafka = new KafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))) {
            failingKafka.start();
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String topic = "indexer-commit-down-" + suffix;
            String group = "indexer-commit-down-group-" + suffix;
            publish(failingKafka.getBootstrapServers(), topic,
                    eventJson("commit-down-" + suffix, "2026-09-04T00:00:00Z", "10.0.0.4"));
            SimpleMeterRegistry metrics = new SimpleMeterRegistry();
            OsEventWriter delegate = realWriter();
            OsEventWriter stopBrokerAfterWrite = new OsEventWriter() {
                @Override
                public BulkWriteResult writeEventsAndAwait(List<SearchEvent> events) {
                    BulkWriteResult result = delegate.writeEventsAndAwait(events);
                    failingKafka.stop();
                    return result;
                }
            };
            OsIndexerConsumer indexer = new OsIndexerConsumer(stopBrokerAfterWrite,
                    kafkaProperties(failingKafka.getBootstrapServers(), topic),
                    new OpenSearchIndexerProperties(), metrics);

            try (KafkaConsumer<String, String> source = consumer(
                    failingKafka.getBootstrapServers(), group, topic)) {
                ConsumerRecords<String, String> records = pollAtLeast(source, 1);

                assertThatThrownBy(() -> indexer.processRecords(source, records))
                        .isInstanceOf(RuntimeException.class);
            }

            refresh("socp-events-2026.09.04");
            assertThat(count("socp-events-2026.09.04")).isEqualTo(1L);
            assertThat(counter(metrics, "commit_failed")).isEqualTo(1.0);
            assertThat(metrics.find("socp.opensearch.indexer.records")
                    .tag("stage", "commit").counter()).isNull();
        }
    }

    private static OsEventWriter realWriter() {
        OpenSearchProperties properties = new OpenSearchProperties();
        properties.setEnabled(true);
        properties.setUrl(openSearchBase());
        return new OsEventWriter(properties);
    }

    private static KafkaProperties kafkaProperties(String bootstrap, String topic) {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrap(bootstrap);
        properties.setTopic(topic);
        return properties;
    }

    private static KafkaConsumer<String, String> consumer(String bootstrap, String group, String topic) {
        Properties properties = KafkaClientSupport.reliableConsumer(bootstrap, group, "earliest", 500);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static void publish(String bootstrap, String topic, String... values) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                KafkaClientSupport.reliableProducer(bootstrap))) {
            for (String value : values) {
                String eventId = MAPPER.readTree(value).path("eventId").asText();
                producer.send(new ProducerRecord<>(topic, eventId, value)).get();
            }
        }
    }

    private static ConsumerRecords<String, String> pollAtLeast(
            KafkaConsumer<String, String> consumer, int expected) {
        Map<TopicPartition, List<ConsumerRecord<String, String>>> accumulated = new LinkedHashMap<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            ConsumerRecords<String, String> batch = consumer.poll(Duration.ofSeconds(1));
            for (TopicPartition partition : batch.partitions()) {
                accumulated.computeIfAbsent(partition, ignored -> new ArrayList<>())
                        .addAll(batch.records(partition));
            }
            int count = accumulated.values().stream().mapToInt(List::size).sum();
            if (count >= expected) return new ConsumerRecords<>(accumulated);
        } while (System.nanoTime() < deadline);
        return new ConsumerRecords<>(accumulated);
    }

    private static List<ConsumerRecord<String, String>> consumeAtLeast(
            String bootstrap, String topic, int expected) {
        try (KafkaConsumer<String, String> consumer = consumer(
                bootstrap, "evidence-" + UUID.randomUUID(), topic)) {
            ConsumerRecords<String, String> records = pollAtLeast(consumer, expected);
            return records.partitions().stream().flatMap(partition -> records.records(partition).stream())
                    .toList();
        }
    }

    private static String eventJson(String eventId, String timestamp, String srcIp) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenant_id", "tenant-a");
        fields.put("src_ip", srcIp);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("tenantId", "tenant-a");
        event.put("timestamp", timestamp);
        event.put("source", "auth");
        event.put("host", "host-1");
        event.put("severity", "HIGH");
        event.put("msg", "indexer failure evidence");
        event.put("fields", fields);
        return MAPPER.writeValueAsString(event);
    }

    private static double counter(SimpleMeterRegistry metrics, String stage) {
        return metrics.get("socp.opensearch.indexer.records")
                .tag("stage", stage).counter().count();
    }

    private static void refresh(String index) throws Exception {
        request("/" + index + "/_refresh", "POST", "", 200);
    }

    /**
     * The production writer deliberately moves malformed src_ip values to
     * src_ip_raw. Keep this failure test meaningful by making that fallback
     * field strict in an already-created index, which models a stale/bad
     * mapping that still requires DLQ handling.
     */
    private static void createStrictRawIpMapping(String index) throws Exception {
        String mapping = "{\"mappings\":{\"properties\":{"
                + "\"fields\":{\"properties\":{\"src_ip_raw\":{\"type\":\"ip\"}}}}}}";
        request("/" + index, "PUT", mapping, 200, 201);
    }

    private static long count(String index) throws Exception {
        return MAPPER.readTree(request("/" + index + "/_count", "GET", null, 200))
                .path("count").asLong();
    }

    private static List<String> searchIds(String index) throws Exception {
        JsonNode hits = MAPPER.readTree(request("/" + index + "/_search", "GET", null, 200))
                .path("hits").path("hits");
        List<String> ids = new ArrayList<>();
        hits.forEach(hit -> ids.add(hit.path("_id").asText()));
        return ids;
    }

    private static String request(String path, String method, String body, int... statuses)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(openSearchBase() + path))
                .timeout(Duration.ofSeconds(30));
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(java.util.Arrays.stream(statuses).boxed().toList())
                .withFailMessage("OpenSearch HTTP %s for %s %s: %s",
                        response.statusCode(), method, path, response.body())
                .contains(response.statusCode());
        return response.body();
    }

    private static String openSearchBase() {
        return "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
    }
}
