import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Byte transport for replay-dlq.py. Run with Java 21 source-file mode and Kafka client jars. */
public final class DlqTransport {
    private static final java.io.PrintStream TRANSPORT_OUT = System.out;
    static final byte[] MAGIC = "SOCPDLQ1".getBytes(StandardCharsets.US_ASCII);
    static final int MAX_RECORD = 4 * 1024 * 1024;
    static final int MAX_BATCH = 64 * 1024 * 1024;
    static final int MAX_COUNT = 10_000;
    static final int MAX_HEADERS = 256;
    static final int MAX_BATCH_HEADERS = 4096;

    record Row(String topic, int partition, long offset, long timestamp, byte[] key, byte[] value, List<Header> headers) { }

    public static void main(String[] args) {
        // Logging bindings supplied with Kafka jars may target System.out. Keep
        // diagnostics off the binary/ack channel, regardless of logging provider.
        System.setOut(System.err);
        try {
            if (args.length != 8) throw new IllegalArgumentException("transport argument count");
            String mode = args[0], bootstrap = args[1], topic = args[2];
            int limit = Integer.parseInt(args[3]), timeout = Integer.parseInt(args[4]);
            if (limit < 1 || limit > MAX_COUNT || timeout < 1000 || timeout > 120000) throw new IllegalArgumentException("bounds");
            if (!topic.matches("[A-Za-z0-9._-]{1,249}") || topic.equals(".") || topic.equals("..")) {
                throw new IllegalArgumentException("topic");
            }
            Properties properties = new Properties();
            if (!args[5].equals("-")) {
                if (Files.size(Path.of(args[5])) > 1024 * 1024) throw new IllegalArgumentException("config size");
                try (var input = Files.newInputStream(Path.of(args[5]))) { properties.load(input); }
            }
            properties.setProperty("bootstrap.servers", bootstrap);
            properties.setProperty("request.timeout.ms", Integer.toString(Math.min(timeout, 3000)));
            properties.setProperty("default.api.timeout.ms", Integer.toString(timeout));
            properties.setProperty("client.id", "socp-dlq-" + mode);
            properties.setProperty("interceptor.classes", "");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            if (mode.equals("consume")) {
                consume(properties, topic, limit, deadline, Integer.parseInt(args[6]), Long.parseLong(args[7]));
            } else if (mode.equals("produce")) {
                produce(properties, topic, limit, deadline, timeout);
            } else throw new IllegalArgumentException("mode");
        } catch (Exception failure) {
            // Do not print payloads, JAAS configuration or exception messages containing credentials.
            System.err.println("[transport-failure] " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    static Duration remaining(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException("transport deadline");
        return Duration.ofNanos(nanos);
    }

    static void consume(Properties props, String topic, int limit, long deadline, int partition, long offset) throws Exception {
        props.setProperty("key.deserializer", ByteArrayDeserializer.class.getName());
        props.setProperty("value.deserializer", ByteArrayDeserializer.class.getName());
        props.setProperty("enable.auto.commit", "false");
        props.setProperty("allow.auto.create.topics", "false");
        props.setProperty("auto.offset.reset", "none");
        props.setProperty("isolation.level", "read_committed");
        props.setProperty("max.poll.records", Integer.toString(Math.min(100, limit)));
        props.setProperty("max.partition.fetch.bytes", Integer.toString(MAX_RECORD));
        props.setProperty("fetch.max.bytes", Integer.toString(MAX_RECORD));
        props.remove("group.id");
        props.remove("group.instance.id");
        var consumer = new KafkaConsumer<byte[], byte[]>(props);
        var rows = new ArrayList<Row>();
        try {
            var infos = consumer.partitionsFor(topic, remaining(deadline));
            if (infos.isEmpty() || infos.size() > 1024) throw new IllegalArgumentException("partition count");
            var partitions = infos.stream().map(info -> new TopicPartition(topic, info.partition()))
                    .filter(tp -> partition < 0 || tp.partition() == partition)
                    .sorted(Comparator.comparingInt(TopicPartition::partition)).toList();
            if (partitions.isEmpty()) throw new IllegalArgumentException("partition absent");
            consumer.assign(partitions);
            var begins = consumer.beginningOffsets(partitions, remaining(deadline));
            var ends = consumer.endOffsets(partitions, remaining(deadline));
            for (var tp : partitions) {
                long start = offset < 0 ? begins.get(tp) : offset;
                if (start < begins.get(tp) || start > ends.get(tp)) throw new IllegalArgumentException("offset outside retained snapshot");
                consumer.seek(tp, start);
            }
            long bytes = 0;
            int headerCount = 0;
            while (rows.size() < limit) {
                boolean complete = true;
                for (var tp : partitions) {
                    if (consumer.position(tp, remaining(deadline)) < ends.get(tp)) complete = false;
                    else consumer.pause(List.of(tp));
                }
                if (complete) break;
                var polled = consumer.poll(Duration.ofMillis(Math.min(500, Math.max(1, remaining(deadline).toMillis()))));
                for (ConsumerRecord<byte[], byte[]> record : polled) {
                    if (record.offset() >= ends.get(new TopicPartition(record.topic(), record.partition()))) continue;
                    Row row = new Row(record.topic(), record.partition(), record.offset(), record.timestamp(),
                            record.key(), record.value(), Arrays.asList(record.headers().toArray()));
                    bytes += validate(row);
                    headerCount += row.headers().size();
                    if (bytes > MAX_BATCH || headerCount > MAX_BATCH_HEADERS) throw new IllegalArgumentException("batch size exceeds limit");
                    rows.add(row);
                    if (rows.size() == limit) break;
                }
            }
        } finally { consumer.close(Duration.ofSeconds(1)); }
        writeRows(rows);
    }

    static void produce(Properties props, String topic, int limit, long deadline, int timeout) throws Exception {
        var rows = readRows(); // Validate the complete input before any publish.
        if (rows.size() > limit) throw new IllegalArgumentException("record limit");
        for (var row : rows) if (!row.topic().equals(topic)) throw new IllegalArgumentException("target mismatch");
        // Admin metadata lookup does not auto-create a mistyped target topic.
        var admin = AdminClient.create(props);
        try {
            var description = admin.describeTopics(List.of(topic)).allTopicNames()
                    .get(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS).get(topic);
            for (var row : rows) {
                if (row.partition() >= description.partitions().size()) throw new IllegalArgumentException("target partition absent");
            }
        } finally { admin.close(Duration.ofSeconds(1)); }
        props.setProperty("key.serializer", ByteArraySerializer.class.getName());
        props.setProperty("value.serializer", ByteArraySerializer.class.getName());
        props.setProperty("acks", "all");
        props.setProperty("enable.idempotence", "true");
        props.setProperty("max.in.flight.requests.per.connection", "1");
        props.setProperty("retries", Integer.toString(Integer.MAX_VALUE));
        props.setProperty("linger.ms", "0");
        props.setProperty("delivery.timeout.ms", Integer.toString(timeout));
        props.setProperty("max.block.ms", Integer.toString(timeout));
        props.setProperty("max.request.size", Integer.toString(MAX_RECORD + 65536));
        props.setProperty("buffer.memory", Integer.toString(MAX_RECORD + 65536));
        props.remove("transactional.id");
        var producer = new KafkaProducer<byte[], byte[]>(props);
        try {
            for (var row : rows) {
                producer.send(new ProducerRecord<>(topic, row.partition() < 0 ? null : row.partition(),
                        null, row.key(), row.value(), row.headers()))
                        .get(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS);
            }
        } finally { producer.close(Duration.ZERO); }
        TRANSPORT_OUT.write(("ACK\t" + rows.size() + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    static long validate(Row row) {
        if (row.headers().size() > MAX_HEADERS) throw new IllegalArgumentException("header count");
        long length = row.topic().getBytes(StandardCharsets.UTF_8).length + size(row.key()) + size(row.value());
        for (var header : row.headers()) {
            if (header.key().getBytes(StandardCharsets.UTF_8).length > 256) throw new IllegalArgumentException("header name size");
            length += header.key().getBytes(StandardCharsets.UTF_8).length + size(header.value());
        }
        if (length > MAX_RECORD) throw new IllegalArgumentException("record bytes exceed limit");
        return length;
    }
    static int size(byte[] value) { return value == null ? 0 : value.length; }

    static void writeRows(List<Row> rows) throws IOException {
        var output = new DataOutputStream(TRANSPORT_OUT);
        output.write(MAGIC); output.writeInt(rows.size());
        for (var row : rows) {
            writeBytes(output, row.topic().getBytes(StandardCharsets.UTF_8));
            output.writeInt(row.partition()); output.writeLong(row.offset()); output.writeLong(row.timestamp());
            writeBytes(output, row.key()); writeBytes(output, row.value());
            output.writeInt(row.headers().size());
            for (var header : row.headers()) {
                writeBytes(output, header.key().getBytes(StandardCharsets.UTF_8)); writeBytes(output, header.value());
            }
        }
        output.flush();
    }
    static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value == null ? -1 : value.length);
        if (value != null) output.write(value);
    }
    static List<Row> readRows() throws IOException {
        var input = new DataInputStream(System.in);
        if (!Arrays.equals(MAGIC, input.readNBytes(MAGIC.length))) throw new IllegalArgumentException("magic");
        int count = input.readInt();
        if (count < 0 || count > MAX_COUNT) throw new IllegalArgumentException("count");
        var rows = new ArrayList<Row>();
        long total = 0;
        int totalHeaders = 0;
        for (int i = 0; i < count; i++) {
            String topic = new String(required(readBytes(input, 249)), StandardCharsets.UTF_8);
            int partition = input.readInt(); long offset = input.readLong(), timestamp = input.readLong();
            byte[] key = readBytes(input), value = readBytes(input);
            int headerCount = input.readInt();
            if (headerCount < 0 || headerCount > MAX_HEADERS) throw new IllegalArgumentException("headers");
            totalHeaders += headerCount;
            if (totalHeaders > MAX_BATCH_HEADERS) throw new IllegalArgumentException("batch headers");
            var headers = new ArrayList<Header>();
            long rowBytes = size(key) + size(value) + topic.getBytes(StandardCharsets.UTF_8).length;
            if (rowBytes > MAX_RECORD) throw new IllegalArgumentException("record bytes");
            for (int h = 0; h < headerCount; h++) {
                byte[] name = required(readBytes(input, 256));
                byte[] content = readBytes(input, (int) Math.max(0, MAX_RECORD - rowBytes - name.length));
                rowBytes += name.length + size(content);
                if (rowBytes > MAX_RECORD) throw new IllegalArgumentException("record bytes");
                headers.add(new RecordHeader(new String(name, StandardCharsets.UTF_8), content));
            }
            var row = new Row(topic, partition, offset, timestamp, key, value, headers);
            if (partition < -1 || offset < -1 || timestamp < -1) throw new IllegalArgumentException("coordinates");
            total += validate(row);
            if (total > MAX_BATCH) throw new IllegalArgumentException("batch bytes");
            rows.add(row);
        }
        if (input.read() != -1) throw new IllegalArgumentException("trailing transport bytes");
        return rows;
    }
    static byte[] required(byte[] value) {
        if (value == null) throw new IllegalArgumentException("required field");
        return value;
    }
    static byte[] readBytes(DataInputStream input) throws IOException {
        return readBytes(input, MAX_RECORD);
    }
    static byte[] readBytes(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length == -1) return null;
        if (length < 0 || length > maximum) throw new IllegalArgumentException("field length");
        byte[] value = input.readNBytes(length);
        if (value.length != length) throw new EOFException("truncated field");
        return value;
    }
}
