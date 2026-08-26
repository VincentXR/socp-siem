package com.socp.platform.test;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused real-middleware semantics. The suite is opt-in locally and enabled
 * by CI with SOCP_TESTCONTAINERS=true, so a skipped local run is not a false
 * green integration claim.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class MiddlewareSemanticsContainerTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(
            "clickhouse/clickhouse-server:24.3")
            .withEnv("CLICKHOUSE_USER", "default")
            .withEnv("CLICKHOUSE_PASSWORD", "socp")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forStatusCode(200));

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            "opensearchproject/opensearch:2.11.1")
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forStatusCode(200));

    @Test
    void kafkaCommitPreventsDuplicateConsumptionButAReplayGroupCanReprocess() {
        String topic = "contract-" + UUID.randomUUID();
        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties)) {
            producer.send(new ProducerRecord<>(topic, "event-1", "payload")).get();
            producer.send(new ProducerRecord<>(topic, "event-1", "payload")).get();
        } catch (Exception exception) {
            throw new AssertionError("Kafka publish failed", exception);
        }

        String group = "contract-consumer-" + UUID.randomUUID();
        try (KafkaConsumer<String, String> consumer = consumer(group, topic)) {
            ConsumerRecords<String, String> records = pollAtLeast(consumer, 2);
            assertThat(records).hasSize(2);
            consumer.commitSync();
        }
        try (KafkaConsumer<String, String> committed = consumer(group, topic)) {
            assertThat(committed.poll(Duration.ofSeconds(3))).isEmpty();
        }
        try (KafkaConsumer<String, String> replay = consumer("replay-" + UUID.randomUUID(), topic)) {
            assertThat(pollAtLeast(replay, 2)).hasSize(2);
        }
    }

    @Test
    void clickhouseLogicalKeyRemainsUniqueAcrossAtLeastOnceInserts() throws Exception {
        clickhouse("CREATE DATABASE IF NOT EXISTS alert_agg");
        clickhouse("CREATE TABLE IF NOT EXISTS alert_agg.contract_alarm_detail "
                + "(tenant_id String, alarm_id String, row_version UInt64) "
                + "ENGINE=ReplacingMergeTree(row_version) ORDER BY (tenant_id, alarm_id)");
        clickhouse("INSERT INTO alert_agg.contract_alarm_detail FORMAT JSONEachRow\n"
                + "{\"tenant_id\":\"tenant-a\",\"alarm_id\":\"alarm-1\",\"row_version\":1}");
        clickhouse("INSERT INTO alert_agg.contract_alarm_detail FORMAT JSONEachRow\n"
                + "{\"tenant_id\":\"tenant-a\",\"alarm_id\":\"alarm-1\",\"row_version\":1}");

        assertThat(clickhouse("SELECT uniqExact(tuple(tenant_id, alarm_id)) "
                + "FROM alert_agg.contract_alarm_detail")).isEqualTo("1");
        assertThat(clickhouse("SELECT count() FROM alert_agg.contract_alarm_detail")).isEqualTo("2");
    }

    @Test
    void opensearchUsesDeterministicDocumentIdsAndReportsPartialBulkFailures() throws Exception {
        String base = "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
        request(base + "/contract-evidence/_doc/alarm-1?refresh=true", "PUT",
                "{\"host\":\"web-1\",\"severity\":\"HIGH\"}", 200, 201);
        request(base + "/contract-evidence/_doc/alarm-1?refresh=true", "PUT",
                "{\"host\":\"web-1\",\"severity\":\"CRITICAL\"}", 200, 201);
        String bulk = "{\"index\":{\"_index\":\"contract-evidence\",\"_id\":\"alarm-2\"}}\n"
                + "{\"host\":\"web-2\"}\n"
                + "{\"delete\":{\"_index\":\"contract-evidence\",\"_id\":\"missing\"}}\n"
                + "{}\n";
        String bulkResponse = request(base + "/_bulk?refresh=true", "POST", bulk, 200);
        assertThat(bulkResponse).contains("\"errors\":true");
        String count = request(base + "/contract-evidence/_count", "GET", null, 200);
        assertThat(count).contains("\"count\":2");
    }

    private static KafkaConsumer<String, String> consumer(String group, String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private static ConsumerRecords<String, String> pollAtLeast(
            KafkaConsumer<String, String> consumer, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        ConsumerRecords<String, String> records;
        do {
            records = consumer.poll(Duration.ofSeconds(1));
            if (records.count() >= expected) return records;
        } while (System.nanoTime() < deadline);
        return records;
    }

    private static String clickhouse(String sql) throws Exception {
        String auth = java.util.Base64.getEncoder().encodeToString(
                "default:socp".getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(clickhouseUri())
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + auth)
                .POST(HttpRequest.BodyPublishers.ofString(sql))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("ClickHouse response for %s", sql)
                .isEqualTo(200);
        return response.body().trim();
    }

    private static URI clickhouseUri() {
        return URI.create("http://" + CLICKHOUSE.getHost() + ":"
                + CLICKHOUSE.getMappedPort(8123) + "/");
    }

    private static String request(String url, String method, String body, int... statuses) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30));
        if ("GET".equals(method)) builder.GET();
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(java.util.Arrays.stream(statuses).boxed().toList())
                .as("OpenSearch response for %s", url)
                .contains(response.statusCode());
        return response.body();
    }
}
