package com.socp.platform.test;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@Timeout(180)
class DlqReplayTransportContainerTest {
    @Container static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(MiddlewareImages.kafka()));
    @TempDir Path temp;

    @Test void realCliExportAndReplayPreserveBytesNullsHeadersAndIndependentObservations() throws Exception {
        String target = "audit-" + UUID.randomUUID();
        String source = target + "-dlq";
        topics(Map.of(target, 2, source, 2));
        var duplicateHeaders = List.<Header>of(new RecordHeader("same", null), new RecordHeader("same", new byte[0]),
                new RecordHeader("same", bytes("a\r\nb")), new RecordHeader("名", new byte[]{0, (byte) 255}));
        var input = new ArrayList<ProducerRecord<byte[], byte[]>>();
        input.add(new ProducerRecord<>(source, 0, null, null, bytes(" {\r\n\t\"eventId\":\"keep-null-key\"\n} "), duplicateHeaders));
        input.add(new ProducerRecord<>(source, 0, null, new byte[0], new byte[0], duplicateHeaders));
        input.add(new ProducerRecord<>(source, 1, null, bytes("tombstone"), null, List.of()));
        input.add(new ProducerRecord<>(source, 1, null, new byte[]{9, 13, 10, 0, (byte) 255}, new byte[]{(byte) 255, 0, 13, 10}, List.of()));
        input.add(new ProducerRecord<>(source, 0, bytes("same-group"), bytes("independent observation")));
        input.add(new ProducerRecord<>(source, 0, bytes("same-group"), bytes("independent observation")));
        String envelope = "{\"originalTopic\":\"" + target + "\",\"partition\":1,\"offset\":42,"
                + "\"key\":\"original\\tkey\\n\",\"eventId\":\"must-not-be-the-key\",\"tenant\":\"acme\","
                + "\"schemaVersion\":\"1\",\"reasonCode\":\"bad\",\"reason\":\"fixture\","
                + "\"originalPayload\":\" {\\n  \\\"message\\\":\\\"中文\\\"\\n} \",\"failedAt\":\"2026-09-21T00:00:00Z\"}";
        input.add(new ProducerRecord<>(source, 0, bytes("wrapper-key"), bytes(envelope)));
        publish(input);
        Path config = temp.resolve("consumer.properties");
        Files.writeString(config, "enable.auto.commit=true\ngroup.id=must-not-commit\n"
                + "key.deserializer=org.apache.kafka.common.serialization.StringDeserializer\n"
                + "value.deserializer=org.apache.kafka.common.serialization.StringDeserializer\n");
        Path capture = temp.resolve("capture.json");
        var exported = cli("--topic", source, "--export-file", capture.toString(), "--consumer-config", config.toString());
        assertEquals(0, exported.code(), exported.error());
        assertTrue(Files.size(capture) > 0);
        var replayed = cli("--topic", source, "--input-file", capture.toString(), "--include-tombstones");
        assertEquals(0, replayed.code(), replayed.error());
        var actual = read(target, 2, input.size());
        Map<String, Integer> expected = new HashMap<>();
        for (int index = 0; index < input.size() - 1; index++) {
            var row = input.get(index);
            expected.merge(signature(row.key(), row.value(), row.headers().toArray()), 1, Integer::sum);
        }
        expected.merge(signature(bytes("original\tkey\n"), bytes(" {\n  \"message\":\"中文\"\n} "), new Header[0]), 1, Integer::sum);
        Map<String, Integer> observed = new HashMap<>();
        for (var row : actual) observed.merge(signature(row.key(), row.value(), row.headers().toArray()), 1, Integer::sum);
        assertEquals(expected, observed);
        assertEquals(1, actual.stream().filter(row -> Arrays.equals(row.key(), bytes("original\tkey\n"))).findFirst().orElseThrow().partition());
        try (var admin = AdminClient.create(properties())) {
            assertTrue(admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS).stream()
                    .noneMatch(group -> group.groupId().equals("must-not-commit")));
        }
    }

    @Test void inspectionHonorsExplicitPartitionOffsetAndLimit() throws Exception {
        String source = "cursor-" + UUID.randomUUID() + "-dlq";
        topics(Map.of(source, 2));
        publish(List.of(new ProducerRecord<>(source, 0, bytes("zero"), bytes("0")),
                new ProducerRecord<>(source, 0, bytes("one"), bytes("1")),
                new ProducerRecord<>(source, 0, bytes("two"), bytes("2")),
                new ProducerRecord<>(source, 1, bytes("other-partition"), bytes("3"))));
        var result = cli("--topic", source, "--partition", "0", "--offset", "1", "--limit", "1", "--dry-run");
        assertEquals(0, result.code(), result.error());
        assertTrue(result.output().contains("key=b'one'"));
        assertFalse(result.output().contains("key=b'zero'"));
        assertFalse(result.output().contains("key=b'two'"));
        assertFalse(result.output().contains("other-partition"));
    }

    @Test void emptyTopicSucceedsButUnavailableSourceAndAbsentTargetFailWithoutAutoCreation() throws Exception {
        String source = "missing-target-" + UUID.randomUUID() + "-dlq";
        String target = source.substring(0, source.length() - 4);
        topics(Map.of(source, 1));
        var empty = cli("--topic", source, "--dry-run");
        assertEquals(0, empty.code(), empty.error());
        var missing = cli("--topic", "absent-" + UUID.randomUUID(), "--timeout-ms", "1000", "--dry-run");
        assertEquals(1, missing.code());
        assertFalse(missing.error().contains("nothing to replay"));
        publish(List.of(new ProducerRecord<>(source, bytes("key"), bytes("value"))));
        var refused = cli("--topic", source, "--timeout-ms", "3000");
        assertEquals(1, refused.code());
        assertFalse(refused.error().contains("broker acknowledged"));
        try (var admin = AdminClient.create(properties())) {
            assertFalse(admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(target));
        }
    }

    private Result cli(String... options) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("build/replay-dlq.py"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("repository root not found");
        }
        var command = new ArrayList<String>(List.of(System.getenv().getOrDefault("PYTHON", "python"),
                root.resolve("build/replay-dlq.py").toString(), "--bootstrap", KAFKA.getBootstrapServers(),
                "--kafka-classpath", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "--java", Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                "--timeout-ms", "15000"));
        command.addAll(List.of(options));
        Path output = temp.resolve(UUID.randomUUID() + ".out"), error = temp.resolve(UUID.randomUUID() + ".err");
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(50, TimeUnit.SECONDS), "CLI did not exit within its process bound");
            return new Result(process.exitValue(), Files.readString(output), Files.readString(error));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private static Properties properties() {
        var props = new Properties();
        props.setProperty("bootstrap.servers", KAFKA.getBootstrapServers());
        props.setProperty("default.api.timeout.ms", "15000");
        props.setProperty("request.timeout.ms", "3000");
        return props;
    }
    private static void topics(Map<String, Integer> topics) throws Exception {
        try (var admin = AdminClient.create(properties())) {
            admin.createTopics(topics.entrySet().stream().map(entry -> new NewTopic(entry.getKey(), entry.getValue(), (short) 1)).toList())
                    .all().get(15, TimeUnit.SECONDS);
        }
    }
    private static void publish(List<ProducerRecord<byte[], byte[]>> rows) throws Exception {
        var props = properties();
        props.setProperty("key.serializer", ByteArraySerializer.class.getName());
        props.setProperty("value.serializer", ByteArraySerializer.class.getName());
        props.setProperty("acks", "all");
        try (var producer = new KafkaProducer<byte[], byte[]>(props)) {
            for (var row : rows) producer.send(row).get(15, TimeUnit.SECONDS);
        }
    }
    private static List<ConsumerRecord<byte[], byte[]>> read(String topic, int partitions, int count) {
        var props = properties();
        props.setProperty("key.deserializer", ByteArrayDeserializer.class.getName());
        props.setProperty("value.deserializer", ByteArrayDeserializer.class.getName());
        props.setProperty("enable.auto.commit", "false");
        try (var consumer = new KafkaConsumer<byte[], byte[]>(props)) {
            var assignment = java.util.stream.IntStream.range(0, partitions).mapToObj(index -> new TopicPartition(topic, index)).toList();
            consumer.assign(assignment); consumer.seekToBeginning(assignment);
            var rows = new ArrayList<ConsumerRecord<byte[], byte[]>>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (rows.size() < count && System.nanoTime() < deadline) consumer.poll(Duration.ofMillis(100)).forEach(rows::add);
            assertEquals(count, rows.size());
            return rows;
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String signature(byte[] key, byte[] value, Header[] headers) {
        var out = new StringBuilder(encoded(key)).append('|').append(encoded(value));
        for (var header : headers) out.append('|').append(encoded(bytes(header.key()))).append(':').append(encoded(header.value()));
        return out.toString();
    }
    private static String encoded(byte[] value) { return value == null ? "null" : Base64.getEncoder().encodeToString(value); }
    private record Result(int code, String output, String error) { }
}
