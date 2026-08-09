package com.socp.detect.web.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Kafka 事件消费者（事件总线接线）：订阅 SEARCH 采集管线的 `socp-events` 主题，
 * 消费归一化事件喂入规则引擎。与 HTTP ingest 端点并存（两条入口最终都进引擎队列）。
 */
@Component
public class KafkaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.topic:socp-events}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private final DetectEngineService engine;

    public KafkaEventConsumer(DetectEngineService engine) {
        this.engine = engine;
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        Thread.ofPlatform().name("kafka-consumer").daemon(true).start(() -> run());
        log.info("Kafka 事件消费者已启动 bootstrap={} topic={}", bootstrap, topic);
    }

    // ---- 可靠性（2026-08-10）----
    /** 已处理 eventId 去重缓存（LRU，最多 10 万条；配合"至少一次"语义实现幂等） */
    private static final java.util.Set<String> DEDUP = java.util.Collections.synchronizedSet(
            new java.util.LinkedHashSet<>() {
                @Override
                public boolean add(String e) {
                    if (size() >= 100_000) clear();
                    return super.add(e);
                }
            });
    private static final int DEDUP_MAX = 100_000;

    private volatile org.apache.kafka.clients.producer.KafkaProducer<String, String> dlqProducer;

    private org.apache.kafka.clients.producer.KafkaProducer<String, String> dlq() {
        org.apache.kafka.clients.producer.KafkaProducer<String, String> p = dlqProducer;
        if (p == null) {
            synchronized (this) {
                if (dlqProducer == null) {
                    Properties props = new Properties();
                    props.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                    props.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                            org.apache.kafka.common.serialization.StringSerializer.class.getName());
                    props.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                            org.apache.kafka.common.serialization.StringSerializer.class.getName());
                    props.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
                    dlqProducer = new org.apache.kafka.clients.producer.KafkaProducer<>(props);
                }
                p = dlqProducer;
            }
        }
        return p;
    }

    /** 解析/处理失败 → 写入 DLQ 主题（socp-events-dlq），不静默丢弃 */
    private void toDlq(String eventId, String raw) {
        try {
            dlq().send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic + "-dlq",
                    eventId == null ? "unknown" : eventId, raw));
        } catch (Exception ex) {
            log.warn("DLQ 写入失败 eventId={}: {}", eventId, ex.getMessage());
        }
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "socp-detect");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        // 手动提交 offset（至少一次语义）：处理完一批再 commit，重启后最多重放一批（配合幂等去重）
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(List.of(topic));
            while (true) {
                var recs = c.poll(Duration.ofMillis(500));
                for (var r : recs) {
                    String raw = r.value();
                    String eventId = null;
                    // trace 上下文透传：从消息 header 恢复 W3C traceparent → MDC（与 HTTP 链路同 traceId）
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
                        // 幂等：已处理的 eventId 跳过（消费重放/重复投递不重复触发规则）
                        if (eventId != null && !"null".equals(eventId) && !DEDUP.add(eventId)) {
                            continue;
                        }
                        SecurityEvent ev = toEvent(m);
                        engine.ingest(ev);
                    } catch (Exception ex) {
                        log.warn("Kafka 事件解析失败 → DLQ: {}", ex.getMessage());
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
            log.warn("Kafka consumer 退出: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static SecurityEvent toEvent(Map<String, Object> m) {
        Map<String, Object> rawFields = (Map<String, Object>) m.getOrDefault("fields", Map.of());
        Map<String, String> fields = new LinkedHashMap<>();
        for (var en : rawFields.entrySet()) fields.put(en.getKey(), String.valueOf(en.getValue()));
        String msg = m.get("msg") == null ? String.valueOf(m.getOrDefault("message", "")) : String.valueOf(m.get("msg"));
        // msg 并入 fields，保证 RuleSpec 的 msg 条件可命中（与 RuleController.toEvent 语义一致）
        if (m.containsKey("msg") && !fields.containsKey("msg")) {
            fields.put("msg", msg);
        }
        Severity severity = Severity.INFO;
        try {
            severity = Severity.valueOf(String.valueOf(m.getOrDefault("severity", "INFO")).toUpperCase());
        } catch (IllegalArgumentException ignored) {
        }
        Instant ts = Instant.now();
        try {
            ts = Instant.parse(String.valueOf(m.getOrDefault("timestamp", ts)));
        } catch (Exception ignored) {
        }
        return new SecurityEvent(ts,
                String.valueOf(m.getOrDefault("source", "unknown")),
                String.valueOf(m.getOrDefault("host", "unknown")),
                msg, fields, severity);
    }
}
