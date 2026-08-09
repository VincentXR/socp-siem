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

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "socp-detect");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(List.of(topic));
            while (true) {
                var recs = c.poll(Duration.ofMillis(500));
                for (var r : recs) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = MAPPER.readValue(r.value(), Map.class);
                        SecurityEvent ev = toEvent(m);
                        engine.ingest(ev);
                    } catch (Exception ex) {
                        log.debug("Kafka 事件解析失败（丢弃）: {}", ex.getMessage());
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
