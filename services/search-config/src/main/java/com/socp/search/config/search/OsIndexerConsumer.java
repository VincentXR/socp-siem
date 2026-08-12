package com.socp.search.config.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * OpenSearch 索引消费者（2026-08-12，P2 可重放主链）。
 *
 * <p>采集主链从「ingest 双写 Kafka+OS」改为「ingest 只发 Kafka，本消费者把 canonical
 * 事件写 OpenSearch」——OS 挂了恢复后可从 Kafka 重放重建索引，不再依赖跨系统双写的一致。
 *
 * <p>可靠性对齐 detect-web {@code KafkaEventConsumer}：原生 KafkaConsumer 手动 commit（至少一次）
 * + LRU 去重（幂等）+ 失败写 DLQ（socp-events-dlq）+ W3C traceparent 透传。
 */
@Component
public class OsIndexerConsumer {

    private static final Logger log = LoggerFactory.getLogger(OsIndexerConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.topic:socp-events}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    @Value("${socp.os-indexer.enabled:true}")
    private boolean indexerEnabled;

    private final OsEventWriter osWriter;

    public OsIndexerConsumer(OsEventWriter osWriter) {
        this.osWriter = osWriter;
    }

    /** 已处理 eventId 去重缓存（LRU，最多 10 万；配合至少一次语义实现幂等） */
    private static final java.util.Set<String> DEDUP = java.util.Collections.synchronizedSet(
            new java.util.LinkedHashSet<>() {
                @Override
                public boolean add(String e) {
                    if (size() >= 100_000) clear();
                    return super.add(e);
                }
            });

    private volatile KafkaProducer<String, String> dlqProducer;

    private KafkaProducer<String, String> dlq() {
        KafkaProducer<String, String> p = dlqProducer;
        if (p == null) {
            synchronized (this) {
                if (dlqProducer == null) {
                    Properties props = new Properties();
                    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.ACKS_CONFIG, "all");
                    dlqProducer = new KafkaProducer<>(props);
                }
                p = dlqProducer;
            }
        }
        return p;
    }

    private void toDlq(String eventId, String raw) {
        try {
            dlq().send(new ProducerRecord<>(topic + "-dlq",
                    eventId == null ? "unknown" : eventId, raw));
        } catch (Exception ex) {
            log.warn("DLQ 写入失败 eventId={}: {}", eventId, ex.getMessage());
        }
    }

    @PostConstruct
    public void start() {
        if (!enabled || !indexerEnabled) return;
        Thread.ofPlatform().name("os-indexer").daemon(true).start(() -> run());
        log.info("OpenSearch 索引消费者已启动 bootstrap={} topic={}（P2 可重放主链）", bootstrap, topic);
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "socp-os-indexer");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(List.of(topic));
            while (true) {
                var recs = c.poll(Duration.ofMillis(500));
                for (var r : recs) {
                    String raw = r.value();
                    String eventId = null;
                    String tp = null;
                    try {
                        var h = r.headers().lastHeader("traceparent");
                        if (h != null) tp = new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                    }
                    String restored = tp == null ? null : com.socp.platform.obs.TraceIdFilter.parseTraceId(tp);
                    if (restored != null) org.slf4j.MDC.put("traceId", restored);
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = MAPPER.readValue(raw, Map.class);
                        eventId = String.valueOf(m.getOrDefault("eventId", r.key()));
                        if (eventId != null && !"null".equals(eventId) && !DEDUP.add(eventId)) {
                            continue;
                        }
                        osWriter.writeEvents(List.of(toEvent(m)));
                    } catch (Exception ex) {
                        log.warn("OS 索引事件解析失败 → DLQ: {}", ex.getMessage());
                        toDlq(eventId, raw);
                    } finally {
                        org.slf4j.MDC.remove("traceId");
                    }
                }
                if (!recs.isEmpty()) {
                    try {
                        c.commitSync();
                    } catch (Exception ex) {
                        log.warn("Kafka commit 失败（下轮重试）: {}", ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("OS indexer consumer 退出: {}", e.getMessage());
        }
    }

    private static SearchEvent toEvent(Map<String, Object> m) {
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) m.getOrDefault("fields", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, String> ecs = (Map<String, String>) m.getOrDefault("ecs", Map.of());
        java.time.Instant ts = java.time.Instant.now();
        try {
            ts = java.time.Instant.parse(String.valueOf(m.getOrDefault("timestamp", ts)));
        } catch (Exception ignored) {
        }
        return new SearchEvent(ts,
                String.valueOf(m.getOrDefault("source", "unknown")),
                String.valueOf(m.getOrDefault("host", "unknown")),
                String.valueOf(m.getOrDefault("severity", "INFO")),
                String.valueOf(m.getOrDefault("msg", "")),
                fields == null ? Map.of() : fields,
                ecs == null ? Map.of() : ecs);
    }
}
