package com.socp.soc.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
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
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 审计 Kafka 消费者（审计链路收口）：订阅各服务 {@code @AuditOperation} 发到
 * {@code socp-audit} 主题的审计记录，落库 {@code t_audit}（PG/H2）。
 *
 * <p>可靠性约定与事件总线一致：手动 commit（处理完才提交）+ LRU 幂等去重 +
 * 解析/落库失败写 DLQ（socp-audit-dlq），不静默丢弃。
 * 时间戳兼容秒/毫秒/ISO（&lt;1e11 判秒），兼容不同发端的序列化差异。
 */
@Component
public class AuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEDUP_MAX = 100_000;

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.audit-topic:socp-audit}")
    private String topic;

    @Value("${socp.kafka.audit-enabled:true}")
    private boolean enabled;

    private final AuditRepository repository;

    public AuditConsumer(AuditRepository repository) {
        this.repository = repository;
    }

    /** 已处理消息去重缓存（LRU：满则清空重建，配合至少一次语义实现幂等） */
    private static final Set<String> DEDUP = Collections.synchronizedSet(new LinkedHashSet<>() {
        @Override
        public boolean add(String e) {
            if (size() >= DEDUP_MAX) clear();
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

    /** 处理失败 → 写入 DLQ 主题，不静默丢弃 */
    private void toDlq(String key, String raw) {
        try {
            dlq().send(new ProducerRecord<>(topic + "-dlq",
                    key == null ? "unknown" : key, raw));
        } catch (Exception ex) {
            log.warn("审计 DLQ 写入失败 key={}: {}", key, ex.getMessage());
        }
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        Thread.ofPlatform().name("audit-consumer").daemon(true).start(() -> run());
        log.info("审计 Kafka 消费者已启动 bootstrap={} topic={}", bootstrap, topic);
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "socp-audit-sink");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        try {
            consumer.subscribe(List.of(topic));
            while (!Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofMillis(500));
                } catch (Exception ex) {
                    log.warn("审计 Kafka poll 异常（重试）: {}", ex.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                if (records.isEmpty()) continue;
                for (ConsumerRecord<String, String> rec : records) {
                    String key = rec.key();
                    try {
                        String dedupKey = (key == null ? "" : key) + ":" + rec.value().hashCode();
                        if (!DEDUP.add(dedupKey)) continue; // 幂等去重
                        AuditEntity entity = parse(rec.value());
                        if (entity != null) {
                            repository.save(entity);
                        }
                    } catch (Exception ex) {
                        log.warn("审计落库失败 key={}（写 DLQ）: {}", key, ex.getMessage());
                        toDlq(key, rec.value());
                    }
                }
                try {
                    consumer.commitSync();
                } catch (Exception ex) {
                    log.warn("审计 offset 提交失败（下次重放一批）: {}", ex.getMessage());
                }
            }
        } finally {
            try { consumer.close(); } catch (Exception ignored) { }
        }
    }

    /** JSON → AuditEntity；timestamp 兼容 秒/毫秒/ISO（&lt;1e11 判秒）。 */
    @SuppressWarnings("unchecked")
    private AuditEntity parse(String raw) throws Exception {
        Map<String, Object> m = MAPPER.readValue(raw, Map.class);
        String tenantId = str(m.get("tenantId"));
        String action = str(m.get("action"));
        String operator = str(m.get("operator"));
        String target = str(m.get("target"));
        String result = str(m.get("result"));
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("audit 消息缺少 action: " + raw);
        }
        return new AuditEntity(
                tenantId == null || tenantId.isBlank() ? "default" : tenantId,
                action,
                operator == null || operator.isBlank() ? "system" : operator,
                target,
                result == null || result.isBlank() ? "OK" : result,
                parseTs(m.get("timestamp")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Instant parseTs(Object ts) {
        if (ts == null) return Instant.now();
        if (ts instanceof Number n) {
            long v = n.longValue();
            return v < 100_000_000_000L ? Instant.ofEpochSecond(v) : Instant.ofEpochMilli(v);
        }
        return Instant.parse(String.valueOf(ts));
    }
}
